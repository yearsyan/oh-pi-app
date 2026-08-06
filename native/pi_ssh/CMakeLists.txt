cmake_minimum_required(VERSION 3.22)

project(pi_ssh VERSION 0.1.0 LANGUAGES C)

option(PI_SSH_USE_SYSTEM_LIBSSH "Link an installed libssh instead of fetching pinned sources" OFF)
option(PI_SSH_BUILD_SHARED "Build pi_ssh as a shared library" ON)
option(PI_SSH_BUILD_TESTS "Build native pi_ssh tests" OFF)
option(PI_SSH_ENABLE_JNI "Compile the Android/Desktop JNI entry points" OFF)
option(PI_SSH_BUILD_APPLE_BUNDLE "Merge static dependencies into one Apple archive" OFF)
set(PI_SSH_OUTPUT_DIRECTORY "" CACHE PATH "Optional final native library output directory")

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(MSVC)
    # The desktop DLL is extracted from the application resources at runtime;
    # link the CRT statically so a separate VC++ Redistributable is unnecessary.
    set(
        CMAKE_MSVC_RUNTIME_LIBRARY
        "MultiThreaded$<$<CONFIG:Debug>:Debug>"
    )
endif()

find_package(Threads REQUIRED)

if(PI_SSH_USE_SYSTEM_LIBSSH)
    find_package(libssh 0.12 CONFIG REQUIRED)
    set(PI_SSH_LIBSSH_TARGET ssh)
