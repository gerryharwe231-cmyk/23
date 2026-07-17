package com.slopeconnector.hotfix;

public final class ArcAutoTrimSettings {
    private static volatile boolean enabled = true;
    private ArcAutoTrimSettings() {}
    public static boolean enabled() { return enabled; }
    public static boolean set(boolean value) { enabled=value; return value; }
    public static boolean toggle() { return set(!enabled); }
}
