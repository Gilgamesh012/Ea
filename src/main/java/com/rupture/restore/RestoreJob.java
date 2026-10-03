package com.rupture.restore;

import com.rupture.RuptureMod;
import com.rupture.RuptureShape;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import com.rupture.registry.ModTags;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Разрыв мира «Энума Элиш» — всё, чего коснулся вихрь ({@link RuptureShape}). Живёт в три фазы:
 * <ol>
 *   <li>CARVING — кратер выгрызается послойно сверху вниз (бюджет блоков за тик), снимок каждого блока сохраняется;</li>
 *   <li>WAITING — пауза перед сборкой;</li>
 *   <li>RESTORING — мир собирается обратно снизу вверх порциями.</li>
 * </ol>
 * Чанки вокруг точки удара принудительно держатся загруженными до конца сборки; в остальных
 * незагруженных чанках вдоль луча блоки не трогаются.
 */
public final class RestoreJob {
    static final int SILENT_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    enum Phase { CARVING, WAITING, RESTORING }

    // --- форма
    private final RuptureShape shape;
    private final BlockPos center;
    private final int minY, maxY;
    private final int maxBlocks;
    private final long delayTicks;

    // --- состояние
    private Phase phase;
    private long restoreAt;
    /** Курсор вырезания: текущий слой y и индекс внутри слоя. */
    private int carveY, carveIndex;
    private int restoreCursor;

    // --- снимок (компактно: позиция + индекс в палитре)
    private final LongArrayList positions = new LongArrayList();
    private final IntArrayList states = new IntArrayList();
    private final List<BlockState> palette = new ArrayList<>();
    private final Object2IntOpenHashMap<BlockState> paletteIndex = new Object2IntOpenHashMap<>();
    private final Int2ObjectOpenHashMap<CompoundTag> blockEntities = new Int2ObjectOpenHashMap<>();
    private final List<CompoundTag> entities = new ArrayList<>();
    private final Set<UUID> capturedEntities = new HashSet<>();
    /** Чанки, которые принудительно загрузили мы (чужие forceload не трогаем). */
    private final LongArrayList forcedChunks = new LongArrayList();
    /** Чанки зоны удара: их ждём, пока загрузятся; прочие незагруженные пропускаем. */
    private final LongOpenHashSet impactChunks = new LongOpenHashSet();

    private RestoreJob(RuptureShape shape, int minY, int maxY, int maxBlocks, long delayTicks) {
        this.shape = shape;
        this.center = BlockPos.containing(shape.end());
        this.minY = minY;
        this.maxY = maxY;
        this.maxBlocks = maxBlocks;
        this.delayTicks = delayTicks;
        this.paletteIndex.defaultReturnValue(-1);
    }

    /** Новый разрыв: держит чанки зоны удара и начинает выгрызаться со следующего тика. */
    public static RestoreJob start(ServerLevel level, RuptureShape shape, int maxBlocks, long delayTicks) {
        AABB b = shape.bounds();
        int minY = Math.max(level.getMinBuildHeight(), (int) Math.floor(b.minY));
        int maxY = Math.min(level.getMaxBuildHeight() - 1, (int) Math.ceil(b.maxY));
        RestoreJob job = new RestoreJob(shape, minY, maxY, maxBlocks, delayTicks);
        job.phase = Phase.CARVING;
        job.carveY = maxY;
        job.carveIndex = 0;
        job.forceChunks(level);
        job.captureEntities(level);
        return job;
    }

    public BlockPos center() {
        return center;
    }

    public boolean isDone() {
        return phase == Phase.RESTORING && restoreCursor >= positions.size() && entities.isEmpty();
    }

    // ================================================================== чанки

