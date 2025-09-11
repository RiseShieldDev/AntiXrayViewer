package com.example.antixrayai.replay;

import com.example.antixrayai.AntiXrayAI;
import com.example.antixrayai.data.PlayerRecording;
import com.example.antixrayai.data.RecordFrame;
import com.example.antixrayai.data.BlockEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Сессия воспроизведения записи для администратора
 */
public class ReplaySession {
    
    private final AntiXrayAI plugin;
    private final Player viewer;
    private final PlayerRecording recording;
    private BukkitTask replayTask;
    private int currentFrameIndex = 0;
    private Location originalLocation;
    private GameMode originalGameMode;
    private boolean isActive = false;
    private final Map<String, Integer> activeBlockBreaking = new HashMap<>();
    private final Map<Location, BlockData> fakeBlocks = new HashMap<>();
    private final Set<Location> brokenBlocks = new HashSet<>();
    
    public ReplaySession(AntiXrayAI plugin, Player viewer, PlayerRecording recording) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.recording = recording;
    }
    
    /**
     * Начать воспроизведение записи
     */
    public void start() {
        if (isActive) {
            return;
        }
        
        isActive = true;
        currentFrameIndex = 0;
        
        // Сохраняем оригинальное состояние игрока
        originalLocation = viewer.getLocation().clone();
        originalGameMode = viewer.getGameMode();
        
        // Переводим в режим наблюдателя
        viewer.setGameMode(GameMode.SPECTATOR);
        
        // Телепортируем к первому кадру
        RecordFrame firstFrame = recording.getFrame(0);
        if (firstFrame != null) {
            teleportToFrame(firstFrame);
        }
        
        // Показываем информацию о загрузке
        viewer.sendTitle(
            "§eЗагрузка записи...",
            "§7Подготовка блоков",
            10, 40, 10
        );
        
        // Задержка для загрузки чанков и затем сканирование блоков
        new BukkitRunnable() {
            @Override
            public void run() {
                // Предварительно сканируем все блоки, которые будут сломаны
                prescanBlockEvents();
                
                // Еще одна небольшая задержка для отправки пакетов
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        // Показываем информацию о начале воспроизведения
                        viewer.sendTitle(
                            "§aВоспроизведение записи",
                            "§7Игрок: §f" + recording.getPlayerName(),
                            10, 70, 20
                        );
                        
                        // Запускаем воспроизведение
                        startReplayTask();
                    }
                }.runTaskLater(plugin, 10); // 0.5 секунды дополнительно
            }
        }.runTaskLater(plugin, 20); // 1 секунда задержки для загрузки чанков
    }
    
    /**
     * Предварительное сканирование всех событий блоков для показа фейковых блоков
     */
    private void prescanBlockEvents() {
        Set<String> processedBlocks = new HashSet<>();
        int restoredCount = 0;
        int skippedCount = 0;
        
        for (RecordFrame frame : recording.getFrames()) {
            if (frame.hasBlockEvents()) {
                for (BlockEvent event : frame.getBlockEvents()) {
                    if (event.getType() == BlockEvent.EventType.BREAK_COMPLETE) {
                        World world = plugin.getServer().getWorld(event.getWorld());
                        if (world != null) {
                            Location loc = new Location(world, event.getX(), event.getY(), event.getZ());
                            String blockKey = getLocationKey(loc);
                            
                            // Проверяем, не обработали ли мы уже этот блок
                            if (!processedBlocks.contains(blockKey)) {
                                processedBlocks.add(blockKey);
                                
                                // Убеждаемся, что чанк загружен
                                if (!loc.getChunk().isLoaded()) {
                                    loc.getChunk().load();
                                }
                                
                                // Получаем текущий блок на этой позиции
                                Block currentBlock = loc.getBlock();
                                
                                // Если блок пустой или отличается от оригинального
                                if (currentBlock.getType() == Material.AIR ||
                                    currentBlock.getType() != event.getBlockType()) {
                                    
                                    // Создаем BlockData для фейкового блока
                                    BlockData fakeBlockData = event.getBlockType().createBlockData();
                                    
                                    // Сохраняем реальное состояние блока
                                    fakeBlocks.put(loc, currentBlock.getBlockData());
                                    
                                    // Отправляем фейковый блок зрителю с небольшой задержкой
                                    // для каждой группы блоков
                                    final int currentBatch = restoredCount / 10; // Группы по 10 блоков
                                    new BukkitRunnable() {
                                        @Override
                                        public void run() {
                                            viewer.sendBlockChange(loc, fakeBlockData);
                                        }
                                    }.runTaskLater(plugin, currentBatch * 2L); // Задержка между группами
                                    
                                    restoredCount++;
                                } else {
                                    skippedCount++;
                                }
                            }
                        }
                    }
                }
            }
        }
        
        viewer.sendMessage(String.format("§7Восстановлено блоков: §e%d§7, пропущено: §8%d",
                                        restoredCount, skippedCount));
        
        // Повторная отправка всех блоков через секунду для надежности
        if (!fakeBlocks.isEmpty()) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    for (Map.Entry<Location, BlockData> entry : fakeBlocks.entrySet()) {
                        Location loc = entry.getKey();
                        // Получаем материал блока из события
                        Material blockMaterial = getBlockMaterialAtLocation(loc);
                        if (blockMaterial != null && blockMaterial != Material.AIR) {
                            viewer.sendBlockChange(loc, blockMaterial.createBlockData());
                        }
                    }
                    viewer.sendMessage("§7Блоки обновлены повторно для надежности");
                }
            }.runTaskLater(plugin, 40L); // 2 секунды после начала
        }
    }
    
    /**
     * Получить материал блока из записанных событий для конкретной локации
     */
    private Material getBlockMaterialAtLocation(Location loc) {
        for (RecordFrame frame : recording.getFrames()) {
            if (frame.hasBlockEvents()) {
                for (BlockEvent event : frame.getBlockEvents()) {
                    if (event.getType() == BlockEvent.EventType.BREAK_COMPLETE &&
                        event.getWorld().equals(loc.getWorld().getName()) &&
                        event.getX() == loc.getBlockX() &&
                        event.getY() == loc.getBlockY() &&
                        event.getZ() == loc.getBlockZ()) {
                        return event.getBlockType();
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * Остановить воспроизведение
     */
    public void stop() {
        if (!isActive) {
            return;
        }
        
        isActive = false;
        
        // Останавливаем задачу
        if (replayTask != null) {
            replayTask.cancel();
            replayTask = null;
        }
        
        // Удаляем все анимации ломания блоков
        clearAllBlockBreakingAnimations();
        
        // Восстанавливаем реальные блоки для зрителя
        restoreRealBlocks();
        
        // Возвращаем игрока в исходное состояние
        if (originalLocation != null) {
            viewer.teleport(originalLocation);
        }
        if (originalGameMode != null) {
            viewer.setGameMode(originalGameMode);
        }
        
        // Очищаем title
        viewer.resetTitle();
        
        // Сообщение об остановке
        viewer.sendMessage("§7Воспроизведение остановлено.");
    }
    
    /**
     * Восстановить реальные блоки для зрителя
     */
    private void restoreRealBlocks() {
        for (Map.Entry<Location, BlockData> entry : fakeBlocks.entrySet()) {
            Location loc = entry.getKey();
            BlockData realBlockData = entry.getValue();
            
            // Восстанавливаем реальный блок для зрителя
            viewer.sendBlockChange(loc, realBlockData);
        }
        
        fakeBlocks.clear();
        brokenBlocks.clear();
    }
    
    /**
     * Запустить задачу воспроизведения
     */
    private void startReplayTask() {
        // Интервал воспроизведения - каждые 2 тика (как и запись)
        replayTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (currentFrameIndex >= recording.getFrameCount()) {
                    // Воспроизведение завершено
                    viewer.sendMessage("§aВоспроизведение завершено!");
                    viewer.sendTitle(
                        "§aВоспроизведение завершено",
                        "§7Всего кадров: §f" + recording.getFrameCount(),
                        10, 40, 20
                    );
                    stop();
                    return;
                }
                
                // Воспроизводим текущий кадр
                RecordFrame frame = recording.getFrame(currentFrameIndex);
                if (frame != null) {
                    playFrame(frame);
                }
                
                currentFrameIndex++;
                
                // Показываем прогресс в action bar
                showProgress();
            }
        }.runTaskTimer(plugin, 0, 2); // Каждые 2 тика
    }
    
    /**
     * Воспроизвести один кадр
     */
    private void playFrame(RecordFrame frame) {
        // Телепортируем зрителя к позиции из кадра
        teleportToFrame(frame);
        
        // Обрабатываем события блоков
        if (frame.hasBlockEvents()) {
            for (BlockEvent event : frame.getBlockEvents()) {
                processBlockEvent(event);
            }
        }
        
        // Показываем состояние игрока
        String status = buildStatusString(frame);
        viewer.sendActionBar(status);
    }
    
    /**
     * Обработать событие блока
     */
    private void processBlockEvent(BlockEvent event) {
        World world = plugin.getServer().getWorld(event.getWorld());
        if (world == null) {
            return;
        }
        
        Location blockLoc = new Location(world, event.getX(), event.getY(), event.getZ());
        String blockKey = getBlockKey(event);
        
        switch (event.getType()) {
            case BREAK_START:
            case BREAK_PROGRESS:
                // Показываем анимацию ломания блока
                sendBlockBreakAnimation(blockLoc, event.getBreakStage());
                activeBlockBreaking.put(blockKey, event.getBreakStage());
                break;
                
            case BREAK_COMPLETE:
                // Показываем полное разрушение и частицы
                sendBlockBreakAnimation(blockLoc, 9);
                
                // Показываем частицы разрушения блока
                world.playEffect(blockLoc, org.bukkit.Effect.STEP_SOUND, event.getBlockType());
                
                // Убираем блок для зрителя (показываем воздух)
                if (!brokenBlocks.contains(blockLoc)) {
                    brokenBlocks.add(blockLoc);
                    // Отправляем воздух несколько раз для надежности
                    viewer.sendBlockChange(blockLoc, Material.AIR.createBlockData());
                    
                    // Повторная отправка через 1 тик
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            viewer.sendBlockChange(blockLoc, Material.AIR.createBlockData());
                        }
                    }.runTaskLater(plugin, 1);
                }
                
                // Убираем анимацию через небольшую задержку
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        sendBlockBreakAnimation(blockLoc, -1);
                        activeBlockBreaking.remove(blockKey);
                    }
                }.runTaskLater(plugin, 2);
                break;
                
            case BREAK_CANCEL:
                // Убираем анимацию ломания
                sendBlockBreakAnimation(blockLoc, -1);
                activeBlockBreaking.remove(blockKey);
                break;
        }
    }
    
    /**
     * Отправить анимацию ломания блока
     */
    private void sendBlockBreakAnimation(Location loc, int stage) {
        try {
            // Используем NMS для отправки пакета анимации ломания блока
            // Это универсальный способ для Paper 1.21.4
            
            // Генерируем уникальный ID для анимации на основе координат
            int entityId = (loc.getBlockX() * 73856093) ^ 
                          (loc.getBlockY() * 19349663) ^ 
                          (loc.getBlockZ() * 83492791);
            
            // Отправляем блок-дамаж пакет
            if (stage >= 0 && stage <= 9) {
                // Показываем анимацию ломания
                float damage = stage / 9.0f;
                viewer.sendBlockDamage(loc, damage);
                
                // Для последнего этапа (полное разрушение) отправляем дважды
                if (stage == 9) {
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            viewer.sendBlockDamage(loc, 1.0f);
                        }
                    }.runTaskLater(plugin, 1);
                }
            } else {
                // Убираем анимацию (stage = -1)
                viewer.sendBlockDamage(loc, 0.0f);
            }
            
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось отправить анимацию ломания блока: " + e.getMessage());
        }
    }
    
    /**
     * Очистить все анимации ломания блоков
     */
    private void clearAllBlockBreakingAnimations() {
        for (String blockKey : activeBlockBreaking.keySet()) {
            String[] parts = blockKey.split(":");
            if (parts.length == 4) {
                World world = plugin.getServer().getWorld(parts[0]);
                if (world != null) {
                    Location loc = new Location(
                        world,
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3])
                    );
                    sendBlockBreakAnimation(loc, -1);
                }
            }
        }
        activeBlockBreaking.clear();
    }
    
    /**
     * Получить ключ блока для хранения
     */
    private String getBlockKey(BlockEvent event) {
        return event.getWorld() + ":" + event.getX() + ":" + event.getY() + ":" + event.getZ();
    }
    
    /**
     * Получить ключ локации для хранения
     */
    private String getLocationKey(Location loc) {
        return loc.getWorld().getName() + ":" + 
               loc.getBlockX() + ":" + 
               loc.getBlockY() + ":" + 
               loc.getBlockZ();
    }
    
    /**
     * Телепортировать зрителя к позиции из кадра
     */
    private void teleportToFrame(RecordFrame frame) {
        World world = plugin.getServer().getWorld(frame.getWorld());
        if (world == null) {
            viewer.sendMessage("§cМир '" + frame.getWorld() + "' не найден!");
            stop();
            return;
        }
        
        Location loc = new Location(
            world,
            frame.getX(),
            frame.getY(),
            frame.getZ(),
            frame.getYaw(),
            frame.getPitch()
        );
        
        viewer.teleport(loc);
    }
    
    /**
     * Построить строку состояния игрока
     */
    private String buildStatusString(RecordFrame frame) {
        StringBuilder sb = new StringBuilder();
        
        // Иконки состояний
        if (frame.isSneaking()) {
            sb.append("§e⬇ ");
        }
        if (frame.isSprinting()) {
            sb.append("§b⚡ ");
        }
        if (frame.isFlying()) {
            sb.append("§f✈ ");
        }
        
        // Проверяем, есть ли события блоков
        if (frame.hasBlockEvents()) {
            boolean hasBreaking = false;
            for (BlockEvent event : frame.getBlockEvents()) {
                if (event.getType() == BlockEvent.EventType.BREAK_START ||
                    event.getType() == BlockEvent.EventType.BREAK_PROGRESS ||
                    event.getType() == BlockEvent.EventType.BREAK_COMPLETE) {
                    hasBreaking = true;
                    break;
                }
            }
            if (hasBreaking) {
                sb.append("§c⛏ ");
            }
        }
        
        // Здоровье и еда
        sb.append(String.format(
            "§c❤ %.1f §6🍖 %d",
            frame.getHealth(),
            frame.getFoodLevel()
        ));
        
        // Координаты
        sb.append(String.format(
            " §7| §fX:§b%.1f §fY:§b%.1f §fZ:§b%.1f",
            frame.getX(),
            frame.getY(),
            frame.getZ()
        ));
        
        return sb.toString();
    }
    
    /**
     * Показать прогресс воспроизведения
     */
    private void showProgress() {
        int totalFrames = recording.getFrameCount();
        int percent = (currentFrameIndex * 100) / totalFrames;
        
        // Создаем прогресс-бар
        int barLength = 20;
        int filled = (percent * barLength) / 100;
        
        StringBuilder progressBar = new StringBuilder("§8[");
        for (int i = 0; i < barLength; i++) {
            if (i < filled) {
                progressBar.append("§a█");
            } else {
                progressBar.append("§7░");
            }
        }
        progressBar.append("§8]");
        
        // Время
        int currentSeconds = (currentFrameIndex * 2) / 20; // 2 тика = 0.1 сек
        int totalSeconds = recording.getDurationSeconds();
        
        // Подсчитываем общее количество событий блоков
        int blockEventsCount = 0;
        for (int i = 0; i <= currentFrameIndex && i < totalFrames; i++) {
            RecordFrame frame = recording.getFrame(i);
            if (frame != null && frame.hasBlockEvents()) {
                blockEventsCount += frame.getBlockEvents().size();
            }
        }
        
        String progressText = String.format(
            "%s §f%d/%d §7(%ds/%ds) §6⛏ %d §e⬜ %d",
            progressBar.toString(),
            currentFrameIndex,
            totalFrames,
            currentSeconds,
            totalSeconds,
            blockEventsCount,
            fakeBlocks.size()
        );
        
        // Отправляем в табlist footer
        viewer.setPlayerListFooter(progressText);
    }
    
    /**
     * Проверить, активна ли сессия
     */
    public boolean isActive() {
        return isActive;
    }
    
    /**
     * Получить зрителя
     */
    public Player getViewer() {
        return viewer;
    }
    
    /**
     * Получить запись
     */
    public PlayerRecording getRecording() {
        return recording;
    }
}