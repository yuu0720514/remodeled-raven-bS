package keystrokesmod.module.impl.player;

import keystrokesmod.event.PreAttackEvent;
import keystrokesmod.event.PreMotionEvent;
import keystrokesmod.event.PrePlayerInputEvent;
import keystrokesmod.event.PreSlotScrollEvent;
import keystrokesmod.event.RightClickMouseEvent;
import keystrokesmod.event.SlotUpdateEvent;
import keystrokesmod.event.StrafeEvent;
import keystrokesmod.helper.RotationHelper;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.movement.LongJump;
import keystrokesmod.module.impl.render.HUD;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockAir;
import net.minecraft.block.BlockSnow;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.util.Vec3i;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;

public class Scaffold extends Module {

    private static final double[] PLACE_OFFSETS = new double[]{
            0.03125, 0.09375, 0.15625, 0.21875, 0.28125, 0.34375, 0.40625, 0.46875,
            0.53125, 0.59375, 0.65625, 0.71875, 0.78125, 0.84375, 0.90625, 0.96875
    };

    private static final String[] ROTATION_MODES = {"NONE", "DEFAULT", "BACKWARDS", "SIDEWAYS"};
    private static final String[] MOVE_FIX_MODES = {"NONE", "SILENT"};
    private static final String[] SPRINT_MODES = {"NONE", "VANILLA"};
    private static final String[] TOWER_MODES = {"NONE", "VANILLA", "EXTRA", "TELLY"};
    private static final String[] KEEP_Y_MODES = {"NONE", "VANILLA", "EXTRA", "TELLY"};

    public final SliderSetting rotationMode = new SliderSetting("Rotations", 2, ROTATION_MODES);
    public final SliderSetting moveFix = new SliderSetting("Move fix", 1, MOVE_FIX_MODES);
    public final SliderSetting sprintMode = new SliderSetting("Sprint", 0, SPRINT_MODES);
    public final SliderSetting groundMotion = new SliderSetting("Ground motion", "%", 100, 0, 100, 1);
    public final SliderSetting airMotion = new SliderSetting("Air motion", "%", 100, 0, 100, 1);
    public final SliderSetting speedMotion = new SliderSetting("Speed motion", "%", 100, 0, 100, 1);
    public final SliderSetting tower = new SliderSetting("Tower", 0, TOWER_MODES);
    public final SliderSetting keepY = new SliderSetting("Keep Y", 0, KEEP_Y_MODES);
    public final ButtonSetting keepYonPress = new ButtonSetting("Keep Y on press", false);
    public final ButtonSetting disableWhileJumpActive = new ButtonSetting("No keep Y on jump potion", false);
    public final ButtonSetting multiplace = new ButtonSetting("Multi place", true);
    public final ButtonSetting safeWalk = new ButtonSetting("Safe walk", true);
    public final ButtonSetting swing = new ButtonSetting("Swing", true);
    public final ButtonSetting itemSpoof = new ButtonSetting("Item spoof", false);
    public final ButtonSetting blockCounter = new ButtonSetting("Block counter", true);

    private int rotationTick = 0;
    private int lastSlot = -1;
    private int blockCount = -1;
    private float yaw = -180.0F;
    private float pitch = 0.0F;
    private boolean canRotate = false;
    private int towerTick = 0;
    private int towerDelay = 0;
    private int stage = 0;
    private int startY = 256;
    private boolean shouldKeepY = false;
    private boolean towering = false;
    private EnumFacing targetFacing = null;

    public Scaffold() {
        super("Scaffold", Module.category.player);
        this.registerSetting(rotationMode);
        this.registerSetting(moveFix);
        this.registerSetting(sprintMode);
        this.registerSetting(groundMotion);
        this.registerSetting(airMotion);
        this.registerSetting(speedMotion);
        this.registerSetting(tower);
        this.registerSetting(keepY);
        this.registerSetting(keepYonPress);
        this.registerSetting(disableWhileJumpActive);
        this.registerSetting(multiplace);
        this.registerSetting(safeWalk);
        this.registerSetting(swing);
        this.registerSetting(itemSpoof);
        this.registerSetting(blockCounter);
    }

    @Override
    public void guiUpdate() {
        boolean keepYActive = keepY.getInput() != 0;
        keepYonPress.setVisible(keepYActive, this);
        disableWhileJumpActive.setVisible(keepYActive, this);
    }

    @Override
    public String getInfo() {
        if (isTowering()) {
            return TOWER_MODES[(int) tower.getInput()];
        }
        if (keepY.getInput() != 0 && stage > 0) {
            return KEEP_Y_MODES[(int) keepY.getInput()];
        }
        return "";
    }

