// JNI bridge over libarchive. Deliberately stateless: every call opens its own
// `struct archive` on a private descriptor, so calls are independent and the decode pool can
// fan pages across the big cores with no locking and no handle lifecycle.
//
// ponytail: re-walks entry headers on each extract instead of caching an offset index.
// libarchive's reader is forward-only, but header-walking a non-solid archive does not
// decompress — measured 2.4 ms to reach the last of 45 entries on the reference device. If a
// solid archive or a 1000-page book makes this show up in the page-turn budget, cache
// (ordinal -> header offset) on first open and seek instead.

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <locale.h>
#ifdef __APPLE__
#include <xlocale.h>   /* host test harness only; bionic declares uselocale in locale.h */
#endif
#include <pthread.h>
#include <android/log.h>
#include <archive.h>
#include <archive_entry.h>

#define LOG_TAG "absolutex.archive"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define BLOCK_SIZE 65536

/* A single entry larger than this is hostile input, not a comic page — and a Java byte[] could
   not hold much more anyway. Bounds the growable read below for entries with no declared size. */
#define MAX_ENTRY_BYTES (512L * 1024 * 1024)

/* Starting buffer for an entry whose header does not declare its size. */
#define UNKNOWN_SIZE_START (256 * 1024)

/*
 * Returns a PRIVATE descriptor for the same file.
 *
 * dup() is wrong here: the copy shares its file offset with the original, so two threads
 * reading pages at once move each other's position and both get short/garbage reads.
 *
 * Re-opening /proc/self/fd/N creates an independent open file description with its own
 * offset. dup() remains as a fallback for descriptors that cannot be re-opened this way. SAF
 * descriptors land there — the app has no path access, so the re-open re-checks permission and
 * fails with EACCES — which is why LibArchiveSource hands us a fresh descriptor per call.
 */
static int private_fd(int fd) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    int p = open(path, O_RDONLY | O_CLOEXEC);
    if (p >= 0) return p;
    LOGE("private_fd: /proc reopen failed (%s) - falling back to dup(), NOT concurrency-safe",
         strerror(errno));
    return dup(fd);
}

static struct archive *open_fd(int fd, int *dup_out) {
    int dfd = private_fd(fd);
    if (dfd < 0) return NULL;
    /* A pipe cannot seek; libarchive then uses its streaming readers, so this is not fatal. */
    (void) lseek(dfd, 0, SEEK_SET);

    struct archive *a = archive_read_new();
    if (a == NULL) { close(dfd); return NULL; }

    archive_read_support_format_zip(a);
    archive_read_support_format_rar(a);    // RAR4 — what the reference corpus actually is
    archive_read_support_format_rar5(a);
    archive_read_support_format_7zip(a);
    archive_read_support_format_tar(a);
    archive_read_support_filter_all(a);

    if (archive_read_open_fd(a, dfd, BLOCK_SIZE) != ARCHIVE_OK) {
        LOGE("open_fd: %s", archive_error_string(a));
        archive_read_free(a);
        close(dfd);
        return NULL;
    }
    *dup_out = dfd;
    return a;
}

/*
 * libarchive converts entry names to "the current locale". An app's native code runs in the C
 * locale, where a UTF-8 name like "\U0001F600 cover.jpg" cannot be represented: libarchive returns
 * ARCHIVE_WARN and a NULL name, and the page silently disappears. Verified on libarchive 3.8.9.
 * uselocale() is thread-local, so scoping a UTF-8 locale to each JNI call fixes names without
 * touching the process-wide locale other code relies on.
 */
static locale_t g_utf8;
static pthread_once_t g_utf8_once = PTHREAD_ONCE_INIT;
static void init_utf8(void) {
    g_utf8 = newlocale(LC_CTYPE_MASK, "C.UTF-8", (locale_t) 0);
    if (g_utf8 == (locale_t) 0) g_utf8 = newlocale(LC_CTYPE_MASK, "en_US.UTF-8", (locale_t) 0);
}
static locale_t enter_utf8(void) {
    pthread_once(&g_utf8_once, init_utf8);
    return g_utf8 ? uselocale(g_utf8) : (locale_t) 0;
}
static void leave_utf8(locale_t prev) { if (prev) uselocale(prev); }

