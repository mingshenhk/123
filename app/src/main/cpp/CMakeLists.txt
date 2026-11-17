cmake_minimum_required(VERSION 3.22)
project(nitan_gguf LANGUAGES C CXX)

include(FetchContent)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Keep CPU inference optimized in the installable debug build.
add_compile_options($<$<CONFIG:Debug>:-O3>)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "x86_64")
    # Android's x86_64 baseline includes SSE 4.2 and does not require AVX.
    foreach(feature AVX AVX2 BMI2 F16C FMA)
        set(GGML_${feature} OFF CACHE BOOL "" FORCE)
    endforeach()
endif()

# b11960 is the minimum llama.cpp version documented by Liquid AI for this model.
FetchContent_Declare(llama_cpp
    URL https://codeload.github.com/ggml-org/llama.cpp/tar.gz/8642541b255bfb5dc39cd351a178be09013a55d6
    URL_HASH SHA256=1688a167a8edd9499cdd0c19b8bd044f0fa184b466981977bbf2222d884953a4
)
FetchContent_MakeAvailable(llama_cpp)

add_library(nitan_gguf SHARED local_model.cpp)
target_compile_features(nitan_gguf PRIVATE cxx_std_17)
target_link_libraries(nitan_gguf PRIVATE llama log)
