package keystrokesmod.module.impl.movement;

import keystrokesmod.event.PostUpdateEvent;
import keystrokesmod.event.PrePlayerInputEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.module.Module;
import keystrokesmod.utility.Utils;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class Stasis
extends Module {
    private double savedMotionX;
    private double savedMotionY;
    private double savedMotionZ;

    public Stasis() {
        super("Stasis", Module.category.movement);
    }

    @Override
    public void onEnable() {
        if (!Utils.nullCheck()) {
            return;
        }
        this.savedMotionX = Stasis.mc.thePlayer.motionX;
        this.savedMotionY = Stasis.mc.thePlayer.motionY;
        this.savedMotionZ = Stasis.mc.thePlayer.motionZ;
        Stasis.mc.thePlayer.motionX = 0.0;
        Stasis.mc.thePlayer.motionY = 0.0;
        Stasis.mc.thePlayer.motionZ = 0.0;
    }

    @Override
    public void onDisable() {
        if (!Utils.nullCheck()) {
            return;
        }
        Stasis.mc.thePlayer.motionX = this.savedMotionX;
        Stasis.mc.thePlayer.motionY = this.savedMotionY;
        Stasis.mc.thePlayer.motionZ = this.savedMotionZ;
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (!Utils.nullCheck()) {
            return;
        }
        Stasis.mc.thePlayer.motionX = 0.0;
        Stasis.mc.thePlayer.motionY = 0.0;
        Stasis.mc.thePlayer.motionZ = 0.0;
    }

    @SubscribeEvent
    public void onPostUpdate(PostUpdateEvent e) {
        if (!Utils.nullCheck()) {
            return;
        }
        Stasis.mc.thePlayer.motionX = 0.0;
        Stasis.mc.thePlayer.motionY = 0.0;
        Stasis.mc.thePlayer.motionZ = 0.0;
    }

    @SubscribeEvent
    public void onPrePlayerInput(PrePlayerInputEvent e) {
        e.setForward(0.0f);
        e.setStrafe(0.0f);
    }

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent e) {
        if (!(e.getPacket() instanceof C03PacketPlayer)) {
            return;
        }
        if (!(e.getPacket() instanceof C03PacketPlayer.C05PacketPlayerLook)) {
            e.setCanceled(true);
        }
    }
}

