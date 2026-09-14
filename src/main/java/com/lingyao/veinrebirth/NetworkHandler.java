package com.lingyao.veinrebirth;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 简单的配置同步通道。
 * <p>
 * 单人游戏里客户端与服务端共用同一份内存数值，这里主要是为多人游戏准备的：
 * 客户端在配置界面点保存后把配置文本发给服务端，服务端应用并落盘；
 * 玩家进入服务器时服务端也会把当前配置发给他，让界面显示服务端的真实数值。
 */
public final class NetworkHandler {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(VeinRebirthMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private NetworkHandler() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, ConfigSyncPacket.class,
                ConfigSyncPacket::encode, ConfigSyncPacket::decode, ConfigSyncPacket::handle);
    }

    /** 服务端 -> 单个玩家。 */
    public static void sendTo(ServerPlayer player, ConfigSyncPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /** 服务端 -> 所有玩家。 */
    public static void broadcast(ConfigSyncPacket packet) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), packet);
    }

    /** 客户端 -> 服务端。 */
    public static void sendToServer(ConfigSyncPacket packet) {
        CHANNEL.sendToServer(packet);
    }
}
