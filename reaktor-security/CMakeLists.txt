cmake_minimum_required(VERSION ${DEPE_CMAKE_MINIMUM})
include("../dependeasy/cmake/api.cmake")
reaktor_platform()
project(${DEPE_NATIVE_TARGET} LANGUAGES C CXX)
reaktor_configuration()

include("cmake/vendor.cmake")
reaktor_library(${PROJECT_NAME})
target_link_libraries(ReaktorSecurity PUBLIC mlspp)

option(RSEC_BUILD_TESTS "Build reaktor-security native tests" ON)
if(RSEC_BUILD_TESTS AND NOT ANDROID AND NOT iOS)
    include("cmake/tests.cmake")
endif()
