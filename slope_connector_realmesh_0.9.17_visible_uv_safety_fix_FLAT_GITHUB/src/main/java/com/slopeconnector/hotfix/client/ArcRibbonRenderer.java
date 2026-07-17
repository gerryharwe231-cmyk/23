package com.slopeconnector.hotfix.client;

import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Geometry and baked-material selection are compiled once per block entity revision. */
public final class ArcRibbonRenderer implements BlockEntityRenderer<ArcRibbonBlockEntity> {
    private final Map<ArcRibbonBlockEntity, CompiledMesh> compiled = new WeakHashMap<>();

    public ArcRibbonRenderer(BlockEntityRendererFactory.Context context) {}

    @Override
    public void render(ArcRibbonBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider consumers, int light, int overlay) {
        if (entity.getWorld() == null) return;
        CompiledMesh mesh = compiled.get(entity);
        if (mesh == null || mesh.revision != entity.getRenderRevision()) {
            mesh = compile(entity);
            compiled.put(entity, mesh);
        }
        if (mesh.faces.isEmpty()) return;

        BlockState state = entity.getSourceState();
        VertexConsumer consumer = consumers.getBuffer(RenderLayers.getBlockLayer(state));
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f positionMatrix = entry.getPositionMatrix();
        Matrix3f normalMatrix = entry.getNormalMatrix();
        int[] directionalLights = {-1, -1, -1, -1, -1, -1};
        BlockPos position = entity.getPos();

        for (Face face : mesh.faces) {
            int lightIndex = face.direction.ordinal();
            int packedLight = directionalLights[lightIndex];
            if (packedLight < 0) {
                packedLight = ArcRenderLighting.directional(entity.getWorld(), state,
                        position, face.direction, light);
                directionalLights[lightIndex] = packedLight;
            }
            ArcMaterialHelper.FaceMaterial material = face.material;
            int color = material.color();
            int red = (color >> 16) & 255;
            int green = (color >> 8) & 255;
            int blue = color & 255;
            for (int i = 0; i < 4; i++) {
                int p = i * 3, t = i * 2;
                vertex(consumer, positionMatrix, normalMatrix,
                        face.xyz[p], face.xyz[p + 1], face.xyz[p + 2],
                        material.u(face.uv[t], face.uv[t + 1]),
                        material.v(face.uv[t], face.uv[t + 1]),
                        face.nx, face.ny, face.nz,
                        packedLight, overlay, red, green, blue);
            }
        }
    }

    private static CompiledMesh compile(ArcRibbonBlockEntity entity) {
        List<RawFace> rawFaces = new ArrayList<>();
        for (ArcRibbonBlockEntity.Prism prism : entity.getPrisms()) {
            float[] v = prism.xyz();
            if (prism.draws(0)) addPrismFace(rawFaces, v, new int[]{0,4,5,1},
                    new float[]{prism.u0(),prism.w0(), prism.u1(),prism.w0(), prism.u1(),prism.w1(), prism.u0(),prism.w1()}, prism.materialHint());
            if (prism.draws(1)) addPrismFace(rawFaces, v, new int[]{3,2,6,7},
                    new float[]{prism.u0(),prism.w0(), prism.u0(),prism.w1(), prism.u1(),prism.w1(), prism.u1(),prism.w0()}, prism.materialHint());
            if (prism.draws(2)) addPrismFace(rawFaces, v, new int[]{0,3,7,4},
                    new float[]{prism.u0(),prism.n0(), prism.u0(),prism.n1(), prism.u1(),prism.n1(), prism.u1(),prism.n0()}, prism.materialHint());
            if (prism.draws(3)) addPrismFace(rawFaces, v, new int[]{1,5,6,2},
                    new float[]{prism.u0(),prism.n0(), prism.u1(),prism.n0(), prism.u1(),prism.n1(), prism.u0(),prism.n1()}, prism.materialHint());
            if (prism.draws(4)) addPrismFace(rawFaces, v, new int[]{0,1,2,3},
                    new float[]{prism.w0(),prism.n0(), prism.w1(),prism.n0(), prism.w1(),prism.n1(), prism.w0(),prism.n1()}, prism.materialHint());
            if (prism.draws(5)) addPrismFace(rawFaces, v, new int[]{4,7,6,5},
                    new float[]{prism.w0(),prism.n0(), prism.w0(),prism.n1(), prism.w1(),prism.n1(), prism.w1(),prism.n0()}, prism.materialHint());
        }
        for (ArcRibbonBlockEntity.SurfaceQuad surface : entity.getSurfaces()) {
            int index = Math.floorMod(surface.face(), Direction.values().length);
            addFace(rawFaces, surface.xyz(), new int[]{0,1,2,3}, surface.uv().clone(),
                    surface.materialHint(), Direction.values()[index], null);
        }

        if (entity.getWorld() == null) return new CompiledMesh(entity.getRenderRevision(), List.of());
        BlockState state = entity.getSourceState();
        BlockPos pos = entity.getPos();
        List<Face> faces = new ArrayList<>(rawFaces.size());
        for (RawFace raw : rawFaces) {
            ArcMaterialHelper.FaceMaterial material = ArcMaterialHelper.material(
                    state, raw.direction, entity.getWorld(), pos,
                    raw.materialHint, raw.targetAspect, raw.targetArea);
            faces.add(new Face(raw.xyz, raw.uv, raw.nx, raw.ny, raw.nz,
                    raw.direction, material));
        }
        return new CompiledMesh(entity.getRenderRevision(), List.copyOf(faces));
    }

