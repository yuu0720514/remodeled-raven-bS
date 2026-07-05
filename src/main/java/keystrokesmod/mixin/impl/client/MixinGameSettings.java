package keystrokesmod.mixin.impl.client;

import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.combat.Autoblock;
import keystrokesmod.module.impl.player.SafeWalk;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(GameSettings.class)
public class MixinGameSettings {

    /**
     * @author Strangers
     * @reason Overwrites the original isKeyDown method, used to fix not sneaking with SafeWalk on ViaForge
     */
    @Overwrite
    public static boolean isKeyDown(KeyBinding key) {
        if (key == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) {
            return key.getKeyCode() != 0 && (key.getKeyCode() < 0 ? Mouse.isButtonDown(key.getKeyCode() + 100) : Keyboard.isKeyDown(key.getKeyCode()));
        }
        SafeWalk safewalk = ModuleManager.safeWalk;
        if (key == mc.gameSettings.keyBindSneak && safewalk != null && safewalk.isEnabled() && safewalk.sneak.isToggled() && safewalk.isSneaking) {
            return true;
        }
        Autoblock autoblock = ModuleManager.autoblock;
        if (key == mc.gameSettings.keyBindUseItem && autoblock != null && autoblock.shouldSpoofUseItemKey()) {
            return autoblock.isUseItemKeyDown();
        }
        return key.getKeyCode() != 0 && (key.getKeyCode() < 0 ? Mouse.isButtonDown(key.getKeyCode() + 100) : Keyboard.isKeyDown(key.getKeyCode()));
    }

}