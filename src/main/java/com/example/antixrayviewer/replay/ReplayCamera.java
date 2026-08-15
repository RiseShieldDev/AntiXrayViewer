package com.example.antixrayviewer.replay;

import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * Система управления камерой.
 *
 * Про высоту (почему раньше камера была "не в голове"):
 * клиент рисует кадр из ГЛАЗ игрока, а Location — это НОГИ (+1.62 до глаз).
 * Значит, чтобы камера оказалась в точке C, телепортировать надо в C − 1.62.
 *
 * Режимы:
 *  - FIRST_PERSON — камера точно в глазах записанного игрока, с его взглядом (по умолчанию).
 *  - THIRD_PERSON — камера за спиной, с трассировкой стен, видна модель игрока.
 *  - FREE_LOOK    — полная свобода: после первой установки плагин НЕ трогает позицию
 *                   зрителя вообще, мышь свободна.
 *                   Кнопка "К игроку" (follow) — единственный принудительный перенос.
 *
 * Вся картинка адресная: модель игрока создаётся невидимой по умолчанию и показывается
 * только зрителю, блоки идут пакетами лично ему. Другие игроки не видят ничего.
 */
public final class ReplayCamera {

    /** Высота глаз зрителя-наблюдателя. */
    private static final double SPECTATOR_EYE = 1.62;
    private static final double EYE_HEIGHT = 1.62;
    private static final double SNEAK_EYE_HEIGHT = 1.27;
    private static final double MIN_MOVE_SQ = 0.0001;

    private final Plugin plugin;
    private final Player viewer;
    private final ReplayTimeline timeline;
    private final ReplayAvatar avatar;

    private CameraMode mode;
    private final double smoothing;
    private final double thirdPersonDistance;
    private final boolean avatarEnabled;

    private final ReplayTimeline.Sample sample = new ReplayTimeline.Sample();

    private boolean initialized;
    private String currentWorld;
    private double cx;
    private double cy;
    private double cz;
    private float cyaw;
    private float cpitch;
    private float lastSentYaw = Float.NaN;
    private float lastSentPitch = Float.NaN;
    /** В свободном режиме камера ставится один раз, дальше зритель летит сам. */
    private boolean freePlaced;

