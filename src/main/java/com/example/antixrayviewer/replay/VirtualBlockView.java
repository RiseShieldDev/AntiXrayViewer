package com.example.antixrayviewer.replay;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Персональный слой виртуальных блоков для одного зрителя.
 *
 * Важно: все изменения отправляются ТОЛЬКО этому игроку (sendBlockChange), реальный
 * мир не меняется и остальные игроки ничего не видят.
 *
 * Оптимизации по сравнению с прежней реализацией:
 * <ul>
 *   <li>раньше каждые 2 тика переотправлялись ВСЕ блоки в радиусе 96 — теперь только изменённые;</li>
 *   <li>повторная синхронизация происходит точково при загрузке чанка клиентом;</li>
 *   <li>есть бюджет пакетов на тик, чтобы большая перемотка не вызывала лаг-спайк;</li>
 *   <li>никаких синхронных chunk.load() — только асинхронная подгрузка.</li>
 * </ul>
 */
public final class VirtualBlockView {

    private final Plugin plugin;
    private final Player viewer;
    private final int updatesPerTick;
    private final double renderDistanceSq;

    /** Желаемое состояние блока в воспроизведении. */
    private final Map<BlockRef, BlockData> desired = new HashMap<>();
    /** Что реально было отправлено клиенту. */
    private final Map<BlockRef, BlockData> sent = new HashMap<>();
    /** Индекс по чанкам для точечной ресинхронизации. */
    private final Map<Long, Set<BlockRef>> byChunk = new HashMap<>();

    private final ArrayDeque<BlockRef> queue = new ArrayDeque<>();
    private final Set<BlockRef> queued = new HashSet<>();
    private final Set<Long> chunkRequests = new HashSet<>();

    private long chunkRequestCooldown = 0L;

