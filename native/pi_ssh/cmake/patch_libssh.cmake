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
