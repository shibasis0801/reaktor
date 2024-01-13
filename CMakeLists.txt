cmake_minimum_required(VERSION 3.22.1)
project(flatInvoker)

add_subdirectory(
        flatbuffers
        ${CMAKE_CURRENT_BINARY_DIR}/flatbuffers-build
)
