/*
 * JNI bridge over PDFium.
 *
 * Unlike archive_jni.c this layer is STATEFUL. libarchive can afford to re-open per call
 * because walking entry headers does not decompress; PDFium cannot, because opening a
 * document parses the xref table and builds the object map. Re-opening per tile would put
 * that cost on every pan frame, so a document is opened once and held behind a jlong handle.
 *
 * Threading: PDFium is not thread-safe, and that is a library-wide property, not a
 * per-document one — the font cache and the module-global CFX state are shared by every
 * FPDF_DOCUMENT in the process. So ALL pdfium calls here are serialised on one global
 * mutex. That is deliberately the conservative choice: it means tile rendering does NOT
 * currently fan across the big cores the way page decode does.
 * TODO(perf): to parallelise, run N documents in N processes, or re-verify upstream's
 * current per-document guarantees and narrow this to a per-document lock. Do not just
 * delete the lock — the failure mode is heap corruption under load, not a wrong pixel.
 */

#include <jni.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <android/log.h>
#include <android/bitmap.h>

#include <fpdfview.h>
#include <fpdf_doc.h>

#define LOG_TAG "absolutex.pdf"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Malformed or hostile outlines can be cyclic; PDFium does not promise to break the cycle
 * for us. Both caps are far above any real comic's table of contents. */
#define OUTLINE_MAX_DEPTH    32
#define OUTLINE_MAX_ENTRIES  4096
#define OUTLINE_MAX_SIBLINGS 4096

static pthread_mutex_t g_pdfium = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t  g_init_once = PTHREAD_ONCE_INIT;

typedef struct {
    int             fd;          /* private descriptor, owned by this struct */
    FPDF_FILEACCESS access;
    FPDF_DOCUMENT   doc;
    FPDF_PAGE       page;        /* single-entry page cache; NULL when empty */
    int             page_index;
} PdfDoc;

static void init_pdfium(void) {
    FPDF_LIBRARY_CONFIG cfg;
    memset(&cfg, 0, sizeof(cfg));
    /* Version 2 is the lowest non-deprecated interface version. Everything above it is
     * V8/XFA and renderer-selection config we do not build against; leaving those fields
     * zeroed and the version at 2 stops PDFium reading past what we actually filled in. */
    cfg.version = 2;
    FPDF_InitLibraryWithConfig(&cfg);
}

/*
 * Returns a descriptor for the same file that this document owns for its lifetime.
 *
 * Re-opening /proc/self/fd/N yields an independent open file description; dup() would share
 * the original's file offset. Both outcomes are handled, and it matters which one you get:
 *
 *   - For a plain file the /proc re-open succeeds and the descriptor is fully independent.
 *   - For a SAF descriptor it FAILS, and we fall back to dup(). That is not hypothetical:
 *     SAF exists to grant access the app does not have by path, so re-opening re-checks the
 *     real path and is refused. :source:libarchive hit exactly this on device, where the
 *     silently shared offset corrupted concurrent reads.
 *
 * This layer is safe under the dup() fallback, for two independent reasons:
 *
 *   1. Every read goes through pread(), which takes its offset as an argument and neither
 *      reads nor moves the shared one. A shared offset is simply never consulted.
 *   2. All PDFium calls, and therefore all get_block() callbacks, are serialised on
 *      g_pdfium anyway, so two reads are never in flight at once regardless.
 *
 * Either reason alone is sufficient, which is why this module can hold ONE descriptor for
 * the life of the document.
 *
 * NOTE for whoever reads this after seeing LibArchiveSource's open-per-read factory: do not
 * port that pattern here. It is the right fix there because libarchive reads sequentially
 * through the shared offset. Here it would re-parse the xref table and rebuild the object
 * map on every tile, which is the cost this whole module is arranged to pay exactly once.
 * If a read() is ever added to this file, fix it by making that read a pread(), not by
 * re-opening the document.
 */
static int private_fd(int fd) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    int p = open(path, O_RDONLY | O_CLOEXEC);
    return p >= 0 ? p : dup(fd);
}

/* PDFium's pull-reader callback. Non-zero means success; a short read is a failure. */
static int get_block(void *param, unsigned long position, unsigned char *buf,
                     unsigned long size) {
    PdfDoc *d = (PdfDoc *) param;
    unsigned long done = 0;
    while (done < size) {
        ssize_t n = pread(d->fd, buf + done, (size_t) (size - done),
                          (off_t) (position + done));
        if (n < 0) {
            if (errno == EINTR) continue;
            return 0;
        }
        if (n == 0) return 0;   /* short file: PDFium promised not to read past m_FileLen */
        done += (unsigned long) n;
    }
    return 1;
}

