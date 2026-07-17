package com.slopeconnector.hotfix.client;

import com.slopeconnector.hotfix.ArcHotfixMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;

public final class ArcHotfixClient implements ClientModInitializer{
    @Override public void onInitializeClient(){
        BlockEntityRendererRegistry.register(ArcHotfixMod.ARC_RIBBON_ENTITY,ArcRibbonRenderer::new);
        BlockEntityRendererRegistry.register(ArcHotfixMod.ARC_TRIM_ENTITY,ArcTrimRenderer::new);
    }
}
