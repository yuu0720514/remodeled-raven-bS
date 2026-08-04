package keystrokesmod.module.impl.movement;

import keystrokesmod.event.PostUpdateEvent;
import keystrokesmod.event.PrePlayerInputEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.Utils;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class Stasis
        extends Module {
    private final SliderSetting speedSetting;
    private double savedMotionX;
    private double savedMotionY;
    private double savedMotionZ;
    private double ticksAccumulator = 0.0;
    private boolean isFreezing = false;

    public Stasis() {
        super("Stasis", Module.category.movement);
        this.registerSetting(speedSetting = new SliderSetting("speed", 1.0, 0.0, 1.0, 0.01));
    }

    @Override
    public void onEnable() {
        if (!Utils.nullCheck()) {
            return;
        }
        this.savedMotionX = 0.0;
        this.savedMotionY = 0.0;
        this.savedMotionZ = 0.0;
        this.ticksAccumulator = 0.0;
        this.isFreezing = false;
        if (speedSetting.getInput() == 0.0) {
            freezeMotion();
            this.isFreezing = true;
        }
    }

    @Override
    public void onDisable() {
        if (!Utils.nullCheck()) {
            return;
        }
        if (this.isFreezing) {
            releasePlayer();
        }
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (!Utils.nullCheck()) {
            return;
        }

        double speed = speedSetting.getInput();
        if (speed == 0.0) {
            this.isFreezing = true;
            freezeMotion();
            return;
        }
        ticksAccumulator += speed;

        if (ticksAccumulator >= 1.0) {
            ticksAccumulator -= 1.0;
            if (this.isFreezing) {
                releasePlayer();
            }
        } else {
            if (!this.isFreezing) {
                this.savedMotionX = Stasis.mc.thePlayer.motionX;
                this.savedMotionY = Stasis.mc.thePlayer.motionY;
                this.savedMotionZ = Stasis.mc.thePlayer.motionZ;
                this.isFreezing = true;
            }
            freezeMotion();
        }
    }

    @SubscribeEvent
    public void onPostUpdate(PostUpdateEvent e) {
        if (!Utils.nullCheck()) {
            return;
        }
        if (this.isFreezing) {
            freezeMotion();
        }
    }

    @SubscribeEvent
    public void onPrePlayerInput(PrePlayerInputEvent e) {
        if (this.isFreezing) {
            e.setForward(0.0f);
            e.setStrafe(0.0f);
        }
    }

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent e) {
        if (this.isFreezing) {
            if (!(e.getPacket() instanceof C03PacketPlayer)) {
                return;
            }
            if (!(e.getPacket() instanceof C03PacketPlayer.C05PacketPlayerLook)) {
                e.setCanceled(true);
            }
        }
    }

    private void freezeMotion() {
        Stasis.mc.thePlayer.motionX = 0.0;
        Stasis.mc.thePlayer.motionY = 0.0;
        Stasis.mc.thePlayer.motionZ = 0.0;
    }

    private void releasePlayer() {
        // speed = 0 のときは慣性を復元せず完全クリア（Flag防止）
        if (speedSetting.getInput() == 0.0) {
            freezeMotion();
        } else {
            Stasis.mc.thePlayer.motionX = this.savedMotionX;
            Stasis.mc.thePlayer.motionY = this.savedMotionY;
            Stasis.mc.thePlayer.motionZ = this.savedMotionZ;
        }
        this.isFreezing = false;
    }

    public boolean isFreezing() {
        return this.isFreezing;
    }
}