    public static boolean canSafeWalk() {
        if (ModuleManager.scaffold == null || !ModuleManager.scaffold.isEnabled() || !ModuleManager.scaffold.safeWalk.isToggled()) {
            return false;
        }
        if (!Utils.nullCheck() || !mc.thePlayer.onGround || mc.thePlayer.motionY > 0.0) {
            return false;
        }
        return canMove(mc.thePlayer.motionX, mc.thePlayer.motionZ, -1.0);
    }

    public int getSlot() {
        return lastSlot;
    }

    @Override
    public void onEnable() {
        if (mc.thePlayer != null) {
            lastSlot = mc.thePlayer.inventory.currentItem;
        } else {
            lastSlot = -1;
        }
        blockCount = -1;
        rotationTick = 3;
        yaw = -180.0F;
        pitch = 0.0F;
        canRotate = false;
        towerTick = 0;
        towerDelay = 0;
        towering = false;
    }

    @Override
    public void onDisable() {
        if (mc.thePlayer != null && lastSlot != -1) {
            mc.thePlayer.inventory.currentItem = lastSlot;
        }
        RotationHelper.get().forceMovementFix = false;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPreMotion(PreMotionEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }

        if (rotationTick > 0) {
            rotationTick--;
        }

        if (mc.thePlayer.onGround) {
            if (stage > 0) {
                stage--;
            }
            if (stage < 0) {
                stage++;
            }
            if (stage == 0
                    && keepY.getInput() != 0
                    && (!keepYonPress.isToggled() || Utils.isBindDown(mc.gameSettings.keyBindUseItem))
                    && (!disableWhileJumpActive.isToggled() || !mc.thePlayer.isPotionActive(Potion.jump))
                    && !mc.gameSettings.keyBindJump.isKeyDown()) {
                stage = 1;
            }
            startY = shouldKeepY ? startY : MathHelper.floor_double(mc.thePlayer.posY);
            shouldKeepY = false;
            towering = false;
        }

        if (!canPlace()) {
            return;
        }

        ItemStack stack = mc.thePlayer.getHeldItem();
        int count = isBlock(stack) ? stack.stackSize : 0;
        blockCount = Math.min(blockCount, count);
        if (blockCount <= 0) {
            int slot = mc.thePlayer.inventory.currentItem;
            if (blockCount == 0) {
                slot--;
            }
            for (int i = slot; i > slot - 9; i--) {
                int hotbarSlot = (i % 9 + 9) % 9;
                ItemStack candidate = mc.thePlayer.inventory.getStackInSlot(hotbarSlot);
                if (isBlock(candidate)) {
                    mc.thePlayer.inventory.currentItem = hotbarSlot;
                    blockCount = candidate.stackSize;
                    break;
                }
            }
        }

        float eventYaw = event.getYaw();
        float currentYaw = getCurrentYaw();
        float yawDiffTo180 = wrapAngleDiff(currentYaw - 180.0F, eventYaw);
        float diagonalYaw = isDiagonal(currentYaw)
                ? yawDiffTo180
                : wrapAngleDiff(currentYaw - 135.0F * ((currentYaw + 180.0F) % 90.0F < 45.0F ? 1.0F : -1.0F), eventYaw);

        if (!canRotate) {
            switch ((int) rotationMode.getInput()) {
                case 1:
                    if (yaw == -180.0F && pitch == 0.0F) {
                        yaw = quantizeAngle(diagonalYaw);
                        pitch = quantizeAngle(85.0F);
                    } else {
                        yaw = quantizeAngle(diagonalYaw);
                    }
                    break;
                case 2:
                    if (yaw == -180.0F && pitch == 0.0F) {
                        yaw = quantizeAngle(yawDiffTo180);
                        pitch = quantizeAngle(85.0F);
                    } else {
                        yaw = quantizeAngle(yawDiffTo180);
                    }
                    break;
                case 3:
                    if (yaw == -180.0F && pitch == 0.0F) {
                        yaw = quantizeAngle(diagonalYaw);
                        pitch = quantizeAngle(85.0F);
                    } else {
                        yaw = quantizeAngle(diagonalYaw);
                    }
                    break;
                default:
                    break;
            }
        }

        BlockData blockData = getBlockData();
        Vec3 hitVec = null;
        if (blockData != null) {
            double[] x = PLACE_OFFSETS;
            double[] y = PLACE_OFFSETS;
            double[] z = PLACE_OFFSETS;
            switch (blockData.facing()) {
                case NORTH:
                    z = new double[]{0.0};
                    break;
                case EAST:
                    x = new double[]{1.0};
                    break;
                case SOUTH:
                    z = new double[]{1.0};
                    break;
                case WEST:
                    x = new double[]{0.0};
                    break;
                case DOWN:
                    y = new double[]{0.0};
                    break;
                case UP:
                    y = new double[]{1.0};
                    break;
                default:
                    break;
            }

            float bestYaw = -180.0F;
            float bestPitch = 0.0F;
            float bestDiff = 0.0F;
            double reach = mc.playerController.getBlockReachDistance();

            for (double dx : x) {
                for (double dy : y) {
                    for (double dz : z) {
                        double relX = blockData.blockPos().getX() + dx - mc.thePlayer.posX;
                        double relY = blockData.blockPos().getY() + dy - mc.thePlayer.posY - mc.thePlayer.getEyeHeight();
                        double relZ = blockData.blockPos().getZ() + dz - mc.thePlayer.posZ;
                        float baseYaw = wrapAngleDiff(yaw, eventYaw);
                        float[] rotations = getRotationsTo(relX, relY, relZ, baseYaw, pitch);
                        MovingObjectPosition mop = rayTrace(rotations[0], rotations[1], reach);
                        if (mop != null
                                && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                                && mop.getBlockPos().equals(blockData.blockPos())
                                && mop.sideHit == blockData.facing()) {
                            float totalDiff = Math.abs(rotations[0] - baseYaw) + Math.abs(rotations[1] - pitch);
                            if ((bestYaw == -180.0F && bestPitch == 0.0F) || totalDiff < bestDiff) {
                                bestYaw = rotations[0];
                                bestPitch = rotations[1];
                                bestDiff = totalDiff;
                                hitVec = mop.hitVec;
                            }
                        }
                    }
                }
            }

            if (bestYaw != -180.0F || bestPitch != 0.0F) {
                yaw = bestYaw;
                pitch = bestPitch;
                canRotate = true;
            }
        }

        if (canRotate && isForwardPressed() && Math.abs(MathHelper.wrapAngleTo180_float(yawDiffTo180 - yaw)) < 90.0F) {
            switch ((int) rotationMode.getInput()) {
                case 2:
                    yaw = quantizeAngle(yawDiffTo180);
                    break;
                case 3:
                    yaw = quantizeAngle(diagonalYaw);
                    break;
                default:
                    break;
            }
        }

        if (rotationMode.getInput() != 0) {
            float targetYaw = yaw;
            float targetPitch = pitch;
            if (towering && (mc.thePlayer.motionY > 0.0 || mc.thePlayer.posY > startY + 1)) {
                float yawDiff = MathHelper.wrapAngleTo180_float(yaw - eventYaw);
                float tolerance = rotationTick >= 2
                        ? (float) Utils.randomizeDouble(90.0, 95.0)
                        : (float) Utils.randomizeDouble(30.0, 35.0);
                if (Math.abs(yawDiff) > tolerance) {
                    float clampedYaw = clampAngle(yawDiff, tolerance);
                    targetYaw = quantizeAngle(eventYaw + clampedYaw);
                    rotationTick = Math.max(rotationTick, 1);
                }
            }
            if (isTowering()) {
                float yawDelta = MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - eventYaw);
                targetYaw = quantizeAngle(eventYaw + yawDelta * (float) Utils.randomizeDouble(0.98, 0.99));
                targetPitch = quantizeAngle((float) Utils.randomizeDouble(30.0, 80.0));
                rotationTick = 3;
                towering = true;
            }
            event.setRotations(targetYaw, targetPitch);
            if (moveFix.getInput() == 1) {
                RotationHelper.get().forceMovementFix = true;
                RotationHelper.get().setRotations(targetYaw, targetPitch);
            }
        }