    private static void addPrismFace(List<RawFace> faces, float[] data, int[] ids,
                                     float[] uv, byte materialHint) {
        float[] geometryCenter = centerAll(data);
        addFace(faces, data, ids, uv, materialHint, null, geometryCenter);
    }

    private static void addFace(List<RawFace> faces, float[] data, int[] ids, float[] uv,
                                byte materialHint, Direction fixedDirection,
                                float[] geometryCenter) {
        float[] xyz = new float[12];
        for (int i = 0; i < 4; i++) {
            int source = ids[i] * 3;
            xyz[i * 3] = data[source];
            xyz[i * 3 + 1] = data[source + 1];
            xyz[i * 3 + 2] = data[source + 2];
        }
        float[] normal = normal(xyz);
        if (normal == null) return;
        float[] faceCenter = centerAll(xyz);
        boolean reverse;
        if (fixedDirection != null) {
            reverse = normal[0] * fixedDirection.getOffsetX()
                    + normal[1] * fixedDirection.getOffsetY()
                    + normal[2] * fixedDirection.getOffsetZ() < 0.0f;
        } else {
            reverse = normal[0] * (faceCenter[0] - geometryCenter[0])
                    + normal[1] * (faceCenter[1] - geometryCenter[1])
                    + normal[2] * (faceCenter[2] - geometryCenter[2]) < 0.0f;
        }
        if (reverse) {
            swapVertex(xyz, 1, 3); swapUv(uv, 1, 3);
            normal[0] = -normal[0]; normal[1] = -normal[1]; normal[2] = -normal[2];
        }
        Direction direction = fixedDirection == null
                ? ArcMaterialHelper.dominant(normal[0], normal[1], normal[2]) : fixedDirection;
        float edgeA = distance(xyz, 0, 1), edgeB = distance(xyz, 0, 3);
        float aspect = Math.max(edgeA, edgeB) / Math.max(1.0E-4f, Math.min(edgeA, edgeB));
        float area = Math.max(1.0E-4f, edgeA * edgeB);
        for (int i = 0; i < uv.length; i++) uv[i] = clamp01(uv[i]);
        faces.add(new RawFace(xyz, uv, normal[0], normal[1], normal[2],
                direction, materialHint, aspect, area));
    }

    private static float[] normal(float[] xyz) {
        float ax = xyz[3] - xyz[0], ay = xyz[4] - xyz[1], az = xyz[5] - xyz[2];
        float bx = xyz[6] - xyz[0], by = xyz[7] - xyz[1], bz = xyz[8] - xyz[2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float length = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0E-8f) return null;
        return new float[]{nx / length, ny / length, nz / length};
    }

    private static float[] centerAll(float[] xyz) {
        float x = 0, y = 0, z = 0;
        int count = xyz.length / 3;
        for (int i = 0; i < count; i++) {
            x += xyz[i * 3]; y += xyz[i * 3 + 1]; z += xyz[i * 3 + 2];
        }
        return new float[]{x / count, y / count, z / count};
    }

    private static float distance(float[] xyz, int a, int b) {
        float x = xyz[a * 3] - xyz[b * 3];
        float y = xyz[a * 3 + 1] - xyz[b * 3 + 1];
        float z = xyz[a * 3 + 2] - xyz[b * 3 + 2];
        return (float)Math.sqrt(x * x + y * y + z * z);
    }

    private static void swapVertex(float[] xyz, int a, int b) {
        for (int axis = 0; axis < 3; axis++) {
            float value = xyz[a * 3 + axis]; xyz[a * 3 + axis] = xyz[b * 3 + axis]; xyz[b * 3 + axis] = value;
        }
    }
    private static void swapUv(float[] uv, int a, int b) {
        for (int axis = 0; axis < 2; axis++) {
            float value = uv[a * 2 + axis]; uv[a * 2 + axis] = uv[b * 2 + axis]; uv[b * 2 + axis] = value;
        }
    }
    private static float clamp01(float value) { return Math.max(0.0f, Math.min(1.0f, value)); }

    private static void vertex(VertexConsumer consumer, Matrix4f positionMatrix, Matrix3f normalMatrix,
                               float x, float y, float z, float u, float v,
                               float nx, float ny, float nz,
                               int light, int overlay, int red, int green, int blue) {
        consumer.vertex(positionMatrix, x, y, z)
                .color(red, green, blue, 255)
                .texture(u, v).overlay(overlay).light(light)
                .normal(normalMatrix, nx, ny, nz).next();
    }

    @Override
    public boolean rendersOutsideBoundingBox(ArcRibbonBlockEntity entity) {
        return !entity.getSurfaces().isEmpty();
    }

    private record CompiledMesh(int revision, List<Face> faces) {}
    private record RawFace(float[] xyz, float[] uv,
                           float nx, float ny, float nz,
                           Direction direction, byte materialHint,
                           float targetAspect, float targetArea) {}
    private record Face(float[] xyz, float[] uv,
                        float nx, float ny, float nz,
                        Direction direction,
                        ArcMaterialHelper.FaceMaterial material) {}
}
