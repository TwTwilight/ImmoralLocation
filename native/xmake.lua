-- ImmoralLocation native layer (xmake)
--
-- Build (one ABI at a time; our device is arm64-v8a):
--   xmake f -p android -a arm64-v8a --ndk=<NDK> --ndk_sdkver=21 -m release
--   xmake
--
-- ShadowHook (https://github.com/bytedance/android-inline-hook) comes from the xmake package
-- repository — the same 2.0.1 tag whose sources used to be vendored under third_party/. Installing
-- the package once (xmake does it on the first build, cached in ~/.xmake) means no third-party C
-- sources in this repository and no per-target arch/ABI plumbing: the package picks arch/arm64 or
-- arch/arm from the configured ABI itself.
--
-- packages/ is a project-local package repo holding exactly one package: `shadowhook-immoral`, the
-- official recipe plus a one-line change that lets sh_linker_init() fail on ROMs that strip the
-- linker symbols ShadowHook looks up by name (upstream treats it as fatal). See
-- packages/s/shadowhook-immoral/xmake.lua.
--
-- It is still linked **statically** into libimmoralhook.so (the package installs libshadowhook.a).
-- Keeping it inside a single shared object matters: the module is loaded from inside system_server
-- by LSPosed, where a second .so would have to be resolvable by the loader from the module APK —
-- and that lookup namespace does not include our directory.

set_project("immoralhook")
set_version("1.0.0")
set_languages("c11", "c++23")

add_rules("mode.debug", "mode.release")

-- Pin the version: ShadowHook's ABI is the thing we hook with, so it must not float.
-- (Offline builds: run `xmake require shadowhook-immoral 2.0.1` once while online, then it is cached.)
add_repositories("immoral-packages .")
add_requires("shadowhook-immoral 2.0.1")

if is_mode("release") then
    set_optimize("fastest")
    set_symbols("hidden")
    set_strip("all")
end

target("immoralhook")
    set_kind("shared")
    set_basename("immoralhook")

    -- our own C++ sources
    add_files("src/*.cpp")
    add_includedirs("src")

    -- ShadowHook: headers + libshadowhook.a, pulled in from the package
    add_packages("shadowhook-immoral")

    add_links("log", "android")
    add_cxflags("-fno-exceptions", "-fno-rtti", "-Wno-unused-parameter")
    add_cxxflags("-std=c++23")
    add_cflags("-std=c11")
    -- 16 KiB page alignment required by Android 15+.
    add_shflags("-Wl,-z,max-page-size=16384")
    set_targetdir("out/$(arch)")
