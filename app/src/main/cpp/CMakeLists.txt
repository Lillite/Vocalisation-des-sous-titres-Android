cmake_minimum_required(VERSION 3.22.1)
project(subtitle_model_probe LANGUAGES C CXX)

# Exact upstream source and bytes. The application never downloads code or models
# at runtime. Build metadata must not depend on the caller's PR/merge commit.
include(FetchContent)
set(LLAMA_BUILD_NUMBER 0 CACHE STRING "Pinned diagnostic build" FORCE)
set(LLAMA_BUILD_COMMIT "8ca4060b" CACHE STRING "Pinned llama.cpp revision" FORCE)
# ggml otherwise overwrites LLAMA_BUILD_COMMIT by walking up from its unpacked
# source to the application's .git directory. An intentionally defined empty
# executable suppresses find_program(), preserving the pinned source revision.
set(GIT_EXE "")
set(GGML_GIT_DIRTY 0)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
# Android's x86_64 ABI guarantees SSE4.2, but not AVX/AVX2. The emulator test
# must execute on that ABI baseline rather than require the build host's ISA.
set(GGML_AVX OFF CACHE BOOL "" FORCE)
set(GGML_AVX2 OFF CACHE BOOL "" FORCE)
set(GGML_AVX512 OFF CACHE BOOL "" FORCE)
set(GGML_F16C OFF CACHE BOOL "" FORCE)
set(GGML_FMA OFF CACHE BOOL "" FORCE)
set(GGML_BMI2 OFF CACHE BOOL "" FORCE)
# Only Android arm64 packages include the optional Adreno backend. Its loader is
# static and vendor discovery is direct; CPU startup never needs libOpenCL.so.
function(subtitle_add_direct_opencl)
    if(TARGET OpenCL)
        return()
    endif()
    FetchContent_Declare(subtitle_opencl_headers
        URL https://codeload.github.com/KhronosGroup/OpenCL-Headers/tar.gz/refs/tags/v2025.07.22
        URL_HASH SHA256=98f0a3ea26b4aec051e533cb1750db2998ab8e82eda97269ed6efe66ec94a240
    )
    FetchContent_GetProperties(subtitle_opencl_headers)
    if(NOT subtitle_opencl_headers_POPULATED)
        FetchContent_Populate(subtitle_opencl_headers)
    endif()
    find_package(Python3 REQUIRED COMPONENTS Interpreter)
    set(generated_direct "${CMAKE_CURRENT_BINARY_DIR}/direct-opencl/direct_opencl_generated.cpp")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
        "${CMAKE_CURRENT_SOURCE_DIR}/generate_direct_opencl.py")
    execute_process(
        COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/generate_direct_opencl.py"
            "${subtitle_opencl_headers_SOURCE_DIR}/CL/cl.h" "${generated_direct}"
        RESULT_VARIABLE direct_status
    )
    if(NOT direct_status EQUAL 0)
        message(FATAL_ERROR "Pinned direct OpenCL dispatch could not be generated")
    endif()
    # A static, hidden C API shim. The Android vendor library is resolved only
    # after an explicit GPU request, never a mandatory dependency of the APK.
    add_library(OpenCL STATIC "${generated_direct}")
    target_compile_features(OpenCL PRIVATE cxx_std_17)
    set_target_properties(OpenCL PROPERTIES POSITION_INDEPENDENT_CODE ON CXX_VISIBILITY_PRESET hidden)
    target_include_directories(OpenCL PUBLIC "${subtitle_opencl_headers_SOURCE_DIR}" PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
    target_link_libraries(OpenCL PRIVATE ${CMAKE_DL_LIBS})
    set(OpenCL_LIBRARY OpenCL CACHE STRING "Optional direct vendor dispatch target" FORCE)
    set(OpenCL_INCLUDE_DIR "${subtitle_opencl_headers_SOURCE_DIR}" CACHE PATH "Pinned OpenCL includes" FORCE)
    set(subtitle_opencl_headers_SOURCE_DIR "${subtitle_opencl_headers_SOURCE_DIR}" PARENT_SCOPE)
    set(Python3_EXECUTABLE "${Python3_EXECUTABLE}" PARENT_SCOPE)
endfunction()

function(subtitle_add_optional_vulkan)
    # Headers are compile-time dependencies only. The phone's Vulkan loader is
    # resolved through a small hidden shim after an explicit Vulkan request.
    FetchContent_Declare(subtitle_vulkan_headers
        URL https://codeload.github.com/KhronosGroup/Vulkan-Headers/tar.gz/refs/tags/v1.4.341
        URL_HASH SHA256=8876aad926b2e72bdefddc34885472d5332e2d20aa3ed57313466f0bc982cf3f
    )
    FetchContent_Declare(subtitle_spirv_headers
        URL https://codeload.github.com/KhronosGroup/SPIRV-Headers/tar.gz/refs/tags/vulkan-sdk-1.4.341.0
        URL_HASH SHA256=cab0a654c4917e16367483296b44cdb1d614e3120c721beafcd37e3a8580486c
    )
    foreach(dependency subtitle_vulkan_headers subtitle_spirv_headers)
        FetchContent_GetProperties(${dependency})
        if(NOT ${dependency}_POPULATED)
            FetchContent_Populate(${dependency})
        endif()
    endforeach()
    set(shader_compiler "${CMAKE_ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc")
    if(CMAKE_HOST_WIN32)
        string(APPEND shader_compiler ".exe")
    endif()
    if(NOT EXISTS "${shader_compiler}")
        message(FATAL_ERROR "The pinned Android NDK's host glslc is required for Vulkan")
    endif()
    set(Vulkan_GLSLC_EXECUTABLE "${shader_compiler}" CACHE FILEPATH "Pinned NDK host shader compiler" FORCE)
    find_program(SUBTITLE_VULKAN_HOST_CC NAMES cc gcc clang NO_CMAKE_FIND_ROOT_PATH REQUIRED)
    find_program(SUBTITLE_VULKAN_HOST_CXX NAMES c++ g++ clang++ NO_CMAKE_FIND_ROOT_PATH REQUIRED)
    set(shader_host_toolchain "${CMAKE_CURRENT_BINARY_DIR}/vulkan-host-toolchain.cmake")
    file(WRITE "${shader_host_toolchain}"
        "set(CMAKE_BUILD_TYPE Release)\n"
        "set(CMAKE_C_COMPILER \"${SUBTITLE_VULKAN_HOST_CC}\")\n"
        "set(CMAKE_CXX_COMPILER \"${SUBTITLE_VULKAN_HOST_CXX}\")\n"
        "set(CMAKE_MAKE_PROGRAM \"${CMAKE_MAKE_PROGRAM}\" CACHE FILEPATH \"Host shader build tool\" FORCE)\n"
        "set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)\n"
        "set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY NEVER)\n"
        "set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE NEVER)\n")
    set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${shader_host_toolchain}" CACHE FILEPATH "Host shader generator toolchain" FORCE)
    set(Vulkan_INCLUDE_DIR "${subtitle_vulkan_headers_SOURCE_DIR}/include" CACHE PATH "Pinned Vulkan headers" FORCE)
    add_library(subtitle_vulkan_dispatch STATIC optional_vulkan_dispatch.cpp)
    set_target_properties(subtitle_vulkan_dispatch PROPERTIES POSITION_INDEPENDENT_CODE ON CXX_VISIBILITY_PRESET hidden)
    target_compile_features(subtitle_vulkan_dispatch PRIVATE cxx_std_17)
    target_include_directories(subtitle_vulkan_dispatch PUBLIC "${Vulkan_INCLUDE_DIR}" PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
    target_link_libraries(subtitle_vulkan_dispatch PRIVATE ${CMAKE_DL_LIBS})
    add_library(Vulkan::Vulkan ALIAS subtitle_vulkan_dispatch)
    # FindVulkan still validates its two inputs; its imported target is already
    # supplied by the optional dispatch shim rather than a mandatory .so.
    set(Vulkan_LIBRARY subtitle_vulkan_dispatch CACHE STRING "Optional Vulkan loader dispatch target" FORCE)
    set(spirv_config "${CMAKE_CURRENT_BINARY_DIR}/vulkan-dependencies")
    file(MAKE_DIRECTORY "${spirv_config}")
    file(WRITE "${spirv_config}/SPIRV-HeadersConfig.cmake"
        "if(NOT TARGET SPIRV-Headers::SPIRV-Headers)\n"
        "  add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)\n"
        "  set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES INTERFACE_INCLUDE_DIRECTORIES \"${subtitle_spirv_headers_SOURCE_DIR}/include\")\n"
        "endif()\n")
    set(SPIRV-Headers_DIR "${spirv_config}" CACHE PATH "Pinned SPIR-V header package" FORCE)
    set(subtitle_vulkan_headers_SOURCE_DIR "${subtitle_vulkan_headers_SOURCE_DIR}" PARENT_SCOPE)
    set(subtitle_spirv_headers_SOURCE_DIR "${subtitle_spirv_headers_SOURCE_DIR}" PARENT_SCOPE)
endfunction()

set(SUBTITLE_HAS_OPENCL OFF)
set(SUBTITLE_HAS_VULKAN OFF)
if(ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    option(SUBTITLE_ENABLE_OPENCL "Build optional Android Adreno OpenCL backend" ON)
    option(SUBTITLE_ENABLE_VULKAN "Build optional Android Vulkan backend" ON)
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    FetchContent_Declare(kleidiai
        URL https://github.com/ARM-software/kleidiai/releases/download/v1.32.0/kleidiai-v1.32.0-src.tar.gz
        URL_HASH SHA256=a48d373e7c5d721a0aecbf5ddaf0bdf245bc8064e6b84086f13db0e102b553a6
    )
    if(SUBTITLE_ENABLE_OPENCL)
        set(SUBTITLE_HAS_OPENCL ON)
        subtitle_add_direct_opencl()
        set(GGML_OPENCL_PROFILING OFF CACHE BOOL "" FORCE)
        set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
        set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
        set(GGML_OPENCL_USE_ADRENO_BIN_KERNELS OFF CACHE BOOL "" FORCE)
    endif()
    if(SUBTITLE_ENABLE_VULKAN)
        set(SUBTITLE_HAS_VULKAN ON)
        subtitle_add_optional_vulkan()
    endif()
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
endif()
set(GGML_OPENCL ${SUBTITLE_HAS_OPENCL} CACHE BOOL "" FORCE)
set(GGML_VULKAN ${SUBTITLE_HAS_VULKAN} CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    # Assertions and debug records must not embed the runner's checkout path.
    add_compile_options(
        "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=subtitle-native"
        "-ffile-prefix-map=${CMAKE_BINARY_DIR}=subtitle-native-build"
    )
endif()
FetchContent_Declare(probe_llama
    URL https://codeload.github.com/ggml-org/llama.cpp/tar.gz/8ca4060b7e30e76baaa5670ee1ec1b760b7f8552
    URL_HASH SHA256=68f2759b1cd93f352ade2413c1e123199632a348c46414b05f30efc67913f618
)
FetchContent_MakeAvailable(probe_llama)

if(SUBTITLE_HAS_OPENCL)
    find_package(Python3 REQUIRED COMPONENTS Interpreter)
    # ggml's default static registry probes every compiled backend at first CPU
    # use. Remove only OpenCL auto-registration: strict CPU then remains usable
    # even when the vendor GPU driver is absent or fails during initialization.
    foreach(property COMPILE_DEFINITIONS INTERFACE_COMPILE_DEFINITIONS)
        get_target_property(definitions ggml ${property})
        if(definitions)
            list(REMOVE_ITEM definitions GGML_USE_OPENCL)
            set_property(TARGET ggml PROPERTY ${property} "${definitions}")
        endif()
    endforeach()
    set(instrumented_opencl "${CMAKE_CURRENT_BINARY_DIR}/optional-opencl/ggml-opencl-instrumented.cpp")
    execute_process(
        COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/optional_opencl_patch.py"
            "${probe_llama_SOURCE_DIR}/ggml/src/ggml-opencl/ggml-opencl.cpp" "${instrumented_opencl}"
        RESULT_VARIABLE instrumented_status
    )
    if(NOT instrumented_status EQUAL 0)
        message(FATAL_ERROR "Pinned OpenCL instrumentation could not be generated")
    endif()
    get_target_property(opencl_sources ggml-opencl SOURCES)
    list(REMOVE_ITEM opencl_sources ggml-opencl.cpp)
    list(APPEND opencl_sources "${instrumented_opencl}")
    set_property(TARGET ggml-opencl PROPERTY SOURCES "${opencl_sources}")
    target_include_directories(ggml-opencl PRIVATE "${probe_llama_SOURCE_DIR}/ggml/src/ggml-opencl")
endif()

if(SUBTITLE_HAS_VULKAN)
    find_package(Python3 REQUIRED COMPONENTS Interpreter)
    # Upstream's ExternalProject inherits Ninja but does not forward its path.
    # Configure the exact host project once with that path; the regular upstream
    # configure/build then reuses this valid cache even when Ninja is not on PATH.
    ExternalProject_Get_Property(vulkan-shaders-gen SOURCE_DIR BINARY_DIR CMAKE_ARGS)
    string(REPLACE "$<CONFIG>" "${CMAKE_BUILD_TYPE}" shader_host_args "${CMAKE_ARGS}")
    execute_process(
        COMMAND "${CMAKE_COMMAND}" -S "${SOURCE_DIR}" -B "${BINARY_DIR}"
            -G "${CMAKE_GENERATOR}" "-DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}" ${shader_host_args}
        RESULT_VARIABLE shader_host_status
    )
    if(NOT shader_host_status EQUAL 0)
        message(FATAL_ERROR "Pinned Vulkan host shader generator could not be configured")
    endif()
    foreach(property COMPILE_DEFINITIONS INTERFACE_COMPILE_DEFINITIONS)
        get_target_property(definitions ggml ${property})
        if(definitions)
            list(REMOVE_ITEM definitions GGML_USE_VULKAN)
            set_property(TARGET ggml PROPERTY ${property} "${definitions}")
        endif()
    endforeach()
    set(instrumented_vulkan "${CMAKE_CURRENT_BINARY_DIR}/optional-vulkan/ggml-vulkan-instrumented.cpp")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
        "${CMAKE_CURRENT_SOURCE_DIR}/optional_vulkan_patch.py")
    execute_process(
        COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/optional_vulkan_patch.py"
            "${probe_llama_SOURCE_DIR}/ggml/src/ggml-vulkan/ggml-vulkan.cpp" "${instrumented_vulkan}"
        RESULT_VARIABLE instrumented_status
    )
    if(NOT instrumented_status EQUAL 0)
        message(FATAL_ERROR "Pinned Vulkan instrumentation could not be generated")
    endif()
    get_target_property(vulkan_sources ggml-vulkan SOURCES)
    list(REMOVE_ITEM vulkan_sources ggml-vulkan.cpp)
    list(APPEND vulkan_sources "${instrumented_vulkan}")
    set_property(TARGET ggml-vulkan PROPERTY SOURCES "${vulkan_sources}")
    target_include_directories(ggml-vulkan PRIVATE
        "${probe_llama_SOURCE_DIR}/ggml/src/ggml-vulkan"
        "${subtitle_spirv_headers_SOURCE_DIR}/include")
endif()

add_library(model_probe_engine STATIC model_probe_engine.cpp local_model_backend.cpp local_translation_engine.cpp optional_opencl_loader.cpp optional_vulkan_backend.cpp)
target_compile_features(model_probe_engine PUBLIC cxx_std_17)
target_include_directories(model_probe_engine PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(model_probe_engine PUBLIC llama)
if(SUBTITLE_HAS_OPENCL)
    target_compile_definitions(model_probe_engine PRIVATE SUBTITLE_HAS_OPENCL=1 CL_TARGET_OPENCL_VERSION=300)
    target_include_directories(model_probe_engine PRIVATE "${subtitle_opencl_headers_SOURCE_DIR}")
    target_link_libraries(model_probe_engine PRIVATE OpenCL ggml-opencl ${CMAKE_DL_LIBS})
endif()
if(SUBTITLE_HAS_VULKAN)
    target_compile_definitions(model_probe_engine PRIVATE SUBTITLE_HAS_VULKAN=1)
    target_link_libraries(model_probe_engine PRIVATE subtitle_vulkan_dispatch ggml-vulkan ${CMAKE_DL_LIBS})
endif()

if(ANDROID)
    add_library(subtitle_model_probe SHARED model_probe_jni.cpp local_translation_jni.cpp)
    target_link_libraries(subtitle_model_probe PRIVATE model_probe_engine)
    # Android 15+ devices may use 16-KiB memory pages.
    target_link_options(subtitle_model_probe PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL" "-Wl,--no-undefined")
else()
    target_compile_definitions(model_probe_engine PUBLIC SUBTITLE_NATIVE_TESTING=1)
    # Actual host inference test, not an Android stub or performance claim.
    add_executable(model-probe-smoke tests/model_probe_smoke.cpp)
    target_link_libraries(model-probe-smoke PRIVATE model_probe_engine)
    add_executable(local-translation-smoke tests/local_translation_smoke.cpp)
    target_link_libraries(local-translation-smoke PRIVATE model_probe_engine)
    add_executable(optional-opencl-diagnostic-smoke tests/optional_opencl_diagnostic_smoke.cpp)
    target_compile_features(optional-opencl-diagnostic-smoke PRIVATE cxx_std_17)
    target_include_directories(optional-opencl-diagnostic-smoke PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
    find_package(Threads REQUIRED)
    target_link_libraries(optional-opencl-diagnostic-smoke PRIVATE Threads::Threads)
    # Actual host forwarding test with a shared library whose opaque handles
    # intentionally do not contain ICD dispatch tables. No GPU is simulated.
    subtitle_add_direct_opencl()
    set(fake_direct "${CMAKE_CURRENT_BINARY_DIR}/direct-opencl/fake_opencl.cpp")
    execute_process(
        COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/generate_fake_opencl.py"
            "${subtitle_opencl_headers_SOURCE_DIR}/CL/cl.h" "${fake_direct}"
        RESULT_VARIABLE fake_status
    )
    if(NOT fake_status EQUAL 0)
        message(FATAL_ERROR "Synthetic non-ICD host library could not be generated")
    endif()
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
        "${CMAKE_CURRENT_SOURCE_DIR}/tests/generate_fake_opencl.py")
    foreach(kind complete optional missing)
        add_library(direct-opencl-synthetic-${kind} SHARED "${fake_direct}")
        target_include_directories(direct-opencl-synthetic-${kind} PRIVATE "${subtitle_opencl_headers_SOURCE_DIR}")
        target_compile_features(direct-opencl-synthetic-${kind} PRIVATE cxx_std_17)
        if(kind STREQUAL optional)
            target_compile_definitions(direct-opencl-synthetic-${kind} PRIVATE DIRECT_TEST_OPTIONAL=1)
        elseif(kind STREQUAL missing)
            target_compile_definitions(direct-opencl-synthetic-${kind} PRIVATE DIRECT_TEST_MISSING_REQUIRED=1)
        endif()
    endforeach()
    add_executable(direct-opencl-dispatch-smoke tests/direct_opencl_dispatch_smoke.cpp)
    target_compile_features(direct-opencl-dispatch-smoke PRIVATE cxx_std_17)
    target_include_directories(direct-opencl-dispatch-smoke PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
    target_link_libraries(direct-opencl-dispatch-smoke PRIVATE OpenCL Threads::Threads ${CMAKE_DL_LIBS})
endif()
