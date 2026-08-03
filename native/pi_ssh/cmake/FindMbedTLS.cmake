# libssh's bundled finder expects installed library files. During the portable
# build Mbed TLS already exists as CMake targets, so expose the same contract.
if(NOT TARGET mbedcrypto)
    set(MbedTLS_FOUND FALSE)
    set(MBEDTLS_FOUND FALSE)
    return()
endif()

set(MBEDTLS_INCLUDE_DIR "${pi_ssh_mbedtls_SOURCE_DIR}/include")
set(MBEDTLS_INCLUDE_DIRS "${MBEDTLS_INCLUDE_DIR}")
set(MBEDTLS_VERSION "3.6.6")
set(MBEDTLS_LIBRARIES mbedtls mbedx509 mbedcrypto)
set(MbedTLS_FOUND TRUE)
set(MBEDTLS_FOUND TRUE)

if(NOT TARGET MbedTLS::mbedcrypto)
    add_library(MbedTLS::mbedcrypto ALIAS mbedcrypto)
endif()
if(NOT TARGET MbedTLS::mbedx509)
    add_library(MbedTLS::mbedx509 ALIAS mbedx509)
endif()
if(NOT TARGET MbedTLS::mbedtls)
    add_library(MbedTLS::mbedtls ALIAS mbedtls)
endif()
