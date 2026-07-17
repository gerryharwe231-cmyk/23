package com.slopeconnector.hotfix.mixin;

import com.slopeconnector.ArcSlopeWandItem;
import com.slopeconnector.SlopeConnectorMod;
import com.slopeconnector.hotfix.ArcRibbonGenerator;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Constructor;

@Mixin(value=ArcSlopeWandItem.class,remap=false)
public abstract class ArcSlopeWandMixin {
    @Inject(method="generate",at=@At("HEAD"),cancellable=true,remap=false)
    private static void slopeconnectorArcRibbon(World world, BlockPos start, BlockPos control, BlockPos end,
                                                BlockState source, SlopeConnectorMod.PlayerSettings settings,
                                                CallbackInfoReturnable<Object> cir){
        ArcRibbonGenerator.Result result=ArcRibbonGenerator.generate(world,start,control,end,source,settings);
        cir.setReturnValue(buildOriginalResult(result));
    }
    private static Object buildOriginalResult(ArcRibbonGenerator.Result result){
        try{
            Class<?> cls=Class.forName("com.slopeconnector.ArcSlopeWandItem$BuildResult");
            Constructor<?> ctor=cls.getDeclaredConstructor(int.class,int.class,int.class,int.class,String.class);
            ctor.setAccessible(true);return ctor.newInstance(result.placed(),result.materialPicked(),result.fallbackUsed(),result.skipped(),result.error());
        }catch(ReflectiveOperationException error){throw new RuntimeException("Cannot construct original ArcSlopeWandItem.BuildResult",error);}
    }
}
