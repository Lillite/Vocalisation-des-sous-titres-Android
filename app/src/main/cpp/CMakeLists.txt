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
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
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

add_library(model_probe_engine STATIC model_probe_engine.cpp local_model_backend.cpp local_translation_engine.cpp)
target_compile_features(model_probe_engine PUBLIC cxx_std_17)
target_include_directories(model_probe_engine PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(model_probe_engine PUBLIC llama)

if(ANDROID)
    add_library(subtitle_model_probe SHARED model_probe_jni.cpp local_translation_jni.cpp)
    target_link_libraries(subtitle_model_probe PRIVATE model_probe_engine)
    # Android 15+ devices may use 16-KiB memory pages.
    target_link_options(subtitle_model_probe PRIVATE "-Wl,-z,max-page-size=16384")
else()
    # Actual host inference test, not an Android stub or performance claim.
    add_executable(model-probe-smoke tests/model_probe_smoke.cpp)
    target_link_libraries(model-probe-smoke PRIVATE model_probe_engine)
    add_executable(local-translation-smoke tests/local_translation_smoke.cpp)
    target_link_libraries(local-translation-smoke PRIVATE model_probe_engine)
endif()
