function(configure_hermes)
    set(HERMES_SRC_DIR "${REAKTOR_SOURCE_ROOT}/.github_modules/hermes")
    if(NOT DEFINED HERMES_BUILD_DIR)
        set(HERMES_BUILD_DIR "${REAKTOR_SOURCE_ROOT}/build/dependeasy/tools/hermes")
    endif()

    include_directories("${HERMES_SRC_DIR}/API")
    include_directories("${HERMES_SRC_DIR}/API/jsi")
    include_directories("${HERMES_SRC_DIR}/public")

    if(NOT ANDROID)
        if(NOT TARGET hermesvm)
            set(HERMES_ENABLE_TEST_SUITE OFF CACHE BOOL "" FORCE)
            set(HERMES_ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
            set(HERMES_ENABLE_NAPI OFF CACHE BOOL "" FORCE)
            set(HERMES_ENABLE_DEBUGGER OFF CACHE BOOL "" FORCE)
            set(HERMES_ENABLE_INTL OFF CACHE BOOL "" FORCE)
            set(HERMES_BUILD_APPLE_FRAMEWORK OFF CACHE BOOL "" FORCE)
            set(HERMES_BUILD_SHARED_JSI OFF CACHE BOOL "" FORCE)
            set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
            get_filename_component(HERMES_IMPORT_HOST_COMPILERS "${HERMES_BUILD_DIR}/ImportHostCompilers.cmake" ABSOLUTE)
            set(IMPORT_HOST_COMPILERS "${HERMES_IMPORT_HOST_COMPILERS}" CACHE FILEPATH "" FORCE)
            add_subdirectory(
                    "${HERMES_SRC_DIR}"
                    "${CMAKE_CURRENT_BINARY_DIR}/deps/hermes"
            )
        endif()
    endif()
endfunction()
