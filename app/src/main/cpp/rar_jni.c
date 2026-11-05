#include <jni.h>
#include <archive.h>
#include <archive_entry.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef _WIN32
#include <windows.h>
#define file_seek _fseeki64
#define file_tell _ftelli64
#else
#define file_seek fseeko
#define file_tell ftello
#endif

#define PAGE_LIMIT (64LL * 1024 * 1024)
#define FILE_LIMIT (1024LL * 1024 * 1024)
#define JNI_NAME(name) Java_org_comicreader_app_RarPages_##name

int comicreader_rar_solid(struct archive *);
int comicreader_rar5_solid(struct archive *);

typedef struct {
    struct archive *archive;
    FILE *file;
    int64_t length, position, size;
    int flags, cancelled;
    JNIEnv *env;
    jobject thread;
    jmethodID interrupted;
    unsigned char input[64 * 1024];
} Reader;

static void fail(JNIEnv *env, Reader *reader, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass type = (*env)->FindClass(env, reader && reader->cancelled
        ? "java/io/InterruptedIOException" : "java/io/IOException");
    if (type) (*env)->ThrowNew(env, type, message ? message : "Cannot read the CBR archive");
}

static int prepare(Reader *reader, JNIEnv *env) {
    reader->env = env;
    reader->cancelled = 0;
    jclass type = (*env)->FindClass(env, "java/lang/Thread");
    if (!type) return 0;
    jmethodID current = (*env)->GetStaticMethodID(env, type, "currentThread", "()Ljava/lang/Thread;");
    reader->interrupted = (*env)->GetMethodID(env, type, "isInterrupted", "()Z");
    if (!current || !reader->interrupted) return 0;
    reader->thread = (*env)->CallStaticObjectMethod(env, type, current);
    (*env)->DeleteLocalRef(env, type);
    return reader->thread != NULL && !(*env)->ExceptionCheck(env);
}

static int cancelled(Reader *reader) {
    if ((*reader->env)->ExceptionCheck(reader->env)) return 1;
    if ((*reader->env)->CallBooleanMethod(reader->env, reader->thread, reader->interrupted)) {
        reader->cancelled = 1;
        archive_set_error(reader->archive, EINTR, "CBR reading cancelled");
        return 1;
    }
    return (*reader->env)->ExceptionCheck(reader->env);
}

static la_ssize_t input_read(struct archive *archive, void *context, const void **buffer) {
    Reader *reader = context;
    if (cancelled(reader)) return -1;
    size_t capacity = (size_t)(reader->length - reader->position);
    if (capacity > sizeof(reader->input)) capacity = sizeof(reader->input);
    size_t length = fread(reader->input, 1, capacity, reader->file);
    reader->position += (int64_t)length;
    if (!length && ferror(reader->file)) {
        archive_set_error(archive, errno, "Cannot read the CBR file");
        return -1;
    }
    *buffer = reader->input;
    return (la_ssize_t)length;
}

static la_int64_t input_seek(struct archive *archive, void *context, la_int64_t offset, int whence) {
    Reader *reader = context;
    if (cancelled(reader)) return ARCHIVE_FATAL;
    int64_t base = whence == SEEK_SET ? 0 : whence == SEEK_END ? reader->length
        : whence == SEEK_CUR ? reader->position : -1;
    if (base < 0 || offset < -base || offset > reader->length - base
            || file_seek(reader->file, base + offset, SEEK_SET) != 0) {
        archive_set_error(archive, EINVAL, "Invalid CBR seek");
        return ARCHIVE_FATAL;
    }
    reader->position = base + offset;
    return reader->position;
}

static la_int64_t input_skip(struct archive *archive, void *context, la_int64_t amount) {
    Reader *reader = context;
    int64_t position = reader->position;
    if (position < 0 || amount < 0) return ARCHIVE_FATAL;
    int64_t count = amount > reader->length - position ? reader->length - position : amount;
    return input_seek(archive, context, count, SEEK_CUR) < 0 ? ARCHIVE_FATAL : count;
}

