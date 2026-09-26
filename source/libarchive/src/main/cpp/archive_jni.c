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
#include <limits.h>
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

/* archive_error_string() may return NULL (no error latched); %s of NULL is UB. */
static const char *errstr(struct archive *a) {
    const char *s = archive_error_string(a);
    return s != NULL ? s : "(no error)";
}

/* strerror() is not thread-safe (shared static buffer) and the decode pool calls us
   concurrently. Our targets (bionic, macOS host harness) provide the XSI strerror_r
   returning int with the message in buf; the GNU variant's pointer return does not
   apply here. */
static const char *errno_str(int err, char *buf, size_t n) {
    buf[0] = '\0';
    (void) strerror_r(err, buf, n);
    buf[n - 1] = '\0';
    return buf[0] ? buf : "unknown error";
}

#define BLOCK_SIZE 65536

/* A single entry larger than this is hostile input, not a comic page. Bounds the
   growable read below for entries with no declared size, and caps declared sizes too.
   Total native pressure is this cap TIMES the decode-pool width (each worker holds at
   most one entry buffer), so keep the pool narrow — see DecodeDispatchers. */
#define MAX_ENTRY_BYTES (128L * 1024 * 1024)

/* Initial malloc for an entry whose header declares its size: min(declared, this),
   then grown geometrically as bytes actually arrive. A lying 128 MB header therefore
   costs 8 MB up front, not 128 MB times the pool width. */
#define SIZED_START_MAX (8L * 1024 * 1024)

/* More entries than any real comic; a fuzzed central directory claiming millions of
   entries otherwise grows the names table (and the returned byte[][]) without bound. */
#define MAX_ENTRIES 20000

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
    char ebuf[64];
    LOGE("private_fd: /proc reopen failed (%s) - falling back to dup(), NOT concurrency-safe",
         errno_str(errno, ebuf, sizeof(ebuf)));
    return dup(fd);
}

/* Only fixed messages cross JNI: never expose a password or libarchive's input-derived text. */
static void throw_named(JNIEnv *env, const char *type, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass cls = (*env)->FindClass(env, type);
    if (cls == NULL) return;
    (*env)->ThrowNew(env, cls, message);
    (*env)->DeleteLocalRef(env, cls);
}

#define PASSWORD_REQUIRED "com/absolutex/source/libarchive/PasswordRequiredException"
#define WRONG_PASSWORD "com/absolutex/source/libarchive/WrongPasswordException"
#define UNSUPPORTED_ENCRYPTION "com/absolutex/source/libarchive/UnsupportedEncryptionException"

/* libarchive has no typed password error code. Match its known fixed diagnostics narrowly;
   a CRC/data failure is NOT evidence of a wrong password and stays an IO/recovery failure. */
static void password_error(JNIEnv *env, struct archive *a) {
    const char *s = errstr(a);
    if (strstr(s, "encryption support unavailable") ||
        strstr(s, "encrypted data is not currently supported") ||
        strstr(s, "encrypted, but currently not supported") ||
        strstr(s, "Decryption is unsupported") ||
        strstr(s, "Crypto codec not supported yet") ||
        strstr(s, "Unsupported encryption format version:") ||
        strcmp(s, "Encrypted file is unsupported") == 0 ||
        strcmp(s, "Encryption is not supported") == 0) {
        throw_named(env, UNSUPPORTED_ENCRYPTION, "Archive encryption is not supported by this build");
    } else if (strcmp(s, "Passphrase required for this entry") == 0) {
        throw_named(env, PASSWORD_REQUIRED, "Archive password required");
    } else if (strcmp(s, "Incorrect passphrase") == 0 ||
               strcmp(s, "Too many incorrect passphrases") == 0) {
        throw_named(env, WRONG_PASSWORD, "Archive password rejected");
    }
}

static struct archive *open_fd(JNIEnv *env, int fd, int *dup_out, const char *passphrase) {
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
    /* Narrow filter set, not filter_all: every registered bidder sniffs hostile magic,
       so only the filters our formats use are enabled (none for stored/zip entries,
       gzip for .cbt, plus bzip2/xz/zstd/lz4 for tars that declare them). Codecs whose
       CMake switch is OFF degrade to a clean ARCHIVE_FATAL here, never a crash. */
    archive_read_support_filter_none(a);
    archive_read_support_filter_gzip(a);
    archive_read_support_filter_bzip2(a);
    archive_read_support_filter_xz(a);
    archive_read_support_filter_zstd(a);
    archive_read_support_filter_lz4(a);

