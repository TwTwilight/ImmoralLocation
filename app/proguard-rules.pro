# Xposed entry point and every hook class are reachable only by NAME (assets/xposed_init ->
# com.immoral.location.hook.XposedEntry) and through reflection inside the framework, so the whole
# package is kept verbatim. CI check 23-module-integrity asserts this on the built artifact.
-keep class com.immoral.location.hook.** { *; }
# The binder contract runs in two processes (app and system_server) out of the **same** dex, so an
# obfuscation mismatch is impossible — but the AIDL parcelables are also the documented wire format
# and their layout must stay readable in a stack trace / on-device dump. Keep them verbatim.
-keep class com.immoral.location.ipc.** { *; }
-keep class de.robv.android.xposed.** { *; }
-keepclassmembers class * implements android.os.Parcelable { *; }
-dontwarn de.robv.android.xposed.**
-dontwarn org.osmdroid.**
-dontwarn org.slf4j.**
-dontwarn org.apache.**

# osmdroid: was `-keep class org.osmdroid.** { *; }`, which pinned all 330 library classes with
# every member into classes.dex (~1.09 MB of class files) and blocked member shrinking. The app
# instantiates osmdroid directly (MapHost, TileSources: MapView, overlays, Configuration,
# OnlineTileSourceBase, GeoPoint, MapTileIndex), so R8 keeps the reachable closure on its own.
# Only the pieces osmdroid looks up by reflection are pinned:
#   * TileSourceFactory scans its own static fields by name to build the named source table;
#   * MapView is constructed from XML in the layouts (framework inflater, no keep needed) and
#     `Configuration` loads prefs by key, so its public API surface must survive.
-keep class org.osmdroid.tileprovider.tilesource.TileSourceFactory { *; }
-keepclassmembers class org.osmdroid.tileprovider.tilesource.* extends
    org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase {
    <init>(...);
    java.lang.String getTileURLString(long);
}
-keepclassmembers class org.osmdroid.config.Configuration {
    public static org.osmdroid.config.IConfigurationProvider getInstance();
    public void load(...);
}

