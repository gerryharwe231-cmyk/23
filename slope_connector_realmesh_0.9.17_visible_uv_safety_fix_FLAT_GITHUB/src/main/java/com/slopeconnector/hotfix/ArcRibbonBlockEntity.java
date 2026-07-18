package com.slopeconnector.hotfix;

import com.slopeconnector.MaterialStateCodec;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ArcRibbonBlockEntity extends BlockEntity {
    private BlockState sourceState = Blocks.STONE.getDefaultState();
    private final List<Prism> prisms = new ArrayList<>();
    private final List<SurfaceQuad> surfaces = new ArrayList<>();
    private final List<CollisionBox> collisionBoxes = new ArrayList<>();
    private byte modelMode = SourceProfile.MODEL_NONE;
    /** Columns are source S, W and N axes in original model/world coordinates. */
    private final float[] modelBasis = new float[]{1,0,0, 0,0,1, 0,1,0};
    private transient VoxelShape cachedShape;
    private transient int renderRevision;

    public ArcRibbonBlockEntity(BlockPos pos, BlockState state) {
        super(ArcHotfixMod.ARC_RIBBON_ENTITY, pos, state);
    }

    public BlockState getSourceState() { return sourceState; }
    public List<Prism> getPrisms() { return Collections.unmodifiableList(prisms); }
    public List<SurfaceQuad> getSurfaces() { return Collections.unmodifiableList(surfaces); }
    public byte getModelMode() { return modelMode; }
    public float[] getModelBasis() { return modelBasis.clone(); }
    public int getRenderRevision() { return renderRevision; }

    public void setData(BlockState sourceState, List<Prism> newPrisms, List<SurfaceQuad> newSurfaces) {
        setData(sourceState, newPrisms, newSurfaces, SourceProfile.MODEL_NONE,
                new Vec3d(1,0,0), new Vec3d(0,0,1), new Vec3d(0,1,0));
    }

    public void setData(BlockState sourceState, List<Prism> newPrisms, List<SurfaceQuad> newSurfaces,
                        byte modelMode, Vec3d basisS, Vec3d basisW, Vec3d basisN) {
        this.sourceState = sourceState == null ? Blocks.STONE.getDefaultState() : sourceState;
        this.modelMode = modelMode;
        writeBasis(basisS, basisW, basisN);
        prisms.clear();
        prisms.addAll(newPrisms);
        surfaces.clear();
        surfaces.addAll(newSurfaces);
        collisionBoxes.clear();
        for (VoxelShapeUtil.BoxSpec box : VoxelShapeUtil.voxelizePrisms(prisms, 8)) {
            collisionBoxes.add(new CollisionBox(box.minX(), box.minY(), box.minZ(),
                    box.maxX(), box.maxY(), box.maxZ()));
        }
        cachedShape = null;
        renderRevision++;
        markDirty();
    }

    public VoxelShape getCachedShape() {
        if (cachedShape != null) return cachedShape;
        VoxelShape shape = VoxelShapes.empty();
        for (CollisionBox box : collisionBoxes) {
            shape = VoxelShapes.union(shape, VoxelShapes.cuboid(
                    box.minX, box.minY, box.minZ,
                    box.maxX, box.maxY, box.maxZ));
        }
        cachedShape = shape.isEmpty() ? VoxelShapes.empty() : shape.simplify();
        return cachedShape;
    }

    private void writeBasis(Vec3d s, Vec3d w, Vec3d n) {
        Vec3d bs = safeNormal(s, new Vec3d(1,0,0));
        Vec3d bw = safeNormal(w, new Vec3d(0,0,1));
        Vec3d bn = safeNormal(n, new Vec3d(0,1,0));
        modelBasis[0]=(float)bs.x; modelBasis[1]=(float)bs.y; modelBasis[2]=(float)bs.z;
        modelBasis[3]=(float)bw.x; modelBasis[4]=(float)bw.y; modelBasis[5]=(float)bw.z;
        modelBasis[6]=(float)bn.x; modelBasis[7]=(float)bn.y; modelBasis[8]=(float)bn.z;
    }

    private static Vec3d safeNormal(Vec3d value, Vec3d fallback) {
        return value == null || value.lengthSquared() < 1.0E-9 ? fallback : value.normalize();
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.put("SourceState", MaterialStateCodec.write(sourceState));
        nbt.putByte("ModelMode", modelMode);
        for (int i=0;i<modelBasis.length;i++) nbt.putFloat("Basis"+i, modelBasis[i]);

        NbtList list = new NbtList();
        for (Prism p : prisms) {
            NbtCompound item = new NbtCompound();
            for (int i = 0; i < 24; i++) item.putFloat("v" + i, p.xyz[i]);
            item.putFloat("u0", p.u0); item.putFloat("u1", p.u1);
            item.putFloat("w0", p.w0); item.putFloat("w1", p.w1);
            item.putFloat("n0", p.n0); item.putFloat("n1", p.n1);
            item.putByte("FaceMask", p.faceMask);
            item.putByte("MaterialHint", p.materialHint);
            item.putBoolean("Collidable", p.collidable);
            list.add(item);
        }
        nbt.put("Prisms", list);

        NbtList surfaceList = new NbtList();
        for (SurfaceQuad surface : surfaces) {
            NbtCompound item = new NbtCompound();
            for (int i = 0; i < 12; i++) item.putFloat("v" + i, surface.xyz[i]);
            for (int i = 0; i < 8; i++) item.putFloat("t" + i, surface.uv[i]);
            item.putByte("Face", surface.face);
            item.putByte("MaterialHint", surface.materialHint);
            surfaceList.add(item);
        }
        nbt.put("Surfaces", surfaceList);

        NbtList boxes = new NbtList();
        for (CollisionBox box : collisionBoxes) {
            NbtCompound item = new NbtCompound();
            item.putFloat("x0", box.minX); item.putFloat("y0", box.minY); item.putFloat("z0", box.minZ);
            item.putFloat("x1", box.maxX); item.putFloat("y1", box.maxY); item.putFloat("z1", box.maxZ);
            boxes.add(item);
        }
        nbt.put("CollisionBoxes", boxes);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        sourceState = MaterialStateCodec.read(nbt.getCompound("SourceState"));
        modelMode = nbt.contains("ModelMode", NbtElement.BYTE_TYPE)
                ? nbt.getByte("ModelMode") : SourceProfile.MODEL_NONE;
        if (nbt.contains("Basis0", NbtElement.FLOAT_TYPE)) {
            for (int i=0;i<modelBasis.length;i++) modelBasis[i]=nbt.getFloat("Basis"+i);
        } else {
            float[] defaults={1,0,0, 0,0,1, 0,1,0};
            System.arraycopy(defaults,0,modelBasis,0,defaults.length);
        }

        prisms.clear();
        NbtList list = nbt.getList("Prisms", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound item = list.getCompound(i);
            float[] xyz = new float[24];
            for (int j = 0; j < 24; j++) xyz[j] = item.getFloat("v" + j);
            byte faceMask;
            if (item.contains("FaceMask", NbtElement.BYTE_TYPE)) {
                faceMask = item.getByte("FaceMask");
            } else {
                faceMask = 0x0F;
                if (item.getBoolean("CapStart")) faceMask |= 1 << 4;
                if (item.getBoolean("CapEnd")) faceMask |= 1 << 5;
            }
            byte hint = item.contains("MaterialHint", NbtElement.BYTE_TYPE)
                    ? item.getByte("MaterialHint") : SourceProfile.MATERIAL_GENERAL;
            boolean collidable = !item.contains("Collidable", NbtElement.BYTE_TYPE)
                    || item.getBoolean("Collidable");
            prisms.add(new Prism(xyz,
                    item.getFloat("u0"), item.getFloat("u1"),
                    item.getFloat("w0"), item.getFloat("w1"),
                    item.getFloat("n0"), item.getFloat("n1"),
                    faceMask, hint, collidable));
        }

        surfaces.clear();
        NbtList surfaceList = nbt.getList("Surfaces", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < surfaceList.size(); i++) {
            NbtCompound item = surfaceList.getCompound(i);
            float[] xyz = new float[12];
            float[] uv = new float[8];
            for (int j = 0; j < 12; j++) xyz[j] = item.getFloat("v" + j);
            for (int j = 0; j < 8; j++) uv[j] = item.getFloat("t" + j);
            surfaces.add(new SurfaceQuad(xyz, uv, item.getByte("Face"), item.getByte("MaterialHint")));
        }

        collisionBoxes.clear();
        NbtList boxes = nbt.getList("CollisionBoxes", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < boxes.size(); i++) {
            NbtCompound item = boxes.getCompound(i);
            collisionBoxes.add(new CollisionBox(
                    item.getFloat("x0"), item.getFloat("y0"), item.getFloat("z0"),
                    item.getFloat("x1"), item.getFloat("y1"), item.getFloat("z1")));
        }
        if (collisionBoxes.isEmpty() && !prisms.isEmpty()) {
            for (VoxelShapeUtil.BoxSpec box : VoxelShapeUtil.voxelizePrisms(prisms, 6)) {
                collisionBoxes.add(new CollisionBox(box.minX(), box.minY(), box.minZ(),
                        box.maxX(), box.maxY(), box.maxZ()));
            }
        }
        cachedShape = null;
        renderRevision++;
    }

    @Override public NbtCompound toInitialChunkDataNbt() { return createNbt(); }
    @Override public Packet<ClientPlayPacketListener> toUpdatePacket() { return BlockEntityUpdateS2CPacket.create(this); }

    public record Prism(float[] xyz, float u0, float u1, float w0, float w1, float n0, float n1,
                        byte faceMask, byte materialHint, boolean collidable) {
        public Prism {
            if (xyz == null || xyz.length != 24) throw new IllegalArgumentException("Prism requires 8 xyz vertices");
        }
        public boolean draws(int bit) { return (faceMask & (1 << bit)) != 0; }
    }

    public record SurfaceQuad(float[] xyz, float[] uv, byte face, byte materialHint) {
        public SurfaceQuad {
            if (xyz == null || xyz.length != 12) throw new IllegalArgumentException("SurfaceQuad requires 4 xyz vertices");
            if (uv == null || uv.length != 8) throw new IllegalArgumentException("SurfaceQuad requires 4 uv pairs");
        }
    }

    public record CollisionBox(float minX, float minY, float minZ,
                               float maxX, float maxY, float maxZ) {}
}
