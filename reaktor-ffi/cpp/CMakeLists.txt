cmake_minimum_required(VERSION 3.22.1)
include("../../root.cmake")
project(ReaktorFFI)

if(iOS)
    setup_ios()
endif()

init()
configure_hermes()
set(ffi_common ${common})

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
            ${ffi_common}
    )
    target_link_libraries(${PROJECT_NAME}
            PUBLIC
            fbjni::fbjni
            jsi
            hermes-engine::libhermes
    )
elseif(iOS)
    add_library(
            ${PROJECT_NAME}
            STATIC
            ${ffi_common}
            ${darwin}
    )
    target_link_libraries(${PROJECT_NAME}
            PUBLIC
            hermesvm
            jsi
    )
else()
    message(STATUS "CLI Target")
    add_executable(
            ${PROJECT_NAME}
            ${ffi_common}
            ${main}
    )
    target_link_libraries(${PROJECT_NAME}
            PUBLIC
            ReaktorFlexbuffer
            hermes
            jsi
    )
endif()

fi_link_dependencies(${PROJECT_NAME})
target_include_directories(${PROJECT_NAME} PUBLIC .)
