package keystrokesmod.module.impl.player;

import java.util.ArrayList;
import java.util.List;
import keystrokesmod.event.RightClickMouseEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BedDefenseUtils;
import keystrokesmod.utility.BedDefenseUtils.BedRef;
import keystrokesmod.utility.BedDefenseUtils.DefBlock;
import keystrokesmod.utility.Utils;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class MisAntiPlace extends Module {
    private static final int PRESET_COUNT = 5;
    private static final String[] PRESET_OPTIONS = new String[] { "Preset 1", "Preset 2", "Preset 3", "Preset 4", "Preset 5" };

    private final SliderSetting range;
    private final SliderSetting preset;
    private final ButtonSetting recordMode;
    private final ButtonSetting clearPreset;
    private final List<DefBlock>[] presets = new ArrayList[PRESET_COUNT];

    public MisAntiPlace() {
        super("Anti Misplace", Module.category.player);
        for (int i = 0; i < PRESET_COUNT; ++i) {
            this.presets[i] = new ArrayList<DefBlock>();
        }
        this.registerSetting(new DescriptionSetting("Only allow bed defense placements at recorded positions."));
        this.range = new SliderSetting("Range", " blocks", 8.0, 2.0, 12.0, 0.5);
        this.registerSetting(this.range);
        this.preset = new SliderSetting("Preset", 0, PRESET_OPTIONS);
        this.registerSetting(this.preset);
        this.recordMode = new ButtonSetting("Record mode", false);
        this.registerSetting(this.recordMode);
        this.clearPreset = new ButtonSetting("Clear preset", () -> {
            this.getSelectedPreset().clear();
            Utils.sendMessage("&aAnti Misplace cleared preset " + (this.getSelectedPresetIndex() + 1) + ".");
        });
        this.registerSetting(this.clearPreset);
    }

    @Override
    public void guiUpdate() {
    }

    @Override
    public void onEnable() {
        if (this.recordMode.isToggled()) {
            Utils.sendMessage("&aAnti Misplace recording to preset " + (this.getSelectedPresetIndex() + 1) + ".");
        }
    }

    @Override
    public void onDisable() {
        if (this.recordMode.isToggled()) {
            Utils.sendMessage("&aAnti Misplace saved " + this.getSelectedPreset().size() + " allowed positions for preset " + (this.getSelectedPresetIndex() + 1) + ".");
        }
    }

    @Override
    public String getInfo() {
        if (this.recordMode.isToggled()) {
            return "Record";
        }
        return String.valueOf(this.getSelectedPreset().size());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onRightClickMouse(RightClickMouseEvent event) {
        if (this.shouldCancelPlacement(null)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onSendPacket(SendPacketEvent event) {
        if (!(event.getPacket() instanceof C08PacketPlayerBlockPlacement)) {
            return;
        }
        C08PacketPlayerBlockPlacement packet = (C08PacketPlayerBlockPlacement) event.getPacket();
        if (packet.getPlacedBlockDirection() == 255 || packet.getPosition() == null) {
            return;
        }
        EnumFacing side = EnumFacing.getFront(packet.getPlacedBlockDirection());
        BlockPos placedPos = packet.getPosition().offset(side);

        if (this.recordMode.isToggled() && this.isEnabled() && Utils.nullCheck()) {
            this.recordPlacement(placedPos);
        }
        if (this.shouldCancelPlacement(placedPos)) {
            event.setCanceled(true);
        }
    }

    private boolean shouldCancelPlacement(BlockPos packetPlacedPos) {
        if (!this.isEnabled() || !Utils.nullCheck() || MisAntiPlace.mc.currentScreen != null) {
            return false;
        }
        if (MisAntiPlace.mc.thePlayer.capabilities.isFlying || this.isOtherModulePlacing()) {
            return false;
        }
        if (this.recordMode.isToggled()) {
            return false;
        }
        if (!this.isHoldingPlaceableBlock()) {
            return false;
        }

        BedRef bed = BedDefenseUtils.findNearestBed(this.range.getInput());
        if (bed == null) {
            return false;
        }

        List<DefBlock> activeBlocks = this.getSelectedPreset();
        if (activeBlocks.isEmpty()) {
            return false;
        }

        BlockPos placedPos = packetPlacedPos != null ? packetPlacedPos : this.getIntendedPlacementPos();
        if (placedPos == null) {
            return false;
        }

        DefBlock targetRel = BedDefenseUtils.toRelative(bed, placedPos);

        DefBlock matchingRecorded = null;
        for (DefBlock db : activeBlocks) {
            if (BedDefenseUtils.matches(db, targetRel)) {
                matchingRecorded = db;
                break;
            }
        }

        if (matchingRecorded == null) {
            return false;
        }

        ItemStack held = MisAntiPlace.mc.thePlayer.getHeldItem();
        net.minecraft.block.Block heldBlock = (held != null && held.getItem() instanceof ItemBlock)
                ? ((ItemBlock) held.getItem()).getBlock()
                : null;

        if (matchingRecorded.block == null || matchingRecorded.block == heldBlock) {
            return false;
        }

        return true;
    }

    private void recordPlacement(BlockPos placedPos) {
        BedRef bed = BedDefenseUtils.findNearestBed(this.range.getInput());
        if (bed == null) {
            return;
        }
        DefBlock block = BedDefenseUtils.toRelative(bed, placedPos);
        ItemStack held = MisAntiPlace.mc.thePlayer.getHeldItem();
        if (held != null && held.getItem() instanceof ItemBlock) {
            block.block = ((ItemBlock) held.getItem()).getBlock();
        }

        List<DefBlock> selected = this.getSelectedPreset();
        for (DefBlock existing : selected) {
            if (BedDefenseUtils.matches(existing, block)) {
                existing.block = block.block;
                return;
            }
        }
        selected.add(block);
        Utils.sendMessage("&aAnti Misplace recorded #" + selected.size() + " at " + block.right + "," + block.up + "," + block.forward);
    }

    private BlockPos getIntendedPlacementPos() {
        if (MisAntiPlace.mc.objectMouseOver == null
                || MisAntiPlace.mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return null;
        }
        return MisAntiPlace.mc.objectMouseOver.getBlockPos().offset(MisAntiPlace.mc.objectMouseOver.sideHit);
    }

    private boolean isHoldingPlaceableBlock() {
        ItemStack held = MisAntiPlace.mc.thePlayer.getHeldItem();
        return held != null && held.getItem() instanceof ItemBlock;
    }

    private boolean isOtherModulePlacing() {
        if (ModuleManager.bedDefender != null && ModuleManager.bedDefender.isSilentlyPlacing()) {
            return true;
        }
        return ModuleManager.blockIn != null && ModuleManager.blockIn.isPlacing();
    }

    private int getSelectedPresetIndex() {
        if (this.preset == null) {
            return 0;
        }
        return Math.max(0, Math.min(PRESET_COUNT - 1, (int) this.preset.getInput()));
    }

    private List<DefBlock> getSelectedPreset() {
        return this.presets[this.getSelectedPresetIndex()];
    }
}