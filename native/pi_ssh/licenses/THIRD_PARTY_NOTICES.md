# Third-party notices for pi_ssh

`pi_ssh` links the following checksum-pinned libraries into its native output.

## libssh 0.12.1

- Project: https://www.libssh.org/
- Source: https://www.libssh.org/files/0.12/libssh-0.12.1.tar.xz
- SHA-256: `d3941af0a2d78d5d82ed7a36988e9133994312f035b9659a6e43f8db3968784c`
- License: GNU Lesser General Public License 2.1 or later
- Complete license: `libssh-LGPL-2.1-or-later.txt`

The reproducible build applies only the compatibility changes recorded in
`../cmake/patch_libssh.cmake`: FetchContent-safe CMake path roots and use of
the POSIX `S_IWUSR` macro on Android. The library source remains otherwise
unmodified.

## Mbed TLS 3.6.6

- Project: https://github.com/Mbed-TLS/mbedtls
- Source: https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.6/mbedtls-3.6.6.tar.bz2
- SHA-256: `8fb65fae8dcae5840f793c0a334860a411f884cc537ea290ce1c52bb64ca007a`
- License selected by this project: Apache License 2.0
- Complete upstream dual-license notice and Apache License: `mbedtls-LICENSE.txt`

## Rebuilding and relinking

The complete `pi_ssh` wrapper source, dependency versions, patches, compiler
configuration, Android CMake integration, Desktop resource integration, and
iOS static-archive integration are part of this repository. Follow
`../README.md` or invoke the app Gradle tasks to rebuild or replace libssh and
relink the application for personal use.
