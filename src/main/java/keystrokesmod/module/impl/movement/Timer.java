package keystrokesmod.module.impl.movement;

import keystrokesmod.Raven;
import keystrokesmod.clickgui.ClickGui;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.keystroke.KeyStrokeConfigGui;
import keystrokesmod.mixin.impl.accessor.IAccessorMinecraft;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.Utils;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class Timer
extends Module {
    private SliderSetting speed = new SliderSetting("Speed", 1.0, 0.0, 2.0, 0.01);

    public Timer() {
        super("Timer", Module.category.movement);
        this.registerSetting(this.speed);
    }

    @Override
    public String getInfo() {
        return String.format("%.2f", this.speed.getInput());
    }

    public float getConfiguredSpeed() {
        return (float)this.speed.getInput();
    }

    @Override
    public void onEnable() {
        this.applyGameplayTimer();
    }

    @Override
    public void onKeyBind() {
        if (this.shouldBlockKeybindToggle()) {
            this.syncKeyBindState();
            return;
        }
        super.onKeyBind();
    }

    @Override
    public void onDisable() {
        Utils.resetTimer();
    }

    public boolean shouldBlockKeybindToggle() {
        if (Timer.isTypingScreenOpen()) {
            return true;
        }
        if (ClickGui.shouldGuardModuleToggle()) {
            return true;
        }
        if (Raven.isGuiOpenKeyDown()) {
            return true;
        }
        return Timer.mc.currentScreen instanceof ClickGui || Timer.mc.currentScreen instanceof KeyStrokeConfigGui;
    }

    private static boolean isTypingScreenOpen() {
        GuiScreen screen = Timer.mc.currentScreen;
        if (screen == null) {
            return false;
        }
        return screen instanceof GuiChat;
    }

    public void reapplyGameplayTimer() {
        if (!this.isEnabled() || !Utils.nullCheck()) {
            return;
        }
        float configuredSpeed = this.getConfiguredSpeed();
        if (configuredSpeed > 0.0f) {
            ((IAccessorMinecraft)Timer.mc).getTimer().timerSpeed = configuredSpeed;
        } else {
            Utils.resetTimer();
        }
    }

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public void onPreUpdate(PreUpdateEvent event) {
        this.applyGameplayTimer();
    }

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        this.applyGameplayTimer();
    }

    private void applyGameplayTimer() {
        if (!this.isEnabled() || !Utils.nullCheck()) {
            return;
        }
        if (Timer.shouldPauseGameplayTimer()) {
            Utils.resetTimer();
            return;
        }
        float configuredSpeed = this.getConfiguredSpeed();
        if (configuredSpeed > 0.0f) {
            ((IAccessorMinecraft)Timer.mc).getTimer().timerSpeed = configuredSpeed;
        } else {
            Utils.resetTimer();
        }
    }

    private static boolean shouldPauseGameplayTimer() {
        return Timer.mc.currentScreen instanceof ClickGui || Timer.mc.currentScreen instanceof KeyStrokeConfigGui;
    }

    public static boolean isGameplayActive() {
        Timer timer = ModuleManager.timer;
        if (timer == null || !timer.isEnabled()) {
            return false;
        }
        if (!Utils.nullCheck()) {
            return false;
        }
        return !Timer.shouldPauseGameplayTimer();
    }

    public static int consumeExtraLocalUpdatesForBaseTick() {
        return 0;
    }

    public static boolean shouldSkipBaseLocalUpdate() {
        if (!Timer.isGameplayActive()) {
            return false;
        }
        return ModuleManager.timer.speed.getInput() <= 0.0;
    }
}

