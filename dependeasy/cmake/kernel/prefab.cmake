if (ANDROID AND DEFINED FBJNI_PREFAB_DIR AND NOT TARGET fbjni::fbjni)
    message(STATUS "Using extracted FBJNI at ${FBJNI_PREFAB_DIR}")
    add_library(fbjni::fbjni UNKNOWN IMPORTED GLOBAL)
    set_target_properties(fbjni::fbjni PROPERTIES
            IMPORTED_LOCATION ${FBJNI_PREFAB_DIR}/libs/android.${ANDROID_ABI}/libfbjni.so
            INTERFACE_INCLUDE_DIRECTORIES ${FBJNI_PREFAB_DIR}/include
    )
endif()

if (ANDROID AND DEFINED HERMES_PREFAB_DIR AND NOT TARGET hermes-engine::hermesvm)
    message(STATUS "Using extracted HERMES at ${HERMES_PREFAB_DIR}")
    add_library(hermes-engine::hermesvm UNKNOWN IMPORTED GLOBAL)
    set_target_properties(hermes-engine::hermesvm PROPERTIES
            IMPORTED_LOCATION ${HERMES_PREFAB_DIR}/libs/android.${ANDROID_ABI}/libhermesvm.so
            INTERFACE_INCLUDE_DIRECTORIES ${HERMES_PREFAB_DIR}/include
    )
endif()

if (ANDROID AND DEFINED OPENSSL_CRYPTO_PREFAB_DIR)
    set(_openssl_crypto_shared "${OPENSSL_CRYPTO_PREFAB_DIR}/libs/android.${ANDROID_ABI}/libcrypto.so")
    set(_openssl_crypto_static "${OPENSSL_CRYPTO_PREFAB_DIR}/libs/android.${ANDROID_ABI}/libcrypto.a")
    if(EXISTS "${_openssl_crypto_shared}")
        set(OPENSSL_CRYPTO_LIBRARY "${_openssl_crypto_shared}" CACHE FILEPATH "Android OpenSSL crypto library" FORCE)
    elseif(EXISTS "${_openssl_crypto_static}")
        set(OPENSSL_CRYPTO_LIBRARY "${_openssl_crypto_static}" CACHE FILEPATH "Android OpenSSL crypto library" FORCE)
    endif()
    set(OPENSSL_INCLUDE_DIR "${OPENSSL_CRYPTO_PREFAB_DIR}/include" CACHE PATH "Android OpenSSL include directory" FORCE)
    set(OPENSSL_LIBRARIES "${OPENSSL_CRYPTO_LIBRARY}" CACHE STRING "Android OpenSSL libraries" FORCE)
    if(OPENSSL_CRYPTO_LIBRARY AND NOT TARGET OpenSSL::Crypto)
        message(STATUS "Using extracted OpenSSL crypto at ${OPENSSL_CRYPTO_LIBRARY}")
        add_library(OpenSSL::Crypto UNKNOWN IMPORTED GLOBAL)
        set_target_properties(OpenSSL::Crypto PROPERTIES
                IMPORTED_LOCATION "${OPENSSL_CRYPTO_LIBRARY}"
                INTERFACE_INCLUDE_DIRECTORIES "${OPENSSL_INCLUDE_DIR}"
        )
    endif()
endif()

if (ANDROID AND DEFINED OPENSSL_SSL_PREFAB_DIR)
    set(_openssl_ssl_shared "${OPENSSL_SSL_PREFAB_DIR}/libs/android.${ANDROID_ABI}/libssl.so")
    set(_openssl_ssl_static "${OPENSSL_SSL_PREFAB_DIR}/libs/android.${ANDROID_ABI}/libssl.a")
    if(EXISTS "${_openssl_ssl_shared}")
        set(OPENSSL_SSL_LIBRARY "${_openssl_ssl_shared}" CACHE FILEPATH "Android OpenSSL SSL library" FORCE)
    elseif(EXISTS "${_openssl_ssl_static}")
        set(OPENSSL_SSL_LIBRARY "${_openssl_ssl_static}" CACHE FILEPATH "Android OpenSSL SSL library" FORCE)
    endif()
    if(OPENSSL_SSL_LIBRARY)
        set(OPENSSL_LIBRARIES "${OPENSSL_SSL_LIBRARY};${OPENSSL_CRYPTO_LIBRARY}" CACHE STRING "Android OpenSSL libraries" FORCE)
    endif()
    if(OPENSSL_SSL_LIBRARY AND NOT TARGET OpenSSL::SSL)
        message(STATUS "Using extracted OpenSSL SSL at ${OPENSSL_SSL_LIBRARY}")
        add_library(OpenSSL::SSL UNKNOWN IMPORTED GLOBAL)
        set_target_properties(OpenSSL::SSL PROPERTIES
                IMPORTED_LOCATION "${OPENSSL_SSL_LIBRARY}"
                INTERFACE_INCLUDE_DIRECTORIES "${OPENSSL_INCLUDE_DIR}"
                INTERFACE_LINK_LIBRARIES OpenSSL::Crypto
        )
    endif()
endif()
