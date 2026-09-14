cmake_minimum_required(VERSION 3.22)
project(absolutex_pdf C)

include(FetchContent)

# PDFium 155.0.8044.0 (upstream tag chromium/8044) — BSD-3-Clause.
# Full third-party licence inventory and the go/no-go reasoning: ../../../LICENSES.md.
#
# Why a prebuilt and not a source build, unlike :source:libarchive —
# PDFium has no release tarball and no standalone CMake build. Upstream is a gclient
# solution on pdfium.googlesource.com that pulls Chromium's build/ tree, abseil, icu,
# freetype, libjpeg-turbo, lcms, libopenjpeg and partition_alloc, and builds with GN+ninja.
# That is not expressible as FetchContent, so it cannot go in this file at all. The pinned
# .tgz below is the same artifact shape: one URL, one SHA256, one version to bump.
#
# Consequence to be aware of: this is the one dependency in the project we do not compile
# ourselves, so the SHA256 is the entire supply-chain check. Re-pin deliberately, never
# to "latest" — tools/refresh-pdfium.sh re-resolves the hash and the licence texts together.
set(ABSOLUTEX_PDFIUM_RELEASE "chromium/8044")

FetchContent_Declare(
    pdfium_prebuilt
    URL      "https://github.com/bblanchon/pdfium-binaries/releases/download/${ABSOLUTEX_PDFIUM_RELEASE}/pdfium-android-arm64.tgz"
    URL_HASH SHA256=37686e64fa005484d619550a78805500de4c5a6f4df7aa06d8576145bfbee98f
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(pdfium_prebuilt)

# The archive ships a PDFiumConfig.cmake, but it locates the library through find_library(),
# and the NDK toolchain file constrains find_library() to the sysroot — so a prebuilt sitting
# in the FetchContent tree is not reliably found. Declaring the imported target directly
# sidesteps the search entirely and states the arm64-only assumption in one place.
add_library(pdfium SHARED IMPORTED)
set_target_properties(pdfium PROPERTIES
    IMPORTED_LOCATION             "${pdfium_prebuilt_SOURCE_DIR}/lib/libpdfium.so"
    INTERFACE_INCLUDE_DIRECTORIES "${pdfium_prebuilt_SOURCE_DIR}/include"
)

add_library(absolutex_pdf SHARED pdfium_jni.c)

# libpdfium.so links its C++ runtime statically — `readelf -d` lists only libdl/libm/libc —
# so this wrapper stays pure C with ANDROID_STL=none, exactly like :source:libarchive.
# jnigraphics is what lets us render straight into the Bitmap's own pixels (see pdfium_jni.c).
target_link_libraries(absolutex_pdf PRIVATE pdfium jnigraphics log)

# AGP packages every .so it finds in the target output directory. The imported libpdfium.so
# lives in the FetchContent tree, which AGP never looks at, so stage a copy next to our own
# library or the APK ships a wrapper whose DT_NEEDED cannot be resolved at load time.
add_custom_command(TARGET absolutex_pdf POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${pdfium_prebuilt_SOURCE_DIR}/lib/libpdfium.so"
            "$<TARGET_FILE_DIR:absolutex_pdf>/libpdfium.so"
    COMMENT "Staging libpdfium.so for packaging"
    VERBATIM
)