static void close_archive(struct archive *a, int dfd) {
    if (a) archive_read_free(a);
    if (dfd >= 0) close(dfd);
}

/*
 * ARCHIVE_WARN is not an error. libarchive returns it for recoverable conditions — an unusual
 * header attribute, a name it could not transcode — and the entry is still readable. Treating it
 * as fatal silently truncated the page list at the first such entry.
 */
static int header_ok(int r) { return r == ARCHIVE_OK || r == ARCHIVE_WARN; }

/*
 * THE definition of which entries receive an ordinal. nativeList and nativeExtract must agree
 * on it exactly: if they ever diverge, asking for page N silently extracts some other entry.
 * Every regular file gets a slot, including one whose name is missing, so ordinals never shift.
 */
static int is_ordinal_entry(struct archive_entry *e) {
    return archive_entry_filetype(e) == AE_IFREG;
}

/* Name bytes as libarchive has them. Never NULL: a nameless entry gets "" and is filtered out
   by the Kotlin side, keeping its ordinal slot occupied. */
static const char *entry_name(struct archive_entry *e) {
    const char *name = archive_entry_pathname_utf8(e);
    if (name == NULL) name = archive_entry_pathname(e);
    return name != NULL ? name : "";
}

/**
 * Lists every regular-file entry in archive order, as RAW NAME BYTES (byte[][]).
 *
 * Bytes rather than jstring, because NewStringUTF requires Modified UTF-8 and real archive names
 * are not that: a 4-byte UTF-8 sequence (emoji) is invalid Modified UTF-8, and a Japanese scan's
 * Shift-JIS name is not UTF-8 at all. Handing either to NewStringUTF is undefined behaviour at
 * best and a CheckJNI abort at worst. Kotlin decodes the bytes leniently instead.
 *
 * A truncated archive yields the entries read before the failure rather than nothing — the
 * brief requires degrading to "N of M readable", never crashing.
 */
static jobjectArray
list_impl(JNIEnv *env, jclass clazz, jint fd) {
    (void) clazz;
    int dfd = -1;
    struct archive *a = open_fd(fd, &dfd);
    if (a == NULL) return NULL;

    size_t cap = 64, n = 0;
    char **names = malloc(cap * sizeof(char *));
    if (names == NULL) { close_archive(a, dfd); return NULL; }

    struct archive_entry *entry;
    int r;
    while (header_ok(r = archive_read_next_header(a, &entry))) {
        if (!is_ordinal_entry(entry)) continue;
        if (n == cap) {
            char **grown = realloc(names, cap * 2 * sizeof(char *));
            if (grown == NULL) break;   // keep what we have rather than lose the whole list
            names = grown;
            cap *= 2;
        }
        char *copy = strdup(entry_name(entry));
        if (copy == NULL) break;
        names[n++] = copy;
    }
    if (r != ARCHIVE_EOF && !header_ok(r)) {
        LOGE("nativeList stopped early after %zu entries: %s", n, archive_error_string(a));
    }
    close_archive(a, dfd);

    jobjectArray out = NULL;
    jclass byteArrayClass = (*env)->FindClass(env, "[B");
    if (byteArrayClass != NULL) {
        out = (*env)->NewObjectArray(env, (jsize) n, byteArrayClass, NULL);
    }
    for (size_t i = 0; i < n; i++) {
        if (out != NULL && !(*env)->ExceptionCheck(env)) {
            jsize len = (jsize) strlen(names[i]);
            jbyteArray bytes = (*env)->NewByteArray(env, len);
            if (bytes != NULL) {
                (*env)->SetByteArrayRegion(env, bytes, 0, len, (const jbyte *) names[i]);
                (*env)->SetObjectArrayElement(env, out, (jsize) i, bytes);
                (*env)->DeleteLocalRef(env, bytes);   // one local ref per entry would overflow
            }
        }
        free(names[i]);
    }
    free(names);
    return (*env)->ExceptionCheck(env) ? NULL : out;
}

/*
 * Reads the current entry's data to completion.
 *
 * Two things the previous version got wrong:
 *  - archive_read_data may return fewer bytes than asked for; one call is not "the entry".
 *  - Not every header declares a size. A zip written by a streaming tool carries a data
 *    descriptor instead, and read through libarchive's streaming reader the size is unset —
 *    which the old `size <= 0` check rejected outright.
 *
 * ponytail: reads into a native buffer and copies once into the Java array, rather than reading
 * straight into a critical Java region, because the read can block on I/O and a critical region
 * held across a blocking read stalls the GC. One memcpy of ~1 MB against the 250 ms open budget.
 */
