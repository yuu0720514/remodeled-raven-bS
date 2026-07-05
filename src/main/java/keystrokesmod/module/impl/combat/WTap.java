package keystrokesmod.module.impl.combat;

import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.world.AntiBot;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.Utils;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class WTap
extends Module {
    private SliderSetting delayBetweenReset;
    private SliderSetting delayUntilReset;
    private SliderSetting chance = new SliderSetting("Chance", "%", 100.0, 0.0, 100.0, 1.0);
    private ButtonSetting playersOnly;
    private long pendingResetAtMs;
    private long lastResetStartMs;
    private boolean waitingForSprintRestart;
    private boolean wasSprinting;
    public static boolean stopSprint = false;

    public WTap() {
        super("WTap", Module.category.combat);
        this.registerSetting(this.chance);
        this.delayBetweenReset = new SliderSetting("Delay between reset", "ms", 300.0, 0.0, 1000.0, 1.0);
        this.registerSetting(this.delayBetweenReset);
        this.delayUntilReset = new SliderSetting("Delay until reset", "ms", 150.0, 0.0, 1000.0, 1.0);
        this.registerSetting(this.delayUntilReset);
        this.playersOnly = new ButtonSetting("Players only", true);
        this.registerSetting(this.playersOnly);
        this.closetModule = true;
    }

    @Override
    public void onEnable() {
        this.pendingResetAtMs = 0L;
        this.lastResetStartMs = 0L;
        this.waitingForSprintRestart = false;
        this.wasSprinting = false;
        stopSprint = false;
    }

    @Override
    public void onUpdate() {
        if (!Utils.nullCheck() || WTap.mc.thePlayer.isDead) {
            this.pendingResetAtMs = 0L;
            this.waitingForSprintRestart = false;
            this.wasSprinting = false;
            stopSprint = false;
            return;
        }
        long now = System.currentTimeMillis();
        boolean sprintingNow = WTap.mc.thePlayer.isSprinting();
        if (this.waitingForSprintRestart && sprintingNow && !this.wasSprinting) {
            this.lastResetStartMs = now;
            this.waitingForSprintRestart = false;
        }
        if (this.pendingResetAtMs > 0L && now >= this.pendingResetAtMs) {
            stopSprint = true;
            this.pendingResetAtMs = 0L;
            this.waitingForSprintRestart = true;
        }
        this.wasSprinting = sprintingNow;
    }

    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        double ch;
        if (!Utils.nullCheck() || event.entityPlayer != WTap.mc.thePlayer || !WTap.mc.thePlayer.isSprinting()) {
            return;
        }
        if (ModuleManager.autoblock != null && ModuleManager.autoblock.isEnabled() && ModuleManager.autoblock.isTradePaused()) {
            return;
        }
        if (this.chance.getInput() == 0.0) {
            return;
        }
        if (this.playersOnly.isToggled()) {
            if (!(event.target instanceof EntityPlayer)) {
                return;
            }
            if (AntiBot.isBot(event.target)) {
                return;
            }
        } else if (!(event.target instanceof EntityLivingBase)) {
            return;
        }
        if (((EntityLivingBase)event.target).deathTime != 0) {
            return;
        }
        if (this.pendingResetAtMs > 0L) {
            return;
        }
        long currentMs = System.currentTimeMillis();
        long betweenResetDelay = (long)this.delayBetweenReset.getInput();
        if (this.lastResetStartMs > 0L && currentMs - this.lastResetStartMs < betweenResetDelay) {
            return;
        }
        if (this.chance.getInput() != 100.0 && (ch = Math.random()) >= this.chance.getInput() / 100.0) {
            return;
        }
        this.pendingResetAtMs = currentMs + (long)this.delayUntilReset.getInput();
    }

    @Override
    public void onDisable() {
        this.pendingResetAtMs = 0L;
        this.lastResetStartMs = 0L;
        this.waitingForSprintRestart = false;
        this.wasSprinting = false;
        stopSprint = false;
    }
}

