cmake_minimum_required(VERSION 4.0)
include("../dependeasy/cmake/api.cmake")
reaktor_platform()
project(ReaktorSecurity LANGUAGES C CXX)
reaktor_configuration()

include("cmake/vendor.cmake")
reaktor_library(ReaktorSecurity)
target_link_libraries(ReaktorSecurity PUBLIC mlspp)

option(RSEC_BUILD_TESTS "Build reaktor-security native tests" ON)
if(RSEC_BUILD_TESTS AND NOT ANDROID AND NOT iOS)
    include("cmake/tests.cmake")
endif()