    if (passphrase != NULL && archive_read_add_passphrase(a, passphrase) != ARCHIVE_OK) {
        throw_named(env, "java/io/IOException", "Could not register archive password");
        archive_read_free(a);
        close(dfd);
        return NULL;
    }
    if (archive_read_open_fd(a, dfd, BLOCK_SIZE) != ARCHIVE_OK) {
        password_error(env, a);
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
/* g_utf8 is process-lifetime and intentionally never freed: freeing a locale still
   installed by another thread is a use-after-free, and one small allocation for the
   life of the process is benign. */
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
static jbyteArray read_entry(JNIEnv *env, struct archive *a, struct archive_entry *e);

static jobjectArray
list_impl(JNIEnv *env, jclass clazz, jint fd, jbooleanArray complete,
          jbooleanArray encrypted, const char *passphrase) {
    (void) clazz;
    if (fd < 0 || complete == NULL || (*env)->GetArrayLength(env, complete) != 1) return NULL;
    if (encrypted == NULL || (*env)->GetArrayLength(env, encrypted) != 1) return NULL;
    jboolean found_encrypted = JNI_FALSE;
    (*env)->SetBooleanArrayRegion(env, encrypted, 0, 1, &found_encrypted);
    if ((*env)->ExceptionCheck(env)) return NULL;
    jboolean finished = JNI_FALSE;
    (*env)->SetBooleanArrayRegion(env, complete, 0, 1, &finished);
    if ((*env)->ExceptionCheck(env)) return NULL;
    int dfd = -1;
    struct archive *a = open_fd(env, fd, &dfd, passphrase);
    if (a == NULL) return NULL;

    size_t cap = 64, n = 0;
    char **names = malloc(cap * sizeof(char *));
    if (names == NULL) { close_archive(a, dfd); return NULL; }

    struct archive_entry *entry;
    int r;
    while (header_ok(r = archive_read_next_header(a, &entry))) {
        if (archive_entry_is_encrypted(entry) > 0) {
            found_encrypted = JNI_TRUE;
            if (passphrase == NULL) {
                throw_named(env, PASSWORD_REQUIRED, "Archive password required");
                break;
            }
            /* Single password probe deferred to Kotlin: nativeList only tracks the flag.
               The Kotlin side performs one nativeExtract call to verify the password. */
        }
        if (!is_ordinal_entry(entry)) continue;
        if (n >= MAX_ENTRIES) {
            LOGE("nativeList: too many entries (>= %d), truncating list", MAX_ENTRIES);
            break;
        }
        if (n == cap) {
            size_t ncap = cap * 2;
            if (ncap <= cap || ncap > (size_t) MAX_ENTRIES + 64) ncap = (size_t) MAX_ENTRIES + 64;
            char **grown = realloc(names, ncap * sizeof(char *));
            if (grown == NULL) break;   // keep what we have rather than lose the whole list
            names = grown;
            cap = ncap;
        }
        char *copy = strdup(entry_name(entry));
        if (copy == NULL) break;
        names[n++] = copy;
    }
    if (archive_read_has_encrypted_entries(a) > 0) found_encrypted = JNI_TRUE;
    if (r != ARCHIVE_EOF && !header_ok(r)) password_error(env, a);
    close_archive(a, dfd);
    if ((*env)->ExceptionCheck(env)) goto fail;
    (*env)->SetBooleanArrayRegion(env, encrypted, 0, 1, &found_encrypted);
    if ((*env)->ExceptionCheck(env)) goto fail;
    finished = r == ARCHIVE_EOF ? JNI_TRUE : JNI_FALSE;
    (*env)->SetBooleanArrayRegion(env, complete, 0, 1, &finished);
    if ((*env)->ExceptionCheck(env)) goto fail;

    /* n <= MAX_ENTRIES (20000) < INT_MAX, so the (jsize) casts below cannot overflow;
       the explicit check is defense in depth against future cap changes. */
    if (n > (size_t) INT_MAX) {
        LOGE("nativeList: entry count %zu exceeds jsize range", n);
        goto fail;
    }

    jobjectArray out = NULL;
    jclass byteArrayClass = (*env)->FindClass(env, "[B");
    if (byteArrayClass == NULL || (*env)->ExceptionCheck(env)) goto fail;
    out = (*env)->NewObjectArray(env, (jsize) n, byteArrayClass, NULL);
    if (out == NULL || (*env)->ExceptionCheck(env)) { out = NULL; goto done; }
    for (size_t i = 0; i < n; i++) {
        size_t namelen = strlen(names[i]);
        if (namelen > (size_t) INT_MAX) {
            LOGE("nativeList: entry %zu name too long", i);
            out = NULL;
            goto done;
        }
        jbyteArray bytes = (*env)->NewByteArray(env, (jsize) namelen);
        if (bytes == NULL || (*env)->ExceptionCheck(env)) { out = NULL; goto done; }
        (*env)->SetByteArrayRegion(env, bytes, 0, (jsize) namelen, (const jbyte *) names[i]);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->DeleteLocalRef(env, bytes);
            out = NULL;
            goto done;
        }
        (*env)->SetObjectArrayElement(env, out, (jsize) i, bytes);
        (*env)->DeleteLocalRef(env, bytes);   // one local ref per entry would overflow
        if ((*env)->ExceptionCheck(env)) { out = NULL; goto done; }
    }
done:
    (*env)->DeleteLocalRef(env, byteArrayClass);
    {
        int failed = (*env)->ExceptionCheck(env);
        for (size_t i = 0; i < n; i++) free(names[i]);
        free(names);
        return failed ? NULL : out;
    }
fail:
    for (size_t i = 0; i < n; i++) free(names[i]);
    free(names);
    return NULL;
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

    /* min(declared, 8 MB) up front, then grow as bytes arrive — never one giant malloc
       against an untrusted header. Total is still bounded: sized entries by declared
       (<= 128 MB), unsized by MAX_ENTRY_BYTES. */
    size_t cap = sized
        ? (size_t) (declared <= 0 ? 1 : declared < SIZED_START_MAX ? declared : SIZED_START_MAX)
        : UNKNOWN_SIZE_START;
    char *buf = malloc(cap);
    if (buf == NULL) return NULL;

    size_t len = 0;
    for (;;) {
        if (len == cap) {
            if (sized && len >= (size_t) declared) break;   // declared size fully read
            size_t limit = sized ? (size_t) declared : (size_t) MAX_ENTRY_BYTES;
            if (cap >= limit) {
                if (!sized) {
                    LOGE("entry exceeds %ld bytes", MAX_ENTRY_BYTES);
                    free(buf);
                    return NULL;
                }
                break;   // unreachable (len == cap == declared hits the branch above)
            }
            size_t grow = cap * 2;
            if (grow <= cap || grow > limit) grow = limit;   // overflow-clamp, then exact-fit
            char *g = realloc(buf, grow);
            if (g == NULL) { free(buf); return NULL; }
            buf = g;
            cap = grow;
        }
        la_ssize_t got = archive_read_data(a, buf + len, cap - len);
        if (got == 0) break;              // end of entry
        if (got < 0) {
            /* Fail closed: a mid-entry WARN/error means the bytes may be corrupt (bad
               CRC, truncated data). Returning partial bytes would surface a torn page
               — or hostile content — as valid; NULL maps to a generic error upstream. */
            password_error(env, a);
            free(buf);
            return NULL;
        }
        len += (size_t) got;
    }

    /* Encrypted entries must reach the authentication/CRC trailer, not just their declared
       byte count. Some readers report final validation on the next read. */
    if (sized && (la_int64_t) len == declared && archive_entry_is_encrypted(e) > 0) {
        char extra;
        if (archive_read_data(a, &extra, 1) != 0) {
            password_error(env, a);
            free(buf);
            return NULL;
        }
    }
    if (sized && (la_int64_t) len != declared) {
        LOGE("short read: %zu of %lld bytes: %s", len, (long long) declared, errstr(a));
        free(buf);
        return NULL;   // truncated entry -> caller sees it as unreadable, not as garbage
    }
    /* Unsized entries have no declared size to check against: got == 0 (end of entry)
       is the only clean terminator, and the loop above already enforces it — every
       negative return fails closed. A stale nonzero archive_errno here usually traces
       to a benign WARN already tolerated at header time (e.g. an untranscodable name),
       so it is logged for triage, not fatal: failing on it would reintroduce the
       warn-truncation regression the header_ok() contract fixed. */
    if (!sized && archive_errno(a) != 0) {
        LOGE("unsized entry accepted with pending archive status %d: %s",
             archive_errno(a), errstr(a));
    }

    /* len <= 128 MB < INT_MAX, so the (jsize) casts cannot overflow; checked anyway. */
    if (len > (size_t) INT_MAX) {
        LOGE("entry too large for Java array: %zu bytes", len);
        free(buf);
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize) len);
    if (out == NULL || (*env)->ExceptionCheck(env)) { free(buf); return NULL; }
    (*env)->SetByteArrayRegion(env, out, 0, (jsize) len, (const jbyte *) buf);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->DeleteLocalRef(env, out);
        free(buf);
        return NULL;
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
             jint fd, jint ordinal, const char *passphrase) {
    (void) clazz;
    if (fd < 0 || ordinal < 0) return NULL;

    int dfd = -1;
    struct archive *a = open_fd(env, fd, &dfd, passphrase);
    if (a == NULL) return NULL;

    jbyteArray result = NULL;
    struct archive_entry *entry;
    jint seen = -1;
    int r;
    while (header_ok(r = archive_read_next_header(a, &entry))) {
        if (!is_ordinal_entry(entry)) continue;
        if (++seen != ordinal) continue;
        if (archive_entry_is_encrypted(entry) > 0 && passphrase == NULL) {
            throw_named(env, PASSWORD_REQUIRED, "Archive password required");
        } else {
            result = read_entry(env, a, entry);
        }
        break;
    }
    if (seen < ordinal && r != ARCHIVE_EOF) {
        password_error(env, a);
    }

    close_archive(a, dfd);
    return result;
}

