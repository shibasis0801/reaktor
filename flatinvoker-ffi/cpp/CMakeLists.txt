cmake_minimum_required(VERSION 3.22.1)
include("../../root.cmake")
project(FlatInvokerFFI)

init()
configure_hermes()
fi_dependency(flatinvoker-core)

if(ANDROID)
    add_library(
            ${PROJECT_NAME}
            SHARED
            ${droid}
            ${common}
    )
elseif(IS_IOS)
    setup_mobile()
    add_library(
            ${PROJECT_NAME}
            STATIC
            ${common}
            ${darwin}
    )
else()
    message(STATUS "CLI Target")
    add_executable(
            ${PROJECT_NAME}
            ${common}
            ${main}
    )
endif()

target_link_libraries(${PROJECT_NAME} FlatInvokerCore hermes jsi)
target_include_directories(${PROJECT_NAME} PUBLIC .)