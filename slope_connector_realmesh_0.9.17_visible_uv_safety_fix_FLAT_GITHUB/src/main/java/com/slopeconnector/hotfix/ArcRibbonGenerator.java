package com.slopeconnector.hotfix;

import com.slopeconnector.SlopeConnectorMod;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ArcRibbonGenerator {
    private static final int MAX_PRISMS = 4096;
    private static final byte NO_FACES = 0x00;
    private static final byte SIDE_FACES = 0x0F;
    private static final byte ALL_FACES = 0x3F;

    public record Result(int placed, int materialPicked, int fallbackUsed, int skipped, String error) {
        static Result error(String error) { return new Result(0, 0, 0, 0, error); }
    }

    private record Frame(Vec3d center, Vec3d radial) {}
    private record BuildContext(Vec3d startConn, Vec3d d, Vec3d n, Vec3d w,
                                ArcPath.Result path, Vec3d sourcePathDirection) {}
    private static final class MeshCell {
        final List<ArcRibbonBlockEntity.Prism> prisms = new ArrayList<>();
        final List<ArcRibbonBlockEntity.SurfaceQuad> surfaces = new ArrayList<>();
    }

    private ArcRibbonGenerator() {}

    public static Result generate(World world, BlockPos startBlock, BlockPos controlBlock, BlockPos endBlock,
                                  BlockState source, SlopeConnectorMod.PlayerSettings settings) {
        BuildContext context;
        boolean threePoint = settings.arcPointMode == SlopeConnectorMod.ArcPointMode.THREE && controlBlock != null;
        if (threePoint) {
            context = threePointContext(startBlock, controlBlock, endBlock, settings);
        } else {
            ResultOrContext checked = twoPointContext(startBlock, endBlock, settings);
            if (checked.error != null) return Result.error(checked.error);
            context = checked.context;
        }
        if (context == null || !context.path.valid()) {
            return Result.error(context == null ? "路径计算失败" : context.path.error());
        }

        List<ArcPath.Sample> samples = context.path.samples();
        if (samples.size() < 2) return Result.error("路径采样失败");
        double totalLength = samples.get(samples.size() - 1).distance();
        if (totalLength < 0.05) return Result.error("弧线长度太短");
        if (totalLength > 128.0) return Result.error("弧线过长；为避免卡顿，单次连接请控制在128格以内");

        ArcPath.Sample firstSample = samples.get(0);
        Vec3d sourceNormal = context.d.multiply(firstSample.ns()).add(context.n.multiply(firstSample.no())).normalize();
        SourceProfile profile = SourceProfile.from(world, startBlock, source,
                context.sourcePathDirection, context.w, sourceNormal);
        int width = profile.fullLike ? Math.max(1, settings.width) : 1;
        int repeatCount = Math.max(1, (int)Math.round(totalLength));
        double textureScale = repeatCount / totalLength;

        long modelEstimate = profile.usesBakedModel() ? (long)repeatCount * width : 0L;
        long collisionEstimate = (long)Math.max(1, samples.size() - 1)
                * Math.max(1, profile.parts.size()) * width;
        if (modelEstimate + collisionEstimate > MAX_PRISMS) {
            return Result.error("本次弧线细分过多，已拒绝生成以避免卡顿；请缩短距离或减小宽度");
        }

        Set<BlockPos> protectedPos = new HashSet<>();
        protectedPos.add(startBlock);
        protectedPos.add(endBlock);
        if (controlBlock != null) protectedPos.add(controlBlock);

        Map<BlockPos, MeshCell> mesh = new LinkedHashMap<>();
        boolean autoTrim = profile.fullLike && ArcAutoTrimSettings.enabled();
        Map<BlockPos, List<ArcAutoTrim.WorldPrism>> worldPrismsByCell = autoTrim
                ? new LinkedHashMap<>() : null;

        int prismCount = 0;
        if (profile.usesBakedModel()) {
            for (int lane = 0; lane < width; lane++) {
                double laneOffset = lane - (width - 1) * 0.5;
                prismCount += addModelCells(world, context, samples, totalLength, repeatCount,
                        laneOffset, profile.fullLike, protectedPos, mesh, worldPrismsByCell);
            }
        }

        // Exact connected models use these parts only for collision. Fallback shapes still render
        // through the legacy prism faces when no baked-model sweep is available.
        if (!profile.usesBakedModel() && profile.connectionReach < 0.499) {
            prismCount += addEndpointBridges(world, context, samples, profile, startBlock, endBlock,
                    protectedPos, mesh, worldPrismsByCell);
        }
        for (int lane = 0; lane < width; lane++) {
            double laneOffset = lane - (width - 1) * 0.5;
            for (SourceProfile.Part part : profile.parts) {
                if (part.continuous()) {
                    prismCount += addContinuousPart(world, context, samples, part, laneOffset, textureScale,
                            protectedPos, mesh, worldPrismsByCell);
                } else {
                    prismCount += addRepeatedPart(world, context, samples, totalLength, repeatCount,
                            part, laneOffset, protectedPos, mesh, worldPrismsByCell);
                }
                if (prismCount > MAX_PRISMS) {
                    return Result.error("本次弧线生成量过大，已中止以避免卡顿");
                }
            }
        }

        Vec3d basisS, basisW, basisN;
        if (profile.modelMode == SourceProfile.MODEL_CONNECTED) {
            basisS = new Vec3d(1, 0, 0);
            basisW = new Vec3d(0, 0, 1);
            basisN = new Vec3d(0, 1, 0);
        } else {
            basisS = context.sourcePathDirection;
            basisW = context.w;
            basisN = sourceNormal;
        }

        int placed = 0, skipped = 0;
        for (Map.Entry<BlockPos, MeshCell> entry : mesh.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState existing = world.getBlockState(pos);
            if (existing.hasBlockEntity() && existing.getBlock() != ArcHotfixMod.ARC_RIBBON) {
                skipped++;
                continue;
            }
            if (!existing.isAir() && existing.getBlock() != ArcHotfixMod.ARC_RIBBON) {
                skipped++;
                continue;
            }
            world.setBlockState(pos, ArcHotfixMod.ARC_RIBBON.getDefaultState(), 3);
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof ArcRibbonBlockEntity ribbon) {
                MeshCell cell = entry.getValue();
                ribbon.setData(source, cell.prisms, cell.surfaces,
                        profile.modelMode, basisS, basisW, basisN);
                world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
                placed++;
            }
        }

        int trimmed = autoTrim && worldPrismsByCell != null
                ? ArcAutoTrim.apply(world, worldPrismsByCell, protectedPos) : 0;
        return new Result(placed, trimmed, mesh.size(), skipped, "");
    }

    private static int addModelCells(World world, BuildContext context, List<ArcPath.Sample> samples,
                                     double totalLength, int tileCount, double laneOffset,
                                     boolean collidable, Set<BlockPos> protectedPos,
                                     Map<BlockPos, MeshCell> mesh,
                                     Map<BlockPos, List<ArcAutoTrim.WorldPrism>> worldByCell) {
        int added = 0;
        double spacing = totalLength / tileCount;
        for (int tile = 0; tile < tileCount; tile++) {
            double s0 = tile * spacing;
            double s1 = (tile + 1) * spacing;
            Frame fa = frameAtDistance(context, samples, s0);
            Frame fb = frameAtDistance(context, samples, s1);
            float[] vertices = prism(fa.center, fb.center, fa.radial, fb.radial, context.w,
                    laneOffset - 0.5, laneOffset + 0.5, -0.5, 0.5);
            if (makePrism(world, vertices,
                    0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f,
                    SIDE_FACES, SourceProfile.MATERIAL_BAKED_MODEL, collidable,
                    protectedPos, mesh, worldByCell) != null) added++;
        }
        return added;
    }

    private static int addEndpointBridges(World world, BuildContext context, List<ArcPath.Sample> samples,
                                          SourceProfile profile, BlockPos startBlock, BlockPos endBlock,
                                          Set<BlockPos> protectedPos, Map<BlockPos, MeshCell> mesh,
                                          Map<BlockPos, List<ArcAutoTrim.WorldPrism>> worldByCell) {
        int added = 0;
        ArcPath.Sample first = samples.get(0), last = samples.get(samples.size() - 1);
        Frame firstFrame = frame(context, first), lastFrame = frame(context, last);
        Vec3d startTangent = tangent(context, first), endTangent = tangent(context, last);
        Vec3d startInner = blockCenter(startBlock).add(startTangent.multiply(profile.connectionReach));
        Vec3d endInner = blockCenter(endBlock).subtract(endTangent.multiply(profile.connectionReach));

        for (SourceProfile.Part part : profile.parts) {
            if (!part.continuous()) continue;
            if (startInner.squaredDistanceTo(firstFrame.center) > 1.0E-6) {
                float[] vertices = prism(startInner, firstFrame.center,
                        firstFrame.radial, firstFrame.radial, context.w,
                        part.minW(), part.maxW(), part.minN(), part.maxN());
                byte mask = part.materialHint() == SourceProfile.MATERIAL_COLLISION ? NO_FACES : SIDE_FACES;
                if (makePrism(world, vertices,
                        textureCoord(profile.connectionReach + 0.5), 1.0f,
                        textureCoord(part.minW() + 0.5), textureCoordEnd(part.minW() + 0.5, part.maxW() + 0.5),
                        textureCoord(part.minN() + 0.5), textureCoordEnd(part.minN() + 0.5, part.maxN() + 0.5),
                        mask, part.materialHint(), true, protectedPos, mesh, worldByCell) != null) added++;
            }
            if (lastFrame.center.squaredDistanceTo(endInner) > 1.0E-6) {
                float[] vertices = prism(lastFrame.center, endInner,
                        lastFrame.radial, lastFrame.radial, context.w,
                        part.minW(), part.maxW(), part.minN(), part.maxN());
                byte mask = part.materialHint() == SourceProfile.MATERIAL_COLLISION ? NO_FACES : SIDE_FACES;
                if (makePrism(world, vertices,
                        0.0f, textureCoord(0.5 - profile.connectionReach),
                        textureCoord(part.minW() + 0.5), textureCoordEnd(part.minW() + 0.5, part.maxW() + 0.5),
                        textureCoord(part.minN() + 0.5), textureCoordEnd(part.minN() + 0.5, part.maxN() + 0.5),
                        mask, part.materialHint(), true, protectedPos, mesh, worldByCell) != null) added++;
            }
        }
        return added;
    }

    private static Vec3d tangent(BuildContext context, ArcPath.Sample sample) {
        return context.d.multiply(sample.no()).subtract(context.n.multiply(sample.ns())).normalize();
    }

    private static int addContinuousPart(World world, BuildContext context, List<ArcPath.Sample> samples,
                                         SourceProfile.Part part, double laneOffset, double textureScale,
                                         Set<BlockPos> protectedPos, Map<BlockPos, MeshCell> mesh,
                                         Map<BlockPos, List<ArcAutoTrim.WorldPrism>> worldByCell) {
        int added = 0;
        byte mask = part.materialHint() == SourceProfile.MATERIAL_COLLISION ? NO_FACES : SIDE_FACES;
        for (int i = 0; i < samples.size() - 1; i++) {
            ArcPath.Sample a = samples.get(i), b = samples.get(i + 1);
            Frame fa = frame(context, a), fb = frame(context, b);
            float[] worldVertices = prism(fa.center, fb.center, fa.radial, fb.radial, context.w,
                    part.minW() + laneOffset, part.maxW() + laneOffset, part.minN(), part.maxN());
            double mappedA = a.distance() * textureScale;
            double mappedB = b.distance() * textureScale;
            if (makePrism(world, worldVertices,
                    (float)tilePhase(mappedA), (float)phaseEnd(mappedA, mappedB),
                    textureCoord(part.minW() + 0.5), textureCoordEnd(part.minW() + 0.5, part.maxW() + 0.5),
                    textureCoord(part.minN() + 0.5), textureCoordEnd(part.minN() + 0.5, part.maxN() + 0.5),
                    mask, part.materialHint(), true, protectedPos, mesh, worldByCell) != null) added++;
        }
        return added;
    }

    private static int addRepeatedPart(World world, BuildContext context, List<ArcPath.Sample> samples,
                                       double totalLength, int tileCount, SourceProfile.Part part,
                                       double laneOffset, Set<BlockPos> protectedPos,
                                       Map<BlockPos, MeshCell> mesh,
                                       Map<BlockPos, List<ArcAutoTrim.WorldPrism>> worldByCell) {
        int added = 0;
        byte mask = part.materialHint() == SourceProfile.MATERIAL_COLLISION ? NO_FACES : ALL_FACES;
        double spacing = totalLength / tileCount;
        for (int tile = 0; tile < tileCount; tile++) {
            double center = (tile + 0.5) * spacing;
            double s0 = clamp(center + part.minS() * spacing, 0.0, totalLength);
            double s1 = clamp(center + part.maxS() * spacing, 0.0, totalLength);
            if (s1 - s0 < 1.0E-4) continue;
            Frame fa = frameAtDistance(context, samples, s0);
            Frame fb = frameAtDistance(context, samples, s1);
            float[] worldVertices = prism(fa.center, fb.center, fa.radial, fb.radial, context.w,
                    part.minW() + laneOffset, part.maxW() + laneOffset, part.minN(), part.maxN());
            if (makePrism(world, worldVertices,
                    textureCoord(part.minS() + 0.5), textureCoordEnd(part.minS() + 0.5, part.maxS() + 0.5),
                    textureCoord(part.minW() + 0.5), textureCoordEnd(part.minW() + 0.5, part.maxW() + 0.5),
                    textureCoord(part.minN() + 0.5), textureCoordEnd(part.minN() + 0.5, part.maxN() + 0.5),
                    mask, part.materialHint(), true, protectedPos, mesh, worldByCell) != null) added++;
        }
        return added;
    }

    private static ArcRibbonBlockEntity.Prism makePrism(
            World world, float[] worldVertices,
            float u0, float u1, float w0, float w1, float n0, float n1,
            byte faceMask, byte materialHint, boolean collidable,
            Set<BlockPos> protectedPos, Map<BlockPos, MeshCell> mesh,
            Map<BlockPos, List<ArcAutoTrim.WorldPrism>> worldByCell) {
        BlockPos holder = chooseHolder(world, ConvexGeometry.bounds(worldVertices), protectedPos);
        if (holder == null) return null;
        float[] local = new float[24];
        for (int v = 0; v < 8; v++) {
            local[v * 3] = worldVertices[v * 3] - holder.getX();
            local[v * 3 + 1] = worldVertices[v * 3 + 1] - holder.getY();
            local[v * 3 + 2] = worldVertices[v * 3 + 2] - holder.getZ();
        }
        ArcRibbonBlockEntity.Prism prism = new ArcRibbonBlockEntity.Prism(
                local, u0, u1, w0, w1, n0, n1,
                faceMask, materialHint, collidable);
        mesh.computeIfAbsent(holder, key -> new MeshCell()).prisms.add(prism);
        if (worldByCell != null && collidable) indexWorldPrism(worldVertices, worldByCell);
        return prism;
    }

    private static BuildContext threePointContext(BlockPos startBlock, BlockPos controlBlock,
                                                   BlockPos endBlock, SlopeConnectorMod.PlayerSettings settings) {
        Vec3d p0 = blockCenter(startBlock), p1 = blockCenter(controlBlock), p2 = blockCenter(endBlock);
        Vec3d first = p1.subtract(p0), second = p2.subtract(p1);
        if (first.lengthSquared() < 0.0025 || second.lengthSquared() < 0.0025) return null;
        Vec3d t0 = first.normalize(), t2 = second.normalize();
        Vec3d startConn = p0.add(t0.multiply(rayCubeDistance(t0)));
        Vec3d endConn = p2.subtract(t2.multiply(rayCubeDistance(t2)));
        Vec3d chord = endConn.subtract(startConn);
        if (chord.lengthSquared() < 0.0025) return null;
        Vec3d d = chord.normalize();

        Vec3d planeNormal = p1.subtract(startConn).crossProduct(endConn.subtract(p1));
        if (planeNormal.lengthSquared() < 1.0E-8) {
            Direction selected = settings.face == null ? Direction.UP : settings.face;
            Vec3d preferred = directionVector(selected);
            planeNormal = d.crossProduct(preferred);
            if (planeNormal.lengthSquared() < 1.0E-8) planeNormal = d.crossProduct(new Vec3d(0, 1, 0));
            if (planeNormal.lengthSquared() < 1.0E-8) planeNormal = d.crossProduct(new Vec3d(1, 0, 0));
        }
        Vec3d w = planeNormal.normalize();
        Vec3d n = w.crossProduct(d).normalize();
        Vec3d controlRel = p1.subtract(startConn);
        if (controlRel.dotProduct(n) < 0.0) { w = w.multiply(-1.0); n = n.multiply(-1.0); }
        double run = chord.length();
        double cs = controlRel.dotProduct(d), co = controlRel.dotProduct(n);
        ArcPath.Result path = ArcPath.threePoint(run, 0.0, cs, co);
        return new BuildContext(startConn, d, n, w, path, t0);
    }

    private static ResultOrContext twoPointContext(BlockPos startBlock, BlockPos endBlock,
                                                    SlopeConnectorMod.PlayerSettings settings) {
        Direction face = settings.face == null ? Direction.UP : settings.face;
        Vec3d n = directionVector(face);
        Vec3d startCenter = blockCenter(startBlock), endCenter = blockCenter(endBlock);
        Vec3d raw = endCenter.subtract(startCenter);
        double delta = raw.dotProduct(n);
        Vec3d planar = raw.subtract(n.multiply(delta));
        if (planar.lengthSquared() < 0.0025) return ResultOrContext.error("两个端点在连接平面内没有足够距离");
        Vec3d d = planar.normalize();
        Vec3d w = d.crossProduct(n).normalize();

        if (Math.abs(delta) >= 0.05) {
            int expected = delta > 0.0 ? 1 : -1;
            if (settings.arcSide != expected) {
                return ResultOrContext.error("当前正/反向与两个端点的位置不匹配，已拒绝生成；请切换正反向后重试");
            }
        }

        Vec3d startConn, endConn;
        if (Math.abs(delta) < 0.05) {
            startConn = startCenter.add(d.multiply(rayCubeDistance(d)));
            endConn = endCenter.subtract(d.multiply(rayCubeDistance(d)));
        } else if (delta > 0.0) {
            startConn = startCenter.add(d.multiply(rayCubeDistance(d)));
            endConn = endCenter.subtract(n.multiply(rayCubeDistance(n)));
        } else {
            startConn = startCenter.subtract(n.multiply(rayCubeDistance(n)));
            endConn = endCenter.subtract(d.multiply(rayCubeDistance(d)));
        }
        Vec3d between = endConn.subtract(startConn);
        double run = between.dotProduct(d), offset = between.dotProduct(n);
        if (run < 0.05) return ResultOrContext.error("端点连接面之间距离太短");
        ArcPath.Result path = ArcPath.twoPoint(run, offset, settings.arcSide);
        if (!path.valid()) return ResultOrContext.error(path.error());
        ArcPath.Sample first = path.samples().get(0);
        Vec3d tangent = d.multiply(first.no()).subtract(n.multiply(first.ns())).normalize();
        return ResultOrContext.ok(new BuildContext(startConn, d, n, w, path, tangent));
    }

    private record ResultOrContext(BuildContext context, String error) {
        static ResultOrContext ok(BuildContext context) { return new ResultOrContext(context, null); }
        static ResultOrContext error(String error) { return new ResultOrContext(null, error); }
    }

    private static Frame frame(BuildContext context, ArcPath.Sample sample) {
        Vec3d center = context.startConn.add(context.d.multiply(sample.s())).add(context.n.multiply(sample.o()));
        Vec3d radial = context.d.multiply(sample.ns()).add(context.n.multiply(sample.no())).normalize();
        return new Frame(center, radial);
    }

    private static Frame frameAtDistance(BuildContext context, List<ArcPath.Sample> samples, double distance) {
        if (distance <= 0.0) return frame(context, samples.get(0));
        ArcPath.Sample last = samples.get(samples.size() - 1);
        if (distance >= last.distance()) return frame(context, last);
        int low = 0, high = samples.size() - 1;
        while (low + 1 < high) {
            int mid = (low + high) >>> 1;
            if (samples.get(mid).distance() <= distance) low = mid; else high = mid;
        }
        ArcPath.Sample a = samples.get(low), b = samples.get(high);
        double t = (distance - a.distance()) / Math.max(1.0E-9, b.distance() - a.distance());
        ArcPath.Sample interpolated = new ArcPath.Sample(
                lerp(a.s(), b.s(), t), lerp(a.o(), b.o(), t),
                lerp(a.ns(), b.ns(), t), lerp(a.no(), b.no(), t), distance);
        return frame(context, interpolated);
    }

    private static BlockPos chooseHolder(World world, Box bounds, Set<BlockPos> protectedPos) {
        Vec3d center = bounds.getCenter();
        BlockPos primary = BlockPos.ofFloored(center);
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            BlockPos pos = primary.add(dx, dy, dz);
            if (protectedPos.contains(pos)) continue;
            BlockState state = world.getBlockState(pos);
            if (!state.isAir() && state.getBlock() != ArcHotfixMod.ARC_RIBBON) continue;
            double distance = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                    .squaredDistanceTo(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    private static float[] prism(Vec3d c0, Vec3d c1, Vec3d r0, Vec3d r1, Vec3d w,
                                 double w0, double w1, double n0, double n1) {
        Vec3d[] vertices = new Vec3d[]{
                c0.add(w.multiply(w0)).add(r0.multiply(n0)),
                c0.add(w.multiply(w1)).add(r0.multiply(n0)),
                c0.add(w.multiply(w1)).add(r0.multiply(n1)),
                c0.add(w.multiply(w0)).add(r0.multiply(n1)),
                c1.add(w.multiply(w0)).add(r1.multiply(n0)),
                c1.add(w.multiply(w1)).add(r1.multiply(n0)),
                c1.add(w.multiply(w1)).add(r1.multiply(n1)),
                c1.add(w.multiply(w0)).add(r1.multiply(n1))
        };
        float[] out = new float[24];
        for (int i = 0; i < 8; i++) {
            out[i * 3] = (float)vertices[i].x;
            out[i * 3 + 1] = (float)vertices[i].y;
            out[i * 3 + 2] = (float)vertices[i].z;
        }
        return out;
    }

    private static void indexWorldPrism(float[] xyz,
                                        Map<BlockPos, List<ArcAutoTrim.WorldPrism>> worldByCell) {
        Box bounds = ConvexGeometry.bounds(xyz);
        int minX = (int)Math.floor(bounds.minX);
        int maxX = (int)Math.floor(bounds.maxX - 1.0E-7);
        int minY = (int)Math.floor(bounds.minY);
        int maxY = (int)Math.floor(bounds.maxY - 1.0E-7);
        int minZ = (int)Math.floor(bounds.minZ);
        int maxZ = (int)Math.floor(bounds.maxZ - 1.0E-7);
        ArcAutoTrim.WorldPrism prism = new ArcAutoTrim.WorldPrism(xyz);
        for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
            worldByCell.computeIfAbsent(new BlockPos(x, y, z), key -> new ArrayList<>()).add(prism);
        }
    }

    private static Vec3d directionVector(Direction direction) {
        return new Vec3d(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
    }

    private static double rayCubeDistance(Vec3d direction) {
        double max = Math.max(Math.abs(direction.x), Math.max(Math.abs(direction.y), Math.abs(direction.z)));
        return max < 1.0E-9 ? 0.5 : 0.5 / max;
    }

    private static float textureCoord(double value) { return (float)tilePhase(value); }
    private static float textureCoordEnd(double start, double end) { return (float)phaseEnd(start, end); }
    private static double tilePhase(double value) {
        double fraction = value - Math.floor(value);
        return fraction < 1.0E-5 || 1.0 - fraction < 1.0E-5 ? 0.0 : fraction;
    }
    private static double phaseEnd(double start, double end) {
        double a = tilePhase(start), b = tilePhase(end);
        if (end > start + 1.0E-6 && b <= a + 1.0E-6) return 1.0;
        return b;
    }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static Vec3d blockCenter(BlockPos p) { return new Vec3d(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5); }
}
