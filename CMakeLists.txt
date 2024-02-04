cmake_minimum_required(VERSION 3.22.1)
project(flatInvoker)
# Common CMake File for subprojects

# Set the flatbuffers directory path to a variable and ensure it's an absolute path
get_filename_component(flatbuffers_dir "${CMAKE_CURRENT_SOURCE_DIR}/.github_modules/flatbuffers" ABSOLUTE)
set(flatbuffers_dir "${flatbuffers_dir}" CACHE PATH "Path to the flatbuffers directory" FORCE)

add_subdirectory(
        ${flatbuffers_dir}
        ${CMAKE_CURRENT_BINARY_DIR}/flatbuffers-build
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


