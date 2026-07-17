package com.slopeconnector.hotfix.client;

import com.slopeconnector.hotfix.SourceProfile;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.state.property.Properties;
import net.minecraft.block.enums.WallShape;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reads the real baked-model UV rectangle instead of stretching the whole atlas sprite. */
final class ArcMaterialHelper {
    record FaceMaterial(Sprite sprite, int color,
                        float uS, float uT, float uC,
                        float vS, float vT, float vC) {
        float u(float s, float t) {
            return clamp(uS * s + uT * t + uC, sprite.getMinU(), sprite.getMaxU());
        }
        float v(float s, float t) {
            return clamp(vS * s + vT * t + vC, sprite.getMinV(), sprite.getMaxV());
        }
        private static float clamp(float value, float a, float b) {
            float min = Math.min(a, b), max = Math.max(a, b);
            if (!Float.isFinite(value)) return min;
            return Math.max(min, Math.min(max, value));
        }
    }

    private record Key(BlockState state, Direction face) {}
    private record SelectionKey(BlockState state, Direction face, byte hint, int aspectBin, int areaBin) {}
    private record Template(Sprite sprite, int tintIndex,
                            float uS, float uT, float uC,
                            float vS, float vT, float vC,
                            float area, float aspect) {}

    private static final Map<Key, List<Template>> CACHE = new ConcurrentHashMap<>();
    private static final Map<SelectionKey, Template> SELECTION_CACHE = new ConcurrentHashMap<>();
    private ArcMaterialHelper() {}

    static FaceMaterial material(BlockState state, Direction face, BlockRenderView world, BlockPos pos,
                                 byte materialHint, float targetAspect, float targetArea) {
        MinecraftClient client = MinecraftClient.getInstance();
        Key key = new Key(state, face);
        List<Template> candidates = CACHE.computeIfAbsent(key,
                cacheKey -> load(client, cacheKey.state, cacheKey.face));
        int aspectBin = Math.round((float)(Math.log(Math.max(0.04f, targetAspect)) * 16.0));
        int areaBin = Math.round(Math.max(0.0f, Math.min(1.0f, targetArea)) * 64.0f);
        SelectionKey selectionKey = new SelectionKey(state, face, materialHint, aspectBin, areaBin);
        Template template = SELECTION_CACHE.computeIfAbsent(selectionKey,
                ignored -> choose(candidates, materialHint, targetAspect, targetArea));
        int color = 0xFFFFFF;
        if (template.tintIndex >= 0) {
            int tint = client.getBlockColors().getColor(state, world, pos, template.tintIndex);
            if (tint != -1) color = tint & 0xFFFFFF;
        }
        return new FaceMaterial(template.sprite, color,
                template.uS, template.uT, template.uC,
                template.vS, template.vT, template.vC);
    }

