
// Appended to the pinned linkernsbypass source so it can fill that file's private symbols.
// Resolving the linker exports directly avoids the upstream instruction walk, which leaves a
// libdl code page writable and executable; integrity checks such as PairIP treat that as tampering.
static bool gpu_resolve_linker_symbols_directly() {
    if (android_get_device_api_level() < 28) return false;
    void *linkerHandle = dlopen("ld-android.so", RTLD_LAZY | RTLD_NOLOAD);
    void *libdlHandle = dlopen("libdl_android.so", RTLD_LAZY | RTLD_NOLOAD);
    if (!linkerHandle || !libdlHandle) return false;
    android_link_namespaces_all_libs = reinterpret_cast<android_link_namespaces_all_libs_t>(
        dlsym(linkerHandle, "__loader_android_link_namespaces_all_libs"));
    android_link_namespaces = reinterpret_cast<android_link_namespaces_t>(
        dlsym(linkerHandle, "__loader_android_link_namespaces"));
    loader_android_create_namespace = reinterpret_cast<loader_android_create_namespace_t>(
        dlsym(libdlHandle, "__loader_android_create_namespace"));
    android_get_exported_namespace = reinterpret_cast<android_get_exported_namespace_t>(
        dlsym(libdlHandle, "__loader_android_get_exported_namespace"));
    lib_loaded = android_link_namespaces_all_libs && android_link_namespaces &&
        loader_android_create_namespace && android_get_exported_namespace;
    return lib_loaded;
}

#ifdef GPU_LINKER_DIRECT_ONLY
// The staged hook library only receives hook parameters inside the driver namespace, so it never
// falls back to the instruction walk.
__attribute__((constructor)) static void gpu_resolve_linker_symbols_on_load() {
    gpu_resolve_linker_symbols_directly();
}
#else
// Zygisk loads the module in the default namespace, where the linker exports are directly reachable.
extern "C" void gpu_initialize_linker_symbols() {
    if (android_get_device_api_level() < 28) return;
    if (gpu_resolve_linker_symbols_directly()) {
        __android_log_print(ANDROID_LOG_DEBUG, "GpuDriverLoader", "linker symbols resolved directly");
        return;
    }
    __android_log_print(ANDROID_LOG_WARN, "GpuDriverLoader",
                        "linker symbols unavailable directly; falling back to instruction walk");
    gpu_resolve_linker_symbols_by_walk();
}
#endif
