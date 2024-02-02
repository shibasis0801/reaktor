cmake_minimum_required(VERSION 3.22.1)
project(flatInvoker)
# Common CMake File for subprojects

add_subdirectory(
        dependencies/flatbuffers
        ${CMAKE_CURRENT_BINARY_DIR}/flatbuffers-build
)