    public ReplayCamera(Plugin plugin, Player viewer, ReplayTimeline timeline, UUID recordedId, String recordedName,
                        CameraMode mode, double smoothing, double thirdPersonDistance, boolean avatarEnabled) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.timeline = timeline;
        this.mode = mode;
        this.smoothing = Math.max(0.0, Math.min(0.95, smoothing));
        this.thirdPersonDistance = Math.max(1.0, Math.min(12.0, thirdPersonDistance));
        this.avatarEnabled = avatarEnabled;
        this.avatar = new ReplayAvatar(plugin, viewer, recordedId, recordedName);
    }

    public CameraMode getMode() {
        return mode;
    }

    public boolean isFree() {
        return mode == CameraMode.FREE_LOOK;
    }

    public ReplayTimeline.Sample getLastSample() {
        return sample;
    }

    public void setMode(CameraMode newMode, long clock) {
        this.mode = newMode;
        initialized = false;
        freePlaced = false;
        update(clock, true);
    }

    /**
     * Одноразово перенести зрителя к записанному игроку (кнопка "К игроку").
     * В свободном режиме это единственный способ сдвинуть камеру принудительно.
     */
    public void follow(long clock) {
        timeline.sample(clock, sample);
        if (sample.world == null) {
            return;
        }
        World world = plugin.getServer().getWorld(sample.world);
        if (world == null) {
            return;
        }
        double eyeY = sample.y + (sample.sneaking ? SNEAK_EYE_HEIGHT : EYE_HEIGHT);
        cx = sample.x;
        cy = eyeY;
        cz = sample.z;
        cyaw = sample.yaw;
        cpitch = sample.pitch;
        currentWorld = sample.world;
        initialized = true;
        freePlaced = true;
        placeCamera(world, cx, cy, cz, cyaw, cpitch, true, mode == CameraMode.FREE_LOOK);
    }

    /**
     * Обновить камеру для момента времени clock.
     *
     * @param instant true при старте/перемотке — без сглаживания
     */
    public void update(long clock, boolean instant) {
        if (!viewer.isOnline()) {
            return;
        }

        timeline.sample(clock, sample);
        if (sample.world == null) {
            return;
        }

        World world = plugin.getServer().getWorld(sample.world);
        if (world == null) {
            return;
        }

        boolean snap = instant || !initialized || sample.discontinuity
                || currentWorld == null || !currentWorld.equals(sample.world);

        double targetEyeY = sample.y + (sample.sneaking ? SNEAK_EYE_HEIGHT : EYE_HEIGHT);

        if (snap) {
            cx = sample.x;
            cy = targetEyeY;
            cz = sample.z;
            cyaw = sample.yaw;
            cpitch = sample.pitch;
            currentWorld = sample.world;
            initialized = true;
        } else {
            double alpha = 1.0 - smoothing;
            cx += (sample.x - cx) * alpha;
            cy += (targetEyeY - cy) * alpha;
            cz += (sample.z - cz) * alpha;
            cyaw = ReplayTimeline.lerpAngle(cyaw, sample.yaw, (float) alpha);
            cpitch += (sample.pitch - cpitch) * (float) alpha;
        }

        Location avatarLocation = new Location(world, sample.x, sample.y, sample.z, sample.yaw, sample.pitch);
        updateAvatar(avatarLocation);

        switch (mode) {
            case THIRD_PERSON:
                applyThirdPerson(world, snap);
                break;
            case FREE_LOOK:
                applyFreeLook(world);
                break;
            case FIRST_PERSON:
            default:
                applyFirstPerson(world, snap);
                break;
        }
    }

    private void updateAvatar(Location location) {
        boolean needAvatar = avatarEnabled || mode == CameraMode.THIRD_PERSON;
        if (!needAvatar) {
            avatar.remove();
            return;
        }
        if (!avatar.isAlive()) {
            avatar.spawn(location);
        }
        avatar.move(location, location.getYaw(), location.getPitch());

        // В первом лице модель заслоняла бы обзор изнутри головы
        if (mode == CameraMode.THIRD_PERSON || mode == CameraMode.FREE_LOOK) {
            avatar.show();
        } else {
            avatar.hide();
        }
    }

    private void applyFirstPerson(World world, boolean snap) {
        placeCamera(world, cx, cy, cz, cyaw, cpitch, snap, false);
    }

    /**
     * Свободная камера: НИКАКИХ телепортов после первой установки.
     * Именно из-за телепорта каждый тик Shift раньше не работал:
     * клиент опускал камеру, а сервер тут же возвращал её в записанную точку.
     */
    private void applyFreeLook(World world) {
        if (!freePlaced) {
            freePlaced = true;
            placeCamera(world, cx, cy, cz, cyaw, cpitch, true, true);
        }
    }

    private void applyThirdPerson(World world, boolean snap) {
        Vector direction = directionFrom(cyaw, cpitch);
        Location eye = new Location(world, cx, cy, cz);
        Vector back = direction.clone().multiply(-1);

        double distance = thirdPersonDistance;
        RayTraceResult hit = world.rayTraceBlocks(eye, back, distance, org.bukkit.FluidCollisionMode.NEVER, true);
        if (hit != null && hit.getHitPosition() != null) {
            double hitDistance = hit.getHitPosition().distance(eye.toVector());
            distance = Math.max(0.6, hitDistance - 0.4);
        }

        Vector offset = back.clone().multiply(distance);
        placeCamera(world, cx + offset.getX(), cy + offset.getY(), cz + offset.getZ(), cyaw, cpitch, snap, false);
    }

    /**
     * Поставить КАМЕРУ (а не ноги) в точку camX/camY/camZ.
     */
    @SuppressWarnings({"deprecation", "removal"})
    private void placeCamera(World world, double camX, double camY, double camZ,
                             float yaw, float pitch, boolean snap, boolean keepClientRotation) {
        Location target = new Location(world, camX, camY - SPECTATOR_EYE, camZ, yaw, pitch);
        Location current = viewer.getLocation();
        boolean sameWorld = current.getWorld() == target.getWorld();

        if (!snap && sameWorld) {
            double dx = current.getX() - target.getX();
            double dy = current.getY() - target.getY();
            double dz = current.getZ() - target.getZ();
            boolean rotationSame = keepClientRotation
                    || (!Float.isNaN(lastSentYaw)
                        && Math.abs(angleDelta(lastSentYaw, target.getYaw())) < 0.15f
                        && Math.abs(lastSentPitch - target.getPitch()) < 0.15f);
            if (dx * dx + dy * dy + dz * dz < MIN_MOVE_SQ && rotationSame) {
                return;
            }
        }

        if (keepClientRotation) {
            // Сохранить угол взгляда клиента, иначе камеру дёргает при переносе
            viewer.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN,
                    TeleportFlag.Relative.YAW, TeleportFlag.Relative.PITCH);
        } else {
            viewer.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
            lastSentYaw = target.getYaw();
            lastSentPitch = target.getPitch();
        }
    }

    private static float angleDelta(float from, float to) {
        return ((to - from) % 360f + 540f) % 360f - 180f;
    }

    private static Vector directionFrom(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double xz = Math.cos(pitchRad);
        return new Vector(-xz * Math.sin(yawRad), -Math.sin(pitchRad), xz * Math.cos(yawRad));
    }

    public void cleanup() {
        avatar.remove();
    }
}
