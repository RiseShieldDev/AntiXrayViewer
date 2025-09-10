package com.example.antixrayai.replay;

import com.example.antixrayai.AntiXrayAI;
import com.example.antixrayai.data.PlayerRecording;
import com.example.antixrayai.data.RecordFrame;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

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
        
        // Показываем информацию
        viewer.sendTitle(
            "§aВоспроизведение записи",
            "§7Игрок: §f" + recording.getPlayerName(),
            10, 70, 20
        );
        
        // Запускаем воспроизведение
        startReplayTask();
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
        
        // Показываем состояние игрока
        String status = buildStatusString(frame);
        viewer.sendActionBar(status);
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
        
        String progressText = String.format(
            "%s §f%d/%d §7(%ds/%ds)",
            progressBar.toString(),
            currentFrameIndex,
            totalFrames,
            currentSeconds,
            totalSeconds
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