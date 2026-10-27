# The Xposed API is compileOnly: no keep rules are required for it.
# Legacy Xposed entry points must survive shrinking.
-keep class com.immoral.location.hook.XposedEntry { *; }
-keep class com.immoral.location.hook.** { *; }
