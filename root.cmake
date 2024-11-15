# This file is imported in all modules/cpp folders. flatbuffers is cloned at root, reachable by ../../ from a module cmake.
# The reason to have this workaround is to have a common cmake in the root along with other build files.
set(FLATBUFFERS_BUILD_FLATC OFF)
set(FLATBUFFERS_BUILD_TESTS OFF)
option(iOS "Building for iOS" OFF)

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
    message(STATUS "iOS SDK path: ${CMAKE_OSX_SYSROOT}")

    set(CMAKE_OSX_ARCHITECTURES "arm64" CACHE INTERNAL "")
    set(CMAKE_POSITION_INDEPENDENT_CODE ON CACHE INTERNAL "")

    set(IOS FALSE PARENT_SCOPE)
    if(APPLE)
       if(CMAKE_OSX_SYSROOT MATCHES "iphoneos" OR CMAKE_OSX_SYSROOT MATCHES "iphonesimulator")
                set(IOS TRUE PARENT_SCOPE)
        endif()
    endif()
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

function(configure_hermes)
    set(HERMES_SRC_DIR "../../.github_modules/hermes")
    set(HERMES_BUILD_DIR "../../.github_modules/hermes/debug")

    get_filename_component(HERMES_SRC "${HERMES_SRC_DIR}" ABSOLUTE)
    get_filename_component(HERMES_BUILD "${HERMES_BUILD_DIR}" ABSOLUTE)

    include_directories("${HERMES_SRC_DIR}/API")
    include_directories("${HERMES_SRC_DIR}/API/jsi")
    include_directories("${HERMES_SRC_DIR}/public")

    link_directories("${HERMES_BUILD_DIR}/API/hermes")
    link_directories("${HERMES_BUILD_DIR}/jsi")
endfunction()
