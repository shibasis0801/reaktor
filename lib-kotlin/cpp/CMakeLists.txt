cmake_minimum_required(VERSION 3.22.1)
project(kmmFlatInvoker)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_VERBOSE_MAKEFILE ON)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_subdirectory(
        "../../"
        ${CMAKE_CURRENT_BINARY_DIR}/flatInvoker-build
)

file(GLOB droid "droid/*")
#file(GLOB common "*")
include_directories(.)


add_library(
        kmmFlatInvoker
        SHARED
        ${droid}
        ${common}
)

find_package(fbjni REQUIRED CONFIG)
find_library(log-lib log)

target_link_libraries(
        kmmFlatInvoker
        PRIVATE flatInvoker
        android
        log
        fbjni::fbjni
)
