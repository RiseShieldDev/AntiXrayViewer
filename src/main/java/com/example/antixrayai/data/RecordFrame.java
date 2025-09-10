package com.example.antixrayai.data;

/**
 * Представляет один кадр записи движения игрока
 */
public class RecordFrame {
    
    private final long timestamp;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final String world;
    private final boolean sneaking;
    private final boolean sprinting;
    private final boolean flying;
    private final double health;
    private final int foodLevel;
    
    public RecordFrame(long timestamp, double x, double y, double z,
                      float yaw, float pitch, String world,
                      boolean sneaking, boolean sprinting, boolean flying,
                      double health, int foodLevel) {
        this.timestamp = timestamp;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.world = world;
        this.sneaking = sneaking;
        this.sprinting = sprinting;
        this.flying = flying;
        this.health = health;
        this.foodLevel = foodLevel;
    }
    
    // Геттеры
    public long getTimestamp() {
        return timestamp;
    }
    
    public double getX() {
        return x;
    }
    
    public double getY() {
        return y;
    }
    
    public double getZ() {
        return z;
    }
    
    public float getYaw() {
        return yaw;
    }
    
    public float getPitch() {
        return pitch;
    }
    
    public String getWorld() {
        return world;
    }
    
    public boolean isSneaking() {
        return sneaking;
    }
    
    public boolean isSprinting() {
        return sprinting;
    }
    
    public boolean isFlying() {
        return flying;
    }
    
    public double getHealth() {
        return health;
    }
    
    public int getFoodLevel() {
        return foodLevel;
    }
}