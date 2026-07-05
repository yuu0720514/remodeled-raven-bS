package keystrokesmod.module.impl.player;

import keystrokesmod.event.ClientRotationEvent;
import keystrokesmod.event.PostMotionEvent;
import keystrokesmod.event.PreMotionEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ColorSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BedDefenseUtils;
import keystrokesmod.utility.BedDefenseUtils.BedRef;
import keystrokesmod.utility.BedDefenseUtils.DefBlock;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.util.Arrays;

public class BedDefender extends Module {
    private static final DefBlock[] BUTTERFLY_LAYOUT = {

        new DefBlock(-2, 0,  0, null),
        new DefBlock( 2, 0,  0, null),
        new DefBlock(-2, 0,  1, null),
        new DefBlock( 2, 0,  1, null),
        new DefBlock( 0, 0, -1, null),
        new DefBlock( 0, 0,  2, null),
        new DefBlock(-1, 0,  0, null),
        new DefBlock( 1, 0,  0, null),
        new DefBlock(-1, 0,  1, null),
        new DefBlock( 1, 0,  1, null),
        new DefBlock( 0, 1,  0, null),
        new DefBlock( 0, 1,  1, null),
    };
    private static final DefBlock[] WOOL_LAYOUT = {
        new DefBlock( 0, 0, -1, null),
        new DefBlock( 0, 0,  2, null),
        new DefBlock(-1, 0,  0, null),
        new DefBlock( 1, 0,  0, null),
        new DefBlock(-1, 0,  1, null),
        new DefBlock( 1, 0,  1, null),
    };


    private static final DefBlock[] WOOL_EXTRA_LAYOUT = {

        new DefBlock( 0, 0, -2, null),
        new DefBlock(-1, 0, -1, null),
        new DefBlock( 1, 0, -1, null),
        new DefBlock(-1, 0,  2, null),
        new DefBlock( 1, 0,  2, null),
        new DefBlock( 0, 0,  3, null),
        new DefBlock(-1, 1, -1, null),
        new DefBlock( 0, 1, -1, null),
        new DefBlock( 1, 1, -1, null),
        new DefBlock(-1, 1,  0, null),
        new DefBlock( 1, 1,  0, null),
        new DefBlock(-1, 1,  1, null),
        new DefBlock( 1, 1,  1, null),
        new DefBlock(-1, 1,  2, null),
        new DefBlock( 0, 1,  2, null),
        new DefBlock( 1, 1,  2, null),
        new DefBlock( 0, 2,  0, null),
        new DefBlock( 0, 2,  1, null),
    };

    private static final double SEARCH_RANGE = 6.0;

    private final ColorSetting highlightColor;
    private final SliderSetting placeDelay;
    private final SliderSetting fov;

    private boolean silentActive;
    private float silentViewYaw;
    private float silentViewPitch;
    private float targetAimYaw;
    private float targetAimPitch;

    private PendingPlace queuedPlace;
    private BedRef activeBed;
    private int previousSlot = -1;
    private BlockPos lastFailedPos;
    private long lastFailedMs;
    private long lastPlaceTime;

    public BedDefender() {
        super("Bed Defender", Module.category.player);
        this.registerSetting(highlightColor = new ColorSetting("Highlight Color", 255, 255, 0, 80));
        this.registerSetting(placeDelay = new SliderSetting("Place Delay (ms)", 250, 50, 1000, 1));
        this.registerSetting(fov = new SliderSetting("FOV", 180, 30, 180, 5));
    }

    @Override
    public String getInfo() {
        if (!Utils.nullCheck()) return "";
        int es = countInventoryBlock(Blocks.end_stone);
        int wool = countInventoryBlock(Blocks.wool);
        BedRef bed = BedDefenseUtils.findNearestBed(SEARCH_RANGE);
        if (bed == null) return "no bed";
        int rem = countRemainingPositions(bed, es, wool);
        return "ES:" + es + " W:" + wool + " rem:" + rem;
    }