        if (blockData != null && hitVec != null && rotationTick <= 0) {
            place(blockData.blockPos(), blockData.facing(), hitVec);
            if (multiplace.isToggled()) {
                for (int i = 0; i < 3; i++) {
                    blockData = getBlockData();
                    if (blockData == null) {
                        break;
                    }
                    MovingObjectPosition mop = rayTrace(yaw, pitch, mc.playerController.getBlockReachDistance());
                    if (mop != null
                            && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                            && mop.getBlockPos().equals(blockData.blockPos())
                            && mop.sideHit == blockData.facing()) {
                        place(blockData.blockPos(), blockData.facing(), mop.hitVec);
                    } else {
                        hitVec = getClickVec(blockData.blockPos(), blockData.facing());
                        double dx = hitVec.xCoord - mc.thePlayer.posX;
                        double dy = hitVec.yCoord - mc.thePlayer.posY - mc.thePlayer.getEyeHeight();
                        double dz = hitVec.zCoord - mc.thePlayer.posZ;
                        float[] rotations = getRotationsTo(dx, dy, dz, eventYaw, event.getPitch());
                        if (Math.abs(rotations[0] - yaw) >= 120.0F || Math.abs(rotations[1] - pitch) >= 60.0F) {
                            break;
                        }
                        mop = rayTrace(rotations[0], rotations[1], mc.playerController.getBlockReachDistance());
                        if (mop == null
                                || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                                || !mop.getBlockPos().equals(blockData.blockPos())
                                || mop.sideHit != blockData.facing()) {
                            break;
                        }
                        place(blockData.blockPos(), blockData.facing(), mop.hitVec);
                    }
                }
            }
        }