    private void forceChunks(ServerLevel level) {
        int r = Mth.ceil(shape.maxRadius()) + 2;
        int minCx = (center.getX() - r) >> 4, maxCx = (center.getX() + r) >> 4;
        int minCz = (center.getZ() - r) >> 4, maxCz = (center.getZ() + r) >> 4;
        var alreadyForced = level.getForcedChunks();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                long key = ChunkPos.asLong(cx, cz);
                impactChunks.add(key);
                if (!alreadyForced.contains(key)) {
                    level.setChunkForced(cx, cz, true);
                    forcedChunks.add(key);
                }
            }
        }
    }

    void releaseChunks(ServerLevel level) {
        for (int i = 0; i < forcedChunks.size(); i++) {
            long key = forcedChunks.getLong(i);
            level.setChunkForced(ChunkPos.getX(key), ChunkPos.getZ(key), false);
        }
        forcedChunks.clear();
    }

    // ================================================================== вырезание

    private boolean inside(int x, int y, int z) {
        return shape.contains(x + 0.5, y + 0.5, z + 0.5, RuptureShape.SAFE_DISTANCE);
    }

    /**
     * Выгрызает часть разрыва сверху вниз. Чанки зоны удара ждёт (мы их держим), остальные незагруженные пропускает.
     */
    int carveSome(ServerLevel level, int carveBudget, int visitBudget) {
        int carved = 0, visited = 0;
        while (carveY >= minY && carved < carveBudget && visited < visitBudget) {
            int[] lb = shape.layerBounds(carveY);
            if (lb == null) { carveY--; carveIndex = 0; continue; }
            int w = lb[1] - lb[0] + 1, d = lb[3] - lb[2] + 1;
            long total = (long) w * d;

            while (carveIndex < total && carved < carveBudget && visited < visitBudget) {
                int x = lb[0] + (int) (carveIndex % w);
                int z = lb[2] + (int) (carveIndex / w);
                visited++;
                if (!inside(x, carveY, z)) { carveIndex++; continue; }
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) {
                    if (impactChunks.contains(ChunkPos.asLong(x >> 4, z >> 4))) return carved; // ждём загрузки
                    carveIndex++;
                    continue;
                }
                // Пустые секции (воздух) пропускаем без обращения к блокам
                int si = chunk.getSectionIndex(carveY);
                if (si < 0 || si >= chunk.getSectionsCount() || chunk.getSection(si).hasOnlyAir()) { carveIndex++; continue; }
                carveIndex++;
                if (carveBlock(level, new BlockPos(x, carveY, z))) carved++;
                if (positions.size() >= maxBlocks) { finishCarving(level); return carved; }
            }
            if (carveIndex >= total) { carveY--; carveIndex = 0; }
        }
        if (carveY < minY) finishCarving(level);
        return carved;
    }

    private boolean carveBlock(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        if (state.getDestroySpeed(level, pos) < 0) return false; // бедрок, барьер, порталы
        if (state.is(ModTags.RUPTURE_IMMUNE)) return false;

        int idx = paletteIndex.getInt(state);
        if (idx < 0) {
            idx = palette.size();
            palette.add(state);
            paletteIndex.put(state, idx);
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            blockEntities.put(positions.size(), be.saveWithFullMetadata(level.registryAccess()));
            Clearable.tryClear(be); // иначе onRemove вывалит содержимое на землю
        }
        positions.add(pos.asLong());
        states.add(idx);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), SILENT_FLAGS);
        return true;
    }

    private void finishCarving(ServerLevel level) {
        captureEntities(level); // добираем сущности из чанков, загрузившихся позже
        reverseSnapshot();
        phase = Phase.WAITING;
        restoreAt = level.getGameTime() + delayTicks;
        restoreCursor = 0;
    }

    /** Снимок шёл сверху вниз — разворачиваем, чтобы собирать снизу вверх (сначала опоры). */
    private void reverseSnapshot() {
        int n = positions.size();
        for (int i = 0, j = n - 1; i < j; i++, j--) {
            long p = positions.getLong(i); positions.set(i, positions.getLong(j)); positions.set(j, p);
            int s = states.getInt(i); states.set(i, states.getInt(j)); states.set(j, s);
        }
        if (!blockEntities.isEmpty()) {
            Int2ObjectOpenHashMap<CompoundTag> remapped = new Int2ObjectOpenHashMap<>(blockEntities.size());
            blockEntities.int2ObjectEntrySet().forEach(e -> remapped.put(n - 1 - e.getIntKey(), e.getValue()));
            blockEntities.clear();
            blockEntities.putAll(remapped);
        }
    }

    /** Рамки, картины, стойки для брони — иначе отвалятся и выронят предметы. */
    private void captureEntities(ServerLevel level) {
        for (Entity e : level.getEntities((Entity) null, shape.bounds(), e -> e instanceof HangingEntity || e instanceof ArmorStand)) {
            BlockPos p = e.blockPosition();
            if (!inside(p.getX(), p.getY(), p.getZ())) continue;
            if (!capturedEntities.add(e.getUUID())) continue;
            CompoundTag tag = new CompoundTag();
            if (e.save(tag)) {
                entities.add(tag);
                e.discard();
            }
        }
    }

    // ================================================================== «иллюзия»: ничего не проваливается

    private static final String HELD_TAG = "rupture_held";

    /** Предметы и опыт над/в разрыве зависают на месте, пока мир не соберётся. */
    void holdLooseEntities(ServerLevel level) {
        for (Entity e : level.getEntities((Entity) null, shape.bounds(), e -> e instanceof ItemEntity || e instanceof ExperienceOrb)) {
            BlockPos p = e.blockPosition();
            if (!inside(p.getX(), p.getY(), p.getZ()) && !inside(p.getX(), p.getY() - 1, p.getZ())) continue;
            if (!e.isNoGravity()) {
                e.setNoGravity(true);
                e.getPersistentData().putBoolean(HELD_TAG, true);
            }
            e.setDeltaMovement(Vec3.ZERO);
        }
    }

    /** Мир собран: отпускаем зависшее; всё, что оказалось внутри блоков (предметы, мобы, игроки), — на поверхность. */
    void releaseLooseEntities(ServerLevel level) {
        for (Entity e : level.getEntities((Entity) null, shape.bounds().inflate(2), e -> true)) {
            if (e.getPersistentData().getBoolean(HELD_TAG)) {
                e.setNoGravity(false);
                e.getPersistentData().remove(HELD_TAG);
            }
            if (!level.noCollision(e, e.getBoundingBox().deflate(0.05))) {
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, e.getBlockX(), e.getBlockZ());
                if (top > e.getY()) e.teleportTo(e.getX(), top + 0.05, e.getZ());
            }
        }
    }

    // ================================================================== восстановление

    /** @return сколько блоков восстановлено. */
    int tick(ServerLevel level, int carveBudget, int visitBudget, int restoreBudget) {
        switch (phase) {
            case CARVING:
                return carveSome(level, carveBudget, visitBudget);
            case WAITING:
                if (level.getGameTime() >= restoreAt) phase = Phase.RESTORING;
                return 0;
            default:
                return restoreSome(level, restoreBudget);
        }
    }

    private int restoreSome(ServerLevel level, int budget) {
        int spent = 0;
        while (spent < budget && restoreCursor < positions.size()) {
            restoreEntry(level, restoreCursor);
            restoreCursor++;
            spent++;
        }
        if (restoreCursor >= positions.size() && !entities.isEmpty()) {
            restoreEntities(level);
            entities.clear();
        }
        return spent;
    }

    private void restoreEntry(ServerLevel level, int i) {
        BlockPos pos = BlockPos.of(positions.getLong(i));
        BlockState state = palette.get(states.getInt(i));
        BlockState current = level.getBlockState(pos);

        // Если за время разрыва что-то поставили — выбиваем с дропом, чтобы ничего не пропало
        if (!current.isAir() && !current.canBeReplaced() && current.getFluidState().isEmpty() && !current.equals(state)) {
            level.destroyBlock(pos, true);
        }
        level.setBlock(pos, state, SILENT_FLAGS);

        CompoundTag beTag = blockEntities.get(i);
        if (beTag != null) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                try {
                    be.loadWithComponents(beTag, level.registryAccess());
                    be.setChanged();
                    level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
                } catch (Exception ex) {
                    RuptureMod.LOGGER.error("Rupture: failed to restore block entity at {}", pos, ex);
                }
            }
        }
    }

    private void restoreEntities(ServerLevel level) {
        for (CompoundTag tag : entities) {
            Entity e = EntityType.loadEntityRecursive(tag, level, ent -> ent);
            if (e == null || level.getEntity(e.getUUID()) != null) continue;
            level.addFreshEntity(e);
        }
    }

    // ================================================================== NBT

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("v", 3);
        tag.put("shape", shape.save());
        tag.putInt("minY", minY);
        tag.putInt("maxY", maxY);
        tag.putInt("maxBlocks", maxBlocks);
        tag.putLong("delay", delayTicks);
        tag.putString("phase", phase.name());
        tag.putLong("restoreAt", restoreAt);
        tag.putInt("carveY", carveY);
        tag.putInt("carveIndex", carveIndex);
        tag.putInt("cursor", restoreCursor);

        ListTag pal = new ListTag();
        for (BlockState s : palette) pal.add(NbtUtils.writeBlockState(s));
        tag.put("palette", pal);
        tag.put("positions", new LongArrayTag(positions.toLongArray()));
        tag.put("states", new IntArrayTag(states.toIntArray()));

        ListTag bes = new ListTag();
        blockEntities.int2ObjectEntrySet().forEach(e -> {
            CompoundTag be = new CompoundTag();
            be.putInt("i", e.getIntKey());
            be.put("data", e.getValue());
            bes.add(be);
        });
        tag.put("blockEntities", bes);

        ListTag ents = new ListTag();
        ents.addAll(entities);
        tag.put("entities", ents);
        tag.put("forced", new LongArrayTag(forcedChunks.toLongArray()));
        tag.put("impactChunks", new LongArrayTag(impactChunks.toLongArray()));
        return tag;
    }

    public static RestoreJob load(CompoundTag tag, HolderLookup.Provider registries) {
        int v = tag.getInt("v");
        // Старые форматы (1.0–1.4): просто собираем то, что уже вырезано
        boolean legacy = v < 3;
        RuptureShape shape = legacy
                ? new RuptureShape(Vec3.atCenterOf(BlockPos.of(tag.getLong("center"))), new Vec3(0, -1, 0), 0, 0f)
                : RuptureShape.load(tag.getCompound("shape"));
        RestoreJob job = new RestoreJob(shape, tag.getInt("minY"), tag.getInt("maxY"),
                legacy ? Integer.MAX_VALUE : tag.getInt("maxBlocks"), tag.getLong("delay"));
        var blockLookup = registries.lookupOrThrow(Registries.BLOCK);

        ListTag pal = tag.getList("palette", Tag.TAG_COMPOUND);
        for (int i = 0; i < pal.size(); i++) {
            BlockState s = NbtUtils.readBlockState(blockLookup, pal.getCompound(i));
            job.palette.add(s);
            job.paletteIndex.put(s, i);
        }
        job.positions.addElements(0, tag.getLongArray("positions"));
        job.states.addElements(0, tag.getIntArray("states"));

        ListTag bes = tag.getList("blockEntities", Tag.TAG_COMPOUND);
        for (int i = 0; i < bes.size(); i++) {
            CompoundTag be = bes.getCompound(i);
            job.blockEntities.put(be.getInt("i"), be.getCompound("data"));
        }
        ListTag ents = tag.getList("entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < ents.size(); i++) job.entities.add(ents.getCompound(i));

        job.restoreAt = tag.getLong("restoreAt");
        job.restoreCursor = tag.getInt("cursor");
        if (legacy) {
            job.phase = v == 2 && "RESTORING".equals(tag.getString("phase")) ? Phase.RESTORING : Phase.WAITING;
            job.forcedChunks.addElements(0, tag.getLongArray("forced"));
            if (v == 2 && "CARVING".equals(tag.getString("phase"))) {
                // кратер 1.4.0 не довырезан: разворачиваем снимок (он шёл сверху вниз) и собираем
                job.reverseSnapshot();
            }
        } else {
            job.phase = Phase.valueOf(tag.getString("phase"));
            job.carveY = tag.getInt("carveY");
            job.carveIndex = tag.getInt("carveIndex");
            job.forcedChunks.addElements(0, tag.getLongArray("forced"));
            job.impactChunks.addAll(LongArrayList.wrap(tag.getLongArray("impactChunks")));
        }
        return job;
    }
}
