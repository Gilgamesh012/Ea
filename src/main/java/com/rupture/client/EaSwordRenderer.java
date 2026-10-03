package com.rupture.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.rupture.RuptureMod;
import com.rupture.item.SwordOfRuptureItem;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Рендер Эа: неподвижная рукоять + три цилиндра клинка, вращающиеся в разные стороны.
 * Во время зарядки «Энума Элиш» цилиндры раскручиваются всё быстрее.
 */
public final class EaSwordRenderer extends BlockEntityWithoutLevelRenderer {
    public static final ModelResourceLocation STATIC = model("ea_static");
    public static final ModelResourceLocation CYL_1 = model("ea_cyl_1");
    public static final ModelResourceLocation CYL_2 = model("ea_cyl_2");
    public static final ModelResourceLocation CYL_3 = model("ea_cyl_3");

    /** Ось клинка в координатах модели (пиксели /16). */
    private static final float AXIS_X = 8.88f / 16f;
    private static final float AXIS_Z = 8.245f / 16f;

    private static final float BASE_SPEED = 40f;      // град/сек в покое
    private static final float CHARGE_SPEED = 900f;   // добавка на 100% заряда

    private static float angle;
    private static long lastNanos;

    public EaSwordRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    private static ModelResourceLocation model(String name) {
        return ModelResourceLocation.standalone(RuptureMod.id("item/ea/" + name));
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext ctx, PoseStack ps, MultiBufferSource buffers, int light, int overlay) {
        Minecraft mc = Minecraft.getInstance();
        advance(mc);

        ItemRenderer ir = mc.getItemRenderer();
        VertexConsumer vc = ItemRenderer.getFoilBufferDirect(buffers,
                RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS), true, stack.hasFoil());

        ir.renderModelLists(get(mc, STATIC), stack, light, overlay, ps, vc);
        spin(ir, get(mc, CYL_1), stack, light, overlay, ps, vc, angle);
        spin(ir, get(mc, CYL_2), stack, light, overlay, ps, vc, -angle * 1.3f);
        spin(ir, get(mc, CYL_3), stack, light, overlay, ps, vc, angle * 1.6f);
    }

    private static void spin(ItemRenderer ir, BakedModel model, ItemStack stack, int light, int overlay,
                             PoseStack ps, VertexConsumer vc, float degrees) {
        ps.pushPose();
        ps.translate(AXIS_X, 0, AXIS_Z);
        ps.mulPose(Axis.YP.rotationDegrees(degrees));
        ps.translate(-AXIS_X, 0, -AXIS_Z);
        ir.renderModelLists(model, stack, light, overlay, ps, vc);
        ps.popPose();
    }

    private static BakedModel get(Minecraft mc, ModelResourceLocation id) {
        return mc.getModelManager().getModel(id);
    }

    /** Угол накапливается по реальному времени: плавно и в мире, и в инвентаре. */
    private static void advance(Minecraft mc) {
        long now = Util.getNanos();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1.0e9f);
        lastNanos = now;

        float speed = BASE_SPEED;
        Player p = mc.player;
        if (p != null && p.isUsingItem() && p.getUseItem().getItem() instanceof SwordOfRuptureItem) {
            float charge = SwordOfRuptureItem.chargeFromTicks(p.getTicksUsingItem());
            speed += CHARGE_SPEED * charge * charge;
        }
        angle = (angle + speed * dt) % 36000f;
    }
}
