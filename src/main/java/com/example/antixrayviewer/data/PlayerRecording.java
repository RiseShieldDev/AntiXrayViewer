package com.example.antixrayviewer.data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Представляет полную запись движений подозрительного игрока
 */
public class PlayerRecording {
    
    private static final AtomicInteger ID_COUNTER = new AtomicInteger(1);
    
    private final int id;
    private final UUID playerId;
    private final String playerName;
    private final String reason;
    private final long startTime;
    private long endTime;
    private String endReason;
    private final List<RecordFrame> frames;
    
    public PlayerRecording(UUID playerId, String playerName, String reason, long startTime) {
        this.id = ID_COUNTER.getAndIncrement();
        this.playerId = playerId;
        this.playerName = playerName;
        this.reason = reason;
        this.startTime = startTime;
        this.frames = new ArrayList<>();
    }
    
    /**
     * Добавить кадр к записи
     */
    public void addFrame(RecordFrame frame) {
        frames.add(frame);
    }
    
    /**
     * Получить длительность записи в миллисекундах
     */
    public long getDuration() {
        if (endTime == 0) {
            return System.currentTimeMillis() - startTime;
        }
        return endTime - startTime;
    }
    
    /**
     * Получить длительность записи в секундах
     */
    public int getDurationSeconds() {
        return (int) (getDuration() / 1000);
    }
    
    // Геттеры и сеттеры
    public int getId() {
        return id;
    }
    
    public UUID getPlayerId() {
        return playerId;
    }
    
    public String getPlayerName() {
        return playerName;
    }
    
    public String getReason() {
        return reason;
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public long getEndTime() {
        return endTime;
    }
    
    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }
    
    public String getEndReason() {
        return endReason;
    }
    
    public void setEndReason(String endReason) {
        this.endReason = endReason;
    }
    
    public List<RecordFrame> getFrames() {
        return new ArrayList<>(frames);
    }
    
    /**
     * Получить количество кадров в записи
     */
    public int getFrameCount() {
        return frames.size();
    }
    
    /**
     * Получить кадр по индексу
     */
    public RecordFrame getFrame(int index) {
        if (index >= 0 && index < frames.size()) {
            return frames.get(index);
        }
        return null;
    }
}