cmake_minimum_required(VERSION 3.22.1)
include("../../root.cmake")
project(FlatInvokerFFI)

init()
configure_hermes()
fi_dependency(flatinvoker-core)

if(ANDROID)
    setup_mobile()
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
elseif(IS_IOS)
    setup_mobile()
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
else()
    message(STATUS "CLI Target")
    add_executable(
            ${PROJECT_NAME}
            ${common}
            main/main.cpp
    )
    target_link_libraries(${PROJECT_NAME} FlatInvokerCore hermes jsi)
endif()

target_include_directories(
        ${PROJECT_NAME}
        PUBLIC
        .
)