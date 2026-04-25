package com.example.antixrayviewer.replay;

import com.example.antixrayviewer.AntiXrayViewer;
import com.example.antixrayviewer.data.PlayerRecording;
import com.example.antixrayviewer.data.RecordFrame;
import com.example.antixrayviewer.data.BlockEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Сессия воспроизведения записи для администратора
 */
public class ReplaySession {
    
    private final AntiXrayViewer plugin;
    private final Player viewer;
    private final PlayerRecording recording;
    private BukkitTask replayTask;
    private int currentFrameIndex = 0;
    private Location originalLocation;
    private GameMode originalGameMode;
    private boolean isActive = false;
    
    // Улучшенная система отслеживания блоков
    private final Map<String, BlockState> blockStates = new HashMap<>(); // Текущее состояние блоков в воспроизведении
    private final Map<String, BlockData> originalBlocks = new HashMap<>(); // Оригинальные блоки в мире
    private final Map<String, Integer> blockBreakingProgress = new HashMap<>(); // Прогресс ломания блоков
    private final Set<String> processedLocations = new HashSet<>(); // Обработанные локации при предзагрузке
    
    // Внутренний класс для хранения состояния блока
    private static class BlockState {
        Material material;
        BlockData blockData;
        boolean exists;
        
        BlockState(Material material, boolean exists) {
            this.material = material;
            this.exists = exists;
            if (material != null && material != Material.AIR) {
                this.blockData = material.createBlockData();
            }
        }
    }
    
    public ReplaySession(AntiXrayViewer plugin, Player viewer, PlayerRecording recording) {
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
            "§7Анализ блоков и загрузка чанков",
            10, 60, 10
        );
        
