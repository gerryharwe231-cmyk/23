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
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ArcTrimBlockEntity extends BlockEntity {
    private BlockState sourceState = Blocks.STONE.getDefaultState();
    private final List<Triangle> triangles = new ArrayList<>();
    private final List<TrimBox> boxes = new ArrayList<>();
    private transient VoxelShape cachedShape;
    private transient int renderRevision;

    public ArcTrimBlockEntity(BlockPos pos, BlockState state) {
        super(ArcHotfixMod.ARC_TRIM_ENTITY, pos, state);
    }

    public BlockState getSourceState() { return sourceState; }
    public List<Triangle> getTriangles() { return Collections.unmodifiableList(triangles); }
    public int getRenderRevision() { return renderRevision; }

    public void setData(BlockState source, List<Triangle> tris, List<TrimBox> collision) {
        sourceState = source == null ? Blocks.STONE.getDefaultState() : source;
        triangles.clear(); triangles.addAll(tris);
        boxes.clear(); boxes.addAll(collision);
        cachedShape = null;
        renderRevision++;
        markDirty();
    }

    public VoxelShape getCachedShape() {
        if (cachedShape != null) return cachedShape;
        VoxelShape shape = VoxelShapes.empty();
        for (TrimBox box : boxes) {
            shape = VoxelShapes.union(shape, VoxelShapes.cuboid(
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
        }
        cachedShape = shape.isEmpty() ? VoxelShapes.empty() : shape.simplify();
        return cachedShape;
    }

    @Override protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.put("SourceState", MaterialStateCodec.write(sourceState));
        NbtList triangleList = new NbtList();
        for (Triangle triangle : triangles) {
            NbtCompound item = new NbtCompound();
            for (int i = 0; i < 9; i++) item.putFloat("v" + i, triangle.xyz[i]);
            item.putBoolean("CutFace", triangle.cutFace);
            triangleList.add(item);
        }
        nbt.put("Triangles", triangleList);

        NbtList boxList = new NbtList();
        for (TrimBox box : boxes) {
            NbtCompound item = new NbtCompound();
            item.putFloat("x0", box.minX); item.putFloat("y0", box.minY); item.putFloat("z0", box.minZ);
            item.putFloat("x1", box.maxX); item.putFloat("y1", box.maxY); item.putFloat("z1", box.maxZ);
            boxList.add(item);
        }
        nbt.put("Boxes", boxList);
    }

    @Override public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        sourceState = MaterialStateCodec.read(nbt.getCompound("SourceState"));
        triangles.clear(); boxes.clear();
        NbtList triangleList = nbt.getList("Triangles", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < triangleList.size(); i++) {
            NbtCompound item = triangleList.getCompound(i);
            float[] xyz = new float[9];
            for (int j = 0; j < 9; j++) xyz[j] = item.getFloat("v" + j);
            triangles.add(new Triangle(xyz, item.getBoolean("CutFace")));
        }
        NbtList boxList = nbt.getList("Boxes", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < boxList.size(); i++) {
            NbtCompound item = boxList.getCompound(i);
            boxes.add(new TrimBox(
                    item.getFloat("x0"), item.getFloat("y0"), item.getFloat("z0"),
                    item.getFloat("x1"), item.getFloat("y1"), item.getFloat("z1")));
        }
        cachedShape = null;
        renderRevision++;
    }

    @Override public NbtCompound toInitialChunkDataNbt() { return createNbt(); }
    @Override public Packet<ClientPlayPacketListener> toUpdatePacket() { return BlockEntityUpdateS2CPacket.create(this); }

    public record Triangle(float[] xyz, boolean cutFace) {
        public Triangle {
            if (xyz == null || xyz.length != 9) throw new IllegalArgumentException("Triangle requires 3 vertices");
        }
    }
    public record TrimBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {}
}
