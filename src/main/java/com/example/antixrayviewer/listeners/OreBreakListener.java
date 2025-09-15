package com.example.antixrayviewer.listeners;

import com.example.antixrayviewer.AntiXrayViewer;
import com.example.antixrayviewer.managers.RecordingManager;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class OreBreakListener implements Listener {
    
    private final AntiXrayViewer plugin;
    private final RecordingManager recordingManager;
    private final Map<UUID, OreBreakData> playerOreBreaks = new HashMap<>();
    
    // Настройки порогов из конфигурации
    private final int diamondThreshold;
    private final int netheriteThreshold;
    private final long resetTime;
    
    public OreBreakListener(AntiXrayViewer plugin, RecordingManager recordingManager) {
        this.plugin = plugin;
        this.recordingManager = recordingManager;
        
        // Загружаем настройки из конфигурации
        this.diamondThreshold = plugin.getConfig().getInt("thresholds.diamond", 5);
        this.netheriteThreshold = plugin.getConfig().getInt("thresholds.netherite", 3);
        this.resetTime = plugin.getConfig().getInt("thresholds.reset-time", 60) * 1000L; // Переводим в миллисекунды
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Material type = block.getType();
        
        // Проверяем только алмазную руду и руду древних обломков (незерит)
        if (type != Material.DIAMOND_ORE && type != Material.DEEPSLATE_DIAMOND_ORE &&
            type != Material.ANCIENT_DEBRIS) {
            return;
        }
        
        UUID playerId = player.getUniqueId();
        OreBreakData data = playerOreBreaks.computeIfAbsent(playerId, k -> new OreBreakData());
        
        long currentTime = System.currentTimeMillis();
        
        // Сбрасываем счетчик, если прошло много времени
        if (currentTime - data.lastBreakTime > resetTime) {
            data.reset();
        }
        
        data.lastBreakTime = currentTime;
        
        // Увеличиваем соответствующий счетчик
        if (type == Material.DIAMOND_ORE || type == Material.DEEPSLATE_DIAMOND_ORE) {
            data.diamondCount++;
        } else if (type == Material.ANCIENT_DEBRIS) {
            data.netheriteCount++;
        }
        
        // Проверяем, не превышен ли порог
        boolean suspicious = false;
        String reason = "";
        
        if (data.diamondCount >= diamondThreshold) {
            suspicious = true;
            reason = String.format("Сломано %d алмазной руды подряд", data.diamondCount);
        } else if (data.netheriteCount >= netheriteThreshold) {
            suspicious = true;
            reason = String.format("Сломано %d древних обломков подряд", data.netheriteCount);
        }
        
        // Если обнаружена подозрительная активность
        if (suspicious && !recordingManager.isRecording(player)) {
            plugin.getLogger().warning(String.format(
                "⚠ ПОДОЗРЕНИЕ НА X-RAY: Игрок %s - %s",
                player.getName(), reason
            ));
            
            // Уведомляем администраторов
            notifyAdmins(player, reason);
            
            // Начинаем запись
            recordingManager.startRecording(player, reason);
            
            // Сбрасываем счетчики после начала записи
            data.reset();
        }
    }
    
    private void notifyAdmins(Player suspect, String reason) {
        String message = String.format(
            "§c[AntiXrayViewer] §e⚠ Подозрение: §f%s §7- %s §a(запись начата)",
            suspect.getName(), reason
        );
        
        // Отправляем сообщение всем онлайн администраторам
        plugin.getServer().getOnlinePlayers().stream()
            .filter(p -> p.hasPermission("antixrayviewer.admin"))
            .forEach(admin -> admin.sendMessage(message));
        
        // Также логируем в консоль
        plugin.getServer().getConsoleSender().sendMessage(message);
    }
    
    // Вспомогательный класс для хранения данных о ломании руды
    private static class OreBreakData {
        int diamondCount = 0;
        int netheriteCount = 0;
        long lastBreakTime = 0;
        
        void reset() {
            diamondCount = 0;
            netheriteCount = 0;
        }
    }
}