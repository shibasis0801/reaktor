cmake_minimum_required(VERSION 3.22.1)
include("../../root.cmake")
project(FlatInvokerFFI)

init()
configure_hermes()
fi_dependency(flatinvoker-core)

if(ANDROID)
    add_library(
            jsi
            SHARED
            ../../.github_modules/hermes/API/jsi/jsi/jsi.cpp
    )
    target_include_directories(jsi PUBLIC ../../.github_modules/hermes/API/jsi)


    add_library(
            ${PROJECT_NAME}
            SHARED
            ${droid}
            ${common}
    )
    find_package(hermes-engine REQUIRED CONFIG)
    target_link_libraries(${PROJECT_NAME} jsi hermes-engine::libhermes FlatInvokerCore)
elseif(iOS)
    setup_ios()
    add_library(
            ${PROJECT_NAME}
            STATIC
            ${common}
            ${darwin}
    )
    target_link_libraries(${PROJECT_NAME} jsi FlatInvokerCore hermes)
else()
    message(STATUS "CLI Target")
    add_executable(
            ${PROJECT_NAME}
            ${common}
            ${main}
    )
    target_link_libraries(${PROJECT_NAME} FlatInvokerCore hermes jsi)
endif()

target_include_directories(${PROJECT_NAME} PUBLIC .)