/* Caller must hold g_pdfium. Keeps the most recent page loaded: tiles arrive in bursts for
 * one page, so a single-entry cache turns an N-tile pan into one FPDF_LoadPage, not N. */
static FPDF_PAGE ensure_page(PdfDoc *d, int index) {
    if (d->page != NULL && d->page_index == index) return d->page;
    if (d->page != NULL) {
        FPDF_ClosePage(d->page);
        d->page = NULL;
        d->page_index = -1;
    }
    FPDF_PAGE p = FPDF_LoadPage(d->doc, index);
    if (p == NULL) return NULL;
    d->page = p;
    d->page_index = index;
    return p;
}

/*
 * Opens a document. Returns a positive handle, or the NEGATED FPDF error code on failure
 * (so -4 is FPDF_ERR_PASSWORD). An out-parameter would have been racy: FPDF_GetLastError()
 * is process-global, and another thread can overwrite it between the failure and the read.
 */
JNIEXPORT jlong JNICALL
Java_com_absolutex_source_pdf_Pdfium_nativeOpen(JNIEnv *env, jclass clazz,
                                                jint fd, jstring jpassword) {
    (void) clazz;
    pthread_once(&g_init_once, init_pdfium);

    PdfDoc *d = calloc(1, sizeof(PdfDoc));
    if (d == NULL) return -(jlong) FPDF_ERR_UNKNOWN;
    d->page_index = -1;

    d->fd = private_fd(fd);
    if (d->fd < 0) { free(d); return -(jlong) FPDF_ERR_FILE; }

    off_t len = lseek(d->fd, 0, SEEK_END);
    if (len <= 0) { close(d->fd); free(d); return -(jlong) FPDF_ERR_FILE; }

    d->access.m_FileLen  = (unsigned long) len;
    d->access.m_GetBlock = get_block;
    d->access.m_Param    = d;

    const char *password = NULL;
    if (jpassword != NULL) password = (*env)->GetStringUTFChars(env, jpassword, NULL);

    pthread_mutex_lock(&g_pdfium);
    d->doc = FPDF_LoadCustomDocument(&d->access, password);
    unsigned long err = (d->doc == NULL) ? FPDF_GetLastError() : FPDF_ERR_SUCCESS;
    pthread_mutex_unlock(&g_pdfium);

    if (password != NULL) (*env)->ReleaseStringUTFChars(env, jpassword, password);

    if (d->doc == NULL) {
        LOGE("FPDF_LoadCustomDocument failed, err=%lu", err);
        close(d->fd);
        free(d);
        if (err == FPDF_ERR_SUCCESS) err = FPDF_ERR_UNKNOWN;
        return -(jlong) err;
    }
    return (jlong) (intptr_t) d;
}

JNIEXPORT void JNICALL
Java_com_absolutex_source_pdf_Pdfium_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    PdfDoc *d = (PdfDoc *) (intptr_t) handle;
    if (d == NULL) return;

    pthread_mutex_lock(&g_pdfium);
    if (d->page != NULL) FPDF_ClosePage(d->page);
    if (d->doc != NULL) FPDF_CloseDocument(d->doc);
    pthread_mutex_unlock(&g_pdfium);

    /* Deliberately no FPDF_DestroyLibrary(): it tears down process-global state that other
     * live documents still depend on, and PDFium has no refcounted re-init. The library
     * stays initialised for the life of the process, which is what its API expects. */
    close(d->fd);
    free(d);
}

JNIEXPORT jint JNICALL
Java_com_absolutex_source_pdf_Pdfium_nativePageCount(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    PdfDoc *d = (PdfDoc *) (intptr_t) handle;
    if (d == NULL) return 0;
    pthread_mutex_lock(&g_pdfium);
    int n = FPDF_GetPageCount(d->doc);
    pthread_mutex_unlock(&g_pdfium);
    return (jint) n;
}

/*
 * Page size in PostScript points, written into out[0]=width, out[1]=height.
 *
 * Uses FPDF_GetPageSizeByIndexF rather than FPDF_LoadPage + FPDF_GetPageWidthF: it reads the
 * MediaBox straight out of the page dictionary without building the page's content tree.
 * That is what makes a whole-book size pass (needed to lay out the scroll strip before any
 * page renders) cheap rather than a full parse of every page.
 */
JNIEXPORT jboolean JNICALL
Java_com_absolutex_source_pdf_Pdfium_nativePageSize(JNIEnv *env, jclass clazz,
                                                    jlong handle, jint index, jfloatArray out) {
    (void) clazz;
    PdfDoc *d = (PdfDoc *) (intptr_t) handle;
    if (d == NULL || out == NULL) return JNI_FALSE;
    if ((*env)->GetArrayLength(env, out) < 2) return JNI_FALSE;

    FS_SIZEF size;
    pthread_mutex_lock(&g_pdfium);
    int ok = FPDF_GetPageSizeByIndexF(d->doc, index, &size);
    pthread_mutex_unlock(&g_pdfium);
    if (!ok) return JNI_FALSE;

    jfloat wh[2] = { size.width, size.height };
    (*env)->SetFloatArrayRegion(env, out, 0, 2, wh);
    return JNI_TRUE;
}

