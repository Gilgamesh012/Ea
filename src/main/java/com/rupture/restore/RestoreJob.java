package com.rupture.restore;

import com.rupture.RuptureMod;
import com.rupture.registry.ModTags;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Один «разрыв»: снимок вырезанных блоков (+ данные блок-сущностей и «висячих» сущностей)
 * и прогресс их восстановления.
 */
public final class RestoreJob {
    /** Без соседних обновлений и пересчёта форм: ничего не осыпается, не всплывает и не дропается. */
    static final int SILENT_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    record Entry(BlockPos pos, BlockState state, @Nullable CompoundTag blockEntity) {}

    private final List<Entry> entries;
    private final List<CompoundTag> entities;
    private final long restoreAtGameTime;
    private final BlockPos center;
    private int cursor;

    private RestoreJob(List<Entry> entries, List<CompoundTag> entities, long restoreAt, BlockPos center, int cursor) {
        this.entries = entries;
        this.entities = entities;
        this.restoreAtGameTime = restoreAt;
        this.center = center;
        this.cursor = cursor;
    }

    public boolean isEmpty() {
        return entries.isEmpty() && entities.isEmpty();
    }

    public boolean isReady(long gameTime) {
        return gameTime >= restoreAtGameTime;
    }

    public BlockPos center() {
        return center;
    }

    // ================================================================== вырезание

    public static RestoreJob carve(ServerLevel level, BlockPos center, double radius, int maxBlocks, long restoreAt) {
        int ir = Mth.ceil(radius);
        double r2 = radius * radius;
        List<Entry> entries = new ArrayList<>();

        // Проход 1: снимок (ничего не меняем, чтобы двойные сундуки/кровати читались целыми)
        outer:
        for (int dy = -ir; dy <= ir; dy++) {
            for (int dx = -ir; dx <= ir; dx++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r2) continue;
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.isInWorldBounds(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;
                    if (state.getDestroySpeed(level, pos) < 0) continue; // бедрок, барьер, порталы
                    if (state.is(ModTags.RUPTURE_IMMUNE)) continue;

                    BlockEntity be = level.getBlockEntity(pos);
                    CompoundTag beTag = be != null ? be.saveWithFullMetadata(level.registryAccess()) : null;
                    entries.add(new Entry(pos.immutable(), state, beTag));
                    if (entries.size() >= maxBlocks) break outer;
                }
            }
        }

        // Сущности, которые иначе отвалятся и выронят предметы (рамки, картины, стойки для брони)
        List<CompoundTag> entities = new ArrayList<>();
        Vec3 c = Vec3.atCenterOf(center);
        AABB box = new AABB(c, c).inflate(radius + 1);
        for (Entity e : level.getEntities((Entity) null, box,
                e -> (e instanceof HangingEntity || e instanceof ArmorStand) && e.position().distanceToSqr(c) <= (radius + 1) * (radius + 1))) {
            CompoundTag tag = new CompoundTag();
            if (e.save(tag)) {
                entities.add(tag);
                e.discard();
            }
        }

        // Проход 2: удаление. Содержимое контейнеров очищаем ДО удаления, иначе onRemove вывалит его на землю.
        for (Entry entry : entries) {
            BlockEntity be = level.getBlockEntity(entry.pos());
            if (be != null) Clearable.tryClear(be);
            level.setBlock(entry.pos(), Blocks.AIR.defaultBlockState(), SILENT_FLAGS);
        }

        // Восстанавливать будем снизу вверх: сначала опоры, потом то, что на них стоит/висит
        entries.sort(Comparator.comparingInt((Entry e) -> e.pos().getY())
                .thenComparingInt(e -> e.pos().getX())
                .thenComparingInt(e -> e.pos().getZ()));

