package su.rico040.easyauth.mixin;

import net.minecraft.core.UUIDUtil;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import su.rico040.easyauth.utils.PlayerAuth;

import java.nio.file.Path;

import static su.rico040.easyauth.EasyAuth.extendedConfig;
import static su.rico040.easyauth.EasyAuth.serverProp;

@Mixin(PlayerAdvancements.class)
public class PlayerAdvancementsMixin {

    @Mutable
    @Shadow
    @Final
    private Path playerSavePath;

    @Shadow
    private ServerPlayer player;

    @Inject(method = "load(Lnet/minecraft/server/ServerAdvancementManager;)V", at = @At("HEAD"))
    private void startMigratingOfflineAdvancements(ServerAdvancementManager advancementLoader, CallbackInfo ci) {
        if (Boolean.parseBoolean(serverProp.getProperty("online-mode")) && !extendedConfig.forcedOfflineUuid && ((PlayerAuth) this.player).easyAuth$isUsingMojangAccount() && !this.playerSavePath.toFile().isFile()) {
            // Migrate
            String playername = player.getGameProfile().getName();
            this.playerSavePath = this.playerSavePath.getParent().resolve(UUIDUtil.createOfflinePlayerUUID(playername) + ".json");
        }
    }

    @Inject(method = "load(Lnet/minecraft/server/ServerAdvancementManager;)V", at = @At("TAIL"))
    private void endMigratingOfflineAdvancements(ServerAdvancementManager advancementLoader, CallbackInfo ci) {
        if (Boolean.parseBoolean(serverProp.getProperty("online-mode")) && !extendedConfig.forcedOfflineUuid && ((PlayerAuth) this.player).easyAuth$isUsingMojangAccount()) {
            // Changes the file name to use online UUID
            this.playerSavePath = this.playerSavePath.getParent().resolve(player.getUUID() + ".json");
        }
    }
}
