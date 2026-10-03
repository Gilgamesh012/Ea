package com.rupture.item;

import com.rupture.RuptureConfig;
import com.rupture.strike.RuptureStrike;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
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
    private static final Vector3f VOID_DARK = new Vector3f(0.15f, 0.0f, 0.05f);

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
        int used = getUseDuration(stack, entity) - remainingUseDuration;
        float charge = chargeFromTicks(used);

        if (level.isClientSide) {
            spawnChargeParticles(level, entity, used, charge);
            return;
        }

        if (entity instanceof Player player && used % 4 == 0) {
            int pct = Math.round(charge * 100f);
            player.displayClientMessage(Component.translatable("message.rupture.charge", pct)
                    .withStyle(charge >= 1f ? ChatFormatting.DARK_RED : ChatFormatting.RED), true);
        }
        // Нарастающий гул и щелчок на полном заряде
        if (used % 20 == 0 && charge < 1f) {
            level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                    SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, 0.6f + charge, 0.5f + charge);
        }
        if (used == RuptureConfig.chargeTicks()) {
            level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                    SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.5f, 0.5f);
        }
    }

    /** Вращающиеся частицы искажения вокруг игрока; плотность и радиус растут с зарядом. */
    private static void spawnChargeParticles(Level level, LivingEntity e, int used, float charge) {
        RandomSource rnd = e.getRandom();
        int rings = 1 + (int) (charge * 2);
        int perRing = 3 + (int) (charge * 6);
        double radius = 1.0 + charge * 1.2;
        double spin = used * (0.12 + charge * 0.25);

        for (int r = 0; r < rings; r++) {
            double y = e.getY() + 0.2 + r * 0.7;
            double dir = (r % 2 == 0) ? 1 : -1; // кольца крутятся в разные стороны
            for (int i = 0; i < perRing; i++) {
                double a = dir * spin + i * (Math.PI * 2 / perRing);
                double x = e.getX() + Math.cos(a) * radius;
                double z = e.getZ() + Math.sin(a) * radius;
                level.addParticle(new DustParticleOptions(rnd.nextFloat() < charge ? CRIMSON : VOID_DARK, 0.8f + charge),
                        x, y, z, 0, 0, 0);
            }
        }
        // Затягивание пространства к мечу
        if (rnd.nextFloat() < 0.3f + charge) {
            level.addParticle(ParticleTypes.REVERSE_PORTAL,
                    e.getX() + (rnd.nextDouble() - 0.5) * 4, e.getY() + rnd.nextDouble() * 2.5, e.getZ() + (rnd.nextDouble() - 0.5) * 4,
                    0, 0, 0);
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
        level.addParticle(rnd.nextBoolean() ? ParticleTypes.REVERSE_PORTAL : new DustParticleOptions(CRIMSON, 0.6f),
                x, y, z, 0, 0.01, 0);
    }

    // ------------------------------------------------------------------ прочее

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.rupture.sword_of_rupture.desc").withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
        tooltip.add(Component.translatable("item.rupture.sword_of_rupture.ability").withStyle(ChatFormatting.GOLD));
    }
}
