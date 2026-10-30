find_package(Git REQUIRED)
set(snapshot_source "${CMAKE_CURRENT_LIST_DIR}/../external/mlspp")
set(snapshot_patch "${CMAKE_CURRENT_LIST_DIR}/../patches/mlspp-public-state-snapshot.patch")
file(MAKE_DIRECTORY "${CMAKE_CURRENT_LIST_DIR}/../../build")
file(LOCK "${CMAKE_CURRENT_LIST_DIR}/../../build/mlspp-snapshot.lock" GUARD FILE TIMEOUT 120)
execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${snapshot_source}" rev-parse HEAD
    OUTPUT_VARIABLE snapshot_revision OUTPUT_STRIP_TRAILING_WHITESPACE COMMAND_ERROR_IS_FATAL ANY)
if(NOT snapshot_revision STREQUAL "fc724c3100ce3b5d8565dbd6d93648a440991a8c")
    message(FATAL_ERROR "The MLS snapshot patch requires the recorded MLS++ revision")
endif()
execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${snapshot_source}" apply --reverse --check "${snapshot_patch}"
    RESULT_VARIABLE snapshot_applied OUTPUT_QUIET ERROR_QUIET)
if(NOT snapshot_applied EQUAL 0)
    execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${snapshot_source}" diff --quiet
        RESULT_VARIABLE snapshot_dirty)
    if(NOT snapshot_dirty EQUAL 0)
        message(FATAL_ERROR "MLS++ has source changes outside the recorded snapshot patch")
    endif()
    execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${snapshot_source}" apply --check "${snapshot_patch}"
        COMMAND_ERROR_IS_FATAL ANY)
    execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${snapshot_source}" apply "${snapshot_patch}"
        COMMAND_ERROR_IS_FATAL ANY)
endif()
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${snapshot_patch}")
