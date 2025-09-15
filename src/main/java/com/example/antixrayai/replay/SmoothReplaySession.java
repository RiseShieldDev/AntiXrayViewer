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
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Сессия воспроизведения с плавной интерполяцией движения камеры
 */
public class SmoothReplaySession {
    
    private final AntiXrayAI plugin;
    private final Player viewer;
    private final PlayerRecording recording;
    private BukkitTask replayTask;
    private int currentFrameIndex = 0;
    private Location originalLocation;
    private GameMode originalGameMode;
    private boolean isActive = false;
    
    // Интерполяция
    private Location currentLocation;
    private Location targetLocation;
    private float interpolationProgress = 0f;
    private static final float INTERPOLATION_SPEED = 0.25f; // Скорость интерполяции (0.0 - 1.0)
    private static final int TICKS_PER_FRAME = 2; // Тики между кадрами записи
    private static final int INTERPOLATION_UPDATES_PER_FRAME = 4; // Сколько раз обновлять позицию между кадрами
    
    // Буфер кадров для предварительного просмотра
    private static final int FRAME_BUFFER_SIZE = 10;
    private final LinkedList<RecordFrame> frameBuffer = new LinkedList<>();
    
    // Управление блоками
    private final Map<String, BlockState> blockStates = new HashMap<>();
    private final Map<String, BlockData> originalBlocks = new HashMap<>();
    private final Map<String, Integer> blockBreakingProgress = new HashMap<>();
    
    // Настройки сглаживания
    private float smoothingFactor = 0.15f; // Коэффициент сглаживания (0.0 - 1.0)
    private boolean useQuadraticInterpolation = true; // Использовать квадратичную интерполяцию
    private boolean usePredictiveSmoothing = true; // Предсказывающее сглаживание
    
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
    