/* Copy standard UTF-8, not JNI Modified UTF-8 (non-BMP passwords must round-trip).
   The bridge's copy is scrubbed on every exit. libarchive owns its internal copy until free. */
static void wipe_free(char *s) {
    if (s == NULL) return;
    size_t n = strlen(s);
    volatile char *p = s;
    while (n--) *p++ = 0;
    free(s);
}

static char *copy_passphrase(JNIEnv *env, jbyteArray bytes) {
    if (bytes == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, bytes);
    char *s = calloc((size_t) n + 1, 1);
    if (s == NULL) {
        throw_named(env, "java/lang/OutOfMemoryError", "Archive password allocation failed");
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, bytes, 0, n, (jbyte *) s);
    if ((*env)->ExceptionCheck(env)) { wipe_free(s); return NULL; }
    if (n == 0 || memchr(s, 0, (size_t) n) != NULL) {
        volatile char *p = s;
        for (jsize i = 0; i < n; i++) p[i] = 0;
        free(s);
        throw_named(env, "java/lang/IllegalArgumentException", "Password must be nonempty and contain no NUL");
        return NULL;
    }
    return s;
}

JNIEXPORT jobjectArray JNICALL
Java_com_absolutex_source_libarchive_LibArchive_nativeList(JNIEnv *env, jclass clazz, jint fd,
        jbooleanArray complete, jbooleanArray encrypted, jbyteArray password) {
    char *passphrase = copy_passphrase(env, password);
    if ((*env)->ExceptionCheck(env)) return NULL;
    locale_t prev = enter_utf8();
    jobjectArray r = list_impl(env, clazz, fd, complete, encrypted, passphrase);
    leave_utf8(prev);
    wipe_free(passphrase);
    return r;
}

