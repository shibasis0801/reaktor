cmake_minimum_required(VERSION 3.22.1)

project(reaktor)

#message("SHIBASIS PATNAIK: $ENV{NODE_MODULES}")

set(NODE_MODULES "../../tester-react/node_modules")
set(REACT_NATIVE "${NODE_MODULES}/react-native")

include_directories(
        ${REACT_NATIVE}/React
        ${REACT_NATIVE}/React/Base
        ${REACT_NATIVE}/ReactCommon/jsi
        ${REACT_NATIVE}/ReactCommon/jsi/jsi
        ${REACT_NATIVE}/ReactAndroid/src/main/java/com/facebook/react/turbomodule/core/jni
        ${REACT_NATIVE}/ReactCommon/callinvoker
        .
)

add_subdirectory(
        "../../" # pathToDirectory
        ${CMAKE_CURRENT_BINARY_DIR}/flatInvoker-build # pathForBuildDirectory
)


set(CMAKE_VERBOSE_MAKEFILE ON)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

file(GLOB JNI_SRC "droid/*")
file(GLOB TYPE_SRC "types/*")
file(GLOB MODULE_SRC "modules/*")
file(GLOB NATIVE_SRC "native/*")
file(GLOB COMMON_SRC "*")
file(GLOB JNI_BINDINGS "droid/bindings/*")

add_compile_options(-Wall -Werror -fexceptions -frtti -fPIC)
add_library(
        reaktor
        SHARED
        ${JNI_BINDINGS}
        ${JNI_SRC}
        ${TYPE_SRC}
        ${MODULE_SRC}
        ${COMMON_SRC}
        ${NATIVE_SRC}
        ${REACT_NATIVE}/ReactCommon/jsi/jsi/jsi.cpp
        ${REACT_NATIVE}/ReactAndroid/src/main/java/com/facebook/react/turbomodule/core/jni/ReactCommon/CallInvokerHolder.cpp
)

set_target_properties(
        reaktor PROPERTIES
        CXX_STANDARD 20
        CXX_EXTENSIONS OFF
        POSITION_INDEPENDENT_CODE ON
)

find_library(
        log-lib
        log
)

find_package(fbjni REQUIRED CONFIG)

target_link_libraries(
        reaktor
        android
        flatbuffers
        fbjni::fbjni
        ${log-lib}
)

# todo depend on original cmake
# todo React 72 allows CMake based configuration.
# todo After completing functionality, add support for react 72 with jsi::ArrayBuffer and ts-flatbuffer support
#cmake_minimum_required(VERSION 3.22.1)
#
#project(reaktor)
#
#set(NODE_MODULES "../../node_modules")
#set(REACT_NATIVE "${NODE_MODULES}/react-native")
#
#
#set(CMAKE_VERBOSE_MAKEFILE ON)
#set(CMAKE_CXX_STANDARD 17)
#set(CMAKE_CXX_STANDARD_REQUIRED ON)
#
#file(GLOB JNI_SRC "droid/*")
#file(GLOB TYPE_SRC "types/*")
#file(GLOB MODULE_SRC "modules/*")
#file(GLOB COMMON_SRC "*")
#file(GLOB JNI_BINDINGS "droid/bindings/*")
#
#include_directories(
#        ${REACT_NATIVE}/React
#        ${REACT_NATIVE}/React/Base
#        ${REACT_NATIVE}/ReactCommon
#        ${REACT_NATIVE}/ReactCommon/jsi
#        ${REACT_NATIVE}/ReactCommon/callinvoker
#        ${REACT_NATIVE}/ReactCommon/runtimeexecutor
#        ${REACT_NATIVE}/ReactCommon/yoga
#        ${REACT_NATIVE}/ReactAndroid/src/main/jni/react/turbomodule
#        ${REACT_NATIVE}/ReactAndroid/src/main/jni/react/turbomodule/ReactCommon
#        .
#)
#
#add_compile_options(-Wall -Werror -fexceptions -frtti -fPIC)
#add_library(
#        reaktor
#        SHARED
#        ${JNI_BINDINGS}
#        ${JNI_SRC}
#        ${TYPE_SRC}
#        ${MODULE_SRC}
#        ${COMMON_SRC}
#        ${REACT_NATIVE}/ReactCommon/jsi/jsi/jsi.cpp
#)
#
#include("${NODE_MODULES}/react-native/ReactAndroid/cmake-utils/folly-flags.cmake")
#add_compile_options(${folly_FLAGS})
#find_package(fbjni REQUIRED CONFIG)
#find_package(ReactAndroid REQUIRED CONFIG)
#
#set_target_properties(
#        reaktor PROPERTIES
#        CXX_STANDARD 17
#        CXX_EXTENSIONS OFF
#        POSITION_INDEPENDENT_CODE ON
#)
#
#find_library(
#        log-lib
#        log
#)
#
#target_link_libraries(
#        reaktor
#        android
#        ${log-lib}
#        ReactAndroid::folly_runtime
#        ReactAndroid::glog
#        ReactAndroid::jsi
#        ReactAndroid::reactnativejni
#        fbjni::fbjni
#)