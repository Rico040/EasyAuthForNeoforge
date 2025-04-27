package su.rico040.easyauth.event;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import su.rico040.easyauth.storage.PlayerEntryV1;
import su.rico040.easyauth.utils.FloodgateApiHelper;
import su.rico040.easyauth.utils.PlayerAuth;
import su.rico040.easyauth.utils.PlayersCache;

import java.net.SocketAddress;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static su.rico040.easyauth.EasyAuth.*;
import static su.rico040.easyauth.utils.EasyLogger.LogDebug;

/**
 * This class will take care of actions players try to do,
 * and cancel them if they aren't authenticated
 */
public class AuthEventHandler {

    public static long lastAcceptedPacket = 0;

    public static Pattern usernamePattern;
    /**
     * Player pre-join.
     * Returns text as a reason for disconnect or null to pass
     *
     * @param profile GameProfile of the player
     * @param manager PlayerManager
     * @return Text if player should be disconnected
     */
    public static Component checkCanPlayerJoinServer(GameProfile profile, PlayerList manager, SocketAddress socketAddress) {
        // Getting the player. By this point, the player's game profile has been authenticated so the UUID is legitimate.
        String incomingPlayerUsername = profile.getName();
        Player onlinePlayer = manager.getPlayerByName(incomingPlayerUsername);

        if ((onlinePlayer != null && !((PlayerAuth) onlinePlayer).easyAuth$canSkipAuth()) && extendedConfig.preventAnotherLocationKick) {
            // Player needs to be kicked, since there's already a player with that name
            // playing on the server

            // if joining from same IP, allow the player to join
            String string = socketAddress.toString();
            if (string.contains("/")) {
                string = string.substring(string.indexOf(47) + 1);
            }

            if (string.contains(":")) {
                string = string.substring(0, string.indexOf(58));
            }

            if (!((PlayerAuth) onlinePlayer).easyAuth$getIpAddress().equals(string)) {
                return langConfig.playerAlreadyOnline.getWithFallback(incomingPlayerUsername);
            }
        }

        // Checking if player username is valid. The pattern is generated when the config is (re)loaded.
        Matcher matcher = usernamePattern.matcher(incomingPlayerUsername);

        if (!(matcher.matches() || (technicalConfig.floodgateLoaded && extendedConfig.floodgateBypassRegex && FloodgateApiHelper.isFloodgatePlayer(profile.getId())))) {
            return langConfig.disallowedUsername.getWithFallback(extendedConfig.usernameRegexp);
        }
        // If the player name and registered name are different, kick the player if differentUsernameCase is enabled
        // Create in case of Floodgate player
        PlayerEntryV1 playerEntryV1 = PlayersCache.getFloodgate(incomingPlayerUsername);

        if (!extendedConfig.allowCaseInsensitiveUsername && !playerEntryV1.username.equals(incomingPlayerUsername)) {
            return langConfig.differentUsernameCase.getWithFallback(incomingPlayerUsername);
        }

        if (config.maxLoginTries != -1 && playerEntryV1.lastKickedDate.plusSeconds(config.resetLoginAttemptsTimeout).isAfter(ZonedDateTime.now())) {
            return langConfig.loginTriesExceeded.getWithFallback();
        }

        return null;
    }

    public static void loadPlayerData(ServerPlayer player, Connection connection) {
        PlayerAuth playerAuth = (PlayerAuth) player;

        // Create in case of Carpet player
        PlayerEntryV1 cache = PlayersCache.getCarpet(player.getScoreboardName());
        boolean update = false;
        if (cache.uuid == null) {
            cache.uuid = player.getUUID();
            update = true;
        }
        playerAuth.easyAuth$setPlayerEntryV1(cache);

        playerAuth.easyAuth$setIpAddress(connection);
        playerAuth.easyAuth$setSkipAuth();

        if (playerAuth.easyAuth$canSkipAuth()) {
            playerAuth.easyAuth$setAuthenticated(true);

            player.setInvulnerable(false);
            player.setInvisible(false);
            update = false;
        } else if (cache.lastIp.equals(playerAuth.easyAuth$getIpAddress()) && cache.lastAuthenticatedDate.plusSeconds(config.sessionTimeout).isAfter(ZonedDateTime.now())) {
            playerAuth.easyAuth$setAuthenticated(true);

            player.setInvulnerable(false);
            player.setInvisible(false);

            cache.lastAuthenticatedDate = ZonedDateTime.now();
            update = true;
        }

        if (update) {
            cache.update();
        }

        if (extendedConfig.skipAllAuthChecks) {
            playerAuth.easyAuth$setAuthenticated(true);
        }
    }

