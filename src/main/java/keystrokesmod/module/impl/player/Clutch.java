package keystrokesmod.module.impl.player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import keystrokesmod.event.ClientRotationEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.helper.RotationHelper;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.KeySetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

public class Clutch
        extends Module {
    private static final Map<String, Integer> BLOCK_SCORE = new HashMap<String, Integer>();
    private static final double HALF_WIDTH = 0.3;
    private static final double[][] CORNERS = new double[][]{{-0.3, -0.3}, {0.3, -0.3}, {-0.3, 0.3}, {0.3, 0.3}};
    private static final long PLACE_ANIMATION_DURATION_MS = 450L; // アニメーションをより際立たせるため少し短めに調整（任意）
    private final List<PlacedBlockAnim> placeAnimations = new ArrayList<PlacedBlockAnim>();
    private final SliderSetting reach = new SliderSetting("Reach", " blocks", 4.5, 0.5, 4.5, 0.1);
    private final SliderSetting speed;
    private final SliderSetting snapbackSpeed;
    private final SliderSetting maxDistance;
    private final SliderSetting rotationTolerance;
    private final ButtonSetting simulateFuturePosition;
    private final ButtonSetting autoClutch;
    private final SliderSetting minimumFallDistance;
    private final KeySetting selectKeybind;
    private BlockPos placeAtBlock;
    private EnumFacing hitSide;
    private Vec3 hitVec;
    private boolean placeQueued;
    private boolean placing;
    private boolean slotWasSwapped;
    private int prevSlot = -1;
    private int plannedSlot = -1;
    private float aimYaw;
    private float aimPitch;
    private BlockPos targetHitPos;
    private EnumFacing targetSide;
    private boolean hasAim;
    private boolean resetting;
    private BlockPos lastPlaced;
    private int clutchBlocksPlaced;
    private boolean autoClutchActive;
    private boolean autoClutchChecking;
    private int autoClutchCheckCounter;
    private boolean autoClutchLandedGuard;
    private int autoClutchLandedTick;
    private int prevHurtTime = -1;

    public Clutch() {
        super("Clutch", Module.category.player);
        this.registerSetting(this.reach);
        this.speed = new SliderSetting("Speed", 8.0, 0.0, 100.0, 1.0);
        this.registerSetting(this.speed);
        this.snapbackSpeed = new SliderSetting("Snapback Speed", 12.0, 0.0, 100.0, 1.0);
        this.registerSetting(this.snapbackSpeed);
        this.maxDistance = new SliderSetting("Max distance", " blocks", 10.0, 0.0, 20.0, 1.0);
        this.registerSetting(this.maxDistance);
        this.rotationTolerance = new SliderSetting("Rotation Tolerance", "\u00b0", 25.0, 20.0, 100.0, 1.0);
        this.registerSetting(this.rotationTolerance);
        this.simulateFuturePosition = new ButtonSetting("Simulate future position", true);
        this.registerSetting(this.simulateFuturePosition);
        this.autoClutch = new ButtonSetting("Auto Clutch", false);
        this.registerSetting(this.autoClutch);
        this.minimumFallDistance = new SliderSetting("Minimum fall distance", " blocks", 10.0, 3.0, 20.0, 1.0);
        this.registerSetting(this.minimumFallDistance);
        this.selectKeybind = new KeySetting("Select Keybind", 0);
        this.registerSetting(this.selectKeybind);
    }

    @Override
    public void onEnable() {
        this.hasAim = false;
        this.resetting = false;
        this.clutchBlocksPlaced = 0;
        this.autoClutchActive = false;
        this.autoClutchChecking = false;
        this.autoClutchCheckCounter = 0;
        this.autoClutchLandedGuard = false;
        this.autoClutchLandedTick = 0;
        this.prevHurtTime = -1;
    }

    @Override
    public void onDisable() {
        this.clearAim(false);
        this.disablePlacing(true);
        this.placeQueued = false;
        this.autoClutchActive = false;
        this.autoClutchChecking = false;
        this.autoClutchLandedGuard = false;
        this.placeAnimations.clear();
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent e) {
        int maxBlocks;
        MovingObjectPosition mop;
        float basePitch;
        if (!Utils.nullCheck()) {
            return;
        }
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.shouldOverrideMouseOver()) {
            return;
        }
        this.runPrePlayerInteract();
        if (Clutch.mc.currentScreen != null) {
            this.disablePlacing(false);
        }
        float baseYaw = e.yaw != null ? e.yaw.floatValue() : RotationUtils.serverRotations[0];
        float f = basePitch = e.pitch != null ? e.pitch.floatValue() : RotationUtils.serverRotations[1];
        if (this.resetting) {
            this.aimYaw = Clutch.mc.thePlayer.rotationYaw;
            this.aimPitch = Clutch.mc.thePlayer.rotationPitch;
            float[] smoothed = this.getRotationsSmoothed(baseYaw, basePitch, this.aimYaw, this.aimPitch, true);
            if (Math.abs(MathHelper.wrapAngleTo180_float((float)(smoothed[0] - this.aimYaw))) < 0.5f && Math.abs(smoothed[1] - this.aimPitch) < 0.5f) {
                this.resetting = false;
                this.restoreInputs();
                return;
            }
            RotationHelper.get().forceMovementFix = true;
            e.setYaw(Float.valueOf(smoothed[0]));
            e.setPitch(Float.valueOf(smoothed[1]));
            return;
        }
        if (!this.hasAim) {
            return;
        }
        float[] smoothed = this.getRotationsSmoothed(baseYaw, basePitch, this.aimYaw, this.aimPitch, false);
        if (this.placing && this.targetHitPos != null && (mop = RotationUtils.rayCastBlock(this.reach.getInput(), smoothed[0], smoothed[1])) != null && this.targetHitPos.equals((Object)mop.getBlockPos()) && this.targetSide == mop.sideHit && ((maxBlocks = (int)this.maxDistance.getInput()) == 0 || this.clutchBlocksPlaced < maxBlocks)) {
            double tolerance = this.rotationTolerance.getInput();
            if ((double)Math.abs(MathHelper.wrapAngleTo180_float((float)(smoothed[0] - RotationUtils.serverRotations[0]))) <= tolerance && (double)Math.abs(smoothed[1] - RotationUtils.serverRotations[1]) <= tolerance) {
                this.placeAtBlock = mop.getBlockPos();
                this.hitSide = mop.sideHit;
                this.hitVec = mop.hitVec;
                this.placeQueued = true;
            }
        }
        RotationHelper.get().forceMovementFix = true;
        e.setYaw(Float.valueOf(smoothed[0]));
        e.setPitch(Float.valueOf(smoothed[1]));
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (!Utils.nullCheck() || !this.placeQueued) {
            return;
        }
        this.placeQueued = false;
        if (this.placeAtBlock != null && this.hitSide != null && this.hitVec != null) {
            if (Clutch.mc.playerController.onPlayerRightClick(Clutch.mc.thePlayer, Clutch.mc.theWorld, Clutch.mc.thePlayer.getHeldItem(), this.placeAtBlock, this.hitSide, this.hitVec)) {
                if (this.hitSide != EnumFacing.UP) {
                    ++this.clutchBlocksPlaced;
                }
                BlockPos actualPlacedPos = this.placeAtBlock.offset(this.hitSide);

                this.lastPlaced = this.placeAtBlock;
                this.placeAnimations.add(new PlacedBlockAnim(actualPlacedPos, System.currentTimeMillis()));
                Clutch.mc.thePlayer.swingItem();
            }
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent e) {
        if (this.placeAnimations.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<PlacedBlockAnim> it = this.placeAnimations.iterator();
        while (it.hasNext()) {
            PlacedBlockAnim anim = it.next();
            long elapsed = now - anim.placedAt;
            if (elapsed >= PLACE_ANIMATION_DURATION_MS) {
                it.remove();
                continue;
            }
            float progress = (float) elapsed / (float) PLACE_ANIMATION_DURATION_MS;
            if (progress < 0.0f) progress = 0.0f;
            if (progress > 1.0f) progress = 1.0f;
            this.renderPlaceAnimation(anim.pos, progress, e.partialTicks);
        }
    }

    private void renderPlaceAnimation(BlockPos pos, float progress, float partialTicks) {
        double viewX = Clutch.mc.getRenderManager().viewerPosX;
        double viewY = Clutch.mc.getRenderManager().viewerPosY;
        double viewZ = Clutch.mc.getRenderManager().viewerPosZ;
        float alpha = 1.0f - progress;
        double offset = 0.002;
        AxisAlignedBB box = new AxisAlignedBB(
                pos.getX() - offset - viewX, pos.getY() - offset - viewY, pos.getZ() - offset - viewZ,
                pos.getX() + 1.0 + offset - viewX, pos.getY() + 1.0 + offset - viewY, pos.getZ() + 1.0 + offset - viewZ);

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GL11.glLineWidth(3.0f);
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.color(1.0f, 1.0f, 1.0f, alpha);
        RenderGlobal.drawSelectionBoundingBox(box);

        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.popMatrix();
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public void onMouse(MouseEvent e) {
        if ((this.placing || this.resetting || this.hasAim) && e.button > -1) {
            e.setCanceled(true);
        }
    }

    private void runPrePlayerInteract() {
        boolean active;
        if (Clutch.mc.thePlayer.onGround) {
            this.clutchBlocksPlaced = 0;
        }
        int ticksExisted = Clutch.mc.thePlayer.ticksExisted;
        this.updateAutoClutch(ticksExisted);
        boolean bl = active = this.selectKeybind.isPressed() || this.autoClutchActive;
        if (Clutch.mc.currentScreen != null || !active) {
            this.clearAim(true);
            this.disablePlacing(false);
            return;
        }
        BlockPos below = new BlockPos(MathHelper.floor_double((double)Clutch.mc.thePlayer.posX), MathHelper.floor_double((double)Clutch.mc.thePlayer.posY) - 1, MathHelper.floor_double((double)Clutch.mc.thePlayer.posZ));
        if (!this.canPlaceThrough(below)) {
            this.disablePlacing(false);
            return;
        }
        int weakSlot = this.pickBlockSlot();
        if (weakSlot == -1) {
            this.disablePlacing(false);
            return;
        }
        this.plannedSlot = weakSlot;
        AimResult target = this.clutchAim();
        if (target != null) {
            this.targetHitPos = target.ray.getBlockPos();
            this.targetSide = target.ray.sideHit;
            this.aimYaw = target.yaw;
            this.aimPitch = target.pitch;
            this.hasAim = true;
            this.resetting = false;
        }
        if (this.hasAim && !this.placing) {
            this.enablePlacing();
        }
        if (this.placing || this.resetting || this.hasAim) {
            KeyBinding.setKeyBindState((int)Clutch.mc.gameSettings.keyBindAttack.getKeyCode(), (boolean)false);
            KeyBinding.setKeyBindState((int)Clutch.mc.gameSettings.keyBindUseItem.getKeyCode(), (boolean)false);
            this.equipPlannedSlot();
        }
    }

    private void updateAutoClutch(int ticksExisted) {
        if (this.autoClutch.isToggled()) {
            int curHurtTime = Clutch.mc.thePlayer.hurtTime;
            if (curHurtTime > this.prevHurtTime) {
                this.autoClutchChecking = true;
                this.autoClutchCheckCounter = 0;
                this.autoClutchLandedGuard = false;
            }
            this.prevHurtTime = curHurtTime;
            if (this.autoClutchChecking && !this.autoClutchActive && !this.autoClutchLandedGuard) {
                if ((this.autoClutchCheckCounter == 0 || this.autoClutchCheckCounter % 3 == 0) && this.willFallFar(this.minimumFallDistance.getInput())) {
                    this.autoClutchActive = true;
                }
                ++this.autoClutchCheckCounter;
            }
            if (this.autoClutchLandedGuard) {
                boolean airborneUp;
                boolean expired = ticksExisted - this.autoClutchLandedTick >= 10;
                boolean jumped = Clutch.mc.gameSettings.keyBindJump.isKeyDown();
                boolean bl = airborneUp = !Clutch.mc.thePlayer.onGround && Clutch.mc.thePlayer.motionY > 0.0;
                if (expired || jumped || airborneUp) {
                    this.autoClutchActive = false;
                    this.autoClutchChecking = false;
                    this.autoClutchLandedGuard = false;
                }
            }
            if (this.autoClutchActive && Clutch.mc.thePlayer.onGround && Clutch.mc.thePlayer.hurtTime < Clutch.mc.thePlayer.maxHurtTime - 2 && !this.autoClutchLandedGuard) {
                this.autoClutchLandedGuard = true;
                this.autoClutchLandedTick = ticksExisted;
                if (!this.willFallSoon()) {
                    this.autoClutchActive = false;
                    this.autoClutchChecking = false;
                    this.autoClutchLandedGuard = false;
                }
            }
            if (!this.autoClutchActive && !this.autoClutchLandedGuard && Clutch.mc.thePlayer.onGround && Clutch.mc.thePlayer.hurtTime == 0) {
                this.autoClutchChecking = false;
                this.autoClutchCheckCounter = 0;
            }
        } else {
            this.autoClutchActive = false;
            this.autoClutchChecking = false;
            this.autoClutchLandedGuard = false;
            this.prevHurtTime = Clutch.mc.thePlayer.hurtTime;
        }
    }

    private void enablePlacing() {
        if (this.placing) {
            return;
        }
        this.placing = true;
        if (!this.slotWasSwapped) {
            this.prevSlot = Clutch.mc.thePlayer.inventory.currentItem;
        }
    }

    private void disablePlacing(boolean forceRestore) {
        if (!this.placing && !forceRestore) {
            return;
        }
        this.placing = false;
        this.plannedSlot = -1;
        if ((forceRestore || !this.hasAim) && this.slotWasSwapped && this.prevSlot != -1 && this.prevSlot != Clutch.mc.thePlayer.inventory.currentItem) {
            Clutch.mc.thePlayer.inventory.currentItem = this.prevSlot;
            this.slotWasSwapped = false;
        }
        if (forceRestore) {
            this.prevSlot = -1;
            this.restoreInputs();
        }
    }

    private void clearAim(boolean allowSnapback) {
        if (this.slotWasSwapped && this.prevSlot != -1 && this.prevSlot != Clutch.mc.thePlayer.inventory.currentItem) {
            Clutch.mc.thePlayer.inventory.currentItem = this.prevSlot;
            this.slotWasSwapped = false;
        }
        this.targetHitPos = null;
        this.targetSide = null;
        this.lastPlaced = null;
        this.clutchBlocksPlaced = 0;
        if (allowSnapback && this.hasAim) {
            this.resetting = true;
        }
        this.hasAim = false;
        this.prevSlot = -1;
    }

    private void restoreInputs() {
        if (Clutch.mc.currentScreen == null) {
            KeyBinding.setKeyBindState((int)Clutch.mc.gameSettings.keyBindAttack.getKeyCode(), (boolean)Mouse.isButtonDown((int)0));
            KeyBinding.setKeyBindState((int)Clutch.mc.gameSettings.keyBindUseItem.getKeyCode(), (boolean)Mouse.isButtonDown((int)1));
        }
    }

    private boolean willFallFar(double minFall) {
        double startY = Clutch.mc.thePlayer.posY;
        PredictionState prediction = PredictionState.fromPlayer();
        for (int t = 0; t < 60; ++t) {
            prediction.tick(false);
            if (prediction.onGround) {
                return false;
            }
            double fall = startY - prediction.posY;
            if (!(fall > minFall)) continue;
            return true;
        }
        return false;
    }

    private boolean willFallSoon() {
        PredictionState prediction = PredictionState.fromPlayer();
        for (int t = 0; t < 10; ++t) {
            prediction.tick(true);
            if (prediction.onGround || !(prediction.motionY < 0.0)) continue;
            return true;
        }
        return false;
    }

    private AimResult clutchAim() {
        Vec3 playerPos = new Vec3(Clutch.mc.thePlayer.posX, Clutch.mc.thePlayer.posY, Clutch.mc.thePlayer.posZ);
        Vec3 eye = Clutch.mc.thePlayer.getPositionEyes(1.0f);
        Vec3 futurePos = playerPos;
        if (this.simulateFuturePosition.isToggled()) {
            PredictionState prediction = PredictionState.fromPlayer();
            for (int t = 0; t < 20; ++t) {
                prediction.tick(false);
                if (prediction.posY < playerPos.yCoord - 2.0 || prediction.onGround) break;
            }
            futurePos = prediction.getPos();
        }
        int feetX = MathHelper.floor_double((double)playerPos.xCoord);
        int feetZ = MathHelper.floor_double((double)playerPos.zCoord);
        int feetY = MathHelper.floor_double((double)playerPos.yCoord);
        int minX = feetX - 5;
        int maxX = feetX + 4;
        int minZ = feetZ - 5;
        int maxZ = feetZ + 4;
        int maxY = feetY - 1;
        int minY = feetY - 4;
        ArrayList<BlockCandidate> candidates = new ArrayList<BlockCandidate>();
        for (int y = maxY; y >= minY; --y) {
            for (int x = minX; x <= maxX; ++x) {
                for (int z = minZ; z <= maxZ; ++z) {
                    double score;
                    BlockPos pos = new BlockPos(x, y, z);
                    if (this.canPlaceThrough(pos)) continue;
                    double currentDist = BlockUtils.dist2PointAABB(playerPos, pos);
                    double futureDist = BlockUtils.dist2PointAABB(futurePos, pos);
                    double d = score = this.simulateFuturePosition.isToggled() ? currentDist * 0.3 + futureDist * 0.7 : currentDist;
                    if (pos.equals((Object)this.lastPlaced)) {
                        score *= 0.95;
                    }
                    candidates.add(new BlockCandidate(score, pos));
                }
            }
        }
        candidates.sort((a, b) -> Double.compare(a.score, b.score));
        ItemStack held = this.plannedSlot >= 0 && this.plannedSlot <= 8 ? Clutch.mc.thePlayer.inventory.mainInventory[this.plannedSlot] : null;
        for (BlockCandidate candidate : candidates) {
            boolean underPlayer = this.isBlockUnderPlayer(candidate.pos, playerPos);
            AimResult result = this.getBestRotationsToBlock(held, candidate.pos, eye, this.reach.getInput(), underPlayer);
            if (result == null) continue;
            return result;
        }
        return null;
    }

    private boolean isBlockUnderPlayer(BlockPos blockPos, Vec3 pos) {
        if (blockPos.getY() >= MathHelper.floor_double((double)pos.yCoord)) {
            return false;
        }
        for (double[] corner : CORNERS) {
            int cx = MathHelper.floor_double((double)(pos.xCoord + corner[0]));
            int cz = MathHelper.floor_double((double)(pos.zCoord + corner[1]));
            if (blockPos.getX() != cx || blockPos.getZ() != cz) continue;
            return true;
        }
        return false;
    }

    private AimResult getBestRotationsToBlock(ItemStack held, BlockPos targetCell, Vec3 eye, double reachVal, boolean underPlayer) {
        double inset = 0.05;
        double step = 0.2;
        double jitter = step * 0.1;
        boolean faceSouth = Math.abs(eye.zCoord - (double)(targetCell.getZ() + 1)) < Math.abs(eye.zCoord - (double)targetCell.getZ());
        boolean faceEast = Math.abs(eye.xCoord - (double)(targetCell.getX() + 1)) < Math.abs(eye.xCoord - (double)targetCell.getX());
        float baseYaw = Clutch.normYaw(RotationUtils.serverRotations[0]);
        float basePitch = RotationUtils.serverRotations[1];
        int n = (int)Math.round(1.0 / step);
        ArrayList<RotationCandidate> candidates = new ArrayList<RotationCandidate>();
        candidates.add(new RotationCandidate(0.0, baseYaw, basePitch));
        for (int row = 0; row <= n; ++row) {
            double v = Clutch.clamp01((double)row * step + Clutch.randomRange(-jitter, jitter));
            for (int col = 0; col <= n; ++col) {
                double u = Clutch.clamp01((double)col * step + Clutch.randomRange(-jitter, jitter));
                if (underPlayer) {
                    float[] rV = Clutch.getRotationsWrapped(eye, (double)targetCell.getX() + u, (double)(targetCell.getY() + 1) - inset, (double)targetCell.getZ() + v);
                    double costV = Math.abs(Clutch.wrapYawDelta(baseYaw, rV[0])) + Math.abs(rV[1] - basePitch);
                    candidates.add(new RotationCandidate(costV, rV[0], rV[1]));
                }
                float[] rZ = Clutch.getRotationsWrapped(eye, (double)targetCell.getX() + u, (double)targetCell.getY() + v, faceSouth ? (double)(targetCell.getZ() + 1) - inset : (double)targetCell.getZ() + inset);
                double costZ = Math.abs(Clutch.wrapYawDelta(baseYaw, rZ[0])) + Math.abs(rZ[1] - basePitch);
                candidates.add(new RotationCandidate(costZ, rZ[0], rZ[1]));
                float[] rX = Clutch.getRotationsWrapped(eye, faceEast ? (double)(targetCell.getX() + 1) - inset : (double)targetCell.getX() + inset, (double)targetCell.getY() + v, (double)targetCell.getZ() + u);
                double costX = Math.abs(Clutch.wrapYawDelta(baseYaw, rX[0])) + Math.abs(rX[1] - basePitch);
                candidates.add(new RotationCandidate(costX, rX[0], rX[1]));
            }
        }
        candidates.sort((a, b) -> Double.compare(a.cost, b.cost));
        for (RotationCandidate candidate : candidates) {
            EnumFacing face;
            float yaw = Clutch.unwrapYaw(candidate.yaw, RotationUtils.serverRotations[0]);
            MovingObjectPosition ray = RotationUtils.rayCastBlock(reachVal, yaw, candidate.pitch);
            if (ray == null || (face = ray.sideHit) == EnumFacing.DOWN || face == EnumFacing.UP && !underPlayer || !targetCell.equals((Object)ray.getBlockPos()) || !BlockUtils.canPlaceBlockOnSide(held, ray.getBlockPos(), face)) continue;
            return new AimResult(ray, yaw, candidate.pitch);
        }
        return null;
    }

    private int pickBlockSlot() {
        boolean playingBedwars;
        boolean bl = playingBedwars = Utils.getBedwarsStatus() == 2;
        if (!playingBedwars) {
            int current = Clutch.mc.thePlayer.inventory.currentItem;
            if (this.isBlockSlot(current)) {
                return current;
            }
            for (int slot = 8; slot >= 0; --slot) {
                if (!this.isBlockSlot(slot)) continue;
                return slot;
            }
            return -1;
        }
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int slot = 8; slot >= 0; --slot) {
            Integer score;
            Block block;
            ResourceLocation id;
            ItemStack stack = Clutch.mc.thePlayer.inventory.mainInventory[slot];
            if (stack == null || stack.stackSize == 0 || !(stack.getItem() instanceof ItemBlock)) {
                continue;
            }
            block = ((ItemBlock) stack.getItem()).getBlock();
            id = Block.blockRegistry.getNameForObject(block);
            if (id == null || (score = BLOCK_SCORE.get(id.getResourcePath())) == null || score <= bestScore) {
                continue;
            }
            bestScore = score;
            best = slot;
        }
        return best;
    }

    private boolean isBlockSlot(int slot) {
        if (slot < 0 || slot > 8) {
            return false;
        }
        ItemStack stack = Clutch.mc.thePlayer.inventory.mainInventory[slot];
        return stack != null && stack.stackSize > 0 && stack.getItem() instanceof ItemBlock;
    }

    private void equipPlannedSlot() {
        int current = Clutch.mc.thePlayer.inventory.currentItem;
        if (this.plannedSlot != -1 && this.plannedSlot != current) {
            Clutch.mc.thePlayer.inventory.currentItem = this.plannedSlot;
            this.slotWasSwapped = true;
        }
    }

    private float[] getRotationsSmoothed(float currentYaw, float currentPitch, float targetYaw, float targetPitch, boolean snapback) {
        float curYaw = currentYaw;
        float curPitch = currentPitch;
        float deltaYaw = MathHelper.wrapAngleTo180_float((float)(targetYaw - curYaw));
        float deltaPitch = targetPitch - curPitch;
        if (Math.abs(deltaYaw) < 0.1f) {
            curYaw = targetYaw;
        }
        if (Math.abs(deltaPitch) < 0.1f) {
            curPitch = targetPitch;
        }
        if (curYaw == targetYaw && curPitch == targetPitch) {
            return new float[]{curYaw, RotationUtils.clampPitch(curPitch)};
        }
        float maxStep = (float)(snapback ? this.snapbackSpeed.getInput() : this.speed.getInput());
        float factor = 1.0f - (float)Clutch.randomRange(0.0, 0.2);
        maxStep *= factor;
        float totalDelta = Math.abs(deltaYaw) + Math.abs(deltaPitch);
        if (totalDelta <= maxStep) {
            curYaw = targetYaw;
            curPitch = targetPitch;
        } else if (maxStep > 0.0f) {
            float scale = maxStep / totalDelta;
            curYaw += deltaYaw * scale;
            curPitch += deltaPitch * scale;
        }
        return new float[]{curYaw, RotationUtils.clampPitch(curPitch)};
    }

    private boolean canPlaceThrough(BlockPos pos) {
        Block block = BlockUtils.getBlock(pos);
        Material material = block.getMaterial();
        return material == Material.air || material == Material.water || material == Material.lava || block == Blocks.fire;
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    private static double randomRange(double min, double max) {
        return min + Math.random() * (max - min);
    }

    private static float normYaw(float yaw) {
        return (yaw = (yaw % 360.0f + 360.0f) % 360.0f) > 180.0f ? yaw - 360.0f : yaw;
    }

    private static float wrapYawDelta(float base, float target) {
        return MathHelper.wrapAngleTo180_float((float)(target - base));
    }

    private static float unwrapYaw(float yaw, float prevYaw) {
        return prevYaw + MathHelper.wrapAngleTo180_float((float)(yaw - prevYaw));
    }

    private static float[] getRotationsWrapped(Vec3 eye, double tx, double ty, double tz) {
        double dx = tx - eye.xCoord;
        double dy = ty - eye.yCoord;
        double dz = tz - eye.zCoord;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float)Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float)Math.toDegrees(-Math.atan2(dy, horizontalDistance));
        return new float[]{Clutch.normYaw(yaw), RotationUtils.clampPitch(pitch)};
    }

    static {
        BLOCK_SCORE.put("obsidian", 0);
        BLOCK_SCORE.put("end_stone", 1);
        BLOCK_SCORE.put("planks", 2);
        BLOCK_SCORE.put("log", 2);
        BLOCK_SCORE.put("log2", 2);
        BLOCK_SCORE.put("glass", 3);
        BLOCK_SCORE.put("stained_glass", 3);
        BLOCK_SCORE.put("hardened_clay", 4);
        BLOCK_SCORE.put("stained_hardened_clay", 4);
        BLOCK_SCORE.put("stone", 5);
        BLOCK_SCORE.put("wool", 5);
    }

    private static class PredictionState {
        private AxisAlignedBB box;
        private double motionX;
        private double motionY;
        private double motionZ;
        private double posY;
        private boolean onGround;

        private PredictionState() {
        }

        static PredictionState fromPlayer() {
            PredictionState state = new PredictionState();
            state.box = mc.thePlayer.getEntityBoundingBox();
            state.motionX = mc.thePlayer.motionX;
            state.motionY = mc.thePlayer.motionY;
            state.motionZ = mc.thePlayer.motionZ;
            state.posY = mc.thePlayer.posY;
            state.onGround = mc.thePlayer.onGround;
            return state;
        }

        Vec3 getPos() {
            return new Vec3((this.box.minX + this.box.maxX) / 2.0, this.box.minY, (this.box.minZ + this.box.maxZ) / 2.0);
        }

        void tick(boolean stopHorizontal) {
            if (stopHorizontal) {
                this.motionX = 0.0;
                this.motionZ = 0.0;
            }
            this.motionY -= 0.08;
            this.move(this.motionX, this.motionY, this.motionZ);
            this.motionY *= (double)0.98f;
            this.motionX *= 0.91;
            this.motionZ *= 0.91;
        }

        private void move(double x, double y, double z) {
            double originalX = x;
            double originalY = y;
            double originalZ = z;
            List<AxisAlignedBB> collisions = mc.theWorld.getCollidingBoundingBoxes((Entity) mc.thePlayer, this.box.addCoord(x, y, z));
            for (AxisAlignedBB collision : collisions) {
                y = collision.calculateYOffset(this.box, y);
            }
            this.box = this.box.offset(0.0, y, 0.0);
            for (AxisAlignedBB collision : collisions) {
                x = collision.calculateXOffset(this.box, x);
            }
            this.box = this.box.offset(x, 0.0, 0.0);
            for (AxisAlignedBB collision : collisions) {
                z = collision.calculateZOffset(this.box, z);
            }
            this.box = this.box.offset(0.0, 0.0, z);
            this.onGround = originalY != y && originalY < 0.0;
            this.posY = this.box.minY;
            if (originalX != x) {
                this.motionX = 0.0;
            }
            if (originalY != y) {
                this.motionY = 0.0;
            }
            if (originalZ != z) {
                this.motionZ = 0.0;
            }
        }
    }

    private static class AimResult {
        final MovingObjectPosition ray;
        final float yaw;
        final float pitch;

        AimResult(MovingObjectPosition ray, float yaw, float pitch) {
            this.ray = ray;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static class RotationCandidate {
        final double cost;
        final float yaw;
        final float pitch;

        RotationCandidate(double cost, float yaw, float pitch) {
            this.cost = cost;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static class BlockCandidate {
        final double score;
        final BlockPos pos;

        BlockCandidate(double score, BlockPos pos) {
            this.score = score;
            this.pos = pos;
        }
    }

    private static class PlacedBlockAnim {
        final BlockPos pos;
        final long placedAt;

        PlacedBlockAnim(BlockPos pos, long placedAt) {
            this.pos = pos;
            this.placedAt = placedAt;
        }
    }
}