package su.rico040.easyauth;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerNegotiationEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import su.rico040.easyauth.commands.*;
import su.rico040.easyauth.config.*;
import su.rico040.easyauth.event.AuthEventHandler;
import su.rico040.easyauth.storage.database.*;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static su.rico040.easyauth.EasyAuth.MODID;
import static su.rico040.easyauth.config.ConfigMigration.migrateFromV1;
import static su.rico040.easyauth.utils.EasyLogger.*;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(MODID)
@EventBusSubscriber(modid = MODID)
public class EasyAuth {
    public static final String MODID = "easyauth";

    public static MinecraftServer SERVER;

    public static DbApi DB = null;

    public static final ExecutorService THREADPOOL = Executors.newCachedThreadPool();

    // Getting game directory
    public static Path gameDirectory;

    // Server properties
    public static final Properties serverProp = new Properties();

    public static MainConfigV1 config;
    public static ExtendedConfigV1 extendedConfig;
    public static LangConfigV1 langConfig;
    public static TechnicalConfigV1 technicalConfig;
    public static StorageConfigV1 storageConfig;

    public EasyAuth(IEventBus modEventBus, ModContainer modContainer) {
        gameDirectory = FMLPaths.GAMEDIR.get();
        LogInfo("EasyAuth mod by Rico040, NikitaCartes");

        File file = new File(gameDirectory + "/config/EasyAuth");
        if (!file.exists()) {
            if (!file.mkdirs()) {
                throw new RuntimeException("[EasyAuth] Error creating directory for configs");
            }
            ConfigMigration.migrateFromV0();
        }

        loadConfigs();

        if (EasyAuth.storageConfig.databaseType.equalsIgnoreCase("mysql")) {
            DB = new MySQL(EasyAuth.storageConfig);
        } else if (EasyAuth.storageConfig.databaseType.equalsIgnoreCase("mongodb")) {
            DB = new MongoDB(EasyAuth.storageConfig);
        } else {
            DB = new SQLite(EasyAuth.storageConfig);
        }
        try {
            DB.connect();
        } catch (DBApiException e) {
            LogError("Error while set up database connection", e);
        }
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event){
        RegisterCommand.registerCommand(event.getDispatcher());
        LoginCommand.registerCommand(event.getDispatcher());
        LogoutCommand.registerCommand(event.getDispatcher());
        AuthCommand.registerCommand(event.getDispatcher());
        AccountCommand.registerCommand(event.getDispatcher());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPreLogin(PlayerNegotiationEvent event) {
        AuthEventHandler.onPreLogin((ServerLoginPacketListenerImpl) event.getConnection().getPacketListener());
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!AuthEventHandler.onBreakBlock(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (AuthEventHandler.onUseBlock(event.getEntity()) != InteractionResult.PASS) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onUseItem(PlayerInteractEvent.RightClickItem event) {
        if (AuthEventHandler.onUseItem(event.getEntity()).getResult() != InteractionResult.PASS) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (AuthEventHandler.onAttackEntity(event.getEntity()) != InteractionResult.PASS) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onResourceReload(AddReloadListenerEvent event) {
        AuthCommand.reloadConfig(EasyAuth.SERVER);
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (AuthEventHandler.onUseEntity(event.getEntity()) != InteractionResult.PASS) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onServerStart(ServerStartedEvent event) {
        try {
            serverProp.load(new FileReader(gameDirectory + "/server.properties"));
            if (Boolean.parseBoolean(serverProp.getProperty("enforce-secure-profile"))) {
                LogWarn("Disable enforce-secure-profile to allow offline players to join the server");
                LogWarn("For more info, see https://github.com/NikitaCartes/EasyAuth/issues/68");
            }
        } catch (IOException e) {
            LogError("Error while reading server properties: ", e);
        }
        SERVER = event.getServer();
        if (DB.isClosed()) {
            LogError("Couldn't connect to database. Stopping server");
            event.getServer().halt(false);
        }
    }

    @SubscribeEvent
    public static void onServerStop(ServerStoppedEvent event) {
        LogInfo("Shutting down EasyAuth.");

        // Closing threads
        try {
            THREADPOOL.shutdownNow();
            if (!THREADPOOL.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                Thread.currentThread().interrupt();
            }
        } catch (InterruptedException e) {
            LogError("Error on stop", e);
            THREADPOOL.shutdownNow();
        }

        // Closing DbApi connection
        DB.close();
    }

    public static void loadConfigs() {
        VersionConfig version = VersionConfig.load();

        switch (version.configVersion) {
            case -1: {
                EasyAuth.config = MainConfigV1.load();
                EasyAuth.config.save();

                EasyAuth.technicalConfig = TechnicalConfigV1.load();
                EasyAuth.technicalConfig.save();

                EasyAuth.langConfig = LangConfigV1.load();
                EasyAuth.langConfig.save();

                EasyAuth.extendedConfig = ExtendedConfigV1.load();
                EasyAuth.extendedConfig.save();

                EasyAuth.storageConfig = StorageConfigV1.load();
                EasyAuth.storageConfig.save();

                break;
            }
            case 1: {
                EasyAuth.config = MainConfigV1.load();
                EasyAuth.technicalConfig = TechnicalConfigV1.load();
                EasyAuth.langConfig = LangConfigV1.load();
                EasyAuth.extendedConfig = ExtendedConfigV1.load();
                EasyAuth.storageConfig = StorageConfigV1.load();
                migrateFromV1();
                break;
            }
            case 2: {
                EasyAuth.config = MainConfigV1.load();
                EasyAuth.technicalConfig = TechnicalConfigV1.load();
                EasyAuth.langConfig = LangConfigV1.load();
                EasyAuth.extendedConfig = ExtendedConfigV1.load();
                EasyAuth.storageConfig = StorageConfigV1.load();
                break;
            }
            default: {
                LogError("Unknown config version: " + version.configVersion + "\n Using last known version");
                EasyAuth.config = MainConfigV1.load();
                EasyAuth.technicalConfig = TechnicalConfigV1.load();
                EasyAuth.langConfig = LangConfigV1.load();
                EasyAuth.extendedConfig = ExtendedConfigV1.load();
                EasyAuth.storageConfig = StorageConfigV1.load();
                break;
            }
        }
        AuthEventHandler.usernamePattern = Pattern.compile(EasyAuth.extendedConfig.usernameRegexp);
    }

    public static void saveConfigs() {
        EasyAuth.config.save();
        EasyAuth.technicalConfig.save();
        EasyAuth.langConfig.save();
        EasyAuth.extendedConfig.save();
        EasyAuth.storageConfig.save();
    }
    public static ZonedDateTime getUnixZero() {
        return ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    }
}
