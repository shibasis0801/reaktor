function(init)
    if(NOT CMAKE_BUILD_TYPE)
        set(CMAKE_BUILD_TYPE Release PARENT_SCOPE)
    endif()
    set(CMAKE_EXPORT_COMPILE_COMMANDS ON PARENT_SCOPE)

    if(NOT CMAKE_CXX_STANDARD)
        file(STRINGS "${DEPE_CMAKE_DIRECTORY}/../src/main/kotlin/dev/shibasis/dependeasy/toolchain/ToolchainVersions.kt"
            cpp_standard REGEX "const val CppStandard =")
        string(REGEX REPLACE ".*const val CppStandard = \"([0-9]+)\".*" "\\1" cpp_standard "${cpp_standard}")
        set(CMAKE_CXX_STANDARD "${cpp_standard}")
    endif()
    set(CMAKE_CXX_STANDARD "${CMAKE_CXX_STANDARD}" PARENT_SCOPE)
    set(CMAKE_CXX_EXTENSIONS OFF PARENT_SCOPE)
    set(CMAKE_VERBOSE_MAKEFILE ON PARENT_SCOPE)
    set(CMAKE_CXX_STANDARD_REQUIRED ON PARENT_SCOPE)

    if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/src/commonMain/cpp")
        file(GLOB_RECURSE common CONFIGURE_DEPENDS "src/commonMain/cpp/*.cpp" "src/commonMain/cpp/*.c")
        file(GLOB_RECURSE droid CONFIGURE_DEPENDS "src/androidMain/cpp/*.cpp" "src/androidMain/cpp/*.c")
        file(GLOB_RECURSE darwin CONFIGURE_DEPENDS "src/iosMain/cpp/*.cpp" "src/iosMain/cpp/*.mm" "src/iosMain/cpp/*.m")
        set(main "")
    else()
        file(GLOB_RECURSE droid CONFIGURE_DEPENDS "droid/*")
        file(GLOB_RECURSE common CONFIGURE_DEPENDS "common/*")
        file(GLOB_RECURSE darwin CONFIGURE_DEPENDS "darwin/*")
        file(GLOB_RECURSE main CONFIGURE_DEPENDS "main/*")
    endif()

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

    fi_auto_dependencies()
endfunction()
