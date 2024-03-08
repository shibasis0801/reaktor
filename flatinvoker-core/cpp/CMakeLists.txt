cmake_minimum_required(VERSION 3.22.1)
include("../../root.cmake")
project(FlatInvokerCore)
init()


if(ANDROID)
    add_library(
            FlatInvokerCore
            SHARED
            ${droid}
            ${common}
    )

    find_package(fbjni REQUIRED CONFIG)
    find_library(log-lib log)

    target_link_libraries(
            FlatInvokerCore
            PUBLIC
            flatbuffers
            android
            log
            fbjni::fbjni
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
    target_link_libraries(${PROJECT_NAME} PUBLIC flatbuffers)
    target_compile_options(${PROJECT_NAME} PUBLIC -fobjc-arc)
endif()