else()
    include(FetchContent)
    set(PI_SSH_FETCH_TIMESTAMP_ARGS)
    if(CMAKE_VERSION VERSION_GREATER_EQUAL "3.24")
        list(APPEND PI_SSH_FETCH_TIMESTAMP_ARGS DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
    endif()

    # Mbed TLS 3.6 is the current LTS line. Both archives are checksum-pinned.
    FetchContent_Declare(
        pi_ssh_mbedtls
        URL https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.6/mbedtls-3.6.6.tar.bz2
        URL_HASH SHA256=8fb65fae8dcae5840f793c0a334860a411f884cc537ea290ce1c52bb64ca007a
        ${PI_SSH_FETCH_TIMESTAMP_ARGS}
    )
    set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
    set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
    set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "" FORCE)
    set(USE_STATIC_MBEDTLS_LIBRARY ON CACHE BOOL "" FORCE)
    if(WIN32)
        # libssh installs its CRITICAL_SECTION callbacks through Mbed TLS's
        # alternate threading API on Windows.
        set(LINK_WITH_PTHREAD OFF CACHE BOOL "" FORCE)
    else()
        set(LINK_WITH_PTHREAD ON CACHE BOOL "" FORCE)
    endif()
    set(
        MBEDTLS_USER_CONFIG_FILE
        "${CMAKE_CURRENT_SOURCE_DIR}/include/pi_ssh_mbedtls_config.h"
        CACHE FILEPATH "" FORCE
    )
    FetchContent_MakeAvailable(pi_ssh_mbedtls)
    if(WIN32)
        # MBEDTLS_THREADING_ALT includes threading_alt.h from consumers as
        # well as while compiling mbedcrypto.
        target_include_directories(
            mbedcrypto
            PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include"
        )
    endif()

    list(PREPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
    set(WITH_MBEDTLS ON CACHE BOOL "" FORCE)
    set(WITH_GCRYPT OFF CACHE BOOL "" FORCE)
    set(WITH_ZLIB OFF CACHE BOOL "" FORCE)
    set(WITH_SFTP OFF CACHE BOOL "" FORCE)
    set(WITH_SERVER OFF CACHE BOOL "" FORCE)
    set(WITH_GSSAPI OFF CACHE BOOL "" FORCE)
    set(WITH_PCAP OFF CACHE BOOL "" FORCE)
    set(WITH_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WITH_EXEC OFF CACHE BOOL "" FORCE)
    set(WITH_NACL OFF CACHE BOOL "" FORCE)
    set(WITH_FIDO2 OFF CACHE BOOL "" FORCE)
    set(WITH_SYMBOL_VERSIONING OFF CACHE BOOL "" FORCE)
    set(UNIT_TESTING OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    # This is an embedded dependency; its install export cannot include the
    # in-tree Mbed TLS aliases and no install target is needed by Gradle/Xcode.
    set(CMAKE_SKIP_INSTALL_RULES ON CACHE BOOL "" FORCE)
    # Apple's libc exports memset_s but does not declare Annex K APIs unless
    # the opt-in feature macro is available. libssh's link-only probe otherwise
    # produces an undeclared call under modern Clang's -Werror settings.
    set(HAVE_MEMSET_S OFF CACHE INTERNAL "" FORCE)

    FetchContent_Declare(
        pi_ssh_libssh
        URL https://www.libssh.org/files/0.12/libssh-0.12.1.tar.xz
        URL_HASH SHA256=d3941af0a2d78d5d82ed7a36988e9133994312f035b9659a6e43f8db3968784c
        ${PI_SSH_FETCH_TIMESTAMP_ARGS}
        PATCH_COMMAND
            "${CMAKE_COMMAND}"
            -DSOURCE_DIR=<SOURCE_DIR>
            -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/patch_libssh.cmake"
    )
    FetchContent_MakeAvailable(pi_ssh_libssh)
    if(MSVC)
        target_compile_options(ssh PRIVATE /utf-8)
    endif()
    # libssh's primary `ssh` target follows BUILD_SHARED_LIBS and is static in
    # this build. Keeping one archive avoids shipping a second native library.
    set(PI_SSH_LIBSSH_TARGET ssh)
endif()

if(PI_SSH_BUILD_SHARED)
    set(PI_SSH_LIBRARY_TYPE SHARED)
else()
    set(PI_SSH_LIBRARY_TYPE STATIC)
endif()

if(WIN32)
    set(PI_SSH_PLATFORM_SOURCE src/pi_ssh_platform_windows.c)
else()
    set(PI_SSH_PLATFORM_SOURCE src/pi_ssh_platform_posix.c)
endif()

add_library(
    pi_ssh
    ${PI_SSH_LIBRARY_TYPE}
    src/pi_ssh.c
    ${PI_SSH_PLATFORM_SOURCE}
)
target_compile_definitions(pi_ssh PRIVATE PI_SSH_BUILDING_LIBRARY)
set_target_properties(
    pi_ssh
    PROPERTIES
        C_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN YES
)
target_include_directories(
    pi_ssh
    PUBLIC
        "$<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>"
        "$<INSTALL_INTERFACE:include>"
)
target_link_libraries(pi_ssh PRIVATE ${PI_SSH_LIBSSH_TARGET} Threads::Threads)
if(WIN32)
    target_link_libraries(pi_ssh PRIVATE ws2_32)
endif()

if(PI_SSH_ENABLE_JNI)
    target_sources(pi_ssh PRIVATE src/pi_ssh_jni.c)
    if(NOT ANDROID)
        find_path(
            PI_SSH_JNI_INCLUDE_DIR
            jni.h
            HINTS "$ENV{JAVA_HOME}" "${JAVA_HOME}"
            PATH_SUFFIXES include
            REQUIRED
        )
        find_path(
            PI_SSH_JNI_PLATFORM_INCLUDE_DIR
            jni_md.h
            HINTS "${PI_SSH_JNI_INCLUDE_DIR}"
            PATH_SUFFIXES darwin linux win32
            REQUIRED
        )
        target_include_directories(
            pi_ssh
            PRIVATE
                "${PI_SSH_JNI_INCLUDE_DIR}"
                "${PI_SSH_JNI_PLATFORM_INCLUDE_DIR}"
        )
    endif()
endif()

if(PI_SSH_BUILD_SHARED)
    if(APPLE)
        set(
            PI_SSH_EXPORTED_SYMBOLS
            pi_ssh_tunnel_config_init
            pi_ssh_error_init
            pi_ssh_command_config_init
            pi_ssh_command_result_init
            pi_ssh_command_result_free
            pi_ssh_key_pair_init
            pi_ssh_key_pair_free
            pi_ssh_key_pair_generate_ed25519
            pi_ssh_command_execute
            pi_ssh_tunnel_start
            pi_ssh_tunnel_local_port
            pi_ssh_tunnel_state
            pi_ssh_tunnel_copy_last_error
            pi_ssh_tunnel_free
            pi_ssh_library_version
        )
        if(PI_SSH_ENABLE_JNI)
            list(
                APPEND PI_SSH_EXPORTED_SYMBOLS
                Java_io_github_yearsyan_ohpi_ssh_NativeSshBridge_nativeStart
                Java_io_github_yearsyan_ohpi_ssh_NativeSshBridge_nativeExecute
                Java_io_github_yearsyan_ohpi_ssh_NativeSshBridge_nativeGenerateEd25519KeyPair
                Java_io_github_yearsyan_ohpi_ssh_NativeSshBridge_nativeLocalPort
                Java_io_github_yearsyan_ohpi_ssh_NativeSshBridge_nativeState
                Java_io_github_yearsyan_ohpi_ssh_NativeSshBridge_nativeLastError
                Java_io_github_yearsyan_ohpi_ssh_NativeSshBridge_nativeFree
                Java_io_github_yearsyan_ohpi_ssh_NativeSshBridge_nativeVersion
            )
        endif()
        foreach(_symbol IN LISTS PI_SSH_EXPORTED_SYMBOLS)
            target_link_options(pi_ssh PRIVATE "LINKER:-exported_symbol,_${_symbol}")
        endforeach()
    elseif(UNIX)
        # libssh and Mbed TLS are implementation details of the stable C ABI.
        target_link_options(pi_ssh PRIVATE "LINKER:--exclude-libs,ALL")
    endif()
endif()

if(PI_SSH_OUTPUT_DIRECTORY)
    set_target_properties(
        pi_ssh
        PROPERTIES
            ARCHIVE_OUTPUT_DIRECTORY "${PI_SSH_OUTPUT_DIRECTORY}"
            LIBRARY_OUTPUT_DIRECTORY "${PI_SSH_OUTPUT_DIRECTORY}"
            RUNTIME_OUTPUT_DIRECTORY "${PI_SSH_OUTPUT_DIRECTORY}"
    )
    foreach(_configuration DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
        set_target_properties(
            pi_ssh
            PROPERTIES
                ARCHIVE_OUTPUT_DIRECTORY_${_configuration} "${PI_SSH_OUTPUT_DIRECTORY}"
                LIBRARY_OUTPUT_DIRECTORY_${_configuration} "${PI_SSH_OUTPUT_DIRECTORY}"
                RUNTIME_OUTPUT_DIRECTORY_${_configuration} "${PI_SSH_OUTPUT_DIRECTORY}"
        )
    endforeach()
endif()

if(PI_SSH_BUILD_APPLE_BUNDLE)
    if(NOT APPLE OR PI_SSH_BUILD_SHARED OR PI_SSH_USE_SYSTEM_LIBSSH)
        message(FATAL_ERROR "The Apple bundle requires a portable static Apple build")
    endif()
    if(NOT PI_SSH_OUTPUT_DIRECTORY)
        set(PI_SSH_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}")
    endif()
    find_program(PI_SSH_APPLE_LIBTOOL NAMES libtool REQUIRED)
    set(PI_SSH_BUNDLE_ARCHIVE "${PI_SSH_OUTPUT_DIRECTORY}/libpi_ssh_bundle.a")
    if(CMAKE_GENERATOR STREQUAL "Xcode")
        # Xcode's effective platform suffix is only known at build time. CMake
        # escapes it inside custom-command target paths, so resolve the one
        # configuration-specific archive under each dependency build tree.
        add_custom_command(
            OUTPUT "${PI_SSH_BUNDLE_ARCHIVE}"
            COMMAND
                "${CMAKE_COMMAND}"
                -DPI_SSH_BUNDLE_OUTPUT=${PI_SSH_BUNDLE_ARCHIVE}
                -DPI_SSH_CORE_ARCHIVE=$<TARGET_FILE:pi_ssh>
                -DPI_SSH_LIBSSH_BINARY_DIR=${pi_ssh_libssh_BINARY_DIR}
                -DPI_SSH_MBEDTLS_BINARY_DIR=${pi_ssh_mbedtls_BINARY_DIR}
                -DPI_SSH_CONFIGURATION=$<CONFIG>
                -DPI_SSH_APPLE_LIBTOOL=${PI_SSH_APPLE_LIBTOOL}
                -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/bundle_apple.cmake"
            DEPENDS pi_ssh ssh mbedcrypto p256m everest
            VERBATIM
        )
    else()
        add_custom_command(
            OUTPUT "${PI_SSH_BUNDLE_ARCHIVE}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${PI_SSH_OUTPUT_DIRECTORY}"
            COMMAND
                "${PI_SSH_APPLE_LIBTOOL}" -static -o "${PI_SSH_BUNDLE_ARCHIVE}"
                "$<TARGET_FILE:pi_ssh>"
                "$<TARGET_FILE:ssh>"
                "$<TARGET_FILE:mbedcrypto>"
                "$<TARGET_FILE:p256m>"
                "$<TARGET_FILE:everest>"
            DEPENDS pi_ssh ssh mbedcrypto p256m everest
            VERBATIM
        )
    endif()
    add_custom_target(pi_ssh_bundle ALL DEPENDS "${PI_SSH_BUNDLE_ARCHIVE}")
endif()

if(CMAKE_C_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(pi_ssh PRIVATE -Wall -Wextra -Wpedantic -Werror)
endif()

if(PI_SSH_BUILD_TESTS)
    enable_testing()
    add_executable(pi_ssh_config_test tests/pi_ssh_config_test.c)
    target_link_libraries(pi_ssh_config_test PRIVATE pi_ssh)
    add_test(NAME pi_ssh_config_test COMMAND pi_ssh_config_test)

    add_executable(
        pi_ssh_platform_test
        tests/pi_ssh_platform_test.c
        ${PI_SSH_PLATFORM_SOURCE}
    )
    target_include_directories(pi_ssh_platform_test PRIVATE src)
    target_link_libraries(
        pi_ssh_platform_test
        PRIVATE ${PI_SSH_LIBSSH_TARGET} Threads::Threads
    )
    if(WIN32)
        target_link_libraries(pi_ssh_platform_test PRIVATE ws2_32)
    endif()
    add_test(NAME pi_ssh_platform_test COMMAND pi_ssh_platform_test)

    # This executable is run explicitly by CI/dev scripts that provide an
    # isolated sshd and HTTP server; it is not registered as a hermetic ctest.
    add_executable(
        pi_ssh_tunnel_integration_test
        tests/pi_ssh_tunnel_integration_test.c
    )
    target_link_libraries(pi_ssh_tunnel_integration_test PRIVATE pi_ssh)
    if(WIN32)
        target_link_libraries(pi_ssh_tunnel_integration_test PRIVATE ws2_32)
    endif()
endif()