/*
 * Extracts [count] consecutive entries starting at [fromOrdinal], in ONE header walk.
 *
 * This exists because the per-ordinal extract_impl re-walks from zero every call, and the
 * prefetch window is by definition a contiguous run. Measured on the decode M11 corpus (a
 * 300-page CBZ of 6 MP JPEG, host harness tools/bench-decode.sh): reaching page 299 costs
 * ~1.6 ms more than reaching page 0, which is 34% of a whole first page -- paid again for
 * every window the reader opens, not once per book. The cost was invisible until now because
 * every existing measurement looked at page 0, where the walk is zero headers.
 *
 * So the walk is hoisted out of the loop rather than made cheaper: a run of N consecutive
 * entries costs N headers, once, instead of sum(i) headers across N calls. Sequential access
 * is also the access pattern libarchive's reader is optimised for, so the win is larger than
 * the header count alone.
 *
 * The ordinal contract is deliberately IDENTICAL to extract_impl's: both count entries
 * through is_ordinal_entry, so a window and a loop of single extracts must agree entry for
 * entry. Any future divergence is a silent "asking for page N extracts some other entry"
 * bug, which is the exact failure the by-ordinal design exists to prevent.
 *
 * A null element means that one entry was absent or unreadable -- the same verdict the
 * single-extract path gives, per entry, so a caller cannot tell from a window which of the
 * two paths produced it. Never throws for one bad entry: a 300-page book with one torn page
 * must still yield 299 readable ones.
 */
