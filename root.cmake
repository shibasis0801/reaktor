# This file is imported in all modules/cpp folders. flatbuffers is cloned at root, reachable by ../../ from a module cmake.
# The reason to have this workaround is to have a common cmake in the root along with other build files.
set(FLATBUFFERS_BUILD_FLATC OFF)
set(FLATBUFFERS_BUILD_TESTS OFF)

function(setup_ios)
    if(NOT DEFINED sdk)
        set(sdk iphonesimulator)
    endif()
    execute_process(
            COMMAND xcrun --sdk ${sdk} --show-sdk-path
            OUTPUT_VARIABLE IOS_SDK_PATH
            OUTPUT_STRIP_TRAILING_WHITESPACE
    )

    set(CMAKE_SYSTEM_NAME iOS CACHE INTERNAL "")
    set(CMAKE_OSX_SYSROOT "${IOS_SDK_PATH}" CACHE INTERNAL "iOS SDK path")
    if(NOT DEFINED CMAKE_OSX_DEPLOYMENT_TARGET)
        set(CMAKE_OSX_DEPLOYMENT_TARGET "13.0" CACHE INTERNAL "iOS deployment target")
    endif()
    message(STATUS "iOS SDK path: ${CMAKE_OSX_SYSROOT}")

    set(CMAKE_OSX_ARCHITECTURES "arm64" CACHE INTERNAL "")
    set(CMAKE_POSITION_INDEPENDENT_CODE ON CACHE INTERNAL "")
    set(CMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED "NO" CACHE INTERNAL "")
    set(CMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_REQUIRED "NO" CACHE INTERNAL "")
    set(CMAKE_XCODE_ATTRIBUTE_DEVELOPMENT_TEAM "" CACHE INTERNAL "")
endfunction()



function(init)
    set(CMAKE_BUILD_TYPE Release)
    set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

    set(CMAKE_CXX_STANDARD 20 PARENT_SCOPE)
    set(CMAKE_VERBOSE_MAKEFILE ON PARENT_SCOPE)
    set(CMAKE_CXX_STANDARD_REQUIRED ON PARENT_SCOPE)

    file(GLOB_RECURSE droid "droid/*")
    file(GLOB_RECURSE common "common/*")
    file(GLOB_RECURSE darwin "darwin/*")
    file(GLOB_RECURSE main "main/*")

    set(droid ${droid} PARENT_SCOPE)
    set(common ${common} PARENT_SCOPE)
    set(darwin ${darwin} PARENT_SCOPE)
    set(main ${main} PARENT_SCOPE)

    if(ANDROID)
        add_link_options(
                "-Wl,-z,max-page-size=16384"
                "-Wl,-z,common-page-size=16384"
        )
    endif()

    if (APPLE)
#        target_compile_options(${PROJECT_NAME} PUBLIC -fobjc-arc) // need for objc source
    endif()

    fi_auto_dependencies()
endfunction()


# take parameters from the caller
function(fi_dependency name)
    add_subdirectory(
            ../../${name}/cpp
            ${CMAKE_CURRENT_BINARY_DIR}/${name}
    )
endfunction()

