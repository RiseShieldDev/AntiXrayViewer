package com.example.antixrayai.listeners;

import com.example.antixrayai.AntiXrayAI;
import com.example.antixrayai.managers.RecordingManager;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class OreBreakListener implements Listener {

    private static final String PLACED_ORE_META = "placed_ore";

    private final AntiXrayAI plugin;
    private final RecordingManager recordingManager;
    private final Map<UUID, OreBreakData> playerOreBreaks = new HashMap<>();

    // Настройки порогов из конфигурации
    private final int diamondThreshold;
    private final int netheriteThreshold;
    private final long resetTime;

    public OreBreakListener(AntiXrayAI plugin, RecordingManager recordingManager) {
        this.plugin = plugin;
        this.recordingManager = recordingManager;

        this.diamondThreshold = plugin.getConfig().getInt("thresholds.diamond", 5);
        this.netheriteThreshold = plugin.getConfig().getInt("thresholds.netherite", 3);
        this.resetTime = plugin.getConfig().getInt("thresholds.reset-time", 60) * 1000L;
    }

    // ───────────────────────────────────────────────
    //  Утилита: является ли блок отслеживаемой рудой
    // ───────────────────────────────────────────────
    private boolean isTrackedOre(Material type) {
        return type == Material.DIAMOND_ORE
            || type == Material.DEEPSLATE_DIAMOND_ORE
            || type == Material.ANCIENT_DEBRIS;
    }

    // ───────────────────────────────────────────────
    //  Маркировка руды, поставленной игроком
    // ───────────────────────────────────────────────
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();

        if (isTrackedOre(block.getType())) {
            // Маркер живёт только в памяти; не попадает на ItemStack →
            // предметы стакаются нормально; сбрасывается при рестарте
            block.setMetadata(PLACED_ORE_META,
                    new FixedMetadataValue(plugin, true));
        }
    }

    // ───────────────────────────────────────────────
    //  Основная проверка при разрушении руды
    // ───────────────────────────────────────────────
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Material type = block.getType();

        // 1. Проверяем, что это отслеживаемая руда
        if (!isTrackedOre(type)) {
            return;
        }

        // 2. Игнорируем игроков в креативе
        if (player.getGameMode() == GameMode.CREATIVE) {
            // Чистим маркер, если он был (руду мог поставить кто-то другой)
            if (block.hasMetadata(PLACED_ORE_META)) {
                block.removeMetadata(PLACED_ORE_META, plugin);
            }
            return;
        }

        // 3. Игнорируем руду, поставленную игроком (не натуральную)
        if (block.hasMetadata(PLACED_ORE_META)) {
            block.removeMetadata(PLACED_ORE_META, plugin);
            return;
        }

        // ── Далее — стандартная логика подсчёта ──

        UUID playerId = player.getUniqueId();
        OreBreakData data = playerOreBreaks.computeIfAbsent(
                playerId, k -> new OreBreakData());

        long currentTime = System.currentTimeMillis();

        // Сбрасываем счётчик, если прошло много времени
        if (currentTime - data.lastBreakTime > resetTime) {
            data.reset();
        }

        data.lastBreakTime = currentTime;

        // Увеличиваем соответствующий счётчик
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
            reason = String.format(
                    "Сломано %d алмазной руды подряд", data.diamondCount);
        } else if (data.netheriteCount >= netheriteThreshold) {
            suspicious = true;
            reason = String.format(
                    "Сломано %d древних обломков подряд", data.netheriteCount);
        }

        // Если обнаружена подозрительная активность
        if (suspicious && !recordingManager.isRecording(player)) {
            plugin.getLogger().warning(String.format(
                    "⚠ ПОДОЗРЕНИЕ НА X-RAY: Игрок %s - %s",
                    player.getName(), reason));

            notifyAdmins(player, reason);
            recordingManager.startRecording(player, reason);

            // Сбрасываем счётчики после начала записи
            data.reset();
        }
    }

    // ───────────────────────────────────────────────
    //  Уведомление администраторов
    // ───────────────────────────────────────────────
    private void notifyAdmins(Player suspect, String reason) {
        String message = String.format(
                "§c[AntiXrayAI] §e⚠ Подозрение: §f%s §7- %s §a(запись начата)",
                suspect.getName(), reason);

        plugin.getServer().getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("antixrayai.admin"))
                .forEach(admin -> admin.sendMessage(message));

        plugin.getServer().getConsoleSender().sendMessage(message);
    }

    // ───────────────────────────────────────────────
    //  Данные о ломании руды конкретным игроком
    // ───────────────────────────────────────────────
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