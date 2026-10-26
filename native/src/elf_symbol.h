#pragma once

namespace il {

/** Load bias of the first mapped ELF whose path ends with |nameSuffix|, or nullptr. */
void *findLoadedModuleBase(const char *nameSuffix);

/**
 * Resolves |symbol| inside an already-mapped ELF whose path ends with |nameSuffix|, without going
 * through dlopen/dlsym — works across linker namespaces (which is why dlopen fails for
 * libsensorservice.so inside system_server).
 */
void *lookupSymbolInLoadedModule(const char *nameSuffix, const char *symbol);

}  // namespace il
