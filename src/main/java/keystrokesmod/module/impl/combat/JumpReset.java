package keystrokesmod.module.impl.combat;

import keystrokesmod.event.PostMotionEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.Utils;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class JumpReset extends Module {
    private SliderSetting chance;
    private ButtonSetting requireMouseDown;
    private ButtonSetting requireMovingForward;
    private ButtonSetting requireAim;

    private boolean setJump;
    private int lastHurtTime;
    private boolean wasOnGround;
    private boolean landedFromHighFall;

    private static final int HURT_TIME_MAX = 10;
    private static final double HIGH_FALL_THRESHOLD = 3.0;

    public JumpReset() {
        super("Jump Reset", category.combat);
        this.registerSetting(chance = new SliderSetting("Chance", "%", 80, 0, 100, 1));
        this.registerSetting(requireMouseDown = new ButtonSetting("Require mouse down", false));
        this.registerSetting(requireMovingForward = new ButtonSetting("Require moving forward", true));
        this.registerSetting(requireAim = new ButtonSetting("Require aim", true));
        this.closetModule = true;
    }

    @Override
    public String getInfo() {
        return (int) chance.getInput() == 100 ? "" : ((int) chance.getInput()) + "%";
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        int hurtTime = mc.thePlayer.hurtTime;
        boolean onGround = mc.thePlayer.onGround;

        boolean justLanded = onGround && !wasOnGround;

        if (justLanded && mc.thePlayer.fallDistance > HIGH_FALL_THRESHOLD) {
            landedFromHighFall = true;
        }

        boolean hitDetected = (hurtTime == HURT_TIME_MAX && lastHurtTime < HURT_TIME_MAX);

        if (hitDetected) {
            boolean skipThisHit = landedFromHighFall;
            landedFromHighFall = false;

            boolean mouseDown = !requireMouseDown.isToggled() || mc.gameSettings.keyBindAttack.isKeyDown();
            boolean aimingAt  = !requireAim.isToggled() || checkAim();
            boolean forward   = !requireMovingForward.isToggled() || mc.gameSettings.keyBindForward.isKeyDown();
            boolean randomize = (int) chance.getInput() == 100
                    || Utils.randomizeDouble(0, 100) < chance.getInput();

            if (!skipThisHit
                    && onGround
                    && !mc.thePlayer.isBurning()
                    && !mc.thePlayer.capabilities.allowFlying
                    && !hasBadEffect()
                    && mouseDown
                    && aimingAt
                    && forward
                    && randomize) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), true);
                setJump = true;
            }
        }

        lastHurtTime = hurtTime;
        wasOnGround  = onGround;
    }

    @SubscribeEvent
    public void onPostMotion(PostMotionEvent e) {
        if (setJump) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
            setJump = false;
        }
    }

    private boolean hasBadEffect() {
        PotionEffect jump   = mc.thePlayer.getActivePotionEffect(Potion.jump);
        PotionEffect poison = mc.thePlayer.getActivePotionEffect(Potion.poison);
        PotionEffect wither = mc.thePlayer.getActivePotionEffect(Potion.wither);
        return jump != null || poison != null || wither != null;
    }

    private boolean checkAim() {
        MovingObjectPosition result = mc.objectMouseOver;
        return result != null
                && result.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                && result.entityHit instanceof EntityPlayer;
    }
}