/*
 * Renders one tile straight into an ARGB_8888 Bitmap's own pixels.
 *
 * `bitmap` is tile-sized. (tileLeft, tileTop) is the tile's origin within the full page
 * rendered at scale, and (scaledWidth, scaledHeight) is that full page's pixel size. PDFium
 * places the whole page at (-tileLeft, -tileTop) and clips to the bitmap, which is exactly
 * the per-tile render the platform PdfRenderer cannot express — it only ever scales a whole
 * page into a whole bitmap, so a zoomed page would have to be rasterised in full before it
 * could be cropped.
 *
 * Two pixel-format details, both load-bearing:
 *  - FPDFBitmap_BGRA writes B,G,R,A; Android's ARGB_8888 is R,G,B,A in memory. The
 *    FPDF_REVERSE_BYTE_ORDER render flag flips PDFium's output to match, so no swizzle pass.
 *  - Android treats ARGB_8888 as premultiplied, PDFium's BGRA is not. Filling the tile
 *    opaque white first makes alpha 255 everywhere, where the two representations coincide.
 *    White is also simply what a comic page wants behind it. If a transparent render is ever
 *    needed, switch to FPDFBitmap_BGRA_Premul rather than dropping the fill.
 *
 * Rendering into the locked Bitmap avoids the intermediate full-tile buffer and the copy out
 * of it — at 120 Hz that copy is the difference between a smooth pan and a visible hitch.
 */
JNIEXPORT jboolean JNICALL
Java_com_absolutex_source_pdf_Pdfium_nativeRenderTile(JNIEnv *env, jclass clazz, jlong handle,
                                                      jint pageIndex, jint tileLeft, jint tileTop,
                                                      jint scaledWidth, jint scaledHeight,
                                                      jint flags, jobject bitmap) {
    (void) clazz;
    PdfDoc *d = (PdfDoc *) (intptr_t) handle;
    if (d == NULL || bitmap == NULL) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_getInfo failed");
        return JNI_FALSE;
    }
    /* RGBA_8888 only. RGB_565 is banned project-wide and the reverse-byte-order path above
     * assumes a 4-byte pixel, so anything else is a caller bug, not a fallback. */
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("tile bitmap must be ARGB_8888, got format %d", info.format);
        return JNI_FALSE;
    }
    if (info.width == 0 || info.height == 0) return JNI_FALSE;

    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_lockPixels failed");
        return JNI_FALSE;
    }

    jboolean ok = JNI_FALSE;
    pthread_mutex_lock(&g_pdfium);
    FPDF_PAGE page = ensure_page(d, pageIndex);
    if (page != NULL) {
        FPDF_BITMAP bmp = FPDFBitmap_CreateEx((int) info.width, (int) info.height,
                                              FPDFBitmap_BGRA, pixels, (int) info.stride);
        if (bmp != NULL) {
            FPDFBitmap_FillRect(bmp, 0, 0, (int) info.width, (int) info.height, 0xFFFFFFFF);
            FPDF_RenderPageBitmap(bmp, page, -tileLeft, -tileTop, scaledWidth, scaledHeight,
                                  0 /* no extra rotation; page /Rotate is already applied */,
                                  flags | FPDF_REVERSE_BYTE_ORDER);
            FPDFBitmap_Destroy(bmp);   /* does not free `pixels` — the Bitmap still owns them */
            ok = JNI_TRUE;
        }
    }
    pthread_mutex_unlock(&g_pdfium);

    AndroidBitmap_unlockPixels(env, bitmap);
    return ok;
}

/* Resolves a bookmark to a 0-based page index, or -1 when it points somewhere we cannot
 * follow (an external file, a URI, or a destination the document never defines). */
static int bookmark_page(FPDF_DOCUMENT doc, FPDF_BOOKMARK bm) {
    FPDF_DEST dest = FPDFBookmark_GetDest(doc, bm);
    if (dest == NULL) {
        FPDF_ACTION action = FPDFBookmark_GetAction(bm);
        if (action != NULL && FPDFAction_GetType(action) == PDFACTION_GOTO) {
            dest = FPDFAction_GetDest(doc, action);
        }
    }
    return dest != NULL ? FPDFDest_GetDestPageIndex(doc, dest) : -1;
}