function(fi_auto_dependencies)
    if(NOT DEFINED REAKTOR_NATIVE_DEPENDENCY_SOURCE_DIRS OR NOT DEFINED REAKTOR_NATIVE_DEPENDENCY_TARGETS)
        return()
    endif()

    set(source_dirs ${REAKTOR_NATIVE_DEPENDENCY_SOURCE_DIRS})
    set(target_names ${REAKTOR_NATIVE_DEPENDENCY_TARGETS})
    set(project_names ${REAKTOR_NATIVE_DEPENDENCY_PROJECTS})

    list(LENGTH source_dirs source_count)
    if(source_count EQUAL 0)
        return()
    endif()

    math(EXPR last_index "${source_count} - 1")
    foreach(index RANGE ${last_index})
        list(GET source_dirs ${index} dependency_source_dir)
        list(GET target_names ${index} dependency_target_name)
        list(GET project_names ${index} dependency_project_name)
        if(NOT TARGET ${dependency_target_name})
            set(_saved_dependency_projects "${REAKTOR_NATIVE_DEPENDENCY_PROJECTS}")
            set(_saved_dependency_sources "${REAKTOR_NATIVE_DEPENDENCY_SOURCE_DIRS}")
            set(_saved_dependency_targets "${REAKTOR_NATIVE_DEPENDENCY_TARGETS}")
            set(REAKTOR_NATIVE_DEPENDENCY_PROJECTS "" CACHE STRING "" FORCE)
            set(REAKTOR_NATIVE_DEPENDENCY_SOURCE_DIRS "" CACHE STRING "" FORCE)
            set(REAKTOR_NATIVE_DEPENDENCY_TARGETS "" CACHE STRING "" FORCE)
            add_subdirectory(
                    "${dependency_source_dir}"
                    "${CMAKE_CURRENT_BINARY_DIR}/deps/${dependency_project_name}"
            )
            set(REAKTOR_NATIVE_DEPENDENCY_PROJECTS "${_saved_dependency_projects}" CACHE STRING "" FORCE)
            set(REAKTOR_NATIVE_DEPENDENCY_SOURCE_DIRS "${_saved_dependency_sources}" CACHE STRING "" FORCE)
            set(REAKTOR_NATIVE_DEPENDENCY_TARGETS "${_saved_dependency_targets}" CACHE STRING "" FORCE)
        endif()
        set_property(GLOBAL APPEND PROPERTY REAKTOR_NATIVE_DEPENDENCY_TARGETS_PROP ${dependency_target_name})
    endforeach()
endfunction()

function(fi_link_dependencies target)
    get_property(dependency_targets GLOBAL PROPERTY REAKTOR_NATIVE_DEPENDENCY_TARGETS_PROP)
    if(dependency_targets)
        list(REMOVE_DUPLICATES dependency_targets)
        target_link_libraries(${target} PUBLIC ${dependency_targets})
    endif()
endfunction()

function(configure_hermes)
    set(HERMES_SRC_DIR "../../.github_modules/hermes")
    set(HERMES_BUILD_DIR "../../.github_modules/hermes/debug")

    include_directories("${HERMES_SRC_DIR}/API")
    include_directories("${HERMES_SRC_DIR}/API/jsi")
    include_directories("${HERMES_SRC_DIR}/public")

    if(APPLE)
        if(NOT TARGET hermesvm)
            set(HERMES_ENABLE_TEST_SUITE OFF CACHE BOOL "" FORCE)
            set(HERMES_ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
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
    else()
        link_directories("${HERMES_BUILD_DIR}/API/hermes")
        link_directories("${HERMES_BUILD_DIR}/jsi")
    endif()
endfunction()

if (ANDROID AND DEFINED FBJNI_PREFAB_DIR AND NOT TARGET fbjni::fbjni)
    message(STATUS "Using extracted FBJNI at ${FBJNI_PREFAB_DIR}")
    add_library(fbjni::fbjni UNKNOWN IMPORTED GLOBAL)
    set_target_properties(fbjni::fbjni PROPERTIES
            IMPORTED_LOCATION ${FBJNI_PREFAB_DIR}/libs/android.${ANDROID_ABI}/libfbjni.so
            INTERFACE_INCLUDE_DIRECTORIES ${FBJNI_PREFAB_DIR}/include
    )
endif()

if (ANDROID AND DEFINED HERMES_PREFAB_DIR AND NOT TARGET hermes-engine::libhermes)
    message(STATUS "Using extracted HERMES at ${HERMES_PREFAB_DIR}")
    add_library(hermes-engine::libhermes UNKNOWN IMPORTED GLOBAL)
    set_target_properties(hermes-engine::libhermes PROPERTIES
            IMPORTED_LOCATION ${HERMES_PREFAB_DIR}/libs/android.${ANDROID_ABI}/libhermes.so
            INTERFACE_INCLUDE_DIRECTORIES ${HERMES_PREFAB_DIR}/include
    )
endif()
