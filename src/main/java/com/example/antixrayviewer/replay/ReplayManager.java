package com.example.antixrayviewer.replay;

import com.example.antixrayviewer.AntiXrayViewer;
import com.example.antixrayviewer.data.PlayerRecording;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Реестр активных сессий просмотра и гарантийная очистка.
 *
 * Раньше сессии хранились в классе команды и никогда не чистились при выходе игрока:
 * виртуальные блоки и режим наблюдателя могли "зависнуть" навсегда.
 */
public final class ReplayManager implements Listener {

    private final AntiXrayViewer plugin;
    private final Map<UUID, ReplaySession> sessions = new HashMap<>();

    public ReplayManager(AntiXrayViewer plugin) {
        this.plugin = plugin;
    }

    public ReplaySession start(Player viewer, PlayerRecording recording) {
        stop(viewer);
        ReplaySession session = new ReplaySession(plugin, viewer, recording);
        sessions.put(viewer.getUniqueId(), session);
        session.start();
        return session;
    }

    public ReplaySession get(Player viewer) {
        ReplaySession session = sessions.get(viewer.getUniqueId());
        if (session != null && session.isStopped()) {
            sessions.remove(viewer.getUniqueId());
            return null;
        }
        return session;
    }

    public boolean isViewing(Player viewer) {
        return get(viewer) != null;
    }

    public boolean stop(Player viewer) {
        ReplaySession session = sessions.remove(viewer.getUniqueId());
        if (session == null) {
            return false;
        }
        session.stop();
        return true;
    }

    private void stopSilently(UUID viewerId) {
        ReplaySession session = sessions.remove(viewerId);
        if (session != null) {
            session.stop(false);
        }
    }

    public void stopAll() {
        List<ReplaySession> copy = new ArrayList<>(sessions.values());
        sessions.clear();
        for (ReplaySession session : copy) {
            session.stop(false);
        }
    }

    public Collection<ReplaySession> getSessions() {
        return new ArrayList<>(sessions.values());
    }

    public int getActiveCount() {
        return sessions.size();
    }

    // ===================== Гарантии очистки =====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        stopSilently(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        ReplaySession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.isInternalGameModeChange()) {
            return;
        }
        // Игрок сам вышел из режима наблюдателя — аккуратно завершаем просмотр
        sessions.remove(event.getPlayer().getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () -> session.stop(true));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        ReplaySession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }
        // Смена мира вручную — виртуальные блоки больше не имеют смысла
        if (!event.getPlayer().getWorld().getName().equals(session.getTimelineWorld())) {
            sessions.remove(event.getPlayer().getUniqueId());
            session.stop(true);
        }
    }

    /**
     * Клиент получил чанк — переналагаем виртуальные блоки только в этом чанке.
     * Пришло на замену полному перебору всех блоков каждые 2 тика.
     */
    @EventHandler
    public void onChunkSent(PlayerChunkLoadEvent event) {
        ReplaySession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }
        session.onChunkSent(event.getChunk().getX(), event.getChunk().getZ());
    }
}
