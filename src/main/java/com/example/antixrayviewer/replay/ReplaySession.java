package com.example.antixrayviewer.replay;

import com.example.antixrayviewer.AntiXrayViewer;
import com.example.antixrayviewer.data.PlayerRecording;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Сессия воспроизведения записи для одного зрителя.
 *
 * Ключевые отличия от старой версии:
 * <ul>
 *   <li>время — в миллисекундах, а не в индексах кадров: появилась перемотка, скорости,
 *       обратное воспроизведение и отрезки;</li>
 *   <li>блоки синхронизируются инкрементально и только для зрителя;</li>
 *   <li>все эффекты (частицы, звуки, трещины) отправляются лично зрителю.</li>
 * </ul>
 */
public final class ReplaySession {

    private static final long TICK_MS = 50L;
    private static final int MAX_BACKWARD_STEPS = 512;
    private static final int TIMELINE_CELLS = 32;

    private final AntiXrayViewer plugin;
    private final Player viewer;
    private final PlayerRecording recording;
    private final ReplayTimeline timeline;
    private final VirtualBlockView blocks;
    private final ReplayCamera camera;

    private final boolean particlesEnabled;
    private final boolean soundsEnabled;
    private final double maxSpeed;
    private final double breakAnimationDistanceSq;

    private final Map<BlockRef, Integer> activeDamage = new HashMap<>();
    private final Set<BlockRef> damageScratch = new HashSet<>();

    private BukkitTask task;
    private BossBar bossBar;

    private long clock;
    private double speed;
    private boolean paused;
    private boolean loop;
    private long rangeStart;
    private long rangeEnd;
    private int cursor;
    private int tickCounter;
    private boolean stopped;

    private Location returnLocation;
    private GameMode returnGameMode;
    private boolean restoringGameMode;

    public ReplaySession(AntiXrayViewer plugin, Player viewer, PlayerRecording recording) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.recording = recording;
        this.timeline = new ReplayTimeline(recording);

        int updatesPerTick = plugin.getConfig().getInt("replay.performance.block-updates-per-tick", 256);
        double renderDistance = plugin.getConfig().getDouble("replay.performance.block-render-distance", 96.0);
        double breakDistance = plugin.getConfig().getDouble("replay.performance.break-animation-distance", 48.0);
        this.blocks = new VirtualBlockView(plugin, viewer, updatesPerTick, renderDistance);
        this.breakAnimationDistanceSq = breakDistance * breakDistance;

        CameraMode defaultMode = CameraMode.parse(
                plugin.getConfig().getString("replay.camera.default-mode", "FIRST_PERSON"), CameraMode.FIRST_PERSON);
        double smoothing = plugin.getConfig().getDouble("replay.camera.smoothing", 0.35);
        double thirdPerson = plugin.getConfig().getDouble("replay.camera.third-person-distance", 4.0);
        boolean showAvatar = plugin.getConfig().getBoolean("replay.camera.show-avatar", true);
        this.camera = new ReplayCamera(plugin, viewer, timeline, recording.getPlayerId(), recording.getPlayerName(),
                defaultMode, smoothing, thirdPerson, showAvatar);

        this.particlesEnabled = plugin.getConfig().getBoolean("replay.playback.particles", true);
        this.soundsEnabled = plugin.getConfig().getBoolean("replay.playback.sounds", true);
        this.maxSpeed = Math.max(1.0, plugin.getConfig().getDouble("replay.playback.max-speed", 8.0));
        this.speed = clampSpeed(plugin.getConfig().getDouble("replay.playback.default-speed", 1.0));

