package com.example.antixrayviewer.replay;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.EulerAngle;

import java.util.UUID;

/**
 * Визуальная модель записанного игрока.
 *
 * Критично: сущность создаётся с setVisibleByDefault(false) и показывается ТОЛЬКО зрителю,
 * поэтому остальные игроки никогда не видят ни модель, ни её перемещения.
 */
public final class ReplayAvatar {

    private final Plugin plugin;
    private final Player viewer;
    private final UUID recordedPlayerId;
    private final String recordedPlayerName;

    private ArmorStand entity;
    private boolean shown;

    public ReplayAvatar(Plugin plugin, Player viewer, UUID recordedPlayerId, String recordedPlayerName) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.recordedPlayerId = recordedPlayerId;
        this.recordedPlayerName = recordedPlayerName;
    }

    public ArmorStand getEntity() {
        return entity;
    }

    public boolean isAlive() {
        return entity != null && entity.isValid();
    }

    public void spawn(Location location) {
        if (isAlive()) {
            return;
        }
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        entity = world.spawn(location, ArmorStand.class, stand -> {
            stand.setVisibleByDefault(false);
            stand.setPersistent(false);
            stand.setInvulnerable(true);
            stand.setGravity(false);
            stand.setSilent(true);
            stand.setBasePlate(false);
            stand.setArms(true);
            stand.setCollidable(false);
            stand.setCanPickupItems(false);
            stand.setRemoveWhenFarAway(false);
            stand.setCustomNameVisible(true);
            stand.customName(net.kyori.adventure.text.Component.text(recordedPlayerName));
            applyHead(stand);
        });
    }

    private void applyHead(ArmorStand stand) {
        try {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                OfflinePlayer owner = Bukkit.getOfflinePlayer(recordedPlayerId);
                meta.setOwningPlayer(owner);
                head.setItemMeta(meta);
            }
            if (stand.getEquipment() != null) {
                stand.getEquipment().setHelmet(head);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Не удалось применить голову игрока к модели: " + e.getMessage());
        }
    }

    public void show() {
        if (!isAlive() || shown || !viewer.isOnline()) {
            return;
        }
        viewer.showEntity(plugin, entity);
        shown = true;
    }

    public void hide() {
        if (!isAlive() || !shown || !viewer.isOnline()) {
            return;
        }
        viewer.hideEntity(plugin, entity);
        shown = false;
    }

    /**
     * Переместить модель. Клиент сам интерполирует движение сущности,
     * поэтому картинка плавная даже при обновлении раз в тик.
     */
    public void move(Location location, float bodyYaw, float headPitch) {
        if (!isAlive()) {
            spawn(location);
            return;
        }
        if (entity.getWorld() != location.getWorld()) {
            entity.teleport(location);
            return;
        }
        entity.teleport(location);
        entity.setRotation(bodyYaw, 0f);
        entity.setHeadPose(new EulerAngle(Math.toRadians(headPitch), 0.0, 0.0));
    }

    public void remove() {
        if (entity != null) {
            hide();
            entity.remove();
            entity = null;
        }
        shown = false;
    }
}
