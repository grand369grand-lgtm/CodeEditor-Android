# CMakeLists.txt for CodeEditor native library
# Builds:
#   libcode_runner.so  - Code execution (C++/Python)
#   libpty_support.so  - PTY terminal support (forkpty, like Termux)

cmake_minimum_required(VERSION 3.22.1)

project("code_editor_native")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# =====================================================================
# Library 1: code_runner - Code execution engine (existing)
# =====================================================================
add_library(
        code_runner
        SHARED
        native_runner.cpp
)

find_library(log-lib log)

target_link_libraries(
        code_runner
        ${log-lib}
)

target_compile_options(code_runner PRIVATE
        -O2
        -fno-rtti
        -fno-exceptions
        -Wall
        -Wextra
)

# =====================================================================
# Library 2: pty_support - PTY terminal support (Termux-style)
# This provides proper PTY handling via forkpty(), which is the core
# of what makes Termux's terminal work. Without this, shells get
# "can't find tty fd" errors because ProcessBuilder uses pipes.
#
# On Android NDK, forkpty is in libc (not a separate util library).
# If forkpty is not available, we implement it using openpty+fork.
# =====================================================================
add_library(
        pty_support
        SHARED
        pty_jni.cpp
)

# Android NDK: forkpty/openpty are in libc, not a separate util lib
# On some platforms, we need to link util for forkpty
# But on Android, everything is in libc
find_library(log-lib2 log)

# Try to find util library (for non-Android platforms)
# On Android NDK, this will be NOTFOUND, which is fine
find_library(util-lib util)

if(util-lib)
    target_link_libraries(pty_support ${log-lib2} ${util-lib})
else()
    # Android NDK - forkpty/openpty are in libc
    target_link_libraries(pty_support ${log-lib2})
endif()

target_compile_options(pty_support PRIVATE
        -O2
        -Wall
        -Wextra
        -Wno-missing-field-initializers
)
