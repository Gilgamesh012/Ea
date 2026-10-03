package com.rupture.item;

import com.rupture.RuptureConfig;
import com.rupture.registry.ModSounds;
import com.rupture.strike.RuptureStrike;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.util.List;

/**
 * Меч Разрыва (Ea).
 * ЛКМ — обычный удар на 30 урона. ПКМ (удержание) — зарядка «Энума Элиш», отпускание — выстрел разрыва.
 */
public class SwordOfRuptureItem extends SwordItem {
    /** Итоговый урон простой атаки (база игрока 1 + модификатор 29). */
    public static final double MELEE_DAMAGE = 30.0;
    public static final double ATTACK_SPEED = 1.6; // как у обычного меча

    private static final Vector3f CRIMSON = new Vector3f(0.85f, 0.05f, 0.08f);

    public SwordOfRuptureItem(Item.Properties props) {
        super(Tiers.NETHERITE, props
                .attributes(createRuptureAttributes())
                .component(DataComponents.UNBREAKABLE, new Unbreakable(true))
                .fireResistant()
                .rarity(Rarity.EPIC));
    }

    private static ItemAttributeModifiers createRuptureAttributes() {
        return ItemAttributeModifiers.builder()
                .add(Attributes.ATTACK_DAMAGE,
                        new AttributeModifier(BASE_ATTACK_DAMAGE_ID, MELEE_DAMAGE - 1.0, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ATTACK_SPEED,
                        new AttributeModifier(BASE_ATTACK_SPEED_ID, ATTACK_SPEED - 4.0, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .build();
    }

    // ------------------------------------------------------------------ зарядка

    /** Заряд 0..1 по числу тиков удержания. */
    public static float chargeFromTicks(int usedTicks) {
        return Mth.clamp(usedTicks / (float) RuptureConfig.chargeTicks(), 0f, 1f);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.getCooldowns().isOnCooldown(this)) {
            return InteractionResultHolder.fail(stack);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.SPEAR;
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingUseDuration) {
        // Визуал зарядки (вихрь, трещины, тряска, тьма) рисуется на клиенте в ClientFx для всех игроков рядом.
        if (level.isClientSide) return;

        int used = getUseDuration(stack, entity) - remainingUseDuration;
        int full = RuptureConfig.chargeTicks();
        float charge = chargeFromTicks(used);
        double x = entity.getX(), y = entity.getY(), z = entity.getZ();

        if (entity instanceof Player player && used % 4 == 0) {
            int pct = Math.round(charge * 100f);
            player.displayClientMessage(Component.translatable("message.rupture.charge", pct)
                    .withStyle(charge >= 1f ? ChatFormatting.DARK_RED : ChatFormatting.RED), true);
        }

        // Рёв вихря (закольцованный) играет на клиенте и следует за игроком — см. ChargeSoundInstance.
        // Каждые 10% пространство трескается
        int step = Math.max(1, full / 10);
        if (charge < 1f && used > 0 && used % step == 0) {
            level.playSound(null, x, y, z, ModSounds.CRACK.get(), SoundSource.PLAYERS, 0.6f + charge * 1.6f, 1.1f - charge * 0.35f);
        }
        // Полный заряд: суббас-удар и белая вспышка
        if (used == full) {
            level.playSound(null, x, y, z, ModSounds.FULL_CHARGE.get(), SoundSource.PLAYERS, 3.0f, 1.0f);
        }
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (!(entity instanceof Player player)) return;
        int used = getUseDuration(stack, entity) - timeLeft;
        float charge = chargeFromTicks(used);

        if (charge < RuptureConfig.MIN_CHARGE_TO_FIRE.get()) return;

        if (level instanceof ServerLevel serverLevel) {
            RuptureStrike.fire(serverLevel, player, charge);
            player.getCooldowns().addCooldown(this, RuptureConfig.COOLDOWN_TICKS.get());
            player.displayClientMessage(Component.empty(), true);
        }
    }

    // ------------------------------------------------------------------ пассивка

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (!level.isClientSide || !selected || !(entity instanceof LivingEntity living) || living.isUsingItem()) return;
        RandomSource rnd = level.random;
        if (rnd.nextInt(12) != 0) return;
        double x = entity.getX() + (rnd.nextDouble() - 0.5) * 2.5;
        double y = entity.getY() + rnd.nextDouble() * 2.0;
        double z = entity.getZ() + (rnd.nextDouble() - 0.5) * 2.5;
        level.addParticle(new DustParticleOptions(CRIMSON, 0.5f + rnd.nextFloat() * 0.5f),
                x, y, z, 0, 0.01, 0);
    }

    // ------------------------------------------------------------------ прочее

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.rupture.sword_of_rupture.desc").withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
        tooltip.add(Component.translatable("item.rupture.sword_of_rupture.ability").withStyle(ChatFormatting.GOLD));
    }
}
