package com.example.antixrayai;

import com.example.antixrayai.commands.AntiXrayCommand;
import com.example.antixrayai.listeners.OreBreakListener;
import com.example.antixrayai.managers.RecordingManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

public class AntiXrayAI extends JavaPlugin {
    
    private RecordingManager recordingManager;
    private AntiXrayCommand commandHandler;

    @Override
    public void onEnable() {
        // Загружаем конфигурацию
        saveDefaultConfig();
        loadConfiguration();
        
        // Инициализируем менеджеры
        recordingManager = new RecordingManager(this);
        
        // Регистрируем слушатели событий
        getServer().getPluginManager().registerEvents(
            new OreBreakListener(this, recordingManager), 
            this
        );
        
        // Регистрируем команды
        commandHandler = new AntiXrayCommand(this, recordingManager);
        getCommand("antixrayai").setExecutor(commandHandler);
        getCommand("antixrayai").setTabCompleter(commandHandler);
        
        // Сообщение при включении плагина
        getLogger().info("╔════════════════════════════════════╗");
        getLogger().info("║     AntiXrayAI v" + getDescription().getVersion() + " enabled!     ║");
        getLogger().info("║     Anti X-Ray system activated!   ║");
        getLogger().info("╚════════════════════════════════════╝");
        
        // Отправка сообщения в консоль сервера
        Bukkit.getConsoleSender().sendMessage(
            ChatColor.GREEN + "[AntiXrayAI] " + 
            ChatColor.YELLOW + "Система защиты от X-Ray активирована!"
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
        
        // Останавливаем все воспроизведения
        if (commandHandler != null) {
            commandHandler.stopAllReplays();
        }
        
        // Сообщение при выключении плагина
        getLogger().info("╔════════════════════════════════════╗");
        getLogger().info("║     AntiXrayAI v" + getDescription().getVersion() + " disabled!    ║");
        getLogger().info("║      All recordings saved!         ║");
        getLogger().info("╚════════════════════════════════════╝");
        
        // Отправка сообщения в консоль сервера
        Bukkit.getConsoleSender().sendMessage(
            ChatColor.RED + "[AntiXrayAI] " + 
            ChatColor.YELLOW + "Плагин отключен. Все записи сохранены."
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
        
        config.addDefault("notifications.admin-alerts", true);
        config.addDefault("notifications.console-logging", true);
        
        config.addDefault("messages.detection", "§c[AntiXrayAI] §e⚠ Подозрение: §f{player} §7- {reason}");
        config.addDefault("messages.recording-started", "§a[AntiXrayAI] §7Начата запись игрока §f{player}");
        config.addDefault("messages.recording-completed", "§a[AntiXrayAI] §7Запись завершена. ID: §b#{id}");
        
        config.options().copyDefaults(true);
        saveConfig();
    }
    
    public RecordingManager getRecordingManager() {
        return recordingManager;
    }
}