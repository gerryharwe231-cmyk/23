package com.slopeconnector.hotfix;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.enums.WallShape;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/** Source-block geometry in the moving path frame: S=tangent, W=width, N=normal. */
public final class SourceProfile {
    public static final byte MATERIAL_GENERAL = 0;
    public static final byte MATERIAL_RAIL = 1;
    public static final byte MATERIAL_POST = 2;
    public static final byte MATERIAL_PANE = 3;
    public static final byte MATERIAL_WALL = 4;
    public static final byte MATERIAL_FULL = 5;

    record Part(double minS, double maxS,
                double minW, double maxW,
                double minN, double maxN,
                boolean continuous,
                byte materialHint) {}

    final List<Part> parts;
    final boolean fullLike;
    final double connectionReach;

    private SourceProfile(List<Part> parts, boolean fullLike, double connectionReach) {
        this.parts = List.copyOf(parts);
        this.fullLike = fullLike;
        this.connectionReach = connectionReach;
    }

    static SourceProfile from(World world, BlockPos pos, BlockState state,
                              Vec3d pathDirection, Vec3d widthAxis, Vec3d normalAxis) {
        if (state.isFullCube(world, pos)) {
            return new SourceProfile(List.of(
                    new Part(-0.5, 0.5, -0.5, 0.5, -0.5, 0.5,
                            true, MATERIAL_FULL)
            ), true, 0.5);
        }

        // Pane textures contain their visible rods/openings in alpha, so their real connected visual
        // is one thin continuous sheet. Separate solid rods destroy the horizontal connectors.
        if (state.isOf(Blocks.IRON_BARS)) return paneProfile(widthAxis, normalAxis);
        if (state.getBlock() instanceof PaneBlock) return paneProfile(widthAxis, normalAxis);

        // Read the source block's connected outline rather than replacing every railing with a hard-
        // coded vanilla fence. This preserves modded balusters, beams and wall profiles when their
        // block class/properties expose those shapes.
        if (state.getBlock() instanceof FenceBlock) {
            return connectedOutlineProfile(world, pos, state, pathDirection, widthAxis, normalAxis,
                    MATERIAL_RAIL, MATERIAL_POST, 0.125);
        }
        if (state.getBlock() instanceof WallBlock) {
            return connectedOutlineProfile(world, pos, state, pathDirection, widthAxis, normalAxis,
                    MATERIAL_WALL, MATERIAL_POST, 0.25);
        }
        if (hasHorizontalConnections(state)) {
            return connectedOutlineProfile(world, pos, state, pathDirection, widthAxis, normalAxis,
                    MATERIAL_GENERAL, MATERIAL_GENERAL, 0.125);
        }

        return outlineProfile(world, pos, state, pathDirection, widthAxis, normalAxis,
                MATERIAL_GENERAL, MATERIAL_GENERAL, 0.5);
    }

    private static SourceProfile connectedOutlineProfile(World world, BlockPos pos, BlockState original,
                                                         Vec3d pathDirection, Vec3d widthAxis, Vec3d normalAxis,
                                                         byte continuousHint, byte repeatedHint,
                                                         double defaultReach) {
        BlockState connected = straightConnectedState(original, pathDirection);
        SourceProfile profile = outlineProfile(world, pos, connected, pathDirection, widthAxis, normalAxis,
                continuousHint, repeatedHint, defaultReach);
        if (!profile.parts.isEmpty()) return profile;
        return outlineProfile(world, pos, original, pathDirection, widthAxis, normalAxis,
                continuousHint, repeatedHint, defaultReach);
    }

