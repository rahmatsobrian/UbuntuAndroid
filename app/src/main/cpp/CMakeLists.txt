cmake_minimum_required(VERSION 3.22.1)
project(ubuntu-pty C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -fvisibility=hidden")
# 16 KB page alignment (Android 15/16 compliance)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

add_library(ubuntu-pty SHARED pty.c)
target_link_libraries(ubuntu-pty log)
