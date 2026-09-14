package com.lingyao.veinrebirth;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * 配置同步包：内容就是配置文件的完整文本（UTF-8 字符串）。
 * <p>
 * 这样客户端与服务端共用同一套序列化 / 解析逻辑，不需要为每个字段单独写协议。
 */
public class ConfigSyncPacket {

    private static final int MAX_LENGTH = 0x100000;

    private final String data;

    public ConfigSyncPacket(String data) {
        this.data = data == null ? "" : data;
    }

    public String data() {
        return this.data;
    }

    public static void encode(ConfigSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.data, MAX_LENGTH);
    }

    public static ConfigSyncPacket decode(FriendlyByteBuf buf) {
        return new ConfigSyncPacket(buf.readUtf(MAX_LENGTH));
    }

    public static void handle(ConfigSyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                // 来自客户端（服务端侧）：需要 OP 权限，应用后落盘并广播给所有人
                if (!sender.hasPermissions(2)) {
                    return;
                }
                ConfigManager.applyFromText(packet.data());
                ConfigManager.save();
                NetworkHandler.broadcast(new ConfigSyncPacket(ConfigManager.serialize()));
            } else {
                // 来自服务端（客户端侧）：应用服务端的数值
                ConfigManager.applyFromText(packet.data());
            }
        });
        context.setPacketHandled(true);
    }
}
