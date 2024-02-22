cmake_minimum_required(VERSION 3.22.1)
project(kmmFlatInvoker)


add_subdirectory(
        "../../" # pathToDirectory
        ${CMAKE_CURRENT_BINARY_DIR}/flatInvoker-build # pathForBuildDirectory
)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_VERBOSE_MAKEFILE ON)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
setup_mobile()

set(NODE_MODULES "../../tester-react/node_modules")
set(REACT_NATIVE "${NODE_MODULES}/react-native")

include_directories(
        ${REACT_NATIVE}/React
        ${REACT_NATIVE}/React/Base
        ${REACT_NATIVE}/ReactCommon/jsi
        ${REACT_NATIVE}/ReactCommon/jsi/jsi
        ${REACT_NATIVE}/ReactAndroid/src/main/java/com/facebook/react/turbomodule/core/jni
        ${REACT_NATIVE}/ReactCommon/callinvoker
)

include_directories(.)
file(GLOB droid "droid/*")
file(GLOB common "common/*")
file(GLOB darwin "darwin/*")


if(ANDROID)
    add_library(
            kmmFlatInvoker
            SHARED
            ${REACT_NATIVE}/ReactCommon/jsi/jsi/jsi.cpp
            ${droid}
            ${common}
    )

    find_package(fbjni REQUIRED CONFIG)
    find_library(log-lib log)

    target_link_libraries(
            kmmFlatInvoker
            PRIVATE flatbuffers
            android
            log
            fbjni::fbjni
    )
elseif(APPLE)
    add_library(
            Reaktor
            STATIC
            ${common}
            ${darwin}
            ${REACT_NATIVE}/ReactCommon/jsi/jsi/jsi.cpp
            #            fix this later
            ${flatbuffers_dir}/src/idl_parser.cpp
            ${flatbuffers_dir}/src/util.cpp
    )
    target_link_libraries(Reaktor PRIVATE flatbuffers)
    target_compile_options(Reaktor PRIVATE -fobjc-arc)
endif()
