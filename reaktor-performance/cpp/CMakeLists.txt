cmake_minimum_required(VERSION 3.22.1)
include("../../root.cmake")
project(ReaktorPerformance)

init()

if(ANDROID)
    add_library(${PROJECT_NAME} SHARED common/reaktor_perf_timer.cpp)
elseif(APPLE)
    setup_ios()
    add_library(${PROJECT_NAME} STATIC common/reaktor_perf_timer.cpp)
else()
    add_library(${PROJECT_NAME} STATIC common/reaktor_perf_timer.cpp)
endif()

target_include_directories(${PROJECT_NAME} PUBLIC . common)
target_compile_features(${PROJECT_NAME} PUBLIC cxx_std_17)
