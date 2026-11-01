-- Project-local xmake package: the xmake-repo "shadowhook" recipe (v2.0.1) plus one local change.
--
-- It is published under its own name (`shadowhook-immoral`) on purpose: a project-local repo entry
-- carrying the *same* name as an official package loses to the official repo, so an override has to
-- be a distinct package. The recipe below is verbatim xmake-io/xmake-repo
-- (packages/s/shadowhook/xmake.lua) apart from the one `io.replace` in on_install.
--
-- Why the change exists: shadowhook_init() -> sh_linker_init() resolves the *private* linker
-- symbols soinfo::call_constructors / soinfo::call_destructors / g_dl_mutex by NAME. HyperOS 15
-- (this module's test ROM) renamed or stripped them, so the lookup fails and upstream's
-- shadowhook_init() aborts with SHADOWHOOK_ERRNO_INIT_LINKER (12) — every later shadowhook_* call
-- then returns 12 and no hook is installed. Measured on the device: the stock package gives
-- `native hooks hooked=false version=immoralhook/1.0.0 (shadowhook) error=shadowhook_init rc=12`;
-- with this one line relaxed the same build gives hooked=true.
--
-- Those linker internals are only needed for ShadowHook's dlopen-monitor / "hook by symbol name"
-- features. We hook an address we resolve ourselves with dlsym(), i.e. shadowhook_hook_sym_addr(),
-- so init is allowed to continue with a warning instead of giving up.

package("shadowhook-immoral")
    set_homepage("https://github.com/bytedance/android-inline-hook/tree/main/doc")
    set_description("ShadowHook is an Android inline hook library which supports thumb, arm32 and arm64.")
    set_license("MIT")

    add_urls("https://github.com/bytedance/android-inline-hook/archive/refs/tags/$(version).tar.gz",
             "https://github.com/bytedance/android-inline-hook.git")

    add_versions("v2.0.1", "77b954483cee4eead5f2ab4571b19d00c5bfa6db1b656e62a3dc7c136a14aedb")

    add_deps("xdl")

    on_install("android|arm64-v8a", "android|armeabi-v7a", "android|armeabi", function (package)
        os.cd("shadowhook/src/main/cpp")

        -- The one local change: keep going when the linker internals cannot be resolved.
        io.replace("shadowhook.c",
            "if (__predict_false(0 != sh_linker_init())) GOTO_END(SHADOWHOOK_ERRNO_INIT_LINKER);",
            "if (__predict_false(0 != sh_linker_init())) SH_LOG_WARN(\"sh_linker_init failed, continue\");",
            {plain = true})
        assert(io.readfile("shadowhook.c"):find("sh_linker_init failed, continue", 1, true),
            "shadowhook.c: sh_linker_init relaxation not applied (upstream source changed?)")

        os.mv("shadowhook.map.txt", "shadowhook.map")
        io.writefile("xmake.lua", [[
            add_rules("mode.asan", "mode.release", "mode.debug")
            add_requires("xdl")
            add_packages("xdl")
            target("shadowhook")
                set_kind("$(kind)")
                set_languages("c17")

                add_syslinks("log")

                add_files("*.c", "common/*.c")

                if is_arch("arm64.*", "aarch64") then
                    add_files("arch/arm64/*.c")
                    add_files("arch/arm64/*.S")
                    add_includedirs("arch/arm64")
                    add_ldflags("-Wl,-z,max-page-size=16384")
                elseif is_arch("arm.*") then
                    add_files("arch/arm/*.c")
                    add_files("arch/arm/*.S")
                    add_includedirs("arch/arm")
                end

                add_includedirs(".", "include", "common", "third_party/bsd", "third_party/lss")
                add_headerfiles("include/(**.h)")

                if is_mode("asan") then
                    add_cflags("-fno-omit-frame-pointer")
                else
                    set_optimize("smallest")
                    add_cflags("-ffunction-sections", "-fdata-sections")
                    add_ldflags("-Wl,--exclude-libs,ALL", "-Wl,--gc-sections")
                    add_files("shadowhook.map")
                end
        ]])
        import("package.tools.xmake").install(package)
    end)

    on_test(function (package)
        assert(package:has_cfuncs("shadowhook_dlopen", {includes = "shadowhook.h"}))
    end)