        // Задержка для загрузки чанков и анализа блоков
        new BukkitRunnable() {
            @Override
            public void run() {
                // Анализируем и подготавливаем все блоки
                analyzeAndPrepareBlocks();
                
                // Задержка для синхронизации с клиентом
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
                }.runTaskLater(plugin, 20); // 1 секунда для синхронизации
            }
        }.runTaskLater(plugin, 40); // 2 секунды для загрузки чанков
    }
    
    /**
     * Анализировать и подготовить все блоки для воспроизведения
     */
    private void analyzeAndPrepareBlocks() {
        Map<String, Material> finalBlockStates = new HashMap<>();
        Map<String, Material> initialBlockStates = new HashMap<>();
        Set<String> allBlockLocations = new HashSet<>();
        
        viewer.sendMessage("§eАнализ записи...");
        
        // Проходим по всем кадрам и собираем информацию о блоках
        for (RecordFrame frame : recording.getFrames()) {
            if (frame.hasBlockEvents()) {
                for (BlockEvent event : frame.getBlockEvents()) {
                    World world = plugin.getServer().getWorld(event.getWorld());
                    if (world == null) continue;
                    
                    String blockKey = getBlockKey(event.getWorld(), event.getX(), event.getY(), event.getZ());
                    allBlockLocations.add(blockKey);
                    
                    // Отслеживаем изменения блоков
                    switch (event.getType()) {
                        case BREAK_COMPLETE:
                            if (!initialBlockStates.containsKey(blockKey)) {
                                initialBlockStates.put(blockKey, event.getBlockType());
                            }
                            finalBlockStates.put(blockKey, Material.AIR);
                            break;
                            
                        case PLACE:
                            if (!initialBlockStates.containsKey(blockKey)) {
                                initialBlockStates.put(blockKey, Material.AIR);
                            }
                            finalBlockStates.put(blockKey, event.getBlockType());
                            break;
                            
                        case BREAK_CANCEL:
                        case BREAK_START:
                        case BREAK_PROGRESS:
                            // Эти события не требуют обработки при предзагрузке
                            break;
                    }
                }
            }
        }
        
        viewer.sendMessage(String.format("§7Найдено §e%d§7 уникальных блоков для обработки", allBlockLocations.size()));
        
        // Загружаем чанки и восстанавливаем начальное состояние блоков
        int restoredCount = 0;
        int chunksLoaded = 0;
        Set<String> loadedChunks = new HashSet<>();
        
        for (String blockKey : allBlockLocations) {
            String[] parts = blockKey.split(":");
            if (parts.length != 4) continue;
            
            World world = plugin.getServer().getWorld(parts[0]);
            if (world == null) continue;
            
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            
            Location loc = new Location(world, x, y, z);
            
            // Загружаем чанк если нужно
            String chunkKey = world.getName() + ":" + (x >> 4) + ":" + (z >> 4);
            if (!loadedChunks.contains(chunkKey)) {
                if (!loc.getChunk().isLoaded()) {
                    loc.getChunk().load();
                    chunksLoaded++;
                }
                loadedChunks.add(chunkKey);
            }
            
            Block currentBlock = loc.getBlock();
            
            // Сохраняем текущее состояние блока в мире
            originalBlocks.put(blockKey, currentBlock.getBlockData().clone());
            
            // Восстанавливаем начальное состояние для воспроизведения
            Material initialMaterial = initialBlockStates.get(blockKey);
            if (initialMaterial != null) {
                // Устанавливаем начальное состояние блока
                if (initialMaterial == Material.AIR) {
                    // Блок должен быть пустым в начале
                    if (currentBlock.getType() != Material.AIR) {
                        viewer.sendBlockChange(loc, Material.AIR.createBlockData());
                        blockStates.put(blockKey, new BlockState(Material.AIR, false));
                        restoredCount++;
                    }
                } else {
                    // Блок должен существовать в начале
                    if (currentBlock.getType() != initialMaterial) {
                        BlockData blockData = initialMaterial.createBlockData();
                        viewer.sendBlockChange(loc, blockData);
                        blockStates.put(blockKey, new BlockState(initialMaterial, true));
                        restoredCount++;
                    } else {
                        blockStates.put(blockKey, new BlockState(initialMaterial, true));
                    }
                }
            }
        }
        
        viewer.sendMessage(String.format("§7Загружено чанков: §e%d§7, восстановлено блоков: §a%d", 
                                        chunksLoaded, restoredCount));
        
        // Дополнительная синхронизация всех блоков
        new BukkitRunnable() {
            @Override
            public void run() {
                int syncCount = 0;
                for (Map.Entry<String, BlockState> entry : blockStates.entrySet()) {
                    String blockKey = entry.getKey();
                    BlockState state = entry.getValue();
                    
                    String[] parts = blockKey.split(":");
                    if (parts.length != 4) continue;
                    
                    World world = plugin.getServer().getWorld(parts[0]);
                    if (world == null) continue;
                    
                    Location loc = new Location(world, 
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3])
                    );
                    
                    if (state.exists && state.material != Material.AIR) {
                        viewer.sendBlockChange(loc, state.material.createBlockData());
                        syncCount++;
                    } else {
                        viewer.sendBlockChange(loc, Material.AIR.createBlockData());
                        syncCount++;
                    }
                }
                
                if (syncCount > 0) {
                    viewer.sendMessage(String.format("§7Синхронизировано блоков: §a%d", syncCount));
                }
            }
        }.runTaskLater(plugin, 10);
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
        
        // Очищаем все анимации ломания
        clearAllBlockBreakingAnimations();
        
        // Восстанавливаем оригинальные блоки
        restoreOriginalBlocks();
        
        // Возвращаем игрока в исходное состояние
        if (originalLocation != null) {
            viewer.teleport(originalLocation);
        }
        if (originalGameMode != null) {
            viewer.setGameMode(originalGameMode);
        }
        
        // Очищаем title и tab list
        viewer.resetTitle();
        viewer.setPlayerListHeader("");
        viewer.setPlayerListFooter("");
        
        // Сообщение об остановке
        viewer.sendMessage("§7Воспроизведение остановлено.");
    }
    
    /**
     * Восстановить оригинальные блоки в мире
     */
    private void restoreOriginalBlocks() {
        for (Map.Entry<String, BlockData> entry : originalBlocks.entrySet()) {
            String blockKey = entry.getKey();
            BlockData originalData = entry.getValue();
            
            String[] parts = blockKey.split(":");
            if (parts.length != 4) continue;
            
            World world = plugin.getServer().getWorld(parts[0]);
            if (world == null) continue;
            
            Location loc = new Location(world,
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3])
            );
            
            // Восстанавливаем оригинальный блок
            viewer.sendBlockChange(loc, originalData);
        }
        
        // Очищаем все коллекции
        blockStates.clear();
        originalBlocks.clear();
        blockBreakingProgress.clear();
        processedLocations.clear();
    }
    
    /**
     * Запустить задачу воспроизведения
     */
    private void startReplayTask() {
        replayTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (currentFrameIndex >= recording.getFrameCount()) {
                    viewer.sendMessage("§aВоспроизведение завершено!");
                    viewer.sendTitle(
                        "§aВоспроизведение завершено",
                        "§7Всего кадров: §f" + recording.getFrameCount(),
                        10, 40, 20
                    );
                    stop();
                    return;
                }
                
                RecordFrame frame = recording.getFrame(currentFrameIndex);
                if (frame != null) {
                    playFrame(frame);
                }
                
                currentFrameIndex++;
                showProgress();
            }
        }.runTaskTimer(plugin, 0, 2); // Каждые 2 тика
    }
    
    /**
     * Воспроизвести один кадр
     */
    private void playFrame(RecordFrame frame) {
        // Телепортируем зрителя
        teleportToFrame(frame);
        
        // Повторно отправляем виртуальные блоки рядом со зрителем
        // (после выгрузки/перезагрузки чанков клиентом фейковые блоки теряются)
        refreshBlocksNearViewer();
        
        // Обрабатываем события блоков
        if (frame.hasBlockEvents()) {
            for (BlockEvent event : frame.getBlockEvents()) {
                processBlockEvent(event);
            }
        }
        
        // Показываем состояние игрока
        viewer.sendActionBar(buildStatusString(frame));
    }
    
    /**
     * Радиус (в блоках), в пределах которого пересинхронизируем виртуальные блоки
     * около зрителя. ~96 блоков ≈ 6 чанков покрывает типичный render distance клиента.
     */
    private static final double REFRESH_RADIUS = 96.0;
    private static final double REFRESH_RADIUS_SQ = REFRESH_RADIUS * REFRESH_RADIUS;
    
    /**
     * Повторно отправить sendBlockChange для всех виртуальных блоков в радиусе зрителя.
     * Нужно потому, что при удалении зрителя клиент выгружает чанки и при возврате
     * получает реальное состояние мира с сервера, перетирая фейковые блоки.
     */
    private void refreshBlocksNearViewer() {
        if (blockStates.isEmpty()) return;
        
        Location viewerLoc = viewer.getLocation();
        World viewerWorld = viewerLoc.getWorld();
        if (viewerWorld == null) return;
        
        String viewerWorldName = viewerWorld.getName();
        double vx = viewerLoc.getX();
        double vy = viewerLoc.getY();
        double vz = viewerLoc.getZ();
        
        for (Map.Entry<String, BlockState> entry : blockStates.entrySet()) {
            String blockKey = entry.getKey();
            BlockState state = entry.getValue();
            
            String[] parts = blockKey.split(":");
            if (parts.length != 4) continue;
            
            // Только блоки в текущем мире зрителя
            if (!parts[0].equals(viewerWorldName)) continue;
            
            int bx, by, bz;
            try {
                bx = Integer.parseInt(parts[1]);
                by = Integer.parseInt(parts[2]);
                bz = Integer.parseInt(parts[3]);
            } catch (NumberFormatException e) {
                continue;
            }
            
            double dx = bx - vx;
            double dy = by - vy;
            double dz = bz - vz;
            double distSq = dx * dx + dy * dy + dz * dz;
            
            if (distSq > REFRESH_RADIUS_SQ) continue;
            
            Location loc = new Location(viewerWorld, bx, by, bz);
            if (state.exists && state.material != null && state.material != Material.AIR) {
                BlockData data = state.blockData != null
                    ? state.blockData
                    : state.material.createBlockData();
                viewer.sendBlockChange(loc, data);
            } else {
                viewer.sendBlockChange(loc, Material.AIR.createBlockData());
            }
        }
    }
    
    private void processBlockEvent(BlockEvent event) {
        World world = plugin.getServer().getWorld(event.getWorld());
        if (world == null) return;
        
        Location blockLoc = new Location(world, event.getX(), event.getY(), event.getZ());
        String blockKey = getBlockKey(event.getWorld(), event.getX(), event.getY(), event.getZ());
        
        switch (event.getType()) {
            case BREAK_START:
                // Начало ломания блока
                sendBlockBreakAnimation(blockLoc, 1);
                blockBreakingProgress.put(blockKey, 1);
                break;
                
            case BREAK_PROGRESS:
                // Прогресс ломания
                int stage = event.getBreakStage();
                sendBlockBreakAnimation(blockLoc, stage);
                blockBreakingProgress.put(blockKey, stage);
                break;
                
            case BREAK_COMPLETE:
                // Блок сломан
                // Показываем финальную анимацию
                sendBlockBreakAnimation(blockLoc, 9);
                
                // Показываем частицы
                world.playEffect(blockLoc, org.bukkit.Effect.STEP_SOUND, event.getBlockType());
                
                // Обновляем состояние блока
                blockStates.put(blockKey, new BlockState(Material.AIR, false));
                
                // Отправляем изменение блока клиенту несколько раз для надежности
                viewer.sendBlockChange(blockLoc, Material.AIR.createBlockData());
                
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        viewer.sendBlockChange(blockLoc, Material.AIR.createBlockData());
                    }
                }.runTaskLater(plugin, 1);
                
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        viewer.sendBlockChange(blockLoc, Material.AIR.createBlockData());
                    }
                }.runTaskLater(plugin, 2);
                
                // Убираем анимацию ломания
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        sendBlockBreakAnimation(blockLoc, -1);
                        blockBreakingProgress.remove(blockKey);
                    }
                }.runTaskLater(plugin, 3);
                break;
                
            case BREAK_CANCEL:
                // Отмена ломания
                sendBlockBreakAnimation(blockLoc, -1);
                blockBreakingProgress.remove(blockKey);
                break;
                
            case PLACE:
                // Установка блока
                // Обновляем состояние
                blockStates.put(blockKey, new BlockState(event.getBlockType(), true));
                
                // Создаем BlockData для нового блока
                BlockData newBlockData = event.getBlockType().createBlockData();
                
                // Отправляем изменение несколько раз для надежности
                viewer.sendBlockChange(blockLoc, newBlockData);
                
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        viewer.sendBlockChange(blockLoc, newBlockData);
                    }
                }.runTaskLater(plugin, 1);
                
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        viewer.sendBlockChange(blockLoc, newBlockData);
                    }
                }.runTaskLater(plugin, 2);
                
                // Воспроизводим звук
                world.playSound(blockLoc, newBlockData.getSoundGroup().getPlaceSound(), 1.0f, 1.0f);
                
                // Показываем частицы
                world.playEffect(blockLoc, org.bukkit.Effect.STEP_SOUND, event.getBlockType());
                break;
        }
    }
    
    /**
     * Отправить анимацию ломания блока
     */
    private void sendBlockBreakAnimation(Location loc, int stage) {
        try {
            if (stage >= 0 && stage <= 9) {
                float damage = stage / 9.0f;
                viewer.sendBlockDamage(loc, damage);
            } else {
                viewer.sendBlockDamage(loc, 0.0f);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка анимации ломания: " + e.getMessage());
        }
    }
    
    /**
     * Очистить все анимации ломания блоков
     */
    private void clearAllBlockBreakingAnimations() {
        for (Map.Entry<String, Integer> entry : blockBreakingProgress.entrySet()) {
            String blockKey = entry.getKey();
            String[] parts = blockKey.split(":");
            if (parts.length != 4) continue;
            
            World world = plugin.getServer().getWorld(parts[0]);
            if (world == null) continue;
            
            Location loc = new Location(world,
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3])
            );
            
            sendBlockBreakAnimation(loc, -1);
        }
        blockBreakingProgress.clear();
    }
    
    /**
     * Получить ключ блока
     */
    private String getBlockKey(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }
    
    /**
     * Телепортировать зрителя к кадру
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
     * Построить строку состояния
     */
    private String buildStatusString(RecordFrame frame) {
        StringBuilder sb = new StringBuilder();
        
        // Состояния игрока
        if (frame.isSneaking()) sb.append("§e⬇ ");
        if (frame.isSprinting()) sb.append("§b⚡ ");
        if (frame.isFlying()) sb.append("§f✈ ");
        
        // События блоков
        if (frame.hasBlockEvents()) {
            boolean hasBreaking = false;
            boolean hasPlacing = false;
            
            for (BlockEvent event : frame.getBlockEvents()) {
                if (event.getType() == BlockEvent.EventType.BREAK_START ||
                    event.getType() == BlockEvent.EventType.BREAK_PROGRESS ||
                    event.getType() == BlockEvent.EventType.BREAK_COMPLETE) {
                    hasBreaking = true;
                }
                if (event.getType() == BlockEvent.EventType.PLACE) {
                    hasPlacing = true;
                }
            }
            
            if (hasBreaking) sb.append("§c⛏ ");
            if (hasPlacing) sb.append("§a⬜ ");
        }
        
        // Здоровье и еда
        sb.append(String.format("§c❤ %.1f §6🍖 %d", frame.getHealth(), frame.getFoodLevel()));
        
        // Координаты
        sb.append(String.format(" §7| §fX:§b%.1f §fY:§b%.1f §fZ:§b%.1f",
            frame.getX(), frame.getY(), frame.getZ()));
        
        return sb.toString();
    }
    
    /**
     * Показать прогресс воспроизведения
     */
    private void showProgress() {
        int totalFrames = recording.getFrameCount();
        int percent = (currentFrameIndex * 100) / totalFrames;
        
        // Прогресс-бар
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
        int currentSeconds = (currentFrameIndex * 2) / 20;
        int totalSeconds = recording.getDurationSeconds();
        
        // Статистика блоков
        int activeBlocks = blockStates.size();
        int breakingBlocks = blockBreakingProgress.size();
        
        String progressText = String.format(
            "%s §f%d/%d §7(%ds/%ds) §6Блоков: %d §cЛомается: %d",
            progressBar.toString(),
            currentFrameIndex,
            totalFrames,
            currentSeconds,
            totalSeconds,
            activeBlocks,
            breakingBlocks
        );
        
        viewer.setPlayerListFooter(progressText);
    }
    
    // Геттеры
    public boolean isActive() { return isActive; }
    public Player getViewer() { return viewer; }
    public PlayerRecording getRecording() { return recording; }
}