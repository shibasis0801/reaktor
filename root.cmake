# This file is imported in all modules/cpp folders. flatbuffers is cloned at root, reachable by ../../ from a module cmake.
# The reason to have this workaround is to have a common cmake in the root along with other build files.
set(flatbuffers_dir ../../.github_modules/flatbuffers)
add_subdirectory(
        ../../.github_modules/flatbuffers
        ${CMAKE_CURRENT_BINARY_DIR}/fb-build
)


function(setup_mobile)
    execute_process(
            COMMAND xcrun --sdk iphonesimulator --show-sdk-path
            OUTPUT_VARIABLE IOS_SDK_PATH
            OUTPUT_STRIP_TRAILING_WHITESPACE
    )

    set(CMAKE_OSX_SYSROOT "${IOS_SDK_PATH}" CACHE INTERNAL "iOS Simulator SDK path")
    message(STATUS "iOS SDK path: ${CMAKE_OSX_SYSROOT}")
endfunction()

function(init)
    setup_mobile()

    set(CMAKE_CXX_STANDARD 20 PARENT_SCOPE)
    set(CMAKE_VERBOSE_MAKEFILE ON PARENT_SCOPE)
    set(CMAKE_CXX_STANDARD_REQUIRED ON PARENT_SCOPE)

    include_directories(.)
    file(GLOB_RECURSE droid "droid/*")
    file(GLOB_RECURSE common "common/*")
    file(GLOB_RECURSE darwin "darwin/*")

    set(droid ${droid} PARENT_SCOPE)
    set(common ${common} PARENT_SCOPE)
    set(darwin ${darwin} PARENT_SCOPE)

    if (APPLE)
#        target_compile_options(${PROJECT_NAME} PUBLIC -fobjc-arc)
    endif()
endfunction()