    @Override
    public void onEnable() {
        this.silentActive = false;
        this.queuedPlace = null;
        this.activeBed = null;
        this.previousSlot = -1;
        this.lastFailedPos = null;
        this.lastFailedMs = 0L;
    }

    @Override
    public void onDisable() {
        if (this.previousSlot != -1 && Utils.nullCheck()) {
            BedDefender.mc.thePlayer.inventory.currentItem = this.previousSlot;
            BedDefender.mc.playerController.updateController();
        }
        this.clearSilentState();
        this.previousSlot = -1;
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent e) {
        this.silentActive = false;
        this.queuedPlace = null;
        this.activeBed = null;

        if (!this.isEnabled()
                || !Utils.nullCheck()
                || BedDefender.mc.currentScreen != null
                || BedDefender.mc.thePlayer.capabilities.isFlying) {
            return;
        }

        BedRef bed = BedDefenseUtils.findNearestBed(SEARCH_RANGE);
        if (bed == null) return;

        PendingPlace pending = findNextPlacement(bed);
        if (pending == null) {
            return;
        }

        this.silentViewYaw = BedDefender.mc.thePlayer.rotationYaw;
        this.silentViewPitch = BedDefender.mc.thePlayer.rotationPitch;

        e.setYaw(Float.valueOf(pending.place.aimYaw));
        e.setPitch(Float.valueOf(pending.place.aimPitch));

        this.targetAimYaw = pending.place.aimYaw;
        this.targetAimPitch = pending.place.aimPitch;
        this.activeBed = bed;
        this.queuedPlace = pending;
        this.silentActive = true;
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPreMotion(PreMotionEvent e) {
        if (!this.silentActive) return;
        RotationUtils.setFakeRotations(this.silentViewYaw, this.silentViewPitch);
    }


    @SubscribeEvent
    public void onPostMotion(PostMotionEvent e) {
        if (!Utils.nullCheck()
                || BedDefender.mc.currentScreen != null
                || BedDefender.mc.thePlayer.capabilities.isFlying) {
            return;
        }
        if (!this.silentActive || this.queuedPlace == null || this.activeBed == null) {
            return;
        }

        BedDefender.mc.thePlayer.rotationYawHead = this.targetAimYaw;

        if (!canPlaceAt(this.queuedPlace.target)) return;

        if (System.currentTimeMillis() - this.lastPlaceTime < (long) this.placeDelay.getInput()) return;

        boolean placed = placeBlock(this.queuedPlace);
        if (placed) {
            this.lastPlaceTime = System.currentTimeMillis();
        } else {
            this.lastFailedPos = this.queuedPlace.target;
            this.lastFailedMs = System.currentTimeMillis();
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent e) {
        if (!Utils.nullCheck() || !this.silentActive || this.queuedPlace == null) return;
        RenderUtils.renderBlock(this.queuedPlace.target, highlightColor.getColor(), true, true);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        BedRef bed = BedDefenseUtils.findNearestBed(SEARCH_RANGE);
        if (bed == null) return;

        int total = BUTTERFLY_LAYOUT.length + WOOL_EXTRA_LAYOUT.length;
        int placed = 0;
        for (DefBlock d : BUTTERFLY_LAYOUT) {
            BlockPos p = BedDefenseUtils.toWorld(bed, d);
            if (!BlockUtils.replaceable(p)) placed++;
        }
        for (DefBlock d : WOOL_EXTRA_LAYOUT) {
            BlockPos p = BedDefenseUtils.toWorld(bed, d);
            if (!BlockUtils.replaceable(p)) placed++;
        }

        int pct = total > 0 ? (placed * 100 / total) : 0;
        if (pct >= 100) return;
        String text = pct + "%";
        ScaledResolution sr = new ScaledResolution(BedDefender.mc);
        int x = sr.getScaledWidth() / 2 - BedDefender.mc.fontRendererObj.getStringWidth(text) / 2;
        int y = sr.getScaledHeight() / 2 + 12;
        BedDefender.mc.fontRendererObj.drawStringWithShadow(text, x, y, 0xFFFFFFFF);
    }



    private PendingPlace findNextPlacement(BedRef bed) {
        int esCount = countInventoryBlock(Blocks.end_stone);
        int woolCount = countInventoryBlock(Blocks.wool);

        if (esCount <= 0 && woolCount <= 0) return null;


        if (esCount > 0 || woolCount > 0) {
            PendingPlace p = tryLayout(bed, BUTTERFLY_LAYOUT, esCount, woolCount);
            if (p != null) return p;
        }


        if (woolCount > 0) {
            PendingPlace p = tryLayout(bed, WOOL_EXTRA_LAYOUT, 0, woolCount);
            if (p != null) return p;
        }

        return null;
    }

    private PendingPlace tryLayout(BedRef bed, DefBlock[] layout, int esAvail, int woolAvail) {

        Vec3 eye = BedDefender.mc.thePlayer.getPositionEyes(1.0f);


        java.util.List<DefBlock> sorted = new java.util.ArrayList<>(java.util.Arrays.asList(layout));
        sorted.sort((a, b) -> {
            BlockPos pa = BedDefenseUtils.toWorld(bed, a);
            BlockPos pb = BedDefenseUtils.toWorld(bed, b);
            double da = eye.squareDistanceTo(new Vec3(pa.getX() + 0.5, pa.getY() + 0.5, pa.getZ() + 0.5));
            double db = eye.squareDistanceTo(new Vec3(pb.getX() + 0.5, pb.getY() + 0.5, pb.getZ() + 0.5));
            return Double.compare(db, da);
        });

        for (DefBlock defBlock : sorted) {
            BlockPos target = BedDefenseUtils.toWorld(bed, defBlock);

            if (!canPlaceAt(target)) continue;

            if (target.equals(this.lastFailedPos)
                    && System.currentTimeMillis() - this.lastFailedMs < 200L) {
                continue;
            }

            PlaceTarget placeTarget = findPlaceTarget(target);
            if (placeTarget == null) continue;

            Block blockToUse;
            int slot;
            if (esAvail > 0) {
                slot = findHotbarSlot(Blocks.end_stone);
                blockToUse = Blocks.end_stone;
                if (slot == -1 && woolAvail > 0) {
                    slot = findHotbarSlot(Blocks.wool);
                    blockToUse = Blocks.wool;
                }
            } else {
                slot = findHotbarSlot(Blocks.wool);
                blockToUse = Blocks.wool;
            }
            if (slot == -1) continue;

            return new PendingPlace(target, slot, placeTarget, blockToUse);
        }
        return null;
    }

    private DefBlock[] selectLayout(int esCount, int woolCount) {
        if (esCount > 0) return BUTTERFLY_LAYOUT;
        if (woolCount > 0) return WOOL_LAYOUT;
        return null;
    }

    private int countRemainingPositions(BedRef bed, int esCount, int woolCount) {
        int count = 0;
        DefBlock[] main = selectLayout(esCount, woolCount);
        if (main != null) {
            for (DefBlock d : main) {
                if (canPlaceAt(BedDefenseUtils.toWorld(bed, d))) count++;
            }
        }
        if (woolCount > 0) {
            for (DefBlock d : WOOL_EXTRA_LAYOUT) {
                if (canPlaceAt(BedDefenseUtils.toWorld(bed, d))) count++;
            }
        }
        return count;
    }



    private int countInventoryBlock(Block target) {
        int count = 0;
        for (int i = 0; i < InventoryPlayer.getHotbarSize(); i++) {
            ItemStack stack = BedDefender.mc.thePlayer.inventory.getStackInSlot(i);
            if (stack == null || !(stack.getItem() instanceof ItemBlock)) continue;
            if (((ItemBlock) stack.getItem()).getBlock() == target) {
                count += stack.stackSize;
            }
        }
        return count;
    }

    private int findHotbarSlot(Block target) {
        for (int i = 0; i < InventoryPlayer.getHotbarSize(); i++) {
            ItemStack stack = BedDefender.mc.thePlayer.inventory.getStackInSlot(i);
            if (stack == null || !(stack.getItem() instanceof ItemBlock)) continue;
            if (((ItemBlock) stack.getItem()).getBlock() == target) return i;
        }
        return -1;
    }

    private boolean canPlaceAt(BlockPos pos) {
        return pos != null && BedDefender.mc.theWorld != null && BlockUtils.replaceable(pos);
    }

    private PlaceTarget findPlaceTarget(BlockPos target) {
        Vec3 eye = BedDefender.mc.thePlayer.getPositionEyes(1.0f);
        double reach = BedDefender.mc.playerController.getBlockReachDistance();
        double reachSq = reach * reach;
        float curYaw = RotationUtils.serverRotations[0];
        float curPitch = RotationUtils.serverRotations[1];
        float playerYaw = BedDefender.mc.thePlayer.rotationYaw;
        float playerPitch = BedDefender.mc.thePlayer.rotationPitch;
        float maxFov = (float) fov.getInput();

        final int[] sdx = {0, 0, 0, 0, 1, -1};
        final int[] sdy = {1, -1, 0, 0, 0, 0};
        final int[] sdz = {0, 0, -1, 1, 0, 0};
        final EnumFacing[] sides = {
            EnumFacing.DOWN, EnumFacing.UP,
            EnumFacing.SOUTH, EnumFacing.NORTH,
            EnumFacing.WEST, EnumFacing.EAST
        };

        final double INSET = 0.05;
        final double STEP  = 0.25;

        PlaceTarget best = null;
        double bestCost = Double.POSITIVE_INFINITY;

        for (int fi = 0; fi < 6; fi++) {
            BlockPos support = new BlockPos(
                    target.getX() + sdx[fi],
                    target.getY() + sdy[fi],
                    target.getZ() + sdz[fi]);

            if (BlockUtils.replaceable(support)) continue;
            IBlockState state = BedDefender.mc.theWorld.getBlockState(support);
            if (state == null || state.getBlock() == Blocks.air) continue;

            EnumFacing side = sides[fi];
            boolean isBed = state.getBlock() instanceof BlockBed;
            if (isBed && side != EnumFacing.UP) continue;

            double faceCoord;
            switch (side) {
                case UP:    faceCoord = support.getY() + 1.0 - INSET; break;
                case DOWN:  faceCoord = support.getY() + INSET;        break;
                case SOUTH: faceCoord = support.getZ() + 1.0 - INSET; break;
                case NORTH: faceCoord = support.getZ() + INSET;        break;
                case EAST:  faceCoord = support.getX() + 1.0 - INSET; break;
                default:    faceCoord = support.getX() + INSET;        break; // WEST
            }

            for (double u = INSET; u < 1.0 - INSET + 1e-6; u += STEP) {
                for (double v = INSET; v < 1.0 - INSET + 1e-6; v += STEP) {
                    double px, py, pz;
                    switch (side) {
                        case UP: case DOWN:
                            px = support.getX() + u;
                            pz = support.getZ() + v;
                            py = faceCoord;
                            break;
                        case NORTH: case SOUTH:
                            px = support.getX() + u;
                            py = support.getY() + v;
                            pz = faceCoord;
                            break;
                        default: // EAST, WEST
                            pz = support.getZ() + u;
                            py = support.getY() + v;
                            px = faceCoord;
                            break;
                    }

                    Vec3 hit = new Vec3(px, py, pz);
                    if (eye.squareDistanceTo(hit) > reachSq) continue;

                    float[] rot = RotationUtils.getRotationsToPoint(px, py, pz, curYaw, curPitch);

                    float dyaw = (rot[0] - playerYaw) % 360f;
                    if (dyaw < -180f) dyaw += 360f;
                    if (dyaw > 180f) dyaw -= 360f;
                    if (Math.abs(dyaw) > maxFov) continue;
                    if (Math.abs(rot[1] - playerPitch) > Math.min(maxFov, 90f)) continue;

                    MovingObjectPosition mop = RotationUtils.rayCastBlock(reach, rot[0], rot[1]);
                    if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) continue;
                    if (!mop.getBlockPos().equals(support)) continue;

                    double cost = Math.abs(rot[0] - curYaw) + Math.abs(rot[1] - curPitch);
                    if (side == EnumFacing.UP) cost -= 0.25;

                    if (cost < bestCost) {
                        bestCost = cost;
                        best = new PlaceTarget(support, side, hit, rot[0], rot[1]);
                    }
                }
            }
        }
        return best;
    }

    private boolean placeBlock(PendingPlace pending) {
        if (this.previousSlot == -1) {
            this.previousSlot = BedDefender.mc.thePlayer.inventory.currentItem;
        }
        if (BedDefender.mc.thePlayer.inventory.currentItem != pending.slot) {
            BedDefender.mc.thePlayer.inventory.currentItem = pending.slot;
            BedDefender.mc.playerController.updateController();
        }
        ItemStack stack = BedDefender.mc.thePlayer.getHeldItem();
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) return false;

        boolean wasSneaking = BedDefender.mc.thePlayer.isSneaking();
        if (!wasSneaking) {
            BedDefender.mc.thePlayer.setSneaking(true);
            BedDefender.mc.thePlayer.sendQueue.addToSendQueue(
                new C0BPacketEntityAction(BedDefender.mc.thePlayer,
                    C0BPacketEntityAction.Action.START_SNEAKING));
        }
        boolean result = BedDefender.mc.playerController.onPlayerRightClick(
                BedDefender.mc.thePlayer, BedDefender.mc.theWorld,
                stack, pending.place.support, pending.place.side, pending.place.hitVec);
        if (!wasSneaking) {
            BedDefender.mc.thePlayer.setSneaking(false);
            BedDefender.mc.thePlayer.sendQueue.addToSendQueue(
                new C0BPacketEntityAction(BedDefender.mc.thePlayer,
                    C0BPacketEntityAction.Action.STOP_SNEAKING));
        }

        if (result) {
            BedDefender.mc.thePlayer.swingItem();
        }
        return result;
    }