        this.rangeStart = 0L;
        this.rangeEnd = timeline.getDuration();
    }

    // ===================== Жизненный цикл =====================

    public void start() {
        returnLocation = viewer.getLocation().clone();
        returnGameMode = viewer.getGameMode();

        if (viewer.getGameMode() != GameMode.SPECTATOR) {
            restoringGameMode = true;
            viewer.setGameMode(GameMode.SPECTATOR);
            restoringGameMode = false;
        }

        clock = rangeStart;
        applyFullState(clock);
        camera.update(clock, true);

        bossBar = BossBar.bossBar(Component.text("Запись"), 0f, BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_20);
        viewer.showBossBar(bossBar);

        updateFooter();
        sendPanel();

        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        stop(true);
    }

    public void stop(boolean notify) {
        if (stopped) {
            return;
        }
        stopped = true;

        if (task != null) {
            task.cancel();
            task = null;
        }

        clearAllBreakAnimations();
        blocks.restore();
        camera.cleanup();

        if (viewer.isOnline()) {
            if (bossBar != null) {
                viewer.hideBossBar(bossBar);
            }
            viewer.sendActionBar(Component.empty());
            viewer.sendPlayerListFooter(Component.empty());

            if (returnGameMode != null && viewer.getGameMode() != returnGameMode) {
                restoringGameMode = true;
                viewer.setGameMode(returnGameMode);
                restoringGameMode = false;
            }
            if (returnLocation != null && returnLocation.getWorld() != null) {
                viewer.teleport(returnLocation);
            }
            if (notify) {
                viewer.sendMessage(Component.text("■ Воспроизведение завершено.", NamedTextColor.GRAY));
            }
        }
    }

    public boolean isStopped() {
        return stopped;
    }

    public boolean isInternalGameModeChange() {
        return restoringGameMode;
    }

    // ===================== Основной цикл =====================

    private void tick() {
        if (stopped) {
            return;
        }
        if (!viewer.isOnline()) {
            stop(false);
            return;
        }

        if (!paused) {
            long previous = clock;
            clock += (long) Math.round(TICK_MS * speed);

            if (clock >= rangeEnd) {
                if (loop) {
                    seek(rangeStart, true);
                    previous = clock;
                } else {
                    clock = rangeEnd;
                    paused = true;
                }
            } else if (clock <= rangeStart) {
                if (loop) {
                    seek(rangeEnd, true);
                    previous = clock;
                } else {
                    clock = rangeStart;
                    paused = true;
                }
            }

            syncBlocks(previous, clock, false);
        }

        camera.update(clock, false);
        updateBreakAnimations();
        blocks.flush();

        if (++tickCounter % 4 == 0) {
            updateHud();
        }
    }

    // ===================== Управление воспроизведением =====================

    public void setPaused(boolean value) {
        this.paused = value;
        updateHud();
    }

    public boolean isPaused() {
        return paused;
    }

    public void togglePause() {
        setPaused(!paused);
    }

    public double getSpeed() {
        return speed;
    }

    public void setSpeed(double value) {
        this.speed = clampSpeed(value);
        updateHud();
    }

    private double clampSpeed(double value) {
        double abs = Math.abs(value);
        if (abs < 0.1) {
            abs = 0.1;
        } else if (abs > maxSpeed) {
            abs = maxSpeed;
        }
        return value < 0 ? -abs : abs;
    }

    /** Перемотка к абсолютному времени записи (мс). */
    public void seek(long target) {
        seek(target, false);
    }

    private void seek(long target, boolean silent) {
        long previous = clock;
        clock = Math.max(rangeStart, Math.min(rangeEnd, target));

        long distance = Math.abs(clock - previous);
        if (distance > 2000L) {
            applyFullState(clock);
        } else {
            syncBlocks(previous, clock, true);
        }

        clearAllBreakAnimations();
        camera.update(clock, true);
        blocks.flush();
        if (!silent) {
            updateHud();
        }
    }

    /** Относительный прыжок: отрицательное значение — назад. */
    public void jump(long deltaMs) {
        seek(clock + deltaMs);
    }

    public void setRange(long from, long to) {
        long a = Math.max(0L, Math.min(timeline.getDuration(), from));
        long b = Math.max(0L, Math.min(timeline.getDuration(), to));
        this.rangeStart = Math.min(a, b);
        this.rangeEnd = Math.max(Math.min(a, b) + 100L, Math.max(a, b));
        seek(rangeStart);
    }

    public void clearRange() {
        this.rangeStart = 0L;
        this.rangeEnd = timeline.getDuration();
        updateHud();
    }

    public boolean hasRange() {
        return rangeStart > 0L || rangeEnd < timeline.getDuration();
    }

    public long getRangeStart() {
        return rangeStart;
    }

    public long getRangeEnd() {
        return rangeEnd;
    }

    public boolean isLoop() {
        return loop;
    }

    public void setLoop(boolean value) {
        this.loop = value;
        updateHud();
    }

    /** Прыжок к следующему важному событию (добыча руды). */
    public boolean nextMarker() {
        long time = timeline.nextMarkerTime(clock);
        if (time < 0) {
            return false;
        }
        seek(Math.max(rangeStart, time - 1500L));
        return true;
    }

    public boolean previousMarker() {
        long time = timeline.previousMarkerTime(clock);
        if (time < 0) {
            return false;
        }
        seek(Math.max(rangeStart, time - 1500L));
        return true;
    }

    public void setCameraMode(CameraMode mode) {
        camera.setMode(mode, clock);
        updateHud();
    }

    public CameraMode getCameraMode() {
        return camera.getMode();
    }

    public long getClock() {
        return clock;
    }

    public long getDuration() {
        return timeline.getDuration();
    }

    public PlayerRecording getRecording() {
        return recording;
    }

    public ReplayTimeline getTimeline() {
        return timeline;
    }

    public Player getViewer() {
        return viewer;
    }

    /** Мир, в котором сейчас идёт воспроизведение. */
    public String getTimelineWorld() {
        String world = camera.getLastSample().world;
        if (world != null) {
            return world;
        }
        return recording.getFrameCount() > 0 ? recording.getFrame(0).getWorld() : "";
    }

    // ===================== Синхронизация блоков =====================

    private void applyFullState(long time) {
        Map<BlockRef, BlockData> state = timeline.stateAt(time);
        blocks.setAll(state);
        cursor = timeline.deltaIndexFor(time);
    }

    /**
     * Инкрементально довести состояние блоков до момента to.
     */
    private void syncBlocks(long from, long to, boolean seeking) {
        int target = timeline.deltaIndexFor(to);
        if (target == cursor) {
            return;
        }

        boolean effects = !seeking && Math.abs(speed) <= 4.0;

        if (target > cursor) {
            for (int i = cursor; i < target; i++) {
                ReplayTimeline.BlockDelta delta = timeline.getDeltas().get(i);
                blocks.set(delta.ref, delta.to);
                if (effects) {
                    playDeltaEffects(delta);
                }
            }
            cursor = target;
            return;
        }

        // Назад: отменяем дельты в обратном порядке, либо строим состояние заново
        if (cursor - target > MAX_BACKWARD_STEPS) {
            applyFullState(to);
            return;
        }
        for (int i = cursor - 1; i >= target; i--) {
            ReplayTimeline.BlockDelta delta = timeline.getDeltas().get(i);
            blocks.set(delta.ref, delta.from);
        }
        cursor = target;
    }

    private void playDeltaEffects(ReplayTimeline.BlockDelta delta) {
        if (!viewer.isOnline()) {
            return;
        }
        World world = viewer.getWorld();
        if (!world.getName().equals(delta.ref.getWorld())) {
            return;
        }
        Location center = delta.ref.toCenterLocation(world);
        if (center.distanceSquared(viewer.getLocation()) > breakAnimationDistanceSq) {
            return;
        }

        BlockData visual = delta.destructive ? delta.from : delta.to;
        if (visual == null) {
            return;
        }

        // ВАЖНО: частицы и звуки отправляются только зрителю.
        // Раньше использовались world.playEffect/world.playSound — их видели и слышали все рядом.
        if (particlesEnabled) {
            viewer.spawnParticle(Particle.BLOCK, center, 16, 0.3, 0.3, 0.3, 0.0, visual);
        }
        if (soundsEnabled) {
            Sound sound = delta.destructive
                    ? visual.getSoundGroup().getBreakSound()
                    : visual.getSoundGroup().getPlaceSound();
            viewer.playSound(center, sound, 0.8f, 0.9f);
        }
    }

    // ===================== Анимация трещин =====================

    private void updateBreakAnimations() {
        if (!viewer.isOnline()) {
            return;
        }
        World world = viewer.getWorld();
        String worldName = world.getName();
        Location viewerLocation = viewer.getLocation();

        damageScratch.clear();

        java.util.List<ReplayTimeline.BreakSpan> spans = timeline.getBreakSpans();
        for (int i = timeline.firstSpanIndexFor(clock); i < spans.size(); i++) {
            ReplayTimeline.BreakSpan span = spans.get(i);
            if (span.start > clock) {
                break;
            }
            if (span.end <= clock) {
                continue;
            }
            if (!worldName.equals(span.ref.getWorld())) {
                continue;
            }
            if (span.ref.distanceSquared(viewerLocation.getX(), viewerLocation.getY(), viewerLocation.getZ())
                    > breakAnimationDistanceSq) {
                continue;
            }

            double progress = (double) (clock - span.start) / (double) Math.max(1L, span.end - span.start);
            int stage = (int) Math.round(Math.max(0.0, Math.min(1.0, progress)) * 9.0);
            if (stage <= 0) {
                stage = 1;
            }

            damageScratch.add(span.ref);
            Integer previous = activeDamage.get(span.ref);
            if (previous == null || previous != stage) {
                viewer.sendBlockDamage(span.ref.toLocation(world), stage / 9.0f);
                activeDamage.put(span.ref, stage);
            }
        }

        // Сбрасываем трещины, которые больше не активны (раньше они зависали на блоках)
        Iterator<Map.Entry<BlockRef, Integer>> iterator = activeDamage.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockRef, Integer> entry = iterator.next();
            if (damageScratch.contains(entry.getKey())) {
                continue;
            }
            if (worldName.equals(entry.getKey().getWorld())) {
                viewer.sendBlockDamage(entry.getKey().toLocation(world), 0f);
            }
            iterator.remove();
        }
    }

    private void clearAllBreakAnimations() {
        if (activeDamage.isEmpty()) {
            return;
        }
        if (viewer.isOnline()) {
            World world = viewer.getWorld();
            String worldName = world.getName();
            for (BlockRef ref : activeDamage.keySet()) {
                if (worldName.equals(ref.getWorld())) {
                    viewer.sendBlockDamage(ref.toLocation(world), 0f);
                }
            }
        }
        activeDamage.clear();
    }

    // ===================== События от менеджера =====================

    public void onChunkSent(int chunkX, int chunkZ) {
        blocks.onChunkSent(chunkX, chunkZ);
    }

    // ===================== Интерфейс =====================

    private void updateHud() {
        if (!viewer.isOnline() || stopped) {
            return;
        }

        long duration = Math.max(1L, timeline.getDuration());
        float progress = (float) Math.max(0.0, Math.min(1.0, (double) clock / (double) duration));

        if (bossBar != null) {
            bossBar.progress(progress);
            bossBar.name(Component.text(recording.getPlayerName() + " — " + formatTime(clock) + " / " + formatTime(duration)
                    + "  " + (paused ? "⏸" : "▶") + " x" + trimSpeed(speed)));
            bossBar.color(paused ? BossBar.Color.YELLOW : BossBar.Color.BLUE);
        }

        StringBuilder status = new StringBuilder();
        status.append(paused ? "⏸ " : "▶ ");
        status.append(formatTime(clock)).append(" / ").append(formatTime(duration));
        status.append("  •  ").append(camera.getMode().getDisplayName());
        status.append("  •  x").append(trimSpeed(speed));
        if (loop) {
            status.append("  •  ↻");
        }
        if (hasRange()) {
            status.append("  •  ").append(formatTime(rangeStart)).append("—").append(formatTime(rangeEnd));
        }
        // Живая шкала времени: обновляется 5 раз в секунду, курсор едет сам
        viewer.sendActionBar(Component.text(liveBar(duration) + "  ", NamedTextColor.AQUA)
                .append(Component.text(status.toString(), NamedTextColor.GRAY)));

        if (tickCounter % 40 == 0) {
            updateFooter();
        }
    }

    /**
     * Постоянная справка в списке игроков (Tab).
     * Не занимает чат, не исчезает и не вытесняет сообщения игроков.
     */
    private void updateFooter() {
        if (!viewer.isOnline() || stopped) {
            return;
        }

        Component footer = Component.text(liveBar(Math.max(1L, timeline.getDuration())), NamedTextColor.AQUA)
                .append(Component.newline())
                .append(Component.text("Запись #" + recording.getId() + " · " + recording.getPlayerName(), NamedTextColor.GOLD))
                .append(Component.newline())
                .append(Component.text(formatTime(clock) + " / " + formatTime(Math.max(1L, timeline.getDuration()))
                        + " · x" + trimSpeed(speed) + " · " + camera.getMode().getDisplayName(), NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text("Панель с кнопками: /axv panel", NamedTextColor.DARK_AQUA));

        viewer.sendPlayerListFooter(footer);
    }

    /**
     * Живая (движущаяся) шкала времени для action bar и Tab.
     *
     * Сообщение в чате после отправки изменить невозможно (протокол не умеет редактировать
     * уже напечатанную строку), поэтому кликабельная полоса — снимок на момент отправки,
     * а бегущая шкала живёт здесь: action bar / boss bar / Tab перерисовываются на месте.
     */
    private String liveBar(long duration) {
        int cells = 24;
        int current = (int) Math.min(cells - 1, Math.max(0L, clock) * cells / Math.max(1L, duration));

        boolean[] markerCells = new boolean[cells];
        for (ReplayTimeline.Marker marker : timeline.getMarkers()) {
            int cell = (int) Math.min(cells - 1, marker.time * cells / Math.max(1L, duration));
            markerCells[cell] = true;
        }

        StringBuilder bar = new StringBuilder(cells + 2);
        for (int i = 0; i < cells; i++) {
            if (i == current) {
                bar.append('█');
            } else if (markerCells[i]) {
                bar.append('▲');
            } else if (i < current) {
                bar.append('━');
            } else {
                bar.append('─');
            }
        }
        return bar.toString();
    }

    /** Одноразово перенести свободную камеру к записанному игроку. */
    public void followPlayer() {
        camera.follow(clock);
        updateHud();
    }

    /** Отметить начало отрезка тем временем, на котором сейчас просмотр. */
    public void setRangeStartHere() {
        long to = hasRange() ? rangeEnd : timeline.getDuration();
        if (to <= clock) {
            to = timeline.getDuration();
        }
        setRange(clock, to);
    }

    /** Отметить конец отрезка текущим временем. */
    public void setRangeEndHere() {
        long from = hasRange() ? rangeStart : 0L;
        if (from >= clock) {
            from = 0L;
        }
        setRange(from, clock);
    }

    /**
     * Кликабельная полоса времени в чате: перемотка как в Dota.
     * Каждая клетка — переход на своё время, ⬆ — момент добычи руды.
     */
    public void sendTimeline() {
        sendPanel();
    }

    /**
     * Панель управления в чате: всё жамкается мышкой, команды вручную не нужны.
     * Отправляется ОДИН раз при старте и по кнопке ↻, а текущее состояние живёт
     * в boss bar / action bar / Tab — так чат остаётся свободным для репортов и сообщений.
     */
    public void sendPanel() {
        if (!viewer.isOnline()) {
            return;
        }

        long duration = Math.max(1L, timeline.getDuration());
        boolean[] markerCells = new boolean[TIMELINE_CELLS];
        for (ReplayTimeline.Marker marker : timeline.getMarkers()) {
            int cell = (int) ((marker.time * TIMELINE_CELLS) / duration);
            if (cell >= TIMELINE_CELLS) {
                cell = TIMELINE_CELLS - 1;
            }
            markerCells[cell] = true;
        }
        int currentCell = (int) Math.min(TIMELINE_CELLS - 1, (clock * TIMELINE_CELLS) / duration);

        Component bar = Component.empty();
        for (int i = 0; i < TIMELINE_CELLS; i++) {
            long cellTime = (duration * i) / TIMELINE_CELLS;
            String glyph;
            NamedTextColor color;
            if (i == currentCell) {
                glyph = "▌";
                color = NamedTextColor.WHITE;
            } else if (markerCells[i]) {
                glyph = "▲";
                color = NamedTextColor.RED;
            } else if (i < currentCell) {
                glyph = "━";
                color = NamedTextColor.AQUA;
            } else {
                glyph = "━";
                color = NamedTextColor.DARK_GRAY;
            }

            bar = bar.append(Component.text(glyph, color)
                    .clickEvent(ClickEvent.runCommand("/axv seek " + (cellTime / 1000)))
                    .hoverEvent(HoverEvent.showText(Component.text("Перейти к " + formatTime(cellTime)))));
        }

        Component space = Component.text(" ");

        viewer.sendMessage(Component.text("▬▬▬ ", NamedTextColor.DARK_GRAY)
                .append(Component.text("Запись #" + recording.getId() + " · " + recording.getPlayerName(), NamedTextColor.GOLD)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text(" ▬▬▬ ", NamedTextColor.DARK_GRAY))
                .append(button("↻", "/axv panel", "Обновить панель"))
                .append(space)
                .append(button("■", "/axv stop", "Завершить просмотр")));

        // Полоса времени: любая клетка — прыжок на своё время, ▲ — добыча руды
        viewer.sendMessage(Component.text("0:00 ", NamedTextColor.DARK_GRAY)
                .append(bar)
                .append(Component.text(" " + formatTime(duration), NamedTextColor.DARK_GRAY)));

        viewer.sendMessage(Component.empty()
                .append(button("⏮", "/axv marker prev", "Предыдущая добыча руды"))
                .append(space).append(button("-30", "/axv jump -30", "Назад на 30 секунд"))
                .append(space).append(button("-10", "/axv jump -10", "Назад на 10 секунд"))
                .append(space).append(button("-3", "/axv jump -3", "Назад на 3 секунды"))
                .append(space).append(button(paused ? "▶ Пуск" : "⏸ Пауза", "/axv pause", "Пауза / продолжить"))
                .append(space).append(button("+3", "/axv jump 3", "Вперёд на 3 секунды"))
                .append(space).append(button("+10", "/axv jump 10", "Вперёд на 10 секунд"))
                .append(space).append(button("+30", "/axv jump 30", "Вперёд на 30 секунд"))
                .append(space).append(button("⏭", "/axv marker next", "Следующая добыча руды")));

        Component speedRow = Component.text("Скорость: ", NamedTextColor.GRAY);
        double[] presets = {0.25, 0.5, 1, 2, 4, 8};
        for (double preset : presets) {
            if (preset > maxSpeed) {
                continue;
            }
            String label = trimSpeed(preset) + "x";
            speedRow = speedRow.append(button(Math.abs(speed - preset) < 0.001 ? "•" + label : label,
                    "/axv speed " + preset, "Скорость x" + trimSpeed(preset))).append(space);
        }
        speedRow = speedRow
                .append(button("◀◀ Реверс", "/axv speed -" + trimSpeed(Math.max(1.0, Math.abs(speed))),
                        "Воспроизведение назад"))
                .append(space)
                .append(button(loop ? "↻ Цикл: вкл" : "↻ Цикл: выкл", "/axv loop", "Повторять отрезок"));
        viewer.sendMessage(speedRow);

        CameraMode current = camera.getMode();
        viewer.sendMessage(Component.text("Камера: ", NamedTextColor.GRAY)
                .append(button(current == CameraMode.FIRST_PERSON ? "•1-е лицо" : "1-е лицо",
                        "/axv camera first", "Глаза игрока и его взгляд"))
                .append(space)
                .append(button(current == CameraMode.THIRD_PERSON ? "•3-е лицо" : "3-е лицо",
                        "/axv camera third", "Камера за спиной"))
                .append(space)
                .append(button(current == CameraMode.FREE_LOOK ? "•Свободная" : "Свободная",
                        "/axv camera free", "Полная свобода полёта: плагин вас не двигает"))
                .append(space)
                .append(button("🎯 К игроку", "/axv follow", "Перенести камеру к игроку")));

        viewer.sendMessage(Component.text("Отрезок" + (hasRange()
                        ? " [" + formatTime(rangeStart) + " — " + formatTime(rangeEnd) + "]: "
                        : ": "), NamedTextColor.GRAY)
                .append(button("A здесь", "/axv range a", "Начало отрезка = текущее время"))
                .append(space)
                .append(button("B здесь", "/axv range b", "Конец отрезка = текущее время"))
                .append(space)
                .append(button("Сброс", "/axv range clear", "Вся запись целиком"))
                .append(space)
                .append(button("▶ Отрезок с начала", "/axv seek " + (rangeStart / 1000), "Перейти к началу отрезка")));

        viewer.sendMessage(Component.text("Состояние — в boss bar и в списке игроков (Tab). Чат остаётся свободным.",
                NamedTextColor.DARK_GRAY));
    }

    private static Component button(String label, String command, String hover) {
        return Component.text("[" + label + "]", NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover)));
    }

    public static String formatTime(long ms) {
        long totalSeconds = Math.max(0L, ms) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%d:%02d", minutes, seconds);
    }

    private static String trimSpeed(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
