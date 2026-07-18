package com.slopeconnector.hotfix.mixin;

import com.slopeconnector.ArcSlopeWandItem;
import com.slopeconnector.SlopeConnectorMod;
import com.slopeconnector.hotfix.ArcRibbonGenerator;
import com.slopeconnector.hotfix.SourceProfile;
import net.minecraft.block.BlockState;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Constructor;
import java.util.Locale;

@Mixin(value=ArcSlopeWandItem.class,remap=false)
public abstract class ArcSlopeWandMixin {
    @Inject(method="generate",at=@At("HEAD"),cancellable=true,remap=false)
    private static void slopeconnectorArcRibbon(World world, BlockPos start, BlockPos control, BlockPos end,
                                                BlockState source, SlopeConnectorMod.PlayerSettings settings,
                                                CallbackInfoReturnable<Object> cir){
        ArcRibbonGenerator.Result result=ArcRibbonGenerator.generate(world,start,control,end,source,settings);
        if((result.error()==null||result.error().isEmpty())&&result.placed()>0){
            connectRealEndpointStates(world,start,control,end,settings);
        }
        cir.setReturnValue(buildOriginalResult(result));
    }

    /**
     * The arc holder is a custom block, so vanilla/modded fences do not automatically see it as a
     * connectable neighbour. Set the two real endpoint blocks to their correct one-sided state after
     * generation. They remain normal independent blocks and can still be broken separately.
     */
    private static void connectRealEndpointStates(World world,BlockPos start,BlockPos control,BlockPos end,
                                                  SlopeConnectorMod.PlayerSettings settings){
        Vec3d startDirection;
        Vec3d endDirection;
        boolean threePoint=settings.arcPointMode==SlopeConnectorMod.ArcPointMode.THREE&&control!=null;
        if(threePoint){
            startDirection=center(control).subtract(center(start));
            endDirection=center(control).subtract(center(end));
        }else{
            Direction face=settings.face==null?Direction.UP:settings.face;
            Vec3d normal=new Vec3d(face.getOffsetX(),face.getOffsetY(),face.getOffsetZ());
            Vec3d raw=center(end).subtract(center(start));
            double delta=raw.dotProduct(normal);
            Vec3d planar=raw.subtract(normal.multiply(delta));
            if(planar.lengthSquared()<1.0E-8)return;
            Vec3d along=planar.normalize();
            if(Math.abs(delta)<0.05){
                startDirection=along;
                endDirection=along.multiply(-1.0);
            }else if(delta>0.0){
                startDirection=along;
                endDirection=normal.multiply(-1.0);
            }else{
                startDirection=normal.multiply(-1.0);
                endDirection=along.multiply(-1.0);
            }
        }
        connectEndpoint(world,start,dominantHorizontal(startDirection));
        connectEndpoint(world,end,dominantHorizontal(endDirection));
    }

    private static void connectEndpoint(World world,BlockPos pos,Direction direction){
        if(direction==null)return;
        BlockState oldState=world.getBlockState(pos);
        if(!hasHorizontalConnectionModel(oldState))return;
        BlockState newState=SourceProfile.endpointConnectedState(oldState,direction);
        if(!newState.equals(oldState))world.setBlockState(pos,newState,3);
    }

    private static boolean hasHorizontalConnectionModel(BlockState state){
        if(state.getBlock() instanceof FenceBlock||state.getBlock() instanceof PaneBlock
                ||state.getBlock() instanceof WallBlock)return true;
        for(Property<?> property:state.getProperties()){
            String name=property.getName().toLowerCase(Locale.ROOT);
            if(name.equals("north")||name.equals("south")||name.equals("east")||name.equals("west")
                    ||name.equals("north_wall_shape")||name.equals("south_wall_shape")
                    ||name.equals("east_wall_shape")||name.equals("west_wall_shape"))return true;
        }
        return false;
    }

    private static Direction dominantHorizontal(Vec3d vector){
        if(vector==null)return null;
        double ax=Math.abs(vector.x),az=Math.abs(vector.z);
        if(Math.max(ax,az)<1.0E-5)return null;
        if(ax>=az)return vector.x>=0.0?Direction.EAST:Direction.WEST;
        return vector.z>=0.0?Direction.SOUTH:Direction.NORTH;
    }

    private static Vec3d center(BlockPos pos){
        return new Vec3d(pos.getX()+0.5,pos.getY()+0.5,pos.getZ()+0.5);
    }

    private static Object buildOriginalResult(ArcRibbonGenerator.Result result){
        try{
            Class<?> cls=Class.forName("com.slopeconnector.ArcSlopeWandItem$BuildResult");
            Constructor<?> ctor=cls.getDeclaredConstructor(int.class,int.class,int.class,int.class,String.class);
            ctor.setAccessible(true);return ctor.newInstance(result.placed(),result.materialPicked(),result.fallbackUsed(),result.skipped(),result.error());
        }catch(ReflectiveOperationException error){throw new RuntimeException("Cannot construct original ArcSlopeWandItem.BuildResult",error);}
    }
}
