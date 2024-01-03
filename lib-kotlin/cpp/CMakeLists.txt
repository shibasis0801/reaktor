cmake_minimum_required(VERSION 3.22.1)
project(kmmFlatInvoker)

execute_process(
        COMMAND xcrun --sdk iphonesimulator --show-sdk-path
        OUTPUT_VARIABLE IOS_SDK_PATH
        OUTPUT_STRIP_TRAILING_WHITESPACE
    )
    
set(CMAKE_OSX_SYSROOT "${IOS_SDK_PATH}" CACHE INTERNAL "iOS Simulator SDK path")
message(STATUS "iOS SDK path: ${CMAKE_OSX_SYSROOT}")


# Common settings
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_VERBOSE_MAKEFILE ON)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
include_directories(.)

# Add subdirectory for flatInvoker
add_subdirectory(
        "../../" # pathToDirectory
        ${CMAKE_CURRENT_BINARY_DIR}/flatInvoker-build # pathForBuildDirectory
)

# Platform-specific settings
if(ANDROID)
    # Android specific build settings
    file(GLOB droid "droid/*")
    add_library(
            kmmFlatInvoker
            SHARED
            ${droid}
            ${common} # Uncomment or define 'common' if needed for Android
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
elseif(APPLE)
    # iOS specific build settings
    add_library(
            Reaktor 
            STATIC 
            Reaktor.mm # Replace with your iOS source files
    )

    target_include_directories(Reaktor PRIVATE ../../flatbuffers/include/)

    set_target_properties(Reaktor PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED YES
        CXX_EXTENSIONS NO
    )

    
#     set(CMAKE_XCODE_ATTRIBUTE_ARCHS "arm64")
#     set(CMAKE_XCODE_ATTRIBUTE_ONLY_ACTIVE_ARCH NO)
#     set(CMAKE_XCODE_ATTRIBUTE_VALID_ARCHS "arm64")
#     set(CMAKE_XCODE_ATTRIBUTE_SUPPORTED_PLATFORMS "iphonesimulator")
#     set(CMAKE_SYSTEM_PROCESSOR arm64) # or x86_64 for Intel-based Macs
#     set(CMAKE_OSX_ARCHITECTURES arm64) # or x86_64 for Intel-based Macs
#     set(CMAKE_XCODE_EFFECTIVE_PLATFORMS "-iphonesimulator")

    # iOS Compiler flags
    target_compile_options(Reaktor PRIVATE -fobjc-arc)
endif()