    public boolean isSilentlyPlacing() {
        return this.silentActive;
    }

    public double getDefenseRange() {
        return SEARCH_RANGE;
    }

    public java.util.List<DefBlock> getAllowedDefenseBlocks() {
        int es = countInventoryBlock(Blocks.end_stone);
        int wool = countInventoryBlock(Blocks.wool);
        DefBlock[] main = selectLayout(es, wool);
        java.util.List<DefBlock> list = new java.util.ArrayList<>();
        if (main != null) {
            for (DefBlock d : main) list.add(d);
        }
        if (wool > 0) {
            for (DefBlock d : WOOL_EXTRA_LAYOUT) list.add(d);
        }
        return list;
    }

    private void clearSilentState() {
        this.silentActive = false;
        this.queuedPlace = null;
        this.activeBed = null;
        RotationUtils.setFakeRotations = false;
    }


    private static class PendingPlace {
        final BlockPos target;
        final int slot;
        final PlaceTarget place;
        final Block blockType;

        PendingPlace(BlockPos target, int slot, PlaceTarget place, Block blockType) {
            this.target = target;
            this.slot = slot;
            this.place = place;
            this.blockType = blockType;
        }
    }

    private static class PlaceTarget {
        final BlockPos support;
        final EnumFacing side;
        final Vec3 hitVec;
        final float aimYaw;
        final float aimPitch;

        PlaceTarget(BlockPos support, EnumFacing side, Vec3 hitVec, float aimYaw, float aimPitch) {
            this.support = support;
            this.side = side;
            this.hitVec = hitVec;
            this.aimYaw = aimYaw;
            this.aimPitch = aimPitch;
        }
    }
}
