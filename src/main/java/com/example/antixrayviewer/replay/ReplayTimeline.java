package com.example.antixrayviewer.replay;

import com.example.antixrayviewer.data.BlockEvent;
import com.example.antixrayviewer.data.PlayerRecording;
import com.example.antixrayviewer.data.RecordFrame;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Предварительно построенная таймлиния записи.
 *
 * Строится ОДИН раз при открытии записи и даёт:
 * <ul>
 *   <li>временную шкалу в миллисекундах от начала записи (а не индексы кадров);</li>
 *   <li>список дельт изменения блоков (from → to), т.е. перемотка работает в обе стороны;</li>
 *   <li>ключевые кадры состояния блоков — мгновенный seek в любую точку;</li>
 *   <li>интервалы ломания блоков для плавной анимации трещин;</li>
 *   <li>маркеры важных событий (для прыжков next/prev и отметок на полосе).</li>
 * </ul>
 */
public final class ReplayTimeline {

    /** Каждые N дельт сохраняем полный снимок состояния. */
    private static final int KEYFRAME_INTERVAL = 128;

    /** Если BREAK_START не был записан, анимацию показываем за это время до разрушения. */
    private static final long IMPLICIT_BREAK_LEAD_MS = 400L;

    public static final class BlockDelta {
        public final long time;
        public final BlockRef ref;
        public final BlockData from;
        public final BlockData to;
        public final Material material;
        public final boolean destructive;

        BlockDelta(long time, BlockRef ref, BlockData from, BlockData to, Material material, boolean destructive) {
            this.time = time;
            this.ref = ref;
            this.from = from;
            this.to = to;
            this.material = material;
            this.destructive = destructive;
        }
    }

    public static final class BreakSpan {
        public final BlockRef ref;
        public final long start;
        public final long end;
        public final boolean completed;

        BreakSpan(BlockRef ref, long start, long end, boolean completed) {
            this.ref = ref;
            this.start = start;
            this.end = end;
            this.completed = completed;
        }
    }

    public static final class Marker {
        public final long time;
        public final Material material;
        public final BlockRef ref;

        Marker(long time, Material material, BlockRef ref) {
            this.time = time;
            this.material = material;
            this.ref = ref;
        }
    }

    /** Снимок состояния блоков после применения deltaIndex дельт. */
    private static final class Snapshot {
        final int deltaIndex;
        final Map<BlockRef, BlockData> state;

        Snapshot(int deltaIndex, Map<BlockRef, BlockData> state) {
            this.deltaIndex = deltaIndex;
            this.state = state;
        }
    }

    /** Интерполированное состояние игрока в произвольный момент времени. */
    public static final class Sample {
        public String world;
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;
        public boolean sneaking;
        public boolean sprinting;
        public boolean flying;
        public double health;
        public int foodLevel;
        public int frameIndex;
        /** true, если между кадрами произошёл разрыв (другой мир / телепорт) — камеру надо снапать. */
        public boolean discontinuity;
    }

    private final PlayerRecording recording;
    private final List<RecordFrame> frames;
    private final long[] frameTimes;
    private final long duration;

    private final List<BlockDelta> deltas;
    private final Map<BlockRef, BlockData> initialState;
    private final List<Snapshot> keyframes;
    private final List<BreakSpan> breakSpans;
    private final List<Marker> markers;
    private final long maxSpanLength;
    private final int oreBreakCount;

