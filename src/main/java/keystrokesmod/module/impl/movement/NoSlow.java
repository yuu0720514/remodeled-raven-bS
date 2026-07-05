package keystrokesmod.module.impl.movement;

import keystrokesmod.event.PostPlayerInputEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.Utils;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemSword;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class NoSlow
extends Module {
    public static SliderSetting mode;
    public static SliderSetting slowed;
    public static ButtonSetting disableBow;
    public static ButtonSetting disablePotions;
    public static ButtonSetting swordOnly;
    public static ButtonSetting vanillaSword;
    private final String[] NOSLOW_MODES = new String[]{"Vanilla", "Beta"};
    public boolean noSlowing;
    private boolean setJump;

    public NoSlow() {
        super("NoSlow", Module.category.movement, 0);
        this.registerSetting(new DescriptionSetting("Default is 80% motion reduction."));
        mode = new SliderSetting("Mode", 0, this.NOSLOW_MODES);
        this.registerSetting(mode);
        slowed = new SliderSetting("Slow %", 80.0, 0.0, 80.0, 1.0);
        this.registerSetting(slowed);
        disableBow = new ButtonSetting("Disable bow", false);
        this.registerSetting(disableBow);
        disablePotions = new ButtonSetting("Disable potions", false);
        this.registerSetting(disablePotions);
        swordOnly = new ButtonSetting("Sword only", false);
        this.registerSetting(swordOnly);
        vanillaSword = new ButtonSetting("Vanilla sword", false);
        this.registerSetting(vanillaSword);
    }

    @Override
    public void onDisable() {
        this.noSlowing = false;
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        boolean apply;
        if (ModuleManager.autoblock != null && ModuleManager.autoblock.shouldDisableNoSlowBypass()) {
            return;
        }
        if (vanillaSword.isToggled() && Utils.holdingSword()) {
            return;
        }
        boolean bl = apply = NoSlow.getSlowed() != 0.2f;
        if (!apply || !NoSlow.mc.thePlayer.isUsingItem()) {
            return;
        }
        switch ((int)mode.getInput()) {
            case 1: {
                NoSlow.mc.thePlayer.sendQueue.addToSendQueue((Packet)new C09PacketHeldItemChange(NoSlow.mc.thePlayer.inventory.currentItem % 8 + 1));
                NoSlow.mc.thePlayer.sendQueue.addToSendQueue((Packet)new C09PacketHeldItemChange(NoSlow.mc.thePlayer.inventory.currentItem));
                NoSlow.mc.thePlayer.sendQueue.addToSendQueue((Packet)new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
            }
        }
    }

    @SubscribeEvent
    public void onPostPlayerInput(PostPlayerInputEvent e) {
        if (this.setJump) {
            NoSlow.mc.thePlayer.movementInput.jump = true;
            this.setJump = false;
        }
    }

    public static float getSlowed() {
        if (ModuleManager.autoblock != null && ModuleManager.autoblock.shouldApplyItemSlow()) {
            return 0.2f;
        }
        if (NoSlow.mc.thePlayer.getHeldItem() == null || ModuleManager.noSlow == null || !ModuleManager.noSlow.isEnabled()) {
            return 0.2f;
        }
        if (swordOnly.isToggled() && !(NoSlow.mc.thePlayer.getHeldItem().getItem() instanceof ItemSword)) {
            return 0.2f;
        }
        if (NoSlow.mc.thePlayer.getHeldItem().getItem() instanceof ItemBow && disableBow.isToggled()) {
            return 0.2f;
        }
        if (NoSlow.mc.thePlayer.getHeldItem().getItem() instanceof ItemPotion && !ItemPotion.isSplash((int)NoSlow.mc.thePlayer.getHeldItem().getItemDamage()) && disablePotions.isToggled()) {
            return 0.2f;
        }
        return (100.0f - (float)slowed.getInput()) / 100.0f;
    }

    @Override
    public String getInfo() {
        return this.NOSLOW_MODES[(int)mode.getInput()];
    }
}

