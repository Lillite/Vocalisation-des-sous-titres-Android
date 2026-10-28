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
# static and vendor discovery is explicit; CPU startup never needs libOpenCL.so.
set(SUBTITLE_HAS_OPENCL OFF)
if(ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    option(SUBTITLE_ENABLE_OPENCL "Build optional Android Adreno OpenCL backend" ON)
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    FetchContent_Declare(kleidiai
        URL https://github.com/ARM-software/kleidiai/releases/download/v1.32.0/kleidiai-v1.32.0-src.tar.gz
        URL_HASH SHA256=a48d373e7c5d721a0aecbf5ddaf0bdf245bc8064e6b84086f13db0e102b553a6
    )
    if(SUBTITLE_ENABLE_OPENCL)
        set(SUBTITLE_HAS_OPENCL ON)
        FetchContent_Declare(subtitle_opencl_headers
            URL https://codeload.github.com/KhronosGroup/OpenCL-Headers/tar.gz/refs/tags/v2025.07.22
            URL_HASH SHA256=98f0a3ea26b4aec051e533cb1750db2998ab8e82eda97269ed6efe66ec94a240
        )
        FetchContent_GetProperties(subtitle_opencl_headers)
        if(NOT subtitle_opencl_headers_POPULATED)
            FetchContent_Populate(subtitle_opencl_headers)
        endif()
        set(OPENCL_ICD_LOADER_HEADERS_DIR "${subtitle_opencl_headers_SOURCE_DIR}" CACHE PATH "Pinned OpenCL headers" FORCE)
        set(OPENCL_ICD_LOADER_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
        set(OPENCL_ICD_LOADER_PIC ON CACHE BOOL "" FORCE)
        set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE)
        set(ENABLE_OPENCL_LAYERS OFF CACHE BOOL "" FORCE)
        set(ENABLE_OPENCL_LAYERINFO OFF CACHE BOOL "" FORCE)
        set(ENABLE_OPENCL_LOADER_MANAGED_DISPATCH OFF CACHE BOOL "" FORCE)
        FetchContent_Declare(subtitle_opencl_loader
            URL https://codeload.github.com/KhronosGroup/OpenCL-ICD-Loader/tar.gz/refs/tags/v2025.07.22
            URL_HASH SHA256=dff7a0b11ad5b63a669358e3476e3dc889a4a361674e5b69b267b944d0794142
        )
        FetchContent_MakeAvailable(subtitle_opencl_loader)
        set_target_properties(OpenCL PROPERTIES POSITION_INDEPENDENT_CODE ON C_VISIBILITY_PRESET hidden)
        # FindOpenCL accepts this existing CMake target. No vendor library is
        # linked or copied into the APK; it is opened only for a GPU request.
        set(OpenCL_LIBRARY OpenCL CACHE STRING "Pinned static ICD loader target" FORCE)
        set(OpenCL_INCLUDE_DIR "${subtitle_opencl_headers_SOURCE_DIR}" CACHE PATH "Pinned OpenCL includes" FORCE)
        set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
        set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
        set(GGML_OPENCL_USE_ADRENO_BIN_KERNELS OFF CACHE BOOL "" FORCE)
    endif()
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
endif()
set(GGML_OPENCL ${SUBTITLE_HAS_OPENCL} CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
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

add_library(model_probe_engine STATIC model_probe_engine.cpp local_model_backend.cpp local_translation_engine.cpp optional_opencl_loader.cpp)
target_compile_features(model_probe_engine PUBLIC cxx_std_17)
target_include_directories(model_probe_engine PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(model_probe_engine PUBLIC llama)
if(SUBTITLE_HAS_OPENCL)
    target_compile_definitions(model_probe_engine PRIVATE SUBTITLE_HAS_OPENCL=1 CL_TARGET_OPENCL_VERSION=300)
    target_include_directories(model_probe_engine PRIVATE "${subtitle_opencl_headers_SOURCE_DIR}")
    target_link_libraries(model_probe_engine PRIVATE OpenCL ggml-opencl ${CMAKE_DL_LIBS})
endif()

if(ANDROID)
    add_library(subtitle_model_probe SHARED model_probe_jni.cpp local_translation_jni.cpp)
    target_link_libraries(subtitle_model_probe PRIVATE model_probe_engine)
    # Android 15+ devices may use 16-KiB memory pages.
    target_link_options(subtitle_model_probe PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")
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
endif()
