package com.slopeconnector.hotfix.client;

import com.slopeconnector.hotfix.ArcTrimBlockEntity;
import com.slopeconnector.hotfix.SourceProfile;
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

/** Exact CSG faces and baked materials are compiled once; cut faces are recessed to stop flicker. */
public final class ArcTrimRenderer implements BlockEntityRenderer<ArcTrimBlockEntity> {
    private static final float CUT_RECESS = 0.0015f;
    private final Map<ArcTrimBlockEntity, CompiledMesh> compiled = new WeakHashMap<>();

    public ArcTrimRenderer(BlockEntityRendererFactory.Context context) {}

    @Override
    public void render(ArcTrimBlockEntity entity, float tickDelta, MatrixStack matrices,
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
            int color = face.material.color();
            int red = (color >> 16) & 255;
            int green = (color >> 8) & 255;
            int blue = color & 255;

            // Block render layers are QUADS. Emit every CSG triangle as a degenerate quad;
            // sending raw groups of three vertices makes the buffer join unrelated triangles,
            // which was the direct cause of disappearing faces and flickering spikes.
            emit(consumer, positionMatrix, normalMatrix, face, 0,
                    face.nx, face.ny, face.nz, packedLight, overlay, red, green, blue);
            emit(consumer, positionMatrix, normalMatrix, face, 1,
                    face.nx, face.ny, face.nz, packedLight, overlay, red, green, blue);
            emit(consumer, positionMatrix, normalMatrix, face, 2,
                    face.nx, face.ny, face.nz, packedLight, overlay, red, green, blue);
            emit(consumer, positionMatrix, normalMatrix, face, 2,
                    face.nx, face.ny, face.nz, packedLight, overlay, red, green, blue);

            // Keep the complementary cut visible after the independently breakable arc block is
            // removed. The duplicated final vertex keeps the reverse side in the same QUADS mode.
            emit(consumer, positionMatrix, normalMatrix, face, 2,
                    -face.nx, -face.ny, -face.nz, packedLight, overlay, red, green, blue);
            emit(consumer, positionMatrix, normalMatrix, face, 1,
                    -face.nx, -face.ny, -face.nz, packedLight, overlay, red, green, blue);
            emit(consumer, positionMatrix, normalMatrix, face, 0,
                    -face.nx, -face.ny, -face.nz, packedLight, overlay, red, green, blue);
            emit(consumer, positionMatrix, normalMatrix, face, 0,
                    -face.nx, -face.ny, -face.nz, packedLight, overlay, red, green, blue);
        }
    }

    private static CompiledMesh compile(ArcTrimBlockEntity entity) {
        if (entity.getWorld() == null) return new CompiledMesh(entity.getRenderRevision(), List.of());
        BlockState state = entity.getSourceState();
        BlockPos position = entity.getPos();
        List<Face> faces = new ArrayList<>();
        for (ArcTrimBlockEntity.Triangle triangle : entity.getTriangles()) {
            float[] xyz = triangle.xyz().clone();
            float[] normal = normal(xyz);
            if (normal == null) continue;
            if (triangle.cutFace()) {
                float rx = -normal[0] * CUT_RECESS;
                float ry = -normal[1] * CUT_RECESS;
                float rz = -normal[2] * CUT_RECESS;
                for (int i = 0; i < 3; i++) {
                    xyz[i * 3] += rx; xyz[i * 3 + 1] += ry; xyz[i * 3 + 2] += rz;
                }
            }
            Direction direction = ArcMaterialHelper.dominant(normal[0], normal[1], normal[2]);
            float edgeA = distance(xyz, 0, 1), edgeB = distance(xyz, 0, 2);
            float aspect = Math.max(edgeA, edgeB) / Math.max(1.0E-4f, Math.min(edgeA, edgeB));
            float area = Math.max(1.0E-4f, triangleArea(xyz));
            ArcMaterialHelper.FaceMaterial material = ArcMaterialHelper.material(
                    state, direction, entity.getWorld(), position,
                    SourceProfile.MATERIAL_FULL, aspect, area);
            faces.add(new Face(xyz, normal[0], normal[1], normal[2], direction, material));
        }
        return new CompiledMesh(entity.getRenderRevision(), List.copyOf(faces));
    }

    private static void emit(VertexConsumer consumer, Matrix4f positionMatrix, Matrix3f normalMatrix,
                             Face face, int index, float nx, float ny, float nz,
                             int light, int overlay, int red, int green, int blue) {
        int p = index * 3;
        float x = face.xyz[p], y = face.xyz[p + 1], z = face.xyz[p + 2];
        float s, t;
        switch (face.direction.getAxis()) {
            case Y -> { s = x; t = z; }
            case X -> { s = z; t = y; }
            default -> { s = x; t = y; }
        }
        s = clamp01(s); t = clamp01(t);
        consumer.vertex(positionMatrix, x, y, z)
                .color(red, green, blue, 255)
                .texture(face.material.u(s, t), face.material.v(s, t))
                .overlay(overlay).light(light)
                .normal(normalMatrix, nx, ny, nz).next();
    }

    private static float[] normal(float[] xyz) {
        float ax = xyz[3] - xyz[0], ay = xyz[4] - xyz[1], az = xyz[5] - xyz[2];
        float bx = xyz[6] - xyz[0], by = xyz[7] - xyz[1], bz = xyz[8] - xyz[2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float length = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0E-8f) return null;
        return new float[]{nx / length, ny / length, nz / length};
    }
    private static float distance(float[] xyz, int a, int b) {
        float x = xyz[a * 3] - xyz[b * 3], y = xyz[a * 3 + 1] - xyz[b * 3 + 1], z = xyz[a * 3 + 2] - xyz[b * 3 + 2];
        return (float)Math.sqrt(x * x + y * y + z * z);
    }
    private static float triangleArea(float[] xyz) {
        float ax = xyz[3] - xyz[0], ay = xyz[4] - xyz[1], az = xyz[5] - xyz[2];
        float bx = xyz[6] - xyz[0], by = xyz[7] - xyz[1], bz = xyz[8] - xyz[2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        return (float)Math.sqrt(nx * nx + ny * ny + nz * nz) * 0.5f;
    }
    private static float clamp01(float value) { return Math.max(0.0f, Math.min(1.0f, value)); }

    @Override
    public boolean rendersOutsideBoundingBox(ArcTrimBlockEntity entity) { return false; }

    private record CompiledMesh(int revision, List<Face> faces) {}
    private record Face(float[] xyz, float nx, float ny, float nz,
                        Direction direction, ArcMaterialHelper.FaceMaterial material) {}
}
