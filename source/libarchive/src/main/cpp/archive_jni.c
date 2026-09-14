// JNI bridge over libarchive. Deliberately stateless: every call opens its own
// `struct archive` on a dup'd fd, so calls are thread-safe by construction and the decode
// pool can fan pages across the big cores with no locking and no handle lifecycle.
//
// ponytail: re-walks entry headers on each extract instead of caching an offset index.
// libarchive's reader is forward-only, but header-walking a non-solid archive does not
// decompress — measured 27 ms to reach entry 45 of 45 vs 699 ms for a full pass on the
// reference corpus. If a solid archive or a 1000-page book makes this show up in the page-turn
// budget, cache (name -> header offset) on first open and seek instead.

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>
#include <archive.h>
#include <archive_entry.h>

#define LOG_TAG "absolutex.archive"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define BLOCK_SIZE 65536

static struct archive *open_fd(int fd, int *dup_out) {
    int dfd = dup(fd);
    if (dfd < 0) return NULL;
    if (lseek(dfd, 0, SEEK_SET) < 0) { close(dfd); return NULL; }

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

static void close_archive(struct archive *a, int dfd) {
    if (a) archive_read_free(a);
    if (dfd >= 0) close(dfd);
}

/**
 * Lists regular-file entry names in archive order.
 * A truncated archive yields the entries read before the failure rather than throwing —
 * the brief requires degrading to "N of M readable", never crashing.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_absolutex_source_libarchive_LibArchive_nativeList(JNIEnv *env, jclass clazz, jint fd) {
    (void) clazz;
    int dfd = -1;
    struct archive *a = open_fd(fd, &dfd);
    if (a == NULL) return NULL;

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    size_t cap = 64, n = 0;
    char **names = malloc(cap * sizeof(char *));
    if (names == NULL) { close_archive(a, dfd); return NULL; }

    struct archive_entry *entry;
    int r;
    while ((r = archive_read_next_header(a, &entry)) == ARCHIVE_OK) {
        if (archive_entry_filetype(entry) != AE_IFREG) continue;
        const char *name = archive_entry_pathname_utf8(entry);
        if (name == NULL) name = archive_entry_pathname(entry);
        if (name == NULL) continue;

        if (n == cap) {
            cap *= 2;
            char **grown = realloc(names, cap * sizeof(char *));
            if (grown == NULL) break;
            names = grown;
        }
        names[n++] = strdup(name);
    }
    if (r != ARCHIVE_EOF && r != ARCHIVE_OK) {
        LOGE("nativeList stopped early: %s", archive_error_string(a));  // truncated: keep what we have
    }
    close_archive(a, dfd);

    jobjectArray out = (*env)->NewObjectArray(env, (jsize) n, stringClass, NULL);
    for (size_t i = 0; i < n; i++) {
        if (out != NULL) {
            jstring s = (*env)->NewStringUTF(env, names[i]);
            (*env)->SetObjectArrayElement(env, out, (jsize) i, s);
            (*env)->DeleteLocalRef(env, s);
        }
        free(names[i]);
    }
    free(names);
    return out;
}

/** Extracts one entry by name. Returns null if absent or unreadable. */
JNIEXPORT jbyteArray JNICALL
Java_com_absolutex_source_libarchive_LibArchive_nativeExtract(JNIEnv *env, jclass clazz,
                                                              jint fd, jstring jname) {
    (void) clazz;
    const char *want = (*env)->GetStringUTFChars(env, jname, NULL);
    if (want == NULL) return NULL;

    int dfd = -1;
    struct archive *a = open_fd(fd, &dfd);
    if (a == NULL) { (*env)->ReleaseStringUTFChars(env, jname, want); return NULL; }

    jbyteArray result = NULL;
    struct archive_entry *entry;
    while (archive_read_next_header(a, &entry) == ARCHIVE_OK) {
        const char *name = archive_entry_pathname_utf8(entry);
        if (name == NULL) name = archive_entry_pathname(entry);
        if (name == NULL || strcmp(name, want) != 0) continue;

        la_int64_t size = archive_entry_size(entry);
        if (size <= 0 || size > (la_int64_t) INT32_MAX) break;

        result = (*env)->NewByteArray(env, (jsize) size);
        if (result == NULL) break;

        jbyte *buf = (*env)->GetByteArrayElements(env, result, NULL);
        la_ssize_t got = archive_read_data(a, buf, (size_t) size);
        (*env)->ReleaseByteArrayElements(env, result, buf, 0);

        if (got != (la_ssize_t) size) {
            LOGE("short read on '%s': %s", want, archive_error_string(a));
            result = NULL;   // truncated entry -> caller sees it as unreadable
        }
        break;
    }

    close_archive(a, dfd);
    (*env)->ReleaseStringUTFChars(env, jname, want);
    return result;
}
