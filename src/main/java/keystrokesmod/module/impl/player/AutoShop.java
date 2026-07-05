package keystrokesmod.module.impl.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.GroupSetting;
import keystrokesmod.module.setting.impl.InventoryItemListSetting;
import keystrokesmod.module.setting.impl.KeySetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.ItemSearchIndex;
import keystrokesmod.utility.Utils;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class AutoShop
extends Module {
    private final SliderSetting cps = new SliderSetting("CPS", 120.0, 1.0, 500.0, 1.0);
    private final ButtonSetting onlyHypixelShop;
    private final KeySetting buy1Key;
    private final KeySetting buy2Key;
    private final KeySetting buy3Key;
    private final KeySetting buy4Key;
    private final KeySetting buy5Key;
    private final InventoryItemListSetting buy1Items;
    private final InventoryItemListSetting buy1CostItems;
    private final InventoryItemListSetting buy2Items;
    private final InventoryItemListSetting buy2CostItems;
    private final InventoryItemListSetting buy3Items;
    private final InventoryItemListSetting buy3CostItems;
    private final InventoryItemListSetting buy4Items;
    private final InventoryItemListSetting buy4CostItems;
    private final InventoryItemListSetting buy5Items;
    private final InventoryItemListSetting buy5CostItems;
    private long nextClickTime;
    private Random rand;

    public AutoShop() {
        super("Auto Shop", Module.category.player);
        this.registerSetting(this.cps);
        this.onlyHypixelShop = new ButtonSetting("Only Hypixel shop", true);
        this.registerSetting(this.onlyHypixelShop);
        GroupSetting buy1 = new GroupSetting("Buy 1");
        this.registerSetting(buy1);
        this.buy1Key = new KeySetting(buy1, "Key", 0);
        this.registerSetting(this.buy1Key);
        this.buy1Items = new InventoryItemListSetting(buy1, "Items");
        this.registerSetting(this.buy1Items);
        this.buy1CostItems = new InventoryItemListSetting(buy1, "Cost items (stop if none)");
        this.registerSetting(this.buy1CostItems);
        GroupSetting buy2 = new GroupSetting("Buy 2");
        this.registerSetting(buy2);
        this.buy2Key = new KeySetting(buy2, "Key", 0);
        this.registerSetting(this.buy2Key);
        this.buy2Items = new InventoryItemListSetting(buy2, "Items");
        this.registerSetting(this.buy2Items);
        this.buy2CostItems = new InventoryItemListSetting(buy2, "Cost items (stop if none)");
        this.registerSetting(this.buy2CostItems);
        GroupSetting buy3 = new GroupSetting("Buy 3");
        this.registerSetting(buy3);
        this.buy3Key = new KeySetting(buy3, "Key", 0);
        this.registerSetting(this.buy3Key);
        this.buy3Items = new InventoryItemListSetting(buy3, "Items");
        this.registerSetting(this.buy3Items);
        this.buy3CostItems = new InventoryItemListSetting(buy3, "Cost items (stop if none)");
        this.registerSetting(this.buy3CostItems);
        GroupSetting buy4 = new GroupSetting("Buy 4");
        this.registerSetting(buy4);
        this.buy4Key = new KeySetting(buy4, "Key", 0);
        this.registerSetting(this.buy4Key);
        this.buy4Items = new InventoryItemListSetting(buy4, "Items");
        this.registerSetting(this.buy4Items);
        this.buy4CostItems = new InventoryItemListSetting(buy4, "Cost items (stop if none)");
        this.registerSetting(this.buy4CostItems);
        GroupSetting buy5 = new GroupSetting("Buy 5");
        this.registerSetting(buy5);
        this.buy5Key = new KeySetting(buy5, "Key", 0);
        this.registerSetting(this.buy5Key);
        this.buy5Items = new InventoryItemListSetting(buy5, "Items");
        this.registerSetting(this.buy5Items);
        this.buy5CostItems = new InventoryItemListSetting(buy5, "Cost items (stop if none)");
        this.registerSetting(this.buy5CostItems);
    }

    @Override
    public String getInfo() {
        double value = this.cps.getInput();
        return value == Math.rint(value) ? Integer.toString((int)value) : Double.toString(Utils.round(value, 1));
    }

    @Override
    public void onEnable() {
        this.rand = new Random();
        this.nextClickTime = 0L;
    }

    @Override
    public void onDisable() {
        this.nextClickTime = 0L;
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(Utils.nullCheck() && AutoShop.mc.currentScreen instanceof GuiChest && AutoShop.mc.thePlayer.openContainer instanceof ContainerChest)) {
            this.nextClickTime = 0L;
            return;
        }
        ContainerChest chest = (ContainerChest)AutoShop.mc.thePlayer.openContainer;
        if (this.onlyHypixelShop.isToggled() && !this.isShop(chest)) {
            this.nextClickTime = 0L;
            return;
        }
        List<ActiveBuy> activeBuys = this.getActiveBuys();
        if (activeBuys.isEmpty()) {
            this.nextClickTime = 0L;
            return;
        }
        boolean anyHasItems = false;
        for (ActiveBuy ab : activeBuys) {
            if (ab.items.getItems().isEmpty()) continue;
            anyHasItems = true;
            break;
        }
        if (!anyHasItems) {
            this.nextClickTime = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        if (this.nextClickTime == 0L) {
            this.nextClickTime = now + this.nextDelay();
        }
        int clicks = 0;
        while (this.nextClickTime <= now) {
            ++clicks;
            this.nextClickTime += this.nextDelay();
        }
        if (clicks <= 0) {
            return;
        }
        for (int i = 0; i < clicks; ++i) {
            for (ActiveBuy active : activeBuys) {
                if (active.items.getItems().isEmpty() || !active.costItems.getItems().isEmpty() && this.countPaymentItems(active.costItems) == 0) continue;
                this.clickMatchingItem(chest, active.items);
            }
        }
    }

    private List<ActiveBuy> getActiveBuys() {
        ArrayList<ActiveBuy> result = new ArrayList<ActiveBuy>();
        if (this.buy1Key.isPressed()) {
            result.add(new ActiveBuy(this.buy1Items, this.buy1CostItems));
        }
        if (this.buy2Key.isPressed()) {
            result.add(new ActiveBuy(this.buy2Items, this.buy2CostItems));
        }
        if (this.buy3Key.isPressed()) {
            result.add(new ActiveBuy(this.buy3Items, this.buy3CostItems));
        }
        if (this.buy4Key.isPressed()) {
            result.add(new ActiveBuy(this.buy4Items, this.buy4CostItems));
        }
        if (this.buy5Key.isPressed()) {
            result.add(new ActiveBuy(this.buy5Items, this.buy5CostItems));
        }
        return result;
    }

    private boolean clickMatchingItem(ContainerChest chest, InventoryItemListSetting list) {
        int chestSize = chest.getLowerChestInventory().getSizeInventory();
        for (Object object : chest.inventorySlots) {
            ItemStack stack;
            if (!(object instanceof Slot)) continue;
            Slot slot = (Slot)object;
            if (slot.slotNumber < 0 || slot.slotNumber >= chestSize || (stack = slot.getStack()) == null) continue;
            for (String storageId : list.getItems()) {
                if (!ItemSearchIndex.matches(storageId, stack)) continue;
                short actionNum = (short) AutoShop.mc.thePlayer.openContainer.getNextTransactionID(AutoShop.mc.thePlayer.inventory);
                AutoShop.mc.thePlayer.sendQueue.addToSendQueue(
                    new C0EPacketClickWindow(
                        AutoShop.mc.thePlayer.openContainer.windowId,
                        slot.slotNumber, 0, 0, stack, actionNum
                    )
                );
                return true;
            }
        }
        return false;
    }

    private boolean isShop(ContainerChest chest) {
        String title = Utils.stripColor(chest.getLowerChestInventory().getDisplayName().getUnformattedText()).toLowerCase();
        return title.contains("item shop") || title.contains("quick buy") || title.contains("shop") || title.contains("upgrades");
    }

    private long nextDelay() {
        long base;
        int target = Math.max(1, (int)this.cps.getInput());
        long jitter = (long)((double)(base = (long)Math.max(1, 1000 / target)) * 0.2);
        return Math.max(1L, base + (jitter == 0L ? 0L : this.rand.nextLong() % jitter));
    }

    private int countPaymentItems(InventoryItemListSetting costList) {
        int total = 0;
        for (int i = 0; i < AutoShop.mc.thePlayer.inventory.getSizeInventory(); ++i) {
            ItemStack stack = AutoShop.mc.thePlayer.inventory.getStackInSlot(i);
            if (stack == null) continue;
            for (String storageId : costList.getItems()) {
                if (!ItemSearchIndex.matches(storageId, stack)) continue;
                total += stack.stackSize;
            }
        }
        return total;
    }

    private static class ActiveBuy {
        final InventoryItemListSetting items;
        final InventoryItemListSetting costItems;

        ActiveBuy(InventoryItemListSetting items, InventoryItemListSetting costItems) {
            this.items = items;
            this.costItems = costItems;
        }
    }
}

