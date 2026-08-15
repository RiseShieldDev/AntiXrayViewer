package com.example.antixrayviewer;

import com.example.antixrayviewer.commands.AntiXrayViewerCommand;
import com.example.antixrayviewer.listeners.OreBreakListener;
import com.example.antixrayviewer.managers.RecordingManager;
import com.example.antixrayviewer.replay.ReplayManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

public class AntiXrayViewer extends JavaPlugin {
    
    private RecordingManager recordingManager;
    private ReplayManager replayManager;
    private AntiXrayViewerCommand commandHandler;

    @Override
    public void onEnable() {
        // Загружаем конфигурацию
        saveDefaultConfig();
        loadConfiguration();
        
        // Инициализируем менеджеры
        recordingManager = new RecordingManager(this);
        replayManager = new ReplayManager(this);
        
        // Регистрируем слушатели событий
        getServer().getPluginManager().registerEvents(
            new OreBreakListener(this, recordingManager), 
            this
        );
        
        // Слушатель просмотров: гарантирует очистку виртуальных блоков и камеры
        getServer().getPluginManager().registerEvents(replayManager, this);
        
        // Регистрируем команды
        commandHandler = new AntiXrayViewerCommand(this, recordingManager, replayManager);
        getCommand("antixrayviewer").setExecutor(commandHandler);
        getCommand("antixrayviewer").setTabCompleter(commandHandler);
        
        // Сообщение при включении плагина
        getLogger().info("╔════════════════════════════════════╗");
        getLogger().info("║   AntiXrayViewer v" + getPluginMeta().getVersion() + " enabled!  ║");
        getLogger().info("║   Anti X-Ray viewer system active!  ║");
        getLogger().info("╚════════════════════════════════════╝");
        
        // Отправка сообщения в консоль сервера
        Bukkit.getConsoleSender().sendMessage(
            Component.text("[AntiXrayViewer] ", NamedTextColor.GREEN).append(Component.text("Система просмотра активирована!", NamedTextColor.YELLOW))
        );
        
        // Информация о конфигурации
        FileConfiguration config = getConfig();
        getLogger().info("Настройки загружены:");
        getLogger().info("- Порог алмазов: " + config.getInt("thresholds.diamond", 5));
        getLogger().info("- Порог незерита: " + config.getInt("thresholds.netherite", 3));
        getLogger().info("- Время записи: " + config.getInt("recording.duration", 180) + " секунд");
    }

    @Override
    public void onDisable() {
        // Останавливаем все активные записи
        if (recordingManager != null) {
            recordingManager.stopAllRecordings();
        }
        
        // Останавливаем все воспроизведения и возвращаем зрителям реальный мир
        if (replayManager != null) {
            replayManager.stopAll();
        }
        
        getLogger().info("╔════════════════════════════════════╗");
        getLogger().info("║  AntiXrayViewer v" + getPluginMeta().getVersion() + " disabled!   ║");
        getLogger().info("║      All recordings saved!         ║");
        getLogger().info("╚════════════════════════════════════╝");
        
        Bukkit.getConsoleSender().sendMessage(
            Component.text("[AntiXrayViewer] ", NamedTextColor.RED).append(Component.text("Плагин отключен. Все записи сохранены.", NamedTextColor.YELLOW))
        );
    }
    
    private void loadConfiguration() {
        FileConfiguration config = getConfig();
        
        // Устанавливаем значения по умолчанию, если их нет
        config.addDefault("thresholds.diamond", 5);
        config.addDefault("thresholds.netherite", 3);
        config.addDefault("thresholds.reset-time", 60);
        
        config.addDefault("recording.enabled", true);
        config.addDefault("recording.duration", 180);
        config.addDefault("recording.interval-ticks", 2);
        config.addDefault("recording.max-saved", 50);
        
        // Настройки воспроизведения и камеры
        config.addDefault("replay.camera.default-mode", "FIRST_PERSON");
        config.addDefault("replay.camera.smoothing", 0.35);
        config.addDefault("replay.camera.third-person-distance", 4.0);
        config.addDefault("replay.camera.show-avatar", true);
        
        config.addDefault("replay.performance.block-updates-per-tick", 256);
        config.addDefault("replay.performance.block-render-distance", 96.0);
        config.addDefault("replay.performance.break-animation-distance", 48.0);
        
        config.addDefault("replay.playback.default-speed", 1.0);
        config.addDefault("replay.playback.max-speed", 8.0);
        config.addDefault("replay.playback.particles", true);
        config.addDefault("replay.playback.sounds", true);
        
        config.addDefault("notifications.admin-alerts", true);
        config.addDefault("notifications.console-logging", true);
        
        config.addDefault("messages.detection", "§c[AntiXrayViewer] §e⚠ Подозрение: §f{player} §7- {reason}");
        config.addDefault("messages.recording-started", "§a[AntiXrayViewer] §7Начата запись игрока §f{player}");
        config.addDefault("messages.recording-completed", "§a[AntiXrayViewer] §7Запись завершена. ID: §b#{id}");
        
        config.options().copyDefaults(true);
        saveConfig();
    }
    
    public RecordingManager getRecordingManager() {
        return recordingManager;
    }
    
    public ReplayManager getReplayManager() {
        return replayManager;
    }
}