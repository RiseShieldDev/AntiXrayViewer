package com.example.antixrayai.data;

import org.bukkit.Material;

/**
 * Представляет событие, связанное с блоком (ломание, установка)
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
    private final float breakProgress; // 0.0 - 1.0 для прогресса ломания
    private final int entityId; // ID сущности для показа анимации
    
    public BlockEvent(long timestamp, EventType type, int x, int y, int z, 
                     String world, Material blockType, float breakProgress, int entityId) {
        this.timestamp = timestamp;
        this.type = type;
        this.x = x;
        this.y = y;
        this.z = z;
        this.world = world;
        this.blockType = blockType;
        this.breakProgress = breakProgress;
        this.entityId = entityId;
    }
    
    // Упрощенный конструктор для событий без прогресса
    public BlockEvent(long timestamp, EventType type, int x, int y, int z, 
                     String world, Material blockType) {
        this(timestamp, type, x, y, z, world, blockType, 0.0f, -1);
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
    
    /**
     * Получить стадию ломания блока (0-9) для пакета
     */
    public int getBreakStage() {
        if (type == EventType.BREAK_COMPLETE || type == EventType.BREAK_CANCEL) {
            return -1; // Удаляет анимацию
        }
        return (int) (breakProgress * 9); // 0-9 стадий
    }
}