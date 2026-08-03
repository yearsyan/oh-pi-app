#ifndef PI_SSH_MBEDTLS_THREADING_ALT_H
#define PI_SSH_MBEDTLS_THREADING_ALT_H

/*
 * libssh's thread callbacks store their platform mutex behind a void pointer.
 * Keeping the Mbed TLS mutex value identical makes its alternate threading
 * callbacks ABI-compatible with libssh on Windows.
 */
typedef void *mbedtls_threading_mutex_t;

#endif
