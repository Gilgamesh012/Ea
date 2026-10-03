package com.rupture.network;

import com.rupture.RuptureMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Сервер → клиенты: «Энума Элиш» выпущен. Клиенты рисуют алый вихрь, вспышку и тряску.
 */
public record StrikeFxPayload(Vec3 start, Vec3 end, float charge, float impactRadius) implements CustomPacketPayload {
    public static final Type<StrikeFxPayload> TYPE = new Type<>(RuptureMod.id("strike_fx"));

    public static final StreamCodec<FriendlyByteBuf, StrikeFxPayload> CODEC =
            StreamCodec.ofMember(StrikeFxPayload::write, StrikeFxPayload::read);

    private static void write(StrikeFxPayload p, FriendlyByteBuf buf) {
        buf.writeDouble(p.start.x); buf.writeDouble(p.start.y); buf.writeDouble(p.start.z);
        buf.writeDouble(p.end.x); buf.writeDouble(p.end.y); buf.writeDouble(p.end.z);
        buf.writeFloat(p.charge);
        buf.writeFloat(p.impactRadius);
    }

    private static StrikeFxPayload read(FriendlyByteBuf buf) {
        Vec3 s = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        Vec3 e = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        return new StrikeFxPayload(s, e, buf.readFloat(), buf.readFloat());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Выполняется только на клиенте. */
    public static void handle(StrikeFxPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.rupture.client.ClientFx.onStrike(payload));
    }
}
