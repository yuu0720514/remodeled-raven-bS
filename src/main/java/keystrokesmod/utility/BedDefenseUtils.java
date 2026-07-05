package keystrokesmod.utility;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BedDefenseUtils implements IMinecraftInstance {
    private BedDefenseUtils() {
    }

    public static BedRef findNearestBed(double searchRange) {
        if (!Utils.nullCheck()) {
            return null;
        }
        int radius = (int) Math.ceil(searchRange);
        double rangeSq = searchRange * searchRange;
        BlockPos origin = new BlockPos((Entity) mc.thePlayer);
        BedRef best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        HashSet<BlockPos> seenFoot = new HashSet<BlockPos>();
        for (int x = -radius; x <= radius; ++x) {
            for (int y = -radius; y <= radius; ++y) {
                for (int z = -radius; z <= radius; ++z) {
                    BedRef bed = getBedRef(origin.add(x, y, z));
                    if (bed == null || seenFoot.contains(bed.foot)) {
                        continue;
                    }
                    seenFoot.add(bed.foot);
                    double dist = mc.thePlayer.getDistanceSq(bed.foot);
                    if (dist > rangeSq || dist >= bestDist) {
                        continue;
                    }
                    bestDist = dist;
                    best = bed;
                }
            }
        }
        return best;
    }

    public static BedRef getBedRef(BlockPos pos) {
        if (mc.theWorld == null) {
            return null;
        }
        IBlockState state = mc.theWorld.getBlockState(pos);
        if (state == null || !(state.getBlock() instanceof BlockBed)) {
            return null;
        }
        BlockBed.EnumPartType part = (BlockBed.EnumPartType) state.getValue((IProperty) BlockBed.PART);
        EnumFacing facing = (EnumFacing) state.getValue((IProperty) BlockBed.FACING);
        BlockPos foot = part == BlockBed.EnumPartType.FOOT ? pos : pos.offset(facing.getOpposite());
        IBlockState footState = mc.theWorld.getBlockState(foot);
        if (footState == null || !(footState.getBlock() instanceof BlockBed)
                || footState.getValue((IProperty) BlockBed.PART) != BlockBed.EnumPartType.FOOT) {
            return null;
        }
        EnumFacing footFacing = (EnumFacing) footState.getValue((IProperty) BlockBed.FACING);
        BlockPos head = foot.offset(footFacing);
        IBlockState headState = mc.theWorld.getBlockState(head);
        if (headState == null || !(headState.getBlock() instanceof BlockBed)
                || headState.getValue((IProperty) BlockBed.PART) != BlockBed.EnumPartType.HEAD) {
            return null;
        }
        return new BedRef(foot, head, footFacing);
    }

    public static DefBlock toRelative(BedRef bed, BlockPos worldPos) {
        int dx = worldPos.getX() - bed.foot.getX();
        int dy = worldPos.getY() - bed.foot.getY();
        int dz = worldPos.getZ() - bed.foot.getZ();
        EnumFacing rightFacing = bed.facing.rotateY();
        int forward = dx * bed.facing.getFrontOffsetX() + dz * bed.facing.getFrontOffsetZ();
        int right = dx * rightFacing.getFrontOffsetX() + dz * rightFacing.getFrontOffsetZ();
        return new DefBlock(right, dy, forward, null);
    }

    public static BlockPos toWorld(BedRef bed, DefBlock block) {
        EnumFacing rightFacing = bed.facing.rotateY();
        int x = bed.foot.getX() + bed.facing.getFrontOffsetX() * block.forward + rightFacing.getFrontOffsetX() * block.right;
        int z = bed.foot.getZ() + bed.facing.getFrontOffsetZ() * block.forward + rightFacing.getFrontOffsetZ() * block.right;
        return new BlockPos(x, bed.foot.getY() + block.up, z);
    }

    public static List<DefBlock> defaultDefense() {
        ArrayList<DefBlock> list = new ArrayList<DefBlock>();
        list.add(new DefBlock(-1, 0, 0, null));
        list.add(new DefBlock(1, 0, 0, null));
        list.add(new DefBlock(-1, 0, 1, null));
        list.add(new DefBlock(1, 0, 1, null));
        list.add(new DefBlock(0, 0, -1, null));
        list.add(new DefBlock(0, 0, 2, null));
        list.add(new DefBlock(0, 1, 0, null));
        list.add(new DefBlock(0, 1, 1, null));
        list.add(new DefBlock(-1, 1, 0, null));
        list.add(new DefBlock(1, 1, 0, null));
        list.add(new DefBlock(-1, 1, 1, null));
        list.add(new DefBlock(1, 1, 1, null));
        return list;
    }

    public static Set<String> toKeySet(List<DefBlock> blocks) {
        HashSet<String> keys = new HashSet<String>();
        if (blocks == null) {
            return keys;
        }
        for (DefBlock block : blocks) {
            keys.add(key(block));
        }
        return keys;
    }

    public static boolean isAllowedPosition(BedRef bed, BlockPos worldPos, Set<String> allowedKeys) {
        if (bed == null || worldPos == null || allowedKeys == null || allowedKeys.isEmpty()) {
            return false;
        }
        return allowedKeys.contains(key(toRelative(bed, worldPos)));
    }

    public static String key(DefBlock block) {
        return block.right + ":" + block.up + ":" + block.forward;
    }

    public static boolean matches(DefBlock a, DefBlock b) {
        return a.right == b.right && a.up == b.up && a.forward == b.forward;
    }

    public static class BedRef {
        public final BlockPos foot;
        public final BlockPos head;
        public final EnumFacing facing;

        public BedRef(BlockPos foot, BlockPos head, EnumFacing facing) {
            this.foot = foot;
            this.head = head;
            this.facing = facing;
        }
    }

    public static class DefBlock {
        public final int right;
        public final int up;
        public final int forward;
        public Block block;

        public DefBlock(int right, int up, int forward, Block block) {
            this.right = right;
            this.up = up;
            this.forward = forward;
            this.block = block;
        }
    }
}
