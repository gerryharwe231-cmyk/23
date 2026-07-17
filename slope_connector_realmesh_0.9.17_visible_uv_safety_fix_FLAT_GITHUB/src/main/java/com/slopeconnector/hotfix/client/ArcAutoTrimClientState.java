package com.slopeconnector.hotfix.client;

import net.minecraft.client.MinecraftClient;

public final class ArcAutoTrimClientState {
    private static boolean enabled=true;
    private ArcAutoTrimClientState(){}
    public static boolean enabled(){return enabled;}
    public static boolean toggle(){
        enabled=!enabled;
        MinecraftClient client=MinecraftClient.getInstance();
        if(client.player!=null&&client.player.networkHandler!=null)client.player.networkHandler.sendChatCommand("slopeconnector arctrim "+(enabled?"on":"off"));
        return enabled;
    }
    public static String label(){return "自动弧边裁切："+(enabled?"开":"关");}
}
