cmake_minimum_required(VERSION 3.22.1)
include("../../root.cmake")
project(FlatInvokerIpc)
init()

add_subdirectory(
        ../../flatinvoker-core
        ${CMAKE_CURRENT_BINARY_DIR}/flatinvoker-core
)

if(ANDROID)
    add_library(
            ${PROJECT_NAME}
            SHARED
            ${droid}
            ${common}
    )

    target_link_libraries(
            ${PROJECT_NAME}
            PUBLIC
            FlatInvokerCore
    )
elseif(APPLE)
    add_library(
            ${PROJECT_NAME}
            STATIC
            ${common}
            ${darwin}
            #            fix this later
            ${flatbuffers_dir}/src/idl_parser.cpp
            ${flatbuffers_dir}/src/util.cpp
    )
    target_link_libraries(
            ${PROJECT_NAME}
            PUBLIC
            flatbuffers
    )
endif()
