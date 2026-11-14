# Super-resolution in the reader: Real-ESRGAN models run with ncnn (Vulkan
# on the GPU, or the CPU). ncnn's prebuilt Android libraries are fetched
# and checked by Gradle (fetchNcnn) into NCNN_ROOT: the shared library,
# which carries its own C++ runtime.
cmake_minimum_required(VERSION 3.22.1)
project(aenhtsr CXX)

if(NOT DEFINED NCNN_ROOT)
    message(FATAL_ERROR "NCNN_ROOT is not set (the fetchNcnn Gradle task sets it)")
endif()
set(ncnn_DIR ${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(aenhtsr SHARED sr.cpp)
target_compile_options(aenhtsr PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(aenhtsr PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
target_link_libraries(aenhtsr ncnn jnigraphics android log)
