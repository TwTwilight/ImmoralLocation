// Namespace-free symbol resolution.
//
// system_server loads libsensorservice.so into its own isolated linker namespace, so a plain
// `dlopen("libsensorservice.so", RTLD_NOLOAD)` from our library fails even though the library is
// mapped in the same process. `dl_iterate_phdr` sees every mapped object regardless of namespace,
// so we locate the module by path suffix and resolve the symbol from its in-memory ELF dynamic
// symbol table ourselves. This is the same trick xdl/ShadowHook use internally, kept local so the
// hook path has no further dependencies.

#include "elf_symbol.h"

#include <link.h>

#include <cstring>
#include <elf.h>

namespace il {
namespace {

struct Match {
    const char *wanted;
    void *base;
    bool found;
};

int phdrCallback(struct dl_phdr_info *info, size_t, void *data) {
    auto *match = static_cast<Match *>(data);
    if (info->dlpi_name == nullptr) return 0;
    const char *name = info->dlpi_name;
    const size_t nameLen = strlen(name);
    const size_t wantLen = strlen(match->wanted);
    if (nameLen < wantLen) return 0;
    if (strcmp(name + (nameLen - wantLen), match->wanted) != 0) return 0;
    match->base = reinterpret_cast<void *>(info->dlpi_addr);
    match->found = true;
    return 1;  // stop iterating
}

/** Returns the load bias of the first mapped object whose path ends with |suffix|. */
void *moduleBase(const char *suffix) {
    Match match{suffix, nullptr, false};
    dl_iterate_phdr(phdrCallback, &match);
    return match.found ? match.base : nullptr;
}

const ElfW(Phdr) *findProgramHeader(void *base, ElfW(Word) type) {
    const auto *ehdr = static_cast<const ElfW(Ehdr) *>(base);
    if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0) return nullptr;
    const auto *phdr = reinterpret_cast<const ElfW(Phdr) *>(
        static_cast<const char *>(base) + ehdr->e_phoff);
    for (int i = 0; i < ehdr->e_phnum; ++i) {
        if (phdr[i].p_type == type) return &phdr[i];
    }
    return nullptr;
}

}  // namespace

void *findLoadedModuleBase(const char *nameSuffix) { return moduleBase(nameSuffix); }

void *lookupSymbolInLoadedModule(const char *nameSuffix, const char *symbol) {
    void *base = moduleBase(nameSuffix);
    if (base == nullptr) return nullptr;

    const ElfW(Phdr) *dyn = findProgramHeader(base, PT_DYNAMIC);
    if (dyn == nullptr) return nullptr;

    auto *dynEntries = reinterpret_cast<ElfW(Dyn) *>(
        static_cast<char *>(base) + dyn->p_vaddr);
    const ElfW(Sym) *symtab = nullptr;
    const char *strtab = nullptr;
    size_t strsz = 0;
    const ElfW(Word) *gnuHash = nullptr;
    const ElfW(Word) *sysvHash = nullptr;

    for (ElfW(Dyn) *entry = dynEntries; entry->d_tag != DT_NULL; ++entry) {
        switch (entry->d_tag) {
            case DT_SYMTAB:
                symtab = reinterpret_cast<const ElfW(Sym) *>(
                    static_cast<char *>(base) + entry->d_un.d_ptr);
                break;
            case DT_STRTAB:
                strtab = reinterpret_cast<const char *>(
                    static_cast<char *>(base) + entry->d_un.d_ptr);
                break;
            case DT_STRSZ:
                strsz = entry->d_un.d_val;
                break;
            case DT_GNU_HASH:
                gnuHash = reinterpret_cast<const ElfW(Word) *>(
                    static_cast<char *>(base) + entry->d_un.d_ptr);
                break;
            case DT_HASH:
                sysvHash = reinterpret_cast<const ElfW(Word) *>(
                    static_cast<char *>(base) + entry->d_un.d_ptr);
                break;
            default:
                break;
        }
    }
    if (symtab == nullptr || strtab == nullptr || strsz == 0) return nullptr;

    // GNU hash gives an exact symbol count (bucket/symoffset/chain layout).
    size_t symbolCount = 0;
    if (gnuHash != nullptr) {
        const ElfW(Word) nbuckets = gnuHash[0];
        const ElfW(Word) symoffset = gnuHash[1];
        const ElfW(Word) bloomSize = gnuHash[2];
        const auto *buckets = gnuHash + 4 + bloomSize * 2;
        const auto *chain = buckets + nbuckets;
        ElfW(Word) last = 0;
        for (ElfW(Word) i = 0; i < nbuckets; ++i) {
            if (buckets[i] > last) last = buckets[i];
        }
        if (last >= symoffset) {
            while ((chain[last - symoffset] & 1u) == 0) ++last;
            symbolCount = last + 1;
        }
    } else if (sysvHash != nullptr) {
        symbolCount = sysvHash[1];
    }
    if (symbolCount == 0) return nullptr;

    for (size_t i = 1; i < symbolCount; ++i) {
        const ElfW(Sym) &sym = symtab[i];
        if (sym.st_name == 0 || sym.st_name >= strsz) continue;
        if (sym.st_shndx == SHN_UNDEF) continue;
        if (strcmp(strtab + sym.st_name, symbol) != 0) continue;
        return static_cast<char *>(base) + sym.st_value;
    }
    return nullptr;
}

}  // namespace il
