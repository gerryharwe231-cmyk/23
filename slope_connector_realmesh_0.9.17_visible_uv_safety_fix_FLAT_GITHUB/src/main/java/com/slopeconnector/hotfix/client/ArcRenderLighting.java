package com.slopeconnector.hotfix.client;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;

/** One cached directional light lookup per block-entity face, not per generated triangle. */
final class ArcRenderLighting {
    private ArcRenderLighting() {}

    static int directional(BlockRenderView world, BlockState state, BlockPos pos,
                           Direction face, int fallback) {
        BlockPos outside = pos.add(face.getOffsetX(), face.getOffsetY(), face.getOffsetZ());
        return maxPacked(fallback, WorldRenderer.getLightmapCoordinates(world, state, outside));
    }

    private static int maxPacked(int a, int b) {
        int low = Math.max(a & 0xFFFF, b & 0xFFFF);
        int high = Math.max((a >>> 16) & 0xFFFF, (b >>> 16) & 0xFFFF);
        return low | (high << 16);
    }
}
