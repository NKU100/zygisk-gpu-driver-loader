cmake_minimum_required(VERSION 3.22.1)
project(sample)

set(CXX_FLAGS "${CXX_FLAGS} -fno-exceptions -fno-rtti -fvisibility=hidden -fvisibility-inlines-hidden")

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${CXX_FLAGS}")

add_library(${MODULE_NAME} SHARED
        example.cpp
        driver_loader.cpp
        graphics_environment.cpp
        companion_fd.cpp
        external/yyjson.c
)
target_include_directories(${MODULE_NAME} PRIVATE external external/libadrenotools/include)
target_link_libraries(${MODULE_NAME} log)
if(NOT DEFINED MODULE_ID)
    set(MODULE_ID "zygisk_sample")
endif()
if(NOT MODULE_ID MATCHES "^[A-Za-z0-9_.-]+$" OR MODULE_ID STREQUAL "." OR MODULE_ID STREQUAL "..")
    message(FATAL_ERROR "MODULE_ID must be a single safe path component")
endif()
target_compile_definitions(${MODULE_NAME} PRIVATE MODULE_ID="${MODULE_ID}")

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(BUILD_SHARED_LIBS OFF)
    add_subdirectory(external/libadrenotools EXCLUDE_FROM_ALL)
    # The Zygisk module initializes linker symbols only after a target and its private files pass validation.
    # The separately built hook libraries retain upstream constructor behavior.
    set(linker_source_dir "${CMAKE_CURRENT_SOURCE_DIR}/external/libadrenotools/lib/linkernsbypass")
    file(READ "${linker_source_dir}/android_linker_ns.cpp" linker_source)
    string(REPLACE "__attribute__((constructor)) static void resolve_linker_symbols()"
        "extern \"C\" void gpu_initialize_linker_symbols()" deferred_linker_source "${linker_source}")
    if(deferred_linker_source STREQUAL linker_source)
        message(FATAL_ERROR "Pinned linkernsbypass initializer changed; review deferred initialization")
    endif()
    file(GENERATE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/deferred_linker.cpp" CONTENT "${deferred_linker_source}")
    add_library(gpu_linkernsbypass STATIC "${CMAKE_CURRENT_BINARY_DIR}/deferred_linker.cpp"
        "${linker_source_dir}/elf_soname_patcher.cpp")
    target_include_directories(gpu_linkernsbypass PUBLIC "${linker_source_dir}")
    target_include_directories(${MODULE_NAME} PRIVATE "${linker_source_dir}")
    target_sources(${MODULE_NAME} PRIVATE external/libadrenotools/src/hook/hook_impl.cpp)
    target_include_directories(${MODULE_NAME} PRIVATE external/libadrenotools/src/hook)
    target_link_libraries(gpu_linkernsbypass dl)
    set_target_properties(gpu_linkernsbypass PROPERTIES POSITION_INDEPENDENT_CODE ON)
    set_property(TARGET adrenotools PROPERTY LINK_LIBRARIES gpu_linkernsbypass)
    set_property(TARGET adrenotools PROPERTY INTERFACE_LINK_LIBRARIES gpu_linkernsbypass)
    set_target_properties(adrenotools linkernsbypass PROPERTIES POSITION_INDEPENDENT_CODE ON)
    target_link_libraries(${MODULE_NAME} adrenotools)
    target_link_options(${MODULE_NAME} PRIVATE "-Wl,-u,gpu_initialize_linker_symbols")
    add_dependencies(${MODULE_NAME} hook_impl main_hook)
endif()
