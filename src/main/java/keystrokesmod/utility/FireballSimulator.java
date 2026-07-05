package keystrokesmod.utility;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.projectile.EntityLargeFireball;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

public final class FireballSimulator {

    private static final int MAX_TICKS = 200;
    private static final double DRAG = 0.95D;

    private FireballSimulator() {}

    public static Result simulate(EntityLargeFireball fireball) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            return new Result(null);
        }

        double posX = fireball.posX;
        double posY = fireball.posY;
        double posZ = fireball.posZ;
        double motionX = fireball.motionX;
        double motionY = fireball.motionY;
        double motionZ = fireball.motionZ;
        double accX = fireball.accelerationX;
        double accY = fireball.accelerationY;
        double accZ = fireball.accelerationZ;

        for (int tick = 0; tick < MAX_TICKS; tick++) {
            double nextX = posX + motionX;
            double nextY = posY + motionY;
            double nextZ = posZ + motionZ;

            Vec3 from = new Vec3(posX, posY, posZ);
            Vec3 to = new Vec3(nextX, nextY, nextZ);

            MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(from, to, false, true, false);
            if (hit != null && hit.hitVec != null) {
                return new Result(hit.hitVec);
            }

            posX = nextX;
            posY = nextY;
            posZ = nextZ;

            motionX += accX;
            motionY += accY;
            motionZ += accZ;

            motionX *= DRAG;
            motionY *= DRAG;
            motionZ *= DRAG;

            if (posY < -64.0D || posY > 512.0D) {
                break;
            }
        }

        return new Result(null);
    }

    public static final class Result {
        private final Vec3 impactPosition;

        private Result(Vec3 impactPosition) {
            this.impactPosition = impactPosition;
        }

        public Vec3 getImpactPosition() {
            return impactPosition;
        }
    }
}