static jobjectArray
extract_window_impl(JNIEnv *env, jclass clazz,
                    jint fd, jint fromOrdinal, jint count, const char *passphrase) {
    (void) clazz;
    if (fd < 0 || fromOrdinal < 0 || count <= 0) return NULL;

    int dfd = -1;
    struct archive *a = open_fd(env, fd, &dfd, passphrase);
    if (a == NULL) return NULL;

    jclass array_class = (*env)->FindClass(env, "[B");
    if (array_class == NULL) { close_archive(a, dfd); return NULL; }
    jobjectArray result = (*env)->NewObjectArray(env, count, array_class, NULL);
    if (result == NULL) { close_archive(a, dfd); return NULL; }

    struct archive_entry *entry;
    jint seen = -1;
    jint produced = 0;
    int r;
    int need_password = 0;
    /* Stop as soon as the run is filled: walking past the last wanted entry would make a
       window at the end of a book pay for headers nobody asked for. */
    while (produced < count && header_ok(r = archive_read_next_header(a, &entry))) {
        if (!is_ordinal_entry(entry)) continue;
        if (++seen < fromOrdinal) continue;
        if (seen >= fromOrdinal + count) break;

        if (archive_entry_is_encrypted(entry) > 0 && passphrase == NULL) {
            /* No exception-throwing call may run from here until the archive is closed: JNI
               forbids most calls while an exception is pending, and -Xcheck:jni reports it on
               a device run exactly as it does on the host. So the flag is latched, the walk
               stops, and the throw happens after close_archive. */
            need_password = 1;
            break;
        }
        jbyteArray bytes = read_entry(env, a, entry);
        /* read_entry calls password_error, which THROWS, on a wrong passphrase mid-entry.
           That leaves an exception pending, and continuing the walk would make JNI calls with
           one pending -- forbidden, and reported by -Xcheck:jni on device exactly as on the
           host. So the run stops here and the already-thrown exception propagates, which is
           also the right semantics: a wrong passphrase invalidates the whole window, not just
           the entry that happened to fail first. */
        if ((*env)->ExceptionCheck(env)) break;
        /* A null element stays null. read_entry has already turned a torn entry into NULL
           with the reason logged, and set_error_flags latches nothing here, so one bad
           page does not poison the rest of the run. */
        (*env)->SetObjectArrayElement(env, result, produced, bytes);
        if ((*env)->ExceptionCheck(env)) break;
        if (bytes != NULL) (*env)->DeleteLocalRef(env, bytes);
        produced++;
    }
    /* Once an exception is pending, NO JNI call may follow. errstr reads libarchive state
       (not a JNI call, so it is safe to read) but throw_named and ExceptionCheck are not, so
       the archive is closed first and the pending exception is simply left to propagate --
       there is nothing to add to it. */
    if (!(*env)->ExceptionCheck(env) && produced < count && r == ARCHIVE_FATAL) {
        /* A short run is a short book, not a failure: the tail stays null rather than
           throwing, matching extract_impl returning NULL for an unreachable ordinal. */
        char err[128];
        snprintf(err, sizeof(err), "%s", errstr(a));
        close_archive(a, dfd);
        LOGE("extract window %d..%d short: %s", (int) fromOrdinal, (int) (fromOrdinal + count), err);
        return result;
    }

    close_archive(a, dfd);
    if (need_password && !(*env)->ExceptionCheck(env)) {
        /* No throw happened during the walk, so make the one the single-extract path would
           have made: same exception, same fixed message, same input. */
        throw_named(env, PASSWORD_REQUIRED, "Archive password required");
    }
    /* A pending exception means the caller gets an exception, not this array. Returning the
       partial run anyway would be a lie about how much of the window is trustworthy. */
    return (*env)->ExceptionCheck(env) ? NULL : result;
}

JNIEXPORT jobjectArray JNICALL
Java_com_absolutex_source_libarchive_LibArchive_nativeExtractWindow(JNIEnv *env, jclass clazz,
        jint fd, jint fromOrdinal, jint count, jbyteArray password) {
    char *passphrase = copy_passphrase(env, password);
    if ((*env)->ExceptionCheck(env)) return NULL;
    locale_t prev = enter_utf8();
    jobjectArray r = extract_window_impl(env, clazz, fd, fromOrdinal, count, passphrase);
    leave_utf8(prev);
    wipe_free(passphrase);
    return r;
}

JNIEXPORT jbyteArray JNICALL
Java_com_absolutex_source_libarchive_LibArchive_nativeExtract(JNIEnv *env, jclass clazz,
        jint fd, jint ordinal, jbyteArray password) {
    char *passphrase = copy_passphrase(env, password);
    if ((*env)->ExceptionCheck(env)) return NULL;
    locale_t prev = enter_utf8();
    jbyteArray r = extract_impl(env, clazz, fd, ordinal, passphrase);
    leave_utf8(prev);
    wipe_free(passphrase);
    return r;
}
