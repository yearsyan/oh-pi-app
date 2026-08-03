# libssh 0.12.1 uses CMAKE_SOURCE_DIR in a few paths and therefore assumes it
# is the top-level project. Replace only those path roots so it is safe as a
# FetchContent dependency. The patch is deterministic and idempotent.
set(_files
    "${SOURCE_DIR}/CMakeLists.txt"
    "${SOURCE_DIR}/ConfigureChecks.cmake"
)

foreach(_file IN LISTS _files)
    file(READ "${_file}" _contents)
    string(REPLACE "\${CMAKE_SOURCE_DIR}" "\${CMAKE_CURRENT_SOURCE_DIR}" _contents "${_contents}")
    string(REPLACE "\${CMAKE_BINARY_DIR}" "\${CMAKE_CURRENT_BINARY_DIR}" _contents "${_contents}")
    file(WRITE "${_file}" "${_contents}")
endforeach()

# Android's Bionic exposes the POSIX S_IWUSR macro, but not the historical
# S_IWRITE alias used by libssh 0.12.1 in ssh_dir_writeable(). Keep the patch
# narrowly scoped so the unrelated Windows _S_IWRITE use is unchanged.
set(_misc_file "${SOURCE_DIR}/src/misc.c")
file(READ "${_misc_file}" _misc_contents)
string(REPLACE
    "(buffer.st_mode & S_IWRITE)"
    "(buffer.st_mode & S_IWUSR)"
    _misc_contents
    "${_misc_contents}"
)
file(WRITE "${_misc_file}" "${_misc_contents}")

# libssh 0.12.1 passes Mbed TLS's alternate mutex callbacks to the backend but
# falls off the end of crypto_thread_init(). MSVC correctly diagnoses the
# undefined return value, which can make ssh_init() fail nondeterministically.
set(_mbedtls_threads_file "${SOURCE_DIR}/src/threads/mbedtls.c")
file(READ "${_mbedtls_threads_file}" _mbedtls_threads_contents)
set(_mbedtls_threads_original [=[
        mbedtls_threading_set_alt(user_callbacks->mutex_init,
                                  user_callbacks->mutex_destroy,
                                  user_callbacks->mutex_lock,
                                  user_callbacks->mutex_unlock);
    }
#elif defined MBEDTLS_THREADING_PTHREAD
]=])
set(_mbedtls_threads_patched [=[
        mbedtls_threading_set_alt(user_callbacks->mutex_init,
                                  user_callbacks->mutex_destroy,
                                  user_callbacks->mutex_lock,
                                  user_callbacks->mutex_unlock);
        return SSH_OK;
    }
#elif defined MBEDTLS_THREADING_PTHREAD
]=])
string(FIND
    "${_mbedtls_threads_contents}"
    "${_mbedtls_threads_original}"
    _mbedtls_threads_original_offset
)
string(FIND
    "${_mbedtls_threads_contents}"
    "${_mbedtls_threads_patched}"
    _mbedtls_threads_patched_offset
)
if(NOT _mbedtls_threads_original_offset EQUAL -1)
    string(REPLACE
        "${_mbedtls_threads_original}"
        "${_mbedtls_threads_patched}"
        _mbedtls_threads_contents
        "${_mbedtls_threads_contents}"
    )
elseif(_mbedtls_threads_patched_offset EQUAL -1)
    message(FATAL_ERROR "Unexpected libssh Mbed TLS threading implementation")
endif()
file(WRITE "${_mbedtls_threads_file}" "${_mbedtls_threads_contents}")

# PROGRAMDATA uses native backslashes. Unescaped values end up in generated C
# string literals such as "C:\ProgramData", where \P is not a valid escape.
set(_define_options_file "${SOURCE_DIR}/DefineOptions.cmake")
file(READ "${_define_options_file}" _define_options_contents)
string(REPLACE
    [=[set(GLOBAL_CONF_DIR "$ENV{PROGRAMDATA}/ssh")]=]
    [=[file(TO_CMAKE_PATH "$ENV{PROGRAMDATA}" _LIBSSH_PROGRAMDATA)
        set(GLOBAL_CONF_DIR "${_LIBSSH_PROGRAMDATA}/ssh")]=]
    _define_options_contents
    "${_define_options_contents}"
)
file(WRITE "${_define_options_file}" "${_define_options_contents}")
