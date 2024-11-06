cmake_minimum_required(VERSION 3.22.1)
include("../../root.cmake")
project(FlatInvokerCore)

set(flatbuffers_dir ../../.github_modules/flatbuffers)
add_subdirectory(
        ../../.github_modules/flatbuffers
        ${CMAKE_CURRENT_BINARY_DIR}/fb-build
)

init()

if(ANDROID)
    add_library(
            ${PROJECT_NAME}
            SHARED
            ${droid}
            ${common}
    )

    find_package(fbjni REQUIRED CONFIG)
    find_library(log-lib log)

    target_link_libraries(
            ${PROJECT_NAME}
            PUBLIC
            flatbuffers
            android
            log
            fbjni::fbjni
    )
elseif(APPLE)
    setup_mobile(${SDK_NAME} ${ARCH})
    add_library(
            ${PROJECT_NAME}
            STATIC
            ${common}
            ${darwin}
            #            fix this later
            ${flatbuffers_dir}/src/idl_parser.cpp
            ${flatbuffers_dir}/src/util.cpp
    )
    target_compile_options(${PROJECT_NAME} PUBLIC -fobjc-arc)
    target_link_libraries(${PROJECT_NAME} PUBLIC flatbuffers)
endif()

target_include_directories(
        ${PROJECT_NAME}
        PUBLIC
        ${flatbuffers_dir}/include
        .
)
# add optimization to O3
#target_compile_options(${PROJECT_NAME} PUBLIC -O3)