typedef struct {
    JNIEnv       *env;
    FPDF_DOCUMENT doc;
    int           n;        /* entries emitted so far */
    int           cap;
    jobjectArray  titles;   /* NULL on the counting pass */
    jint         *depths;
    jint         *pages;
} OutlineWalk;

/* Pre-order DFS. Runs twice with identical ordering: once to count, once to fill. Two passes
 * rather than a growable list because each title is a JNI local ref, and the default local
 * reference table holds far fewer than OUTLINE_MAX_ENTRIES of them. */
static void walk_outline(OutlineWalk *w, FPDF_BOOKMARK parent, int depth) {
    if (depth > OUTLINE_MAX_DEPTH) return;

    FPDF_BOOKMARK bm = FPDFBookmark_GetFirstChild(w->doc, parent);
    int siblings = 0;
    while (bm != NULL && w->n < w->cap && siblings < OUTLINE_MAX_SIBLINGS) {
        int slot = w->n++;

        if (w->titles != NULL) {
            /* FPDFBookmark_GetTitle returns UTF-16LE including the terminating NUL, and
             * returns the required byte count whatever buffer you pass. jchar is UTF-16 and
             * arm64 Android is little-endian, so NewString takes the buffer as-is. */
            unsigned long bytes = FPDFBookmark_GetTitle(bm, NULL, 0);
            jstring title = NULL;
            if (bytes >= 2 * sizeof(jchar)) {
                jchar *buf = malloc(bytes);
                if (buf != NULL) {
                    FPDFBookmark_GetTitle(bm, buf, bytes);
                    title = (*w->env)->NewString(w->env, buf,
                                                 (jsize) (bytes / sizeof(jchar)) - 1);
                    free(buf);
                }
            }
            if (title == NULL) title = (*w->env)->NewStringUTF(w->env, "");
            (*w->env)->SetObjectArrayElement(w->env, w->titles, slot, title);
            (*w->env)->DeleteLocalRef(w->env, title);

            w->depths[slot] = depth;
            w->pages[slot]  = bookmark_page(w->doc, bm);
        }

        walk_outline(w, bm, depth + 1);
        bm = FPDFBookmark_GetNextSibling(w->doc, bm);
        siblings++;
    }
}

/*
 * Returns the outline flattened into {String[] titles, int[] depths, int[] pageIndices},
 * all the same length, in pre-order. Flat parallel arrays rather than a node tree built in
 * C: the tree shape is fully recoverable from the depth column, and this needs no FindClass
 * or constructor lookup across the JNI boundary. A document with no outline returns three
 * empty arrays, never null.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_absolutex_source_pdf_Pdfium_nativeOutline(JNIEnv *env, jclass clazz, jlong handle) {
    (void) clazz;
    PdfDoc *d = (PdfDoc *) (intptr_t) handle;
    if (d == NULL) return NULL;

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jclass objectClass = (*env)->FindClass(env, "java/lang/Object");
    if (stringClass == NULL || objectClass == NULL) return NULL;

    pthread_mutex_lock(&g_pdfium);
    OutlineWalk count = { env, d->doc, 0, OUTLINE_MAX_ENTRIES, NULL, NULL, NULL };
    walk_outline(&count, NULL, 0);
    int n = count.n;
    pthread_mutex_unlock(&g_pdfium);

    jobjectArray titles = (*env)->NewObjectArray(env, (jsize) n, stringClass, NULL);
    jintArray    depths = (*env)->NewIntArray(env, (jsize) n);
    jintArray    pages  = (*env)->NewIntArray(env, (jsize) n);
    if (titles == NULL || depths == NULL || pages == NULL) return NULL;

    if (n > 0) {
        jint *depthBuf = calloc((size_t) n, sizeof(jint));
        jint *pageBuf  = calloc((size_t) n, sizeof(jint));
        if (depthBuf == NULL || pageBuf == NULL) { free(depthBuf); free(pageBuf); return NULL; }

        pthread_mutex_lock(&g_pdfium);
        OutlineWalk fill = { env, d->doc, 0, n, titles, depthBuf, pageBuf };
        walk_outline(&fill, NULL, 0);
        pthread_mutex_unlock(&g_pdfium);

        (*env)->SetIntArrayRegion(env, depths, 0, (jsize) n, depthBuf);
        (*env)->SetIntArrayRegion(env, pages, 0, (jsize) n, pageBuf);
        free(depthBuf);
        free(pageBuf);
    }

    jobjectArray out = (*env)->NewObjectArray(env, 3, objectClass, NULL);
    if (out == NULL) return NULL;
    (*env)->SetObjectArrayElement(env, out, 0, titles);
    (*env)->SetObjectArrayElement(env, out, 1, depths);
    (*env)->SetObjectArrayElement(env, out, 2, pages);
    return out;
}
