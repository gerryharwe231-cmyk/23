package com.slopeconnector.hotfix;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Passive CSG trimming. Only blocks with real positive-volume overlap are replaced. */
final class ArcAutoTrim {
    private static final double INTERSECTION_EPS = 2.0E-5;
    private static final double REMAINING_EPS = 2.0E-5;

    private ArcAutoTrim() {}

    static int apply(World world,
                     Map<BlockPos, List<WorldPrism>> prismsByCell,
                     Set<BlockPos> protectedPos) {
        int changed = 0;
        for (Map.Entry<BlockPos, List<WorldPrism>> entry : prismsByCell.entrySet()) {
            BlockPos pos = entry.getKey();
            if (protectedPos.contains(pos) || entry.getValue().isEmpty()) continue;

            BlockState source = world.getBlockState(pos);
            if (!isOrdinaryFull(world, pos, source)) continue;

            SubtractResult result = subtractCell(pos, entry.getValue());
            if (!result.touched) continue;

            double remainingVolume = 0.0;
            for (ConvexGeometry.Poly piece : result.remaining) remainingVolume += ConvexGeometry.volume(piece);
            if (remainingVolume >= 1.0 - REMAINING_EPS) continue;

            if (remainingVolume <= REMAINING_EPS || result.remaining.isEmpty()) {
                world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                changed++;
                continue;
            }

            List<ConvexGeometry.SurfaceTriangle> boundary = ConvexGeometry.boundaryTriangles(
                    result.remaining, pos, result.cutters);
            if (boundary.isEmpty()) continue;

            List<ArcTrimBlockEntity.Triangle> triangles = new ArrayList<>(boundary.size());
            for (ConvexGeometry.SurfaceTriangle triangle : boundary) {
                triangles.add(new ArcTrimBlockEntity.Triangle(triangle.xyz(), triangle.cutFace()));
            }

            Vec3d toLocal = new Vec3d(-pos.getX(), -pos.getY(), -pos.getZ());
            List<ConvexGeometry.Poly> localPieces = new ArrayList<>(result.remaining.size());
            for (ConvexGeometry.Poly piece : result.remaining) {
                localPieces.add(ConvexGeometry.translated(piece, toLocal));
            }
            List<ArcTrimBlockEntity.TrimBox> boxes = new ArrayList<>();
            for (VoxelShapeUtil.BoxSpec box : VoxelShapeUtil.voxelizePolys(localPieces, 8)) {
                boxes.add(new ArcTrimBlockEntity.TrimBox(
                        box.minX(), box.minY(), box.minZ(),
                        box.maxX(), box.maxY(), box.maxZ()));
            }

            world.setBlockState(pos, ArcHotfixMod.ARC_TRIM.getDefaultState(), 3);
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof ArcTrimBlockEntity trim) {
                trim.setData(source, triangles, boxes);
                world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
                changed++;
            } else {
                // Never leave an invisible replacement block behind if entity creation failed.
                world.setBlockState(pos, source, 3);
            }
        }
        return changed;
    }

    private static SubtractResult subtractCell(BlockPos cell, List<WorldPrism> prisms) {
        List<ConvexGeometry.Poly> remaining = new ArrayList<>();
        remaining.add(ConvexGeometry.cube(cell));
        List<ConvexGeometry.Poly> activeCutters = new ArrayList<>();
        Box cellBox = new Box(cell.getX(), cell.getY(), cell.getZ(),
                cell.getX() + 1.0, cell.getY() + 1.0, cell.getZ() + 1.0);
        boolean touched = false;

        for (WorldPrism prism : prisms) {
            if (!intersects(cellBox, prism.bounds)) continue;
            List<ConvexGeometry.Poly> next = new ArrayList<>();
            boolean cutterTouched = false;
            for (ConvexGeometry.Poly piece : remaining) {
                if (!intersects(ConvexGeometry.bounds(piece), prism.bounds)) {
                    next.add(piece);
                    continue;
                }
                ConvexGeometry.Poly overlap = ConvexGeometry.intersection(piece, prism.poly);
                double overlapVolume = ConvexGeometry.volume(overlap);
                if (overlap == null || overlapVolume <= INTERSECTION_EPS) {
                    next.add(piece);
                    continue;
                }
                cutterTouched = true;
                touched = true;
                next.addAll(ConvexGeometry.subtract(List.of(piece), prism.poly));
            }
            if (cutterTouched) activeCutters.add(prism.poly);
            remaining = next;
            if (remaining.isEmpty()) break;
        }
        return new SubtractResult(remaining, activeCutters, touched);
    }

    private static boolean isOrdinaryFull(World world, BlockPos pos, BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity()) return false;
        if (state.getBlock() == ArcHotfixMod.ARC_RIBBON || state.getBlock() == ArcHotfixMod.ARC_TRIM) return false;
        return state.isFullCube(world, pos);
    }

    private static boolean intersects(Box a, Box b) {
        return a.maxX > b.minX + 1.0E-7 && a.minX < b.maxX - 1.0E-7
                && a.maxY > b.minY + 1.0E-7 && a.minY < b.maxY - 1.0E-7
                && a.maxZ > b.minZ + 1.0E-7 && a.minZ < b.maxZ - 1.0E-7;
    }

    static final class WorldPrism {
        final float[] xyz;
        final ConvexGeometry.Poly poly;
        final Box bounds;

        WorldPrism(float[] source) {
            this.xyz = source.clone();
            this.poly = ConvexGeometry.prism(this.xyz);
            this.bounds = ConvexGeometry.bounds(this.xyz);
        }
    }

    private record SubtractResult(List<ConvexGeometry.Poly> remaining,
                                  List<ConvexGeometry.Poly> cutters,
                                  boolean touched) {}
}
