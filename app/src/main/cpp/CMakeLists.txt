# CMakeLists.txt for CodeEditor native library
# Builds the libcode_runner.so shared library used by JNI

cmake_minimum_required(VERSION 3.22.1)

project("code_runner")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add the native runner source file
add_library(
        code_runner
        SHARED
        native_runner.cpp
)

# Find and link Android libraries
find_library(log-lib log)

# Link libraries
target_link_libraries(
        code_runner
        ${log-lib}
)

# Set compile options for optimization
target_compile_options(code_runner PRIVATE
        -O2
        -fno-rtti
        -fno-exceptions
        -Wall
        -Wextra
)