    public VirtualBlockView(Plugin plugin, Player viewer, int updatesPerTick, double renderDistance) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.updatesPerTick = Math.max(16, updatesPerTick);
        this.renderDistanceSq = renderDistance * renderDistance;
    }

    /**
     * Задать желаемое состояние блока. Фактическая отправка произойдёт в flush().
     */
    public void set(BlockRef ref, BlockData data) {
        if (data == null) {
            return;
        }
        BlockData previous = desired.put(ref, data);
        if (previous == null) {
            byChunk.computeIfAbsent(ref.getChunkKey(), k -> new HashSet<>()).add(ref);
        }
        enqueue(ref);
    }

    /**
     * Применить целиком новое состояние (используется при большом seek).
     */
    public void setAll(Map<BlockRef, BlockData> state) {
        for (Map.Entry<BlockRef, BlockData> entry : state.entrySet()) {
            set(entry.getKey(), entry.getValue());
        }
    }

    private void enqueue(BlockRef ref) {
        if (queued.add(ref)) {
            queue.add(ref);
        }
    }

    /**
     * Отправить накопившиеся изменения с учётом бюджета.
     */
    public void flush() {
        if (queue.isEmpty() || !viewer.isOnline()) {
            return;
        }

        World viewerWorld = viewer.getWorld();
        String worldName = viewerWorld.getName();
        double px = viewer.getLocation().getX();
        double py = viewer.getLocation().getY();
        double pz = viewer.getLocation().getZ();

        int budget = updatesPerTick;
        int deferred = 0;
        int size = queue.size();

        while (budget > 0 && deferred < size && !queue.isEmpty()) {
            BlockRef ref = queue.poll();
            queued.remove(ref);

            if (!worldName.equals(ref.getWorld())) {
                // Блок в другом мире: отправлять нечего, клиент его не видит
                sent.remove(ref);
                continue;
            }

            if (ref.distanceSquared(px, py, pz) > renderDistanceSq) {
                // Слишком далеко — клиент всё равно выгрузит чанк.
                // Сбрасываем отметку об отправленном: блок вернётся в очередь через
                // onChunkSent() или revalidate(), когда зритель окажется рядом.
                sent.remove(ref);
                continue;
            }

            if (!viewerWorld.isChunkLoaded(ref.getChunkX(), ref.getChunkZ())) {
                requestChunk(viewerWorld, ref);
                enqueue(ref);
                deferred++;
                continue;
            }

            BlockData target = desired.get(ref);
            if (target == null) {
                continue;
            }

            BlockData alreadySent = sent.get(ref);
            if (alreadySent != null && alreadySent.matches(target)) {
                continue;
            }

            viewer.sendBlockChange(ref.toLocation(viewerWorld), target);
            sent.put(ref, target);
            budget--;
        }
    }

    private void requestChunk(World world, BlockRef ref) {
        long key = ref.getChunkKey();
        long now = System.currentTimeMillis();
        if (now < chunkRequestCooldown) {
            return;
        }
        if (!chunkRequests.add(key)) {
            return;
        }
        chunkRequestCooldown = now + 50L;
        // Асинхронная загрузка: не блокирует основной поток и не роняет TPS
        world.getChunkAtAsync(ref.getChunkX(), ref.getChunkZ(), false)
                .thenRun(() -> chunkRequests.remove(key));
    }

    /**
     * Клиент только что получил реальные данные чанка — надо заново наложить виртуальные блоки.
     * Именно здесь раньше терялись фейковые блоки и запись "рассыпалась".
     */
    public void onChunkSent(int chunkX, int chunkZ) {
        long key = ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
        Set<BlockRef> refs = byChunk.get(key);
        if (refs == null || refs.isEmpty()) {
            return;
        }
        for (BlockRef ref : refs) {
            sent.remove(ref);
            enqueue(ref);
        }
    }

    /**
     * Периодическая проверка: вернуть в очередь всё, что должно быть видно рядом,
     * но фактически клиенту не отправлено: блок был далеко в момент flush(),
     * зритель к нему переместился, либо чанк пришёл позже пакета изменения.
     */
    public int revalidate() {
        if (!viewer.isOnline() || desired.isEmpty()) {
            return 0;
        }
        String worldName = viewer.getWorld().getName();
        double px = viewer.getLocation().getX();
        double py = viewer.getLocation().getY();
        double pz = viewer.getLocation().getZ();

        int restored = 0;
        for (Map.Entry<BlockRef, BlockData> entry : desired.entrySet()) {
            BlockRef ref = entry.getKey();
            if (!worldName.equals(ref.getWorld())) {
                continue;
            }
            if (ref.distanceSquared(px, py, pz) > renderDistanceSq) {
                continue;
            }
            BlockData alreadySent = sent.get(ref);
            if (alreadySent != null && alreadySent.matches(entry.getValue())) {
                continue;
            }
            enqueue(ref);
            restored++;
        }
        return restored;
    }

    /**
     * Полная переотправка: считаем, что клиент не видит ничего из виртуального слоя.
     * Нужна после телепорта и пока клиент догружает мир: пакеты изменения блоков,
     * отправленные раньше самого чанка, затираются реальными данными мира.
     */
    public int resync() {
        sent.clear();
        queue.clear();
        queued.clear();
        for (BlockRef ref : desired.keySet()) {
            enqueue(ref);
        }
        return queue.size();
    }

    /**
     * Вернуть зрителю реальное состояние мира.
     * Состояние берётся из мира СЕЙЧАС, а не из снимка на момент старта —
     * иначе изменения, сделанные в мире во время просмотра, оставались бы призраками.
     */
    public void restore() {
        if (viewer.isOnline()) {
            World world = viewer.getWorld();
            String worldName = world.getName();
            Iterator<BlockRef> iterator = sent.keySet().iterator();
            while (iterator.hasNext()) {
                BlockRef ref = iterator.next();
                if (!worldName.equals(ref.getWorld())) {
                    continue;
                }
                if (!world.isChunkLoaded(ref.getChunkX(), ref.getChunkZ())) {
                    // Незагруженный чанк клиент получит заново с реальными данными
                    continue;
                }
                Block block = world.getBlockAt(ref.getX(), ref.getY(), ref.getZ());
                viewer.sendBlockChange(ref.toLocation(world), block.getBlockData());
            }
        }

        desired.clear();
        sent.clear();
        byChunk.clear();
        queue.clear();
        queued.clear();
        chunkRequests.clear();
    }

    public int getTrackedBlocks() {
        return desired.size();
    }

    public int getPendingUpdates() {
        return queue.size();
    }

    public boolean isAir(BlockRef ref) {
        BlockData data = desired.get(ref);
        return data == null || data.getMaterial() == Material.AIR;
    }
}
