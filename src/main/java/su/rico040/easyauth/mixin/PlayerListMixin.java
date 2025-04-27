package su.rico040.easyauth.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import su.rico040.easyauth.event.AuthEventHandler;
import su.rico040.easyauth.utils.PlayerAuth;

import java.io.File;
import java.net.SocketAddress;
import java.util.Optional;
import java.util.UUID;

import static su.rico040.easyauth.EasyAuth.*;
import static su.rico040.easyauth.utils.EasyLogger.LogDebug;
import static su.rico040.easyauth.utils.EasyLogger.LogWarn;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Unique
    private final PlayerList playerList = (PlayerList) (Object) this;

    @Final
    @Shadow
    private MinecraftServer server;

    @Inject(method = "placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V", at = @At("HEAD"))
    private void placeNewPlayerHead(Connection connection, ServerPlayer player, CommonListenerCookie clientData, CallbackInfo ci) {
        AuthEventHandler.loadPlayerData(player, connection);
    }

    @ModifyVariable(method = "placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V",
            at = @At("STORE"), ordinal = 0)
    private ResourceKey<Level> placeNewPlayer(ResourceKey<Level> world, Connection connection, ServerPlayer player, CommonListenerCookie clientData) {
        if (config.hidePlayerCoords && !((PlayerAuth) player).easyAuth$isAuthenticated()) {
            ((PlayerAuth) player).easyAuth$saveTrueDimension(world);
            return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(config.worldSpawn.dimension));
        }
        return world;
    }

    @ModifyArgs(method = "placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;teleport(DDDFF)V"))
    private void onPlayerConnect(Args args, Connection connection, ServerPlayer player, CommonListenerCookie clientData) {
        if (config.hidePlayerCoords && !((PlayerAuth) player).easyAuth$isAuthenticated()) {
            ((PlayerAuth) player).easyAuth$saveTrueLocation();

            Optional<CompoundTag> nbtCompound = playerList.load(player);
            if(nbtCompound.isPresent() && nbtCompound.get().contains("RootVehicle", 10)) {
                CompoundTag rootVehicle = nbtCompound.get().getCompound("RootVehicle");
                CompoundTag rootRootVehicle = new CompoundTag();
                rootRootVehicle.put("RootVehicle", rootVehicle);
                ((PlayerAuth) player).easyAuth$setRootVehicle(rootRootVehicle);

                if (rootVehicle.contains("Attach")) {
                    ((PlayerAuth) player).easyAuth$setRidingEntityUUID(rootVehicle.getUUID("Attach"));
                    LogDebug(String.format("Saving vehicle of player %s as %s", player.getScoreboardName(), rootVehicle.getUUID("Attach")));
                }
            }

            LogDebug(String.format("Teleporting player %s", player.getScoreboardName()));
            LogDebug(String.format("Spawn position of player %s is %s", player.getScoreboardName(), config.worldSpawn));

            args.set(0, config.worldSpawn.x);
            args.set(1, config.worldSpawn.y);
            args.set(2, config.worldSpawn.z);
            args.set(3, config.worldSpawn.yaw);
            args.set(4, config.worldSpawn.pitch);
        }
    }

    @Inject(method = "placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V", at = @At("RETURN"))
    private void onPlayerConnectReturn(Connection connection, ServerPlayer player, CommonListenerCookie clientData, CallbackInfo ci) {
        AuthEventHandler.onPlayerJoin(player);
    }

    @Redirect(method = "respawn",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;findRespawnPositionAndUseSpawnBlock(ZLnet/minecraft/world/level/portal/DimensionTransition$PostDimensionTransition;)Lnet/minecraft/world/level/portal/DimensionTransition;"))
    private DimensionTransition replaceRespawnTarget(ServerPlayer player, boolean alive, DimensionTransition.PostDimensionTransition postDimensionTransition) {
        if (!alive && config.hidePlayerCoords && !((PlayerAuth) player).easyAuth$isAuthenticated()) {
            return new DimensionTransition(
                    this.server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(config.worldSpawn.dimension))),
                    new Vec3(config.worldSpawn.x, config.worldSpawn.y, config.worldSpawn.z),
                    new Vec3(0.0F, 0.0F, 0.0F), config.worldSpawn.yaw, config.worldSpawn.pitch, postDimensionTransition
            );
        }
        return player.findRespawnPositionAndUseSpawnBlock(alive, postDimensionTransition);
    }

    @Redirect(method = "placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;startRiding(Lnet/minecraft/world/entity/Entity;Z)Z"))
    private boolean onPlayerConnectStartRiding(ServerPlayer instance, Entity entity, boolean force, Connection connection, ServerPlayer player, CommonListenerCookie clientData) {
        if (config.hidePlayerCoords && !((PlayerAuth) player).easyAuth$isAuthenticated()) {
            return false;
        }
        return instance.startRiding(entity, force);
    }

    @Redirect(method = "placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isPassenger()Z"))
    private boolean onPlayerConnectStartRiding(ServerPlayer instance, Connection connection, ServerPlayer player, CommonListenerCookie clientData) {
        if (config.hidePlayerCoords && !((PlayerAuth) player).easyAuth$isAuthenticated()) {
            return true;
        }
        return instance.isPassenger();
    }

    @Inject(method = "remove(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"))
    private void onPlayerLeave(ServerPlayer serverPlayerEntity, CallbackInfo ci) {
        AuthEventHandler.onPlayerLeave(serverPlayerEntity);
    }

    @Inject(method = "canPlayerLogin(Ljava/net/SocketAddress;Lcom/mojang/authlib/GameProfile;)Lnet/minecraft/network/chat/Component;", at = @At("HEAD"), cancellable = true)
    private void checkCanJoin(SocketAddress socketAddress, GameProfile profile, CallbackInfoReturnable<Component> cir) {
        // Getting the player that is trying to join the server
        Component returnText = AuthEventHandler.checkCanPlayerJoinServer(profile, playerList, socketAddress);

        if (returnText != null) {
            // Canceling player joining with the returnText message
            cir.setReturnValue(returnText);
        }
    }

    @Inject(method = "getPlayerStats(Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/stats/ServerStatsCounter;",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
            )
    )
    private void migrateOfflineStats(Player player, CallbackInfoReturnable<ServerStatsCounter> cir, @Local UUID uUID, @Local ServerStatsCounter serverStatHandler, @Local(ordinal = 0) File serverStatsDir) {
        File onlineFile = new File(serverStatsDir, uUID + ".json");
        if (server.usesAuthentication() && !extendedConfig.forcedOfflineUuid && ((PlayerAuth) player).easyAuth$isUsingMojangAccount() && !onlineFile.exists()) {
            String playername = player.getGameProfile().getName();
            File offlineFile = new File(onlineFile.getParent(), UUIDUtil.createOfflinePlayerUUID(playername) + ".json");
            if (!offlineFile.renameTo(onlineFile)) {
                LogWarn("Failed to migrate offline stats (" + offlineFile.getName() + ") for player " + playername + " to online stats (" + onlineFile.getName() + ")");
            } else {
                LogDebug("Migrated offline stats (" + offlineFile.getName() + ") for player " + playername + " to online stats (" + onlineFile.getName() + ")");
            }

            serverStatHandler.file = onlineFile;
        }
    }
}