    public ReplayTimeline(PlayerRecording recording) {
        this.recording = recording;
        this.frames = recording.getFramesView();

        long base = recording.getStartTime();
        if (!frames.isEmpty()) {
            base = Math.min(base, frames.get(0).getTimestamp());
        }

        this.frameTimes = new long[frames.size()];
        long previous = 0L;
        for (int i = 0; i < frames.size(); i++) {
            long t = frames.get(i).getTimestamp() - base;
            // Защита от немонотонных меток времени в старых записях
            if (t < previous) {
                t = previous;
            }
            frameTimes[i] = t;
            previous = t;
        }

        long computedDuration = frameTimes.length == 0 ? 0L : frameTimes[frameTimes.length - 1];
        this.duration = Math.max(1L, computedDuration);

        List<BlockDelta> builtDeltas = new ArrayList<>();
        Map<BlockRef, BlockData> builtInitial = new HashMap<>();
        List<Snapshot> builtKeyframes = new ArrayList<>();
        List<BreakSpan> builtSpans = new ArrayList<>();
        List<Marker> builtMarkers = new ArrayList<>();
        Map<BlockRef, BlockData> running = new HashMap<>();
        Map<BlockRef, Long> pendingBreakStarts = new HashMap<>();

        long longestSpan = IMPLICIT_BREAK_LEAD_MS;
        int ores = 0;

        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            RecordFrame frame = frames.get(frameIndex);
            if (!frame.hasBlockEvents()) {
                continue;
            }

            for (BlockEvent event : frame.getBlockEventsView()) {
                long time = clampTime(event.getTimestamp() - base, frameTimes[frameIndex]);
                BlockRef ref = new BlockRef(event.getWorld(), event.getX(), event.getY(), event.getZ());

                switch (event.getType()) {
                    case BREAK_START: {
                        pendingBreakStarts.put(ref, time);
                        break;
                    }
                    case BREAK_CANCEL: {
                        Long started = pendingBreakStarts.remove(ref);
                        if (started != null && time > started) {
                            builtSpans.add(new BreakSpan(ref, started, time, false));
                            longestSpan = Math.max(longestSpan, time - started);
                        }
                        break;
                    }
                    case BREAK_PROGRESS: {
                        pendingBreakStarts.putIfAbsent(ref, time);
                        break;
                    }
                    case BREAK_COMPLETE: {
                        Long started = pendingBreakStarts.remove(ref);
                        long spanStart = started != null ? started : Math.max(0L, time - IMPLICIT_BREAK_LEAD_MS);
                        builtSpans.add(new BreakSpan(ref, spanStart, time, true));
                        longestSpan = Math.max(longestSpan, time - spanStart);

                        BlockData broken = event.resolveBlockData();
                        BlockData from = running.get(ref);
                        if (from == null) {
                            from = broken;
                            builtInitial.put(ref, broken);
                        }
                        BlockData air = Material.AIR.createBlockData();
                        builtDeltas.add(new BlockDelta(time, ref, from, air, event.getBlockType(), true));
                        running.put(ref, air);

                        if (isValuable(event.getBlockType())) {
                            builtMarkers.add(new Marker(time, event.getBlockType(), ref));
                            ores++;
                        }
                        break;
                    }
                    case PLACE: {
                        BlockData placed = event.resolveBlockData();
                        if (placed == null) {
                            break;
                        }
                        BlockData from = running.get(ref);
                        if (from == null) {
                            BlockData replaced = event.resolvePreviousBlockData();
                            from = replaced != null ? replaced : Material.AIR.createBlockData();
                            builtInitial.put(ref, from);
                        }
                        builtDeltas.add(new BlockDelta(time, ref, from, placed, event.getBlockType(), false));
                        running.put(ref, placed);
                        break;
                    }
                    default:
                        break;
                }

                if (!builtDeltas.isEmpty() && builtDeltas.size() % KEYFRAME_INTERVAL == 0) {
                    int index = builtDeltas.size();
                    if (builtKeyframes.isEmpty()
                            || builtKeyframes.get(builtKeyframes.size() - 1).deltaIndex != index) {
                        builtKeyframes.add(new Snapshot(index, new HashMap<>(running)));
                    }
                }
            }
        }

        // Если руды не было вообще — отмечаем все разрушения, чтобы прыжки по событиям работали
        if (builtMarkers.isEmpty()) {
            for (BlockDelta delta : builtDeltas) {
                if (delta.destructive) {
                    builtMarkers.add(new Marker(delta.time, delta.material, delta.ref));
                }
            }
        }

        builtSpans.sort((a, b) -> Long.compare(a.start, b.start));
        builtMarkers.sort((a, b) -> Long.compare(a.time, b.time));

