cmake_minimum_required(VERSION 3.22)
project(absolutex_archive C)

include(FetchContent)

# libarchive 3.8.9 — New BSD. Its RAR4/RAR5 readers are clean-room and contain no RARLAB
# UnRAR code, so none of the UnRAR licence restrictions apply. This is the §2 go/no-go answer.
set(ENABLE_TEST          OFF CACHE BOOL "" FORCE)
set(ENABLE_OPENSSL       OFF CACHE BOOL "" FORCE)
set(ENABLE_LIBXML2       OFF CACHE BOOL "" FORCE)
set(ENABLE_EXPAT         OFF CACHE BOOL "" FORCE)
set(ENABLE_ICONV         OFF CACHE BOOL "" FORCE)
set(ENABLE_ACL           OFF CACHE BOOL "" FORCE)
set(ENABLE_XATTR         OFF CACHE BOOL "" FORCE)
set(ENABLE_PCREPOSIX     OFF CACHE BOOL "" FORCE)
set(ENABLE_LZMA          OFF CACHE BOOL "" FORCE)  # TODO(phase6): ON for .cb7 (7z/LZMA)
set(ENABLE_BZip2         OFF CACHE BOOL "" FORCE)
set(ENABLE_LZ4           OFF CACHE BOOL "" FORCE)
set(ENABLE_ZSTD          OFF CACHE BOOL "" FORCE)
set(ENABLE_ZLIB          ON  CACHE BOOL "" FORCE)  # NDK ships libz; deflate covers .cbz
# CLI front-ends we never ship:
set(ENABLE_TAR           OFF CACHE BOOL "" FORCE)
set(ENABLE_CPIO          OFF CACHE BOOL "" FORCE)
set(ENABLE_CAT           OFF CACHE BOOL "" FORCE)
set(ENABLE_UNZIP         OFF CACHE BOOL "" FORCE)
set(ENABLE_INSTALL       OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    libarchive
    URL      https://github.com/libarchive/libarchive/releases/download/v3.8.9/libarchive-3.8.9.tar.gz
    URL_HASH SHA256=f5a6539059cf5e597dbeda37bfa4874b1e8dea063c8d93bf85a2b44af90a5bd4
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(libarchive)

add_library(absolutex_archive SHARED archive_jni.c)
target_link_libraries(absolutex_archive PRIVATE archive_static z log)
target_include_directories(absolutex_archive PRIVATE
    ${libarchive_SOURCE_DIR}/libarchive
)

# Hardening: stack protector + _FORTIFY_SOURCE (needs -O, we build Release/-Os) +
# format-string errors. C11 for static_assert/max-align guarantees; hidden visibility
# so only JNIEXPORT entry points leave the .so (mirrors the Gradle cFlags below —
# duplication across both is benign, flags are idempotent).
set_target_properties(absolutex_archive PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    C_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
target_compile_options(absolutex_archive PRIVATE
    -Os
    -fvisibility=hidden
    -fstack-protector-strong
    -D_FORTIFY_SOURCE=2
    -Wformat -Werror=format-security
    -Wall -Wextra
)