    // Player joining the server
    public static void onPlayerJoin(ServerPlayer player) {
        PlayerAuth playerAuth = (PlayerAuth) player;

        if (playerAuth.easyAuth$canSkipAuth()) {
            langConfig.onlinePlayerLogin.send(player);
            return;
        } else if (playerAuth.easyAuth$isAuthenticated()) {
            langConfig.validSession.send(player);
            return;
        } else if (extendedConfig.skipAllAuthChecks) {
            return;
        }

        // Tries to rescue player from nether portal
        if (extendedConfig.tryPortalRescue) {
            BlockPos pos = player.blockPosition();
            player.randomTeleport(pos.getX() + 0.5, player.getY(), pos.getZ() + 0.5, false);
            if (player.getInBlockState().getBlock().equals(Blocks.NETHER_PORTAL) || player.level().getBlockState(player.blockPosition().above()).getBlock().equals(Blocks.NETHER_PORTAL)) {
                // Faking portal blocks to be air
                ClientboundBlockUpdatePacket feetPacket = new ClientboundBlockUpdatePacket(pos, Blocks.AIR.defaultBlockState());
                player.connection.send(feetPacket);

                ClientboundBlockUpdatePacket headPacket = new ClientboundBlockUpdatePacket(pos.above(), Blocks.AIR.defaultBlockState());
                player.connection.send(headPacket);
            }
        }
    }

    public static void onPlayerLeave(ServerPlayer player) {
        PlayerAuth playerAuth = (PlayerAuth) player;
        if (playerAuth.easyAuth$canSkipAuth())
            return;

        if (playerAuth.easyAuth$isAuthenticated()) {
            PlayerEntryV1 playerCache = playerAuth.easyAuth$getPlayerEntryV1();
            playerCache.lastAuthenticatedDate = ZonedDateTime.now();
            playerCache.update();
        } else if (config.hidePlayerCoords) {
            ((PlayerAuth) player).easyAuth$restoreTrueLocation();

            player.setInvulnerable(false);
            player.setInvisible(false);
        }
    }

    // Player execute command
    public static InteractionResult onPlayerCommand(ServerPlayer player, String command) {
        // Getting the message to then be able to check it
        if (extendedConfig.allowCommands) {
            return InteractionResult.PASS;
        }
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (command.startsWith("login ")
                || command.startsWith("register ")
                || (extendedConfig.aliases.login && command.startsWith("l "))
                || (extendedConfig.aliases.register && command.startsWith("reg "))) {
            return InteractionResult.PASS;
        }
        if (!((PlayerAuth) player).easyAuth$isAuthenticated()) {
            for (String allowedCommand : extendedConfig.allowedCommands) {
                if (command.startsWith(allowedCommand)) {
                    LogDebug("Player " + player.getScoreboardName() + " executed command " + command + " without being authenticated.");
                    return InteractionResult.PASS;
                }
            }
            LogDebug("Player " + player.getScoreboardName() + " tried to execute command " + command + " without being authenticated.");
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    // Player chatting
    public static InteractionResult onPlayerChat(ServerPlayer player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowChat) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    // Player movement
    public static InteractionResult onPlayerMove(ServerPlayer player) {
        // Player will fall if enabled (prevent fly kick)
        boolean auth = ((PlayerAuth) player).easyAuth$isAuthenticated();
        // Otherwise, movement should be disabled
        if (!auth && !extendedConfig.allowMovement) {
            if (System.nanoTime() >= lastAcceptedPacket + extendedConfig.teleportationTimeoutMs * 1000000) {
                player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
                lastAcceptedPacket = System.nanoTime();
            }
            if (!player.isInvulnerable())
                player.setInvulnerable(extendedConfig.playerInvulnerable);
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    // Using a block (right-click function)
    public static InteractionResult onUseBlock(Player player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowBlockInteraction) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    // Breaking a block
    public static boolean onBreakBlock(Player player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowBlockBreaking) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return false;
        }
        return true;
    }

    // Using an item
    public static InteractionResultHolder<ItemStack> onUseItem(Player player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowItemUsing) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return InteractionResultHolder.fail(ItemStack.EMPTY);
        }

        return InteractionResultHolder.pass(ItemStack.EMPTY);
    }

    // Dropping an item
    public static InteractionResult onDropItem(Player player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowItemDropping) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    // Changing inventory (item moving etc.)
    public static InteractionResult onTakeItem(ServerPlayer player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowItemMoving) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return InteractionResult.FAIL;
        }

        return InteractionResult.PASS;
    }

    // Attacking an entity
    public static InteractionResult onAttackEntity(Player player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowEntityAttacking) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return InteractionResult.FAIL;
        }

        return InteractionResult.PASS;
    }

    // Interacting with entity
    public static InteractionResult onUseEntity(Player player) {
        if (!((PlayerAuth) player).easyAuth$isAuthenticated() && !extendedConfig.allowEntityInteraction) {
            ((PlayerAuth) player).easyAuth$sendAuthMessage();
            return InteractionResult.FAIL;
        }

        return InteractionResult.PASS;
    }

    public static void onPreLogin(ServerLoginPacketListenerImpl nethandler) {
        if (extendedConfig.forcedOfflineUuid && nethandler.authenticatedProfile != null) {
            nethandler.authenticatedProfile = UUIDUtil.createOfflineProfile(nethandler.authenticatedProfile.getName());
        }
    }

}