        if (targetFacing != null) {
            if (rotationTick <= 0) {
                int playerBlockX = MathHelper.floor_double(mc.thePlayer.posX);
                int playerBlockY = MathHelper.floor_double(mc.thePlayer.posY);
                int playerBlockZ = MathHelper.floor_double(mc.thePlayer.posZ);
                BlockPos belowPlayer = new BlockPos(playerBlockX, playerBlockY - 1, playerBlockZ);
                hitVec = getHitVec(belowPlayer, targetFacing, yaw, pitch);
                place(belowPlayer, targetFacing, hitVec);
            }
            targetFacing = null;
        } else if (keepY.getInput() == 2 && stage > 0 && !mc.thePlayer.onGround) {
            int nextBlockY = MathHelper.floor_double(mc.thePlayer.posY + mc.thePlayer.motionY);
            if (nextBlockY <= startY && mc.thePlayer.posY > startY + 1) {
                shouldKeepY = true;
                blockData = getBlockData();
                if (blockData != null && rotationTick <= 0) {
                    hitVec = getHitVec(blockData.blockPos(), blockData.facing(), yaw, pitch);
                    place(blockData.blockPos(), blockData.facing(), hitVec);
                }
            }
        }
    }

    @SubscribeEvent
    public void onStrafe(StrafeEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }

        if (!mc.thePlayer.isCollidedHorizontally
                && mc.thePlayer.hurtTime <= 5
                && !mc.thePlayer.isPotionActive(Potion.jump)
                && mc.gameSettings.keyBindJump.isKeyDown()
                && isHoldingBlock()) {
            int yState = (int) (mc.thePlayer.posY % 1.0 * 100.0);
            switch ((int) tower.getInput()) {
                case 1:
                    switch (towerTick) {
                        case 0:
                            if (mc.thePlayer.onGround) {
                                towerTick = 1;
                                mc.thePlayer.motionY = -0.0784000015258789;
                            }
                            return;
                        case 1:
                            if (yState == 0 && isAirBelow()) {
                                startY = MathHelper.floor_double(mc.thePlayer.posY);
                                towerTick = 2;
                                mc.thePlayer.motionY = 0.42F;
                                if (isForwardPressed()) {
                                    setSpeed(getSpeed(), getMoveYaw());
                                } else {
                                    setSpeed(0.0);
                                    event.setForward(0.0F);
                                    event.setStrafe(0.0F);
                                }
                                return;
                            }
                            towerTick = 0;
                            return;
                        case 2:
                            towerTick = 3;
                            mc.thePlayer.motionY = 0.75 - mc.thePlayer.posY % 1.0;
                            return;
                        case 3:
                            towerTick = 1;
                            mc.thePlayer.motionY = 1.0 - mc.thePlayer.posY % 1.0;
                            return;
                        default:
                            towerTick = 0;
                            return;
                    }
                case 2:
                    switch (towerTick) {
                        case 0:
                            if (mc.thePlayer.onGround) {
                                towerTick = 1;
                                mc.thePlayer.motionY = -0.0784000015258789;
                            }
                            return;
                        case 1:
                            if (yState == 0 && isAirBelow()) {
                                startY = MathHelper.floor_double(mc.thePlayer.posY);
                                if (!isForwardPressed()) {
                                    towerDelay = 2;
                                    setSpeed(0.0);
                                    event.setForward(0.0F);
                                    event.setStrafe(0.0F);
                                    EnumFacing facing = yawToFacing(MathHelper.wrapAngleTo180_float(yaw - 180.0F));
                                    double distance = distanceToEdge(facing);
                                    if (distance > 0.1) {
                                        if (mc.thePlayer.onGround) {
                                            Vec3i directionVec = facing.getDirectionVec();
                                            double offset = Math.min(getRandomOffset(), distance - 0.05);
                                            double jitter = Utils.randomizeDouble(0.02, 0.03);
                                            AxisAlignedBB nextBox = mc.thePlayer.getEntityBoundingBox()
                                                    .offset(directionVec.getX() * (offset - jitter), 0.0, directionVec.getZ() * (offset - jitter));
                                            if (mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, nextBox).isEmpty()) {
                                                mc.thePlayer.motionY = -0.0784000015258789;
                                                mc.thePlayer.setPosition(
                                                        nextBox.minX + (nextBox.maxX - nextBox.minX) / 2.0,
                                                        nextBox.minY,
                                                        nextBox.minZ + (nextBox.maxZ - nextBox.minZ) / 2.0
                                                );
                                            }
                                            return;
                                        }
                                    } else {
                                        towerTick = 2;
                                        targetFacing = facing;
                                        mc.thePlayer.motionY = 0.42F;
                                    }
                                    return;
                                }
                                towerTick = 2;
                                towerDelay++;
                                mc.thePlayer.motionY = 0.42F;
                                setSpeed(getSpeed(), getMoveYaw());
                                return;
                            }
                            towerTick = 0;
                            return;
                        case 2:
                            towerTick = 3;
                            mc.thePlayer.motionY = mc.thePlayer.motionY - Utils.randomizeDouble(0.00101, 0.00109);
                            return;
                        case 3:
                            if (towerDelay >= 4) {
                                towerTick = 4;
                                towerDelay = 0;
                            } else {
                                towerTick = 1;
                                mc.thePlayer.motionY = 1.0 - mc.thePlayer.posY % 1.0;
                            }
                            return;
                        case 4:
                            towerTick = 5;
                            return;
                        case 5:
                            if (!isAirBelow()) {
                                towerTick = 0;
                            } else {
                                towerTick = 1;
                                mc.thePlayer.motionY -= 0.08;
                                mc.thePlayer.motionY *= 0.98F;
                                mc.thePlayer.motionY -= 0.08;
                                mc.thePlayer.motionY *= 0.98F;
                            }
                            return;
                        default:
                            towerTick = 0;
                            towerDelay = 0;
                            return;
                    }
                default:
                    towerTick = 0;
                    towerDelay = 0;
                    break;
            }
        } else {
            towerTick = 0;
            towerDelay = 0;
        }
    }

    @SubscribeEvent
    public void onMoveInput(PrePlayerInputEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }

        float speed = getMotionMultiplier();
        if (speed != 1.0F) {
            float forward = event.getForward();
            float strafe = event.getStrafe();
            if (forward != 0.0F && strafe != 0.0F) {
                forward *= (float) (1.0 / Math.sqrt(2.0));
                strafe *= (float) (1.0 / Math.sqrt(2.0));
            }
            event.setForward(forward * speed);
            event.setStrafe(strafe * speed);
        }

        if (shouldStopSprint()) {
            mc.thePlayer.setSprinting(false);
        }

        if (mc.thePlayer.onGround && stage > 0 && isForwardPressed()) {
            event.setJump(true);
        }
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck() || !blockCounter.isToggled()) {
            return;
        }

        int count = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.stackSize > 0 && stack.getItem() instanceof ItemBlock) {
                Block block = ((ItemBlock) stack.getItem()).getBlock();
                if (!BlockUtils.isInteractable(block) && isSolidBlock(block)) {
                    count += stack.stackSize;
                }
            }
        }

        float scale = HUD.getSelectedFontScale();
        boolean shadow = ModuleManager.hud != null && ModuleManager.hud.isEnabled();
        GlStateManager.pushMatrix();
        GlStateManager.scale(scale, scale, 0.0F);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        ScaledResolution resolution = new ScaledResolution(mc);
        mc.fontRendererObj.drawString(
                String.format("%d block%s left", count, count != 1 ? "s" : ""),
                (resolution.getScaledWidth() / 2.0F + mc.fontRendererObj.FONT_HEIGHT * 1.5F) / scale,
                resolution.getScaledHeight() / 2.0F / scale - mc.fontRendererObj.FONT_HEIGHT / 2.0F + 1.0F,
                (count > 0 ? Color.WHITE.getRGB() : new Color(255, 85, 85).getRGB()) | -1090519040,
                shadow
        );
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPreAttack(PreAttackEvent event) {
        if (Utils.nullCheck()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onRightClick(RightClickMouseEvent event) {
        if (Utils.nullCheck()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onSlotScroll(PreSlotScrollEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }
        if (lastSlot != -1) {
            mc.thePlayer.inventory.currentItem = lastSlot;
        }
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onSlotUpdate(SlotUpdateEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }
        if (lastSlot != -1) {
            mc.thePlayer.inventory.currentItem = lastSlot;
        }
        event.setCanceled(true);
    }

    private boolean shouldStopSprint() {
        if (isTowering()) {
            return false;
        }
        boolean keepStage = keepY.getInput() == 1 || keepY.getInput() == 2;
        return (!keepStage || stage <= 0) && sprintMode.getInput() == 0;
    }

    private boolean canPlace() {
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.isActivelyMining()) {
            return false;
        }
        if (ModuleManager.longJump != null && ModuleManager.longJump.isEnabled() && !LongJump.function) {
            return false;
        }
        return true;
    }

    private EnumFacing getBestFacing(BlockPos blockPos1, BlockPos blockPos3) {
        double offset = 0.0;
        EnumFacing enumFacing = null;
        for (EnumFacing facing : EnumFacing.VALUES) {
            if (facing == EnumFacing.DOWN) {
                continue;
            }
            BlockPos pos = blockPos1.offset(facing);
            if (pos.getY() <= blockPos3.getY()) {
                double distance = pos.distanceSqToCenter(
                        blockPos3.getX() + 0.5,
                        blockPos3.getY() + 0.5,
                        blockPos3.getZ() + 0.5
                );
                if (enumFacing == null || distance < offset || (distance == offset && facing == EnumFacing.UP)) {
                    offset = distance;
                    enumFacing = facing;
                }
            }
        }
        return enumFacing;
    }

    private BlockData getBlockData() {
        int feetY = MathHelper.floor_double(mc.thePlayer.posY);
        BlockPos targetPos = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                (stage != 0 && !shouldKeepY ? Math.min(feetY, startY) : feetY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ)
        );
        if (!isReplaceable(targetPos)) {
            return null;
        }

        ArrayList<BlockPos> positions = new ArrayList<>();
        double reach = mc.playerController.getBlockReachDistance();
        for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 0; y++) {
                for (int z = -4; z <= 4; z++) {
                    BlockPos pos = targetPos.add(x, y, z);
                    if (isReplaceable(pos)
                            || BlockUtils.isInteractable(BlockUtils.getBlock(pos))
                            || mc.thePlayer.getDistance(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > reach
                            || (stage != 0 && !shouldKeepY && pos.getY() >= startY)) {
                        continue;
                    }
                    for (EnumFacing facing : EnumFacing.VALUES) {
                        if (facing == EnumFacing.DOWN) {
                            continue;
                        }
                        BlockPos blockPos = pos.offset(facing);
                        if (isReplaceable(blockPos)) {
                            positions.add(pos);
                        }
                    }
                }
            }
        }

        if (positions.isEmpty()) {
            return null;
        }

        positions.sort(Comparator.comparingDouble(o ->
                o.distanceSqToCenter(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5)
        ));
        BlockPos blockPos = positions.get(0);
        EnumFacing facing = getBestFacing(blockPos, targetPos);
        return facing == null ? null : new BlockData(blockPos, facing);
    }

    private void place(BlockPos blockPos, EnumFacing enumFacing, Vec3 vec3) {
        if (isHoldingBlock() && blockCount > 0) {
            if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, mc.thePlayer.inventory.getCurrentItem(), blockPos, enumFacing, vec3)) {
                if (mc.playerController.getCurrentGameType() != GameType.CREATIVE) {
                    blockCount--;
                }
                if (swing.isToggled()) {
                    mc.thePlayer.swingItem();
                } else {
                    mc.thePlayer.sendQueue.addToSendQueue(new C0APacketAnimation());
                }
            }
        }
    }

    private EnumFacing yawToFacing(float yawValue) {
        if (yawValue < -135.0F || yawValue > 135.0F) {
            return EnumFacing.NORTH;
        }
        if (yawValue < -45.0F) {
            return EnumFacing.EAST;
        }
        return yawValue < 45.0F ? EnumFacing.SOUTH : EnumFacing.WEST;
    }

    private double distanceToEdge(EnumFacing enumFacing) {
        switch (enumFacing) {
            case NORTH:
                return mc.thePlayer.posZ - Math.floor(mc.thePlayer.posZ);
            case EAST:
                return Math.ceil(mc.thePlayer.posX) - mc.thePlayer.posX;
            case SOUTH:
                return Math.ceil(mc.thePlayer.posZ) - mc.thePlayer.posZ;
            case WEST:
            default:
                return mc.thePlayer.posX - Math.floor(mc.thePlayer.posX);
        }
    }

    private float getMotionMultiplier() {
        if (!mc.thePlayer.onGround) {
            return (float) airMotion.getInput() / 100.0F;
        }
        return getSpeedLevel() > 0
                ? (float) speedMotion.getInput() / 100.0F
                : (float) groundMotion.getInput() / 100.0F;
    }

    private double getRandomOffset() {
        return 0.2155 - Utils.randomizeDouble(1.0E-4, 9.0E-4);
    }

    private float getCurrentYaw() {
        return adjustYaw(mc.thePlayer.rotationYaw, getForwardValue(), getLeftValue());
    }

    private boolean isDiagonal(float yawValue) {
        float absYaw = Math.abs(yawValue % 90.0F);
        return absYaw > 20.0F && absYaw < 70.0F;
    }

    private boolean isTowering() {
        if (mc.thePlayer.onGround && isForwardPressed() && !isAirAbove()) {
            boolean keepYTelly = keepY.getInput() == 3;
            boolean towerTelly = tower.getInput() == 3;
            return keepYTelly && stage > 0 || towerTelly && mc.gameSettings.keyBindJump.isKeyDown();
        }
        return false;
    }

    private static boolean canMove(double x, double z, double y) {
        AxisAlignedBB boundingBox = mc.thePlayer.getEntityBoundingBox().offset(x, y, z);
        return mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, boundingBox).isEmpty();
    }

    private static boolean isAirBelow() {
        AxisAlignedBB axisAlignedBB = mc.thePlayer.getEntityBoundingBox().offset(0.0, -1.0, 0.0);
        return !mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, axisAlignedBB).isEmpty();
    }

    private static boolean isAirAbove() {
        AxisAlignedBB axisAlignedBB = mc.thePlayer.getEntityBoundingBox().offset(0.0, 1.0, 0.0);
        return !mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, axisAlignedBB).isEmpty();
    }

    private static boolean isReplaceable(BlockPos blockPos) {
        Block block = BlockUtils.getBlock(blockPos);
        if (!(block instanceof BlockAir) && !block.getMaterial().isReplaceable()) {
            if (block instanceof BlockSnow) {
                return block.getBlockBoundsMaxY() <= 0.125;
            }
            return false;
        }
        return true;
    }

    private static boolean isSolidBlock(Block block) {
        if (block instanceof BlockSnow) {
            return false;
        }
        return !BlockUtils.isInteractable(block) && !(block instanceof BlockAir);
    }

    private static boolean isBlock(ItemStack itemStack) {
        if (itemStack == null || itemStack.stackSize < 1 || !(itemStack.getItem() instanceof ItemBlock)) {
            return false;
        }
        Block block = ((ItemBlock) itemStack.getItem()).getBlock();
        return !BlockUtils.isInteractable(block) && isSolidBlock(block);
    }

    private static boolean isHoldingBlock() {
        return isBlock(mc.thePlayer.getHeldItem());
    }

    private static boolean isForwardPressed() {
        if (mc.gameSettings.keyBindForward.isKeyDown() != mc.gameSettings.keyBindBack.isKeyDown()) {
            return true;
        }
        return mc.gameSettings.keyBindLeft.isKeyDown() != mc.gameSettings.keyBindRight.isKeyDown();
    }

    private static int getForwardValue() {
        int forwardValue = 0;
        if (mc.gameSettings.keyBindForward.isKeyDown()) {
            forwardValue++;
        }
        if (mc.gameSettings.keyBindBack.isKeyDown()) {
            forwardValue--;
        }
        return forwardValue;
    }

    private static int getLeftValue() {
        int leftValue = 0;
        if (mc.gameSettings.keyBindLeft.isKeyDown()) {
            leftValue++;
        }
        if (mc.gameSettings.keyBindRight.isKeyDown()) {
            leftValue--;
        }
        return leftValue;
    }

    private static float adjustYaw(float yawValue, float forward, float strafe) {
        if (forward < 0.0F) {
            yawValue += 180.0F;
        }
        if (strafe != 0.0F) {
            float multiplier = forward == 0.0F ? 1.0F : 0.5F * Math.signum(forward);
            yawValue += -90.0F * multiplier * Math.signum(strafe);
        }
        return MathHelper.wrapAngleTo180_float(yawValue);
    }

    private static float getMoveYaw() {
        Float serverYaw = RotationHelper.get().getServerYaw();
        float baseYaw = serverYaw != null ? serverYaw : mc.thePlayer.rotationYaw;
        return adjustYaw(baseYaw, mc.thePlayer.movementInput.moveForward, mc.thePlayer.movementInput.moveStrafe);
    }

    private static double getSpeed() {
        return Math.hypot(mc.thePlayer.motionX, mc.thePlayer.motionZ);
    }

    private static void setSpeed(double speed, float yawValue) {
        mc.thePlayer.motionX = -Math.sin(Math.toRadians(yawValue)) * speed;
        mc.thePlayer.motionZ = Math.cos(Math.toRadians(yawValue)) * speed;
    }

    private static void setSpeed(double speed) {
        setSpeed(speed, getMoveYaw());
    }

    private static int getSpeedLevel() {
        if (mc.thePlayer.isPotionActive(Potion.moveSpeed)) {
            return mc.thePlayer.getActivePotionEffect(Potion.moveSpeed).getAmplifier() + 1;
        }
        return 0;
    }

    private static float wrapAngleDiff(float angle, float target) {
        return target + MathHelper.wrapAngleTo180_float(angle - target);
    }

    private static float clampAngle(float angle, float maxAngle) {
        maxAngle = Math.max(0.0F, Math.min(180.0F, maxAngle));
        if (angle > maxAngle) {
            return maxAngle;
        }
        if (angle < -maxAngle) {
            return -maxAngle;
        }
        return angle;
    }

    private static float smoothAngle(float angle, float smoothFactor) {
        return angle * (0.5F + 0.5F * (1.0F - Math.max(0.0F, Math.min(1.0F,
                (float) (smoothFactor + Utils.randomizeDouble(-0.1, 0.1))))));
    }

    private static float quantizeAngle(float angle) {
        return (float) (angle - angle % 0.0096F);
    }

    private static float[] getRotationsTo(double targetX, double targetY, double targetZ, float currentYaw, float currentPitch) {
        return getRotations(targetX, targetY, targetZ, currentYaw, currentPitch, 180.0F, 0.0F);
    }

    private static float[] getRotations(double targetX, double targetY, double targetZ, float currentYaw, float currentPitch, float maxAngle, float smoothFactor) {
        double horizontalDistance = Math.sqrt(targetX * targetX + targetZ * targetZ);
        float yawDelta = MathHelper.wrapAngleTo180_float(
                (float) (Math.atan2(targetZ, targetX) * 180.0 / Math.PI) - 90.0F - currentYaw
        );
        float pitchDelta = MathHelper.wrapAngleTo180_float(
                (float) (-Math.atan2(targetY, horizontalDistance) * 180.0 / Math.PI) - currentPitch
        );
        yawDelta = Math.abs(yawDelta) <= 1.0F ? 0.0F : smoothAngle(clampAngle(yawDelta, maxAngle), smoothFactor);
        pitchDelta = Math.abs(pitchDelta) <= 1.0F ? 0.0F : smoothAngle(clampAngle(pitchDelta, maxAngle), smoothFactor);
        return new float[]{quantizeAngle(currentYaw + yawDelta), quantizeAngle(currentPitch + pitchDelta)};
    }

    private static MovingObjectPosition rayTrace(float yawValue, float pitchValue, double distance) {
        return RotationUtils.rayCastBlock(distance, yawValue, pitchValue);
    }

    private static Vec3 getHitVec(BlockPos blockPos, EnumFacing enumFacing, float yawValue, float pitchValue) {
        MovingObjectPosition movingObjectPosition = rayTrace(yawValue, pitchValue, mc.playerController.getBlockReachDistance());
        if (movingObjectPosition != null
                && movingObjectPosition.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && movingObjectPosition.getBlockPos().equals(blockPos)
                && movingObjectPosition.sideHit == enumFacing) {
            return movingObjectPosition.hitVec;
        }
        return getClickVec(blockPos, enumFacing);
    }

    private static Vec3 getClickVec(BlockPos blockPos, EnumFacing enumFacing) {
        Block block = BlockUtils.getBlock(blockPos);
        Vec3 vec3 = new Vec3(
                blockPos.getX() + Math.min(Math.max(Utils.randomizeDouble(0.0, 1.0), block.getBlockBoundsMinX()), block.getBlockBoundsMaxX()),
                blockPos.getY() + Math.min(Math.max(Utils.randomizeDouble(0.0, 1.0), block.getBlockBoundsMinY()), block.getBlockBoundsMaxY()),
                blockPos.getZ() + Math.min(Math.max(Utils.randomizeDouble(0.0, 1.0), block.getBlockBoundsMinZ()), block.getBlockBoundsMaxZ())
        );
        switch (enumFacing) {
            case UP:
                return new Vec3(vec3.xCoord, blockPos.getY() + block.getBlockBoundsMaxY(), vec3.zCoord);
            case NORTH:
                return new Vec3(vec3.xCoord, vec3.yCoord, blockPos.getZ() + block.getBlockBoundsMinZ());
            case EAST:
                return new Vec3(blockPos.getX() + block.getBlockBoundsMaxX(), vec3.yCoord, vec3.zCoord);
            case SOUTH:
                return new Vec3(vec3.xCoord, vec3.yCoord, blockPos.getZ() + block.getBlockBoundsMaxZ());
            case WEST:
                return new Vec3(blockPos.getX() + block.getBlockBoundsMinX(), vec3.yCoord, vec3.zCoord);
            default:
                return new Vec3(vec3.xCoord, blockPos.getY() + block.getBlockBoundsMinY(), vec3.zCoord);
        }
    }

    public static final class BlockData {
        private final BlockPos blockPos;
        private final EnumFacing facing;

        public BlockData(BlockPos blockPos, EnumFacing enumFacing) {
            this.blockPos = blockPos;
            this.facing = enumFacing;
        }

        public BlockPos blockPos() {
            return blockPos;
        }

        public EnumFacing facing() {
            return facing;
        }
    }
}
