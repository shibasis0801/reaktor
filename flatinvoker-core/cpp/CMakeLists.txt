cmake_minimum_required(VERSION 3.22.1)
project(kmmFlatInvoker)


execute_process(
        COMMAND xcrun --sdk iphonesimulator --show-sdk-path
        OUTPUT_VARIABLE IOS_SDK_PATH
        OUTPUT_STRIP_TRAILING_WHITESPACE
)

set(CMAKE_OSX_SYSROOT "${IOS_SDK_PATH}" CACHE INTERNAL "iOS Simulator SDK path")
message(STATUS "iOS SDK path: ${CMAKE_OSX_SYSROOT}")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_VERBOSE_MAKEFILE ON)
set(CMAKE_CXX_STANDARD_REQUIRED ON)


add_subdirectory(
        "../../" # pathToDirectory
        ${CMAKE_CURRENT_BINARY_DIR}/flatInvoker-build # pathForBuildDirectory
)
include_directories(.)
file(GLOB droid "droid/*")
file(GLOB common "common/*")
file(GLOB darwin "darwin/*")


if(ANDROID)
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
            PRIVATE flatbuffers
            android
            log
            fbjni::fbjni
    )
elseif(APPLE)
    add_library(
            Reaktor
            STATIC
            ${common}
            ${darwin}
#            fix this later
            ../../.github_modules/flatbuffers/src/idl_parser.cpp
            ../../.github_modules/flatbuffers/src/util.cpp
    )
    target_link_libraries(Reaktor PRIVATE flatbuffers)
    target_compile_options(Reaktor PRIVATE -fobjc-arc)
endif()
