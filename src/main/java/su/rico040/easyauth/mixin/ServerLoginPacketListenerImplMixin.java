package su.rico040.easyauth.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import su.rico040.easyauth.storage.PlayerEntryV1;
import su.rico040.easyauth.utils.PlayersCache;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static su.rico040.easyauth.EasyAuth.extendedConfig;
import static su.rico040.easyauth.utils.EasyLogger.LogDebug;
import static su.rico040.easyauth.utils.EasyLogger.LogError;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {
    @Shadow
    public GameProfile authenticatedProfile;

    @Shadow
    private ServerLoginPacketListenerImpl.State state;

    @Final
    @Shadow
    MinecraftServer server;

    @Unique
    private static final Pattern pattern = Pattern.compile("^[a-zA-Z0-9_]{1,16}$");

    /**
     * Checks whether the player has purchased an account.
     * If so, server is presented as online, and continues as in normal-online mode.
     * Otherwise, player is marked as ready to be accepted into the game.
     *
     * @param packet
     * @param ci
     */
    @Inject(
            method = "handleHello(Lnet/minecraft/network/protocol/login/ServerboundHelloPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"
            ),
            cancellable = true
    )
    private void checkPremium(ServerboundHelloPacket packet, CallbackInfo ci) {
        String username = packet.name();

        PlayerEntryV1 playerData = PlayersCache.getOrRegister(username);

        if (server.usesAuthentication()) {
            try {
                Matcher matcher = pattern.matcher(username);

                if (playerData.onlineAccount == PlayerEntryV1.OnlineAccount.FALSE) {
                    LogDebug("Player " + username + " is forced to be offline");
                    state = ServerLoginPacketListenerImpl.State.VERIFYING;

                    this.authenticatedProfile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(packet.name()), packet.name());
                    ci.cancel();
                    return;
                }
                if (playerData.onlineAccount == PlayerEntryV1.OnlineAccount.TRUE) {
                    LogDebug("Player " + username + " is cached as online player. Authentication continues as vanilla");
                    return;
                }
                if (!matcher.matches()) {
                    // Player definitely doesn't have a mojang account
                    LogDebug("Player " + username + " doesn't have a valid username for Mojang account");
                    state = ServerLoginPacketListenerImpl.State.VERIFYING;
                    playerData.onlineAccount = PlayerEntryV1.OnlineAccount.FALSE;
                    playerData.update();

                    this.authenticatedProfile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(packet.name()), packet.name());
                    ci.cancel();
                } else {
                    // Checking account status from API
                    LogDebug("Checking player " + username + " for premium status");
                    HttpsURLConnection httpsURLConnection = (HttpsURLConnection) URI.create(extendedConfig.mojangApiSettings.url + username).toURL().openConnection();
                    httpsURLConnection.setRequestMethod("GET");
                    httpsURLConnection.setConnectTimeout(extendedConfig.mojangApiSettings.connectionTimeout);
                    httpsURLConnection.setReadTimeout(extendedConfig.mojangApiSettings.readTimeout);

                    int response = httpsURLConnection.getResponseCode();
                    if (response == HttpURLConnection.HTTP_OK) {
                        // Player has a Mojang account
                        httpsURLConnection.disconnect();
                        LogDebug("Player " + username + " has a Mojang account");

                        // Caches the request
                        playerData.onlineAccount = PlayerEntryV1.OnlineAccount.TRUE;
                        playerData.update();
                        // Authentication continues in original method
                    } else if (response == HttpURLConnection.HTTP_NO_CONTENT || response == HttpURLConnection.HTTP_NOT_FOUND) {
                        // Player doesn't have a Mojang account
                        httpsURLConnection.disconnect();
                        LogDebug("Player " + username + " doesn't have a Mojang account");
                        state = ServerLoginPacketListenerImpl.State.VERIFYING;

                        playerData.onlineAccount = PlayerEntryV1.OnlineAccount.FALSE;
                        playerData.update();

                        this.authenticatedProfile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(packet.name()), packet.name());
                        ci.cancel();
                    }
                }
            } catch (IOException e) {
                LogError("checkPremium error", e);
            }
        }
    }
}
