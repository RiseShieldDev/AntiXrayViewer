package com.example.antixrayai.storage;

import com.example.antixrayai.AntiXrayAI;
import com.example.antixrayai.data.PlayerRecording;
import com.example.antixrayai.data.RecordFrame;
import com.example.antixrayai.data.BlockEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.bukkit.Material;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Класс для сохранения и загрузки записей в файлы
 */
public class RecordingStorage {
    
    private final AntiXrayAI plugin;
    private final File recordingsFolder;
    private final Gson gson;
    
    public RecordingStorage(AntiXrayAI plugin) {
        this.plugin = plugin;
        this.recordingsFolder = new File(plugin.getDataFolder(), "recordings");
        
        // Создаем папку для записей, если её нет
        if (!recordingsFolder.exists()) {
            recordingsFolder.mkdirs();
        }
        
        // Настраиваем Gson с красивым форматированием
        this.gson = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(Material.class, new MaterialAdapter())
            .create();
    }
    
    /**
     * Сохранить запись в файл
     */
    public boolean saveRecording(PlayerRecording recording) {
        File file = new File(recordingsFolder, "recording-" + recording.getId() + ".json");
        
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            
            // Конвертируем запись в JSON
            RecordingData data = RecordingData.fromRecording(recording);
            gson.toJson(data, writer);
            
            plugin.getLogger().info("Запись #" + recording.getId() + 
                                  " сохранена в файл: " + file.getName());
            return true;
            
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, 
                "Ошибка при сохранении записи #" + recording.getId(), e);
            return false;
        }
    }
    
    /**
     * Загрузить все записи из файлов
     */
    public List<PlayerRecording> loadAllRecordings() {
        List<PlayerRecording> recordings = new ArrayList<>();
        
        if (!recordingsFolder.exists()) {
            return recordings;
        }
        
        File[] files = recordingsFolder.listFiles((dir, name) -> 
            name.startsWith("recording-") && name.endsWith(".json"));
        
        if (files == null) {
            return recordings;
        }
        
        for (File file : files) {
            PlayerRecording recording = loadRecording(file);
            if (recording != null) {
                recordings.add(recording);
            }
        }
        
        plugin.getLogger().info("Загружено записей из файлов: " + recordings.size());
        return recordings;
    }
    
    /**
     * Загрузить одну запись из файла
     */
    private PlayerRecording loadRecording(File file) {
        try (Reader reader = new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8)) {
            
            RecordingData data = gson.fromJson(reader, RecordingData.class);
            if (data != null) {
                PlayerRecording recording = data.toRecording();
                plugin.getLogger().info("Загружена запись #" + recording.getId() + 
                                      " из файла: " + file.getName());
                return recording;
            }
            
        } catch (IOException | JsonSyntaxException e) {
            plugin.getLogger().log(Level.WARNING, 
                "Ошибка при загрузке записи из файла: " + file.getName(), e);
        }
        
        return null;
    }
    
    /**
     * Удалить файл записи
     */
    public boolean deleteRecording(int recordingId) {
        File file = new File(recordingsFolder, "recording-" + recordingId + ".json");
        
        if (file.exists()) {
            boolean deleted = file.delete();
            if (deleted) {
                plugin.getLogger().info("Удален файл записи #" + recordingId);
            }
            return deleted;
        }
        
        return false;
    }
    
    /**
     * Получить размер всех файлов записей в МБ
     */
    public double getTotalSizeMB() {
        if (!recordingsFolder.exists()) {
            return 0;
        }
        
        File[] files = recordingsFolder.listFiles((dir, name) -> 
            name.startsWith("recording-") && name.endsWith(".json"));
        
        if (files == null) {
            return 0;
        }
        
        long totalBytes = 0;
        for (File file : files) {
            totalBytes += file.length();
        }
        
        return totalBytes / (1024.0 * 1024.0);
    }
    
    /**
     * Внутренний класс для сериализации записи
     */
    private static class RecordingData {
        private int id;
        private String playerId;
        private String playerName;
        private String reason;
        private long startTime;
        private long endTime;
        private String endReason;
        private List<FrameData> frames;
        
        static RecordingData fromRecording(PlayerRecording recording) {
            RecordingData data = new RecordingData();
            data.id = recording.getId();
            data.playerId = recording.getPlayerId().toString();
            data.playerName = recording.getPlayerName();
            data.reason = recording.getReason();
            data.startTime = recording.getStartTime();
            data.endTime = recording.getEndTime();
            data.endReason = recording.getEndReason();
            
            data.frames = new ArrayList<>();
            for (RecordFrame frame : recording.getFrames()) {
                data.frames.add(FrameData.fromFrame(frame));
            }
            
            return data;
        }
        
        PlayerRecording toRecording() {
            PlayerRecording recording = new PlayerRecording(
                java.util.UUID.fromString(playerId),
                playerName,
                reason,
                startTime
            );
            
            // Устанавливаем ID через рефлексию (так как он final)
            try {
                java.lang.reflect.Field idField = PlayerRecording.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(recording, id);
            } catch (Exception e) {
                // Игнорируем, будет использован автоматический ID
            }
            
            recording.setEndTime(endTime);
            recording.setEndReason(endReason);
            
            for (FrameData frameData : frames) {
                recording.addFrame(frameData.toFrame());
            }
            
            return recording;
        }
    }
    
    /**
     * Внутренний класс для сериализации кадра
     */
    private static class FrameData {
        private long timestamp;
        private double x, y, z;
        private float yaw, pitch;
        private String world;
        private boolean sneaking, sprinting, flying;
        private double health;
        private int foodLevel;
        private List<BlockEventData> blockEvents;
        
        static FrameData fromFrame(RecordFrame frame) {
            FrameData data = new FrameData();
            data.timestamp = frame.getTimestamp();
            data.x = frame.getX();
            data.y = frame.getY();
            data.z = frame.getZ();
            data.yaw = frame.getYaw();
            data.pitch = frame.getPitch();
            data.world = frame.getWorld();
            data.sneaking = frame.isSneaking();
            data.sprinting = frame.isSprinting();
            data.flying = frame.isFlying();
            data.health = frame.getHealth();
            data.foodLevel = frame.getFoodLevel();
            
            data.blockEvents = new ArrayList<>();
            for (BlockEvent event : frame.getBlockEvents()) {
                data.blockEvents.add(BlockEventData.fromEvent(event));
            }
            
            return data;
        }
        
        RecordFrame toFrame() {
            RecordFrame frame = new RecordFrame(
                timestamp, x, y, z, yaw, pitch, world,
                sneaking, sprinting, flying, health, foodLevel
            );
            
            for (BlockEventData eventData : blockEvents) {
                frame.addBlockEvent(eventData.toEvent());
            }
            
            return frame;
        }
    }
    
    /**
     * Внутренний класс для сериализации события блока
     */
    private static class BlockEventData {
        private long timestamp;
        private String type;
        private int x, y, z;
        private String world;
        private String blockType;
        private float breakProgress;
        private int entityId;
        
        static BlockEventData fromEvent(BlockEvent event) {
            BlockEventData data = new BlockEventData();
            data.timestamp = event.getTimestamp();
            data.type = event.getType().name();
            data.x = event.getX();
            data.y = event.getY();
            data.z = event.getZ();
            data.world = event.getWorld();
            data.blockType = event.getBlockType().name();
            data.breakProgress = event.getBreakProgress();
            data.entityId = event.getEntityId();
            return data;
        }
        
        BlockEvent toEvent() {
            return new BlockEvent(
                timestamp,
                BlockEvent.EventType.valueOf(type),
                x, y, z,
                world,
                Material.valueOf(blockType),
                breakProgress,
                entityId
            );
        }
    }
    
    /**
     * Адаптер для сериализации Material
     */
    private static class MaterialAdapter extends com.google.gson.TypeAdapter<Material> {
        @Override
        public void write(com.google.gson.stream.JsonWriter out, Material value) throws IOException {
            out.value(value.name());
        }
        
        @Override
        public Material read(com.google.gson.stream.JsonReader in) throws IOException {
            return Material.valueOf(in.nextString());
        }
    }
}