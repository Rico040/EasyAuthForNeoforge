package su.rico040.easyauth.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import su.rico040.easyauth.EasyAuth;
import su.rico040.easyauth.storage.PlayerEntryV1;
import su.rico040.easyauth.storage.database.DBApiException;
import su.rico040.easyauth.utils.AuthHelper;
import su.rico040.easyauth.utils.PlayerAuth;

import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.BoolArgumentType.getBool;
import static com.mojang.brigadier.arguments.StringArgumentType.*;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.Commands.argument;
import static su.rico040.easyauth.EasyAuth.*;
import static su.rico040.easyauth.utils.EasyLogger.LogError;

public class AuthCommand {
    /**
     * Registers the "/auth" command
     *
     * @param dispatcher
     */
    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("auth")
                .requires(Permissions.require("easyauth.commands.auth.root", 3))
                .then(literal("reload")
                        .requires(Permissions.require("easyauth.commands.auth.reload", 3))
                        .executes(ctx -> reloadConfig(ctx.getSource()))
                )
                .then(literal("setGlobalPassword")
                        .requires(Permissions.require("easyauth.commands.auth.setGlobalPassword", 4))
                        .then(argument("password", string())
                                .executes(ctx -> setGlobalPassword(
                                        ctx.getSource(),
                                        getString(ctx, "password"),
                                        false
                                ))
                                .then(argument("singleUse", bool())
                                        .executes(ctx -> setGlobalPassword(
                                                ctx.getSource(),
                                                getString(ctx, "password"),
                                                getBool(ctx, "singleUse")
                                        ))
                                )
                        )
                )
                .then(literal("setSpawn")
                        .requires(Permissions.require("easyauth.commands.auth.setSpawn", 3))
                        .executes(ctx -> setSpawn(
                                ctx.getSource(),
                                ctx.getSource().getEntityOrException().getCommandSenderWorld().dimension().registry(),
                                ctx.getSource().getEntityOrException().getX(),
                                ctx.getSource().getEntityOrException().getY(),
                                ctx.getSource().getEntityOrException().getZ(),
                                ctx.getSource().getEntityOrException().getYRot(),
                                ctx.getSource().getEntityOrException().getXRot()
                        ))
                        .then(argument("dimension", DimensionArgument.dimension())
                                .then(argument("position", BlockPosArgument.blockPos())
                                        .then(argument("angle", RotationArgument.rotation())
                                                .executes(ctx -> setSpawn(
                                                                ctx.getSource(),
                                                                DimensionArgument.getDimension(ctx, "dimension").dimension().registry(),
                                                                BlockPosArgument.getLoadedBlockPos(ctx, "position").getX(),
                                                                // +1 to not spawn player in ground
                                                                BlockPosArgument.getLoadedBlockPos(ctx, "position").getY() + 1,
                                                                BlockPosArgument.getLoadedBlockPos(ctx, "position").getZ(),
                                                                RotationArgument.getRotation(ctx, "angle").getRotation(ctx.getSource()).y,
                                                                RotationArgument.getRotation(ctx, "angle").getRotation(ctx.getSource()).x
                                                        )
                                                )
                                        )
                                )
                        )
                )
                .then(literal("remove")
                        .requires(Permissions.require("easyauth.commands.auth.remove", 3))
                        .then(argument("username", word())
                                .executes(ctx -> removeAccount(
                                        ctx.getSource(),
                                        getString(ctx, "username")
                                ))
                        )
                )
                .then(literal("register")
                        .requires(Permissions.require("easyauth.commands.auth.register", 3))
                        .then(argument("username", word())
                                .then(argument("password", string())
                                        .executes(ctx -> registerUser(
                                                ctx.getSource(),
                                                getString(ctx, "username"),
                                                getString(ctx, "password")
                                        ))
                                )
                        )
                )
                .then(literal("update")
                        .requires(Permissions.require("easyauth.commands.auth.update", 3))
                        .then(argument("username", word())
                                .then(argument("password", string())
                                        .executes(ctx -> updatePassword(
                                                ctx.getSource(),
                                                getString(ctx, "username"),
                                                getString(ctx, "password")
                                        ))
                                )
                        )
                )
                .then(literal("list")
                        .requires(Permissions.require("easyauth.commands.auth.list", 3))
                        .executes(ctx -> getRegisteredPlayers(ctx.getSource()))
                )
                .then(literal("markAsOffline")
                        .requires(Permissions.require("easyauth.commands.auth.markAsOffline", 3))
                        .then(argument("username", word())
                                .executes(ctx -> markAsOffline(
                                        ctx.getSource(),
                                        getString(ctx, "username")
                                ))
                        )
                )
                .then(literal("markAsOnline")
                        .requires(Permissions.require("easyauth.commands.auth.markAsOnline", 3))
                        .then(argument("username", word())
                                .executes(ctx -> markAsOnline(
                                        ctx.getSource(),
                                        getString(ctx, "username")
                                ))
                        )
                )
                .then(literal("getPlayerInfo")
                        .requires(Permissions.require("easyauth.commands.auth.getPlayerInfo", 3))
                        .then(argument("username", word())
                                .executes(ctx -> getPlayerInfo(
                                        ctx.getSource(),
                                        getString(ctx, "username")
                                ))
                        )
                )
        );
    }

    /**
     * Reloads the config file.
     *
     * @param sender executioner of the command
     * @return 0
     */
    public static int reloadConfig(CommandSourceStack sender) {
        DB.close();
        EasyAuth.loadConfigs();

        try {
            DB.connect();
        } catch (DBApiException e) {
            LogError("onInitialize error: ", e);
        }

        langConfig.configurationReloaded.send(sender);

        return Command.SINGLE_SUCCESS;
    }

    public static void reloadConfig(MinecraftServer sender) {
        DB.close();
        EasyAuth.loadConfigs();

        try {
            DB.connect();
        } catch (DBApiException e) {
            LogError("onInitialize error: ", e);
        }

        langConfig.configurationReloaded.send(sender);
    }

    /**
     * Sets global password.
     *
     * @param source   executioner of the command
     * @param password password that will be set
     * @param singleUse whether the global password is single-use
     * @return 0
     */
    private static int setGlobalPassword(CommandSourceStack source, String password, boolean singleUse) {
        // Different thread to avoid lag spikes
        THREADPOOL.submit(() -> {
            // Writing the global pass to config
            technicalConfig.globalPassword = AuthHelper.hashPassword(password.toCharArray());
            config.enableGlobalPassword = true;
            config.singleUseGlobalPassword = singleUse;
            technicalConfig.save();
            config.save();
        });

        langConfig.globalPasswordSet.send(source);
        return 1;
    }

    /**
     * Sets {@link su.rico040.easyauth.config.deprecated.AuthConfig.MainConfig.WorldSpawn global spawn}.
     *
     * @param source executioner of the command
     * @param world  world id of global spawn
     * @param x      x coordinate of the global spawn
     * @param y      y coordinate of the global spawn
     * @param z      z coordinate of the global spawn
     * @param yaw    player yaw (y rotation)
     * @param pitch  player pitch (x rotation)
     * @return 0
     */
    private static int setSpawn(CommandSourceStack source, ResourceLocation world, double x, double y, double z, float yaw, float pitch) {
        // Setting config values and saving
        // Different thread to avoid lag spikes
        THREADPOOL.submit(() -> {
            config.worldSpawn.dimension = String.valueOf(world);
            config.worldSpawn.x = x;
            config.worldSpawn.y = y;
            config.worldSpawn.z = z;
            config.worldSpawn.yaw = yaw;
            config.worldSpawn.pitch = pitch;
            config.hidePlayerCoords = true;
            config.save();
        });

        langConfig.worldSpawnSet.send(source);
        return 1;
    }

    /**
     * Deletes (unregisters) player's account.
     *
     * @param source   executioner of the command
     * @param username username of the player to delete account for
     * @return 0
     */
    private static int removeAccount(CommandSourceStack source, String username) {
        THREADPOOL.submit(() -> {
            DB.deleteUserData(username);
        });

        ServerPlayer playerEntity = source.getServer().getPlayerList().getPlayerByName(username);
        if (playerEntity != null) {
            ((PlayerAuth) playerEntity).easyAuth$setPlayerEntryV1(new PlayerEntryV1(username));
            playerEntity.connection.disconnect(langConfig.userdataDeleted.get());
        }

        langConfig.userdataDeleted.send(source);
        return 1; // Success
    }

    /**
     * Creates account for player.
     *
     * @param source   executioner of the command
     * @param username username of the player to create account for
     * @param password new password for the player account
     * @return 0
     */
    private static int registerUser(CommandSourceStack source, String username, String password) {
        THREADPOOL.submit(() -> {
            PlayerEntryV1 playerData = DB.getUserDataOrCreate(username);
            playerData.password = AuthHelper.hashPassword(password.toCharArray());
            playerData.registrationDate = ZonedDateTime.now();
            playerData.update();

            langConfig.userdataUpdated.send(source);
        });
        return 0;
    }

    /**
     * Force-updates the player's password.
     *
     * @param source   executioner of the command
     * @param username username of the player to update data for
     * @param password new password for the player
     * @return 0
     */
    private static int updatePassword(CommandSourceStack source, String username, String password) {
        THREADPOOL.submit(() -> {
            PlayerEntryV1 playerData = DB.getUserData(username);
            if (playerData == null || playerData.password.isEmpty()) {
                langConfig.userNotRegistered.send(source);
                return;
            }
            playerData.password = AuthHelper.hashPassword(password.toCharArray());
            playerData.update();
            langConfig.userdataUpdated.send(source);
        });
        return 0;
    }

    /**
     * List of registered username
     *
     * @param source executioner of the command
     * @return 0
     */
    public static int getRegisteredPlayers(CommandSourceStack source) {
        THREADPOOL.submit(() -> {
            if (langConfig.registeredPlayers.enabled) {
                AtomicInteger i = new AtomicInteger();
                MutableComponent message = langConfig.registeredPlayers.get();
                DB.getAllData().forEach((username, playerData) -> {
                    if (playerData == null || playerData.password == null) {
                        return;
                    }
                    i.getAndIncrement();
                    message.append(Component.translatable(username)
                                    .setStyle(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, username)))
                                    .withStyle(ChatFormatting.YELLOW))
                            .append(", ");
                });
                source.sendSystemMessage(message);
            }
        });
        return 1;
    }

    /**
     * Set player as player with offline account
     *
     * @param source   executioner of the command
     * @param username player to add in list
     * @return 0
     */
    private static int markAsOffline(CommandSourceStack source, String username) {
        THREADPOOL.submit(() -> {
            PlayerEntryV1 entry = DB.getUserDataOrCreate(username);
            entry.onlineAccount = PlayerEntryV1.OnlineAccount.FALSE;
            entry.update();
        });

        langConfig.markAsOffline.send(source, username);
        return 1;
    }

    /**
     * Set player as player with online account
     *
     * @param source   executioner of the command
     * @param username player to add in list
     * @return 0
     */
    private static int markAsOnline(CommandSourceStack source, String username) {
        THREADPOOL.submit(() -> {
            PlayerEntryV1 entry = DB.getUserDataOrCreate(username);
            entry.onlineAccount = PlayerEntryV1.OnlineAccount.TRUE;
            entry.update();
        });

        langConfig.markAsOnline.send(source, username);
        return 1;
    }

    /**
     * Retrieves information about a player from the database.
     *
     * @param source   executioner of the command
     * @param username username of the player to get information for
     * @return 0
     */
    private static int getPlayerInfo(CommandSourceStack source, String username) {
        THREADPOOL.submit(() -> {
            PlayerEntryV1 playerData = DB.getUserData(username);
            if (playerData == null) {
                langConfig.userNotRegistered.send(source);
                return;
            }
            // Send player information to the source
            source.sendSystemMessage(Component.literal("Player Info: " + playerData.toJson()));
        });
        return 1;
    }

}
