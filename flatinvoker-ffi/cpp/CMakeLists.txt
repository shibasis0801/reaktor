cmake_minimum_required(VERSION 3.22.1)
include("../../root.cmake")
project(FlatInvokerFFI)
init()

add_subdirectory(
        ../../flatinvoker-core/cpp
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
    )
    target_link_libraries(
            ${PROJECT_NAME}
            PUBLIC
            FlatInvokerCore
    )
endif()

target_include_directories(
        ${PROJECT_NAME}
        PUBLIC
        .
)
