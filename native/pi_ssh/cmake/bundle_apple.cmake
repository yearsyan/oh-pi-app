get_filename_component(_output_directory "${PI_SSH_BUNDLE_OUTPUT}" DIRECTORY)
file(MAKE_DIRECTORY "${_output_directory}")

file(
    GLOB _libssh
    "${PI_SSH_LIBSSH_BINARY_DIR}/src/${PI_SSH_CONFIGURATION}*/libssh.a"
)
file(
    GLOB _mbedcrypto
    "${PI_SSH_MBEDTLS_BINARY_DIR}/library/${PI_SSH_CONFIGURATION}*/libmbedcrypto.a"
)
file(
    GLOB _p256m
    "${PI_SSH_MBEDTLS_BINARY_DIR}/3rdparty/p256-m/${PI_SSH_CONFIGURATION}*/libp256m.a"
)
file(
    GLOB _everest
    "${PI_SSH_MBEDTLS_BINARY_DIR}/3rdparty/everest/${PI_SSH_CONFIGURATION}*/libeverest.a"
)

foreach(_dependency _libssh _mbedcrypto _p256m _everest)
    list(LENGTH ${_dependency} _count)
    if(NOT _count EQUAL 1)
        message(FATAL_ERROR "Expected one ${_dependency} archive, found ${_count}")
    endif()
endforeach()

execute_process(
    COMMAND
        "${PI_SSH_APPLE_LIBTOOL}" -static -o "${PI_SSH_BUNDLE_OUTPUT}"
        "${PI_SSH_CORE_ARCHIVE}"
        "${_libssh}"
        "${_mbedcrypto}"
        "${_p256m}"
        "${_everest}"
    RESULT_VARIABLE _result
    COMMAND_ERROR_IS_FATAL ANY
)

if(NOT _result EQUAL 0)
    message(FATAL_ERROR "Apple pi_ssh archive merge failed: ${_result}")
endif()
