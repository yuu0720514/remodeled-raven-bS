package keystrokesmod.module.impl.combat;

import keystrokesmod.event.PrePlayerInteractEvent;
import keystrokesmod.event.PreSlotScrollEvent;
import keystrokesmod.event.SlotUpdateEvent;
import keystrokesmod.mixin.impl.accessor.IAccessorPlayerControllerMP;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.combat.KillAura;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.ItemListSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.CombatTargeting;
import keystrokesmod.utility.ItemSearchIndex;
import keystrokesmod.utility.ItemSortScoring;
import keystrokesmod.utility.Utils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

public class AutoWeapon
extends Module {
    private final ItemListSetting weapons = new ItemListSetting("Weapons");
    private final SliderSetting range;
    private final ButtonSetting switchBackWhenDone;
    private final ButtonSetting overrideSwapBack;
    private final ButtonSetting requireMouse;
    private final ButtonSetting ignoreTeammates;
    private final ButtonSetting useKillAuraTarget;
    private boolean hasSwapped;
    private int previousSlot = -1;

    public AutoWeapon() {
        super("Auto Weapon", Module.category.combat);
        this.registerSetting(this.weapons);
        this.weapons.addItem("@category:sword");
        this.weapons.addItem("minecraft:stick");
        this.range = new SliderSetting("Range", 3.0, 1.0, 6.0, 0.1);
        this.registerSetting(this.range);
        this.switchBackWhenDone = new ButtonSetting("Switch back when done", true);
        this.registerSetting(this.switchBackWhenDone);
        this.overrideSwapBack = new ButtonSetting("Override swap back", true);
        this.registerSetting(this.overrideSwapBack);
        this.requireMouse = new ButtonSetting("Require mouse", false);
        this.registerSetting(this.requireMouse);
        this.ignoreTeammates = new ButtonSetting("Ignore teammates", true);
        this.registerSetting(this.ignoreTeammates);
        this.useKillAuraTarget = new ButtonSetting("Use KillAura target", true);
        this.registerSetting(this.useKillAuraTarget);
        this.closetModule = true;
    }

    @Override
    public void onEnable() {
        this.resetState();
    }

    @Override
    public void onDisable() {
        this.resetState();
    }

    @SubscribeEvent
    public void onScrollSlot(PreSlotScrollEvent event) {
        if (!this.hasSwapped || !this.overrideSwapBack.isToggled()) {
            return;
        }
        int slot = Integer.compare(event.slot, 0);
        this.previousSlot = Math.floorMod(AutoWeapon.mc.thePlayer.inventory.currentItem - slot, 9);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onSlotUpdate(SlotUpdateEvent event) {
        if (!this.hasSwapped || !this.overrideSwapBack.isToggled()) {
            return;
        }
        this.previousSlot = event.slot;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onPrePlayerInteract(PrePlayerInteractEvent event) {
        if (!Utils.nullCheck() || AutoWeapon.mc.thePlayer.isDead || AutoWeapon.mc.currentScreen != null) {
            this.resetState();
            return;
        }
        if (this.requireMouse.isToggled() && !Mouse.isButtonDown((int)0)) {
            this.resetSlot();
            return;
        }
        EntityPlayer target = this.getCombatTarget();
        if (target == null) {
            this.resetSlot();
            return;
        }
        int weaponSlot = this.findBestWeaponSlot();
        if (weaponSlot == -1) {
            this.resetSlot();
            return;
        }
        int currentSlot = AutoWeapon.mc.thePlayer.inventory.currentItem;
        if (weaponSlot == currentSlot) {
            if (!this.hasSwapped) {
                this.previousSlot = -1;
            }
            return;
        }
        if (!this.hasSwapped) {
            this.previousSlot = currentSlot;
        }
        this.setSlot(weaponSlot);
    }

    private EntityPlayer getCombatTarget() {
        EntityPlayer killAuraTarget;
        boolean ignoreTeam;
        double rangeSq = this.range.getInput() * this.range.getInput();
        EntityPlayer crosshairTarget = CombatTargeting.getMouseOverTarget(rangeSq, ignoreTeam = this.ignoreTeammates.isToggled());
        if (crosshairTarget != null) {
            return crosshairTarget;
        }
        if (this.useKillAuraTarget.isToggled() && ModuleManager.killAura != null && ModuleManager.killAura.isEnabled() && KillAura.target instanceof EntityPlayer && CombatTargeting.isValidPlayer(killAuraTarget = (EntityPlayer)KillAura.target, rangeSq, ignoreTeam)) {
            return killAuraTarget;
        }
        return null;
    }

    private int findBestWeaponSlot() {
        if (this.weapons.getItems().isEmpty()) {
            return -1;
        }
        int currentSlot = AutoWeapon.mc.thePlayer.inventory.currentItem;
        ItemStack currentStack = AutoWeapon.mc.thePlayer.inventory.getStackInSlot(currentSlot);
        if (this.weapons.matches(currentStack)) {
            return currentSlot;
        }
        int bestSlot = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack stack = AutoWeapon.mc.thePlayer.inventory.getStackInSlot(slot);
            double score = this.scoreWeaponStack(stack);
            if (!(score > bestScore)) continue;
            bestScore = score;
            bestSlot = slot;
        }
        return bestSlot;
    }

    private double scoreWeaponStack(ItemStack stack) {
        if (!this.weapons.matches(stack)) {
            return Double.NEGATIVE_INFINITY;
        }
        double bestScore = ItemSortScoring.getMeleeSelectionScore(stack);
        for (String storageId : this.weapons.getItems()) {
            double quality = ItemSearchIndex.getMatchQuality(storageId, stack);
            if (!(quality > bestScore)) continue;
            bestScore = quality;
        }
        return bestScore;
    }

    private void resetState() {
        this.resetSlot();
        this.previousSlot = -1;
        this.hasSwapped = false;
    }

    private void resetSlot() {
        if (this.previousSlot != -1 && this.switchBackWhenDone.isToggled()) {
            this.setSlot(this.previousSlot);
        }
        this.previousSlot = -1;
        this.hasSwapped = false;
    }

    private void setSlot(int slot) {
        if (slot < 0 || slot > 8 || slot == AutoWeapon.mc.thePlayer.inventory.currentItem) {
            return;
        }
        AutoWeapon.mc.thePlayer.inventory.currentItem = slot;
        this.hasSwapped = true;
        ((IAccessorPlayerControllerMP)AutoWeapon.mc.playerController).callSyncCurrentPlayItem();
    }
}