static void release(Reader *reader) {
    if (!reader) return;
    if (reader->archive) archive_read_free(reader->archive);
    if (reader->file) fclose(reader->file);
    free(reader);
}

static Reader *get_reader(JNIEnv *env, jlong handle) {
    Reader *reader = (Reader *)(intptr_t)handle;
    if (!reader) fail(env, NULL, "The CBR reader is closed");
    return reader;
}

JNIEXPORT jlong JNICALL JNI_NAME(nativeOpen)(JNIEnv *env, jclass owner, jbyteArray path) {
    (void)owner;
    if (!path) { fail(env, NULL, "Missing CBR file"); return 0; }
    jsize length = (*env)->GetArrayLength(env, path);
    if (length <= 0 || length > 131072) { fail(env, NULL, "Invalid CBR path"); return 0; }
    char *name = calloc((size_t)length + 1, 1);
    Reader *reader = calloc(1, sizeof(*reader));
    if (!name || !reader) {
        free(name); release(reader); fail(env, NULL, "Not enough memory for the CBR reader"); return 0;
    }
    (*env)->GetByteArrayRegion(env, path, 0, length, (jbyte *)name);
    if ((*env)->ExceptionCheck(env) || memchr(name, 0, (size_t)length)) {
        free(name); release(reader); fail(env, NULL, "Invalid CBR path"); return 0;
    }
#ifdef _WIN32
    int units = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, name, length, NULL, 0);
    wchar_t *wide = units > 0 ? calloc((size_t)units + 1, sizeof(wchar_t)) : NULL;
    if (wide && MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, name, length, wide, units))
        reader->file = _wfopen(wide, L"rb");
    free(wide);
#else
    reader->file = fopen(name, "rb");
#endif
    free(name);
    if (!reader->file || file_seek(reader->file, 0, SEEK_END) != 0
            || (reader->length = file_tell(reader->file)) <= 0 || reader->length > FILE_LIMIT
            || file_seek(reader->file, 0, SEEK_SET) != 0) {
        fail(env, reader, "Cannot open the CBR file or file exceeds 1 GiB"); release(reader); return 0;
    }
    reader->archive = archive_read_new();
    if (!reader->archive || !prepare(reader, env)) {
        fail(env, reader, "Cannot create the CBR decoder"); release(reader); return 0;
    }
    if (cancelled(reader)
            || archive_read_support_filter_none(reader->archive) != ARCHIVE_OK
            || archive_read_support_format_rar(reader->archive) != ARCHIVE_OK
            || archive_read_support_format_rar5(reader->archive) != ARCHIVE_OK
            || archive_read_set_seek_callback(reader->archive, input_seek) != ARCHIVE_OK
            || archive_read_open2(reader->archive, reader, NULL, input_read, input_skip, NULL) != ARCHIVE_OK) {
        fail(env, reader, archive_error_string(reader->archive)); release(reader); return 0;
    }
    return (jlong)(intptr_t)reader;
}

