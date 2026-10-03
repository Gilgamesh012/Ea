package com.rupture.client;

import com.rupture.item.SwordOfRuptureItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.neoforged.neoforge.client.IArmPoseTransformer;

/**
 * Своя поза руки для третьего лица (расширение enum HumanoidModel.ArmPose, см. META-INF/enumextensions.json).
 * При зарядке рука плавно выносится вперёд на уровень плеча, а клинок Эа встаёт строго вертикально — остриём в небо.
 */
public final class RuptureArmPoses {
    private RuptureArmPoses() {}

    /** Параметры конструктора ArmPose(boolean twoHanded, IArmPoseTransformer). */
    public static final EnumProxy<HumanoidModel.ArmPose> RAISE_EA = new EnumProxy<>(
            HumanoidModel.ArmPose.class, false, (IArmPoseTransformer) RuptureArmPoses::raise);

    /**
     * В руке клинок смотрит вперёд вдоль кисти. Поворот руки вперёд на 90° (xRot = −π/2)
     * переводит клинок из горизонтали ровно в вертикаль. Взмахи ходьбы на этой руке гасятся,
     * чтобы меч не качался.
     */
    private static void raise(HumanoidModel<?> model, LivingEntity entity, HumanoidArm arm) {
        ModelPart part = arm == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
        float side = arm == HumanoidArm.RIGHT ? 1f : -1f;
        float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
        float k = Mth.clamp((entity.getTicksUsingItem() + partial) / 6f, 0f, 1f);
        k = k * k * (3f - 2f * k);                        // плавный подъём за ~0.3 с
        float charge = entity.getUseItem().getItem() instanceof SwordOfRuptureItem
                ? SwordOfRuptureItem.chargeFromTicks(entity.getTicksUsingItem()) : 0f;

        float targetX = -(float) Math.PI / 2f;           // рука вперёд → клинок вертикально
        part.xRot = Mth.lerp(k, part.xRot, targetX);
        part.yRot = Mth.lerp(k, part.yRot, -0.08f * side); // кисть чуть к центру тела
        part.zRot = Mth.lerp(k, part.zRot, 0f);
        // едва заметное «дыхание» силы на полном заряде (не дрожь)
        part.xRot += Mth.sin((entity.tickCount + partial) * 0.1f) * 0.015f * charge;
    }
}
