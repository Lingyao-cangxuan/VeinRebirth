package com.lingyao.veinrebirth;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 通用（客户端 + 服务端）事件：注册命令、补充识别模组矿物、玩家进入服务器时同步配置。
 */
@Mod.EventBusSubscriber(modid = VeinRebirthMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CommonEvents {

    private CommonEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        OreGenCommand.register(event);
    }

    /**
     * 服务端完全启动后再识别一次：此时数据包已加载，方块标签（forge:ores /
     * forge:ores_in_ground/*）才有内容，可以补上没按 _ore 命名的矿石并修正维度归属。
     * <p>
     * 放在这里而不是更早，是为了拿到已加载的标签；放在这里而不是之后，是为了让世界生成
     * （出生点区块之后的新区块）就能用上正确的分组。
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        OreDiscoverer.discoverWithTags();
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            NetworkHandler.sendTo(player, new ConfigSyncPacket(ConfigManager.serialize()));
        }
    }
}
