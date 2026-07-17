package com.slopeconnector.hotfix;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public final class ArcHotfixMod implements ModInitializer {
    public static final String MOD_ID="slopeconnector_arc_hotfix";
    public static final ArcRibbonBlock ARC_RIBBON=new ArcRibbonBlock(FabricBlockSettings.copyOf(Blocks.STONE).nonOpaque().dropsNothing().strength(0.2f));
    public static final ArcTrimBlock ARC_TRIM=new ArcTrimBlock(FabricBlockSettings.copyOf(Blocks.STONE).nonOpaque().dropsNothing().strength(0.2f));
    public static BlockEntityType<ArcRibbonBlockEntity> ARC_RIBBON_ENTITY;
    public static BlockEntityType<ArcTrimBlockEntity> ARC_TRIM_ENTITY;
    public static Identifier id(String path){return new Identifier(MOD_ID,path);}

    @Override public void onInitialize(){
        Registry.register(Registries.BLOCK,id("arc_ribbon"),ARC_RIBBON);
        Registry.register(Registries.BLOCK,id("arc_trim"),ARC_TRIM);
        ARC_RIBBON_ENTITY=Registry.register(Registries.BLOCK_ENTITY_TYPE,id("arc_ribbon_entity"), FabricBlockEntityTypeBuilder.create(ArcRibbonBlockEntity::new,ARC_RIBBON).build(null));
        ARC_TRIM_ENTITY=Registry.register(Registries.BLOCK_ENTITY_TYPE,id("arc_trim_entity"),FabricBlockEntityTypeBuilder.create(ArcTrimBlockEntity::new,ARC_TRIM).build(null));
        CommandRegistrationCallback.EVENT.register((dispatcher,registryAccess,environment)->dispatcher.register(
                CommandManager.literal("slopeconnector").then(CommandManager.literal("arctrim")
                        .then(CommandManager.literal("on").executes(ctx->{ArcAutoTrimSettings.set(true);ctx.getSource().sendFeedback(()->Text.literal("弧边自动裁切：开启"),false);return 1;}))
                        .then(CommandManager.literal("off").executes(ctx->{ArcAutoTrimSettings.set(false);ctx.getSource().sendFeedback(()->Text.literal("弧边自动裁切：关闭"),false);return 1;}))
                        .then(CommandManager.literal("toggle").executes(ctx->{boolean v=ArcAutoTrimSettings.toggle();ctx.getSource().sendFeedback(()->Text.literal("弧边自动裁切："+(v?"开启":"关闭")),false);return 1;}))
                        .then(CommandManager.literal("status").executes(ctx->{ctx.getSource().sendFeedback(()->Text.literal("弧边自动裁切："+(ArcAutoTrimSettings.enabled()?"开启":"关闭")),false);return 1;}))
                )));
    }
}