    private static SourceProfile outlineProfile(World world, BlockPos pos, BlockState state,
                                                Vec3d pathDirection, Vec3d widthAxis, Vec3d normalAxis,
                                                byte continuousHint, byte repeatedHint,
                                                double defaultReach) {
        VoxelShape shape = state.getOutlineShape(world, pos);
        List<Box> boxes = shape.isEmpty() ? List.of(new Box(0, 0, 0, 1, 1, 1)) : shape.getBoundingBoxes();
        List<Part> parts = new ArrayList<>();
        double coreReach = 0.0;
        for (Box box : boxes) {
            double minS = Double.POSITIVE_INFINITY, maxS = Double.NEGATIVE_INFINITY;
            double minW = Double.POSITIVE_INFINITY, maxW = Double.NEGATIVE_INFINITY;
            double minN = Double.POSITIVE_INFINITY, maxN = Double.NEGATIVE_INFINITY;
            for (int ix = 0; ix < 2; ix++) for (int iy = 0; iy < 2; iy++) for (int iz = 0; iz < 2; iz++) {
                Vec3d local = new Vec3d(
                        (ix == 0 ? box.minX : box.maxX) - 0.5,
                        (iy == 0 ? box.minY : box.maxY) - 0.5,
                        (iz == 0 ? box.minZ : box.maxZ) - 0.5);
                double s = local.dotProduct(pathDirection);
                double w = local.dotProduct(widthAxis);
                double n = local.dotProduct(normalAxis);
                minS = Math.min(minS, s); maxS = Math.max(maxS, s);
                minW = Math.min(minW, w); maxW = Math.max(maxW, w);
                minN = Math.min(minN, n); maxN = Math.max(maxN, n);
            }
            if (maxS - minS < 1.0E-4 || maxW - minW < 1.0E-4 || maxN - minN < 1.0E-4) continue;
            boolean continuous = maxS - minS >= 0.72;
            byte hint = continuous ? continuousHint : repeatedHint;
            parts.add(new Part(minS, maxS, minW, maxW, minN, maxN, continuous, hint));
            if (!continuous && minS <= 1.0E-5 && maxS >= -1.0E-5) {
                coreReach = Math.max(coreReach, Math.max(Math.abs(minS), Math.abs(maxS)));
            }
        }
        if (parts.isEmpty()) {
            parts.add(new Part(-0.5, 0.5, -0.5, 0.5, -0.5, 0.5,
                    true, continuousHint));
            return new SourceProfile(parts, false, 0.5);
        }
        double reach = coreReach > 1.0E-4 ? coreReach : defaultReach;
        return new SourceProfile(parts, false, Math.max(0.03125, Math.min(0.5, reach)));
    }

    private static BlockState straightConnectedState(BlockState state, Vec3d pathDirection) {
        boolean alongX = Math.abs(pathDirection.x) >= Math.abs(pathDirection.z);
        try {
            if (state.contains(Properties.NORTH)) state = state.with(Properties.NORTH, !alongX);
            if (state.contains(Properties.SOUTH)) state = state.with(Properties.SOUTH, !alongX);
            if (state.contains(Properties.EAST)) state = state.with(Properties.EAST, alongX);
            if (state.contains(Properties.WEST)) state = state.with(Properties.WEST, alongX);

            if (state.contains(Properties.NORTH_WALL_SHAPE)) {
                state = state.with(Properties.NORTH_WALL_SHAPE, alongX ? WallShape.NONE : WallShape.LOW);
            }
            if (state.contains(Properties.SOUTH_WALL_SHAPE)) {
                state = state.with(Properties.SOUTH_WALL_SHAPE, alongX ? WallShape.NONE : WallShape.LOW);
            }
            if (state.contains(Properties.EAST_WALL_SHAPE)) {
                state = state.with(Properties.EAST_WALL_SHAPE, alongX ? WallShape.LOW : WallShape.NONE);
            }
            if (state.contains(Properties.WEST_WALL_SHAPE)) {
                state = state.with(Properties.WEST_WALL_SHAPE, alongX ? WallShape.LOW : WallShape.NONE);
            }
            if (state.contains(Properties.UP)) state = state.with(Properties.UP, true);
        } catch (IllegalArgumentException ignored) {
            return state;
        }
        return state;
    }

    private static boolean hasHorizontalConnections(BlockState state) {
        return state.contains(Properties.NORTH) && state.contains(Properties.SOUTH)
                && state.contains(Properties.EAST) && state.contains(Properties.WEST);
    }

    private static SourceProfile paneProfile(Vec3d widthAxis, Vec3d normalAxis) {
        boolean verticalIsW = isVertical(widthAxis, normalAxis);
        if (verticalIsW) {
            return new SourceProfile(List.of(
                    new Part(-0.5, 0.5, -0.5, 0.5, -0.0625, 0.0625,
                            true, MATERIAL_PANE)
            ), false, 0.0625);
        }
        return new SourceProfile(List.of(
                new Part(-0.5, 0.5, -0.0625, 0.0625, -0.5, 0.5,
                        true, MATERIAL_PANE)
        ), false, 0.0625);
    }

    private static boolean isVertical(Vec3d widthAxis, Vec3d normalAxis) {
        return Math.abs(widthAxis.y) >= Math.abs(normalAxis.y);
    }
}