        return new RestoreJob(entries, entities, restoreAt, center.immutable(), 0);
    }

    // ================================================================== восстановление

    /**
     * Восстанавливает до {@code budget} блоков. Возвращает, сколько бюджета потрачено.
     */
    public int restoreSome(ServerLevel level, int budget) {
        int spent = 0;
        while (spent < budget && cursor < entries.size()) {
            restoreEntry(level, entries.get(cursor));
            cursor++;
            spent++;
        }
        if (cursor >= entries.size() && !entities.isEmpty()) {
            restoreEntities(level);
            entities.clear();
        }
        return spent;
    }

    public boolean isDone() {
        return cursor >= entries.size() && entities.isEmpty();
    }

    private static void restoreEntry(ServerLevel level, Entry entry) {
        BlockPos pos = entry.pos();
        BlockState current = level.getBlockState(pos);

        // Если за время разрыва игрок что-то поставил — выбиваем это с дропом, чтобы ничего не пропало
        if (!current.isAir() && !current.canBeReplaced() && current.getFluidState().isEmpty() && !current.equals(entry.state())) {
            level.destroyBlock(pos, true);
        }

        level.setBlock(pos, entry.state(), SILENT_FLAGS);

        if (entry.blockEntity() != null) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                try {
                    be.loadWithComponents(entry.blockEntity(), level.registryAccess());
                    be.setChanged();
                    level.sendBlockUpdated(pos, entry.state(), entry.state(), Block.UPDATE_ALL);
                } catch (Exception ex) {
                    RuptureMod.LOGGER.error("Rupture: failed to restore block entity at {}", pos, ex);
                }
            }
        }
    }

    private void restoreEntities(ServerLevel level) {
        for (CompoundTag tag : entities) {
            Entity e = EntityType.loadEntityRecursive(tag, level, ent -> ent);
            if (e == null) continue;
            if (level.getEntity(e.getUUID()) != null) continue; // уже существует — не дублируем
            level.addFreshEntity(e);
        }
    }

    // ================================================================== NBT

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("restoreAt", restoreAtGameTime);
        tag.putLong("center", center.asLong());
        tag.putInt("cursor", cursor);

        // Палитра состояний — чтобы не писать один и тот же BlockState тысячи раз
        Map<BlockState, Integer> paletteIdx = new HashMap<>();
        ListTag palette = new ListTag();
        long[] positions = new long[entries.size()];
        int[] states = new int[entries.size()];
        ListTag blockEntities = new ListTag();

        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            positions[i] = e.pos().asLong();
            states[i] = paletteIdx.computeIfAbsent(e.state(), s -> {
                palette.add(NbtUtils.writeBlockState(s));
                return palette.size() - 1;
            });
            if (e.blockEntity() != null) {
                CompoundTag be = new CompoundTag();
                be.putInt("i", i);
                be.put("data", e.blockEntity());
                blockEntities.add(be);
            }
        }
        tag.put("palette", palette);
        tag.put("positions", new LongArrayTag(positions));
        tag.put("states", new IntArrayTag(states));
        tag.put("blockEntities", blockEntities);

        ListTag ents = new ListTag();
        ents.addAll(entities);
        tag.put("entities", ents);
        return tag;
    }

    public static RestoreJob load(CompoundTag tag, HolderLookup.Provider registries) {
        var blockLookup = registries.lookupOrThrow(Registries.BLOCK);

        ListTag paletteTag = tag.getList("palette", Tag.TAG_COMPOUND);
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(NbtUtils.readBlockState(blockLookup, paletteTag.getCompound(i)));
        }

        long[] positions = tag.getLongArray("positions");
        int[] states = tag.getIntArray("states");

        Map<Integer, CompoundTag> bes = new HashMap<>();
        ListTag beList = tag.getList("blockEntities", Tag.TAG_COMPOUND);
        for (int i = 0; i < beList.size(); i++) {
            CompoundTag be = beList.getCompound(i);
            bes.put(be.getInt("i"), be.getCompound("data"));
        }

        List<Entry> entries = new ArrayList<>(positions.length);
        for (int i = 0; i < positions.length; i++) {
            entries.add(new Entry(BlockPos.of(positions[i]), palette.get(states[i]), bes.get(i)));
        }

        List<CompoundTag> entities = new ArrayList<>();
        ListTag ents = tag.getList("entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < ents.size(); i++) entities.add(ents.getCompound(i));

        return new RestoreJob(entries, entities, tag.getLong("restoreAt"), BlockPos.of(tag.getLong("center")), tag.getInt("cursor"));
    }
}
