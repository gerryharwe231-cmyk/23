package com.slopeconnector.hotfix;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One-time sparse voxelisation with greedy box merging. */
final class VoxelShapeUtil {
    private VoxelShapeUtil() {}

    record BoxSpec(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {}
    private record Cell(int x, int y, int z) {}

    static List<BoxSpec> voxelizePrisms(List<ArcRibbonBlockEntity.Prism> prisms, int grid) {
        List<ConvexGeometry.Poly> polys = new ArrayList<>();
        for (ArcRibbonBlockEntity.Prism prism : prisms) if (prism.collidable()) polys.add(ConvexGeometry.prism(prism.xyz()));
        return voxelize(polys, grid);
    }

    static List<BoxSpec> voxelizePolys(List<ConvexGeometry.Poly> polys, int grid) {
        return voxelize(polys, grid);
    }

    private static List<BoxSpec> voxelize(List<ConvexGeometry.Poly> polys, int grid) {
        if (polys.isEmpty()) return List.of();
        Set<Cell> filled = new HashSet<>();
        double cell = 1.0 / grid;
        for (ConvexGeometry.Poly poly : polys) {
            List<ConvexGeometry.Plane> planes = ConvexGeometry.planes(poly);
            Box b = bounds(poly);
            int minX = (int)Math.floor(b.minX * grid) - 1;
            int maxX = (int)Math.ceil(b.maxX * grid) + 1;
            int minY = (int)Math.floor(b.minY * grid) - 1;
            int maxY = (int)Math.ceil(b.maxY * grid) + 1;
            int minZ = (int)Math.floor(b.minZ * grid) - 1;
            int maxZ = (int)Math.ceil(b.maxZ * grid) + 1;
            for (int x = minX; x < maxX; x++) {
                for (int y = minY; y < maxY; y++) {
                    for (int z = minZ; z < maxZ; z++) {
                        Vec3d p = new Vec3d((x + 0.5) * cell, (y + 0.5) * cell, (z + 0.5) * cell);
                        if (contains(planes, p)) filled.add(new Cell(x, y, z));
                    }
                }
            }
        }
        return merge(filled, grid);
    }


    private static boolean contains(List<ConvexGeometry.Plane> planes, Vec3d point) {
        for (ConvexGeometry.Plane plane : planes) {
            if (plane.side(point) > 1.0E-5) return false;
        }
        return true;
    }

    private static List<BoxSpec> merge(Set<Cell> cells, int grid) {
        Set<Cell> remaining = new HashSet<>(cells);
        List<BoxSpec> out = new ArrayList<>();
        float s = 1.0f / grid;
        while (!remaining.isEmpty()) {
            Cell start = remaining.iterator().next();
            int x1 = start.x + 1;
            while (remaining.contains(new Cell(x1, start.y, start.z))) x1++;

            int z1 = start.z + 1;
            outerZ:
            while (true) {
                for (int x = start.x; x < x1; x++) {
                    if (!remaining.contains(new Cell(x, start.y, z1))) break outerZ;
                }
                z1++;
            }

            int y1 = start.y + 1;
            outerY:
            while (true) {
                for (int x = start.x; x < x1; x++) for (int z = start.z; z < z1; z++) {
                    if (!remaining.contains(new Cell(x, y1, z))) break outerY;
                }
                y1++;
            }

            for (int x = start.x; x < x1; x++) for (int y = start.y; y < y1; y++) for (int z = start.z; z < z1; z++) {
                remaining.remove(new Cell(x, y, z));
            }
            out.add(new BoxSpec(start.x * s, start.y * s, start.z * s, x1 * s, y1 * s, z1 * s));
        }
        return out;
    }

    private static Box bounds(ConvexGeometry.Poly poly) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (List<Vec3d> face : poly.faces()) for (Vec3d p : face) {
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
            minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
        }
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
