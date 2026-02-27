package com.example.antixrayviewer.managers;

import com.example.antixrayviewer.AntiXrayViewer;
import com.example.antixrayviewer.data.PlayerRecording;
import com.example.antixrayviewer.data.RecordFrame;
import com.example.antixrayviewer.data.BlockEvent;
import com.example.antixrayviewer.storage.RecordingStorage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RecordingManager implements Listener {
    
    private final AntiXrayViewer plugin;
    private final Map<UUID, PlayerRecording> recordings = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> recordingTasks = new HashMap<>();
    private final List<PlayerRecording> completedRecordings = new ArrayList<>();
    private final Map<UUID, List<BlockEvent>> pendingBlockEvents = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> blockBreakingProgress = new ConcurrentHashMap<>();
    private final RecordingStorage storage;
    
    private final long recordingDuration;
    private final int recordIntervalTicks;
    private final int maxSavedRecordings;
    
    public RecordingManager(AntiXrayViewer plugin) {
        this.plugin = plugin;
        
        this.recordingDuration = plugin.getConfig().getInt("recording.duration", 180) * 1000L;
        this.recordIntervalTicks = plugin.getConfig().getInt("recording.interval-ticks", 2);
        this.maxSavedRecordings = plugin.getConfig().getInt("recording.max-saved", 50);
        
        // Создаем хранилище записей
        this.storage = new RecordingStorage(plugin);
        
        // Загружаем сохраненные записи
        loadSavedRecordings();
        
        // Регистрируем слушатель событий
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }
    
    /**
     * Загрузить сохраненные записи из файлов
     */
    private void loadSavedRecordings() {
        List<PlayerRecording> loaded = storage.loadAllRecordings();
        completedRecordings.addAll(loaded);
        
        // Сортируем по ID в обратном порядке (новые первые)
        completedRecordings.sort((r1, r2) -> Integer.compare(r2.getId(), r1.getId()));
        
        // Ограничиваем количество записей (если maxSavedRecordings > 0)
        if (maxSavedRecordings > 0) {
            while (completedRecordings.size() > maxSavedRecordings) {
                PlayerRecording removed = completedRecordings.remove(completedRecordings.size() - 1);
                storage.deleteRecording(removed.getId());
            }
        }
        
        plugin.getLogger().info("Загружено " + loaded.size() + " записей из хранилища");
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
        
        String currentPlayerName = player.getName();
        PlayerRecording recording = new PlayerRecording(
            playerId,
            currentPlayerName,
            reason,
            System.currentTimeMillis()
        );
        
        // Дополнительное логирование для отладки проблемы с никами
        plugin.getLogger().info(String.format(
            "Создана запись: UUID=%s, Name=%s, Reason=%s",
            playerId.toString(), currentPlayerName, reason
        ));
        
        recordings.put(playerId, recording);
        
        // Создаем задачу для периодической записи позиции игрока
        BukkitTask task = new BukkitRunnable() {
            private long startTime = System.currentTimeMillis();
            
            @Override
            public void run() {
                // Проверяем, все еще ли идет запись (важная проверка!)
                if (!recordings.containsKey(playerId)) {
                    // Запись была остановлена извне, прекращаем задачу
                    this.cancel();
                    return;
                }
                
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
        
        // Сначала отменяем задачу
        if (task != null) {
            task.cancel();
        }
        
        // Дополнительная проверка - убеждаемся, что запись удалена из активных
        recordings.remove(playerId);
        
        // Очищаем буферы событий
        pendingBlockEvents.remove(playerId);
        blockBreakingProgress.remove(playerId);
        
        if (recording != null) {
            recording.setEndTime(System.currentTimeMillis());
            recording.setEndReason(endReason);
            
            // Сохраняем запись
            saveRecording(recording);
            
            // Подсчитываем общее количество событий блоков
            int totalBlockEvents = 0;
            for (RecordFrame frame : recording.getFrames()) {
                if (frame.hasBlockEvents()) {
                    totalBlockEvents += frame.getBlockEvents().size();
                }
            }
            
            plugin.getLogger().info(String.format(
                "Остановлена запись игрока %s. Причина: %s. Записано кадров: %d, событий блоков: %d",
                recording.getPlayerName(), endReason, recording.getFrames().size(), totalBlockEvents
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
        
        // Добавляем накопленные события блоков к кадру
        UUID playerId = player.getUniqueId();
        List<BlockEvent> events = pendingBlockEvents.remove(playerId);
        if (events != null && !events.isEmpty()) {
            for (BlockEvent event : events) {
                frame.addBlockEvent(event);
            }
        }
        
        recording.addFrame(frame);
    }
    
    /**
     * Сохранить запись в хранилище
     */
    private void saveRecording(PlayerRecording recording) {
        completedRecordings.add(0, recording); // Добавляем в начало списка
        
        // Сохраняем в файл
        storage.saveRecording(recording);
        
        // Ограничиваем количество сохраненных записей (если maxSavedRecordings > 0)
        if (maxSavedRecordings > 0) {
            while (completedRecordings.size() > maxSavedRecordings) {
                PlayerRecording removed = completedRecordings.remove(completedRecordings.size() - 1);
                storage.deleteRecording(removed.getId());
            }
        }
    }
    
    /**
     * Уведомить администраторов о завершении записи
     */
    private void notifyAdminsRecordingComplete(PlayerRecording recording) {
        String message = String.format(
            "§a[AntiXrayViewer] §7Запись игрока §f%s §7завершена. " +
            "§7Кадров: §e%d §7| ID: §b#%d §7| §f/axv view %d",
            recording.getPlayerName(),
            recording.getFrames().size(),
            recording.getId(),
            recording.getId()
        );
        
        plugin.getServer().getOnlinePlayers().stream()
            .filter(p -> p.hasPermission("antixrayviewer.admin"))
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
        // Синхронизируем с файловой системой
        syncRecordingsWithFileSystem();
        return new ArrayList<>(completedRecordings);
    }
    
    /**
     * Синхронизировать записи в памяти с файловой системой
     * Удаляет из памяти записи, файлы которых не существуют
     */
    public void syncRecordingsWithFileSystem() {
        List<PlayerRecording> toRemove = new ArrayList<>();
        
        for (PlayerRecording recording : completedRecordings) {
            if (!storage.recordingFileExists(recording.getId())) {
                toRemove.add(recording);
                plugin.getLogger().info("Запись #" + recording.getId() + " удалена из памяти (файл не найден)");
            }
        }
        
        // Удаляем записи без файлов из памяти
        completedRecordings.removeAll(toRemove);
        
        if (!toRemove.isEmpty()) {
            plugin.getLogger().info("Синхронизация: удалено " + toRemove.size() + " записей без файлов");
        }
    }
    
    /**
     * Перезагрузить все записи из файлов
     * Полностью обновляет список записей из файловой системы
     */
    public void reloadRecordings() {
        completedRecordings.clear();
        loadSavedRecordings();
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
        boolean removedFromList = completedRecordings.removeIf(r -> r.getId() == id);
        boolean removedFromStorage = storage.deleteRecording(id);
        return removedFromList || removedFromStorage;
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
        // Создаем копию множества, чтобы избежать ConcurrentModificationException
        Set<UUID> activeRecordings = new HashSet<>(recordings.keySet());
        
        for (UUID playerId : activeRecordings) {
            stopRecording(playerId, "Плагин выключен");
        }
        
        // Дополнительная очистка на случай, если что-то осталось
        recordings.clear();
        
        // Отменяем все оставшиеся задачи
        for (BukkitTask task : recordingTasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        recordingTasks.clear();
    }
    
    /**
     * Принудительно остановить запись игрока (для команд администратора)
     */
    public boolean forceStopRecording(String playerName) {
        for (Map.Entry<UUID, PlayerRecording> entry : recordings.entrySet()) {
            PlayerRecording recording = entry.getValue();
            if (recording.getPlayerName().equalsIgnoreCase(playerName)) {
                UUID playerId = entry.getKey();
                stopRecording(playerId, "Остановлено администратором");
                return true;
            }
        }
        return false;
    }
    
    // ========== ОБРАБОТЧИКИ СОБЫТИЙ БЛОКОВ ==========
    
    /**
     * Обработка начала ломания блока
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        Player player = event.getPlayer();
        if (!isRecording(player)) {
            return;
        }
        
        Block block = event.getBlock();
        String blockKey = getBlockKey(block);
        UUID playerId = player.getUniqueId();
        
        // Записываем время начала ломания
        blockBreakingProgress.computeIfAbsent(playerId, k -> new HashMap<>())
            .put(blockKey, System.currentTimeMillis());
        
        // Добавляем событие начала ломания в буфер
        BlockEvent blockEvent = new BlockEvent(
            System.currentTimeMillis(),
            BlockEvent.EventType.BREAK_START,
            block.getX(),
            block.getY(),
            block.getZ(),
            block.getWorld().getName(),
            block.getType(),
            0.1f,
            -1 // Не используем entityId, так как он может быть неуникальным
        );
        
        pendingBlockEvents.computeIfAbsent(playerId, k -> new ArrayList<>()).add(blockEvent);
    }
    
    /**
     * Обработка отмены ломания блока
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockDamageAbort(BlockDamageAbortEvent event) {
        Player player = event.getPlayer();
        if (!isRecording(player)) {
            return;
        }
        
        Block block = event.getBlock();
        String blockKey = getBlockKey(block);
        UUID playerId = player.getUniqueId();
        
        // Удаляем прогресс ломания
        Map<String, Long> progress = blockBreakingProgress.get(playerId);
        if (progress != null) {
            progress.remove(blockKey);
        }
        
        // Добавляем событие отмены ломания в буфер
        BlockEvent blockEvent = new BlockEvent(
            System.currentTimeMillis(),
            BlockEvent.EventType.BREAK_CANCEL,
            block.getX(),
            block.getY(),
            block.getZ(),
            block.getWorld().getName(),
            block.getType()
        );
        
        pendingBlockEvents.computeIfAbsent(playerId, k -> new ArrayList<>()).add(blockEvent);
    }
    
    /**
     * Обработка полного ломания блока
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!isRecording(player)) {
            return;
        }
        
        Block block = event.getBlock();
        String blockKey = getBlockKey(block);
        UUID playerId = player.getUniqueId();
        
        // Удаляем прогресс ломания
        Map<String, Long> progress = blockBreakingProgress.get(playerId);
        if (progress != null) {
            progress.remove(blockKey);
        }
        
        // Добавляем событие завершения ломания в буфер
        BlockEvent blockEvent = new BlockEvent(
            System.currentTimeMillis(),
            BlockEvent.EventType.BREAK_COMPLETE,
            block.getX(),
            block.getY(),
            block.getZ(),
            block.getWorld().getName(),
            block.getType(),
            1.0f,
            -1 // Не используем entityId, так как он может быть неуникальным
        );
        
        pendingBlockEvents.computeIfAbsent(playerId, k -> new ArrayList<>()).add(blockEvent);
    }
    
    /**
     * Обработка установки блока
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!isRecording(player)) {
            return;
        }
        
        Block block = event.getBlockPlaced();
        UUID playerId = player.getUniqueId();
        
        // Добавляем событие установки блока в буфер
        BlockEvent blockEvent = new BlockEvent(
            System.currentTimeMillis(),
            BlockEvent.EventType.PLACE,
            block.getX(),
            block.getY(),
            block.getZ(),
            block.getWorld().getName(),
            block.getType(),
            1.0f,
            -1 // Не используем entityId, так как он может быть неуникальным
        );
        
        pendingBlockEvents.computeIfAbsent(playerId, k -> new ArrayList<>()).add(blockEvent);
        
        plugin.getLogger().fine(String.format(
            "Записана установка блока: %s в %d,%d,%d мире %s игроком %s",
            block.getType(), block.getX(), block.getY(), block.getZ(),
            block.getWorld().getName(), player.getName()
        ));
    }
    
    /**
     * Создать уникальный ключ для блока
     */
    private String getBlockKey(Block block) {
        return block.getWorld().getName() + ":" +
               block.getX() + ":" +
               block.getY() + ":" +
               block.getZ();
    }
    
}