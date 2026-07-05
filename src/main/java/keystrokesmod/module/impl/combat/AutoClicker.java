package keystrokesmod.module.impl.combat;

import java.lang.reflect.Field;
import java.util.Random;
import keystrokesmod.event.PrePlayerInteractEvent;
import keystrokesmod.mixin.impl.accessor.IAccessorMinecraft;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.combat.KillAura;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.ReflectionUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.Slot;
import net.minecraft.util.BlockPos;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

public class AutoClicker
extends Module {
    public SliderSetting targetCPS;
    public ButtonSetting simulateExhaust;
    public ButtonSetting notUsingItem;
    public ButtonSetting breakBlocks;
    public ButtonSetting weaponOnly;
    public ButtonSetting disableCreative;
    public ButtonSetting inventory;
    public SliderSetting inventoryStartDelay;
    private long nextClickTime;
    private long inventoryNextClickTime;
    private boolean isHoldingBlockBreak;
    private Random rand;
    private static Field hoveredSlotField;

    public AutoClicker() {
        super("Auto Clicker", Module.category.combat, 0);
        this.registerSetting(new DescriptionSetting("Best with delay remover."));
        this.targetCPS = new SliderSetting("Target CPS", 10.0, 1.0, 20.0, 0.5);
        this.registerSetting(this.targetCPS);
        this.simulateExhaust = new ButtonSetting("Simulate exhaust", true);
        this.registerSetting(this.simulateExhaust);
        this.notUsingItem = new ButtonSetting("Not using item", false);
        this.registerSetting(this.notUsingItem);
        this.breakBlocks = new ButtonSetting("Break blocks", false);
        this.registerSetting(this.breakBlocks);
        this.weaponOnly = new ButtonSetting("Weapon only", false);
        this.registerSetting(this.weaponOnly);
        this.disableCreative = new ButtonSetting("Disable in creative", false);
        this.registerSetting(this.disableCreative);
        this.inventory = new ButtonSetting("Inventory", false);
        this.registerSetting(this.inventory);
        this.inventoryStartDelay = new SliderSetting("Start delay", "ms", 100.0, 0.0, 250.0, 1.0);
        this.registerSetting(this.inventoryStartDelay);
        this.closetModule = true;
    }

    @Override
    public String getInfo() {
        double cps = this.targetCPS.getInput();
        return cps == Math.rint(cps) ? Integer.toString((int)cps) : Double.toString(Utils.round(cps, 1));
    }

    @Override
    public void onEnable() {
        this.rand = new Random();
        this.nextClickTime = 0L;
        this.inventoryNextClickTime = 0L;
        this.isHoldingBlockBreak = false;
        AutoClicker.ensureHoveredSlotField();
    }

    @Override
    public void onDisable() {
        this.nextClickTime = 0L;
        this.inventoryNextClickTime = 0L;
        this.isHoldingBlockBreak = false;
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        int mode;
        if (e.phase != TickEvent.Phase.END) {
            return;
        }
        if (!this.inventory.isToggled()) {
            return;
        }
        if (!Utils.nullCheck()) {
            return;
        }
        if (!(AutoClicker.mc.currentScreen instanceof GuiContainer)) {
            this.inventoryNextClickTime = 0L;
            return;
        }
        if (!Mouse.isButtonDown((int)0)) {
            this.inventoryNextClickTime = 0L;
            return;
        }
        AutoClicker.ensureHoveredSlotField();
        if (hoveredSlotField == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (this.inventoryNextClickTime == 0L) {
            this.inventoryNextClickTime = now + (long)this.inventoryStartDelay.getInput();
        }
        int clicks = 0;
        while (this.inventoryNextClickTime <= now) {
            ++clicks;
            this.inventoryNextClickTime += this.nextDelay();
        }
        if (clicks <= 0) {
            return;
        }
        GuiContainer gui = (GuiContainer)AutoClicker.mc.currentScreen;
        Slot slot = AutoClicker.getHoveredSlot(gui);
        if (slot == null || slot.slotNumber < 0) {
            return;
        }
        int windowId = gui.inventorySlots.windowId;
        int slotId = slot.slotNumber;
        int n = mode = GuiScreen.isShiftKeyDown() ? 1 : 0;
        if (AutoClicker.mc.playerController == null || AutoClicker.mc.thePlayer == null) {
            return;
        }
        for (int i = 0; i < clicks; ++i) {
            AutoClicker.mc.playerController.windowClick(windowId, slotId, 0, mode, (EntityPlayer)AutoClicker.mc.thePlayer);
        }
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public void onPrePlayerInteract(PrePlayerInteractEvent e) {
        if (!Utils.nullCheck()) {
            return;
        }
        if (ModuleManager.killAura != null && ModuleManager.killAura.isEnabled() && KillAura.target != null) {
            return;
        }
        int key = AutoClicker.mc.gameSettings.keyBindAttack.getKeyCode();
        boolean clickActive = Mouse.isButtonDown((int)0);
        if (!clickActive && ModuleManager.autoblock != null && ModuleManager.autoblock.shouldAssistAutoClicker()) {
            clickActive = true;
        }
        if (clickActive) {
            long now = System.currentTimeMillis();
            if (this.nextClickTime == 0L) {
                this.nextClickTime = now + this.nextDelay();
            }
            int clicks = 0;
            while (this.nextClickTime <= now) {
                ++clicks;
                this.nextClickTime += this.nextDelay();
            }
            if (this.notUsingItem.isToggled() && AutoClicker.mc.thePlayer.isUsingItem() && (ModuleManager.autoblock == null || !ModuleManager.autoblock.canAutoClickerAttack())) {
                return;
            }
            if (ModuleManager.autoblock != null && ModuleManager.autoblock.isEnabled() && ModuleManager.autoblock.isTradePaused()) {
                return;
            }
            if (this.disableCreative.isToggled() && AutoClicker.mc.thePlayer.capabilities.isCreativeMode) {
                return;
            }
            if (AutoClicker.mc.currentScreen != null || !AutoClicker.mc.inGameHasFocus) {
                return;
            }
            if (this.weaponOnly.isToggled() && !Utils.holdingWeapon()) {
                return;
            }
            if (this.breakBlocks.isToggled()) {
                if (!AutoClicker.mc.thePlayer.capabilities.allowEdit) {
                    if (this.isHoldingBlockBreak) {
                        KeyBinding.setKeyBindState((int)key, (boolean)false);
                        ReflectionUtils.setButton(0, false);
                        this.isHoldingBlockBreak = false;
                    }
                } else if (AutoClicker.mc.objectMouseOver != null) {
                    BlockPos pos = AutoClicker.mc.objectMouseOver.getBlockPos();
                    if (pos != null) {
                        Block block = AutoClicker.mc.theWorld.getBlockState(pos).getBlock();
                        if (block != Blocks.air && !(block instanceof BlockLiquid)) {
                            if (!this.isHoldingBlockBreak) {
                                KeyBinding.setKeyBindState((int)key, (boolean)true);
                                ReflectionUtils.setButton(0, true);
                                this.isHoldingBlockBreak = true;
                            }
                            return;
                        }
                        if (this.isHoldingBlockBreak) {
                            KeyBinding.setKeyBindState((int)key, (boolean)false);
                            ReflectionUtils.setButton(0, false);
                            this.isHoldingBlockBreak = false;
                            return;
                        }
                    } else {
                        this.isHoldingBlockBreak = false;
                    }
                }
            }
            for (int i = 0; i < clicks; ++i) {
                boolean abTrade;
                boolean bl = abTrade = ModuleManager.autoblock != null && ModuleManager.autoblock.shouldAssistAutoClicker();
                if (abTrade || ModuleManager.autoblock != null && ModuleManager.autoblock.canAutoClickerAttack()) {
                    ((IAccessorMinecraft)mc).setLeftClickCounter(0);
                }
                if (abTrade) {
                    ((IAccessorMinecraft)mc).callClickMouse();
                    continue;
                }
                KeyBinding.onTick((int)key);
                ReflectionUtils.setButton(0, true);
            }
        } else {
            this.nextClickTime = 0L;
            this.isHoldingBlockBreak = false;
            KeyBinding.setKeyBindState((int)key, (boolean)false);
            ReflectionUtils.setButton(0, false);
        }
    }

    private long nextDelay() {
        int finalDelay;
        int target = Math.max(1, (int)this.targetCPS.getInput());
        int baseDelay = 1000 / target;
        if (this.simulateExhaust.isToggled()) {
            int variation = this.rand.nextInt(baseDelay + 1) - baseDelay / 2;
            finalDelay = baseDelay + variation;
            if (this.rand.nextInt(100) < 15) {
                finalDelay = this.rand.nextBoolean() ? 25 + this.rand.nextInt(16) : baseDelay + 50 + this.rand.nextInt(41);
            }
            if (this.rand.nextInt(100) < 8) {
                int spikeMult = 50 + this.rand.nextInt(151);
                finalDelay = finalDelay * spikeMult / 100;
            }
            if (this.rand.nextInt(100) < 10) {
                finalDelay += 10 + this.rand.nextInt(26);
            }
        } else {
            finalDelay = baseDelay + (this.rand.nextInt(21) - 10);
        }
        return Math.max(33, Math.min(180, finalDelay));
    }

    private static void ensureHoveredSlotField() {
        if (hoveredSlotField != null) {
            return;
        }
        try {
            hoveredSlotField = GuiContainer.class.getDeclaredField("theSlot");
            hoveredSlotField.setAccessible(true);
        }
        catch (NoSuchFieldException e) {
            try {
                hoveredSlotField = GuiContainer.class.getDeclaredField("theSlot");
                hoveredSlotField.setAccessible(true);
            }
            catch (NoSuchFieldException ignored) {
                hoveredSlotField = null;
            }
        }
    }

    private static Slot getHoveredSlot(GuiContainer gui) {
        if (hoveredSlotField == null || gui == null) {
            return null;
        }
        try {
            Object value = hoveredSlotField.get(gui);
            if (value instanceof Slot) {
                return (Slot)value;
            }
        }
        catch (IllegalAccessException illegalAccessException) {
            // empty catch block
        }
        return null;
    }
}

