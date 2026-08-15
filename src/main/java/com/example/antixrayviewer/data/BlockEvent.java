package com.example.antixrayviewer.data;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * Событие, связанное с блоком (ломание, установка).
 *
 * В версии 1.6.0 добавлено сохранение полного состояния блока (BlockData)
 * и предыдущего состояния — это нужно для корректной перемотки записи
 * в обе стороны и для точного отображения блоков (поворот, waterlogged и т.д.).
 */
public class BlockEvent {

    public enum EventType {
        BREAK_START,    // Начало ломания блока
        BREAK_PROGRESS, // Прогресс ломания блока
        BREAK_COMPLETE, // Блок сломан
        BREAK_CANCEL,   // Отмена ломания
        PLACE           // Установка блока
    }

    private final long timestamp;
    private final EventType type;
    private final int x;
    private final int y;
    private final int z;
    private final String world;
    private final Material blockType;
    private final float breakProgress; // 0.0 - 1.0
    private final int entityId;

    /** Полное состояние блока в виде строки, например "minecraft:oak_stairs[facing=east]". Может быть null. */
    private final String blockData;
    /** Состояние блока до события (актуально для PLACE). Может быть null. */
    private final String previousBlockData;

    private transient BlockData resolvedBlockData;
    private transient boolean blockDataResolved;
    private transient BlockData resolvedPreviousBlockData;
    private transient boolean previousBlockDataResolved;

    public BlockEvent(long timestamp, EventType type, int x, int y, int z,
                      String world, Material blockType, float breakProgress, int entityId,
                      String blockData, String previousBlockData) {
        this.timestamp = timestamp;
        this.type = type;
        this.x = x;
        this.y = y;
        this.z = z;
        this.world = world;
        this.blockType = blockType;
        this.breakProgress = breakProgress;
        this.entityId = entityId;
        this.blockData = blockData;
        this.previousBlockData = previousBlockData;
    }

    public BlockEvent(long timestamp, EventType type, int x, int y, int z,
                      String world, Material blockType, float breakProgress, int entityId) {
        this(timestamp, type, x, y, z, world, blockType, breakProgress, entityId, null, null);
    }

    // Упрощенный конструктор для событий без прогресса
    public BlockEvent(long timestamp, EventType type, int x, int y, int z,
                      String world, Material blockType) {
        this(timestamp, type, x, y, z, world, blockType, 0.0f, -1, null, null);
    }

    // Геттеры
    public long getTimestamp() {
        return timestamp;
    }

    public EventType getType() {
        return type;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public String getWorld() {
        return world;
    }

    public Material getBlockType() {
        return blockType;
    }

    public float getBreakProgress() {
        return breakProgress;
    }

    public int getEntityId() {
        return entityId;
    }

    public String getBlockDataString() {
        return blockData;
    }

    public String getPreviousBlockDataString() {
        return previousBlockData;
    }

    /**
     * Состояние блока на момент события. Кэшируется, поэтому дешево вызывать многократно.
     */
    public BlockData resolveBlockData() {
        if (!blockDataResolved) {
            blockDataResolved = true;
            resolvedBlockData = parse(blockData, blockType);
        }
        return resolvedBlockData;
    }

    /**
     * Состояние блока до события (для PLACE — что было заменено).
     */
    public BlockData resolvePreviousBlockData() {
        if (!previousBlockDataResolved) {
            previousBlockDataResolved = true;
            resolvedPreviousBlockData = parse(previousBlockData, null);
        }
        return resolvedPreviousBlockData;
    }

    private static BlockData parse(String raw, Material fallback) {
        if (raw != null && !raw.isEmpty()) {
            try {
                return Bukkit.createBlockData(raw);
            } catch (IllegalArgumentException ignored) {
                // Блок из другой версии/мода — падаем на fallback
            }
        }
        if (fallback != null && fallback.isBlock()) {
            try {
                return fallback.createBlockData();
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Стадия ломания блока (0-9) для пакета анимации.
     */
    public int getBreakStage() {
        if (type == EventType.BREAK_COMPLETE || type == EventType.BREAK_CANCEL) {
            return -1; // Удаляет анимацию
        }
        return (int) (breakProgress * 9); // 0-9 стадий
    }
}
