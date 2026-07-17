package com.slopeconnector.hotfix;

import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import org.jetbrains.annotations.Nullable;

public final class ArcRibbonBlock extends BlockWithEntity {
    public ArcRibbonBlock(Settings settings){super(settings);}
    @Nullable @Override public BlockEntity createBlockEntity(BlockPos pos,BlockState state){return new ArcRibbonBlockEntity(pos,state);}
    @Override public BlockRenderType getRenderType(BlockState state){return BlockRenderType.INVISIBLE;}
    @Override public VoxelShape getOutlineShape(BlockState state,BlockView world,BlockPos pos,ShapeContext context){
        BlockEntity be=world.getBlockEntity(pos); return be instanceof ArcRibbonBlockEntity e?e.getCachedShape():VoxelShapes.empty();
    }
    @Override public VoxelShape getCollisionShape(BlockState state,BlockView world,BlockPos pos,ShapeContext context){
        BlockEntity be=world.getBlockEntity(pos); return be instanceof ArcRibbonBlockEntity e?e.getCachedShape():VoxelShapes.empty();
    }
}