        this.deltas = Collections.unmodifiableList(builtDeltas);
        this.initialState = Collections.unmodifiableMap(builtInitial);
        this.keyframes = Collections.unmodifiableList(builtKeyframes);
        this.breakSpans = Collections.unmodifiableList(builtSpans);
        this.markers = Collections.unmodifiableList(builtMarkers);
        this.maxSpanLength = longestSpan;
        this.oreBreakCount = ores;
    }

    private long clampTime(long eventTime, long frameTime) {
        if (eventTime < 0) {
            return frameTime;
        }
        return eventTime;
    }

    private static boolean isValuable(Material material) {
        if (material == null) {
            return false;
        }
        if (material == Material.ANCIENT_DEBRIS) {
            return true;
        }
        String name = material.name();
        return name.endsWith("_ORE");
    }

    // ===================== Временная шкала =====================

    public PlayerRecording getRecording() {
        return recording;
    }

    public long getDuration() {
        return duration;
    }

    public int getFrameCount() {
        return frames.size();
    }

    public long getFrameTime(int index) {
        if (index < 0 || index >= frameTimes.length) {
            return 0L;
        }
        return frameTimes[index];
    }

    /**
     * Индекс последнего кадра с frameTime <= time.
     */
    public int frameIndexAt(long time) {
        if (frameTimes.length == 0) {
            return -1;
        }
        int low = 0;
        int high = frameTimes.length - 1;
        int result = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (frameTimes[mid] <= time) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return result;
    }

    /**
     * Плавно интерполированное состояние игрока. Для позиции используется сплайн
     * Catmull-Rom (если соседние кадры корректны), для углов — интерполяция по кратчайшей дуге.
     */
    public Sample sample(long time, Sample reuse) {
        Sample out = reuse != null ? reuse : new Sample();
        if (frames.isEmpty()) {
            return out;
        }

        int i1 = frameIndexAt(time);
        int i2 = Math.min(i1 + 1, frames.size() - 1);
        RecordFrame f1 = frames.get(i1);
        RecordFrame f2 = frames.get(i2);

        double t;
        long t1 = frameTimes[i1];
        long t2 = frameTimes[i2];
        if (i1 == i2 || t2 <= t1) {
            t = 0.0;
        } else {
            t = (double) (time - t1) / (double) (t2 - t1);
            if (t < 0.0) {
                t = 0.0;
            } else if (t > 1.0) {
                t = 1.0;
            }
        }

        boolean sameWorld = f1.getWorld().equals(f2.getWorld());
        out.discontinuity = !sameWorld;
        out.world = f1.getWorld();
        out.frameIndex = i1;
        out.sneaking = t < 0.5 ? f1.isSneaking() : f2.isSneaking();
        out.sprinting = t < 0.5 ? f1.isSprinting() : f2.isSprinting();
        out.flying = t < 0.5 ? f1.isFlying() : f2.isFlying();
        out.health = f1.getHealth() + (f2.getHealth() - f1.getHealth()) * t;
        out.foodLevel = t < 0.5 ? f1.getFoodLevel() : f2.getFoodLevel();

        if (!sameWorld) {
            out.x = f1.getX();
            out.y = f1.getY();
            out.z = f1.getZ();
            out.yaw = f1.getYaw();
            out.pitch = f1.getPitch();
            return out;
        }

        double dx = f2.getX() - f1.getX();
        double dy = f2.getY() - f1.getY();
        double dz = f2.getZ() - f1.getZ();
        double segment = dx * dx + dy * dy + dz * dz;

        if (segment > 64.0) {
            // Огромный скачок — телепорт/элитры: не сглаживаем, а фиксируем разрыв
            out.discontinuity = true;
            out.x = t < 0.5 ? f1.getX() : f2.getX();
            out.y = t < 0.5 ? f1.getY() : f2.getY();
            out.z = t < 0.5 ? f1.getZ() : f2.getZ();
            out.yaw = t < 0.5 ? f1.getYaw() : f2.getYaw();
            out.pitch = t < 0.5 ? f1.getPitch() : f2.getPitch();
            return out;
        }

        RecordFrame f0 = frames.get(Math.max(0, i1 - 1));
        RecordFrame f3 = frames.get(Math.min(frames.size() - 1, i2 + 1));
        boolean splineOk = f0.getWorld().equals(f1.getWorld()) && f3.getWorld().equals(f1.getWorld())
                && distanceSquared(f0, f1) < 64.0 && distanceSquared(f2, f3) < 64.0;

        if (splineOk) {
            out.x = catmullRom(f0.getX(), f1.getX(), f2.getX(), f3.getX(), t);
            out.y = catmullRom(f0.getY(), f1.getY(), f2.getY(), f3.getY(), t);
            out.z = catmullRom(f0.getZ(), f1.getZ(), f2.getZ(), f3.getZ(), t);
        } else {
            out.x = f1.getX() + dx * t;
            out.y = f1.getY() + dy * t;
            out.z = f1.getZ() + dz * t;
        }

        out.yaw = lerpAngle(f1.getYaw(), f2.getYaw(), (float) t);
        out.pitch = f1.getPitch() + (f2.getPitch() - f1.getPitch()) * (float) t;
        return out;
    }

    private static double distanceSquared(RecordFrame a, RecordFrame b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * ((2 * p1)
                + (-p0 + p2) * t
                + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    public static float lerpAngle(float from, float to, float t) {
        float delta = ((to - from) % 360f + 540f) % 360f - 180f;
        return from + delta * t;
    }

    // ===================== Состояние блоков =====================

    public List<BlockDelta> getDeltas() {
        return deltas;
    }

    public Map<BlockRef, BlockData> getInitialState() {
        return initialState;
    }

    /**
     * Количество дельт, которые должны быть применены к моменту time.
     */
    public int deltaIndexFor(long time) {
        int low = 0;
        int high = deltas.size() - 1;
        int result = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (deltas.get(mid).time <= time) {
                result = mid + 1;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return result;
    }

    /**
     * Полное состояние всех затронутых блоков на момент time.
     * Использует ближайший ключевой кадр, поэтому даже большой скачок дешёвый.
     */
    public Map<BlockRef, BlockData> stateAt(long time) {
        int target = deltaIndexFor(time);
        Map<BlockRef, BlockData> state = new HashMap<>(initialState);

        int start = 0;
        for (int i = keyframes.size() - 1; i >= 0; i--) {
            Snapshot snapshot = keyframes.get(i);
            if (snapshot.deltaIndex <= target) {
                state.putAll(snapshot.state);
                start = snapshot.deltaIndex;
                break;
            }
        }

        for (int i = start; i < target; i++) {
            BlockDelta delta = deltas.get(i);
            state.put(delta.ref, delta.to);
        }
        return state;
    }

    // ===================== Анимации и маркеры =====================

    public List<BreakSpan> getBreakSpans() {
        return breakSpans;
    }

    public long getMaxSpanLength() {
        return maxSpanLength;
    }

    /**
     * Индекс первого интервала ломания, который может быть активен в момент time.
     */
    public int firstSpanIndexFor(long time) {
        long from = time - maxSpanLength;
        int low = 0;
        int high = breakSpans.size() - 1;
        int result = breakSpans.size();
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (breakSpans.get(mid).start >= from) {
                result = mid;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return result;
    }

    public List<Marker> getMarkers() {
        return markers;
    }

    public int getOreBreakCount() {
        return oreBreakCount;
    }

    /**
     * Время следующего важного события после time, или -1.
     */
    public long nextMarkerTime(long time) {
        for (Marker marker : markers) {
            if (marker.time > time + 50L) {
                return marker.time;
            }
        }
        return -1L;
    }

    /**
     * Время предыдущего важного события до time, или -1.
     */
    public long previousMarkerTime(long time) {
        long result = -1L;
        for (Marker marker : markers) {
            if (marker.time < time - 50L) {
                result = marker.time;
            } else {
                break;
            }
        }
        return result;
    }
}
