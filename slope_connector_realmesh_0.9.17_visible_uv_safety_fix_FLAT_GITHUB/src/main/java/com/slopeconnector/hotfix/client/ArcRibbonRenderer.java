package com.slopeconnector.hotfix.client;

import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import com.slopeconnector.hotfix.SourceProfile;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 0.9.18: full blocks and connected rail/fence/pane models are bent from their real baked quads.
 * Original model vertices and atlas UVs are preserved; only the vertex positions are deformed.
 */
public final class ArcRibbonRenderer implements BlockEntityRenderer<ArcRibbonBlockEntity> {
    private static final Map<BlockState, CachedBakedModel> BAKED_CACHE = new ConcurrentHashMap<>();
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

        BlockState renderState = SourceProfile.canonicalModelState(entity.getSourceState(), entity.getModelMode());
        VertexConsumer consumer = consumers.getBuffer(RenderLayers.getBlockLayer(renderState));
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f positionMatrix = entry.getPositionMatrix();
        Matrix3f normalMatrix = entry.getNormalMatrix();
        int[] directionalLights = {-1, -1, -1, -1, -1, -1};
        BlockPos position = entity.getPos();

        for (Face face : mesh.faces) {
            int lightIndex = face.direction.ordinal();
            int packedLight = directionalLights[lightIndex];
            if (packedLight < 0) {
                packedLight = ArcRenderLighting.directional(entity.getWorld(), renderState,
                        position, face.direction, light);
                directionalLights[lightIndex] = packedLight;
            }
            int color = face.color;
            int red = (color >> 16) & 255;
            int green = (color >> 8) & 255;
            int blue = color & 255;
            for (int i = 0; i < 4; i++) {
                int p = i * 3, t = i * 2;
                vertex(consumer, positionMatrix, normalMatrix,
                        face.xyz[p], face.xyz[p + 1], face.xyz[p + 2],
                        face.uv[t], face.uv[t + 1],
                        face.nx, face.ny, face.nz,
                        packedLight, overlay, red, green, blue);
            }
        }
    }

    private static CompiledMesh compile(ArcRibbonBlockEntity entity) {
        if (entity.getWorld() == null) return new CompiledMesh(entity.getRenderRevision(), List.of());
        List<Face> faces = new ArrayList<>();
        BlockState source = entity.getSourceState();
        BlockState modelState = SourceProfile.canonicalModelState(source, entity.getModelMode());
        float[] basis = entity.getModelBasis();

        for (ArcRibbonBlockEntity.Prism prism : entity.getPrisms()) {
            if (prism.materialHint() == SourceProfile.MATERIAL_BAKED_MODEL) {
                compileBakedPrism(entity, modelState, prism, basis, faces);
            } else if (prism.materialHint() != SourceProfile.MATERIAL_COLLISION) {
                compileLegacyPrism(entity, source, prism, faces);
            }
        }
        for (ArcRibbonBlockEntity.SurfaceQuad surface : entity.getSurfaces()) {
            compileSurface(entity, source, surface, faces);
        }
        return new CompiledMesh(entity.getRenderRevision(), List.copyOf(faces));
    }

    private static void compileBakedPrism(ArcRibbonBlockEntity entity, BlockState modelState,
                                          ArcRibbonBlockEntity.Prism prism, float[] basis,
                                          List<Face> faces) {
        for (ModelQuad quad : bakedQuads(modelState)) {
            float pathDot = quad.sourceNormal[0] * basis[0]
                    + quad.sourceNormal[1] * basis[1]
                    + quad.sourceNormal[2] * basis[2];
            if (pathDot < -0.90f && !prism.draws(4)) continue;
            if (pathDot > 0.90f && !prism.draws(5)) continue;

            float[] xyz = new float[12];
            for (int i = 0; i < 4; i++) {
                int p = i * 3;
                float x = quad.xyz[p] - 0.5f;
                float y = quad.xyz[p + 1] - 0.5f;
                float z = quad.xyz[p + 2] - 0.5f;
                float s = 0.5f + x * basis[0] + y * basis[1] + z * basis[2];
                float w = 0.5f + x * basis[3] + y * basis[4] + z * basis[5];
                float n = 0.5f + x * basis[6] + y * basis[7] + z * basis[8];
                float[] target = mapPrism(prism.xyz(), s, w, n);
                xyz[p] = target[0]; xyz[p + 1] = target[1]; xyz[p + 2] = target[2];
            }
            float[] normal = normal(xyz);
            if (normal == null) continue;
            Direction direction = ArcMaterialHelper.dominant(normal[0], normal[1], normal[2]);
            int color = 0xFFFFFF;
            if (quad.tintIndex >= 0) {
                int tint = MinecraftClient.getInstance().getBlockColors().getColor(
                        modelState, entity.getWorld(), entity.getPos(), quad.tintIndex);
                if (tint != -1) color = tint & 0xFFFFFF;
            }
            faces.add(new Face(xyz, quad.uv.clone(), normal[0], normal[1], normal[2], direction, color));
        }
    }

    private static void compileLegacyPrism(ArcRibbonBlockEntity entity, BlockState source,
                                           ArcRibbonBlockEntity.Prism prism, List<Face> faces) {
        float[] v = prism.xyz();
        if (prism.draws(0)) addLegacyFace(entity, source, faces, v, new int[]{0,4,5,1},
                new float[]{prism.u0(),prism.w0(), prism.u1(),prism.w0(), prism.u1(),prism.w1(), prism.u0(),prism.w1()}, prism.materialHint());
        if (prism.draws(1)) addLegacyFace(entity, source, faces, v, new int[]{3,2,6,7},
                new float[]{prism.u0(),prism.w0(), prism.u0(),prism.w1(), prism.u1(),prism.w1(), prism.u1(),prism.w0()}, prism.materialHint());
        if (prism.draws(2)) addLegacyFace(entity, source, faces, v, new int[]{0,3,7,4},
                new float[]{prism.u0(),prism.n0(), prism.u0(),prism.n1(), prism.u1(),prism.n1(), prism.u1(),prism.n0()}, prism.materialHint());
        if (prism.draws(3)) addLegacyFace(entity, source, faces, v, new int[]{1,5,6,2},
                new float[]{prism.u0(),prism.n0(), prism.u1(),prism.n0(), prism.u1(),prism.n1(), prism.u0(),prism.n1()}, prism.materialHint());
        if (prism.draws(4)) addLegacyFace(entity, source, faces, v, new int[]{0,1,2,3},
                new float[]{prism.w0(),prism.n0(), prism.w1(),prism.n0(), prism.w1(),prism.n1(), prism.w0(),prism.n1()}, prism.materialHint());
        if (prism.draws(5)) addLegacyFace(entity, source, faces, v, new int[]{4,7,6,5},
                new float[]{prism.w0(),prism.n0(), prism.w0(),prism.n1(), prism.w1(),prism.n1(), prism.w1(),prism.n0()}, prism.materialHint());
    }

    private static void addLegacyFace(ArcRibbonBlockEntity entity, BlockState source, List<Face> faces,
                                      float[] data, int[] ids, float[] normalizedUv, byte hint) {
        float[] xyz = new float[12];
        for (int i = 0; i < 4; i++) {
            int from = ids[i] * 3;
            xyz[i * 3] = data[from]; xyz[i * 3 + 1] = data[from + 1]; xyz[i * 3 + 2] = data[from + 2];
        }
        float[] n = normal(xyz);
        if (n == null) return;
        Direction direction = ArcMaterialHelper.dominant(n[0], n[1], n[2]);
        float edgeA = distance(xyz,0,1), edgeB = distance(xyz,0,3);
        float aspect = Math.max(edgeA, edgeB) / Math.max(1.0E-4f, Math.min(edgeA, edgeB));
        float area = Math.max(1.0E-4f, edgeA * edgeB);
        ArcMaterialHelper.FaceMaterial material = ArcMaterialHelper.material(
                source, direction, entity.getWorld(), entity.getPos(), hint, aspect, area);
        float[] uv = new float[8];
        for (int i=0;i<4;i++) {
            uv[i*2] = material.u(clamp01(normalizedUv[i*2]), clamp01(normalizedUv[i*2+1]));
            uv[i*2+1] = material.v(clamp01(normalizedUv[i*2]), clamp01(normalizedUv[i*2+1]));
        }
        faces.add(new Face(xyz, uv, n[0], n[1], n[2], direction, material.color()));
    }

    private static void compileSurface(ArcRibbonBlockEntity entity, BlockState source,
                                       ArcRibbonBlockEntity.SurfaceQuad surface, List<Face> faces) {
        float[] xyz = surface.xyz().clone();
        float[] n = normal(xyz);
        if (n == null) return;
        int index = Math.floorMod(surface.face(), Direction.values().length);
        Direction direction = Direction.values()[index];
        ArcMaterialHelper.FaceMaterial material = ArcMaterialHelper.material(
                source, direction, entity.getWorld(), entity.getPos(), surface.materialHint(), 1.0f, 1.0f);
        float[] uv = new float[8];
        for (int i=0;i<4;i++) {
            uv[i*2] = material.u(clamp01(surface.uv()[i*2]), clamp01(surface.uv()[i*2+1]));
            uv[i*2+1] = material.v(clamp01(surface.uv()[i*2]), clamp01(surface.uv()[i*2+1]));
        }
        faces.add(new Face(xyz, uv, n[0], n[1], n[2], direction, material.color()));
    }

    private static List<ModelQuad> bakedQuads(BlockState state) {
        MinecraftClient client = MinecraftClient.getInstance();
        BakedModel current = client.getBlockRenderManager().getModel(state);
        CachedBakedModel cached = BAKED_CACHE.get(state);
        if (cached != null && cached.model == current) return cached.quads;

        List<ModelQuad> quads = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            appendModelQuads(quads, current.getQuads(state, direction, Random.create(0L)));
        }
        appendModelQuads(quads, current.getQuads(state, null, Random.create(0L)));
        CachedBakedModel replacement = new CachedBakedModel(current, List.copyOf(quads));
        BAKED_CACHE.put(state, replacement);
        return replacement.quads;
    }

    private static void appendModelQuads(List<ModelQuad> out, List<BakedQuad> quads) {
        for (BakedQuad quad : quads) {
            int[] data = quad.getVertexData();
            if (data.length < 24 || data.length % 4 != 0) continue;
            int stride = data.length / 4;
            if (stride < 6) continue;
            float[] xyz = new float[12], uv = new float[8];
            for (int i=0;i<4;i++) {
                int base=i*stride;
                xyz[i*3]=Float.intBitsToFloat(data[base]);
                xyz[i*3+1]=Float.intBitsToFloat(data[base+1]);
                xyz[i*3+2]=Float.intBitsToFloat(data[base+2]);
                uv[i*2]=Float.intBitsToFloat(data[base+4]);
                uv[i*2+1]=Float.intBitsToFloat(data[base+5]);
            }
            float[] n=normal(xyz);
            if(n==null) continue;
            out.add(new ModelQuad(xyz,uv,n,quad.hasColor()?quad.getColorIndex():-1));
        }
    }

    /** Trilinear map from canonical model cube (S,W,N) into a bent prism. */
    private static float[] mapPrism(float[] v, float s, float w, float n) {
        float[] start = bilerp(v, 0, w, n);
        float[] end = bilerp(v, 4, w, n);
        return new float[]{
                start[0] + (end[0] - start[0]) * s,
                start[1] + (end[1] - start[1]) * s,
                start[2] + (end[2] - start[2]) * s
        };
    }

    private static float[] bilerp(float[] v, int baseVertex, float w, float n) {
        float a=(1-w)*(1-n), b=w*(1-n), c=w*n, d=(1-w)*n;
        int i0=baseVertex*3, i1=(baseVertex+1)*3, i2=(baseVertex+2)*3, i3=(baseVertex+3)*3;
        return new float[]{
                v[i0]*a+v[i1]*b+v[i2]*c+v[i3]*d,
                v[i0+1]*a+v[i1+1]*b+v[i2+1]*c+v[i3+1]*d,
                v[i0+2]*a+v[i1+2]*b+v[i2+2]*c+v[i3+2]*d
        };
    }

    private static float[] normal(float[] xyz) {
        float ax=xyz[3]-xyz[0], ay=xyz[4]-xyz[1], az=xyz[5]-xyz[2];
        float bx=xyz[6]-xyz[0], by=xyz[7]-xyz[1], bz=xyz[8]-xyz[2];
        float nx=ay*bz-az*by, ny=az*bx-ax*bz, nz=ax*by-ay*bx;
        float length=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
        if(length<1.0E-8f)return null;
        return new float[]{nx/length,ny/length,nz/length};
    }

    private static float distance(float[] xyz,int a,int b){
        float x=xyz[a*3]-xyz[b*3],y=xyz[a*3+1]-xyz[b*3+1],z=xyz[a*3+2]-xyz[b*3+2];
        return(float)Math.sqrt(x*x+y*y+z*z);
    }
    private static float clamp01(float value){return Math.max(0.0f,Math.min(1.0f,value));}

    private static void vertex(VertexConsumer consumer, Matrix4f positionMatrix, Matrix3f normalMatrix,
                               float x, float y, float z, float u, float v,
                               float nx, float ny, float nz,
                               int light, int overlay, int red, int green, int blue) {
        consumer.vertex(positionMatrix,x,y,z).color(red,green,blue,255)
                .texture(u,v).overlay(overlay).light(light)
                .normal(normalMatrix,nx,ny,nz).next();
    }

    @Override
    public boolean rendersOutsideBoundingBox(ArcRibbonBlockEntity entity) {
        return !entity.getSurfaces().isEmpty();
    }

    private record CachedBakedModel(BakedModel model,List<ModelQuad> quads){}
    private record ModelQuad(float[] xyz,float[] uv,float[] sourceNormal,int tintIndex){}
    private record CompiledMesh(int revision,List<Face> faces){}
    private record Face(float[] xyz,float[] uv,float nx,float ny,float nz,Direction direction,int color){}
}
