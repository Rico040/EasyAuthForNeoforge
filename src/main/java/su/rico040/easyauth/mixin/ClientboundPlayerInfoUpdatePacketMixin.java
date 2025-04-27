package su.rico040.easyauth.mixin;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import su.rico040.easyauth.utils.PlayerAuth;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static su.rico040.easyauth.EasyAuth.extendedConfig;

@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public class ClientboundPlayerInfoUpdatePacketMixin {
    @Mutable
    @Final
    @Shadow
    private List<ClientboundPlayerInfoUpdatePacket.Entry> entries;

    @Unique
    private static boolean hideFromTabList(ServerPlayer player) {
        return !((PlayerAuth) player).easyAuth$isAuthenticated();
    }
    @ModifyVariable(
            method = "<init>(Ljava/util/EnumSet;Ljava/util/Collection;)V",
            at = @At("HEAD"),
            argsOnly = true)
    private static Collection<ServerPlayer> playerListS2CPacket(Collection<ServerPlayer> players) {
        // direct removeIf errors out as this seems to receive ImmutableCollection from time to time (?)
        if (extendedConfig.hidePlayersFromPlayerList) {
            ArrayList<ServerPlayer> temp = new ArrayList<>();
            for (ServerPlayer player : players) {
                if (!hideFromTabList(player)) {
                    temp.add(player);
                }
            }
            return temp.stream().toList();
        }
        return players;
    }
    /* Check the other, single player arg constructor - overriding the entries field with empty if not allowed */
    @Redirect(
            method = "<init>(Lnet/minecraft/network/protocol/game/ClientboundPlayerInfoUpdatePacket$Action;Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/network/protocol/game/ClientboundPlayerInfoUpdatePacket;entries:Ljava/util/List;",
                    opcode = Opcodes.PUTFIELD
            )
    )
    private void checkSetEntries(ClientboundPlayerInfoUpdatePacket instance, List<ClientboundPlayerInfoUpdatePacket.Entry> entries, ClientboundPlayerInfoUpdatePacket.Action _action, ServerPlayer player) {
        assert !entries.isEmpty();
        if (extendedConfig.hidePlayersFromPlayerList && hideFromTabList(player)) {
            this.entries = new ArrayList<>();
            return;
        }
        this.entries = entries;
    }
}
