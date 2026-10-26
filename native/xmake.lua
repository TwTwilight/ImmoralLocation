-- ImmoralLocation native layer (xmake)
--
-- Build (one ABI at a time; our device is arm64-v8a):
--   xmake f -p android -a arm64-v8a --ndk=<NDK> --ndk_sdkver=21 -m release
--   xmake
--
-- ShadowHook (https://github.com/bytedance/android-inline-hook) is vendored under
-- third_party/android-inline-hook and compiled **into** libimmoralhook.so. Keeping it inside a
-- single shared object matters: the module is loaded from inside system_server by LSPosed, where
-- a second .so would have to be resolvable by the loader from the module APK — and that lookup
-- namespace does not include our directory.

set_project("immoralhook")
set_version("1.0.0")
set_languages("c11", "c++23")

add_rules("mode.debug", "mode.release")

if is_mode("release") then
    set_optimize("fastest")
    set_symbols("hidden")
    set_strip("all")
end

local shadowhook = "third_party/android-inline-hook/shadowhook/src/main/cpp"

target("immoralhook")
    set_kind("shared")
    set_basename("immoralhook")

    -- our own C++ sources
    add_files("src/*.cpp")

    -- ShadowHook, statically compiled in (arch chosen by ABI)
    add_files(shadowhook .. "/*.c")
    add_files(shadowhook .. "/common/*.c")
    add_files(shadowhook .. "/third_party/xdl/*.c")
    if is_arch("arm64-v8a", "arm64") then
        add_files(shadowhook .. "/arch/arm64/*.c")
        add_files(shadowhook .. "/arch/arm64/*.S")
        add_includedirs(shadowhook .. "/arch/arm64")
    elseif is_arch("armeabi-v7a", "armv7") then
        add_files(shadowhook .. "/arch/arm/*.c")
        add_files(shadowhook .. "/arch/arm/*.S")
    end

    -- sh_jni.c only ships ShadowHook's own Java bindings; we call the C API directly and must
    -- not end up with two JNI_OnLoad definitions in one shared object.
    remove_files(shadowhook .. "/sh_jni.c")

    add_includedirs("src")
    add_includedirs(shadowhook .. "/include")
    add_includedirs(shadowhook)
    add_includedirs(shadowhook .. "/common")
    add_includedirs(shadowhook .. "/third_party/xdl")
    add_includedirs(shadowhook .. "/third_party/bsd")
    add_includedirs(shadowhook .. "/third_party/lss")

    add_links("log", "android")
    add_cxflags("-fno-exceptions", "-fno-rtti", "-Wno-unused-parameter")
    add_cxxflags("-std=c++23")
    add_cflags("-std=c11")
    -- 16 KiB page alignment required by Android 15+.
    add_shflags("-Wl,-z,max-page-size=16384")
    set_targetdir("out/$(arch)")
