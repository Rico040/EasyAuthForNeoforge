package su.rico040.easyauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import su.rico040.easyauth.storage.PlayerEntryV1;
import su.rico040.easyauth.utils.AuthHelper;
import su.rico040.easyauth.utils.PlayerAuth;

import java.time.ZonedDateTime;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.Commands.argument;
import static su.rico040.easyauth.EasyAuth.*;
import static su.rico040.easyauth.utils.EasyLogger.LogDebug;

public class LoginCommand {

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> node = registerLogin(dispatcher); // Registering the "/login" command
        if (extendedConfig.aliases.login) {
            dispatcher.register(literal("l")
                    .requires(Permissions.require("easyauth.commands.login", true))
                    .redirect(node));
        }
    }

    public static LiteralCommandNode<CommandSourceStack> registerLogin(CommandDispatcher<CommandSourceStack> dispatcher) {
        return dispatcher.register(literal("login")
                .requires(Permissions.require("easyauth.commands.login", true))
                .then(argument("password", string())
                        .executes(ctx -> login(ctx.getSource(), getString(ctx, "password")) // Tries to authenticate user
                        ))
                .executes(ctx -> {
                    langConfig.enterPassword.send(ctx.getSource());
                    return 0;
                }));
    }

    // Method called for checking the password
    private static int login(CommandSourceStack source, String pass) throws CommandSyntaxException {
        // Getting the player who send the command
        ServerPlayer player = source.getPlayerOrException();
        PlayerAuth playerAuth = (PlayerAuth) player;

        LogDebug("Player " + player.getScoreboard() + " is trying to login");
        if (playerAuth.easyAuth$isAuthenticated()) {
            LogDebug("Player " + player.getScoreboard() + " is already authenticated");
            langConfig.alreadyAuthenticated.send(source);
            return 0;
        }
        PlayerEntryV1 playerData = playerAuth.easyAuth$getPlayerEntryV1();

        AuthHelper.PasswordOptions passwordResult = AuthHelper.checkPassword(playerData, pass.toCharArray());

        if (passwordResult == AuthHelper.PasswordOptions.CORRECT) {
            LogDebug("Player " + player.getScoreboard() + " provide correct password");
            if (playerData.lastKickedDate.plusSeconds(config.resetLoginAttemptsTimeout).isAfter(ZonedDateTime.now())) {
                LogDebug("Player " + player.getScoreboard() + " will be kicked due to kick timeout");
                player.connection.disconnect(langConfig.loginTriesExceeded.get());
                return 0;
            }
            langConfig.successfullyAuthenticated.send(source);
            playerAuth.easyAuth$setAuthenticated(true);
            playerAuth.easyAuth$restoreTrueLocation();
            playerData.lastAuthenticatedDate = ZonedDateTime.now();
            playerData.loginTries = 0;
            playerData.lastIp = playerAuth.easyAuth$getIpAddress();
            playerData.update();
            // player.getServer().getPlayerManager().sendToAll(new PlayerListS2CPacket(PlayerListS2CPacket.Action.ADD_PLAYER, player));
            return 0;
        } else if (passwordResult == AuthHelper.PasswordOptions.NOT_REGISTERED) {
            LogDebug("Player " + player.getScoreboard() + " is not registered");
            if (config.singleUseGlobalPassword) {
                langConfig.registerRequiredWithGlobalPassword.send(source);
                return 0;
            }
            langConfig.registerRequired.send(source);
            return 0;
        }
        playerData.loginTries++;
        if (playerData.loginTries >= config.maxLoginTries && config.maxLoginTries != -1) { // Player exceeded maxLoginTries
            LogDebug("Player " + player.getScoreboard() + " exceeded max login tries");
            // Send the player a different error message if the max login tries is 1.
            playerData.lastKickedDate = ZonedDateTime.now();
            playerData.loginTries = 0;
            playerData.update();
            if (config.maxLoginTries == 1) {
                player.connection.disconnect(langConfig.wrongPassword.get());
            } else {
                player.connection.disconnect(langConfig.loginTriesExceeded.get());
            }
            return 0;
        }
        LogDebug("Player " + player.getScoreboard() + " provided wrong password");
        // Sending wrong pass message
        langConfig.wrongPassword.send(source);
        return 0;
    }
}
