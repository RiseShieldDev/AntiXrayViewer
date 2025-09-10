package com.example.antixrayai.managers;

import com.example.antixrayai.AntiXrayAI;
import com.example.antixrayai.data.PlayerRecording;
import com.example.antixrayai.data.RecordFrame;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RecordingManager {
    
    private final AntiXrayAI plugin;
    private final Map<UUID, PlayerRecording> recordings = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> recordingTasks = new HashMap<>();
    private final List<PlayerRecording> completedRecordings = new ArrayList<>();
    
    private final long recordingDuration;
    private final int recordIntervalTicks;
    private final int maxSavedRecordings;
    
    public RecordingManager(AntiXrayAI plugin) {
        this.plugin = plugin;
        
        this.recordingDuration = plugin.getConfig().getInt("recording.duration", 180) * 1000L;
        this.recordIntervalTicks = plugin.getConfig().getInt("recording.interval-ticks", 2);
        this.maxSavedRecordings = plugin.getConfig().getInt("recording.max-saved", 50);
    }
    
    /**
     * Начать запись движений игрока
     */
    public void startRecording(Player player, String reason) {
        UUID playerId = player.getUniqueId();
        
        // Если уже записываем этого игрока, не начинаем новую запись
        if (isRecording(player)) {
            return;
        }
        
        PlayerRecording recording = new PlayerRecording(
            playerId,
            player.getName(),
            reason,
            System.currentTimeMillis()
        );
        
        recordings.put(playerId, recording);
        
        // Создаем задачу для периодической записи позиции игрока
        BukkitTask task = new BukkitRunnable() {
            private long startTime = System.currentTimeMillis();
            
            @Override
            public void run() {
                // Проверяем, не вышел ли игрок
                Player currentPlayer = plugin.getServer().getPlayer(playerId);
                if (currentPlayer == null || !currentPlayer.isOnline()) {
                    stopRecording(playerId, "Игрок вышел с сервера");
                    return;
                }
                
                // Проверяем, не истекло ли время записи
                if (System.currentTimeMillis() - startTime > recordingDuration) {
                    stopRecording(playerId, "Время записи истекло");
                    return;
                }
                
                // Записываем текущий кадр
                recordFrame(currentPlayer, recording);
            }
        }.runTaskTimer(plugin, 0, recordIntervalTicks);
        
        recordingTasks.put(playerId, task);
        
        plugin.getLogger().info(String.format(
            "Начата запись игрока %s (UUID: %s) по причине: %s",
            player.getName(), playerId, reason
        ));
    }
    
    /**
     * Остановить запись игрока
     */
    public void stopRecording(UUID playerId, String endReason) {
        PlayerRecording recording = recordings.remove(playerId);
        BukkitTask task = recordingTasks.remove(playerId);
        
        if (task != null) {
            task.cancel();
        }
        
        if (recording != null) {
            recording.setEndTime(System.currentTimeMillis());
            recording.setEndReason(endReason);
            
            // Сохраняем запись
            saveRecording(recording);
            
            plugin.getLogger().info(String.format(
                "Остановлена запись игрока %s. Причина: %s. Записано кадров: %d",
                recording.getPlayerName(), endReason, recording.getFrames().size()
            ));
            
            // Уведомляем администраторов
            notifyAdminsRecordingComplete(recording);
        }
    }
    
    /**
     * Записать один кадр движения игрока
     */
    private void recordFrame(Player player, PlayerRecording recording) {
        Location loc = player.getLocation();
        
        RecordFrame frame = new RecordFrame(
            System.currentTimeMillis(),
            loc.getX(),
            loc.getY(),
            loc.getZ(),
            loc.getYaw(),
            loc.getPitch(),
            player.getWorld().getName(),
            player.isSneaking(),
            player.isSprinting(),
            player.isFlying(),
            player.getHealth(),
            player.getFoodLevel()
        );
        
        recording.addFrame(frame);
    }
    
    /**
     * Сохранить запись в хранилище
     */
    private void saveRecording(PlayerRecording recording) {
        completedRecordings.add(0, recording); // Добавляем в начало списка
        
        // Ограничиваем количество сохраненных записей
        while (completedRecordings.size() > maxSavedRecordings) {
            completedRecordings.remove(completedRecordings.size() - 1);
        }
    }
    
    /**
     * Уведомить администраторов о завершении записи
     */
    private void notifyAdminsRecordingComplete(PlayerRecording recording) {
        String message = String.format(
            "§a[AntiXrayAI] §7Запись игрока §f%s §7завершена. " +
            "§7Кадров: §e%d §7| ID: §b#%d §7| §f/axai view %d",
            recording.getPlayerName(),
            recording.getFrames().size(),
            recording.getId(),
            recording.getId()
        );
        
        plugin.getServer().getOnlinePlayers().stream()
            .filter(p -> p.hasPermission("antixrayai.admin"))
            .forEach(admin -> admin.sendMessage(message));
    }
    
    /**
     * Проверить, записывается ли игрок в данный момент
     */
    public boolean isRecording(Player player) {
        return recordings.containsKey(player.getUniqueId());
    }
    
    /**
     * Получить все завершенные записи
     */
    public List<PlayerRecording> getCompletedRecordings() {
        return new ArrayList<>(completedRecordings);
    }
    
    /**
     * Получить запись по ID
     */
    public PlayerRecording getRecording(int id) {
        return completedRecordings.stream()
            .filter(r -> r.getId() == id)
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Удалить запись по ID
     */
    public boolean deleteRecording(int id) {
        return completedRecordings.removeIf(r -> r.getId() == id);
    }
    
    /**
     * Получить активные записи
     */
    public Map<UUID, PlayerRecording> getActiveRecordings() {
        return new HashMap<>(recordings);
    }
    
    /**
     * Остановить все активные записи (при выключении плагина)
     */
    public void stopAllRecordings() {
        for (UUID playerId : new HashSet<>(recordings.keySet())) {
            stopRecording(playerId, "Плагин выключен");
        }
    }
}