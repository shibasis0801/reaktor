if(NOT TARGET crypto)
    message(FATAL_ERROR "The pinned BoringSSL project must be configured before MLS++")
endif()

set(OPENSSL_INCLUDE_DIR "${BoringSSL_SOURCE_DIR}/include")
if(NOT EXISTS "${OPENSSL_INCLUDE_DIR}/openssl/is_boringssl.h")
    message(FATAL_ERROR "The pinned crypto project does not contain BoringSSL headers")
endif()
set(BORINGSSL_INCLUDE_DIR "${OPENSSL_INCLUDE_DIR}")
set(OPENSSL_CRYPTO_LIBRARY crypto)
set(OPENSSL_LIBRARIES crypto)
include(FindPackageHandleStandardArgs)
find_package_handle_standard_args(OpenSSL REQUIRED_VARS OPENSSL_INCLUDE_DIR OPENSSL_CRYPTO_LIBRARY)
if(NOT TARGET OpenSSL::Crypto)
    add_library(OpenSSL::Crypto INTERFACE IMPORTED GLOBAL)
    set_target_properties(OpenSSL::Crypto PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${OPENSSL_INCLUDE_DIR}"
        INTERFACE_LINK_LIBRARIES crypto)
endif()
