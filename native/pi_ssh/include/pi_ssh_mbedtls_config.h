#ifndef PI_SSH_MBEDTLS_CONFIG_H
#define PI_SSH_MBEDTLS_CONFIG_H

/* libssh may be initialized and used by different app-owned tunnel threads. */
#define MBEDTLS_THREADING_C
#if defined(_WIN32)
#define MBEDTLS_THREADING_ALT
#else
#define MBEDTLS_THREADING_PTHREAD
#endif

#endif
