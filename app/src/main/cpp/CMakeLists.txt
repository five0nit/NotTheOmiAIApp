cmake_minimum_required(VERSION 3.22)
project(nottheomi_whisper LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# Upstream is a verified, immutable library input, not a package installer.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "Portable Android arm64 baseline" FORCE)
foreach(option
    WHISPER_BUILD_TESTS WHISPER_BUILD_EXAMPLES WHISPER_BUILD_SERVER WHISPER_CURL
    WHISPER_SDL2 WHISPER_COREML WHISPER_OPENVINO WHISPER_VITISAI
    WHISPER_USE_SYSTEM_GGML WHISPER_USE_SYSTEM_LLAMA WHISPER_COMMON_FFMPEG
    GGML_BUILD_TESTS GGML_BUILD_EXAMPLES GGML_NATIVE GGML_CCACHE GGML_LTO
    GGML_OPENMP GGML_OPENMP_FETCH GGML_BLAS GGML_ACCELERATE GGML_LLAMAFILE
    GGML_CPU_KLEIDIAI GGML_CPU_HBM GGML_CPU_ALL_VARIANTS GGML_BACKEND_DL
    GGML_METAL GGML_CUDA GGML_HIP GGML_MUSA GGML_VULKAN GGML_RPC GGML_SYCL
    GGML_OPENCL GGML_OPENVINO GGML_WEBGPU GGML_HEXAGON GGML_ZDNN GGML_ZENDNN
    GGML_VIRTGPU GGML_VIRTGPU_BACKEND GGML_ET GGML_ET_SYSEMU
    GGML_SSE42 GGML_AVX GGML_AVX2 GGML_AVX_VNNI GGML_BMI2 GGML_FMA GGML_F16C
    GGML_AVX512 GGML_AVX512_VBMI GGML_AVX512_VNNI GGML_AVX512_BF16
    GGML_AMX_TILE GGML_AMX_INT8 GGML_AMX_BF16)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
set(FETCHCONTENT_FULLY_DISCONNECTED ON CACHE BOOL "No dependency fetching during builds" FORCE)
if(NOT WHISPER_SOURCE OR NOT EXISTS "${WHISPER_SOURCE}/include/whisper.h")
    message(FATAL_ERROR "Set WHISPER_SOURCE to the hash-verified pinned source")
endif()

# Remove build-root strings from release artifacts and prohibit runtime lookup
# of additional GPU/network backends. Build scripts also fix SOURCE_DATE_EPOCH.
get_filename_component(REPOSITORY_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
add_compile_options("-ffile-prefix-map=${REPOSITORY_ROOT}=."
                    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=./native-build"
                    -fvisibility=hidden -fvisibility-inlines-hidden)
add_subdirectory("${WHISPER_SOURCE}" whisper-build)
add_library(nottheomi-whisper SHARED whisper_jni.cpp)
target_link_libraries(nottheomi-whisper PRIVATE whisper)
target_compile_options(nottheomi-whisper PRIVATE -Wall -Wextra -Werror)
target_link_options(nottheomi-whisper PRIVATE -Wl,--exclude-libs,ALL
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map"
    -Wl,-z,defs -Wl,-z,relro -Wl,-z,now -Wl,-z,noexecstack)
set_target_properties(nottheomi-whisper PROPERTIES
    LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/exports.map")

option(NOTTHEOMI_HOST_TEST "Build host JNI for safety tests, never packaged" OFF)
if(ANDROID)
    if(NOT ANDROID_ABI MATCHES "^(arm64-v8a|x86_64)$")
        message(FATAL_ERROR "Supported distributed ABIs: arm64-v8a, x86_64")
    endif()
    # Android 15 16KiB page compatibility; libc++ is statically linked by the script.
    target_link_options(nottheomi-whisper PRIVATE -Wl,-z,max-page-size=16384)
elseif(NOTTHEOMI_HOST_TEST)
    find_package(JNI REQUIRED)
    target_include_directories(nottheomi-whisper PRIVATE ${JNI_INCLUDE_DIRS})
else()
    message(FATAL_ERROR "Android NDK required; host builds require NOTTHEOMI_HOST_TEST")
endif()
