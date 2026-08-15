package com.example.antixrayviewer.replay;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Неизменяемая ссылка на позицию блока.
 *
 * Раньше позиции хранились строками вида "world:x:y:z" и парсились через split(":")
 * на каждом кадре воспроизведения — это создавало мусор и заметно грузило сервер.
 * Здесь координаты хранятся числами, а hashCode считается один раз.
 */
public final class BlockRef {

    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private final int hash;

    public BlockRef(String world, int x, int y, int z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        int h = world == null ? 0 : world.hashCode();
        h = 31 * h + x;
        h = 31 * h + y;
        h = 31 * h + z;
        this.hash = h;
    }

    public String getWorld() {
        return world;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public int getChunkX() {
        return x >> 4;
    }

    public int getChunkZ() {
        return z >> 4;
    }

    public long getChunkKey() {
        return ((long) (x >> 4) & 0xFFFFFFFFL) | (((long) (z >> 4) & 0xFFFFFFFFL) << 32);
    }

    public Location toLocation(World w) {
        return new Location(w, x, y, z);
    }

    public Location toCenterLocation(World w) {
        return new Location(w, x + 0.5, y + 0.5, z + 0.5);
    }

    public double distanceSquared(double px, double py, double pz) {
        double dx = (x + 0.5) - px;
        double dy = (y + 0.5) - py;
        double dz = (z + 0.5) - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlockRef)) {
            return false;
        }
        BlockRef other = (BlockRef) o;
        return x == other.x && y == other.y && z == other.z
                && (world == null ? other.world == null : world.equals(other.world));
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return world + " " + x + " " + y + " " + z;
    }
}