    public SmoothReplaySession(AntiXrayAI plugin, Player viewer, PlayerRecording recording) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.recording = recording;
    }
    
    /**
     * Начать воспроизведение с плавной интерполяцией
     */
    public void start() {
        if (isActive) {
            return;
        }
        
        isActive = true;
        currentFrameIndex = 0;
        
        // Сохраняем оригинальное состояние
        originalLocation = viewer.getLocation().clone();
        originalGameMode = viewer.getGameMode();
        
        // Переводим в режим наблюдателя
        viewer.setGameMode(GameMode.SPECTATOR);
        
        // Инициализируем начальную позицию
        RecordFrame firstFrame = recording.getFrame(0);
        if (firstFrame != null) {
            currentLocation = frameToLocation(firstFrame);
            targetLocation = currentLocation.clone();
            viewer.teleport(currentLocation);
            
            // Заполняем буфер кадров
            fillFrameBuffer();
        }
        
        // Показываем информацию о загрузке
        viewer.sendTitle(
            "§eЗагрузка записи...",
            "§7Режим: §bПлавное воспроизведение",
            10, 60, 10
        );
        
        // Анализируем блоки
        new BukkitRunnable() {
            @Override
            public void run() {
                analyzeAndPrepareBlocks();
                
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        viewer.sendTitle(
                            "§aПлавное воспроизведение",
                            "§7Игрок: §f" + recording.getPlayerName(),
                            10, 70, 20
                        );
                        
                        startSmoothReplayTask();
                    }
                }.runTaskLater(plugin, 20);
            }
        }.runTaskLater(plugin, 40);
    }
    
    /**
     * Заполнить буфер кадров
     */
    private void fillFrameBuffer() {
        frameBuffer.clear();
        for (int i = 0; i < FRAME_BUFFER_SIZE && (currentFrameIndex + i) < recording.getFrameCount(); i++) {
            RecordFrame frame = recording.getFrame(currentFrameIndex + i);
            if (frame != null) {
                frameBuffer.add(frame);
            }
        }
    }
    
    /**
     * Запустить задачу плавного воспроизведения
     */
    private void startSmoothReplayTask() {
        // Основная задача обработки кадров
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isActive || currentFrameIndex >= recording.getFrameCount()) {
                    if (isActive) {
                        viewer.sendMessage("§aВоспроизведение завершено!");
                        viewer.sendTitle(
                            "§aВоспроизведение завершено",
                            "§7Всего кадров: §f" + recording.getFrameCount(),
                            10, 40, 20
                        );
                        stop();
                    }
                    cancel();
                    return;
                }
                
                // Обрабатываем текущий кадр
                RecordFrame currentFrame = recording.getFrame(currentFrameIndex);
                if (currentFrame != null) {
                    processFrame(currentFrame);
                    
                    // Устанавливаем целевую позицию для интерполяции
                    RecordFrame nextFrame = getNextFrame();
                    if (nextFrame != null) {
                        targetLocation = frameToLocation(nextFrame);
                        
                        // Если используем предсказывающее сглаживание
                        if (usePredictiveSmoothing) {
                            targetLocation = predictSmoothLocation(targetLocation);
                        }
                    }
                }
                
                currentFrameIndex++;
                updateFrameBuffer();
                showProgress();
            }
        }.runTaskTimer(plugin, 0, TICKS_PER_FRAME);
        
        // Задача интерполяции движения (работает чаще для плавности)
        replayTask = new BukkitRunnable() {
            private int tickCounter = 0;
            
            @Override
            public void run() {
                if (!isActive) {
                    cancel();
                    return;
                }
                
                // Интерполируем позицию камеры
                if (currentLocation != null && targetLocation != null) {
                    Location interpolated = interpolateLocation(currentLocation, targetLocation, smoothingFactor);
                    
                    // Применяем дополнительное сглаживание на основе истории движения
                    if (frameBuffer.size() > 2) {
                        interpolated = applySmoothingFilter(interpolated);
                    }
                    
                    viewer.teleport(interpolated);
                    currentLocation = interpolated;
                }
                
                // Показываем статус
                RecordFrame currentFrame = recording.getFrame(Math.min(currentFrameIndex, recording.getFrameCount() - 1));
                if (currentFrame != null) {
                    viewer.sendActionBar(buildSmoothStatusString(currentFrame));
                }
                
                tickCounter++;
            }
        }.runTaskTimer(plugin, 0, 1); // Каждый тик для максимальной плавности
    }
    
    /**
     * Интерполировать позицию между двумя локациями
     */
    private Location interpolateLocation(Location from, Location to, float factor) {
        if (useQuadraticInterpolation) {
            return quadraticInterpolation(from, to, factor);
        } else {
            return linearInterpolation(from, to, factor);
        }
    }
    
    /**
     * Линейная интерполяция
     */
    private Location linearInterpolation(Location from, Location to, float factor) {
        double x = from.getX() + (to.getX() - from.getX()) * factor;
        double y = from.getY() + (to.getY() - from.getY()) * factor;
        double z = from.getZ() + (to.getZ() - from.getZ()) * factor;
        
        // Интерполяция углов (с учетом перехода через 360/0)
        float yaw = interpolateAngle(from.getYaw(), to.getYaw(), factor);
        float pitch = from.getPitch() + (to.getPitch() - from.getPitch()) * factor;
        
        return new Location(from.getWorld(), x, y, z, yaw, pitch);
    }
    
    /**
     * Квадратичная интерполяция для более плавного движения
     */
    private Location quadraticInterpolation(Location from, Location to, float factor) {
        // Используем сглаженный фактор для более плавного ускорения/замедления
        float smoothFactor = factor * factor * (3.0f - 2.0f * factor);
        
        double x = from.getX() + (to.getX() - from.getX()) * smoothFactor;
        double y = from.getY() + (to.getY() - from.getY()) * smoothFactor;
        double z = from.getZ() + (to.getZ() - from.getZ()) * smoothFactor;
        
        float yaw = interpolateAngle(from.getYaw(), to.getYaw(), smoothFactor);
        float pitch = from.getPitch() + (to.getPitch() - from.getPitch()) * smoothFactor;
        
        return new Location(from.getWorld(), x, y, z, yaw, pitch);
    }
    
    /**
     * Интерполяция углов с учетом перехода через 360/0
     */
    private float interpolateAngle(float from, float to, float factor) {
        float diff = to - from;
        
        // Корректируем разницу для кратчайшего пути
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        
        return from + diff * factor;
    }
    
    /**
     * Применить фильтр сглаживания на основе буфера кадров
     */
    private Location applySmoothingFilter(Location current) {
        if (frameBuffer.size() < 3) {
            return current;
        }
        
        // Используем взвешенное среднее для сглаживания
        double weightSum = 0;
        double xSum = 0, ySum = 0, zSum = 0;
        float yawSum = 0, pitchSum = 0;
        
        int index = 0;
        for (RecordFrame frame : frameBuffer) {
            if (index > 5) break; // Используем только ближайшие кадры
            
            double weight = 1.0 / (index + 1); // Убывающий вес для дальних кадров
            Location loc = frameToLocation(frame);
            
            xSum += loc.getX() * weight;
            ySum += loc.getY() * weight;
            zSum += loc.getZ() * weight;
            yawSum += loc.getYaw() * weight;
            pitchSum += loc.getPitch() * weight;
            weightSum += weight;
            
            index++;
        }
        
        // Смешиваем сглаженную позицию с текущей
        double smoothX = (xSum / weightSum) * 0.3 + current.getX() * 0.7;
        double smoothY = (ySum / weightSum) * 0.3 + current.getY() * 0.7;
        double smoothZ = (zSum / weightSum) * 0.3 + current.getZ() * 0.7;
        float smoothYaw = (float)((yawSum / weightSum) * 0.3 + current.getYaw() * 0.7);
        float smoothPitch = (float)((pitchSum / weightSum) * 0.3 + current.getPitch() * 0.7);
        
        return new Location(current.getWorld(), smoothX, smoothY, smoothZ, smoothYaw, smoothPitch);
    }
    
    /**
     * Предсказать сглаженную позицию на основе тренда движения
     */
    private Location predictSmoothLocation(Location target) {
        if (frameBuffer.size() < 3) {
            return target;
        }
        
        // Анализируем тренд движения
        RecordFrame prev2 = frameBuffer.get(0);
        RecordFrame prev1 = frameBuffer.get(1);
        RecordFrame current = frameBuffer.get(2);
        
        if (prev2 == null || prev1 == null || current == null) {
            return target;
        }
        
        // Вычисляем вектор скорости
        Vector v1 = new Vector(
            prev1.getX() - prev2.getX(),
            prev1.getY() - prev2.getY(),
            prev1.getZ() - prev2.getZ()
        );
        
        Vector v2 = new Vector(
            current.getX() - prev1.getX(),
            current.getY() - prev1.getY(),
            current.getZ() - prev1.getZ()
        );
        
        // Среднее ускорение
        Vector acceleration = v2.subtract(v1).multiply(0.5);
        
        // Предсказываем следующую позицию
        double predictedX = target.getX() + v2.getX() + acceleration.getX();
        double predictedY = target.getY() + v2.getY() + acceleration.getY();
        double predictedZ = target.getZ() + v2.getZ() + acceleration.getZ();
        
        // Смешиваем предсказание с целевой позицией
        double mixedX = predictedX * 0.2 + target.getX() * 0.8;
        double mixedY = predictedY * 0.2 + target.getY() * 0.8;
        double mixedZ = predictedZ * 0.2 + target.getZ() * 0.8;
        
        return new Location(target.getWorld(), mixedX, mixedY, mixedZ, target.getYaw(), target.getPitch());
    }
    
    /**
     * Получить следующий кадр
     */
    private RecordFrame getNextFrame() {
        if (currentFrameIndex + 1 < recording.getFrameCount()) {
            return recording.getFrame(currentFrameIndex + 1);
        }
        return null;
    }
    
    /**
     * Обновить буфер кадров
     */
    private void updateFrameBuffer() {
        if (frameBuffer.size() > 0) {
            frameBuffer.removeFirst();
        }
        
        int nextIndex = currentFrameIndex + FRAME_BUFFER_SIZE - 1;
        if (nextIndex < recording.getFrameCount()) {
            RecordFrame frame = recording.getFrame(nextIndex);
            if (frame != null) {
                frameBuffer.add(frame);
            }
        }
    }
    
    /**
     * Преобразовать кадр в локацию
     */
    private Location frameToLocation(RecordFrame frame) {
        World world = plugin.getServer().getWorld(frame.getWorld());
        if (world == null) {
            return currentLocation;
        }
        
        return new Location(
            world,
            frame.getX(),
            frame.getY(),
            frame.getZ(),
            frame.getYaw(),
            frame.getPitch()
        );
    }
    
    /**
     * Обработать кадр (события блоков)
     */
    private void processFrame(RecordFrame frame) {
        if (frame.hasBlockEvents()) {
            for (BlockEvent event : frame.getBlockEvents()) {
                processBlockEvent(event);
            }
        }
    }
    
    /**
     * Остановить воспроизведение
     */
    public void stop() {
        if (!isActive) {
            return;
        }
        
        isActive = false;
        
        // Останавливаем задачи
        if (replayTask != null) {
            replayTask.cancel();
            replayTask = null;
        }
        
        // Очищаем анимации
        clearAllBlockBreakingAnimations();
        
        // Восстанавливаем блоки
        restoreOriginalBlocks();
        
        // Возвращаем игрока
        if (originalLocation != null) {
            viewer.teleport(originalLocation);
        }
        if (originalGameMode != null) {
            viewer.setGameMode(originalGameMode);
        }
        
        // Очищаем UI
        viewer.resetTitle();
        viewer.setPlayerListHeader("");
        viewer.setPlayerListFooter("");
        
        viewer.sendMessage("§7Плавное воспроизведение остановлено.");
    }
    
    /**
     * Построить строку состояния с индикатором плавности
     */
    private String buildSmoothStatusString(RecordFrame frame) {
        StringBuilder sb = new StringBuilder();
        
        // Индикатор плавности
        sb.append("§b⚡ SMOOTH ");
        
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
        
        // FPS индикатор (симуляция)
        int smoothFps = 60 + (int)(Math.random() * 20);
        sb.append(String.format(" §7| §aFPS: %d", smoothFps));
        
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
                progressBar.append("§b█");
            } else {
                progressBar.append("§7░");
            }
        }
        progressBar.append("§8]");
        
        // Время
        int currentSeconds = (currentFrameIndex * 2) / 20;
        int totalSeconds = recording.getDurationSeconds();
        
        // Индикатор плавности
        String smoothIndicator = "§a◆ SMOOTH MODE ◆";
        
        String progressText = String.format(
            "%s %s §f%d/%d §7(%ds/%ds) §bБуфер: %d",
            smoothIndicator,
            progressBar.toString(),
            currentFrameIndex,
            totalFrames,
            currentSeconds,
            totalSeconds,
            frameBuffer.size()
        );
        
        viewer.setPlayerListFooter(progressText);
    }
    
    // === Методы работы с блоками (копия из ReplaySession) ===
    
    private void analyzeAndPrepareBlocks() {
        Map<String, Material> finalBlockStates = new HashMap<>();
        Map<String, Material> initialBlockStates = new HashMap<>();
        Set<String> allBlockLocations = new HashSet<>();
        
        viewer.sendMessage("§eАнализ записи для плавного воспроизведения...");
        
        for (RecordFrame frame : recording.getFrames()) {
            if (frame.hasBlockEvents()) {
                for (BlockEvent event : frame.getBlockEvents()) {
                    World world = plugin.getServer().getWorld(event.getWorld());
                    if (world == null) continue;
                    
                    String blockKey = getBlockKey(event.getWorld(), event.getX(), event.getY(), event.getZ());
                    allBlockLocations.add(blockKey);
                    
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
                    }
                }
            }
        }
        
        viewer.sendMessage(String.format("§7Найдено §e%d§7 блоков для обработки", allBlockLocations.size()));
        
        int restoredCount = 0;
        for (String blockKey : allBlockLocations) {
            String[] parts = blockKey.split(":");
            if (parts.length != 4) continue;
            
            World world = plugin.getServer().getWorld(parts[0]);
            if (world == null) continue;
            
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            
            Location loc = new Location(world, x, y, z);
            
            if (!loc.getChunk().isLoaded()) {
                loc.getChunk().load();
            }
            
            Block currentBlock = loc.getBlock();
            originalBlocks.put(blockKey, currentBlock.getBlockData().clone());
            
            Material initialMaterial = initialBlockStates.get(blockKey);
            if (initialMaterial != null) {
                if (initialMaterial == Material.AIR) {
                    if (currentBlock.getType() != Material.AIR) {
                        viewer.sendBlockChange(loc, Material.AIR.createBlockData());
                        blockStates.put(blockKey, new BlockState(Material.AIR, false));
                        restoredCount++;
                    }
                } else {
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
        
        viewer.sendMessage(String.format("§7Подготовлено для плавного воспроизведения: §a%d блоков", restoredCount));
    }
    
    private void processBlockEvent(BlockEvent event) {
        World world = plugin.getServer().getWorld(event.getWorld());
        if (world == null) return;
        
        Location blockLoc = new Location(world, event.getX(), event.getY(), event.getZ());
        String blockKey = getBlockKey(event.getWorld(), event.getX(), event.getY(), event.getZ());
        
        switch (event.getType()) {
            case BREAK_START:
                sendBlockBreakAnimation(blockLoc, 1);
                blockBreakingProgress.put(blockKey, 1);
                break;
                
            case BREAK_PROGRESS:
                int stage = event.getBreakStage();
                sendBlockBreakAnimation(blockLoc, stage);
                blockBreakingProgress.put(blockKey, stage);
                break;
                
            case BREAK_COMPLETE:
                sendBlockBreakAnimation(blockLoc, 9);
                world.playEffect(blockLoc, org.bukkit.Effect.STEP_SOUND, event.getBlockType());
                blockStates.put(blockKey, new BlockState(Material.AIR, false));
                viewer.sendBlockChange(blockLoc, Material.AIR.createBlockData());
                
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        sendBlockBreakAnimation(blockLoc, -1);
                        blockBreakingProgress.remove(blockKey);
                    }
                }.runTaskLater(plugin, 3);
                break;
                
            case BREAK_CANCEL:
                sendBlockBreakAnimation(blockLoc, -1);
                blockBreakingProgress.remove(blockKey);
                break;
                
            case PLACE:
                blockStates.put(blockKey, new BlockState(event.getBlockType(), true));
                BlockData newBlockData = event.getBlockType().createBlockData();
                viewer.sendBlockChange(blockLoc, newBlockData);
                world.playSound(blockLoc, newBlockData.getSoundGroup().getPlaceSound(), 1.0f, 1.0f);
                world.playEffect(blockLoc, org.bukkit.Effect.STEP_SOUND, event.getBlockType());
                break;
        }
    }
    
    private void sendBlockBreakAnimation(Location loc, int stage) {
        try {
            if (stage >= 0 && stage <= 9) {
                float damage = stage / 9.0f;
                viewer.sendBlockDamage(loc, damage);
            } else {
                viewer.sendBlockDamage(loc, 0.0f);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка анимации: " + e.getMessage());
        }
    }
    
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
            
            viewer.sendBlockChange(loc, originalData);
        }
        
        blockStates.clear();
        originalBlocks.clear();
        blockBreakingProgress.clear();
    }
    
    private String getBlockKey(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }
    
    // Геттеры и сеттеры
    public boolean isActive() { return isActive; }
    public Player getViewer() { return viewer; }
    public PlayerRecording getRecording() { return recording; }
    
    public float getSmoothingFactor() { return smoothingFactor; }
    public void setSmoothingFactor(float factor) { 
        this.smoothingFactor = Math.max(0.05f, Math.min(1.0f, factor)); 
    }
    
    public boolean isUseQuadraticInterpolation() { return useQuadraticInterpolation; }
    public void setUseQuadraticInterpolation(boolean use) { this.useQuadraticInterpolation = use; }
    
    public boolean isUsePredictiveSmoothing() { return usePredictiveSmoothing; }
    public void setUsePredictiveSmoothing(boolean use) { this.usePredictiveSmoothing = use; }
}