static jbyteArray read_entry(JNIEnv *env, struct archive *a, struct archive_entry *e) {
    int sized = archive_entry_size_is_set(e);
    la_int64_t declared = sized ? archive_entry_size(e) : -1;
    if (sized && (declared < 0 || declared > MAX_ENTRY_BYTES)) {
        LOGE("entry size %lld out of range", (long long) declared);
        return NULL;
    }

    size_t cap = sized ? (size_t) (declared > 0 ? declared : 1) : UNKNOWN_SIZE_START;
    char *buf = malloc(cap);
    if (buf == NULL) return NULL;

    size_t len = 0;
    for (;;) {
        if (len == cap) {
            if (sized || cap >= (size_t) MAX_ENTRY_BYTES) {
                if (sized) break;   // declared size fully read
                LOGE("entry exceeds %ld bytes", MAX_ENTRY_BYTES);
                free(buf);
                return NULL;
            }
            size_t grow = cap * 2 > (size_t) MAX_ENTRY_BYTES ? (size_t) MAX_ENTRY_BYTES : cap * 2;
            char *g = realloc(buf, grow);
            if (g == NULL) { free(buf); return NULL; }
            buf = g;
            cap = grow;
        }
        la_ssize_t got = archive_read_data(a, buf + len, cap - len);
        if (got == 0) break;              // end of entry
        if (got < 0) {
            if (got == ARCHIVE_WARN && len > 0) {
                LOGE("warning mid-entry, keeping %zu bytes: %s", len, archive_error_string(a));
                break;
            }
            LOGE("read failed after %zu bytes: %s", len, archive_error_string(a));
            free(buf);
            return NULL;
        }
        len += (size_t) got;
    }

    if (sized && (la_int64_t) len != declared) {
        LOGE("short read: %zu of %lld bytes: %s", len, (long long) declared, archive_error_string(a));
        free(buf);
        return NULL;   // truncated entry -> caller sees it as unreadable, not as garbage
    }

    jbyteArray out = (*env)->NewByteArray(env, (jsize) len);
    if (out != NULL) {
        (*env)->SetByteArrayRegion(env, out, 0, (jsize) len, (const jbyte *) buf);
    }
    free(buf);
    return out;
}

/**
 * Extracts the entry at [ordinal] — its position among regular files as listed by nativeList.
 *
 * By ordinal, not by name: names do not survive a JNI round trip intact (see nativeList), and an
 * archive may legitimately hold two entries with the same name, where matching by name returns
 * the first one twice and the second one never. Returns null if absent or unreadable.
 */
static jbyteArray
extract_impl(JNIEnv *env, jclass clazz,
                                                              jint fd, jint ordinal) {
    (void) clazz;
    if (ordinal < 0) return NULL;

    int dfd = -1;
    struct archive *a = open_fd(fd, &dfd);
    if (a == NULL) return NULL;

    jbyteArray result = NULL;
    struct archive_entry *entry;
    jint seen = -1;
    int r;
    while (header_ok(r = archive_read_next_header(a, &entry))) {
        if (!is_ordinal_entry(entry)) continue;
        if (++seen != ordinal) continue;
        result = read_entry(env, a, entry);
        break;
    }
    if (seen < ordinal && r != ARCHIVE_EOF) {
        LOGE("nativeExtract: archive ended before ordinal %d: %s", ordinal, archive_error_string(a));
    }

    close_archive(a, dfd);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_com_absolutex_source_libarchive_LibArchive_nativeList(JNIEnv *env, jclass clazz, jint fd) {
    locale_t prev = enter_utf8();
    jobjectArray r = list_impl(env, clazz, fd);
    leave_utf8(prev);
    return r;
}

JNIEXPORT jbyteArray JNICALL
Java_com_absolutex_source_libarchive_LibArchive_nativeExtract(JNIEnv *env, jclass clazz,
                                                              jint fd, jint ordinal) {
    locale_t prev = enter_utf8();
    jbyteArray r = extract_impl(env, clazz, fd, ordinal);
    leave_utf8(prev);
    return r;
}
