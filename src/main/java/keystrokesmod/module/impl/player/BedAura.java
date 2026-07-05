package keystrokesmod.module.impl.player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import keystrokesmod.event.ClientRotationEvent;
import keystrokesmod.event.PreAttackEvent;
import keystrokesmod.event.PrePlayerInteractEvent;
import keystrokesmod.event.PreSlotScrollEvent;
import keystrokesmod.event.SlotUpdateEvent;
import keystrokesmod.mixin.impl.accessor.IAccessorEntityRenderer;
import keystrokesmod.mixin.impl.accessor.IAccessorPlayerControllerMP;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.combat.KillAura;
import keystrokesmod.module.impl.render.BlockOverlay;
import keystrokesmod.module.impl.render.HUD;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.ColorSetting;
import keystrokesmod.module.setting.impl.GroupSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemStack;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.util.Vec3i;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

public class BedAura
extends Module {
    private final SliderSetting fov;
    private final SliderSetting range;
    private final SliderSetting rate;
    private final SliderSetting breakDelay;
    private final SliderSetting breakSpeed;
    private final ButtonSetting whitelistOwnBed;
    private final ButtonSetting prioritizeKillAura;
    private final GroupSetting swapGroup;
    private final ButtonSetting switchBackWhenDone;
    private final ButtonSetting overrideSwapBack;
    private final GroupSetting renderGroup;
    private final ButtonSetting renderOutline;
    private final ButtonSetting renderFill;
    private final ColorSetting outlineColor;
    private final ColorSetting fillColor;
    private final ButtonSetting showBreakProgress;
    private final ButtonSetting roundedProgressBackground;
    private final SliderSetting progressBackgroundRadius;
    private final SliderSetting progressHeight;
    private static final int MS_PER_TICK = 50;
    private static final double BED_FIND_EXTRA_BLOCKS = 1.0;
    private static final double OWN_BED_PROTECTION_RADIUS_SQ = 800.0;
    private static final float BED_ESP_HEIGHT = 0.5625f;
    private static final int PROGRESS_BAR_WIDTH = 80;
    private static final int PROGRESS_BAR_HEIGHT = 4;
    private static final int PROGRESS_BAR_GAP = 3;
    private static final int PROGRESS_BAR_BG_COLOR = 0x80000000;
    private final List<BlockPos[]> bedPairsCache = new ArrayList<BlockPos[]>();
    private int scanCooldown;
    private BlockPos targetPos;
    private Vec3 targetHitVec;
    private EnumFacing targetSide;
    private BlockPos progressBedFoot;
    private BlockPos progressBedHead;
    private List<BreakPlanEntry> progressBreakPlan;
    private long progressMiningStartMs = -1L;
    private float progressExpectedTotalMs = 0f;
    private int progressLastActiveIndex = -1;
    private float progressStagePeakDamage = 0.0f;
    private boolean miningActive;
    private int hotbarProgrammaticDepth;
    private boolean hasSwapped;
    private int previousSlot = -1;
    private BlockPos spawnAnchor;
    private boolean pendingSpawnAnchorCapture;
    private boolean waitingForRespawn;
    private long respawnMessageTime;

    public BedAura() {
        super("Bed Aura", Module.category.player);
        this.breakSpeed = new SliderSetting("Break speed", "x", 1.0, 1.0, 2.0, 0.02);
        this.registerSetting(this.breakSpeed);
        this.breakDelay = new SliderSetting("Break delay", "ms", 250.0, 0.0, 250.0, 1.0);
        this.registerSetting(this.breakDelay);
        this.range = new SliderSetting("Range", " blocks", 4.5, 2.0, 6.0, 0.1);
        this.registerSetting(this.range);
        this.fov = new SliderSetting("FOV", "", 180.0, 30.0, 360.0, 1.0);
        this.registerSetting(this.fov);
        this.rate = new SliderSetting("Scan rate", "ms", 250.0, 50.0, 2000.0, 1.0);
        this.registerSetting(this.rate);
        this.whitelistOwnBed = new ButtonSetting("Whitelist own bed", true);
        this.registerSetting(this.whitelistOwnBed);
        this.prioritizeKillAura = new ButtonSetting("Prioritize KillAura", false);
        this.registerSetting(this.prioritizeKillAura);
        this.swapGroup = new GroupSetting("Swap");
        this.registerSetting(this.swapGroup);
        this.switchBackWhenDone = new ButtonSetting(this.swapGroup, "Switch back when done", true, "Swap to previous slot");
        this.registerSetting(this.switchBackWhenDone);
        this.overrideSwapBack = new ButtonSetting(this.swapGroup, "Override swap back", true);
        this.registerSetting(this.overrideSwapBack);
        this.renderGroup = new GroupSetting("Render");
        this.registerSetting(this.renderGroup);
        this.renderOutline = new ButtonSetting(this.renderGroup, "Render block outline", true);
        this.registerSetting(this.renderOutline);
        this.renderFill = new ButtonSetting(this.renderGroup, "Render block fill", true);
        this.registerSetting(this.renderFill);
        this.outlineColor = new ColorSetting(this.renderGroup, "Outline color", 255, 64, 64, 229);
        this.registerSetting(this.outlineColor);
        this.fillColor = new ColorSetting(this.renderGroup, "Fill color", 255, 64, 64, 96);
        this.registerSetting(this.fillColor);
        this.showBreakProgress = new ButtonSetting(this.renderGroup, "Show break progress", true, "Adjacent block + bed");
        this.registerSetting(this.showBreakProgress);
        this.roundedProgressBackground = new ButtonSetting(this.renderGroup, "Rounded background", false);
        this.registerSetting(this.roundedProgressBackground);
        this.progressBackgroundRadius = new SliderSetting(this.renderGroup, "Background radius", 8.0, 0.0, 30.0, 0.5);
        this.registerSetting(this.progressBackgroundRadius);
        this.progressHeight = new SliderSetting(this.renderGroup, "Progress height", 0.6, 0.1, 3.0, 0.1);
        this.registerSetting(this.progressHeight);
    }

    @Override
    public void guiUpdate() {
        this.outlineColor.setVisible(this.renderOutline.isToggled(), this);
        this.fillColor.setVisible(this.renderFill.isToggled(), this);
        boolean showProgressSettings = this.showBreakProgress.isToggled();
        this.roundedProgressBackground.setVisible(showProgressSettings, this);
        this.progressBackgroundRadius.setVisible(showProgressSettings && this.roundedProgressBackground.isToggled(), this);
        this.progressHeight.setVisible(showProgressSettings, this);
    }

    @Override
    public void onDisable() {
        this.resetMining();
        this.clearProgressTracking();
        this.resetSpawnTracking();
        this.bedPairsCache.clear();
        this.scanCooldown = 0;
    }

    @Override
    public void onUpdate() {
        if (!Utils.nullCheck()) {
            return;
        }
        if (this.pendingSpawnAnchorCapture && Utils.getBedwarsStatus() == 2) {
            this.spawnAnchor = BedAura.mc.thePlayer.getPosition();
            this.pendingSpawnAnchorCapture = false;
        }
    }

    @Override
    public String getInfo() {
        return String.format("%.2fx", this.breakSpeed.getInput());
    }

    @SubscribeEvent
    public void onWorldJoin(EntityJoinWorldEvent e) {
        if (e.entity == BedAura.mc.thePlayer) {
            this.resetSpawnTracking();
        }
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }
        String strippedMessage = Utils.stripColor(event.message.getUnformattedText());
        if (strippedMessage.startsWith(" ") && strippedMessage.contains("Protect your bed and destroy the enemy beds.")) {
            this.pendingSpawnAnchorCapture = true;
            this.waitingForRespawn = false;
        } else if (strippedMessage.equals("You will respawn because you still have a bed!")) {
            this.waitingForRespawn = true;
            this.respawnMessageTime = System.currentTimeMillis();
        } else if (strippedMessage.equals("You have respawned!") && this.waitingForRespawn && Utils.timeBetween(System.currentTimeMillis(), this.respawnMessageTime) <= 12000L) {
            this.pendingSpawnAnchorCapture = true;
            this.waitingForRespawn = false;
        }
    }

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public void onPrePlayerInteract(PrePlayerInteractEvent e) {
        this.applyMiningKeyState();
    }

    @SubscribeEvent(priority=EventPriority.HIGH)
    public void onMouse(MouseEvent e) {
        if (!this.shouldSuppressManualMouse()) {
            return;
        }
        if (e.button == 0 || e.button == 1) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority=EventPriority.HIGH)
    public void onPreAttack(PreAttackEvent e) {
        if (!this.shouldSuppressManualMouse()) {
            return;
        }
        e.setCanceled(true);
    }

    @SubscribeEvent(priority=EventPriority.HIGH)
    public void onSlotScroll(PreSlotScrollEvent e) {
        if (!this.shouldSuppressManualMouse()) {
            return;
        }
        if (this.hasSwapped && this.overrideSwapBack.isToggled() && Utils.nullCheck()) {
            int slot = Integer.compare(e.slot, 0);
            this.previousSlot = Math.floorMod(BedAura.mc.thePlayer.inventory.currentItem - slot, InventoryPlayer.getHotbarSize());
        }
        e.setCanceled(true);
    }

    @SubscribeEvent(priority=EventPriority.HIGH)
    public void onSlotUpdate(SlotUpdateEvent e) {
        if (!this.shouldSuppressManualMouse() || this.hotbarProgrammaticDepth > 0) {
            return;
        }
        if (this.hasSwapped && this.overrideSwapBack.isToggled()) {
            this.previousSlot = e.slot;
        }
        e.setCanceled(true);
    }

    private boolean shouldSuppressManualMouse() {
        return this.miningActive && this.isEnabled() && Utils.nullCheck() && BedAura.mc.currentScreen == null && this.canMineBlocks() && !this.shouldYieldToKillAura();
    }

    public void applyMiningKeyState() {
        if (!this.canMineBlocks() || this.shouldYieldToKillAura()) {
            if (this.miningActive) {
                this.resetMining();
            }
            return;
        }
        if (!(this.miningActive && this.isEnabled() && Utils.nullCheck() && BedAura.mc.currentScreen == null)) {
            return;
        }
        int atk = BedAura.mc.gameSettings.keyBindAttack.getKeyCode();
        int use = BedAura.mc.gameSettings.keyBindUseItem.getKeyCode();
        KeyBinding.setKeyBindState((int)atk, (boolean)false);
        KeyBinding.setKeyBindState((int)use, (boolean)false);
        KeyBinding.setKeyBindState((int)atk, (boolean)true);
    }

    public BlockPos getAuraTargetPos() {
        return this.miningActive && this.canMineBlocks() ? this.targetPos : null;
    }

    public boolean isActivelyMining() {
        return this.miningActive && this.isEnabled() && Utils.nullCheck() && BedAura.mc.currentScreen == null && this.canMineBlocks() && !this.shouldYieldToKillAura();
    }

    public boolean shouldOverrideFastMine() {
        return this.isActivelyMining();
    }

    public float getBreakSpeedMultiplier() {
        float multiplier = (float)this.breakSpeed.getInput();
        return multiplier > 1.0f ? multiplier : 1.0f;
    }

    public int getBreakDelayTicks() {
        return Math.max(0, Math.min(5, (int)(this.breakDelay.getInput() / 50.0)));
    }

    public float getAuraBreakProgress() {
        if (!this.canMineBlocks() || !this.miningActive || BedAura.mc.playerController == null) {
            return 0.0f;
        }
        if (this.progressBreakPlan != null && !this.progressBreakPlan.isEmpty()) {
            return this.computeOverallBedBreakProgress();
        }
        IAccessorPlayerControllerMP pc = (IAccessorPlayerControllerMP)BedAura.mc.playerController;
        BlockPos currentBlock = pc.getCurrentBlock();
        if (this.targetPos == null || currentBlock == null || !this.targetPos.equals((Object)currentBlock)) {
            return 0.0f;
        }
        return MathHelper.clamp_float(pc.getCurBlockDamageMP(), 0.0f, 1.0f);
    }

    public boolean shouldOverrideMouseOver() {
        return this.isEnabled() && this.miningActive && this.canMineBlocks() && this.targetPos != null && this.targetHitVec != null && this.targetSide != null && Utils.nullCheck() && !this.shouldYieldToKillAura();
    }

    public void modifyMouseOverFromGetMouseOver(float partialTicks) {
        MovingObjectPosition mop;
        if (!this.shouldOverrideMouseOver()) {
            return;
        }
        if (mc.getRenderViewEntity() == null) {
            return;
        }
        BedAura.mc.objectMouseOver = mop = new MovingObjectPosition(this.targetHitVec, this.targetSide, this.targetPos);
        BedAura.mc.pointedEntity = null;
        EntityRenderer renderer = BedAura.mc.entityRenderer;
        if (renderer instanceof IAccessorEntityRenderer) {
            ((IAccessorEntityRenderer)renderer).setPointedEntity(null);
        }
    }

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public void onClientRotation(ClientRotationEvent e) {
        if (!(this.isEnabled() && Utils.nullCheck() && BedAura.mc.currentScreen == null && this.canMineBlocks())) {
            this.resetMining();
            return;
        }
        if (this.shouldYieldToKillAura()) {
            this.resetMining();
            return;
        }
        if (e.scriptRotations) {
            this.resetMining();
            return;
        }
        double reach = this.range.getInput();
        double reachSq = reach * reach;
        if (--this.scanCooldown <= 0) {
            this.scanCooldown = Math.max(1, (int)Math.round(this.rate.getInput() / 50.0));
            this.rebuildBedPairsCache(reach + 1.0);
        }
        if (this.bedPairsCache.isEmpty()) {
            this.resetMining();
            return;
        }
        Choice best = this.chooseBestTarget(reachSq);
        if (best == null) {
            this.resetMining();
            return;
        }
        this.targetPos = best.pos;
        this.targetHitVec = best.hitVec;
        this.targetSide = best.side;
        this.miningActive = true;
        BlockPos[] bedPair = this.findAssociatedBedPair(best.pos);
        if (bedPair != null) {
            this.ensureProgressSnapshot(bedPair, best.pos);
        }
        this.updateProgressStageTracking();
        this.equipBestHotbarTool(BlockUtils.getBlock(this.targetPos));
        float baseYaw = e.yaw != null ? e.yaw.floatValue() : RotationUtils.serverRotations[0];
        float basePitch = e.pitch != null ? e.pitch.floatValue() : RotationUtils.serverRotations[1];
        float[] r = RotationUtils.getRotationsToPoint(this.targetHitVec.xCoord, this.targetHitVec.yCoord, this.targetHitVec.zCoord, baseYaw, basePitch);
        e.setYaw(Float.valueOf(r[0]));
        e.setPitch(Float.valueOf(r[1]));
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent e) {
        IBlockState st;
        Block b;
        if (!(this.isEnabled() && Utils.nullCheck() && this.canMineBlocks())) {
            return;
        }
        if (this.miningActive && this.targetPos != null && (b = (st = BedAura.mc.theWorld.getBlockState(this.targetPos)).getBlock()) != null && b != Blocks.air) {
            boolean showOutline = this.renderOutline.isToggled();
            boolean showFill = this.renderFill.isToggled();
            if (showOutline || showFill) {
                int fill = this.fillColor.getColor();
                int outline = this.outlineColor.getColor();
                BlockOverlay.renderBlockHighlight(this.targetPos, fill, outline, outline, 2.0f, true, showFill, showOutline);
            }
        }
        if (!this.showBreakProgress.isToggled() || !this.miningActive || this.progressBreakPlan == null || this.progressBreakPlan.isEmpty()) {
            return;
        }
        BlockPos[] bedPair = this.getProgressBedPair();
        if (bedPair == null) {
            return;
        }
        if (this.isBedDestroyed(bedPair)) {
            this.clearProgressTracking();
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !Utils.nullCheck() || BedAura.mc.currentScreen != null) {
            return;
        }
        if (!this.showBreakProgress.isToggled() || !this.miningActive || this.progressBreakPlan == null || this.progressBreakPlan.isEmpty()) {
            return;
        }
        BlockPos[] bedPair = this.getProgressBedPair();
        if (bedPair == null) {
            return;
        }
        this.updateProgressStageTracking();
        float progress = this.computeOverallBedBreakProgress();
        boolean destroyed = this.isBedDestroyed(bedPair);
        if (destroyed) {
            progress = 1.0f;
        }
        this.renderBreakProgressHud(progress);
        if (destroyed) {
            this.clearProgressTracking();
        }
    }

    private void renderBreakProgressHud(float progress) {
        float clampedProgress = MathHelper.clamp_float(progress, 0.0f, 1.0f);
        int percent = MathHelper.clamp_int(Math.round(clampedProgress * 100.0f), 0, 100);
        String text = percent + "%";
        ScaledResolution sr = new ScaledResolution(BedAura.mc);
        int centerX = sr.getScaledWidth() / 2;
        int centerY = sr.getScaledHeight() / 2;
        int textWidth = BedAura.mc.fontRendererObj.getStringWidth(text);
        int textHeight = BedAura.mc.fontRendererObj.FONT_HEIGHT;
        int barLeft = centerX - PROGRESS_BAR_WIDTH / 2;
        int barTop = centerY;
        int barRight = barLeft + PROGRESS_BAR_WIDTH;
        int barBottom = barTop + PROGRESS_BAR_HEIGHT;
        int fillRight = barLeft + Math.round(PROGRESS_BAR_WIDTH * clampedProgress);
        int hudColor = this.withFullAlpha(HUD.getHudColor(0.0));
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        RenderUtils.drawRect(barLeft, barTop, barRight, barBottom, PROGRESS_BAR_BG_COLOR);
        if (fillRight > barLeft) {
            RenderUtils.drawRect(barLeft, barTop, fillRight, barBottom, hudColor);
        }
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        BedAura.mc.fontRendererObj.drawString(text, centerX - textWidth / 2, barTop - textHeight - PROGRESS_BAR_GAP, hudColor, true);
    }

    private int withFullAlpha(int rgb) {
        return rgb & 0x00FFFFFF | 0xFF000000;
    }

    private void ensureProgressSnapshot(BlockPos[] pair, BlockPos currentTarget) {
        BlockPos foot = pair[0];
        if (this.progressBedFoot != null && this.progressBedFoot.equals((Object)foot) && this.progressBreakPlan != null && !this.progressBreakPlan.isEmpty()) {
            if (this.progressMiningStartMs < 0) {
                this.progressExpectedTotalMs = this.computeExpectedBreakMs();
                this.progressMiningStartMs = System.currentTimeMillis();
            }
            return;
        }
        this.progressBedFoot = new BlockPos((Vec3i)foot);
        this.progressBedHead = new BlockPos((Vec3i)pair[1]);
        this.progressBreakPlan = this.buildTwoBlockBreakPlan(pair, currentTarget);
        this.progressExpectedTotalMs = this.computeExpectedBreakMs();
        this.progressMiningStartMs = System.currentTimeMillis();
        this.progressLastActiveIndex = -1;
        this.progressStagePeakDamage = 0.0f;
    }

    private float computeExpectedBreakMs() {
        if (this.progressBreakPlan == null || this.progressBreakPlan.isEmpty()) {
            return 0f;
        }
        float ticks = 0f;
        for (BreakPlanEntry entry : this.progressBreakPlan) {
            Block block = BedAura.mc.theWorld.getBlockState(entry.pos).getBlock();
            if (block == Blocks.air) continue;
            float digRate = this.getPlannedDigRate(block, entry.pos);
            if (digRate > 0f) {
                ticks += 1.0f / digRate;
            }
        }
        if (this.progressBreakPlan.size() > 1) {
            ticks += this.getBreakDelayTicks();
        }
        return Math.max(1f, ticks * 50f);
    }

    private float bestDigRateIncludingBareHands(Block block) {
        float best = BlockUtils.getBlockHardness(block, null, false, false);
        if (BedAura.mc.thePlayer == null) {
            return best;
        }
        for (int i = 0; i < InventoryPlayer.getHotbarSize(); i++) {
            ItemStack stack = BedAura.mc.thePlayer.inventory.getStackInSlot(i);
            if (stack == null) continue;
            float rate = BlockUtils.getBlockHardness(block, stack, false, false);
            if (rate > best) {
                best = rate;
            }
        }
        return best;
    }

    private List<BreakPlanEntry> buildTwoBlockBreakPlan(BlockPos[] pair, BlockPos currentTarget) {
        ArrayList<BreakPlanEntry> plan = new ArrayList<BreakPlanEntry>();
        if (currentTarget == null) {
            return plan;
        }
        BlockPos bedTarget = this.pickBedBreakTarget(pair);
        Block currentBlock = BedAura.mc.theWorld.getBlockState(currentTarget).getBlock();
        if (currentBlock instanceof BlockBed) {
            plan.add(new BreakPlanEntry(new BlockPos((Vec3i)currentTarget), this.getBreakWork(currentBlock, currentTarget)));
            return plan;
        }
        if (bedTarget != null) {
            plan.add(new BreakPlanEntry(new BlockPos((Vec3i)currentTarget), this.getBreakWork(currentBlock, currentTarget)));
            Block bedBlock = BedAura.mc.theWorld.getBlockState(bedTarget).getBlock();
            plan.add(new BreakPlanEntry(bedTarget, this.getBreakWork(bedBlock instanceof BlockBed ? bedBlock : Blocks.bed, bedTarget)));
        }
        return plan;
    }

    private BlockPos pickBedBreakTarget(BlockPos[] pair) {
        for (BlockPos bedPart : pair) {
            if (!(BedAura.mc.theWorld.getBlockState(bedPart).getBlock() instanceof BlockBed)) continue;
            return new BlockPos((Vec3i)bedPart);
        }
        return new BlockPos((Vec3i)pair[0]);
    }

    private float getBreakWork(Block block, BlockPos pos) {
        return this.getMiningWork(block, pos);
    }

    private float getPlannedDigRate(Block block, BlockPos pos) {
        if (block == null || block == Blocks.air || BedAura.mc.thePlayer == null || BedAura.mc.theWorld == null) {
            return 0.0f;
        }
        float speed = 0.0f;
        if (pos != null && this.isHeldToolOptimalForBlock(block)) {
            speed = block.getPlayerRelativeBlockHardness(BedAura.mc.thePlayer, BedAura.mc.theWorld, pos);
        }
        if (speed <= 0.0f) {
            speed = BlockUtils.getBlockHardness(block, this.getBestToolStackForBlock(block), false, false);
        }
        return speed * this.getBreakSpeedMultiplier();
    }

    private boolean isHeldToolOptimalForBlock(Block block) {
        int bestSlot = Utils.getTool(block);
        return bestSlot < 0 || bestSlot == BedAura.mc.thePlayer.inventory.currentItem;
    }

    private ItemStack getBestToolStackForBlock(Block block) {
        int slot = Utils.getTool(block);
        if (slot >= 0) {
            return BedAura.mc.thePlayer.inventory.getStackInSlot(slot);
        }
        return BedAura.mc.thePlayer.getHeldItem();
    }

    private float getMiningWork(Block block, BlockPos pos) {
        float digRate = this.getPlannedDigRate(block, pos);
        return digRate <= 0.0f ? 1.0f : 1.0f / digRate;
    }

    private void updateProgressStageTracking() {
        if (this.progressBreakPlan == null || this.progressBreakPlan.isEmpty() || !this.miningActive || BedAura.mc.playerController == null) {
            return;
        }
        int activeIndex = this.resolveActivePlanIndex();
        if (activeIndex < 0) {
            return;
        }
        if (activeIndex != this.progressLastActiveIndex) {
            this.progressLastActiveIndex = activeIndex;
            this.progressStagePeakDamage = 0.0f;
        }
        BreakPlanEntry entry = this.progressBreakPlan.get(activeIndex);
        BlockPos trackedPos = this.targetPos != null ? this.targetPos : entry.pos;
        if (!this.matchesProgressPlanEntry(entry.pos, trackedPos)) {
            return;
        }
        IAccessorPlayerControllerMP pc = (IAccessorPlayerControllerMP)BedAura.mc.playerController;
        float damage = MathHelper.clamp_float(pc.getCurBlockDamageMP(), 0.0f, 1.0f);
        Block block = BedAura.mc.theWorld.getBlockState(trackedPos).getBlock();
        if (damage > 0.0f) {
            this.progressStagePeakDamage = Math.max(this.progressStagePeakDamage, damage);
        } else if (this.canInstantMineBlock(block, trackedPos)) {
            this.progressStagePeakDamage = 1.0f;
        }
    }

    private int resolveActivePlanIndex() {
        if (this.progressBreakPlan == null || this.progressBreakPlan.isEmpty()) {
            return -1;
        }
        if (this.targetPos != null) {
            int targetIndex = this.getPlanIndexForTarget(this.targetPos);
            if (targetIndex >= 0) {
                return targetIndex;
            }
        }
        if (BedAura.mc.playerController != null) {
            BlockPos breaking = ((IAccessorPlayerControllerMP)BedAura.mc.playerController).getCurrentBlock();
            if (breaking != null) {
                int breakingIndex = this.getPlanIndexForTarget(breaking);
                if (breakingIndex >= 0) {
                    return breakingIndex;
                }
            }
        }
        return this.getFirstNonAirPlanIndex();
    }

    private int getPlanIndexForTarget(BlockPos pos) {
        if (pos == null || this.progressBreakPlan == null) {
            return -1;
        }
        for (int i = 0; i < this.progressBreakPlan.size(); ++i) {
            if (this.matchesProgressPlanEntry(this.progressBreakPlan.get(i).pos, pos)) {
                return i;
            }
        }
        return -1;
    }

    private boolean matchesProgressPlanEntry(BlockPos planPos, BlockPos otherPos) {
        if (otherPos == null) {
            return false;
        }
        if (planPos.equals((Object)otherPos)) {
            return true;
        }
        return this.isProgressBedPart(planPos) && this.isProgressBedPart(otherPos);
    }

    private boolean isProgressBedPart(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        if (this.progressBedFoot != null && pos.equals((Object)this.progressBedFoot)) {
            return true;
        }
        if (this.progressBedHead != null && pos.equals((Object)this.progressBedHead)) {
            return true;
        }
        return BedAura.mc.theWorld != null && BedAura.mc.theWorld.getBlockState(pos).getBlock() instanceof BlockBed;
    }

    private int getFirstNonAirPlanIndex() {
        if (this.progressBreakPlan == null) {
            return -1;
        }
        for (int i = 0; i < this.progressBreakPlan.size(); ++i) {
            Block block = BedAura.mc.theWorld.getBlockState(this.progressBreakPlan.get(i).pos).getBlock();
            if (block != Blocks.air) {
                return i;
            }
        }
        return -1;
    }

    private boolean canInstantMineBlock(Block block, BlockPos pos) {
        return block != null && block != Blocks.air && this.getPlannedDigRate(block, pos) >= 1.0f;
    }

    private float computeOverallBedBreakProgress() {
        if (this.progressBreakPlan == null || this.progressBreakPlan.isEmpty() || BedAura.mc.playerController == null || BedAura.mc.theWorld == null) {
            return 0.0f;
        }
        int planSize = this.progressBreakPlan.size();
        IAccessorPlayerControllerMP pc = (IAccessorPlayerControllerMP)BedAura.mc.playerController;
        BlockPos breaking = pc.getCurrentBlock();
        float currentDamage = MathHelper.clamp_float(pc.getCurBlockDamageMP(), 0.0f, 1.0f);
        float completed = 0.0f;
        boolean foundActiveStage = false;
        for (int i = 0; i < planSize; ++i) {
            BreakPlanEntry entry = this.progressBreakPlan.get(i);
            BlockPos trackedPos = entry.pos;
            Block block = BedAura.mc.theWorld.getBlockState(trackedPos).getBlock();
            if (block == Blocks.air) {
                completed += 1.0f;
                continue;
            }
            if (this.targetPos != null && this.matchesProgressPlanEntry(trackedPos, this.targetPos)) {
                trackedPos = this.targetPos;
                block = BedAura.mc.theWorld.getBlockState(trackedPos).getBlock();
            } else if (breaking != null && this.matchesProgressPlanEntry(trackedPos, breaking)) {
                trackedPos = breaking;
                block = BedAura.mc.theWorld.getBlockState(trackedPos).getBlock();
            }
            boolean isTarget = this.miningActive && this.targetPos != null && this.matchesProgressPlanEntry(entry.pos, this.targetPos);
            boolean isBreaking = breaking != null && this.matchesProgressPlanEntry(entry.pos, breaking);
            if (isTarget || isBreaking) {
                if (i != this.progressLastActiveIndex) {
                    this.progressLastActiveIndex = i;
                    this.progressStagePeakDamage = 0.0f;
                }
                this.progressStagePeakDamage = Math.max(this.progressStagePeakDamage, currentDamage);
                float stageProgress = this.progressStagePeakDamage;
                if (stageProgress <= 0.0f && this.canInstantMineBlock(block, trackedPos)) {
                    stageProgress = 1.0f;
                    this.progressStagePeakDamage = 1.0f;
                }
                completed += stageProgress;
                foundActiveStage = true;
            }
            break;
        }
        if (!foundActiveStage && this.getFirstNonAirPlanIndex() < 0) {
            return 1.0f;
        }
        return MathHelper.clamp_float(completed / (float)planSize, 0.0f, 1.0f);
    }

    private BlockPos[] getProgressBedPair() {
        if (this.progressBedFoot == null || this.progressBedHead == null) {
            return null;
        }
        return new BlockPos[]{new BlockPos((Vec3i)this.progressBedFoot), new BlockPos((Vec3i)this.progressBedHead)};
    }

    private boolean isBedDestroyed(BlockPos[] pair) {
        if (pair == null || pair.length < 2 || BedAura.mc.theWorld == null) {
            return true;
        }
        Block footBlock = BedAura.mc.theWorld.getBlockState(pair[0]).getBlock();
        Block headBlock = BedAura.mc.theWorld.getBlockState(pair[1]).getBlock();
        return !(footBlock instanceof BlockBed) && !(headBlock instanceof BlockBed);
    }

    private void clearProgressTracking() {
        this.progressBedFoot = null;
        this.progressBedHead = null;
        this.progressBreakPlan = null;
        this.progressMiningStartMs = -1L;
        this.progressExpectedTotalMs = 0f;
        this.progressLastActiveIndex = -1;
        this.progressStagePeakDamage = 0.0f;
    }

    private BlockPos[] findAssociatedBedPair(BlockPos pos) {
        if (pos == null || this.bedPairsCache.isEmpty()) {
            return null;
        }
        BlockPos[] closest = null;
        double closestDistanceSq = Double.MAX_VALUE;
        Vec3 point = new Vec3((double)pos.getX() + 0.5, (double)pos.getY() + 0.5, (double)pos.getZ() + 0.5);
        for (BlockPos[] pair : this.bedPairsCache) {
            if (pair == null || pair.length < 2) continue;
            if (pos.equals((Object)pair[0]) || pos.equals((Object)pair[1])) {
                return pair;
            }
            AxisAlignedBB bounds = BedAura.bedWorldBounds(pair[0], pair[1], 1.0f).expand(3.0, 3.0, 3.0);
            if (point.xCoord >= bounds.minX && point.xCoord <= bounds.maxX && point.yCoord >= bounds.minY && point.yCoord <= bounds.maxY && point.zCoord >= bounds.minZ && point.zCoord <= bounds.maxZ) {
                return pair;
            }
            Vec3 center = this.bedCenter(pair);
            double distanceSq = point.squareDistanceTo(center);
            if (!(distanceSq < closestDistanceSq)) continue;
            closestDistanceSq = distanceSq;
            closest = pair;
        }
        return closestDistanceSq <= 36.0 ? closest : null;
    }

    private static AxisAlignedBB bedWorldBounds(BlockPos foot, BlockPos head, float height) {
        int fx = foot.getX();
        int fy = foot.getY();
        int fz = foot.getZ();
        double h = (float)fy + height;
        if (foot.getX() != head.getX()) {
            if (foot.getX() > head.getX()) {
                return new AxisAlignedBB((double)fx - 1.0, (double)fy, (double)fz, (double)fx + 1.0, h, (double)fz + 1.0);
            }
            return new AxisAlignedBB((double)fx, (double)fy, (double)fz, (double)fx + 2.0, h, (double)fz + 1.0);
        }
        if (foot.getZ() > head.getZ()) {
            return new AxisAlignedBB((double)fx, (double)fy, (double)fz - 1.0, (double)fx + 1.0, h, (double)fz + 1.0);
        }
        return new AxisAlignedBB((double)fx, (double)fy, (double)fz, (double)fx + 1.0, h, (double)fz + 2.0);
    }

    private void resetMining() {
        this.miningActive = false;
        if (this.switchBackWhenDone.isToggled() && this.previousSlot != -1 && Utils.nullCheck()) {
            this.setSlot(this.previousSlot);
        }
        KeyBinding.setKeyBindState((int)BedAura.mc.gameSettings.keyBindAttack.getKeyCode(), (boolean)Mouse.isButtonDown((int)0));
        KeyBinding.setKeyBindState((int)BedAura.mc.gameSettings.keyBindUseItem.getKeyCode(), (boolean)Mouse.isButtonDown((int)1));
        this.hotbarProgrammaticDepth = 0;
        this.targetPos = null;
        this.targetHitVec = null;
        this.targetSide = null;
        this.hasSwapped = false;
        this.previousSlot = -1;
        BlockPos[] progressPair = this.getProgressBedPair();
        if (progressPair != null && this.isBedDestroyed(progressPair)) {
            this.clearProgressTracking();
        } else {
            this.progressMiningStartMs = -1L;
        }
    }

    private void rebuildBedPairsCache(double searchRange) {
        this.bedPairsCache.clear();
        HashSet<BlockPos> seenFeet = new HashSet<BlockPos>();
        int ri = (int)Math.ceil(searchRange);
        BlockPos origin = new BlockPos((Entity)BedAura.mc.thePlayer);
        for (int dx = -ri; dx <= ri; ++dx) {
            for (int dy = -ri; dy <= ri; ++dy) {
                for (int dz = -ri; dz <= ri; ++dz) {
                    Vec3 center;
                    BlockPos foot;
                    BlockPos p = origin.add(dx, dy, dz);
                    BlockPos[] pair = this.footHeadPair(p);
                    if (pair == null || seenFeet.contains(foot = pair[0]) || !this.bedInSearchRange(pair, searchRange) || !this.inFov(center = this.bedCenter(pair), (float)this.fov.getInput())) continue;
                    seenFeet.add(foot);
                    this.bedPairsCache.add(pair);
                }
            }
        }
        this.removeOwnBedPair();
    }

    private BlockPos[] footHeadPair(BlockPos at) {
        IBlockState st = BedAura.mc.theWorld.getBlockState(at);
        if (!(st.getBlock() instanceof BlockBed)) {
            return null;
        }
        BlockBed.EnumPartType part = (BlockBed.EnumPartType)st.getValue((IProperty)BlockBed.PART);
        EnumFacing facing = (EnumFacing)st.getValue((IProperty)BlockBed.FACING);
        BlockPos foot = part == BlockBed.EnumPartType.FOOT ? at : at.offset(facing.getOpposite());
        IBlockState footSt = BedAura.mc.theWorld.getBlockState(foot);
        if (!(footSt.getBlock() instanceof BlockBed)) {
            return null;
        }
        if (footSt.getValue((IProperty)BlockBed.PART) != BlockBed.EnumPartType.FOOT) {
            return null;
        }
        EnumFacing footFacing = (EnumFacing)footSt.getValue((IProperty)BlockBed.FACING);
        BlockPos head = foot.offset(footFacing);
        IBlockState hs = BedAura.mc.theWorld.getBlockState(head);
        if (!(hs.getBlock() instanceof BlockBed)) {
            return null;
        }
        if (hs.getValue((IProperty)BlockBed.PART) != BlockBed.EnumPartType.HEAD) {
            return null;
        }
        if (hs.getValue((IProperty)BlockBed.FACING) != footFacing) {
            return null;
        }
        return new BlockPos[]{foot, head};
    }

    private Vec3 bedCenter(BlockPos[] pair) {
        AxisAlignedBB a = BlockUtils.unionBlockBounds(pair[0], pair[1]);
        return new Vec3((a.minX + a.maxX) * 0.5, (a.minY + a.maxY) * 0.5, (a.minZ + a.maxZ) * 0.5);
    }

    private boolean bedInSearchRange(BlockPos[] pair, double searchRadius) {
        Vec3 eye = BedAura.mc.thePlayer.getPositionEyes(1.0f);
        double r2 = searchRadius * searchRadius + 1.0E-4;
        AxisAlignedBB u = BlockUtils.unionBlockBounds(pair[0], pair[1]);
        Vec3 onBox = RotationUtils.closestPointOnAabb(u, eye);
        if (eye.squareDistanceTo(onBox) <= r2) {
            return true;
        }
        Vec3 mid = new Vec3((u.minX + u.maxX) * 0.5, (u.minY + u.maxY) * 0.5, (u.minZ + u.maxZ) * 0.5);
        return eye.squareDistanceTo(mid) <= r2;
    }

    private boolean inFov(Vec3 worldPoint, float fovDeg) {
        if (fovDeg >= 360.0f) {
            return true;
        }
        Vec3 eyes = BedAura.mc.thePlayer.getPositionEyes(1.0f);
        Vec3 look = BedAura.mc.thePlayer.getLook(1.0f);
        Vec3 to = worldPoint.subtract(eyes);
        double len = to.lengthVector();
        if (len < 1.0E-6) {
            return true;
        }
        to = new Vec3(to.xCoord / len, to.yCoord / len, to.zCoord / len);
        double dot = look.xCoord * to.xCoord + look.yCoord * to.yCoord + look.zCoord * to.zCoord;
        double ang = Math.acos(MathHelper.clamp_double((double)dot, (double)-1.0, (double)1.0)) * 57.29577951308232;
        return ang <= (double)fovDeg * 0.5;
    }

    private Choice chooseBestTarget(double reachSq) {
        IAccessorPlayerControllerMP pc = (IAccessorPlayerControllerMP)BedAura.mc.playerController;
        float curProg = pc.getCurBlockDamageMP();
        BlockPos breaking = pc.getCurrentBlock();
        ArrayList<BlockPos[]> exposed = new ArrayList<BlockPos[]>();
        ArrayList<BlockPos[]> covered = new ArrayList<BlockPos[]>();
        for (BlockPos[] pair : this.bedPairsCache) {
            if (this.isBedExposed(pair)) {
                exposed.add(pair);
                continue;
            }
            covered.add(pair);
        }
        this.sortBedsByEyeDistance(exposed);
        this.sortBedsByEyeDistance(covered);
        Choice c = this.pickBestOnClosestBedWithCandidates(exposed, reachSq, curProg, breaking);
        if (c != null) {
            return c;
        }
        return this.pickBestOnClosestBedWithCandidates(covered, reachSq, curProg, breaking);
    }

    private void sortBedsByEyeDistance(List<BlockPos[]> pairs) {
        Vec3 eye = BedAura.mc.thePlayer.getPositionEyes(1.0f);
        pairs.sort(Comparator.comparingDouble(p -> eye.squareDistanceTo(this.bedCenter((BlockPos[])p))));
    }

    private Choice pickBestOnClosestBedWithCandidates(List<BlockPos[]> sortedPairs, double reachSq, float curProg, BlockPos breaking) {
        for (BlockPos[] pair : sortedPairs) {
            List<Choice> candidates = this.buildCandidates(pair, reachSq);
            if (candidates.isEmpty()) continue;
            Choice best = null;
            double bestScore = Double.POSITIVE_INFINITY;
            for (Choice ch : candidates) {
                double score = this.scoreChoice(ch, curProg, breaking);
                if (!(score < bestScore)) continue;
                bestScore = score;
                best = ch;
            }
            return best;
        }
        return null;
    }

    private double scoreChoice(Choice ch, float curProg, BlockPos breaking) {
        Block block = BlockUtils.getBlock(ch.pos);
        float bestHotbar = this.bestDigRateIncludingBareHands(block);
        if (bestHotbar <= 0.0f) {
            return Double.POSITIVE_INFINITY;
        }
        double timeEst = 1.0 / (double)bestHotbar;
        if (breaking != null && breaking.equals((Object)ch.pos) && curProg > 0.02f) {
            timeEst -= (double)curProg * 12.0;
        }
        Vec3 eye = BedAura.mc.thePlayer.getPositionEyes(1.0f);
        return timeEst += eye.squareDistanceTo(ch.hitVec) * 0.002;
    }

    private List<Choice> buildCandidates(BlockPos[] pair, double reachSq) {
        ArrayList<Choice> out = new ArrayList<Choice>();
        boolean exposed = this.isBedExposed(pair);
        if (exposed) {
            for (BlockPos bp : pair) {
                this.addBlockCandidate(bp, reachSq, out);
            }
        } else {
            HashSet<BlockPos> seen = new HashSet<BlockPos>();
            for (BlockPos bp : pair) {
                for (EnumFacing f : EnumFacing.values()) {
                    float hard;
                    IBlockState st;
                    Block b;
                    BlockPos n;
                    if (f == EnumFacing.DOWN || seen.contains(n = bp.offset(f)) || (b = (st = BedAura.mc.theWorld.getBlockState(n)).getBlock()) == Blocks.air || b instanceof BlockBed || (hard = b.getBlockHardness((World)BedAura.mc.theWorld, n)) < 0.0f) continue;
                    seen.add(n);
                    this.addBlockCandidate(n, reachSq, out);
                }
            }
        }
        return out;
    }

    private boolean isBedExposed(BlockPos[] pair) {
        for (BlockPos bp : pair) {
            for (EnumFacing f : EnumFacing.values()) {
                BlockPos n = bp.offset(f);
                if (BedAura.mc.theWorld.getBlockState(n).getBlock() != Blocks.air) continue;
                return true;
            }
        }
        return false;
    }

    private void addBlockCandidate(BlockPos pos, double reachSq, List<Choice> out) {
        Vec3 hit;
        IBlockState st = BedAura.mc.theWorld.getBlockState(pos);
        Block block = st.getBlock();
        if (block == Blocks.air) {
            return;
        }
        float hard = block.getBlockHardness((World)BedAura.mc.theWorld, pos);
        if (hard < 0.0f) {
            return;
        }
        AxisAlignedBB bb = BlockUtils.getBlockSelectionBox(pos);
        if (bb == null) {
            return;
        }
        Vec3 eye = BedAura.mc.thePlayer.getPositionEyes(1.0f);
        if (eye.squareDistanceTo(hit = RotationUtils.closestPointOnAabb(bb, eye)) > reachSq + 0.001) {
            return;
        }
        MovingObjectPosition trace = block.collisionRayTrace((World)BedAura.mc.theWorld, pos, eye, hit.addVector((hit.xCoord - eye.xCoord) * 0.01, (hit.yCoord - eye.yCoord) * 0.01, (hit.zCoord - eye.zCoord) * 0.01));
        EnumFacing side = BlockUtils.facingFromBlockCenterToPoint(pos, hit);
        if (trace != null && trace.hitVec != null && trace.sideHit != null && pos.equals((Object)trace.getBlockPos())) {
            hit = trace.hitVec;
            side = trace.sideHit;
        }
        if (block instanceof BlockBed && side == EnumFacing.DOWN) {
            return;
        }
        out.add(new Choice(pos, hit, side));
    }

    private void equipBestHotbarTool(Block block) {
        int slot = Utils.getTool(block);
        if (slot < 0) {
            return;
        }
        if (this.previousSlot == -1 && slot != BedAura.mc.thePlayer.inventory.currentItem) {
            this.previousSlot = BedAura.mc.thePlayer.inventory.currentItem;
        }
        if (slot != BedAura.mc.thePlayer.inventory.currentItem) {
            this.setSlot(slot);
        }
    }

    private void setSlot(int slot) {
        if (slot == -1 || slot == BedAura.mc.thePlayer.inventory.currentItem) {
            return;
        }
        ++this.hotbarProgrammaticDepth;
        try {
            BedAura.mc.thePlayer.inventory.currentItem = slot;
            this.hasSwapped = true;
            ((IAccessorPlayerControllerMP)BedAura.mc.playerController).callSyncCurrentPlayItem();
        }
        finally {
            --this.hotbarProgrammaticDepth;
        }
    }

    private boolean canMineBlocks() {
        return BedAura.mc.thePlayer.capabilities.allowEdit && !BedAura.mc.thePlayer.capabilities.isCreativeMode && !BedAura.mc.thePlayer.isSpectator();
    }

    private boolean shouldYieldToKillAura() {
        if (!this.prioritizeKillAura.isToggled()) {
            return false;
        }
        return ModuleManager.killAura != null && ModuleManager.killAura.isEnabled() && KillAura.target != null;
    }

    private void resetSpawnTracking() {
        this.spawnAnchor = null;
        this.pendingSpawnAnchorCapture = false;
        this.waitingForRespawn = false;
        this.respawnMessageTime = 0L;
    }

    private void removeOwnBedPair() {
        if (!this.shouldWhitelistOwnBed() || this.bedPairsCache.isEmpty()) {
            return;
        }
        BlockPos[] ownBedPair = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        Vec3 spawnCenter = this.spawnAnchorCenter();
        for (BlockPos[] pair : this.bedPairsCache) {
            double distance = spawnCenter.squareDistanceTo(this.bedCenter(pair));
            if (!(distance < closestDistance)) continue;
            closestDistance = distance;
            ownBedPair = pair;
        }
        if (ownBedPair != null) {
            this.bedPairsCache.remove(ownBedPair);
        }
    }

    private boolean shouldWhitelistOwnBed() {
        return this.whitelistOwnBed.isToggled() && this.spawnAnchor != null && Utils.getBedwarsStatus() == 2 && BedAura.mc.thePlayer.getDistanceSq(this.spawnAnchor) <= 800.0;
    }

    private Vec3 spawnAnchorCenter() {
        return new Vec3((double)this.spawnAnchor.getX() + 0.5, (double)this.spawnAnchor.getY() + 0.5, (double)this.spawnAnchor.getZ() + 0.5);
    }

    private static final class Choice {
        final BlockPos pos;
        final Vec3 hitVec;
        final EnumFacing side;

        Choice(BlockPos pos, Vec3 hitVec, EnumFacing side) {
            this.pos = pos;
            this.hitVec = hitVec;
            this.side = side;
        }
    }

    private static final class BreakPlanEntry {
        final BlockPos pos;
        final float work;

        BreakPlanEntry(BlockPos pos, float work) {
            this.pos = pos;
            this.work = work;
        }
    }
}

