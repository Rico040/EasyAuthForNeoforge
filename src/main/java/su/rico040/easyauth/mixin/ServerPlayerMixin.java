package su.rico040.easyauth.mixin;

import com.google.common.net.InetAddresses;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import su.rico040.easyauth.event.AuthEventHandler;
import su.rico040.easyauth.storage.PlayerEntryV1;
import su.rico040.easyauth.utils.FloodgateApiHelper;
import su.rico040.easyauth.utils.LastLocation;
import su.rico040.easyauth.utils.PlayerAuth;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Iterator;
import java.util.UUID;

import static su.rico040.easyauth.EasyAuth.*;
import static su.rico040.easyauth.utils.EasyLogger.LogDebug;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin implements PlayerAuth {
    @Unique
    private final ServerPlayer player = (ServerPlayer) (Object) this;

    @Final
    @Shadow
    public MinecraftServer server;

    @Unique
    private long kickTimer = config.kickTimeout * 20;

    @Unique
    private String ipAddress = null;

    @Unique
    private LastLocation lastLocation = null;

    @Unique
    private UUID ridingEntityUUID = null;

    @Unique
    private CompoundTag rootVehicle = null;

    @Unique
    private boolean wasDead = false;

    @Unique
    PlayerEntryV1 playerEntryV1 = new PlayerEntryV1(player.getScoreboardName());

    @Unique
    private boolean canSkipAuth = false;

    @Unique
    private boolean isAuthenticated = false;

    @Unique
    private boolean isUsingMojangAccount = false;

    @Override
    public void easyAuth$saveTrueLocation() {
        if (lastLocation == null) {
            lastLocation = new LastLocation();
        }
        lastLocation.position = player.position();
        lastLocation.yaw = player.getYRot();
        lastLocation.pitch = player.getXRot();

        ridingEntityUUID = player.getVehicle() != null ? player.getVehicle().getUUID() : null;
        wasDead = player.isDeadOrDying();
        LogDebug(String.format("Saving position of player %s as %s", player.getScoreboardName(), lastLocation));
        if (ridingEntityUUID != null) {
            LogDebug(String.format("Saving vehicle of player %s as %s", player.getScoreboardName(), ridingEntityUUID));
        }
    }

    @Override
    public void easyAuth$saveTrueDimension(ResourceKey<Level> registryKey) {
        if (lastLocation == null) {
            lastLocation = new LastLocation();
        }
        lastLocation.dimension = this.server.getLevel(registryKey);
    }

    @Override
    public void easyAuth$restoreTrueLocation() {
        if (lastLocation == null) {
            return;
        }
        if (wasDead) {
            player.kill();
            player.getScoreboard().forAllObjectives(ObjectiveCriteria.DEATH_COUNT, player, (score) -> score.set(score.get() - 1));
            return;
        }
        // Puts player to last saved position
        player.teleportTo(
                lastLocation.dimension == null ? server.getLevel(Level.OVERWORLD) : lastLocation.dimension,
                lastLocation.position.x(),
                lastLocation.position.y(),
                lastLocation.position.z(),
                lastLocation.yaw,
                lastLocation.pitch);
        LogDebug(String.format("Teleported player %s to %s", player.getScoreboardName(), lastLocation));

        if (rootVehicle != null) {
            LogDebug(String.format("Mounting player to vehicle %s", rootVehicle));

            CompoundTag nbtCompound = rootVehicle.getCompound("RootVehicle");
            Entity entity = EntityType.loadEntityRecursive(nbtCompound.getCompound("Entity"), player.serverLevel(), (vehicle) -> !player.serverLevel().addWithUUID(vehicle) ? null : vehicle);
            if (entity != null) {
                UUID uUID;
                if (nbtCompound.hasUUID("Attach")) {
                    uUID = nbtCompound.getUUID("Attach");
                } else {
                    uUID = null;
                }

                Iterator var23;
                Entity entity2;
                if (entity.getUUID().equals(uUID)) {
                    player.startRiding(entity, true);
                } else {
                    var23 = entity.getIndirectPassengers().iterator();

                    while(var23.hasNext()) {
                        entity2 = (Entity)var23.next();
                        if (entity2.getUUID().equals(uUID)) {
                            player.startRiding(entity2, true);
                            break;
                        }
                    }
                }
            }

        }

        if (player.getVehicle() == null && ridingEntityUUID != null) {
            LogDebug(String.format("Mounting player to vehicle %s", ridingEntityUUID));
            if (lastLocation.dimension == null) return;
            ServerLevel world = server.getLevel(lastLocation.dimension.dimension());
            if (world == null) return;
            Entity entity = world.getEntity(ridingEntityUUID);
            if (entity != null) {
                player.startRiding(entity, true);
            } else {
                LogDebug("Could not find vehicle for player " + player.getScoreboardName());
            }
        }
    }

    /**
     * Gets the text which tells the player
     * to login or register, depending on account status.
     *
     * @return Text with appropriate string (login or register)
     */
    @Override
    public void easyAuth$sendAuthMessage() {
        if ((!config.enableGlobalPassword || config.singleUseGlobalPassword) && (playerEntryV1 == null || playerEntryV1.password.isEmpty())) {
            if (config.singleUseGlobalPassword) {
                langConfig.registerRequiredWithGlobalPassword.send(player);
            } else {
                langConfig.registerRequired.send(player);
            }
        } else {
            langConfig.loginRequired.send(player);
        }
    }

    /**
     * Checks whether player can skip authentication process.
     *
     * @return true if player can skip authentication process, otherwise false
     */
    @Override
    public boolean easyAuth$canSkipAuth() {
        return canSkipAuth;
    }

    @Override
    public void easyAuth$setSkipAuth() {
        easyAuth$setUsingMojangAccount();
        canSkipAuth = (this.player.getClass() != ServerPlayer.class) ||
                (config.floodgateAutoLogin && technicalConfig.floodgateLoaded && FloodgateApiHelper.isFloodgatePlayer(this.player)) ||
                (easyAuth$isUsingMojangAccount() && config.premiumAutoLogin);
    }

    /**
     * Whether the player is using the mojang account.
     *
     * @return true if they are  using mojang account, otherwise false
     */
    @Override
    public boolean easyAuth$isUsingMojangAccount() {
        return isUsingMojangAccount;
    }

    @Override
    public void easyAuth$setUsingMojangAccount() {
        isUsingMojangAccount = server.usesAuthentication() && playerEntryV1.onlineAccount == PlayerEntryV1.OnlineAccount.TRUE;
    }

    /**
     * Checks whether player is authenticated.
     *
     * @return false if player is not authenticated, otherwise true.
     */
    @Override
    public boolean easyAuth$isAuthenticated() {
        return isAuthenticated;
    }

    /**
     * Sets the authentication status of the player
     *
     * @param authenticated whether player should be authenticated
     */
    @Override
    public void easyAuth$setAuthenticated(boolean authenticated) {
        isAuthenticated = authenticated;

        player.setInvulnerable(!authenticated && extendedConfig.playerInvulnerable);
        player.setInvisible(!authenticated && extendedConfig.playerIgnored);

        if (authenticated) {
            kickTimer = config.kickTimeout * 20;
            // Updating blocks if needed (in case if portal rescue action happened)
            Level world = player.level();
            BlockPos pos = player.blockPosition();

            // Sending updates to portal blocks
            // This is technically not needed, but it cleans the "messed portal" on the client
            world.sendBlockUpdated(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
            world.sendBlockUpdated(pos.above(), world.getBlockState(pos.above()), world.getBlockState(pos.above()), 3);

            player.containerMenu.sendAllDataToRemote();
        }
    }

    @Inject(method = "doTick()V", at = @At("HEAD"), cancellable = true)
    private void playerTick(CallbackInfo ci) {
        if (!this.easyAuth$isAuthenticated()) {
            // Checking player timer
            if (kickTimer <= 0 && player.connection.isAcceptingMessages()) {
                player.connection.disconnect(langConfig.timeExpired.get());
            } else {
                // Sending authentication prompt every 10 seconds
                if (kickTimer % (extendedConfig.authenticationPromptInterval * 20) == 0) {
                    this.easyAuth$sendAuthMessage();
                }
                --kickTimer;
            }
            ci.cancel();
        }
    }

    // Player item dropping
    @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
    private void dropSelectedItem(boolean dropEntireStack, CallbackInfoReturnable<Boolean> cir) {
        InteractionResult result = AuthEventHandler.onDropItem(player);

        if (result == InteractionResult.FAIL) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "restoreFrom(Lnet/minecraft/server/level/ServerPlayer;Z)V", at = @At("RETURN"))
    private void copyFrom(ServerPlayer oldPlayer, boolean alive, CallbackInfo ci) {
        PlayerAuth oldPlayerAuth = (PlayerAuth) oldPlayer;
        PlayerAuth newPlayerAuth = (PlayerAuth) player;
        newPlayerAuth.easyAuth$setKickTimer(oldPlayerAuth.easyAuth$getKickTimer());
        newPlayerAuth.easyAuth$setIpAddress(oldPlayerAuth.easyAuth$getIpAddress());
        newPlayerAuth.easyAuth$setLastLocation(oldPlayerAuth.easyAuth$getLastLocation());
        newPlayerAuth.easyAuth$setRidingEntityUUID(oldPlayerAuth.easyAuth$getRidingEntityUUID());
        newPlayerAuth.easyAuth$setRootVehicle(oldPlayerAuth.easyAuth$getRootVehicle());
        newPlayerAuth.easyAuth$wasDead(oldPlayerAuth.easyAuth$wasDead());
        newPlayerAuth.easyAuth$canSkipAuth(oldPlayerAuth.easyAuth$canSkipAuth());
        newPlayerAuth.easyAuth$setAuthenticated(oldPlayerAuth.easyAuth$isAuthenticated());

        newPlayerAuth.easyAuth$setPlayerEntryV1(oldPlayerAuth.easyAuth$getPlayerEntryV1());
    }

    public long easyAuth$getKickTimer() {
        return kickTimer;
    }

    public void easyAuth$setKickTimer(long kickTimer) {
        this.kickTimer = kickTimer;
    }

    public void easyAuth$setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public LastLocation easyAuth$getLastLocation() {
        return lastLocation;
    }

    public void easyAuth$setLastLocation(LastLocation lastLocation) {
        this.lastLocation = lastLocation;
    }

    public UUID easyAuth$getRidingEntityUUID() {
        return ridingEntityUUID;
    }

    public void easyAuth$setRidingEntityUUID(UUID ridingEntityUUID) {
        this.ridingEntityUUID = ridingEntityUUID;
    }

    public CompoundTag easyAuth$getRootVehicle() {
        return rootVehicle;
    }

    public void easyAuth$setRootVehicle(CompoundTag rootVehicle) {
        this.rootVehicle = rootVehicle;
    }

    public boolean easyAuth$wasDead() {
        return wasDead;
    }

    public void easyAuth$wasDead(boolean wasDead) {
        this.wasDead = wasDead;
    }

    public void easyAuth$canSkipAuth(boolean cantSkipAuth) {
        this.canSkipAuth = cantSkipAuth;
    }

    public String easyAuth$getIpAddress() {
        return ipAddress;
    }

    public void easyAuth$setIpAddress(Connection connection) {
        SocketAddress socketAddress = connection.getRemoteAddress();
        ipAddress = socketAddress instanceof InetSocketAddress inetSocketAddress ? InetAddresses.toAddrString(inetSocketAddress.getAddress()) : "<unknown>";
    }

    public PlayerEntryV1 easyAuth$getPlayerEntryV1() {
        return playerEntryV1;
    }

    public void easyAuth$setPlayerEntryV1(PlayerEntryV1 playerEntryV1) {
        this.playerEntryV1 = playerEntryV1;
    }

}
