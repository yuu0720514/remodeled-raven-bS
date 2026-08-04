package keystrokesmod.module.impl.player;

import keystrokesmod.event.ClientRotationEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.helper.RotationHelper;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.KeySetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.opengl.GL11;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class BlockLadder extends Module {

    private static final double REACH = 4.5;
    private static final double HIT_INSET = 0.05;
    private static final double LADDER_ROT_TOL = 35.0;
    private static final int MAX_PREDICT_TICKS = 120;
    private static final int MAX_ATTACH_WAIT = 8;
    private static final int MAX_LADDER_ATTEMPTS = 20;
    private static final EnumFacing[] HORIZONTALS = {
            EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST
    };

    private final KeySetting activationKey;
    private final SliderSetting minFallDistance;
    private final ButtonSetting placeBlockSetting;
    private final ButtonSetting renderPreview;

    private enum Phase { IDLE, PLACE_BLOCK, PLACE_LADDER, GRAB }

    private Phase phase = Phase.IDLE;

    private float aimYaw;
    private float aimPitch;

    private int prevSlot = -1;
    private int blockSlot = -1;
    private int ladderSlot = -1;

    private BlockPos targetBlockPos;
    private BlockPos ladderAttachPos;
    private EnumFacing ladderFace;

    private BlockPos placeAtBlock;
    private EnumFacing placeSide;
    private Vec3 placeHitVec;
    private boolean placeQueued;

    private int aimTicks;
    private int grabTicks;
    private static final int MAX_GRAB_TICKS = 40;

    private PredictedPlan lockedPlan;
    private PredictedPlan previewPlan;
    private BlockPos lockedLadderSpace;
    private boolean blockPlaced;
    private int ladderPlaceAttempts;
    private int attachWaitTicks;

    private long lastBlockPlaceTime = 0L;
    private static final long LADDER_DELAY_MS = 50L;

    public BlockLadder() {
        super("BlockLadder", Module.category.player);
        this.registerSetting(activationKey = new KeySetting("Activation key", 0));
        this.registerSetting(minFallDistance = new SliderSetting("Min fall distance", " blocks", 1.5, 0.5, 20.0, 0.5));
        this.registerSetting(placeBlockSetting = new ButtonSetting("Place block", true));
        this.registerSetting(renderPreview = new ButtonSetting("Render preview", true));
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        finish();
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent e) {
        if (!Utils.nullCheck()) return;
        if (mc.currentScreen != null || !activationKey.isPressed()) {
            if (phase != Phase.IDLE) finish();
            return;
        }
        if (mc.thePlayer.capabilities.isFlying || mc.thePlayer.capabilities.isCreativeMode) return;

        if (phase == Phase.IDLE) {
            tryStart();
        }

        if (phase == Phase.PLACE_BLOCK || phase == Phase.PLACE_LADDER) {
            updateTarget();
            updateAimAndQueue(e);
        }
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (!Utils.nullCheck()) return;
        if (mc.currentScreen != null || !activationKey.isPressed()) {
            previewPlan = null;
            return;
        }
        if (mc.thePlayer.capabilities.isFlying || mc.thePlayer.capabilities.isCreativeMode) {
            previewPlan = null;
            return;
        }
        if (phase == Phase.IDLE) {
            findSlots();
            previewPlan = findBestPredictedPlan(true);
        } else {
            previewPlan = null;
        }

        if (placeQueued) {
            placeQueued = false;
            executePlace();
        }

        if (phase == Phase.GRAB) {
            handleGrab();
        } else if (phase == Phase.PLACE_LADDER && isTargetBlockPlaced() && !isLadderPlaced()) {
            tryPlaceLadderImmediate();
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent e) {
        if (!renderPreview.isToggled()) return;
        if (!Utils.nullCheck()) return;
        if (!activationKey.isPressed()) return;
        if (mc.thePlayer.capabilities.isFlying || mc.thePlayer.capabilities.isCreativeMode) return;

        BlockPos previewBlock = targetBlockPos;
        BlockPos previewLadderSpace = lockedLadderSpace;
        BlockPos previewAttach = ladderAttachPos;
        EnumFacing previewFace = ladderFace;

        if (phase == Phase.IDLE) {
            if (previewPlan != null) {
                previewBlock = previewPlan.plan.blockPos;
                previewAttach = previewPlan.plan.blockPos;
                previewFace = previewPlan.plan.ladderFace;
                previewLadderSpace = previewPlan.ladderSpace;
            } else {
                return;
            }
        }

        if (previewBlock == null || previewLadderSpace == null || previewAttach == null || previewFace == null) {
            return;
        }

        double viewerX = mc.getRenderManager().viewerPosX;
        double viewerY = mc.getRenderManager().viewerPosY;
        double viewerZ = mc.getRenderManager().viewerPosZ;

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GL11.glLineWidth(2.0F);
        RenderHelper.disableStandardItemLighting();

        float[] blockColor = getPreviewBlockColor();
        drawFilledBox(previewBlock, viewerX, viewerY, viewerZ, blockColor[0], blockColor[1], blockColor[2], 0.4F);
        drawFilledBox(previewLadderSpace, viewerX, viewerY, viewerZ, 1.0F, 0.8F, 0.1F, 0.4F);

        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    private float[] getPreviewBlockColor() {
        if (blockSlot >= 0 && blockSlot <= 8) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[blockSlot];
            if (stack != null && stack.getItem() instanceof ItemBlock) {
                Block b = ((ItemBlock) stack.getItem()).getBlock();
                if (b != null) {
                    MapColor mapColor = b.getMaterial().getMaterialMapColor();
                    if (mapColor != null) {
                        int colorValue = mapColor.colorValue;
                        float r = (colorValue >> 16 & 255) / 255.0F;
                        float g = (colorValue >> 8 & 255) / 255.0F;
                        float bVal = (colorValue & 255) / 255.0F;
                        return new float[]{r, g, bVal};
                    }
                }
            }
        }
        return new float[]{0.9F, 0.2F, 0.2F};
    }

    private void drawFilledBox(BlockPos pos, double viewerX, double viewerY, double viewerZ, float r, float g, float b, float a) {
        double x = pos.getX() - viewerX;
        double y = pos.getY() - viewerY;
        double z = pos.getZ() - viewerZ;
        AxisAlignedBB bb = new AxisAlignedBB(x, y, z, x + 1.0, y + 1.0, z + 1.0);

        GlStateManager.color(r, g, b, a);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldrenderer = tessellator.getWorldRenderer();

        worldrenderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        worldrenderer.pos(bb.minX, bb.maxY, bb.minZ).endVertex();
        worldrenderer.pos(bb.maxX, bb.maxY, bb.minZ).endVertex();
        worldrenderer.pos(bb.maxX, bb.minY, bb.minZ).endVertex();
        worldrenderer.pos(bb.minX, bb.minY, bb.minZ).endVertex();

        worldrenderer.pos(bb.minX, bb.minY, bb.maxZ).endVertex();
        worldrenderer.pos(bb.maxX, bb.minY, bb.maxZ).endVertex();
        worldrenderer.pos(bb.maxX, bb.maxY, bb.maxZ).endVertex();
        worldrenderer.pos(bb.minX, bb.maxY, bb.maxZ).endVertex();

        worldrenderer.pos(bb.minX, bb.minY, bb.minZ).endVertex();
        worldrenderer.pos(bb.maxX, bb.minY, bb.minZ).endVertex();
        worldrenderer.pos(bb.maxX, bb.minY, bb.maxZ).endVertex();
        worldrenderer.pos(bb.minX, bb.minY, bb.maxZ).endVertex();

        worldrenderer.pos(bb.minX, bb.maxY, bb.maxZ).endVertex();
        worldrenderer.pos(bb.maxX, bb.maxY, bb.maxZ).endVertex();
        worldrenderer.pos(bb.maxX, bb.maxY, bb.minZ).endVertex();
        worldrenderer.pos(bb.minX, bb.maxY, bb.minZ).endVertex();

        worldrenderer.pos(bb.minX, bb.minY, bb.maxZ).endVertex();
        worldrenderer.pos(bb.minX, bb.maxY, bb.maxZ).endVertex();
        worldrenderer.pos(bb.minX, bb.maxY, bb.minZ).endVertex();
        worldrenderer.pos(bb.minX, bb.minY, bb.minZ).endVertex();

        worldrenderer.pos(bb.maxX, bb.minY, bb.minZ).endVertex();
        worldrenderer.pos(bb.maxX, bb.maxY, bb.minZ).endVertex();
        worldrenderer.pos(bb.maxX, bb.maxY, bb.maxZ).endVertex();
        worldrenderer.pos(bb.maxX, bb.minY, bb.maxZ).endVertex();
        tessellator.draw();
    }

    private void tryStart() {
        if (mc.thePlayer.onGround) return;
        if (mc.thePlayer.fallDistance < (float) minFallDistance.getInput()) return;

        findSlots();
        if (ladderSlot == -1) return;
        PredictedPlan prediction = findBestPredictedPlan(false);
        if (prediction == null) return;

        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        if (!canReachBlockFromEye(eye, prediction.plan.blockPos, blockSlot >= 0 ? mc.thePlayer.inventory.mainInventory[blockSlot] : null)
                && prediction.interceptTick > 8) {
            return;
        }

        lockedPlan = prediction;
        lockedLadderSpace = prediction.ladderSpace;
        blockPlaced = false;
        ladderPlaceAttempts = 0;
        attachWaitTicks = 0;
        applyPlan(prediction);
        prevSlot = mc.thePlayer.inventory.currentItem;
        aimTicks = 0;

        if (placeBlockSetting.isToggled() && blockSlot != -1 && BlockUtils.replaceable(targetBlockPos)) {
            phase = Phase.PLACE_BLOCK;
        } else {
            phase = Phase.PLACE_LADDER;
        }
    }

    private void updateTarget() {
        findSlots();
        if (lockedPlan != null && isLockedPlanStillValid()) {
            applyPlan(lockedPlan);
            return;
        }
        PredictedPlan prediction = findBestPredictedPlan(false);
        if (prediction != null) {
            lockedPlan = prediction;
            lockedLadderSpace = prediction.ladderSpace;
            applyPlan(prediction);
        }
    }

    private boolean isLockedPlanStillValid() {
        if (lockedPlan == null || lockedLadderSpace == null) return false;
        if (phase != Phase.PLACE_BLOCK) return true;
        ItemStack blockStack = blockSlot >= 0 ? mc.thePlayer.inventory.mainInventory[blockSlot] : null;
        ItemStack ladderStack = ladderSlot >= 0 ? mc.thePlayer.inventory.mainInventory[ladderSlot] : null;
        return isValidLandingPlan(lockedPlan.plan.blockPos, lockedPlan.plan.ladderFace, lockedLadderSpace, blockStack, ladderStack);
    }

    private void applyPlan(PredictedPlan prediction) {
        targetBlockPos = prediction.plan.blockPos;
        ladderAttachPos = prediction.plan.blockPos;
        ladderFace = prediction.plan.ladderFace;
        lockedLadderSpace = prediction.ladderSpace;
    }

    private PredictedPlan findBestPredictedPlan(boolean isPreview) {
        ItemStack blockStack = blockSlot >= 0 ? mc.thePlayer.inventory.mainInventory[blockSlot] : null;
        ItemStack ladderStack = ladderSlot >= 0 ? mc.thePlayer.inventory.mainInventory[ladderSlot] : null;
        if (ladderStack == null) return null;

        LandingPrediction landing = predictLanding(isPreview);
        if (landing != null && landing.column != null) {
            PredictedPlan plan = findPlanAtLanding(landing, blockStack, ladderStack, isPreview);
            if (plan != null) {
                return plan;
            }
        }

        FallPrediction currentState = FallPrediction.fromPlayer(isPreview);
        List<BlockPos> currentColumns = getLandingColumns(currentState);
        for (BlockPos col : currentColumns) {
            Set<EnumFacing> blocked = getSolidSidesAroundColumn(col);
            PredictedPlan plan = findPlanAtColumnFrontFirst(col, blockStack, ladderStack, blocked, isPreview);
            if (plan != null) {
                return plan;
            }
        }

        return null;
    }

    private PredictedPlan findPlanAtColumnFrontFirst(BlockPos landingColumn, ItemStack blockStack, ItemStack ladderStack, Set<EnumFacing> blockedSides, boolean isPreview) {
        EnumFacing forward = getPlayerForwardFacing();
        EnumFacing left = forward != null ? forward.rotateYCCW() : null;
        EnumFacing right = forward != null ? forward.rotateY() : null;
        EnumFacing backward = forward != null ? forward.getOpposite() : null;

        EnumFacing[] priority = forward != null ? new EnumFacing[]{ forward, left, right, backward } : HORIZONTALS;
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);

        for (EnumFacing side : priority) {
            if (side == null) continue;
            if (blockedSides.contains(side)) continue;
            BlockPos blockPos = landingColumn.offset(side);
            EnumFacing faceTowardPlayer = side.getOpposite();
            if (!isValidLandingPlan(blockPos, faceTowardPlayer, landingColumn, blockStack, ladderStack)) continue;
            if (!isPreview && !canReachBlockFromEye(eye, blockPos, blockStack)) continue;

            return new PredictedPlan(new AdjacentPlan(blockPos, faceTowardPlayer), 0, landingColumn, blockedSides);
        }
        return null;
    }

    private LandingPrediction predictLanding(boolean isPreview) {
        FallPrediction state = FallPrediction.fromPlayer(isPreview);
        boolean wasOnGround = state.onGround;

        for (int tick = 0; tick <= MAX_PREDICT_TICKS; tick++) {
            if (tick > 0) {
                boolean keepHorizontal = isPreview && wasOnGround && state.onGround;
                state.tick(keepHorizontal);
            }
            if (state.onGround && !wasOnGround) {
                List<BlockPos> columns = getLandingColumns(state);
                if (!columns.isEmpty()) {
                    return new LandingPrediction(columns.get(0), tick, state.motionX, state.motionZ);
                }
            }

            if (!state.onGround) {
                wasOnGround = false;
            }
        }

        List<BlockPos> columns = getLandingColumns(state);
        BlockPos baseCol = columns.isEmpty() ? null : columns.get(0);
        if (baseCol == null) return null;

        for (BlockPos col : columns) {
            for (int y = col.getY(); y >= Math.max(0, col.getY() - 128); y--) {
                BlockPos ground = new BlockPos(col.getX(), y, col.getZ());
                if (!BlockUtils.replaceable(ground)) {
                    return new LandingPrediction(ground, MAX_PREDICT_TICKS, state.motionX, state.motionZ);
                }
            }
        }
        return null;
    }

    private PredictedPlan findPlanAtLanding(LandingPrediction landing, ItemStack blockStack, ItemStack ladderStack, boolean isPreview) {
        BlockPos landingColumn = landing.column;
        Set<EnumFacing> blockedSides = getSolidSidesAroundColumn(landingColumn);

        EnumFacing motionForward = getDominantHorizontalFacing(landing.motionX, landing.motionZ);
        EnumFacing playerForward = getPlayerForwardFacing();
        EnumFacing forward = (Math.abs(landing.motionX) > 0.05 || Math.abs(landing.motionZ) > 0.05)
                ? motionForward : playerForward;

        EnumFacing left = forward != null ? forward.rotateYCCW() : null;
        EnumFacing right = forward != null ? forward.rotateY() : null;
        EnumFacing backward = forward != null ? forward.getOpposite() : null;

        EnumFacing[] priority = forward != null ? new EnumFacing[]{ forward, left, right, backward } : HORIZONTALS;
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);

        for (EnumFacing side : priority) {
            if (side == null) continue;
            if (blockedSides.contains(side)) continue;
            BlockPos blockPos = landingColumn.offset(side);
            EnumFacing faceTowardPlayer = side.getOpposite();
            BlockPos ladderSpace = landingColumn;
            if (!isValidLandingPlan(blockPos, faceTowardPlayer, ladderSpace, blockStack, ladderStack)) continue;
            if (!isPreview && !canReachBlockFromEye(eye, blockPos, blockStack)) continue;
            return new PredictedPlan(new AdjacentPlan(blockPos, faceTowardPlayer), landing.interceptTick, ladderSpace, blockedSides);
        }
        return null;
    }

    private EnumFacing getDominantHorizontalFacing(double motionX, double motionZ) {
        if (Math.abs(motionX) < 0.03 && Math.abs(motionZ) < 0.03) {
            float yaw = mc.thePlayer.rotationYaw;
            motionX = -MathHelper.sin(yaw * (float) Math.PI / 180.0F);
            motionZ = MathHelper.cos(yaw * (float) Math.PI / 180.0F);
        }
        if (Math.abs(motionX) >= Math.abs(motionZ)) {
            return motionX > 0.0 ? EnumFacing.EAST : EnumFacing.WEST;
        }
        return motionZ > 0.0 ? EnumFacing.SOUTH : EnumFacing.NORTH;
    }

    private EnumFacing getPlayerForwardFacing() {
        float yaw = mc.thePlayer.rotationYaw;
        yaw = yaw % 360.0f;
        if (yaw < 0) yaw += 360.0f;
        if (yaw < 45 || yaw >= 315) return EnumFacing.SOUTH;
        if (yaw < 135) return EnumFacing.WEST;
        if (yaw < 225) return EnumFacing.NORTH;
        return EnumFacing.EAST;
    }

    private Set<EnumFacing> getSolidSidesAroundColumn(BlockPos playerCol) {
        Set<EnumFacing> blocked = EnumSet.noneOf(EnumFacing.class);
        for (EnumFacing side : HORIZONTALS) {
            if (!BlockUtils.replaceable(playerCol.offset(side))) {
                blocked.add(side);
            }
        }
        return blocked;
    }

    private List<BlockPos> getLandingColumns(FallPrediction state) {
        double py = state.box.minY;
        int feetY = MathHelper.floor_double(py);
        int groundY = feetY - 1;
        if (groundY < 0) {
            return java.util.Collections.emptyList();
        }
        double cx = (state.box.minX + state.box.maxX) * 0.5;
        double cz = (state.box.minZ + state.box.maxZ) * 0.5;
        double inset = 0.05;
        double[] xs = { cx, state.box.minX + inset, state.box.maxX - inset };
        double[] zs = { cz, state.box.minZ + inset, state.box.maxZ - inset };

        java.util.LinkedHashSet<BlockPos> seen = new java.util.LinkedHashSet<>();
        for (double x : xs) {
            for (double z : zs) {
                int bx = MathHelper.floor_double(x);
                int bz = MathHelper.floor_double(z);
                BlockPos groundPos = new BlockPos(bx, groundY, bz);
                BlockPos feetPos = new BlockPos(bx, feetY, bz);
                if (!BlockUtils.replaceable(groundPos) && BlockUtils.replaceable(feetPos)) {
                    seen.add(feetPos);
                }
            }
        }
        List<BlockPos> result = new java.util.ArrayList<>(seen);
        if (result.isEmpty()) {
            result.add(new BlockPos(MathHelper.floor_double(cx), feetY, MathHelper.floor_double(cz)));
        }
        return result;
    }

    private boolean canReachBlockFromEye(Vec3 eye, BlockPos blockPos, ItemStack blockStack) {
        if (blockPos == null || blockStack == null || !BlockUtils.replaceable(blockPos)) return false;
        PlaceTarget blockTarget = getBlockPlaceTarget(blockPos, blockStack);
        return blockTarget != null && eye.distanceTo(blockTarget.hitVec) <= REACH;
    }

    private boolean isValidLandingPlan(BlockPos blockPos, EnumFacing faceTowardPlayer, BlockPos ladderSpace, ItemStack blockStack, ItemStack ladderStack) {
        BlockPos ladderPos = blockPos.offset(faceTowardPlayer);
        if (!ladderPos.equals(ladderSpace)) return false;
        if (!BlockUtils.replaceable(ladderPos)) return false;
        if (!BlockUtils.replaceable(blockPos)) return false;
        if (!placeBlockSetting.isToggled() || blockSlot == -1 || blockStack == null) return false;
        if (ladderStack == null) return false;
        if (getBlockPlaceTarget(blockPos, blockStack) == null) return false;
        if (!canLandOnLadder(ladderSpace, blockPos, faceTowardPlayer)) return false;
        return true;
    }

    private boolean canLandOnLadder(BlockPos ladderSpace, BlockPos attachBlock, EnumFacing faceTowardPlayer) {
        BlockPos above = ladderSpace.up();
        if (!BlockUtils.replaceable(above)) return false;
        EnumFacing enterSide = faceTowardPlayer.getOpposite();
        BlockPos enterBlock = ladderSpace.offset(enterSide);
        if (!BlockUtils.replaceable(enterBlock)) return false;
        return true;
    }

    private boolean isTargetBlockPlaced() {
        return targetBlockPos != null && !BlockUtils.replaceable(targetBlockPos);
    }

    private boolean isLadderPlaced() {
        return lockedLadderSpace != null && BlockUtils.getBlock(lockedLadderSpace) == Blocks.ladder;
    }

    private boolean isRotationReady(float yaw, float pitch) {
        return Math.abs(MathHelper.wrapAngleTo180_float(yaw - RotationUtils.serverRotations[0])) <= LADDER_ROT_TOL
                && Math.abs(pitch - RotationUtils.serverRotations[1]) <= LADDER_ROT_TOL;
    }

    private boolean canPlaceLadderTiming() {
        if (lockedLadderSpace == null || ladderAttachPos == null || ladderFace == null) return false;
        if (!playerClearOfLadderSpace()) return false;
        return canReachLadderAttach();
    }

    private boolean playerClearOfLadderSpace() {
        if (lockedLadderSpace == null) return false;
        AxisAlignedBB ladderBox = new AxisAlignedBB(
                lockedLadderSpace.getX(), lockedLadderSpace.getY(), lockedLadderSpace.getZ(),
                lockedLadderSpace.getX() + 1.0, lockedLadderSpace.getY() + 1.0, lockedLadderSpace.getZ() + 1.0
        );
        return !mc.thePlayer.getEntityBoundingBox().intersectsWith(ladderBox);
    }

    private boolean canReachLadderAttach() {
        if (ladderAttachPos == null || ladderFace == null) return false;
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        for (Vec3 hitVec : getLadderHitCandidates(ladderAttachPos, ladderFace)) {
            if (eye.distanceTo(hitVec) <= REACH) {
                return true;
            }
        }
        return false;
    }

    private void tryPlaceLadderImmediate() {
        if (System.currentTimeMillis() - lastBlockPlaceTime < LADDER_DELAY_MS) return;

        if (ladderSlot < 0 || ladderSlot > 8 || ladderAttachPos == null || ladderFace == null) return;
        if (!isTargetBlockPlaced() || isLadderPlaced() || !canReachLadderAttach()) return;

        ItemStack ladderStack = mc.thePlayer.inventory.mainInventory[ladderSlot];
        if (ladderStack == null || ladderStack.stackSize == 0) return;

        equipSlot(ladderSlot);
        placeLadderVerified(ladderStack, ladderAttachPos, ladderFace);

        if (isLadderPlaced()) {
            mc.thePlayer.swingItem();
            grabTicks = 0;
            phase = Phase.GRAB;
            ladderPlaceAttempts = 0;
        }
    }

    private void updateAimAndQueue(ClientRotationEvent e) {
        if (mc.thePlayer.onGround) {
            finish();
            return;
        }

        if (phase == Phase.PLACE_LADDER && isLadderPlaced()) {
            grabTicks = 0;
            phase = Phase.GRAB;
            return;
        }

        PlaceTarget target = getPlaceTarget();
        if (target == null) {
            if (phase == Phase.PLACE_BLOCK && isTargetBlockPlaced()) {
                blockPlaced = true;
                ladderAttachPos = targetBlockPos;
                phase = Phase.PLACE_LADDER;
                aimTicks = 0;
                attachWaitTicks = 0;
                tryPlaceLadderImmediate();
            } else if (phase == Phase.PLACE_LADDER) {
                if (isTargetBlockPlaced()) {
                    attachWaitTicks = 0;
                    tryPlaceLadderImmediate();
                } else if (++attachWaitTicks > MAX_ATTACH_WAIT) {
                    phase = Phase.PLACE_BLOCK;
                    blockPlaced = false;
                    aimTicks = 0;
                    attachWaitTicks = 0;
                }
            }
            return;
        }

        equipSlot(phase == Phase.PLACE_BLOCK ? blockSlot : ladderSlot);
        aimTicks++;

        float baseYaw = e.yaw != null ? e.yaw : RotationUtils.serverRotations[0];
        float basePitch = e.pitch != null ? e.pitch : RotationUtils.serverRotations[1];
        float[] rots;
        if (phase == Phase.PLACE_LADDER) {
            Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
            rots = RotationUtils.getRotationsFromEye(eye, target.hitVec.xCoord, target.hitVec.yCoord, target.hitVec.zCoord);
        } else {
            rots = RotationUtils.getRotationsToBlock(target.clickPos, target.clickFace, baseYaw, basePitch);
        }
        aimYaw = rots[0];
        aimPitch = rots[1];

        MovingObjectPosition mop = RotationUtils.rayCastBlock(REACH, aimYaw, aimPitch);
        boolean rayHit = mop != null
                && mop.getBlockPos().equals(target.clickPos)
                && mop.sideHit == target.clickFace;
        boolean rotationReady = isRotationReady(aimYaw, aimPitch);

        if (phase == Phase.PLACE_BLOCK) {
            if (rayHit && (rotationReady || aimTicks >= 2)) {
                placeAtBlock = mop.getBlockPos();
                placeSide = mop.sideHit;
                placeHitVec = mop.hitVec;
                placeQueued = true;
            } else if (aimTicks >= 3) {
                placeAtBlock = target.clickPos;
                placeSide = target.clickFace;
                placeHitVec = target.hitVec;
                placeQueued = true;
            }
        } else if (phase == Phase.PLACE_LADDER) {
            if (!isTargetBlockPlaced()) {
                if (++attachWaitTicks > MAX_ATTACH_WAIT) {
                    phase = Phase.PLACE_BLOCK;
                    blockPlaced = false;
                    aimTicks = 0;
                    attachWaitTicks = 0;
                }
            } else {
                attachWaitTicks = 0;
                if (canPlaceLadderTiming() && (System.currentTimeMillis() - lastBlockPlaceTime >= LADDER_DELAY_MS)) {
                    if (rayHit && (rotationReady || aimTicks >= 1)) {
                        placeAtBlock = mop.getBlockPos();
                        placeSide = mop.sideHit;
                        placeHitVec = mop.hitVec;
                        placeQueued = true;
                    } else if (aimTicks >= 2) {
                        placeAtBlock = target.clickPos;
                        placeSide = target.clickFace;
                        placeHitVec = target.hitVec;
                        placeQueued = true;
                    }
                }
            }
        }

        RotationHelper.get().forceMovementFix = true;
        e.setYaw(aimYaw);
        e.setPitch(aimPitch);
    }

    private void executePlace() {
        if (placeAtBlock == null || placeSide == null || placeHitVec == null) return;

        int slot = phase == Phase.PLACE_BLOCK ? blockSlot : ladderSlot;
        if (slot < 0 || slot > 8) return;
        ItemStack stack = mc.thePlayer.inventory.mainInventory[slot];
        if (stack == null || stack.stackSize == 0) return;

        equipSlot(slot);

        if (phase == Phase.PLACE_BLOCK) {
            if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, stack, placeAtBlock, placeSide, placeHitVec)) {
                mc.thePlayer.swingItem();
            }
            if (isTargetBlockPlaced()) {
                ladderAttachPos = targetBlockPos;
                blockPlaced = true;
                phase = Phase.PLACE_LADDER;
                lastBlockPlaceTime = System.currentTimeMillis(); // 100ms遅延用の時刻記録
                aimTicks = 0;
                attachWaitTicks = 0;
                ladderPlaceAttempts = 0;
            }
        } else if (phase == Phase.PLACE_LADDER) {
            if (!isTargetBlockPlaced()) {
                clearQueuedPlace();
                return;
            }
            if (isLadderPlaced()) {
                grabTicks = 0;
                phase = Phase.GRAB;
                clearQueuedPlace();
                return;
            }

            placeLadderVerified(stack, ladderAttachPos, ladderFace);
            if (isLadderPlaced()) {
                mc.thePlayer.swingItem();
                grabTicks = 0;
                phase = Phase.GRAB;
                ladderPlaceAttempts = 0;
            } else {
                ladderPlaceAttempts++;
                if (ladderPlaceAttempts > MAX_LADDER_ATTEMPTS) {
                    phase = Phase.PLACE_BLOCK;
                    blockPlaced = false;
                    aimTicks = 0;
                    attachWaitTicks = 0;
                    ladderPlaceAttempts = 0;
                }
            }
        }

        clearQueuedPlace();
    }

    private void clearQueuedPlace() {
        placeAtBlock = null;
        placeSide = null;
        placeHitVec = null;
    }

    private boolean placeLadderVerified(ItemStack ladderStack, BlockPos attachBlock, EnumFacing face) {
        if (attachBlock == null || face == null || ladderStack == null) return false;
        if (isLadderPlaced()) return true;

        Vec3[] candidates = getLadderHitCandidates(attachBlock, face);
        if (placeHitVec != null) {
            candidates = prependHitCandidate(candidates, placeHitVec);
        }

        for (Vec3 hitVec : candidates) {
            mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, ladderStack, attachBlock, face, hitVec);
            if (isLadderPlaced()) return true;
        }
        return isLadderPlaced();
    }

    private Vec3[] prependHitCandidate(Vec3[] candidates, Vec3 preferred) {
        Vec3[] merged = new Vec3[candidates.length + 1];
        merged[0] = preferred;
        int n = 1;
        for (Vec3 candidate : candidates) merged[n++] = candidate;
        return merged;
    }

    private void handleGrab() {
        grabTicks++;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), true);

        if (mc.thePlayer.isOnLadder() || mc.thePlayer.onGround || grabTicks > MAX_GRAB_TICKS) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
            finish();
        }
    }

    private PlaceTarget getPlaceTarget() {
        if (phase == Phase.PLACE_BLOCK) {
            ItemStack stack = blockSlot >= 0 ? mc.thePlayer.inventory.mainInventory[blockSlot] : null;
            return getBlockPlaceTarget(targetBlockPos, stack);
        }
        if (ladderAttachPos == null || ladderFace == null) return null;
        return getLadderPlaceTarget(ladderAttachPos, ladderFace);
    }

    private PlaceTarget getBlockPlaceTarget(BlockPos dest, ItemStack stack) {
        if (dest == null || stack == null || !BlockUtils.replaceable(dest)) return null;

        EnumFacing[] order = {
                EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH,
                EnumFacing.EAST, EnumFacing.WEST, EnumFacing.UP
        };
        for (EnumFacing face : order) {
            BlockPos clickPos = dest.offset(face);
            if (BlockUtils.replaceable(clickPos)) continue;
            EnumFacing placeFace = face.getOpposite();
            if (!BlockUtils.canPlaceBlockOnSide(stack, clickPos, placeFace)) continue;
            return new PlaceTarget(clickPos, placeFace, getHitVec(clickPos, placeFace));
        }
        return null;
    }

    private PlaceTarget getLadderPlaceTarget(BlockPos attachBlock, EnumFacing face) {
        return null;
    }

    private Vec3 getLadderHitVec(BlockPos pos, EnumFacing face) {
        double x = pos.getX() + 0.5 + face.getFrontOffsetX() * (0.5 - HIT_INSET);
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5 + face.getFrontOffsetZ() * (0.5 - HIT_INSET);
        if (face == EnumFacing.UP) {
            y = pos.getY() + 1.0 - HIT_INSET;
        } else if (face == EnumFacing.DOWN) {
            y = pos.getY() + HIT_INSET;
        }
        return new Vec3(x, y, z);
    }

    private Vec3[] getLadderHitCandidates(BlockPos pos, EnumFacing face) {
        Vec3 center = getLadderHitVec(pos, face);
        Vec3 alt = getHitVec(pos, face);
        Vec3 mid = new Vec3(
                pos.getX() + 0.5 + face.getFrontOffsetX() * 0.5,
                pos.getY() + 0.5,
                pos.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5
        );

        double yLow = pos.getY() + 0.25;
        double yMid = pos.getY() + 0.5;
        double yHigh = pos.getY() + 0.75;
        double inset = 0.5 - HIT_INSET;
        double ox = pos.getX() + 0.5 + face.getFrontOffsetX() * inset;
        double oz = pos.getZ() + 0.5 + face.getFrontOffsetZ() * inset;

        if (face.getAxis() != EnumFacing.Axis.Y) {
            return new Vec3[]{
                    center, alt, mid,
                    new Vec3(ox, yLow, oz),
                    new Vec3(ox, yMid, oz),
                    new Vec3(ox, yHigh, oz),
                    new Vec3(pos.getX() + 0.5, yMid, pos.getZ() + 0.5)
            };
        }
        return new Vec3[]{center, alt, mid};
    }

    private Vec3 getHitVec(BlockPos pos, EnumFacing face) {
        double x = pos.getX() + 0.5 + face.getFrontOffsetX() * (0.5 - HIT_INSET);
        double y = pos.getY() + 0.5 + face.getFrontOffsetY() * (0.5 - HIT_INSET);
        double z = pos.getZ() + 0.5 + face.getFrontOffsetZ() * (0.5 - HIT_INSET);
        return new Vec3(x, y, z);
    }

    private void findSlots() {
        blockSlot = -1;
        ladderSlot = -1;
        Item ladderItem = Item.getItemFromBlock(Blocks.ladder);

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
            if (stack == null || stack.stackSize == 0) continue;

            if (ladderSlot == -1 && stack.getItem() == ladderItem) {
                ladderSlot = i;
            } else if (blockSlot == -1 && stack.getItem() instanceof ItemBlock) {
                Block b = ((ItemBlock) stack.getItem()).getBlock();
                if (b != null && b.getMaterial() != Material.air && b != Blocks.ladder) {
                    blockSlot = i;
                }
            }
        }
    }

    private void equipSlot(int slot) {
        if (slot >= 0 && slot <= 8 && mc.thePlayer.inventory.currentItem != slot) {
            mc.thePlayer.inventory.currentItem = slot;
        }
    }

    private void restoreSlot() {
        if (prevSlot != -1 && mc.thePlayer.inventory.currentItem != prevSlot) {
            mc.thePlayer.inventory.currentItem = prevSlot;
        }
        prevSlot = -1;
    }

    private void finish() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
        restoreSlot();
        resetState();
    }

    private void resetState() {
        phase = Phase.IDLE;
        placeQueued = false;
        aimTicks = 0;
        grabTicks = 0;
        lockedPlan = null;
        previewPlan = null;
        lockedLadderSpace = null;
        blockPlaced = false;
        ladderPlaceAttempts = 0;
        attachWaitTicks = 0;
        targetBlockPos = null;
        ladderAttachPos = null;
        ladderFace = null;
        placeAtBlock = null;
        placeSide = null;
        placeHitVec = null;
    }

    private static class AdjacentPlan {
        final BlockPos blockPos;
        final EnumFacing ladderFace;
        AdjacentPlan(BlockPos blockPos, EnumFacing ladderFace) {
            this.blockPos = blockPos;
            this.ladderFace = ladderFace;
        }
    }

    private static class PredictedPlan {
        final AdjacentPlan plan;
        final int interceptTick;
        final BlockPos ladderSpace;
        final Set<EnumFacing> blockedSides;
        PredictedPlan(AdjacentPlan plan, int interceptTick, BlockPos ladderSpace, Set<EnumFacing> blockedSides) {
            this.plan = plan;
            this.interceptTick = interceptTick;
            this.ladderSpace = ladderSpace;
            this.blockedSides = blockedSides;
        }
    }

    private static class LandingPrediction {
        final BlockPos column;
        final int interceptTick;
        final double motionX;
        final double motionZ;
        LandingPrediction(BlockPos column, int interceptTick, double motionX, double motionZ) {
            this.column = column;
            this.interceptTick = interceptTick;
            this.motionX = motionX;
            this.motionZ = motionZ;
        }
    }

    private static class PlaceTarget {
        final BlockPos clickPos;
        final EnumFacing clickFace;
        final Vec3 hitVec;
        PlaceTarget(BlockPos clickPos, EnumFacing clickFace, Vec3 hitVec) {
            this.clickPos = clickPos;
            this.clickFace = clickFace;
            this.hitVec = hitVec;
        }
    }

    private static class FallPrediction {
        private AxisAlignedBB box;
        private double motionX;
        private double motionY;
        private double motionZ;
        private boolean onGround;

        static FallPrediction fromPlayer(boolean isPreview) {
            FallPrediction s = new FallPrediction();
            s.box = mc.thePlayer.getEntityBoundingBox();
            s.motionX = mc.thePlayer.motionX;
            s.motionY = mc.thePlayer.motionY;
            s.motionZ = mc.thePlayer.motionZ;
            s.onGround = mc.thePlayer.onGround;
            if (isPreview && s.onGround && Math.abs(s.motionX) < 0.05 && Math.abs(s.motionZ) < 0.05) {
                float yaw = mc.thePlayer.rotationYaw;
                s.motionX = -MathHelper.sin(yaw * (float) Math.PI / 180.0F) * 0.2;
                s.motionZ = MathHelper.cos(yaw * (float) Math.PI / 180.0F) * 0.2;
            }
            return s;
        }

        void tick(boolean keepHorizontal) {
            motionY -= 0.08;
            move(motionX, motionY, motionZ);
            motionY *= 0.98;
            if (!keepHorizontal) {
                motionX *= 0.98;
                motionZ *= 0.98;
                motionX *= 0.91;
                motionZ *= 0.91;
            }
        }

        private void move(double x, double y, double z) {
            double reqX = x;
            double reqY = y;
            double reqZ = z;
            List<AxisAlignedBB> collisions = mc.theWorld.getCollidingBoundingBoxes(
                    (Entity) mc.thePlayer, box.addCoord(reqX, reqY, reqZ));

            for (AxisAlignedBB c : collisions) {
                y = c.calculateYOffset(box, y);
            }
            box = box.offset(0.0, y, 0.0);

            for (AxisAlignedBB c : collisions) {
                x = c.calculateXOffset(box, x);
            }
            box = box.offset(x, 0.0, 0.0);

            for (AxisAlignedBB c : collisions) {
                z = c.calculateZOffset(box, z);
            }
            box = box.offset(0.0, 0.0, z);

            onGround = reqY != y && reqY < 0.0;
            if (reqY != y) motionY = 0.0;
            if (reqX != x) motionX = 0.0;
            if (reqZ != z) motionZ = 0.0;
        }
    }
}