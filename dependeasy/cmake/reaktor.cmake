# Native capabilities are exposed through api.cmake; this is the shared kernel entrypoint.
include_guard(GLOBAL)
get_filename_component(DEPE_CMAKE_OWNER "${CMAKE_CURRENT_LIST_FILE}" REALPATH)
get_filename_component(DEPE_CMAKE_DIRECTORY "${DEPE_CMAKE_OWNER}" DIRECTORY)
get_filename_component(REAKTOR_SOURCE_ROOT "${DEPE_CMAKE_DIRECTORY}/../.." ABSOLUTE)
set(FLATBUFFERS_BUILD_FLATC OFF)
set(FLATBUFFERS_BUILD_TESTS OFF)

include("${DEPE_CMAKE_DIRECTORY}/kernel/apple.cmake")
include("${DEPE_CMAKE_DIRECTORY}/kernel/dependencies.cmake")
include("${DEPE_CMAKE_DIRECTORY}/kernel/project.cmake")
include("${DEPE_CMAKE_DIRECTORY}/kernel/hermes.cmake")
include("${DEPE_CMAKE_DIRECTORY}/kernel/prefab.cmake")
