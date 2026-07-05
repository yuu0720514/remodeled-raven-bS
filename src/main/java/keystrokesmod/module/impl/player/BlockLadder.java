package keystrokesmod.module.impl.player;

import keystrokesmod.event.ClientRotationEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.helper.RotationHelper;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
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
    private static final int MAX_PREDICT_TICKS = 40;
    private static final int MAX_ATTACH_WAIT = 6;
    private static final int MAX_LADDER_ATTEMPTS = 20;
    private static final EnumFacing[] HORIZONTALS = {
            EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST
    };

    private final SliderSetting minFallDistance = new SliderSetting("Min fall distance", " blocks", 1.5, 0.5, 20.0, 0.5);
    private final ButtonSetting placeBlockSetting = new ButtonSetting("Place block", true);
    private final ButtonSetting renderPreview = new ButtonSetting("Render preview", true);

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
    private BlockPos lockedLadderSpace;
    private boolean blockPlaced;
    private int ladderPlaceAttempts;
    private int attachWaitTicks;

    public BlockLadder() {
        super("BlockLadder", Module.category.player);
        this.registerSetting(minFallDistance);
        this.registerSetting(placeBlockSetting);
        this.registerSetting(renderPreview);
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
        restoreSlot();
        resetState();
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent e) {
        if (!Utils.nullCheck()) return;
        if (mc.currentScreen != null) return;
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
        if (mc.currentScreen != null) return;
        if (mc.thePlayer.capabilities.isFlying || mc.thePlayer.capabilities.isCreativeMode) return;

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
        if (mc.thePlayer.capabilities.isFlying || mc.thePlayer.capabilities.isCreativeMode) return;

        BlockPos previewBlock = targetBlockPos;
        BlockPos previewLadderSpace = lockedLadderSpace;
        BlockPos previewAttach = ladderAttachPos;
        EnumFacing previewFace = ladderFace;

        if ((previewBlock == null || previewLadderSpace == null || previewAttach == null || previewFace == null)
                && phase == Phase.IDLE
                && !mc.thePlayer.onGround) {
            double earlyThreshold = Math.max(0.3, minFallDistance.getInput() * 0.3);
            if (mc.thePlayer.fallDistance >= (float) earlyThreshold || mc.thePlayer.motionY < -0.3) {
                findSlots();
                PredictedPlan prediction = findBestPredictedPlan();
                if (prediction != null) {
                    previewBlock = prediction.plan.blockPos;
                    previewAttach = prediction.plan.blockPos;
                    previewFace = prediction.plan.ladderFace;
                    previewLadderSpace = prediction.ladderSpace;
                }
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

        drawOutlinedBlock(previewBlock, viewerX, viewerY, viewerZ, 0.15F, 1.0F, 0.25F, 0.9F);

        drawOutlinedBlock(previewLadderSpace, viewerX, viewerY, viewerZ, 0.0F, 0.85F, 1.0F, 0.9F);

        drawLadderPreview(previewAttach, previewFace, viewerX, viewerY, viewerZ);

        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    private void drawOutlinedBlock(BlockPos pos, double viewerX, double viewerY, double viewerZ,
                                   float r, float g, float b, float a) {
        double x = pos.getX() - viewerX;
        double y = pos.getY() - viewerY;
        double z = pos.getZ() - viewerZ;
        drawBoxLines(x, y, z, x + 1.0, y + 1.0, z + 1.0, r, g, b, a);
    }

    private void drawLadderPreview(BlockPos attach, EnumFacing face, double viewerX, double viewerY, double viewerZ) {
        double minX = attach.getX() - viewerX;
        double minY = attach.getY() - viewerY;
        double minZ = attach.getZ() - viewerZ;
        double maxX = minX + 1.0;
        double maxY = minY + 1.0;
        double maxZ = minZ + 1.0;
        double o = 0.015;

        double px1, py1, pz1, px2, py2, pz2, px3, py3, pz3, px4, py4, pz4;
        if (face == EnumFacing.NORTH) {
            px1 = minX + 0.18; py1 = minY + 0.08; pz1 = minZ - o;
            px2 = maxX - 0.18; py2 = minY + 0.08; pz2 = minZ - o;
            px3 = maxX - 0.18; py3 = maxY - 0.08; pz3 = minZ - o;
            px4 = minX + 0.18; py4 = maxY - 0.08; pz4 = minZ - o;
        } else if (face == EnumFacing.SOUTH) {
            px1 = maxX - 0.18; py1 = minY + 0.08; pz1 = maxZ + o;
            px2 = minX + 0.18; py2 = minY + 0.08; pz2 = maxZ + o;
            px3 = minX + 0.18; py3 = maxY - 0.08; pz3 = maxZ + o;
            px4 = maxX - 0.18; py4 = maxY - 0.08; pz4 = maxZ + o;
        } else if (face == EnumFacing.EAST) {
            px1 = maxX + o; py1 = minY + 0.08; pz1 = minZ + 0.18;
            px2 = maxX + o; py2 = minY + 0.08; pz2 = maxZ - 0.18;
            px3 = maxX + o; py3 = maxY - 0.08; pz3 = maxZ - 0.18;
            px4 = maxX + o; py4 = maxY - 0.08; pz4 = minZ + 0.18;
        } else {
            px1 = minX - o; py1 = minY + 0.08; pz1 = maxZ - 0.18;
            px2 = minX - o; py2 = minY + 0.08; pz2 = minZ + 0.18;
            px3 = minX - o; py3 = maxY - 0.08; pz3 = minZ + 0.18;
            px4 = minX - o; py4 = maxY - 0.08; pz4 = maxZ - 0.18;
        }

        drawQuadLines(px1, py1, pz1, px2, py2, pz2, px3, py3, pz3, px4, py4, pz4, 1.0F, 0.9F, 0.05F, 1.0F);
        drawInterpolatedLine(px1, py1 + 0.22, pz1, px2, py2 + 0.22, pz2, 1.0F, 0.9F, 0.05F, 1.0F);
        drawInterpolatedLine(px1, py1 + 0.46, pz1, px2, py2 + 0.46, pz2, 1.0F, 0.9F, 0.05F, 1.0F);
        drawInterpolatedLine(px1, py1 + 0.70, pz1, px2, py2 + 0.70, pz2, 1.0F, 0.9F, 0.05F, 1.0F);

        BlockPos ladderSpace = attach.offset(face);
        double sx = ladderSpace.getX() + 0.5 - viewerX;
        double sy = ladderSpace.getY() + 0.55 - viewerY;
        double sz = ladderSpace.getZ() + 0.5 - viewerZ;
        double ex = attach.getX() + 0.5 - viewerX;
        double ey = attach.getY() + 0.55 - viewerY;
        double ez = attach.getZ() + 0.5 - viewerZ;
        drawInterpolatedLine(sx, sy, sz, ex, ey, ez, 1.0F, 0.35F, 0.05F, 1.0F);

        double hx = ex - face.getFrontOffsetX() * 0.18;
        double hz = ez - face.getFrontOffsetZ() * 0.18;
        drawInterpolatedLine(ex, ey, ez, hx + face.rotateY().getFrontOffsetX() * 0.12, ey + 0.08, hz + face.rotateY().getFrontOffsetZ() * 0.12, 1.0F, 0.35F, 0.05F, 1.0F);
        drawInterpolatedLine(ex, ey, ez, hx + face.rotateYCCW().getFrontOffsetX() * 0.12, ey - 0.08, hz + face.rotateYCCW().getFrontOffsetZ() * 0.12, 1.0F, 0.35F, 0.05F, 1.0F);
    }

    private void drawBoxLines(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                              float r, float g, float b, float a) {
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_LINES);
        vertex(minX, minY, minZ); vertex(maxX, minY, minZ);
        vertex(maxX, minY, minZ); vertex(maxX, minY, maxZ);
        vertex(maxX, minY, maxZ); vertex(minX, minY, maxZ);
        vertex(minX, minY, maxZ); vertex(minX, minY, minZ);
        vertex(minX, maxY, minZ); vertex(maxX, maxY, minZ);
        vertex(maxX, maxY, minZ); vertex(maxX, maxY, maxZ);
        vertex(maxX, maxY, maxZ); vertex(minX, maxY, maxZ);
        vertex(minX, maxY, maxZ); vertex(minX, maxY, minZ);
        vertex(minX, minY, minZ); vertex(minX, maxY, minZ);
        vertex(maxX, minY, minZ); vertex(maxX, maxY, minZ);
        vertex(maxX, minY, maxZ); vertex(maxX, maxY, maxZ);
        vertex(minX, minY, maxZ); vertex(minX, maxY, maxZ);
        GL11.glEnd();
    }

    private void drawQuadLines(double x1, double y1, double z1, double x2, double y2, double z2,
                               double x3, double y3, double z3, double x4, double y4, double z4,
                               float r, float g, float b, float a) {
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_LINES);
        vertex(x1, y1, z1); vertex(x2, y2, z2);
        vertex(x2, y2, z2); vertex(x3, y3, z3);
        vertex(x3, y3, z3); vertex(x4, y4, z4);
        vertex(x4, y4, z4); vertex(x1, y1, z1);
        vertex(x1, y1, z1); vertex(x4, y4, z4);
        vertex(x2, y2, z2); vertex(x3, y3, z3);
        GL11.glEnd();
    }

    private void drawInterpolatedLine(double x1, double y1, double z1, double x2, double y2, double z2,
                                      float r, float g, float b, float a) {
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_LINES);
        vertex(x1, y1, z1);
        vertex(x2, y2, z2);
        GL11.glEnd();
    }

    private void vertex(double x, double y, double z) {
        GL11.glVertex3d(x, y, z);
    }

    private void tryStart() {
        if (mc.thePlayer.onGround) return;
        if (mc.thePlayer.fallDistance < (float) minFallDistance.getInput()) return;

        findSlots();
        if (ladderSlot == -1) {
            return;
        }

        PredictedPlan prediction = findBestPredictedPlan();
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
        PredictedPlan prediction = findBestPredictedPlan();
        if (prediction != null) {
            lockedPlan = prediction;
            lockedLadderSpace = prediction.ladderSpace;
            applyPlan(prediction);
        }
    }

    private boolean isLockedPlanStillValid() {
        if (lockedPlan == null || lockedLadderSpace == null) {
            return false;
        }
        if (phase != Phase.PLACE_BLOCK) {
            return true;
        }
        ItemStack blockStack = blockSlot >= 0 ? mc.thePlayer.inventory.mainInventory[blockSlot] : null;
        ItemStack ladderStack = ladderSlot >= 0 ? mc.thePlayer.inventory.mainInventory[ladderSlot] : null;
        return isValidLandingPlan(
                lockedPlan.plan.blockPos,
                lockedPlan.plan.ladderFace,
                lockedLadderSpace,
                blockStack,
                ladderStack
        );
    }

    private void applyPlan(PredictedPlan prediction) {
        targetBlockPos = prediction.plan.blockPos;
        ladderAttachPos = prediction.plan.blockPos;
        ladderFace = prediction.plan.ladderFace;
        lockedLadderSpace = prediction.ladderSpace;
    }

    private PredictedPlan findBestPredictedPlan() {
        ItemStack blockStack = blockSlot >= 0 ? mc.thePlayer.inventory.mainInventory[blockSlot] : null;
        ItemStack ladderStack = ladderSlot >= 0 ? mc.thePlayer.inventory.mainInventory[ladderSlot] : null;
        if (ladderStack == null) {
            return null;
        }

        LandingPrediction landing = predictLanding();
        if (landing != null && landing.column != null) {
            PredictedPlan plan = findPlanAtLanding(landing, blockStack, ladderStack);
            if (plan != null) {
                return plan;
            }
        }

        FallPrediction currentState = FallPrediction.fromPlayer();
        List<BlockPos> currentColumns = getLandingColumns(currentState);
        for (BlockPos col : currentColumns) {
            Set<EnumFacing> blocked = getSolidSidesAroundColumn(col);
            PredictedPlan plan = findPlanAtColumnFrontFirst(col, blockStack, ladderStack, blocked);
            if (plan != null) {
                return plan;
            }
        }

        return null;
    }

    private PredictedPlan findPlanAtColumnFrontFirst(BlockPos landingColumn, ItemStack blockStack, ItemStack ladderStack,
                                                     Set<EnumFacing> blockedSides) {
        EnumFacing forward = getPlayerForwardFacing();
        EnumFacing left = forward != null ? forward.rotateYCCW() : null;
        EnumFacing right = forward != null ? forward.rotateY() : null;
        EnumFacing backward = forward != null ? forward.getOpposite() : null;

        EnumFacing[] priority;
        if (forward != null) {
            priority = new EnumFacing[]{ forward, left, right, backward };
        } else {
            priority = HORIZONTALS;
        }

        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        for (EnumFacing side : priority) {
            if (side == null) continue;
            if (blockedSides.contains(side)) continue;
            BlockPos blockPos = landingColumn.offset(side);
            EnumFacing faceTowardPlayer = side.getOpposite();
            if (!isValidLandingPlan(blockPos, faceTowardPlayer, landingColumn, blockStack, ladderStack)) continue;
            if (!canReachBlockFromEye(eye, blockPos, blockStack)) continue;
            return new PredictedPlan(new AdjacentPlan(blockPos, faceTowardPlayer), 0, landingColumn, blockedSides);
        }
        return null;
    }

    private LandingPrediction predictLanding() {
        FallPrediction state = FallPrediction.fromPlayer();
        for (int tick = 0; tick <= MAX_PREDICT_TICKS; tick++) {
            if (tick > 0) {
                state.tick();
            }
            if (state.onGround) {
                List<BlockPos> columns = getLandingColumns(state);
                if (!columns.isEmpty()) {
                    return new LandingPrediction(columns.get(0), tick, state.motionX, state.motionZ);
                }
            }
        }

        List<BlockPos> columns = getLandingColumns(state);
        BlockPos baseCol = columns.isEmpty() ? null : columns.get(0);
        if (baseCol == null) {
            return null;
        }

        for (BlockPos col : columns) {
            for (int y = col.getY(); y >= Math.max(0, col.getY() - 64); y--) {
                BlockPos ground = new BlockPos(col.getX(), y, col.getZ());
                if (!BlockUtils.replaceable(ground)) {
                    return new LandingPrediction(ground, MAX_PREDICT_TICKS, state.motionX, state.motionZ);
                }
            }
        }
        return null;
    }

    private PredictedPlan findPlanAtLanding(LandingPrediction landing, ItemStack blockStack, ItemStack ladderStack) {
        BlockPos landingColumn = landing.column;
        Set<EnumFacing> blockedSides = getSolidSidesAroundColumn(landingColumn);

        EnumFacing motionForward = getDominantHorizontalFacing(landing.motionX, landing.motionZ);
        EnumFacing playerForward = getPlayerForwardFacing();
        EnumFacing forward = (Math.abs(landing.motionX) > 0.05 || Math.abs(landing.motionZ) > 0.05)
                ? motionForward : playerForward;

        EnumFacing left = forward != null ? forward.rotateYCCW() : null;
        EnumFacing right = forward != null ? forward.rotateY() : null;
        EnumFacing backward = forward != null ? forward.getOpposite() : null;

        EnumFacing[] priority = forward != null
                ? new EnumFacing[]{ forward, left, right, backward }
                : HORIZONTALS;

        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);

        for (EnumFacing side : priority) {
            if (side == null) continue;
            if (blockedSides.contains(side)) continue;
            BlockPos blockPos = landingColumn.offset(side);
            EnumFacing faceTowardPlayer = side.getOpposite();
            BlockPos ladderSpace = landingColumn;
            if (!isValidLandingPlan(blockPos, faceTowardPlayer, ladderSpace, blockStack, ladderStack)) continue;
            if (!canReachBlockFromEye(eye, blockPos, blockStack)) continue;
            return new PredictedPlan(
                    new AdjacentPlan(blockPos, faceTowardPlayer),
                    landing.interceptTick,
                    ladderSpace,
                    blockedSides
            );
        }
        return null;
    }

    private EnumFacing getBehindSide(double motionX, double motionZ, Set<EnumFacing> blockedSides) {
        EnumFacing motionSide = getDominantHorizontalFacing(motionX, motionZ);
        if (motionSide != null && !blockedSides.contains(motionSide.getOpposite())) {
            return motionSide.getOpposite();
        }

        Set<EnumFacing> touchingNow = EnumSet.noneOf(EnumFacing.class);
        BlockPos currentCol = getPlayerColumn(FallPrediction.fromPlayer());
        if (currentCol != null) {
            touchingNow = getTouchingSolidSides(mc.thePlayer.getEntityBoundingBox(), currentCol);
        }
        for (EnumFacing side : HORIZONTALS) {
            if (touchingNow.contains(side) && !blockedSides.contains(side.getOpposite())) {
                return side.getOpposite();
            }
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

    private double scoreLandingSide(EnumFacing side, EnumFacing behindSide, Set<EnumFacing> blockedSides,
                                    int interceptTick, Vec3 eye, BlockPos blockPos) {
        double score = 20.0 - interceptTick * 0.15;

        EnumFacing forwardSide = getPlayerForwardFacing();
        EnumFacing leftSide = forwardSide != null ? forwardSide.rotateYCCW() : null;
        EnumFacing rightSide = forwardSide != null ? forwardSide.rotateY() : null;

        if (forwardSide != null && side == forwardSide) {
            score += 20.0;
        } else if ((leftSide != null && side == leftSide) || (rightSide != null && side == rightSide)) {
            score += 8.0;
        } else if (behindSide != null && side == behindSide) {
            score += 2.0;
        }

        if (blockedSides.contains(side.getOpposite())) {
            score += 4.0;
        }

        Vec3 target = new Vec3(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
        score -= eye.distanceTo(target) * 0.4;
        return score;
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
        int feetY = MathHelper.floor_double(py) - 1;
        if (feetY < 0) {
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
                seen.add(new BlockPos(MathHelper.floor_double(x), feetY, MathHelper.floor_double(z)));
            }
        }
        List<BlockPos> result = new java.util.ArrayList<>();
        for (BlockPos pos : seen) {
            if (!BlockUtils.replaceable(pos)) {
                result.add(pos);
            }
        }
        if (result.isEmpty()) {
            result.add(new BlockPos(MathHelper.floor_double(cx), feetY, MathHelper.floor_double(cz)));
        }
        return result;
    }

    private BlockPos getLandingColumn(FallPrediction state) {
        List<BlockPos> cols = getLandingColumns(state);
        return cols.isEmpty() ? null : cols.get(0);
    }

    private BlockPos getPlayerColumn(FallPrediction state) {
        return getLandingColumn(state);
    }

    private Set<EnumFacing> getTouchingSolidSides(AxisAlignedBB box, BlockPos playerCol) {
        Set<EnumFacing> touching = EnumSet.noneOf(EnumFacing.class);
        for (EnumFacing side : HORIZONTALS) {
            BlockPos adjacent = playerCol.offset(side);
            if (!BlockUtils.replaceable(adjacent) && boxIntersectsBlockColumn(box, adjacent)) {
                touching.add(side);
            }
        }
        return touching;
    }

    private boolean boxIntersectsBlockColumn(AxisAlignedBB box, BlockPos blockPos) {
        AxisAlignedBB blockBox = new AxisAlignedBB(
                blockPos.getX(), blockPos.getY(), blockPos.getZ(),
                blockPos.getX() + 1.0, blockPos.getY() + 1.0, blockPos.getZ() + 1.0
        );
        return box.intersectsWith(blockBox);
    }

    private boolean canReachBlockFromEye(Vec3 eye, BlockPos blockPos, ItemStack blockStack) {
        if (blockPos == null || blockStack == null || !BlockUtils.replaceable(blockPos)) {
            return false;
        }
        PlaceTarget blockTarget = getBlockPlaceTarget(blockPos, blockStack);
        return blockTarget != null && eye.distanceTo(blockTarget.hitVec) <= REACH;
    }

    private boolean isValidLandingPlan(BlockPos blockPos, EnumFacing faceTowardPlayer, BlockPos ladderSpace,
                                       ItemStack blockStack, ItemStack ladderStack) {
        BlockPos ladderPos = blockPos.offset(faceTowardPlayer);
        if (!ladderPos.equals(ladderSpace)) {
            return false;
        }
        if (!BlockUtils.replaceable(ladderPos)) {
            return false;
        }
        if (!BlockUtils.replaceable(blockPos)) {
            return false;
        }
        if (!placeBlockSetting.isToggled() || blockSlot == -1 || blockStack == null) {
            return false;
        }
        if (ladderStack == null) {
            return false;
        }
        if (getBlockPlaceTarget(blockPos, blockStack) == null) {
            return false;
        }
        if (!canLandOnLadder(ladderSpace, blockPos, faceTowardPlayer)) {
            return false;
        }
        return true;
    }

    private boolean canLandOnLadder(BlockPos ladderSpace, BlockPos attachBlock, EnumFacing faceTowardPlayer) {
        BlockPos above = ladderSpace.up();
        if (!BlockUtils.replaceable(above)) {
            return false;
        }
        EnumFacing enterSide = faceTowardPlayer.getOpposite();
        BlockPos enterBlock = ladderSpace.offset(enterSide);
        if (!BlockUtils.replaceable(enterBlock)) {
            return false;
        }
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
        if (lockedLadderSpace == null || ladderAttachPos == null || ladderFace == null) {
            return false;
        }
        if (!playerClearOfLadderSpace()) {
            return false;
        }
        return canReachLadderAttach();
    }

    private boolean playerClearOfLadderSpace() {
        if (lockedLadderSpace == null) {
            return false;
        }
        AxisAlignedBB ladderBox = new AxisAlignedBB(
                lockedLadderSpace.getX(), lockedLadderSpace.getY(), lockedLadderSpace.getZ(),
                lockedLadderSpace.getX() + 1.0, lockedLadderSpace.getY() + 1.0, lockedLadderSpace.getZ() + 1.0
        );
        return !mc.thePlayer.getEntityBoundingBox().intersectsWith(ladderBox);
    }

    private boolean canReachLadderAttach() {
        if (ladderAttachPos == null || ladderFace == null) {
            return false;
        }
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        for (Vec3 hitVec : getLadderHitCandidates(ladderAttachPos, ladderFace)) {
            if (eye.distanceTo(hitVec) <= REACH) {
                return true;
            }
        }
        return false;
    }

    private void tryPlaceLadderImmediate() {
        if (ladderSlot < 0 || ladderSlot > 8 || ladderAttachPos == null || ladderFace == null) {
            return;
        }
        if (!isTargetBlockPlaced() || isLadderPlaced() || !canReachLadderAttach()) {
            return;
        }
        ItemStack ladderStack = mc.thePlayer.inventory.mainInventory[ladderSlot];
        if (ladderStack == null || ladderStack.stackSize == 0) {
            return;
        }
        equipSlot(ladderSlot);
        placeLadderVerified(ladderStack, ladderAttachPos, ladderFace);
        if (isLadderPlaced()) {
            mc.thePlayer.swingItem();
            grabTicks = 0;
            phase = Phase.GRAB;
            ladderPlaceAttempts = 0;
        }
    }

    private boolean isAttachReadyForLadder(BlockPos attachBlock) {
        if (attachBlock == null) {
            return false;
        }
        return isTargetBlockPlaced() || (!BlockUtils.replaceable(attachBlock) && blockPlaced);
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
                if (canPlaceLadderTiming()) {
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
        if (placeAtBlock == null || placeSide == null || placeHitVec == null) {
            return;
        }

        int slot = phase == Phase.PLACE_BLOCK ? blockSlot : ladderSlot;
        if (slot < 0 || slot > 8) {
            return;
        }

        ItemStack stack = mc.thePlayer.inventory.mainInventory[slot];
        if (stack == null || stack.stackSize == 0) {
            return;
        }

        equipSlot(slot);

        if (phase == Phase.PLACE_BLOCK) {
            if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, stack, placeAtBlock, placeSide, placeHitVec)) {
                mc.thePlayer.swingItem();
            }
            if (isTargetBlockPlaced()) {
                ladderAttachPos = targetBlockPos;
                blockPlaced = true;
                phase = Phase.PLACE_LADDER;
                aimTicks = 0;
                attachWaitTicks = 0;
                ladderPlaceAttempts = 0;
                tryPlaceLadderImmediate();
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
        if (attachBlock == null || face == null || ladderStack == null) {
            return false;
        }
        if (isLadderPlaced()) {
            return true;
        }

        Vec3[] candidates = getLadderHitCandidates(attachBlock, face);
        if (placeHitVec != null) {
            candidates = prependHitCandidate(candidates, placeHitVec);
        }

        for (Vec3 hitVec : candidates) {
            mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, ladderStack, attachBlock, face, hitVec);
            if (isLadderPlaced()) {
                return true;
            }
        }
        return isLadderPlaced();
    }

    private Vec3[] prependHitCandidate(Vec3[] candidates, Vec3 preferred) {
        Vec3[] merged = new Vec3[candidates.length + 1];
        merged[0] = preferred;
        int n = 1;
        for (Vec3 candidate : candidates) {
            merged[n++] = candidate;
        }
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
        ItemStack ladderStack = ladderSlot >= 0 ? mc.thePlayer.inventory.mainInventory[ladderSlot] : null;
        if (ladderStack == null || attachBlock == null || face == null) {
            return null;
        }
        if (!isAttachReadyForLadder(attachBlock)) {
            return null;
        }

        BlockPos ladderSpace = attachBlock.offset(face);
        if (!BlockUtils.replaceable(ladderSpace)) {
            return null;
        }

        return new PlaceTarget(attachBlock, face, getLadderHitVec(attachBlock, face));
    }

    private boolean tryAlternateLadderPlace(ItemStack ladderStack, BlockPos attachBlock, EnumFacing face) {
        if (attachBlock == null || face == null) {
            return false;
        }
        Vec3[] candidates = getLadderHitCandidates(attachBlock, face);
        for (Vec3 hitVec : candidates) {
            if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, ladderStack, attachBlock, face, hitVec)) {
                return true;
            }
        }
        return false;
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
        restoreSlot();
        resetState();
    }

    private void resetState() {
        phase = Phase.IDLE;
        placeQueued = false;
        aimTicks = 0;
        grabTicks = 0;
        lockedPlan = null;
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

        static FallPrediction fromPlayer() {
            FallPrediction s = new FallPrediction();
            s.box = mc.thePlayer.getEntityBoundingBox();
            s.motionX = mc.thePlayer.motionX;
            s.motionY = mc.thePlayer.motionY;
            s.motionZ = mc.thePlayer.motionZ;
            s.onGround = mc.thePlayer.onGround;
            return s;
        }

        void tick() {
            motionY -= 0.08;
            move(motionX, motionY, motionZ);
            motionY *= 0.98;
            motionX *= 0.98;
            motionZ *= 0.98;
            motionX *= 0.91;
            motionZ *= 0.91;
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
            if (reqY != y) {
                motionY = 0.0;
            }
            if (reqX != x) {
                motionX = 0.0;
            }
            if (reqZ != z) {
                motionZ = 0.0;
            }
        }
    }
}