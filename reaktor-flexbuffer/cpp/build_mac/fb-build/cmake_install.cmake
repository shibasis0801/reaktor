# Install script for directory: /Users/ovd/dev/.claude/worktrees/musing-northcutt/flatInvoker/.github_modules/flatbuffers

# Set the install prefix
if(NOT DEFINED CMAKE_INSTALL_PREFIX)
  set(CMAKE_INSTALL_PREFIX "/usr/local")
endif()
string(REGEX REPLACE "/$" "" CMAKE_INSTALL_PREFIX "${CMAKE_INSTALL_PREFIX}")

# Set the install configuration name.
if(NOT DEFINED CMAKE_INSTALL_CONFIG_NAME)
  if(BUILD_TYPE)
    string(REGEX REPLACE "^[^A-Za-z0-9_]+" ""
           CMAKE_INSTALL_CONFIG_NAME "${BUILD_TYPE}")
  else()
    set(CMAKE_INSTALL_CONFIG_NAME "")
  endif()
  message(STATUS "Install configuration: \"${CMAKE_INSTALL_CONFIG_NAME}\"")
endif()

# Set the component getting installed.
if(NOT CMAKE_INSTALL_COMPONENT)
  if(COMPONENT)
    message(STATUS "Install component: \"${COMPONENT}\"")
    set(CMAKE_INSTALL_COMPONENT "${COMPONENT}")
  else()
    set(CMAKE_INSTALL_COMPONENT)
  endif()
endif()

# Is this installation the result of a crosscompile?
if(NOT DEFINED CMAKE_CROSSCOMPILING)
  set(CMAKE_CROSSCOMPILING "FALSE")
endif()

# Set path to fallback-tool for dependency-resolution.
if(NOT DEFINED CMAKE_OBJDUMP)
  set(CMAKE_OBJDUMP "/usr/bin/objdump")
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/include" TYPE DIRECTORY FILES "/Users/ovd/dev/.claude/worktrees/musing-northcutt/flatInvoker/.github_modules/flatbuffers/include/flatbuffers")
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/flatbuffers" TYPE FILE FILES
    "/Users/ovd/dev/.claude/worktrees/musing-northcutt/flatInvoker/.github_modules/flatbuffers/CMake/flatbuffers-config.cmake"
    "/Users/ovd/dev/.claude/worktrees/musing-northcutt/flatInvoker/.github_modules/flatbuffers/CMake/BuildFlatBuffers.cmake"
    "/Users/ovd/dev/.claude/worktrees/musing-northcutt/flatInvoker/reaktor-flexbuffer/cpp/build_mac/fb-build/flatbuffers-config-version.cmake"
    )
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib" TYPE STATIC_LIBRARY FILES "/Users/ovd/dev/.claude/worktrees/musing-northcutt/flatInvoker/reaktor-flexbuffer/cpp/build_mac/fb-build/libflatbuffers.a")
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libflatbuffers.a" AND
     NOT IS_SYMLINK "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libflatbuffers.a")
    execute_process(COMMAND "/usr/bin/ranlib" "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libflatbuffers.a")
  endif()
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/flatbuffers/FlatBuffersTargets.cmake")
    file(DIFFERENT _cmake_export_file_changed FILES
         "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/flatbuffers/FlatBuffersTargets.cmake"
         "/Users/ovd/dev/.claude/worktrees/musing-northcutt/flatInvoker/reaktor-flexbuffer/cpp/build_mac/fb-build/CMakeFiles/Export/71b83468706c459fb1a02b1113714617/FlatBuffersTargets.cmake")
    if(_cmake_export_file_changed)
      file(GLOB _cmake_old_config_files "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/flatbuffers/FlatBuffersTargets-*.cmake")
      if(_cmake_old_config_files)
        string(REPLACE ";" ", " _cmake_old_config_files_text "${_cmake_old_config_files}")
        message(STATUS "Old export file \"$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/flatbuffers/FlatBuffersTargets.cmake\" will be replaced.  Removing files [${_cmake_old_config_files_text}].")
        unset(_cmake_old_config_files_text)
        file(REMOVE ${_cmake_old_config_files})
      endif()
      unset(_cmake_old_config_files)
    endif()
    unset(_cmake_export_file_changed)
  endif()
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/flatbuffers" TYPE FILE FILES "/Users/ovd/dev/.claude/worktrees/musing-northcutt/flatInvoker/reaktor-flexbuffer/cpp/build_mac/fb-build/CMakeFiles/Export/71b83468706c459fb1a02b1113714617/FlatBuffersTargets.cmake")
  if(CMAKE_INSTALL_CONFIG_NAME MATCHES "^()$")
    file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/flatbuffers" TYPE FILE FILES "/Users/ovd/dev/.claude/worktrees/musing-northcutt/flatInvoker/reaktor-flexbuffer/cpp/build_mac/fb-build/CMakeFiles/Export/71b83468706c459fb1a02b1113714617/FlatBuffersTargets-noconfig.cmake")
  endif()
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/pkgconfig" TYPE FILE FILES "/Users/ovd/dev/.claude/worktrees/musing-northcutt/flatInvoker/reaktor-flexbuffer/cpp/build_mac/fb-build/flatbuffers.pc")
endif()

string(REPLACE ";" "\n" CMAKE_INSTALL_MANIFEST_CONTENT
       "${CMAKE_INSTALL_MANIFEST_FILES}")
if(CMAKE_INSTALL_LOCAL_ONLY)
  file(WRITE "/Users/ovd/dev/.claude/worktrees/musing-northcutt/flatInvoker/reaktor-flexbuffer/cpp/build_mac/fb-build/install_local_manifest.txt"
     "${CMAKE_INSTALL_MANIFEST_CONTENT}")
endif()
