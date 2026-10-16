set(JSON_BuildTests OFF CACHE BOOL "" FORCE)
set(JSON_Install OFF CACHE BOOL "" FORCE)
add_subdirectory(cpp/external/json EXCLUDE_FROM_ALL)
set(nlohmann_json_DIR "${CMAKE_CURRENT_BINARY_DIR}/cpp/external/json" CACHE PATH "nlohmann_json build-tree package" FORCE)

set(TESTING OFF CACHE BOOL "" FORCE)
set(DISABLE_PQ ON CACHE BOOL "" FORCE)
add_subdirectory(cpp/external/mlspp EXCLUDE_FROM_ALL)

