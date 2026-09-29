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
set(ENABLE_LZMA          ON  CACHE BOOL "" FORCE)  # liblzma (xz, below): LZMA/LZMA2/BCJ decode for .cb7
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

# liblzma, from xz 5.8.4 - 0BSD (public-domain-equivalent), so no licence restriction. libarchive's
# 7z reader decodes LZMA1, LZMA2 and the BCJ/ARM64/Delta filters through it, which is what makes an
# ordinary .cb7 (7-Zip's default is LZMA2) open. NEVER pin xz 5.6.0 or 5.6.1: those two shipped
# the backdoor. Bump only to a release whose .sig verifies against Lasse Collin's key.
#
# Built DECODER-ONLY as a static library: no xz/xzdec/lzmainfo CLIs, no scripts, no nls/docs/tests,
# no encoders (nothing here compresses), no threaded decoder, no lzip/microlzma. Every option is
# forced so a future xz default cannot quietly switch something back on.
set(XZ_TOOL_XZ           OFF CACHE BOOL "" FORCE)
set(XZ_TOOL_XZDEC        OFF CACHE BOOL "" FORCE)
set(XZ_TOOL_LZMADEC      OFF CACHE BOOL "" FORCE)
set(XZ_TOOL_LZMAINFO     OFF CACHE BOOL "" FORCE)
set(XZ_TOOL_SYMLINKS       OFF CACHE BOOL "" FORCE)
set(XZ_TOOL_SYMLINKS_LZMA  OFF CACHE BOOL "" FORCE)
set(XZ_TOOL_SCRIPTS      OFF CACHE BOOL "" FORCE)
set(XZ_NLS               OFF CACHE BOOL "" FORCE)
set(XZ_DOC               OFF CACHE BOOL "" FORCE)
set(XZ_DOXYGEN           OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING        OFF CACHE BOOL "" FORCE)
set(XZ_THREADS           no  CACHE STRING "" FORCE)
set(XZ_ENCODERS          ""  CACHE STRING "" FORCE)  # decoders stay at xz's default: every filter
set(XZ_MICROLZMA_ENCODER OFF CACHE BOOL "" FORCE)
set(XZ_MICROLZMA_DECODER OFF CACHE BOOL "" FORCE)
set(XZ_LZIP_DECODER      OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    xz
    URL      https://github.com/tukaani-project/xz/releases/download/v5.8.4/xz-5.8.4.tar.gz
    URL_HASH SHA256=0014c7886930454fe8bd4228665b51af55eeae560ea135c9c4cd33f55b2591d9
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    EXCLUDE_FROM_ALL   # only what libarchive links is built; no install rules leak in
)
FetchContent_MakeAvailable(xz)

# Point libarchive's find_package(LibLZMA) at it. FindLibLZMA's own probes cannot work here:
# liblzma is a target of THIS build, so no library file exists yet at configure time to link a
# probe against, and two of its three probes (lzma_easy_encoder, lzma_lzma_preset) ask for
# ENCODER symbols a decoder-only build does not have. So the "library" is the target's name, which
# makes libarchive link the target, and the three probe results are answered up front. The
# encoder answers are never exercised: libarchive's writers are not linked into the .so, only
# the read side archive_jni.c calls (verified by the link succeeding).
set(LIBLZMA_INCLUDE_DIR        "${xz_SOURCE_DIR}/src/liblzma/api" CACHE PATH "" FORCE)
set(LIBLZMA_LIBRARY            liblzma CACHE STRING "" FORCE)
set(LIBLZMA_HAS_AUTO_DECODER   TRUE CACHE INTERNAL "")
set(LIBLZMA_HAS_EASY_ENCODER   TRUE CACHE INTERNAL "")
set(LIBLZMA_HAS_LZMA_PRESET    TRUE CACHE INTERNAL "")

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
