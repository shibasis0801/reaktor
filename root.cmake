# This file is imported in all modules/cpp folders. flatbuffers is cloned at root, reachable by ../../ from a module cmake.
# The reason to have this workaround is to have a common cmake in the root along with other build files.

function(setup_mobile)
    execute_process(
            COMMAND xcrun --sdk iphonesimulator --show-sdk-path
            OUTPUT_VARIABLE IOS_SDK_PATH
            OUTPUT_STRIP_TRAILING_WHITESPACE
    )

    set(CMAKE_OSX_SYSROOT "${IOS_SDK_PATH}" CACHE INTERNAL "iOS Simulator SDK path")
    message(STATUS "iOS SDK path: ${CMAKE_OSX_SYSROOT}")
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

    if (APPLE)
#        target_compile_options(${PROJECT_NAME} PUBLIC -fobjc-arc)
    endif()
endfunction()


# take parameters from the caller
function(fi_dependency name)
    add_subdirectory(
            ../../${name}/cpp
            ${CMAKE_CURRENT_BINARY_DIR}/${name}
    )
endfunction()

function(isIOS result)
    set(${result} FALSE PARENT_SCOPE)
    if(CMAKE_OSX_SYSROOT MATCHES "iphoneos" OR CMAKE_OSX_SYSROOT MATCHES "iphonesimulator")
        set(${result} TRUE PARENT_SCOPE)
    endif()
endfunction()

function(build_hermes)
    message(STATUS "Attempting to build Hermes...")
    execute_process(
            COMMAND cmake -G Ninja -DHERMES_BUILD_APPLE_FRAMEWORK=OFF -DCMAKE_BUILD_TYPE=Debug ${HERMES_SRC_DIR}
            WORKING_DIRECTORY ${HERMES_BUILD_DIR}
            RESULT_VARIABLE build_result
    )
    if (build_result EQUAL 0)
        execute_process(
                COMMAND ninja
                WORKING_DIRECTORY ${HERMES_BUILD_DIR}
                RESULT_VARIABLE build_result
        )
        if(build_result EQUAL 0)
            message(STATUS "Hermes build successful!")
        else()
            message(FATAL_ERROR "Hermes build failed. Please check the build output for details.")
        endif()
    else()
        message(FATAL_ERROR "CMake configuration for Hermes failed. Please check the configuration output for details.")
    endif()
endfunction()


function(configure_hermes)
    set(HERMES_SRC_DIR "../../.github_modules/hermes")
    set(HERMES_BUILD_DIR "../../.github_modules/hermes/debug")

    message(STATUS "Current Working Directory: ${CMAKE_CURRENT_LIST_DIR}")

    get_filename_component(HERMES_SRC "${HERMES_SRC_DIR}" ABSOLUTE)
    get_filename_component(HERMES_BUILD "${HERMES_BUILD_DIR}" ABSOLUTE)

    if (NOT EXISTS "${HERMES_SRC}/API/jsi/jsi/jsi.h")
        message(FATAL_ERROR "HERMES_SRC_DIR does not contain API/jsi/jsi/jsi.h")
    endif ()

    if (NOT EXISTS "${HERMES_BUILD}/bin/hermes${CMAKE_EXECUTABLE_SUFFIX}")
        build_hermes()
        if (NOT EXISTS "${HERMES_BUILD}/bin/hermes${CMAKE_EXECUTABLE_SUFFIX}")
            message(FATAL_ERROR "Hermes executable still not found after build attempt. Please investigate further.")
        endif ()
    endif ()

    include_directories("${HERMES_SRC_DIR}/API")
    include_directories("${HERMES_SRC_DIR}/API/jsi")
    include_directories("${HERMES_SRC_DIR}/public")

    link_directories("${HERMES_BUILD_DIR}/API/hermes")
    link_directories("${HERMES_BUILD_DIR}/jsi")
endfunction()
