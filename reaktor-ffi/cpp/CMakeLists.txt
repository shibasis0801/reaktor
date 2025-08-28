cmake_minimum_required(VERSION 3.22.1)
include("../../root.cmake")
project(ReaktorFFI)

init()
configure_hermes()
fi_dependency(reaktor-flexbuffer)

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
    target_link_libraries(${PROJECT_NAME} jsi hermes-engine::libhermes ReaktorFlexbuffer)
elseif(iOS)
    setup_ios()
    add_library(
            ${PROJECT_NAME}
            STATIC
            ${common}
            ${darwin}
    )
    target_link_libraries(${PROJECT_NAME} jsi ReaktorFlexbuffer hermes)
else()
    message(STATUS "CLI Target")
    add_executable(
            ${PROJECT_NAME}
            ${common}
            ${main}
    )
    target_link_libraries(${PROJECT_NAME} ReaktorFlexbuffer hermes jsi)
endif()

target_include_directories(${PROJECT_NAME} PUBLIC .)