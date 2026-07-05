package keystrokesmod.module.impl.combat;

import keystrokesmod.event.ClientRotationEvent;
import keystrokesmod.helper.RotationHelper;
import keystrokesmod.mixin.impl.accessor.IAccessorMinecraft;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.world.AntiBot;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AimAssist extends Module {

    private SliderSetting mode;
    private SliderSetting targetMode;
    private SliderSetting speed;
    private SliderSetting multipointHorizontal;
    private SliderSetting multipointVertical;
    private SliderSetting randomization;
    private SliderSetting fov;
    private SliderSetting range;
    private SliderSetting sortMode;

    private ButtonSetting aimInvis;
    private ButtonSetting clickAim;
    private ButtonSetting ignoreTeammates;
    private ButtonSetting ignoreBehindWalls;
    private ButtonSetting ignoreBehindEntities;
    private ButtonSetting stopWhenBreaking;
    private ButtonSetting keepMoveDirection;
    private SliderSetting hoverDelay;
    private ButtonSetting weaponOnly;
    private ButtonSetting increasedFovWhileLocked;

    private long miningStartTime = -1;
    private Entity lockedTarget;
    private Entity smoothedTargetEntity;
    private long lastSmoothNanoTime = -1L;
    private float lockedYaw   = Float.NaN;
    private float lockedPitch = Float.NaN;
    private boolean regularAppliedThisRenderFrame = false;

    private static final int MODE_NORMAL = 0;
    private static final int MODE_SILENT = 1;
    private static final int MODE_LOCK_ON = 2;
    private static final float LOCK_ON_ERROR_LOCKED_DEGREES = 2.0F;

    private String[] AIM_MODES = new String[]{"Regular", "Silent", "Lock-on"};
    private String[] TARGET_MODES = new String[]{"Single", "Switch"};
    private String[] SORT_MODES = new String[]{"Health", "Angle", "Hurt time", "Distance"};

    public AimAssist() {
        super("Aim Assist", category.combat);
        this.registerSetting(mode = new SliderSetting("Mode", 0, AIM_MODES));
        this.registerSetting(targetMode = new SliderSetting("Target mode", 0, TARGET_MODES));
        this.registerSetting(speed = new SliderSetting("Speed", 10, 1, 30, 1));
        this.registerSetting(multipointHorizontal = new SliderSetting("Multipoint horizontal", "%", 0, 0, 100, 1));
        this.registerSetting(multipointVertical = new SliderSetting("Multipoint vertical", "%", 0, 0, 100, 1));
        this.registerSetting(randomization = new SliderSetting("Randomization", "%", 20, 0, 100, 1));
        this.registerSetting(fov = new SliderSetting("FOV", 90.0D, 15.0D, 360.0D, 1.0D));
        this.registerSetting(range = new SliderSetting("Range", 4.5D, 0.0D, 5.0D, 0.1D));
        this.registerSetting(sortMode = new SliderSetting("Sort", 1, SORT_MODES));
        this.registerSetting(ignoreBehindWalls = new ButtonSetting("Ignore behind walls", false));
        this.registerSetting(ignoreBehindEntities = new ButtonSetting("Ignore behind entities", false));
        this.registerSetting(aimInvis = new ButtonSetting("Aim invis", false));
        this.registerSetting(clickAim = new ButtonSetting("Require mouse", true));
        this.registerSetting(ignoreTeammates = new ButtonSetting("Ignore teammates", true));
        this.registerSetting(stopWhenBreaking = new ButtonSetting("Stop when breaking", false));
        this.registerSetting(keepMoveDirection = new ButtonSetting("Keep move direction", true));
        this.registerSetting(hoverDelay = new SliderSetting("Hover delay", " ms", 100, 0, 500, 1));
        this.registerSetting(weaponOnly = new ButtonSetting("Weapon only", false));
        this.registerSetting(increasedFovWhileLocked = new ButtonSetting("Increased FOV while locked", true));
    }

    @Override
    public String getInfo() {
        return AIM_MODES[(int) mode.getInput()];
    }

    @Override
    public void guiUpdate() {
        hoverDelay.setVisible(stopWhenBreaking.isToggled(), this);
        keepMoveDirection.setVisible(mode.getInput() == MODE_SILENT, this);
        increasedFovWhileLocked.setVisible(isSingleTargetMode(), this);
    }

    @Override
    public void onDisable() {
        miningStartTime = -1;
        lockedTarget = null;
        this.resetLockOnSmooth();
    }

    private void resetLockOnSmooth() {
        smoothedTargetEntity = null;
        lastSmoothNanoTime = -1L;
        lockedYaw   = Float.NaN;
        lockedPitch = Float.NaN;
    }

    private float getFrameDeltaSeconds() {
        long now = System.nanoTime();
        if (lastSmoothNanoTime < 0L) {
            lastSmoothNanoTime = now;
            return 1.0F / 60.0F;
        }
        float delta = (now - lastSmoothNanoTime) / 1_000_000_000.0F;
        lastSmoothNanoTime = now;
        return MathHelper.clamp_float(delta, 0.001F, 0.05F);
    }

    private float getExpSmoothFactor(float rate, float deltaSeconds) {
        return 1.0F - (float) Math.exp(-rate * deltaSeconds);
    }

    private boolean isLockOnMode() {
        return (int) mode.getInput() == MODE_LOCK_ON;
    }

    private boolean isSingleTargetMode() {
        return (int) targetMode.getInput() == 0;
    }

    private float getLockOnSpeedScale() {
        return 0.7F + ((int) speed.getInput()) / 30.0F * 1.1F;
    }

    private float getAdaptiveLockOnRate(float baseRate, float errorBoost, float angularError, float speedScale) {
        float rate = (baseRate + angularError * errorBoost) * speedScale;
        return MathHelper.clamp_float(rate, baseRate * speedScale, 58.0F);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase == TickEvent.Phase.START) {
            regularAppliedThisRenderFrame = false;
            return;
        }
        if (this.isLockOnMode()) {
            this.applyAim(false);
            return;
        }
        if ((int) mode.getInput() == MODE_NORMAL && !regularAppliedThisRenderFrame) {
            this.applyAim(false);
            regularAppliedThisRenderFrame = true;
        }
    }

    private void applyAim(boolean silentMode) {
        if (ModuleManager.killAura != null && ModuleManager.killAura.isEnabled() && KillAura.target != null) {
            return;
        }
        if (silentMode && mode.getInput() != MODE_SILENT) {
            return;
        }
        if (!silentMode && mode.getInput() == MODE_SILENT) {
            return;
        }
        if (!conditionsMet()) {
            if (this.isLockOnMode()) {
                this.resetLockOnSmooth();
            }
            return;
        }
        Entity en = getEnemy(silentMode);
        if (en == null) {
            if (this.isLockOnMode()) {
                this.resetLockOnSmooth();
            }
            return;
        }

        float[] rot = this.getTargetRotations(en, silentMode, null);
        if (rot == null) {
            return;
        }

        if (silentMode) {
            return;
        }

        mc.thePlayer.rotationYaw = rot[0];
        mc.thePlayer.rotationPitch = rot[1];
        mc.thePlayer.rotationYawHead = rot[0];
    }

    private float[] getTargetRotations(Entity target, boolean silentMode, ClientRotationEvent e) {
        int speedVal = this.getAimSpeed();
        double multipointH = multipointHorizontal.getInput();
        double multipointV = multipointVertical.getInput();
        float randomizationPercent = this.isLockOnMode() ? 0.0F : (float) randomization.getInput();
        boolean useBackup = ignoreBehindWalls.isToggled() || ignoreBehindEntities.isToggled();

        if (silentMode && e != null) {
            return RotationHelper.get().getRotationsToTarget(target, e, speedVal, multipointH, multipointV, randomizationPercent, useBackup, range.getInput(), !ignoreBehindWalls.isToggled(), !ignoreBehindEntities.isToggled());
        }

        if (this.isLockOnMode()) {
            return this.getLockOnRotations(target);
        }

        Vec3 predictedHead = this.getPredictedHeadCenter(target);
        if (predictedHead != null) {
            float baseYaw   = mc.thePlayer.rotationYaw;
            float basePitch = mc.thePlayer.rotationPitch;
            float[] desiredRot = this.getRotationsToPointExact(
                    predictedHead.xCoord, predictedHead.yCoord, predictedHead.zCoord, baseYaw, basePitch);
            if (desiredRot != null) {
                float errorYaw   = MathHelper.wrapAngleTo180_float(desiredRot[0] - baseYaw);
                float errorPitch = desiredRot[1] - basePitch;
                float t = MathHelper.clamp_float(speedVal / 30.0F, 0.0F, 1.0F);
                float factor = 0.08F + t * 0.77F;
                float randScale = (randomizationPercent / 100.0F) * 0.30F;
                float jitterYaw   = (float) ((Math.random() - 0.5) * 2.0 * Math.abs(errorYaw)   * randScale);
                float jitterPitch = (float) ((Math.random() - 0.5) * 2.0 * Math.abs(errorPitch) * randScale);
                float newYaw   = baseYaw   + errorYaw   * factor + jitterYaw;
                float newPitch = RotationUtils.clampPitch(basePitch + errorPitch * factor + jitterPitch);
                return new float[] { newYaw, newPitch };
            }
        }

        return RotationHelper.get().getRotationsToTarget(target, speedVal, multipointH, multipointV, randomizationPercent, useBackup, range.getInput(), !ignoreBehindWalls.isToggled(), !ignoreBehindEntities.isToggled());
    }

    private int getAimSpeed() {
        return (int) speed.getInput();
    }

    private float[] getLockOnRotations(Entity target) {
        if (target == null || mc.thePlayer == null) {
            return null;
        }

        if (target != smoothedTargetEntity) {
            smoothedTargetEntity = target;
            lastSmoothNanoTime = -1L;
        }

        float deltaSeconds = this.getFrameDeltaSeconds();
        float speedScale = this.getLockOnSpeedScale();

        Vec3 headCenter = this.getPredictedHeadCenter(target);
        if (headCenter == null) {
            return null;
        }

        float baseYaw   = !Float.isNaN(lockedYaw)   ? lockedYaw   : mc.thePlayer.rotationYaw;
        float basePitch = !Float.isNaN(lockedPitch)  ? lockedPitch : mc.thePlayer.rotationPitch;

        float[] desiredRot = this.getRotationsToPointExact(
                headCenter.xCoord, headCenter.yCoord, headCenter.zCoord, baseYaw, basePitch);
        if (desiredRot == null) {
            return null;
        }

        float errorYaw   = MathHelper.wrapAngleTo180_float(desiredRot[0] - baseYaw);
        float errorPitch = desiredRot[1] - basePitch;
        float angularError = (float) MathHelper.sqrt_double(errorYaw * errorYaw + errorPitch * errorPitch);
        boolean lockedOn = angularError <= LOCK_ON_ERROR_LOCKED_DEGREES;

        float yaw;
        float pitch;
        if (lockedOn) {
            yaw   = desiredRot[0];
            pitch = desiredRot[1];
        } else {
            float viewRate = this.getAdaptiveLockOnRate(34.0F, 3.8F, angularError, speedScale);
            float viewFactor = this.getExpSmoothFactor(viewRate, deltaSeconds);
            yaw   = baseYaw   + errorYaw   * viewFactor;
            pitch = basePitch + errorPitch * viewFactor;
        }
        lockedYaw   = yaw;
        lockedPitch = RotationUtils.clampPitch(pitch);
        return new float[] { lockedYaw, lockedPitch };
    }

    private Vec3 getPredictedHeadCenter(Entity entity) {
        float partial = ((IAccessorMinecraft) mc).getTimer().renderPartialTicks;
        double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partial;
        double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partial;
        double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partial;

        float borderSize = entity.getCollisionBorderSize();
        net.minecraft.util.AxisAlignedBB bb = entity.getEntityBoundingBox().expand(borderSize, borderSize, borderSize);

        double headCenterY;
        if (entity instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) entity;
            headCenterY = y + living.getEyeHeight() + entity.height * 0.06D;
        } else {
            headCenterY = y + entity.height * 0.925D;
        }
        headCenterY = MathHelper.clamp_double(headCenterY, bb.minY + 0.05D, bb.maxY - 0.05D);

        double velX = entity.posX - entity.lastTickPosX;
        double velY = entity.posY - entity.lastTickPosY;
        double velZ = entity.posZ - entity.lastTickPosZ;

        double distSq = mc.thePlayer.getDistanceSqToEntity(entity);
        float predTicks = (float) Math.min(0.8, Math.sqrt(distSq) * 0.07);

        return new Vec3(x + velX * predTicks, headCenterY + velY * predTicks, z + velZ * predTicks);
    }

    private double lerp(double from, double to, float factor) {
        return from + (to - from) * factor;
    }

    private Vec3 getInterpolatedHeadCenter(Entity entity) {
        float partial = ((IAccessorMinecraft) mc).getTimer().renderPartialTicks;
        double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partial;
        double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partial;
        double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partial;

        float borderSize = entity.getCollisionBorderSize();
        net.minecraft.util.AxisAlignedBB bb = entity.getEntityBoundingBox().expand(borderSize, borderSize, borderSize);
        double centerX = x;
        double centerZ = z;

        double headCenterY;
        if (entity instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) entity;
            // Eye level + small offset lands near the vertical center of the head hitbox.
            headCenterY = y + living.getEyeHeight() + entity.height * 0.06D;
        } else {
            headCenterY = y + entity.height * 0.925D;
        }

        headCenterY = MathHelper.clamp_double(headCenterY, bb.minY + 0.05D, bb.maxY - 0.05D);
        return new Vec3(centerX, headCenterY, centerZ);
    }

    private float[] getRotationsToPointExact(double x, double y, double z, float baseYaw, float basePitch) {
        double deltaX = x - mc.thePlayer.posX;
        double deltaZ = z - mc.thePlayer.posZ;
        double deltaY = y - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        double horizDistSq = deltaX * deltaX + deltaZ * deltaZ;

        if (horizDistSq < 1.0E-12) {
            float pitch = (float) (-(Math.atan2(deltaY, 0.0D) * 57.295780181884766D));
            return new float[] { baseYaw, RotationUtils.clampPitch(pitch) };
        }

        float targetYaw = (float) (Math.atan2(deltaZ, deltaX) * 57.295780181884766D) - 90.0F;
        double horizDist = MathHelper.sqrt_double(horizDistSq);
        float targetPitch = (float) (-(Math.atan2(deltaY, horizDist) * 57.295780181884766D));
        return new float[] { baseYaw + MathHelper.wrapAngleTo180_float(targetYaw - baseYaw), RotationUtils.clampPitch(targetPitch) };
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void onClientRotation(ClientRotationEvent e) {
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.shouldOverrideMouseOver()) {
            return;
        }
        if (ModuleManager.killAura != null && ModuleManager.killAura.isEnabled() && KillAura.target != null) return;
        if (mode.getInput() != MODE_SILENT || !conditionsMet()) {
            return;
        }
        Entity en = getEnemy(true);
        if (en == null) {
            return;
        }

        float[] rot = this.getTargetRotations(en, true, e);
        if (rot == null) return;
        RotationHelper.get().forceMovementFix = true;
        RotationHelper.get().setServerRelativeMovementInputs(!keepMoveDirection.isToggled());
        e.yaw = rot[0];
        e.pitch = rot[1];
    }

    @Override
    public void onUpdate() {
        if (this.isLockOnMode() || (int) mode.getInput() == MODE_NORMAL) {
            // Lock-on と Regular は onRenderTick で処理するためスキップ
            return;
        }
        this.applyAim(false);
    }

    private Entity getEnemy(boolean silentMode) {
        if (this.isSingleTargetMode()) {
            boolean expandedFov = increasedFovWhileLocked.isToggled() && lockedTarget != null;
            if (lockedTarget != null && this.isValidTarget(lockedTarget, silentMode, expandedFov)) {
                return lockedTarget;
            }
        }

        Entity best = this.findBestEnemy(silentMode);
        lockedTarget = best;
        return best;
    }

    private Entity findBestEnemy(boolean silentMode) {
        final int fovVal = (int) this.fov.getInput();
        float viewYaw = mc.thePlayer.rotationYaw;
        if (silentMode) {
            Float serverYaw = RotationHelper.get().getServerYaw();
            if (serverYaw != null) {
                viewYaw = serverYaw;
            }
        }

        List<EntityPlayer> candidates = new ArrayList<>();
        for (EntityPlayer entityPlayer : mc.theWorld.playerEntities) {
            if (!this.passesTargetFilters(entityPlayer, silentMode, fovVal, viewYaw)) {
                continue;
            }
            candidates.add(entityPlayer);
        }

        if (candidates.isEmpty()) {
            return null;
        }

        Comparator<EntityPlayer> primary = this.getSortComparator();
        candidates.sort(primary.thenComparingDouble(p -> mc.thePlayer.getDistanceSqToEntity(p)));

        if (ignoreBehindWalls.isToggled() || ignoreBehindEntities.isToggled()) {
            double multipointH = multipointHorizontal.getInput();
            double multipointV = multipointVertical.getInput();
            double rangeVal = range.getInput();
            boolean allowThroughBlocks = !ignoreBehindWalls.isToggled();
            boolean allowThroughEntities = !ignoreBehindEntities.isToggled();
            for (EntityPlayer candidate : candidates) {
                if (RotationUtils.hasValidAimPoint(candidate, multipointH, multipointV, rangeVal, allowThroughBlocks, allowThroughEntities)) {
                    return candidate;
                }
            }
            return null;
        }

        return candidates.get(0);
    }

    private boolean isValidTarget(Entity target, boolean silentMode, boolean expandedFov) {
        if (!(target instanceof EntityPlayer)) {
            return false;
        }
        float viewYaw = mc.thePlayer.rotationYaw;
        if (silentMode) {
            Float serverYaw = RotationHelper.get().getServerYaw();
            if (serverYaw != null) {
                viewYaw = serverYaw;
            }
        }
        int fovVal = expandedFov ? 360 : (int) this.fov.getInput();
        return this.passesTargetFilters((EntityPlayer) target, silentMode, fovVal, viewYaw);
    }

    private boolean passesTargetFilters(EntityPlayer entityPlayer, boolean silentMode, int fovVal, float viewYaw) {
        if (entityPlayer == mc.thePlayer || entityPlayer.deathTime != 0) {
            return false;
        }
        if (Utils.isFriended(entityPlayer)) {
            return false;
        }
        if (ignoreTeammates.isToggled() && Utils.isTeammate(entityPlayer)) {
            return false;
        }
        if (!aimInvis.isToggled() && entityPlayer.isInvisible()) {
            return false;
        }
        if (RotationUtils.distanceSqFromEyeToClosestOnAABB(entityPlayer) > range.getInput() * range.getInput()) {
            return false;
        }
        if (AntiBot.isBot(entityPlayer)) {
            return false;
        }
        if (fovVal != 360) {
            float angleToEntity = RotationUtils.angle(entityPlayer.posX, entityPlayer.posZ);
            if (!Utils.inFov(viewYaw, (float) fovVal, angleToEntity)) {
                return false;
            }
        }
        if (ignoreBehindWalls.isToggled() || ignoreBehindEntities.isToggled()) {
            double multipointH = multipointHorizontal.getInput();
            double multipointV = multipointVertical.getInput();
            double rangeVal = range.getInput();
            boolean allowThroughBlocks = !ignoreBehindWalls.isToggled();
            boolean allowThroughEntities = !ignoreBehindEntities.isToggled();
            if (!RotationUtils.hasValidAimPoint(entityPlayer, multipointH, multipointV, rangeVal, allowThroughBlocks, allowThroughEntities)) {
                return false;
            }
        }
        return true;
    }

    private Comparator<EntityPlayer> getSortComparator() {
        switch ((int) sortMode.getInput()) {
            case 0:
                return Comparator.comparingDouble(p -> p.getHealth() + p.getAbsorptionAmount());
            case 1:
                return Comparator.comparingDouble(p -> {
                    double yawDelta = Math.abs(Utils.aimDifference(p, false));
                    double pitchDelta = Math.abs(Utils.pitchDifference(p, false));
                    return yawDelta + pitchDelta;
                });
            case 2:
                return Comparator.comparingInt(p -> p.hurtTime);
            case 3:
                return Comparator.comparingDouble(p -> mc.thePlayer.getDistanceSqToEntity(p));
            default:
                return Comparator.comparingDouble(p -> {
                    double yawDelta = Math.abs(Utils.aimDifference(p, false));
                    double pitchDelta = Math.abs(Utils.pitchDifference(p, false));
                    return yawDelta + pitchDelta;
                });
        }
    }

    private boolean conditionsMet() {
        if (mc.currentScreen != null || !mc.inGameHasFocus) {
            return false;
        }
        if (weaponOnly.isToggled() && !Utils.holdingWeapon()) {
            return false;
        }
        if (clickAim.isToggled() && !Mouse.isButtonDown(0)) {
            return false;
        }
        if (stopWhenBreaking.isToggled() && Utils.isMining()) {
            if (miningStartTime == -1) {
                miningStartTime = System.currentTimeMillis();
            }
            long elapsed = System.currentTimeMillis() - miningStartTime;
            if (elapsed >= hoverDelay.getInput()) {
                return false;
            }
        } else {
            miningStartTime = -1;
        }
        return true;
    }

}
