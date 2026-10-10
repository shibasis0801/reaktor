if(NOT ANDROID AND EXISTS "/opt/homebrew/opt/openssl")
    list(APPEND CMAKE_PREFIX_PATH "/opt/homebrew/opt/openssl")
    set(OPENSSL_ROOT_DIR "/opt/homebrew/opt/openssl" CACHE PATH "OpenSSL root" FORCE)
elseif(NOT ANDROID AND EXISTS "/usr/local/opt/openssl")
    list(APPEND CMAKE_PREFIX_PATH "/usr/local/opt/openssl")
    set(OPENSSL_ROOT_DIR "/usr/local/opt/openssl" CACHE PATH "OpenSSL root" FORCE)
endif()

if(iOS AND OPENSSL_ROOT_DIR)
    list(APPEND CMAKE_FIND_ROOT_PATH "${OPENSSL_ROOT_DIR}")
endif()

set(JSON_BuildTests OFF CACHE BOOL "" FORCE)
set(JSON_Install OFF CACHE BOOL "" FORCE)
add_subdirectory(cpp/external/json EXCLUDE_FROM_ALL)
set(nlohmann_json_DIR "${CMAKE_CURRENT_BINARY_DIR}/cpp/external/json" CACHE PATH "nlohmann_json build-tree package" FORCE)

set(TESTING OFF CACHE BOOL "" FORCE)
set(DISABLE_PQ ON CACHE BOOL "" FORCE)
add_subdirectory(cpp/external/mlspp EXCLUDE_FROM_ALL)

