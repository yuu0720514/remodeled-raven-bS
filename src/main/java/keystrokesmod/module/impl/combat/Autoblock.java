package keystrokesmod.module.impl.combat;



import keystrokesmod.Raven;

import keystrokesmod.event.PrePlayerInteractEvent;

import keystrokesmod.event.RightClickMouseEvent;

import keystrokesmod.event.SendPacketEvent;

import keystrokesmod.event.UseItemEvent;

import keystrokesmod.lag.api.EnumLagDirection;

import keystrokesmod.lag.api.LagRequest;

import keystrokesmod.lag.timeout.ModuleBackedTimeout;

import keystrokesmod.mixin.interfaces.IMixinItemRenderer;

import keystrokesmod.module.Module;

import keystrokesmod.module.ModuleManager;

import keystrokesmod.module.setting.impl.ButtonSetting;

import keystrokesmod.module.setting.impl.DescriptionSetting;

import keystrokesmod.module.setting.impl.SliderSetting;

import keystrokesmod.utility.CombatTargeting;

import keystrokesmod.utility.ReflectionUtils;

import keystrokesmod.utility.Utils;

import net.minecraft.client.network.NetworkPlayerInfo;

import net.minecraft.client.settings.KeyBinding;

import net.minecraft.entity.player.EntityPlayer;

import net.minecraft.network.play.client.C02PacketUseEntity;

import net.minecraftforge.client.event.MouseEvent;

import net.minecraftforge.fml.common.eventhandler.EventPriority;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.lwjgl.input.Keyboard;

import org.lwjgl.input.Mouse;



public class Autoblock