    private static Template choose(List<Template> candidates, byte hint,
                                   float targetAspect, float targetArea) {
        if (hint == SourceProfile.MATERIAL_PANE) {
            Template largest = largest(candidates);
            // Pane and iron-bar textures are alpha masks authored as one full 16x16 repeat. Cropping
            // to one multipart arm removes their horizontal connectors, so use the complete sprite.
            return identity(largest.sprite, largest.tintIndex);
        }
        if (hint == SourceProfile.MATERIAL_FULL) {
            // Arc body, endpoint skin and passive trim must all select the same complete source face.
            // Ignoring tiny multipart/detail quads prevents a material switch exactly at the seam.
            return largest(candidates);
        }
        if (candidates.size() == 1) return candidates.get(0);
        targetAspect = Math.max(0.04f, targetAspect);
        targetArea = Math.max(0.002f, Math.min(1.0f, targetArea));
        Template best = candidates.get(0);
        double bestScore = Double.POSITIVE_INFINITY;
        for (Template candidate : candidates) {
            double aspectScore = Math.abs(Math.log(Math.max(0.04f, candidate.aspect) / targetAspect));
            double areaScore = Math.abs(Math.log(Math.max(0.002f, candidate.area) / targetArea));
            double score;
            if (hint == SourceProfile.MATERIAL_RAIL || hint == SourceProfile.MATERIAL_POST) {
                score = aspectScore + areaScore * 0.55;
            } else {
                score = aspectScore * 0.35 + areaScore * 0.25 - candidate.area * 1.5;
            }
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static Template largest(List<Template> candidates) {
        Template largest = candidates.get(0);
        for (Template candidate : candidates) if (candidate.area > largest.area) largest = candidate;
        return largest;
    }

    private static List<Template> load(MinecraftClient client, BlockState original, Direction requestedFace) {
        BlockState modelState = connectedModelState(original);
        BakedModel model = client.getBlockRenderManager().getModel(modelState);
        List<BakedQuad> quads = new ArrayList<>();
        quads.addAll(model.getQuads(modelState, requestedFace, Random.create(0L)));
        quads.addAll(model.getQuads(modelState, null, Random.create(0L)));

        Map<String, Template> unique = new LinkedHashMap<>();
        for (BakedQuad quad : quads) {
            Template template = template(quad, requestedFace, false);
            if (template != null) unique.putIfAbsent(templateKey(quad), template);
        }
        if (unique.isEmpty()) {
            // Some multipart models expose the matching side with opposite winding/cull direction.
            for (Direction face : Direction.values()) {
                for (BakedQuad quad : model.getQuads(modelState, face, Random.create(0L))) {
                    Template template = template(quad, requestedFace, true);
                    if (template != null) unique.putIfAbsent(templateKey(quad), template);
                }
            }
        }
        if (unique.isEmpty()) {
            Sprite sprite = model.getParticleSprite();
            return List.of(identity(sprite, -1));
        }
        return List.copyOf(unique.values());
    }

    private static BlockState connectedModelState(BlockState state) {
        Block block = state.getBlock();
        try {
            if (block instanceof FenceBlock || block instanceof PaneBlock) {
                if (state.contains(Properties.NORTH)) state = state.with(Properties.NORTH, true);
                if (state.contains(Properties.EAST)) state = state.with(Properties.EAST, true);
                if (state.contains(Properties.SOUTH)) state = state.with(Properties.SOUTH, true);
                if (state.contains(Properties.WEST)) state = state.with(Properties.WEST, true);
            } else if (block instanceof WallBlock) {
                if (state.contains(Properties.UP)) state = state.with(Properties.UP, true);
                if (state.contains(Properties.NORTH_WALL_SHAPE)) state = state.with(Properties.NORTH_WALL_SHAPE, WallShape.LOW);
                if (state.contains(Properties.EAST_WALL_SHAPE)) state = state.with(Properties.EAST_WALL_SHAPE, WallShape.LOW);
                if (state.contains(Properties.SOUTH_WALL_SHAPE)) state = state.with(Properties.SOUTH_WALL_SHAPE, WallShape.LOW);
                if (state.contains(Properties.WEST_WALL_SHAPE)) state = state.with(Properties.WEST_WALL_SHAPE, WallShape.LOW);
            }
        } catch (IllegalArgumentException ignored) {
            return state;
        }
        return state;
    }

    private static Template template(BakedQuad quad, Direction requestedFace, boolean acceptOpposite) {
        int[] data = quad.getVertexData();
        if (data.length < 24 || data.length % 4 != 0) return null;
        int stride = data.length / 4;
        if (stride < 6) return null;

        float[] x = new float[4], y = new float[4], z = new float[4];
        float[] u = new float[4], v = new float[4];
        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            x[i] = Float.intBitsToFloat(data[base]);
            y[i] = Float.intBitsToFloat(data[base + 1]);
            z[i] = Float.intBitsToFloat(data[base + 2]);
            u[i] = Float.intBitsToFloat(data[base + 4]);
            v[i] = Float.intBitsToFloat(data[base + 5]);
        }

        float[] normal = normal(x, y, z);
        Direction actual = dominant(normal[0], normal[1], normal[2]);
        if (actual != requestedFace && !(acceptOpposite && actual == requestedFace.getOpposite())) return null;

        int axisS, axisT;
        switch (requestedFace.getAxis()) {
            case Y -> { axisS = 0; axisT = 2; } // X/Z
            case X -> { axisS = 2; axisT = 1; } // Z/Y
            default -> { axisS = 0; axisT = 1; } // X/Y
        }
        float[] sRaw = axis(axisS, x, y, z), tRaw = axis(axisT, x, y, z);
        float minS = min(sRaw), maxS = max(sRaw), minT = min(tRaw), maxT = max(tRaw);
        float spanS = maxS - minS, spanT = maxT - minT;
        if (spanS < 1.0E-5f || spanT < 1.0E-5f) return null;
        float[] s = new float[4], t = new float[4];
        for (int i = 0; i < 4; i++) {
            s[i] = (sRaw[i] - minS) / spanS;
            t[i] = (tRaw[i] - minT) / spanT;
        }
        float[] uc = affine(s, t, u), vc = affine(s, t, v);
        int tintIndex = quad.hasColor() ? quad.getColorIndex() : -1;
        if (uc == null || vc == null || !matches(uc, s, t, u) || !matches(vc, s, t, v)) {
            // Never submit atlas coordinates outside the source sprite. A bad UV transform makes
            // the otherwise valid custom mesh sample transparent atlas space and appear invisible.
            return identity(quad.getSprite(), tintIndex);
        }
        float area = Math.max(1.0E-4f, spanS * spanT);
        float aspect = Math.max(spanS, spanT) / Math.max(1.0E-5f, Math.min(spanS, spanT));
        return new Template(quad.getSprite(), tintIndex,
                uc[0], uc[1], uc[2], vc[0], vc[1], vc[2], area, aspect);
    }

    /** Solves q = a*s + b*t + c from any non-collinear three vertices. */
    private static float[] affine(float[] s, float[] t, float[] q) {
        for (int i = 0; i < 4; i++) for (int j = i + 1; j < 4; j++) for (int k = j + 1; k < 4; k++) {
            float det = s[i] * (t[j] - t[k]) + s[j] * (t[k] - t[i]) + s[k] * (t[i] - t[j]);
            if (Math.abs(det) < 1.0E-6f) continue;
            float a = (q[i] * (t[j] - t[k]) + q[j] * (t[k] - t[i]) + q[k] * (t[i] - t[j])) / det;
            float b = (s[i] * (q[j] - q[k]) + s[j] * (q[k] - q[i]) + s[k] * (q[i] - q[j])) / det;
            // Cramer's rule for the constant column. The previous build used the negated
            // determinant here, moving every atlas UV rectangle outside its sprite.
            float c = (q[i] * (s[j] * t[k] - s[k] * t[j])
                    + q[j] * (s[k] * t[i] - s[i] * t[k])
                    + q[k] * (s[i] * t[j] - s[j] * t[i])) / det;
            if (Float.isFinite(a) && Float.isFinite(b) && Float.isFinite(c)) {
                return new float[]{a, b, c};
            }
        }
        return null;
    }

    private static boolean matches(float[] coefficients, float[] s, float[] t, float[] expected) {
        for (int i = 0; i < 4; i++) {
            float actual = coefficients[0] * s[i] + coefficients[1] * t[i] + coefficients[2];
            if (!Float.isFinite(actual) || Math.abs(actual - expected[i]) > 2.0E-4f) return false;
        }
        return true;
    }

    private static Template identity(Sprite sprite, int tint) {
        return new Template(sprite, tint,
                sprite.getMaxU() - sprite.getMinU(), 0.0f, sprite.getMinU(),
                0.0f, sprite.getMaxV() - sprite.getMinV(), sprite.getMinV(),
                1.0f, 1.0f);
    }

    private static String templateKey(BakedQuad quad) {
        int[] data = quad.getVertexData();
        StringBuilder key = new StringBuilder(Integer.toString(System.identityHashCode(quad.getSprite())));
        int stride = data.length / 4;
        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            key.append(':').append(data[base]).append(',').append(data[base + 1]).append(',')
                    .append(data[base + 2]).append(',').append(data[base + 4]).append(',').append(data[base + 5]);
        }
        return key.toString();
    }

    private static float[] axis(int axis, float[] x, float[] y, float[] z) {
        return switch (axis) { case 0 -> x; case 1 -> y; default -> z; };
    }
    private static float min(float[] a) { float v = Float.POSITIVE_INFINITY; for (float x : a) v = Math.min(v, x); return v; }
    private static float max(float[] a) { float v = Float.NEGATIVE_INFINITY; for (float x : a) v = Math.max(v, x); return v; }

    private static float[] normal(float[] x, float[] y, float[] z) {
        float ax = x[1] - x[0], ay = y[1] - y[0], az = z[1] - z[0];
        float bx = x[2] - x[0], by = y[2] - y[0], bz = z[2] - z[0];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float len = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
        return len < 1.0E-8f ? new float[]{0, 1, 0} : new float[]{nx / len, ny / len, nz / len};
    }

    static Direction dominant(float nx, float ny, float nz) {
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        if (ay >= ax && ay >= az) return ny >= 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return nx >= 0 ? Direction.EAST : Direction.WEST;
        return nz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
