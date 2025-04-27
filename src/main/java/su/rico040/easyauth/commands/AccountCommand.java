package su.rico040.easyauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import su.rico040.easyauth.storage.PlayerEntryV1;
import su.rico040.easyauth.utils.AuthHelper;
import su.rico040.easyauth.utils.PlayerAuth;

import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.BoolArgumentType.getBool;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.Commands.argument;
import static su.rico040.easyauth.EasyAuth.*;

public class AccountCommand {

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("account")
                .requires(Permissions.require("easyauth.commands.account.root", true))
                .then(literal("unregister")
                        .requires(Permissions.require("easyauth.commands.account.unregister", true))
                        .executes(ctx -> {
                            langConfig.enterPassword.send(ctx.getSource());
                            return 1;
                        })
                        .then(argument("password", string())
                                .executes(ctx -> unregister(
                                        ctx.getSource(),
                                                getString(ctx, "password")
                                        )
                                )
                        )
                )
                .then(literal("changePassword")
                        .requires(Permissions.require("easyauth.commands.account.changePassword", true))
                        .then(argument("old password", string())
                                .executes(ctx -> {
                                    langConfig.enterNewPassword.send(ctx.getSource());
                                    return 1;
                                })
                                .then(argument("new password", string())
                                        .executes(ctx -> changePassword(
                                                ctx.getSource(),
                                                        getString(ctx, "old password"),
                                                        getString(ctx, "new password")
                                                )
                                        )
                                )
                        )
                )
                .then(literal("online")
                        .requires(Permissions.require("easyauth.commands.account.online", true))
                        .then(argument("password", string())
                                .executes(ctx -> markAsOnline(
                                        (CommandSourceStack) ctx.getSource(),
                                                getString(ctx, "password"),
                                                false
                                        )
                                )
                                .then(argument("confirm", bool())
                                        .executes(ctx -> markAsOnline(
                                                (CommandSourceStack) ctx.getSource(),
                                                        getString(ctx, "password"),
                                                        getBool(ctx, "confirm")
                                                )
                                        )
                                )
                        )
                )
        );
    }

    // Method called for checking the password and then removing user's account from db
    private static int unregister(CommandSourceStack source, String pass) throws CommandSyntaxException {
        // Getting the player who send the command
        ServerPlayer player = source.getPlayerOrException();
        PlayerAuth playerAuth = (PlayerAuth) player;

        if (config.enableGlobalPassword && !config.singleUseGlobalPassword) {
            langConfig.cannotUnregister.send(source);
            return 0;
        }

        if (playerAuth.easyAuth$canSkipAuth()) {
            langConfig.cannotUnregister.send(source);
            return 0;
        }

        if (!playerAuth.easyAuth$isAuthenticated()) {
            langConfig.loginRequired.send(source);
            return 0;
        }

        // Different thread to avoid lag spikes
        THREADPOOL.submit(() -> {
            String username = player.getScoreboardName();
            if (AuthHelper.checkPassword(playerAuth, pass.toCharArray()) == AuthHelper.PasswordOptions.CORRECT) {
                DB.deleteUserData(username);
                langConfig.accountDeleted.send(source);
                playerAuth.easyAuth$setAuthenticated(false);
                playerAuth.easyAuth$setPlayerEntryV1(new PlayerEntryV1(username));
                player.connection.disconnect(langConfig.accountDeleted.get());
                return;
            }
            langConfig.wrongPassword.send(source);
        });
        return 0;
    }

    // Method called for checking the password and then changing it
    private static int changePassword(CommandSourceStack source, String oldPass, String newPass) throws CommandSyntaxException {
        // Getting the player who send the command
        ServerPlayer player = source.getPlayerOrException();
        PlayerAuth playerAuth = (PlayerAuth) player;

        if (config.enableGlobalPassword && !config.singleUseGlobalPassword) {
            langConfig.cannotChangePassword.send(source);
            return 0;
        }
        if (newPass.length() < extendedConfig.minPasswordLength) {
            langConfig.minPasswordChars.send(source, extendedConfig.minPasswordLength);
            return 0;
        } else if (newPass.length() > extendedConfig.maxPasswordLength && extendedConfig.maxPasswordLength != -1) {
            langConfig.maxPasswordChars.send(source, extendedConfig.maxPasswordLength);
            return 0;
        }
        // Different thread to avoid lag spikes
        THREADPOOL.submit(() -> {
            if (AuthHelper.checkPassword(playerAuth, oldPass.toCharArray()) == AuthHelper.PasswordOptions.CORRECT) {
                // Changing password

                PlayerEntryV1 playerEntry = playerAuth.easyAuth$getPlayerEntryV1();
                playerEntry.password = AuthHelper.hashPassword(newPass.toCharArray());
                playerEntry.update();

                langConfig.passwordUpdated.send(source);
            } else {
                langConfig.wrongPassword.send(source);
            }
        });
        return 0;
    }

    /**
     * Set player as player with online account
     *
     * @param source   executioner of the command
     * @param password password of the player
     * @return 0
     */
    private static int markAsOnline(CommandSourceStack source, String password) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlayerAuth playerAuth = (PlayerAuth) player;

        THREADPOOL.submit(() -> {
            if (AuthHelper.checkPassword(playerAuth, password.toCharArray()) == AuthHelper.PasswordOptions.CORRECT) {

                PlayerEntryV1 playerEntry = playerAuth.easyAuth$getPlayerEntryV1();
                playerEntry.onlineAccount = PlayerEntryV1.OnlineAccount.TRUE;
                playerEntry.update();

                langConfig.selfMarkAsOnline.send(source);
            } else {
                langConfig.wrongPassword.send(source);
            }
        });

        return 1;
    }

    private static int markAsOnline(CommandSourceStack source, String password, boolean confirm) throws CommandSyntaxException {
        if (!confirm) {
            langConfig.selfMarkAsOnlineWarning.send(source);
            return 0;
        }
        return markAsOnline(source, password);
    }
}