JNIEXPORT jbyteArray JNICALL JNI_NAME(nativeNext)(JNIEnv *env, jclass owner, jlong handle) {
    (void)owner;
    Reader *reader = get_reader(env, handle);
    if (!reader || !prepare(reader, env) || cancelled(reader)) {
        if (reader) fail(env, reader, "CBR reading cancelled");
        return NULL;
    }
    struct archive_entry *entry = NULL;
    int status = archive_read_next_header(reader->archive, &entry);
    if (status == ARCHIVE_EOF) return NULL;
    if (status != ARCHIVE_OK) { fail(env, reader, archive_error_string(reader->archive)); return NULL; }
    int type = archive_entry_filetype(entry);
    if (archive_entry_is_encrypted(entry) || archive_read_has_encrypted_entries(reader->archive) == 1
            || archive_entry_hardlink(entry) || archive_entry_symlink(entry)
            || (type != AE_IFREG && type != AE_IFDIR)) {
        fail(env, reader, "Encrypted entries and links are not supported in CBR files"); return NULL;
    }
    if (!archive_entry_size_is_set(entry) || (reader->size = archive_entry_size(entry)) < 0
            || reader->size > PAGE_LIMIT) {
        fail(env, reader, "RAR entry exceeds the 64 MiB limit or has an unknown size"); return NULL;
    }
    const char *name = archive_entry_pathname_utf8(entry);
    if (!name || !*name || strlen(name) > 4096) {
        fail(env, reader, "Invalid or oversized CBR entry name"); return NULL;
    }
    int format = archive_format(reader->archive);
    int solid = format == ARCHIVE_FORMAT_RAR ? comicreader_rar_solid(reader->archive)
        : format == ARCHIVE_FORMAT_RAR_V5 ? comicreader_rar5_solid(reader->archive) : 0;
    reader->flags = (type == AE_IFDIR ? 1 : 0) | (solid ? 2 : 0);
    jsize length = (jsize)strlen(name);
    jbyteArray result = (*env)->NewByteArray(env, length);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, length, (const jbyte *)name);
    return result;
}

JNIEXPORT jlong JNICALL JNI_NAME(nativeSize)(JNIEnv *env, jclass owner, jlong handle) {
    (void)owner;
    Reader *reader = get_reader(env, handle);
    return reader ? reader->size : -1;
}

JNIEXPORT jint JNICALL JNI_NAME(nativeFlags)(JNIEnv *env, jclass owner, jlong handle) {
    (void)owner;
    Reader *reader = get_reader(env, handle);
    return reader ? reader->flags : 0;
}

JNIEXPORT void JNICALL JNI_NAME(nativeSkip)(JNIEnv *env, jclass owner, jlong handle) {
    (void)owner;
    Reader *reader = get_reader(env, handle);
    if (!reader || !prepare(reader, env)) return;
    if (cancelled(reader) || archive_read_data_skip(reader->archive) != ARCHIVE_OK)
        fail(env, reader, archive_error_string(reader->archive));
}

JNIEXPORT jbyteArray JNICALL JNI_NAME(nativeRead)(JNIEnv *env, jclass owner, jlong handle, jboolean keep) {
    (void)owner;
    Reader *reader = get_reader(env, handle);
    if (!reader || !prepare(reader, env)) return NULL;
    if (reader->size < 0 || reader->size > PAGE_LIMIT) {
        fail(env, reader, "Invalid CBR page size"); return NULL;
    }
    jbyteArray result = keep ? (*env)->NewByteArray(env, (jsize)reader->size) : NULL;
    if (keep && !result) return NULL;
    unsigned char output[64 * 1024];
    int64_t written = 0;
    while (written < reader->size) {
        if (cancelled(reader)) { fail(env, reader, "CBR reading cancelled"); return NULL; }
        size_t amount = (size_t)(reader->size - written);
        if (amount > sizeof(output)) amount = sizeof(output);
        la_ssize_t count = archive_read_data(reader->archive, output, amount);
        if (count <= 0 || (size_t)count > amount) {
            fail(env, reader, count < 0 ? archive_error_string(reader->archive) : "Incomplete RAR page");
            return NULL;
        }
        if (keep) (*env)->SetByteArrayRegion(env, result, (jsize)written, (jsize)count, (jbyte *)output);
        if ((*env)->ExceptionCheck(env)) return NULL;
        written += count;
    }
    // Reading through EOF makes the decoder verify the final block and its checksum.
    if (cancelled(reader) || archive_read_data(reader->archive, output, 1) != 0) {
        fail(env, reader, archive_error_string(reader->archive)); return NULL;
    }
    return result;
}

JNIEXPORT void JNICALL JNI_NAME(nativeClose)(JNIEnv *env, jclass owner, jlong handle) {
    (void)env; (void)owner;
    release((Reader *)(intptr_t)handle);
}
