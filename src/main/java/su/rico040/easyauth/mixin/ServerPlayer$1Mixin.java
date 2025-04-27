package su.rico040.easyauth.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import su.rico040.easyauth.utils.PlayerAuth;

import static su.rico040.easyauth.EasyAuth.extendedConfig;

@Mixin(targets = "net.minecraft.server.level.ServerPlayer$1")
public class ServerPlayer$1Mixin {

    @Final
    @Shadow
    ServerPlayer this$0;

    @Inject(method = "sendInitialData(Lnet/minecraft/world/inventory/AbstractContainerMenu;Lnet/minecraft/core/NonNullList;Lnet/minecraft/world/item/ItemStack;[I)V",
            at = @At("HEAD"),
            cancellable = true)
    private void updateStateMixin(CallbackInfo ci) {
        if (extendedConfig.hideInventory && !((PlayerAuth) this$0).easyAuth$isAuthenticated()) {
            ci.cancel();
        }
    }
}