extends Module {

    private static final long MIN_BLOCK_INTERVAL_MS = 50L;

    private static final long COMBO_BLOCK_DELAY_MS = 65L;

    private static final double OPPONENT_REACH_SQ = 3.0 * 3.0;

    private final SliderSetting range = new SliderSetting("Range", 4.0, 2.0, 6.0, 0.1);

    private final SliderSetting maxHurtTimeMs;

    private final SliderSetting maxHoldMs;

    private final ButtonSetting forceBlockAnimation;

    private final SliderSetting lagChance;

    private final SliderSetting lagMaxDuration;

    private final ButtonSetting preventDelayAttacks;

    private final ButtonSetting blockAgainImmediately;

    private final ButtonSetting requireLmb;

    private final ButtonSetting requireRmb;

    private final ButtonSetting onlyWhenDamaged;

    private final ButtonSetting ignoreTeammates;

    private boolean isBlocking;

    private boolean manualBlock;

    private int blockStartTick = -1;

    private EntityPlayer currentTarget;

    private int lastSelfHurtTime;

    private boolean isLagging;

    private int lagStartTick = -1;

    private long nextBlinkMs = -1L;

    private long pendingReblockMs = -1L;

    private boolean prevConditionsMet = false;

    private static final long REBLOCK_GAP_MS = 200L;

    private LagRequest outboundLag;

    private int tickCounter;

    private long lastBlockStartMs;

    private long comboBlockReadyMs;

    private long lastAttackSentMs = -1L;

    private int lastSyncedSlot = -1;

    private boolean slotJustChanged = false;

    private boolean prevHoldingSword = false;



    private static final int SWORD_SWITCH_GUARD_TICKS = 1;

    private int swordSwitchGuardTicks = 0;
public Autoblock() {

        super("Auto Block", Module.category.combat);

        this.registerSetting(this.range);

        this.maxHurtTimeMs = new SliderSetting("Maximum Hurt Time", "ms", 250.0, 50.0, 500.0, 1.0);

        this.registerSetting(this.maxHurtTimeMs);

        this.maxHoldMs = new SliderSetting("Maximum Hold Time", "ms", 100.0, 50.0, 500.0, 1.0);

        this.registerSetting(this.maxHoldMs);

        this.forceBlockAnimation = new ButtonSetting("Force block animation", true);

        this.registerSetting(this.forceBlockAnimation);

        this.registerSetting(new DescriptionSetting("Lag"));

        this.lagChance = new SliderSetting("Lag Chance", "%", 100.0, 0.0, 100.0, 5.0);

        this.registerSetting(this.lagChance);

        this.lagMaxDuration = new SliderSetting("Lag Max Duration", "ms", 100.0, 50.0, 250.0, 1.0);

        this.registerSetting(this.lagMaxDuration);

        this.preventDelayAttacks = new ButtonSetting("Prevent delaying attacks", true);

        this.registerSetting(this.preventDelayAttacks);

        this.blockAgainImmediately = new ButtonSetting("Block again immediately", true);

        this.registerSetting(this.blockAgainImmediately);

        this.registerSetting(new DescriptionSetting("Conditions"));

        this.requireLmb = new ButtonSetting("Require Left mouse", true);

        this.registerSetting(this.requireLmb);

        this.requireRmb = new ButtonSetting("Require right mouse", true);

        this.registerSetting(this.requireRmb);

        this.onlyWhenDamaged = new ButtonSetting("Damaged", false);

        this.registerSetting(this.onlyWhenDamaged);

        this.ignoreTeammates = new ButtonSetting("Ignore teammates", true);

        this.registerSetting(this.ignoreTeammates);

        this.closetModule = true;

    }



    @Override

    public void onEnable() {

        this.tickCounter = 0;

        this.resetState(false);

    }



    @Override

    public void onDisable() {

        this.resetState(false);

        if (Utils.nullCheck() && Mouse.isButtonDown((int)1) && Autoblock.mc.currentScreen == null) {

            KeyBinding.setKeyBindState((int)Autoblock.mc.gameSettings.keyBindUseItem.getKeyCode(), (boolean)true);

        }

    }



    private static int msToTicks(double ms) {

        if (ms <= 0.0) {

            return 0;

        }

        return (int)Math.ceil(ms / 50.0);

    }



    private int getPingMs() {

        if (Autoblock.mc.getNetHandler() == null || Autoblock.mc.thePlayer == null) {

            return 0;

        }

        NetworkPlayerInfo info = Autoblock.mc.getNetHandler().getPlayerInfo(Autoblock.mc.thePlayer.getUniqueID());

        return info != null ? info.getResponseTime() : 0;

    }



    private int getPredictiveHurtTimeThreshold() {

        double leadMs = this.maxHurtTimeMs.getInput() + this.getPingMs() * 0.5;

        return Math.max(1, Math.min(10, msToTicks(leadMs)));

    }



    private boolean isAutoClickerActive() {

        return ModuleManager.autoClicker != null && ModuleManager.autoClicker.isEnabled();

    }



    private boolean resolveLmbDown() {

        if (Mouse.isButtonDown((int)0)) {

            return true;

        }

        if (this.isAutoClickerActive() && this.isEnabled() && this.currentTarget != null) {

            return true;

        }

        return ModuleManager.killAura != null && ModuleManager.killAura.isEnabled() && !ModuleManager.killAura.isRequireMouseDown() && this.currentTarget != null;

    }



    private boolean checkConditions(boolean lmbDown, boolean rmbDown) {

        if (!Utils.holdingSword()) {

            return false;

        }

        if (this.requireLmb.isToggled() && !lmbDown) {

            return false;

        }

        return !this.requireRmb.isToggled() || rmbDown;

    }



    private boolean isInModuleRange() {

        if (this.currentTarget == null || !Utils.nullCheck()) {

            return false;

        }

        double rangeSq = this.range.getInput() * this.range.getInput();

        return CombatTargeting.isWithinRange(this.currentTarget, rangeSq);

    }



    private boolean isTradingActive() {

        if (!Utils.nullCheck() || !Utils.holdingSword()) {

            return false;

        }

        this.refreshCurrentTarget();

        if (this.currentTarget == null || !this.isInModuleRange()) {

            return false;

        }

        if (this.requireRmb.isToggled() && !Mouse.isButtonDown((int)1)) {

            return false;

        }

        return !this.requireLmb.isToggled() || this.resolveLmbDown();

    }



    private void refreshCurrentTarget() {

        if (!Utils.nullCheck()) {

            this.currentTarget = null;

            return;

        }

        this.currentTarget = CombatTargeting.findTarget(this.range.getInput() * this.range.getInput(), this.ignoreTeammates.isToggled());

    }



    private boolean isKbDisplacementActive() {

        KBDisplacement kb = ModuleManager.kbDisplacement;

        return kb != null && kb.isEnabled() && kb.isDisplacementActive();

    }



    private boolean canBlockOnServer() {

        if (!Utils.nullCheck()) {

            return false;

        }

        return true;

    }



    private boolean canEngageAutoblock() {

        return this.canBlockOnServer() && !this.isKbDisplacementActive();

    }



    private boolean shouldForceGuardVisualNow() {
        if (this.slotJustChanged || this.swordSwitchGuardTicks > 0) {
            return false;
        }

        return this.forceBlockAnimation.isToggled() && this.isTradingActive();
    }

    private boolean shouldPredictiveBlock() {

        int selfHurtTime = Autoblock.mc.thePlayer.hurtTime;

        if (selfHurtTime <= 0) {

            return false;

        }

        return selfHurtTime <= this.getPredictiveHurtTimeThreshold();

    }



    private boolean isComboActive() {

        return this.currentTarget != null && this.currentTarget.hurtTime > 0;

    }



    private boolean isOpponentThreatening() {

        if (this.currentTarget == null || !Utils.nullCheck() || !this.isInModuleRange()) {

            return false;

        }

        if (!CombatTargeting.isWithinRange(this.currentTarget, OPPONENT_REACH_SQ)) {

            return false;

        }

        return this.currentTarget.isSwingInProgress;

    }



    private boolean shouldEngageBlock(boolean hurtAgain) {

        if (this.onlyWhenDamaged.isToggled()) {

            return hurtAgain || Autoblock.mc.thePlayer.hurtTime > 0;

        }

        return true;

    }



    private boolean canStartBlockNow(boolean urgent) {

        if (urgent) {

            return true;

        }

        long now = System.currentTimeMillis();

        return this.lastBlockStartMs <= 0L || now - this.lastBlockStartMs >= MIN_BLOCK_INTERVAL_MS;

    }



    private void onOwnAttack() {

        if (!(this.isEnabled() && Utils.nullCheck() && Utils.holdingSword() && this.isTradingActive())) {

            return;

        }

        long now = System.currentTimeMillis();

        if (this.comboBlockReadyMs <= now) {

            this.comboBlockReadyMs = now + COMBO_BLOCK_DELAY_MS;

        }

    }



    private void tryStartTradeBlock(int currentTick, boolean conditionsMet, boolean hurtAgain) {

        if (!conditionsMet || !this.canEngageAutoblock() || this.isBlocking || this.isLagging || !this.shouldEngageBlock(hurtAgain)) {

            return;

        }

        if (this.pendingReblockMs > 0) {

            return;

        }

        long now = System.currentTimeMillis();

        boolean urgent = hurtAgain || this.shouldPredictiveBlock();

        if (this.comboBlockReadyMs > 0L && now < this.comboBlockReadyMs && !urgent) {

            return;

        }

        if (!this.canStartBlockNow(urgent)) {

            return;

        }

        this.comboBlockReadyMs = 0L;

        this.lastBlockStartMs = now;

        this.startBlocking(currentTick);

    }



    private void finishBlockingWithLag(int currentTick) {

        if (this.shouldStartLag()) {

            this.startLag(currentTick);

        }

        this.stopBlocking(true);

    }



    private void updateUseItemKeyState() {

        int keyCode = Autoblock.mc.gameSettings.keyBindUseItem.getKeyCode();

        KeyBinding.setKeyBindState(keyCode, keyCode < 0 ? Mouse.isButtonDown(keyCode + 100) : Keyboard.isKeyDown(keyCode));

    }



    @SubscribeEvent(priority=EventPriority.HIGHEST)

    public void onMouse(MouseEvent e) {

        if (!Utils.nullCheck() || !Utils.holdingSword()) {

            return;

        }

        if (ModuleManager.bedAura != null && ModuleManager.bedAura.isActivelyMining()) {

            return;

        }

        if (e.button == 1 && (this.isManagingBlockInput() || this.resolveLmbDown())) {

            e.setCanceled(true);

        }

    }



    @SubscribeEvent(priority=EventPriority.HIGHEST)

    public void onRightClickMouse(RightClickMouseEvent e) {

        if (this.shouldBlockVanillaUse()) {

            e.setCanceled(true);

        }

    }



    @SubscribeEvent(priority=EventPriority.HIGHEST)

    public void onUseItem(UseItemEvent e) {

        if (this.shouldBlockVanillaUse()) {

            e.setCanceled(true);

        }

    }



    @SubscribeEvent

    public void onRenderTick(TickEvent.RenderTickEvent e) {

        if (e.phase != TickEvent.Phase.START && e.phase != TickEvent.Phase.END) {

            return;

        }

        if (!Utils.nullCheck()) {

            return;

        }

        if (ModuleManager.bedAura != null && ModuleManager.bedAura.isActivelyMining()) {

            return;

        }

        if (Autoblock.mc.currentScreen != null && (this.isBlocking || this.isLagging)) {

            this.resetState(true);

            return;

        }

        this.syncForceGuardVisual();

    }



    private void syncForceGuardVisual() {

        if (!Utils.nullCheck()) {

            return;

        }

        int currentSlot = Autoblock.mc.thePlayer.inventory.currentItem;

        if (currentSlot != this.lastSyncedSlot) {
            this.forceHeldItemResync();
            this.lastSyncedSlot = currentSlot;
            this.slotJustChanged = true;

            if (Utils.holdingSword()) {
                this.swordSwitchGuardTicks = SWORD_SWITCH_GUARD_TICKS;
            }

            return;
        }

        if (!Utils.holdingSword()) {

            this.clearForceGuardVisual();

            return;

        }

        if (!this.forceBlockAnimation.isToggled()) {

            this.clearForceGuardVisual();

            return;

        }

        if (this.slotJustChanged) {

            this.slotJustChanged = false;

            return;

        }

        boolean guardVisual = this.shouldForceGuardVisualNow();

        if (!guardVisual) {
            this.clearForceGuardVisual();
            return;
        }

        ReflectionUtils.setItemInUse(true);

        if (mc.getItemRenderer() != null) {

            IMixinItemRenderer renderer = (IMixinItemRenderer)mc.getItemRenderer();

            renderer.setCancelReset(true);

            renderer.setCancelUpdate(true);

        }

    }



    private void clearForceGuardVisual() {

        ReflectionUtils.setItemInUse(false);

        if (mc.getItemRenderer() != null) {

            IMixinItemRenderer renderer = (IMixinItemRenderer)mc.getItemRenderer();

            renderer.setCancelReset(false);

            renderer.setCancelUpdate(false);

        }

    }



    private void forceHeldItemResync() {
        this.clearForceGuardVisual();

        if (!Utils.nullCheck()) {
            return;
        }

        int keyCode = Autoblock.mc.gameSettings.keyBindUseItem.getKeyCode();
        KeyBinding.setKeyBindState(keyCode, false);
        Autoblock.mc.thePlayer.clearItemInUse();

        // resetEquippedProgress() は通常時の剣の上下揺れ/消失の原因になるため呼ばない。
    }



    @SubscribeEvent(priority=EventPriority.HIGH)

    public void onSendPacket(SendPacketEvent e) {

        if (ModuleManager.bedAura != null && ModuleManager.bedAura.isActivelyMining()) {

            this.releaseLag();

            return;

        }

        if (!(e.getPacket() instanceof C02PacketUseEntity)) {

            return;

        }

        if (((C02PacketUseEntity)e.getPacket()).getAction() != C02PacketUseEntity.Action.ATTACK) {

            return;

        }

        if (this.isBlocking) {

            this.stopBlocking(true);

            this.lastAttackSentMs = System.currentTimeMillis();

            this.onOwnAttack();

            this.doAttackBlink();

            return;

        }

        this.lastAttackSentMs = System.currentTimeMillis();

        this.onOwnAttack();

        this.doAttackBlink();

        if (!this.isLagging || !this.preventDelayAttacks.isToggled()) {

            return;

        }

        this.releaseLag();

        this.pendingReblockMs = System.currentTimeMillis() + REBLOCK_GAP_MS;

    }



    @SubscribeEvent(priority=EventPriority.LOW)

    public void onPrePlayerInteract(PrePlayerInteractEvent e) {

        boolean conditionsMet;

        if (!Utils.nullCheck() || Autoblock.mc.thePlayer.isDead || Autoblock.mc.currentScreen != null) {

            this.resetState(true);

            return;

        }

        if (ModuleManager.bedAura != null && ModuleManager.bedAura.isActivelyMining()) {

            this.resetState(true);

            return;

        }

        int selfHurtTime = Autoblock.mc.thePlayer.hurtTime;

        boolean hurtAgain = selfHurtTime > this.lastSelfHurtTime;

        this.lastSelfHurtTime = selfHurtTime;

        if (!Utils.holdingSword()) {

            this.resetState(false);

            this.prevHoldingSword = false;

            return;

        }

        if (!this.prevHoldingSword) {
            this.forceHeldItemResync();
            this.slotJustChanged = true;
            this.swordSwitchGuardTicks = SWORD_SWITCH_GUARD_TICKS;
            this.prevHoldingSword = true;
            return;
        }

        this.prevHoldingSword = true;

        if (this.swordSwitchGuardTicks > 0) {
            --this.swordSwitchGuardTicks;
            this.clearForceGuardVisual();
        }

        if (!this.canEngageAutoblock()) {

            this.releaseLag();

            this.stopBlocking(true);

            this.comboBlockReadyMs = 0L;

            this.syncForceGuardVisual();

            return;

        }

        ++this.tickCounter;

        int currentTick = this.tickCounter;

        this.currentTarget = CombatTargeting.findTarget(this.range.getInput() * this.range.getInput(), this.ignoreTeammates.isToggled());

        boolean rmbDown = Mouse.isButtonDown((int)1);

        boolean lmbDown = this.resolveLmbDown();

        if (this.requireRmb.isToggled() && !rmbDown) {

            this.resetState(true);

            return;

        }

        if (!lmbDown) {

            this.releaseLag();

            this.stopBlocking(true);

            if (!this.isBlocking) {

                KeyBinding.setKeyBindState((int)Autoblock.mc.gameSettings.keyBindUseItem.getKeyCode(), (boolean)true);

                this.manualBlock = true;

            }

            return;

        }

        if (this.manualBlock) {

            this.updateUseItemKeyState();

            this.manualBlock = false;

        }

        conditionsMet = this.currentTarget != null && this.checkConditions(lmbDown, rmbDown);

        this.prevConditionsMet = conditionsMet;

        if (this.isLagging) {

            if (!conditionsMet) {

                this.releaseLag();

            } else if (this.nextBlinkMs > 0 && System.currentTimeMillis() >= this.nextBlinkMs) {

                this.releaseLag();

                this.lastBlockStartMs = System.currentTimeMillis();

                this.startBlocking(currentTick);

            }

        }

        if (this.pendingReblockMs > 0) {

            if (!conditionsMet) {

                this.pendingReblockMs = -1L;

            } else if (System.currentTimeMillis() >= this.pendingReblockMs) {

                this.pendingReblockMs = -1L;

                this.lastBlockStartMs = System.currentTimeMillis();

                this.startBlocking(currentTick);

            }

        }

        if (!conditionsMet) {

            this.stopBlocking(true);

            this.comboBlockReadyMs = 0L;

            this.syncForceGuardVisual();

            return;

        }

        this.tryStartTradeBlock(currentTick, conditionsMet, hurtAgain);

        if (this.isBlocking) {

            boolean shouldStop = hurtAgain;

            int maxHoldTicks = Autoblock.msToTicks(this.maxHoldMs.getInput());

            if (maxHoldTicks > 0 && this.blockStartTick >= 0 && currentTick - this.blockStartTick >= maxHoldTicks) {

                shouldStop = true;

            }

            if (shouldStop) {

                this.finishBlockingWithLag(currentTick);

                if (!this.isLagging) {

                    this.tryStartTradeBlock(currentTick, conditionsMet, false);

                }

            }

        }

        this.syncForceGuardVisual();

    }



    private boolean isManagingBlockInput() {

        return this.isEnabled() && (this.isBlocking || this.isLagging || this.shouldBlockVanillaUse());

    }



    private boolean shouldBlockVanillaUse() {
        return this.isEnabled() && this.isLagging && Utils.nullCheck() && Utils.holdingSword() && Autoblock.mc.currentScreen == null;
    }



    private void startBlocking(int currentTick) {
        if (!Utils.holdingSword()) {
            return;
        }

        int keyCode = Autoblock.mc.gameSettings.keyBindUseItem.getKeyCode();

        KeyBinding.setKeyBindState((int)keyCode, (boolean)true);

        long msSinceAttack = this.lastAttackSentMs < 0L
                ? Long.MAX_VALUE
                : System.currentTimeMillis() - this.lastAttackSentMs;

        if (msSinceAttack >= 50L) {

            KeyBinding.onTick((int)keyCode);

        }

        this.isBlocking = true;

        this.blockStartTick = currentTick;

    }



    private void stopBlocking(boolean forceRelease) {

        if (!this.isBlocking && !forceRelease) {

            return;

        }

        int keyCode = Autoblock.mc.gameSettings.keyBindUseItem.getKeyCode();

        KeyBinding.setKeyBindState((int)keyCode, (boolean)false);

        this.isBlocking = false;

        this.blockStartTick = -1;

    }



    private boolean shouldStartLag() {

        double chance = this.lagChance.getInput();

        if (chance <= 0.0) {

            return false;

        }

        if (chance >= 100.0) {

            return true;

        }

        return Math.random() * 100.0 < chance;

    }



    private boolean isJumpKeyDown() {

        return Utils.nullCheck() && Autoblock.mc.gameSettings.keyBindJump.isKeyDown();

    }



    private void startLag(int currentTick) {

        if (this.isLagging) {

            return;

        }

        int lagReferenceTick = this.blockStartTick >= 0 ? this.blockStartTick : currentTick;

        this.outboundLag = new LagRequest(EnumLagDirection.ONLY_OUTBOUND, new ModuleBackedTimeout(this));

        Raven.lagHandler.requestLag(this.outboundLag);

        this.isLagging = true;

        this.lagStartTick = lagReferenceTick;

        this.nextBlinkMs = System.currentTimeMillis() + (long) this.lagMaxDuration.getInput();

    }





    private void releaseLag() {

        if (!this.isLagging) {

            return;

        }

        if (this.outboundLag != null) {

            this.outboundLag.getTimeout().forceTimeOut();

            this.outboundLag = null;

        }

        this.isLagging = false;

        this.lagStartTick = -1;

        this.nextBlinkMs = -1L;

        this.pendingReblockMs = -1L;

    }



    private void doAttackBlink() {

        if (this.lagChance.getInput() <= 0.0) return;

        if (!this.isLagging || this.nextBlinkMs <= 0L) return;

        long blinkMs = Math.max(20L, (long)(this.lagMaxDuration.getInput() * 0.4));

        long earlierBlink = System.currentTimeMillis() + blinkMs;

        if (earlierBlink < this.nextBlinkMs) {

            this.nextBlinkMs = earlierBlink;

        }

    }



    private void resetState(boolean releaseUseKey) {

        this.releaseLag();

        this.stopBlocking(releaseUseKey);

        this.manualBlock = false;

        this.clearForceGuardVisual();

        this.currentTarget = null;

        this.lastSelfHurtTime = 0;

        this.lastBlockStartMs = 0L;

        this.comboBlockReadyMs = 0L;

        this.lastAttackSentMs = -1L;

        this.pendingReblockMs = -1L;

        this.prevConditionsMet = false;

        this.slotJustChanged = false;

        this.prevHoldingSword = false;
        this.swordSwitchGuardTicks = 0;

    }



    @Override

    public String getInfo() {

        double chance = this.lagChance.getInput();

        if (chance <= 0.0) {

            return "Pulse";

        }

        if (chance == Math.rint(chance)) {

            return "Lag " + (int)chance + "%";

        }

        return "Lag " + Utils.round(chance, 0) + "%";

    }



    public boolean isActive() {

        return this.isEnabled() && (this.isBlocking || this.isLagging);

    }



    public boolean isLagging() {

        return this.isEnabled() && this.isLagging;

    }






    public boolean isHoldingBlock() {

        return this.isEnabled() && this.isBlocking;

    }



    public boolean shouldSpoofUseItemKey() {

        return this.isEnabled() && this.isLagging && Utils.nullCheck() && Utils.holdingSword();

    }



    public boolean isUseItemKeyDown() {

        return false;

    }



    public boolean shouldShowBlockAnimation() {

        if (!this.isEnabled() || !Utils.holdingSword()) {

            return false;

        }

        if (this.forceBlockAnimation.isToggled()) {

            return this.shouldForceGuardVisualNow();

        }

        return this.isBlocking || this.isLagging;

    }



    public boolean shouldShowThirdPersonBlock() {

        if (!this.isEnabled() || !Utils.holdingSword()) {

            return false;

        }

        if (this.forceBlockAnimation.isToggled()) {

            return this.shouldForceGuardVisualNow();

        }

        return this.isBlocking || this.isLagging;

    }



    public boolean isTradePaused() {

        return false;

    }



    public boolean isServerBlocking() {

        return this.isEnabled() && Utils.holdingSword() && (this.isBlocking || this.isLagging) && this.canEngageAutoblock();

    }



    public boolean isGuardActive() {

        return this.isEnabled() && this.isLagging && Utils.holdingSword();

    }



    public boolean shouldReportBlocking() {

        if (!this.isEnabled() || !Utils.nullCheck() || !Utils.holdingSword()) {

            return false;

        }

        if (this.isLagging) {

            return this.canEngageAutoblock();

        }

        return this.isBlocking && this.canEngageAutoblock();

    }



    public boolean shouldApplyItemSlow() {

        return this.isEnabled() && Utils.nullCheck() && Utils.holdingSword() && this.isBlocking && this.canEngageAutoblock();

    }



    public boolean shouldDisableNoSlowBypass() {

        return this.isEnabled() && Utils.nullCheck() && Utils.holdingSword() && this.isBlocking && this.canEngageAutoblock();

    }



    public boolean canAutoClickerAttack() {

        if (!(this.isEnabled() && Utils.nullCheck() && Utils.holdingSword())) {

            return true;

        }

        if (this.isKbDisplacementActive()) {

            return true;

        }


        if (this.isBlocking) {

            return false;

        }

        return this.isLagging || this.isTradingActive();

    }



    public boolean shouldAssistAutoClicker() {

        return this.isEnabled() && this.isAutoClickerActive() && this.isTradingActive() && this.canAutoClickerAttack();

    }

}


