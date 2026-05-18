cmake_minimum_required(VERSION 3.22.1)
include("../../root.cmake")

project(ReaktorSecurity LANGUAGES CXX)

if(iOS)
    setup_ios()
endif()

init()

option(RSEC_BUILD_TESTS "Build reaktor-security native tests" ON)

if(NOT ANDROID AND EXISTS "/opt/homebrew/opt/openssl")
    list(APPEND CMAKE_PREFIX_PATH "/opt/homebrew/opt/openssl")
    set(OPENSSL_ROOT_DIR "/opt/homebrew/opt/openssl" CACHE PATH "OpenSSL root" FORCE)
elseif(NOT ANDROID AND EXISTS "/usr/local/opt/openssl")
    list(APPEND CMAKE_PREFIX_PATH "/usr/local/opt/openssl")
    set(OPENSSL_ROOT_DIR "/usr/local/opt/openssl" CACHE PATH "OpenSSL root" FORCE)
endif()

set(JSON_BuildTests OFF CACHE BOOL "" FORCE)
set(JSON_Install OFF CACHE BOOL "" FORCE)
add_subdirectory(external/json EXCLUDE_FROM_ALL)
set(nlohmann_json_DIR "${CMAKE_CURRENT_BINARY_DIR}/external/json" CACHE PATH "nlohmann_json build-tree package" FORCE)

set(TESTING OFF CACHE BOOL "" FORCE)
set(DISABLE_PQ ON CACHE BOOL "" FORCE)
add_subdirectory(external/mlspp EXCLUDE_FROM_ALL)

add_library(rsec_mls_core STATIC
    rsec-mls-core/src/MlsEngine.cpp
)
target_include_directories(rsec_mls_core
    PUBLIC
        rsec-mls-core/include
)
target_link_libraries(rsec_mls_core
    PUBLIC
        mlspp
)

add_library(rsec_mls_capi STATIC
    rsec-mls-capi/src/rsec.cpp
)
target_include_directories(rsec_mls_capi
    PUBLIC
        rsec-mls-capi/include
)
target_link_libraries(rsec_mls_capi
    PUBLIC
        rsec_mls_core
)

if(ANDROID)
    add_library(${PROJECT_NAME} SHARED
        rsec-mls-capi/src/rsec.cpp
        ${droid}
    )
elseif(iOS)
    add_library(${PROJECT_NAME} STATIC
        rsec-mls-capi/src/rsec.cpp
        ${darwin}
    )
else()
    add_library(${PROJECT_NAME} STATIC
        rsec-mls-capi/src/rsec.cpp
        ${common}
    )
endif()
target_include_directories(${PROJECT_NAME}
    PUBLIC
        rsec-mls-capi/include
        rsec-mls-core/include
        .
)
target_link_libraries(${PROJECT_NAME}
    PUBLIC
        rsec_mls_core
)

if(ANDROID)
    find_library(log-lib log)
    target_link_libraries(${PROJECT_NAME}
        PUBLIC
            android
            ${log-lib}
    )
endif()

fi_link_dependencies(${PROJECT_NAME})

if(RSEC_BUILD_TESTS AND NOT ANDROID AND NOT iOS)
    set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
    set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
    add_subdirectory(external/googletest EXCLUDE_FROM_ALL)

    enable_testing()
    include(GoogleTest)

    add_executable(rsec_mls_core_tests
        tests/native/MlsEngineTest.cpp
        tests/native/RsecCapiTest.cpp
    )
    target_link_libraries(rsec_mls_core_tests
        PRIVATE
            rsec_mls_capi
            GTest::gtest_main
    )
    gtest_discover_tests(rsec_mls_core_tests)
endif()
