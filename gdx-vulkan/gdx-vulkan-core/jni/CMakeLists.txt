cmake_minimum_required(VERSION 3.20)
project(gdxvulkan LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_C_VISIBILITY_PRESET hidden)

# Flat output dir on every generator: multi-config generators (MSVC) would otherwise nest binaries under <config>/,
# and gdx-vulkan-core/build.gradle points -Dgdxvulkan.native.path at this directory.
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})
foreach(cfg IN ITEMS DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY_${cfg} ${CMAKE_BINARY_DIR})
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY_${cfg} ${CMAKE_BINARY_DIR})
endforeach()

option(GDXVK_BUILD_JNI "Build the JNI shared library" ON)
option(GDXVK_BUILD_TESTS "Build native tests" ON)

# volk loads the loader at runtime; no link-time dependency on Vulkan.
add_library(gdxvk_core STATIC
    src/gdxvk_context.cpp
    src/gdxvk_presenter.cpp
    src/gdxvk_timeline.cpp
    src/gdxvk_deletion.cpp
    src/gdxvk_caches.cpp
    src/gdxvk_layout.cpp
    src/gdxvk_staging.cpp
    src/gdxvk_rings.cpp
    src/gdxvk_resources.cpp
    src/gdxvk_swappy.cpp
    src/gdxvk_shader_compiler.cpp
    src/vma_impl.cpp
    src/gdxvk_reflect.cpp
    src/volk_impl.c
    vendor/spirv-reflect/spirv_reflect.c)
target_include_directories(gdxvk_core PUBLIC src vendor)
target_compile_features(gdxvk_core PUBLIC cxx_std_17)
if(WIN32)
    target_compile_definitions(gdxvk_core PUBLIC VK_USE_PLATFORM_WIN32_KHR NOMINMAX)
elseif(ANDROID)
    target_compile_definitions(gdxvk_core PUBLIC VK_USE_PLATFORM_ANDROID_KHR)
elseif(UNIX AND NOT APPLE)
    target_compile_definitions(gdxvk_core PUBLIC VK_USE_PLATFORM_XLIB_KHR)
endif()
if(NOT MSVC)
    target_compile_options(gdxvk_core PRIVATE -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers)
    # VMA triggers noisy warnings on some compilers; it is third-party.
    set_source_files_properties(src/vma_impl.cpp PROPERTIES COMPILE_FLAGS "-w")
endif()
target_link_libraries(gdxvk_core PUBLIC ${CMAKE_DL_LIBS})

# glslang (Khronos): GLSL to SPIR-V at run time, used by the shader translator (docs/SHADER_COMPILER.md). Vendored in vendor/glslang (16.6.0)
# with only the front end and the SPIR-V generator: no HLSL, no tools, no tests, no SPIRV-Tools optimizer yet.
option(GDXVK_GLSLANG "Build glslang for run-time GLSL compilation" ON)
if(GDXVK_GLSLANG)
    set(ENABLE_HLSL OFF CACHE BOOL "" FORCE)
    set(ENABLE_OPT OFF CACHE BOOL "" FORCE)
    set(ENABLE_GLSLANG_BINARIES OFF CACHE BOOL "" FORCE)
    set(ENABLE_SPVREMAPPER OFF CACHE BOOL "" FORCE)
    set(ENABLE_GLSLANG_JS OFF CACHE BOOL "" FORCE)
    set(BUILD_EXTERNAL OFF CACHE BOOL "" FORCE)
    set(GLSLANG_TESTS OFF CACHE BOOL "" FORCE)
    set(GLSLANG_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
    set(ENABLE_PCH OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    add_subdirectory(vendor/glslang EXCLUDE_FROM_ALL)
    target_compile_definitions(gdxvk_core PUBLIC GDXVK_GLSLANG)
    target_link_libraries(gdxvk_core PUBLIC glslang glslang-default-resource-limits)
    target_include_directories(gdxvk_core PRIVATE vendor/glslang)
endif()

# Android Frame Pacing (Swappy), from the games-frame-pacing AAR through prefab. Off unless the Gradle build asks for it.
option(GDXVK_SWAPPY "Link Android Frame Pacing (Swappy)" OFF)
if(GDXVK_SWAPPY AND ANDROID)
    find_package(games-frame-pacing REQUIRED CONFIG)
    target_compile_definitions(gdxvk_core PUBLIC GDXVK_SWAPPY)
    target_link_libraries(gdxvk_core PUBLIC games-frame-pacing::swappy_static)
endif()

if(GDXVK_BUILD_JNI)
    if(NOT ANDROID) # the NDK sysroot already carries jni.h
        find_package(JNI REQUIRED COMPONENTS JVM) # headers only; AWT (libjawt) is missing from headless JDKs
    endif()
    add_library(gdxvk SHARED src/gdxvk_jni.cpp)
    target_include_directories(gdxvk PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(gdxvk PRIVATE gdxvk_core)
    if(ANDROID) # ANativeWindow_fromSurface / ANativeWindow_release
        target_link_libraries(gdxvk PRIVATE android log)
    endif()
    set_target_properties(gdxvk PROPERTIES OUTPUT_NAME gdxvk)
endif()

if(GDXVK_BUILD_TESTS AND NOT ANDROID)
    enable_testing()
    add_executable(tier_test test/tier_test.cpp)
    target_include_directories(tier_test PRIVATE src)
    add_test(NAME tier_test COMMAND tier_test)

    add_executable(smoke test/smoke_main.cpp)
    target_link_libraries(smoke PRIVATE gdxvk_core)
    # Needs an ICD (lavapipe in CI). Exit code 2 = no ICD, treated as skip locally.
    add_test(NAME smoke COMMAND smoke --validation)

    if(GDXVK_GLSLANG)
        add_executable(shader_compiler_test test/shader_compiler_test.cpp)
        target_link_libraries(shader_compiler_test PRIVATE gdxvk_core)
        add_test(NAME shader_compiler_test COMMAND shader_compiler_test)
    endif()
    set_tests_properties(smoke PROPERTIES SKIP_RETURN_CODE 2)
endif()
