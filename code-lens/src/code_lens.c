#include "code_lens.h"

#include <ctype.h>
#include <errno.h>
#include <inttypes.h>
#include <limits.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#ifdef _WIN32
#include "windows_compat.h"
#else
#include <dirent.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
#endif

#include <zlib.h>
#include <libdeflate.h>
#include <tree_sitter/api.h>

#if defined(__APPLE__) && defined(__MACH__)
#include <copyfile.h>
#endif

const TSLanguage *tree_sitter_clojure(void);
const TSLanguage *tree_sitter_java(void);
const TSLanguage *tree_sitter_c(void);

static char *copy_bytes(const char *value, size_t len)
{
    char *copy = code_lens_alloc(len + 1U);
    if (copy == nullptr) {
        return nullptr;
    }
    if (len > 0U) {
        (void)memcpy(copy, value, len);
    }
    copy[len] = '\0';
    return copy;
}

/* Doubles the capacity of an arena-backed array, starting at initial_capacity. */
static bool grow_array(void **items, size_t *capacity, size_t elem_size, size_t initial_capacity)
{
    size_t new_capacity = *capacity == 0U ? initial_capacity : *capacity * 2U;
    void *new_items;

    if ((new_capacity < *capacity) || (new_capacity > (SIZE_MAX / elem_size))) {
        return false;
    }
    new_items = code_lens_resize(*items, new_capacity * elem_size);
    if (new_items == nullptr) {
        return false;
    }
    *items = new_items;
    *capacity = new_capacity;
    return true;
}

/* Arena allocator */

#define CODE_LENS_ARENA_BLOCK_SIZE (8U * 1024U * 1024U)

typedef struct CodeLensArenaBlock {
    struct CodeLensArenaBlock *next;
    size_t capacity;
    size_t used;
    unsigned char data[];
} CodeLensArenaBlock;

typedef struct {
    size_t size;
} CodeLensAllocationHeader;

static _Thread_local CodeLensArenaBlock *thread_first_block;
static _Thread_local CodeLensArenaBlock *thread_current_block;

static size_t arena_alignment(void)
{
    return _Alignof(max_align_t);
}

static size_t align_up_size(size_t value, size_t alignment)
{
    size_t remainder;

    if (alignment == 0U) {
        return value;
    }
    remainder = value % alignment;
    if (remainder == 0U) {
        return value;
    }
    if ((alignment - remainder) > (SIZE_MAX - value)) {
        return SIZE_MAX;
    }
    return value + (alignment - remainder);
}

static uintptr_t align_up_uintptr(uintptr_t value, size_t alignment)
{
    uintptr_t remainder;
    uintptr_t adjust;

    if (alignment == 0U) {
        return value;
    }
    remainder = value % (uintptr_t)alignment;
    if (remainder == 0U) {
        return value;
    }
    adjust = (uintptr_t)alignment - remainder;
    if (adjust > (UINTPTR_MAX - value)) {
        return UINTPTR_MAX;
    }
    return value + adjust;
}

static size_t allocation_header_size(void)
{
    return align_up_size(sizeof(CodeLensAllocationHeader), arena_alignment());
}

static CodeLensArenaBlock *arena_new_block(size_t required)
{
    size_t capacity = CODE_LENS_ARENA_BLOCK_SIZE;
    size_t total;
    CodeLensArenaBlock *block;

    if (required > (SIZE_MAX - arena_alignment())) {
        return nullptr;
    }
    required += arena_alignment();
    while (capacity < required) {
        if (capacity > (SIZE_MAX / 2U)) {
            capacity = required;
            break;
        }
        capacity *= 2U;
    }
    if (capacity > (SIZE_MAX - sizeof(*block))) {
        return nullptr;
    }
    total = sizeof(*block) + capacity;
    block = malloc(total);
    if (block == nullptr) {
        return nullptr;
    }
    block->next = nullptr;
    block->capacity = capacity;
    block->used = 0U;
    return block;
}

static void *arena_try_alloc(CodeLensArenaBlock *block, size_t size, size_t alignment)
{
    uintptr_t base;
    uintptr_t aligned;
    size_t offset;

    base = (uintptr_t)block->data;
    aligned = align_up_uintptr(base + block->used, alignment);
    if (aligned == UINTPTR_MAX) {
        return nullptr;
    }
    offset = (size_t)(aligned - base);
    if ((offset > block->capacity) || (size > (block->capacity - offset))) {
        return nullptr;
    }
    block->used = offset + size;
    return (void *)aligned;
}

static void *arena_alloc_raw(size_t size, size_t alignment)
{
    if (size == 0U) {
        size = 1U;
    }

    for (;;) {
        void *ptr;

        if (thread_current_block == nullptr) {
            if (thread_first_block == nullptr) {
                thread_first_block = arena_new_block(size);
                if (thread_first_block == nullptr) {
                    return nullptr;
                }
            }
            thread_current_block = thread_first_block;
        }

        ptr = arena_try_alloc(thread_current_block, size, alignment);
        if (ptr != nullptr) {
            return ptr;
        }

        if (thread_current_block->next != nullptr) {
            thread_current_block = thread_current_block->next;
            thread_current_block->used = 0U;
            continue;
        }

        thread_current_block->next = arena_new_block(size);
        if (thread_current_block->next == nullptr) {
            return nullptr;
        }
        thread_current_block = thread_current_block->next;
    }
}

void *code_lens_alloc(size_t size)
{
    size_t header_size = allocation_header_size();
    size_t total;
    unsigned char *raw;
    CodeLensAllocationHeader *header;

    if (size == 0U) {
        size = 1U;
    }
    if (size > (SIZE_MAX - header_size)) {
        return nullptr;
    }
    total = header_size + size;
    raw = arena_alloc_raw(total, arena_alignment());
    if (raw == nullptr) {
        return nullptr;
    }
    header = (CodeLensAllocationHeader *)raw;
    header->size = size;
    return raw + header_size;
}

void *code_lens_alloc_zeroed(size_t count, size_t size)
{
    size_t total;
    void *ptr;

    if ((count != 0U) && (size > (SIZE_MAX / count))) {
        return nullptr;
    }
    total = count * size;
    ptr = code_lens_alloc(total);
    if (ptr != nullptr) {
        (void)memset(ptr, 0, total);
    }
    return ptr;
}

void *code_lens_resize(void *ptr, size_t size)
{
    size_t header_size = allocation_header_size();
    CodeLensAllocationHeader *header;
    void *new_ptr;

    if (ptr == nullptr) {
        return code_lens_alloc(size);
    }
    if (size == 0U) {
        return nullptr;
    }

    header = (CodeLensAllocationHeader *)((unsigned char *)ptr - header_size);
    if (size <= header->size) {
        header->size = size;
        return ptr;
    }

    new_ptr = code_lens_alloc(size);
    if (new_ptr == nullptr) {
        return nullptr;
    }
    (void)memcpy(new_ptr, ptr, header->size);
    return new_ptr;
}

CodeLensArenaMark code_lens_arena_mark(void)
{
    CodeLensArenaMark mark;

    mark.block = thread_current_block;
    mark.used = thread_current_block == nullptr ? 0U : thread_current_block->used;
    return mark;
}

void code_lens_arena_reset(CodeLensArenaMark mark)
{
    if (mark.block == nullptr) {
        if (thread_first_block != nullptr) {
            thread_first_block->used = 0U;
        }
        thread_current_block = thread_first_block;
        return;
    }

    thread_current_block = mark.block;
    thread_current_block->used = mark.used;
}

/* Platform helpers */

char *code_lens_default_home(void)
{
    const char *configured = getenv("CODE_LENS_HOME");
    const char *home;
    size_t len;
    char *path;

    if ((configured != nullptr) && (configured[0] != '\0')) {
        return copy_bytes(configured, strlen(configured));
    }

    home = getenv("HOME");
#ifdef _WIN32
    if ((home == nullptr) || (home[0] == '\0')) {
        home = getenv("USERPROFILE");
    }
#endif
    if ((home == nullptr) || (home[0] == '\0')) {
        return nullptr;
    }

    len = strlen(home) + strlen("/.code-lens");
    path = code_lens_alloc(len + 1U);
    if (path == nullptr) {
        return nullptr;
    }
    (void)snprintf(path, len + 1U, "%s/.code-lens", home);
    return path;
}

char *code_lens_join_path(const char *left, const char *right)
{
    size_t left_len;
    size_t right_len;
    bool slash;
    char *path;

    if ((left == nullptr) || (right == nullptr)) {
        return nullptr;
    }

    left_len = strlen(left);
    right_len = strlen(right);
    slash = (left_len > 0U) && (left[left_len - 1U] == '/');
    path = code_lens_alloc(left_len + (slash ? 0U : 1U) + right_len + 1U);
    if (path == nullptr) {
        return nullptr;
    }
    (void)sprintf(path, slash ? "%s%s" : "%s/%s", left, right);
    return path;
}

bool code_lens_path_exists(const char *path)
{
    struct stat st;
    return (path != nullptr) && (stat(path, &st) == 0);
}

bool code_lens_is_directory(const char *path)
{
    struct stat st;
    return (path != nullptr) && (stat(path, &st) == 0) && S_ISDIR(st.st_mode);
}

static int64_t stat_mtime_nsec(const struct stat *st)
{
#if defined(__APPLE__) && defined(__MACH__)
    return (int64_t)st->st_mtimespec.tv_nsec;
#elif defined(_POSIX_C_SOURCE) && (_POSIX_C_SOURCE >= 200809L)
    return (int64_t)st->st_mtim.tv_nsec;
#else
    (void)st;
    return 0;
#endif
}

static int stat_regular_file(const char *path,
                             int64_t *out_size,
                             int64_t *out_mtime_sec,
                             int64_t *out_mtime_nsec)
{
    struct stat st;

    if ((path == nullptr) || (stat(path, &st) != 0) || !S_ISREG(st.st_mode) ||
        (st.st_size < 0)) {
        return -1;
    }
    if (out_size != nullptr) {
        *out_size = (int64_t)st.st_size;
    }
    if (out_mtime_sec != nullptr) {
        *out_mtime_sec = (int64_t)st.st_mtime;
    }
    if (out_mtime_nsec != nullptr) {
        *out_mtime_nsec = stat_mtime_nsec(&st);
    }
    return 0;
}

int code_lens_mkdir_p(const char *path)
{
    char *copy;
    size_t len;

    if ((path == nullptr) || (path[0] == '\0')) {
        return -1;
    }

    copy = copy_bytes(path, strlen(path));
    if (copy == nullptr) {
        return -1;
    }

    len = strlen(copy);
    if ((len > 1U) && (copy[len - 1U] == '/')) {
        copy[len - 1U] = '\0';
    }

    for (char *p = copy + 1; *p != '\0'; p++) {
        if (*p == '/') {
            *p = '\0';
            if ((mkdir(copy, 0775) != 0) && (errno != EEXIST)) {
                return -1;
            }
            *p = '/';
        }
    }

    if ((mkdir(copy, 0775) != 0) && (errno != EEXIST)) {
        return -1;
    }

    return 0;
}

int code_lens_remove_tree(const char *path)
{
    struct stat st;

    if ((path == nullptr) || (path[0] == '\0')) {
        return -1;
    }

    if (lstat(path, &st) != 0) {
        return errno == ENOENT ? 0 : -1;
    }

    if (S_ISDIR(st.st_mode)) {
        DIR *dir = opendir(path);
        struct dirent *entry;

        if (dir == nullptr) {
            return -1;
        }

        while ((entry = readdir(dir)) != nullptr) {
            char *child;
            int rc;

            if ((strcmp(entry->d_name, ".") == 0) || (strcmp(entry->d_name, "..") == 0)) {
                continue;
            }

            child = code_lens_join_path(path, entry->d_name);
            if (child == nullptr) {
                (void)closedir(dir);
                return -1;
            }
            rc = code_lens_remove_tree(child);
            if (rc != 0) {
                (void)closedir(dir);
                return -1;
            }
        }

        if (closedir(dir) != 0) {
            return -1;
        }
        return rmdir(path);
    }

    return unlink(path);
}

int code_lens_map_file(const char *path, CodeLensMappedFile *out_file)
{
    int fd;
    struct stat st;
    size_t size;
    void *mapping;

    if ((path == nullptr) || (out_file == nullptr)) {
        return -1;
    }

    (void)memset(out_file, 0, sizeof(*out_file));
    fd = open(path, O_RDONLY);
    if (fd < 0) {
        return -1;
    }
    if ((fstat(fd, &st) != 0) || (st.st_size < 0) ||
        ((uintmax_t)st.st_size > (uintmax_t)SIZE_MAX)) {
        (void)close(fd);
        return -1;
    }
    if (S_ISREG(st.st_mode)) {
        out_file->has_stat = true;
        out_file->stat_size = (int64_t)st.st_size;
        out_file->stat_mtime_sec = (int64_t)st.st_mtime;
        out_file->stat_mtime_nsec = stat_mtime_nsec(&st);
    }
    size = (size_t)st.st_size;
    if (size == 0U) {
        if (close(fd) != 0) {
            return -1;
        }
        out_file->data = "";
        out_file->len = 0U;
        return 0;
    }

    /* Small files are read into a heap buffer: 8 parse workers mapping and
     * unmapping ~2000 short-lived files causes constant TLB-shootdown IPIs
     * across every core, which taxes the walker and emitter more than one
     * extra copy of the bytes does. Large files keep mmap. */
    if (size <= (256U * 1024U)) {
        uint8_t *buf = malloc(size);
        size_t got = 0U;

        if (buf == nullptr) {
            (void)close(fd);
            return -1;
        }
        while (got < size) {
            ssize_t n = read(fd, buf + got, size - got);

            if (n <= 0) {
                free(buf);
                (void)close(fd);
                return -1;
            }
            got += (size_t)n;
        }
        if (close(fd) != 0) {
            free(buf);
            return -1;
        }
        out_file->data = (const char *)buf;
        out_file->len = size;
        out_file->heap = buf;
        return 0;
    }

    mapping = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (mapping == MAP_FAILED) {
        (void)close(fd);
        return -1;
    }
    if (close(fd) != 0) {
        (void)munmap(mapping, size);
        return -1;
    }
#ifdef MADV_SEQUENTIAL
    (void)madvise(mapping, size, MADV_SEQUENTIAL);
#endif

    out_file->data = mapping;
    out_file->len = size;
    out_file->mapping = mapping;
    out_file->mapping_len = size;
    return 0;
}

void code_lens_mapped_file_free(CodeLensMappedFile *file)
{
    if (file == nullptr) {
        return;
    }
    if ((file->mapping != nullptr) && (file->mapping_len != 0U)) {
        (void)munmap(file->mapping, file->mapping_len);
    }
    free(file->heap);
    (void)memset(file, 0, sizeof(*file));
}

static bool should_skip_dir(const char *name)
{
    return (strcmp(name, ".git") == 0) || (strcmp(name, "node_modules") == 0) ||
           (strcmp(name, "vendor") == 0) || (strcmp(name, "target") == 0) ||
           (strcmp(name, "build") == 0) || (strcmp(name, ".shadow-cljs") == 0) ||
           (strcmp(name, ".cpcache") == 0) || (strcmp(name, ".idea") == 0) ||
           (strcmp(name, ".vscode") == 0) || (strcmp(name, ".lsp") == 0) ||
           (strcmp(name, ".code-lens") == 0);
}

typedef bool (*SourceExtensionPredicate)(const char *path);

static bool has_clojure_extension(const char *path)
{
    const char *name = strrchr(path, '/');
    const char *dot = strrchr(path, '.');

    name = name == nullptr ? path : name + 1;
    if (strncmp(name, ".#", 2U) == 0) {
        return false;
    }
    if (dot == nullptr) {
        return false;
    }
    return (strcmp(dot, ".clj") == 0) || (strcmp(dot, ".cljc") == 0) ||
           (strcmp(dot, ".cljs") == 0) || (strcmp(dot, ".bb") == 0);
}

static bool has_java_extension(const char *path)
{
    const char *name = strrchr(path, '/');
    const char *dot = strrchr(path, '.');

    name = name == nullptr ? path : name + 1;
    return (strncmp(name, ".#", 2U) != 0) && (dot != nullptr) &&
           (strcmp(dot, ".java") == 0);
}

static bool has_c_extension(const char *path)
{
    const char *name = strrchr(path, '/');
    const char *dot = strrchr(path, '.');

    name = name == nullptr ? path : name + 1;
    return (strncmp(name, ".#", 2U) != 0) && (dot != nullptr) &&
           ((strcmp(dot, ".c") == 0) || (strcmp(dot, ".h") == 0));
}

static bool has_supported_extension(const char *path)
{
    return has_clojure_extension(path) || has_java_extension(path) || has_c_extension(path);
}

static bool dirent_can_skip_without_stat(const struct dirent *entry,
                                         SourceExtensionPredicate accepts)
{
#if defined(DT_DIR) && defined(DT_UNKNOWN) && defined(DT_LNK)
    return (entry->d_type != DT_DIR) && (entry->d_type != DT_UNKNOWN) &&
           (entry->d_type != DT_LNK) && !accepts(entry->d_name);
#else
    (void)entry;
    (void)accepts;
    return false;
#endif
}

#ifndef _WIN32
static bool dirent_is_directory_at(int parent_fd, const struct dirent *entry)
{
#if defined(DT_DIR) && defined(DT_UNKNOWN) && defined(DT_LNK)
    if (entry->d_type == DT_DIR) {
        return true;
    }
    if ((entry->d_type != DT_UNKNOWN) && (entry->d_type != DT_LNK)) {
        return false;
    }
#endif
    {
        struct stat st;

        return (fstatat(parent_fd, entry->d_name, &st, 0) == 0) && S_ISDIR(st.st_mode);
    }
}
#endif

typedef struct {
    char *path;
    char *excluded_path;
    bool include_ignored_dirs;
    size_t len;
    size_t capacity;
} PathBuffer;

static _Thread_local char path_buffer_home_input[PATH_MAX];
static _Thread_local char path_buffer_home_resolved[PATH_MAX];
static _Thread_local bool path_buffer_home_valid;

static int path_buffer_init(PathBuffer *buffer, const char *root)
{
    size_t len;

    if ((buffer == nullptr) || (root == nullptr)) {
        return -1;
    }

    len = strlen(root);
    while ((len > 1U) && (root[len - 1U] == '/')) {
        len--;
    }

    buffer->path = code_lens_alloc(len + 1U);
    if (buffer->path == nullptr) {
        return -1;
    }
    if (len > 0U) {
        (void)memcpy(buffer->path, root, len);
    }
    buffer->path[len] = '\0';
    buffer->len = len;
    buffer->capacity = len + 1U;
    buffer->excluded_path = nullptr;
    {
        char *home = code_lens_default_home();
        size_t home_len = home == nullptr ? 0U : strlen(home);

        if ((home_len > 0U) && (home_len < (size_t)PATH_MAX)) {
            if ((strcmp(home, path_buffer_home_input) != 0) || !path_buffer_home_valid) {
                char resolved[PATH_MAX];

                (void)memcpy(path_buffer_home_input, home, home_len + 1U);
                path_buffer_home_valid = realpath(home, resolved) != nullptr;
                if (path_buffer_home_valid) {
                    (void)snprintf(path_buffer_home_resolved,
                                   sizeof(path_buffer_home_resolved),
                                   "%s",
                                   resolved);
                } else {
                    path_buffer_home_resolved[0] = '\0';
                }
            }
            if (path_buffer_home_valid) {
                buffer->excluded_path = copy_bytes(path_buffer_home_resolved,
                                                   strlen(path_buffer_home_resolved));
            }
        }
    }
    return 0;
}

static int path_buffer_reserve(PathBuffer *buffer, size_t needed)
{
    size_t capacity;
    char *path;

    if (buffer->capacity >= needed) {
        return 0;
    }

    capacity = buffer->capacity == 0U ? 256U : buffer->capacity;
    while (capacity < needed) {
        if (capacity > (SIZE_MAX / 2U)) {
            return -1;
        }
        capacity *= 2U;
    }

    path = code_lens_resize(buffer->path, capacity);
    if (path == nullptr) {
        return -1;
    }
    buffer->path = path;
    buffer->capacity = capacity;
    return 0;
}

static int path_sb_append(PathBuffer *buffer, const char *name, size_t *out_mark)
{
    size_t name_len;
    size_t separator_len;
    size_t needed;
    size_t pos;

    if ((buffer == nullptr) || (name == nullptr) || (out_mark == nullptr)) {
        return -1;
    }

    name_len = strlen(name);
    separator_len = ((buffer->len > 0U) && (buffer->path[buffer->len - 1U] != '/')) ? 1U : 0U;
    if ((separator_len > (SIZE_MAX - buffer->len)) ||
        ((buffer->len + separator_len) > (SIZE_MAX - 1U)) ||
        (name_len > (SIZE_MAX - buffer->len - separator_len - 1U))) {
        return -1;
    }
    needed = buffer->len + separator_len + name_len + 1U;
    if (path_buffer_reserve(buffer, needed) != 0) {
        return -1;
    }

    *out_mark = buffer->len;
    pos = buffer->len;
    if (separator_len != 0U) {
        buffer->path[pos++] = '/';
    }
    if (name_len != 0U) {
        (void)memcpy(buffer->path + pos, name, name_len);
        pos += name_len;
    }
    buffer->path[pos] = '\0';
    buffer->len = pos;
    return 0;
}

static void path_buffer_restore(PathBuffer *buffer, size_t mark)
{
    buffer->len = mark;
    buffer->path[mark] = '\0';
}

static bool path_buffer_is_excluded(const PathBuffer *buffer)
{
    return (buffer != nullptr) && (buffer->excluded_path != nullptr) &&
           (strcmp(buffer->path, buffer->excluded_path) == 0);
}

#ifndef _WIN32
/* Walks one directory from an already-open fd, which fdopendir takes
 * ownership of. Directory descent uses openat/fstatat against the parent
 * fd, so each step resolves one name instead of re-resolving the whole
 * path prefix; buffer still tracks the full path for callbacks. Symlinked
 * directories are followed, matching the stat-based walker this replaces. */
static int walk_path(int dir_fd,
                     PathBuffer *buffer,
                     SourceExtensionPredicate accepts,
                     CodeLensFileCallback callback,
                     void *ctx)
{
    DIR *dir = fdopendir(dir_fd);
    struct dirent *entry;

    if (dir == nullptr) {
        (void)close(dir_fd);
        return -1;
    }

    while ((entry = readdir(dir)) != nullptr) {
        size_t mark;
        int rc;

        if ((strcmp(entry->d_name, ".") == 0) || (strcmp(entry->d_name, "..") == 0)) {
            continue;
        }
        if (dirent_can_skip_without_stat(entry, accepts)) {
            continue;
        }

        if (path_sb_append(buffer, entry->d_name, &mark) != 0) {
            (void)closedir(dir);
            return -1;
        }

        if (dirent_is_directory_at(dir_fd, entry)) {
            if ((buffer->include_ignored_dirs || !should_skip_dir(entry->d_name)) &&
                !path_buffer_is_excluded(buffer)) {
                int child_fd = openat(dir_fd, entry->d_name, O_RDONLY | O_DIRECTORY | O_CLOEXEC);

                rc = (child_fd < 0)
                         ? -1
                         : walk_path(child_fd, buffer, accepts, callback, ctx);
                path_buffer_restore(buffer, mark);
                if (rc != 0) {
                    (void)closedir(dir);
                    return rc;
                }
            } else {
                path_buffer_restore(buffer, mark);
            }
        } else if (accepts(entry->d_name)) {
            rc = callback(buffer->path, ctx);
            path_buffer_restore(buffer, mark);
            if (rc != 0) {
                (void)closedir(dir);
                return rc;
            }
        } else {
            path_buffer_restore(buffer, mark);
        }
    }

    (void)closedir(dir);
    return 0;
}
#else
/* Windows directory handles are path based. Keep the same deterministic
 * depth-first ordering contract while avoiding POSIX-only openat/fdopendir. */
static int walk_path(int unused_dir_fd,
                     PathBuffer *buffer,
                     SourceExtensionPredicate accepts,
                     CodeLensFileCallback callback,
                     void *ctx)
{
    DIR *dir = opendir(buffer->path);
    struct dirent *entry;

    (void)unused_dir_fd;
    if (dir == nullptr) {
        return -1;
    }
    while ((entry = readdir(dir)) != nullptr) {
        size_t mark;
        struct stat st;
        int rc = 0;

        if ((strcmp(entry->d_name, ".") == 0) || (strcmp(entry->d_name, "..") == 0)) {
            continue;
        }
        if (dirent_can_skip_without_stat(entry, accepts)) {
            continue;
        }
        if (path_sb_append(buffer, entry->d_name, &mark) != 0) {
            (void)closedir(dir);
            return -1;
        }
        if ((stat(buffer->path, &st) == 0) && S_ISDIR(st.st_mode)) {
            if ((buffer->include_ignored_dirs || !should_skip_dir(entry->d_name)) &&
                !path_buffer_is_excluded(buffer)) {
                rc = walk_path(-1, buffer, accepts, callback, ctx);
            }
        } else if (accepts(entry->d_name)) {
            rc = callback(buffer->path, ctx);
        }
        path_buffer_restore(buffer, mark);
        if (rc != 0) {
            (void)closedir(dir);
            return rc;
        }
    }
    (void)closedir(dir);
    return 0;
}
#endif

static int path_list_append(CodeLensPathList *list, const char *path)
{
    char *copy;

    if ((list == nullptr) || (path == nullptr)) {
        return -1;
    }

    if ((list->count == list->capacity) &&
        !grow_array((void **)&list->paths, &list->capacity, sizeof(*list->paths), 128U)) {
        return -1;
    }

    copy = copy_bytes(path, strlen(path));
    if (copy == nullptr) {
        return -1;
    }
    list->paths[list->count++] = copy;
    return 0;
}

static int collect_path_callback(const char *path, void *ctx)
{
    return path_list_append(ctx, path);
}

static int code_lens_walk_files_mode(const char *root,
                                 SourceExtensionPredicate accepts,
                                 CodeLensFileCallback callback,
                                 void *ctx,
                                 bool include_ignored_dirs)
{
    PathBuffer buffer = {.include_ignored_dirs = include_ignored_dirs};
    int root_fd;

    if ((root == nullptr) || (accepts == nullptr) || (callback == nullptr) ||
        !code_lens_is_directory(root)) {
        return -1;
    }

    if (path_buffer_init(&buffer, root) != 0) {
        return -1;
    }
#ifdef _WIN32
    root_fd = -1;
#else
    root_fd = open(buffer.path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (root_fd < 0) {
        return -1;
    }
#endif
    return walk_path(root_fd, &buffer, accepts, callback, ctx);
}

static int code_lens_walk_files(const char *root,
                                SourceExtensionPredicate accepts,
                                CodeLensFileCallback callback,
                                void *ctx)
{
    return code_lens_walk_files_mode(root, accepts, callback, ctx, false);
}

static int code_lens_walk_all_files(const char *root,
                                    SourceExtensionPredicate accepts,
                                    CodeLensFileCallback callback,
                                    void *ctx)
{
    return code_lens_walk_files_mode(root, accepts, callback, ctx, true);
}

int code_lens_walk_clojure_files(const char *root, CodeLensFileCallback callback, void *ctx)
{
    return code_lens_walk_files(root, has_clojure_extension, callback, ctx);
}

int code_lens_walk_source_files(const char *root, CodeLensFileCallback callback, void *ctx)
{
    return code_lens_walk_files(root, has_supported_extension, callback, ctx);
}

static int code_lens_collect_files(const char *root,
                                    SourceExtensionPredicate accepts,
                                    CodeLensPathList *out_files)
{
    CodeLensPathList files;

    if (out_files == nullptr) {
        return -1;
    }

    (void)memset(&files, 0, sizeof(files));
    if (code_lens_walk_files(root, accepts, collect_path_callback, &files) != 0) {
        return -1;
    }

    *out_files = files;
    return 0;
}

static int code_lens_collect_all_files(const char *root,
                                        SourceExtensionPredicate accepts,
                                        CodeLensPathList *out_files)
{
    CodeLensPathList files = {0};

    if ((out_files == nullptr) ||
        (code_lens_walk_all_files(root, accepts, collect_path_callback, &files) != 0)) {
        return -1;
    }
    *out_files = files;
    return 0;
}

int code_lens_collect_clojure_files(const char *root, CodeLensPathList *out_files)
{
    return code_lens_collect_files(root, has_clojure_extension, out_files);
}

int code_lens_collect_source_files(const char *root, CodeLensPathList *out_files)
{
    return code_lens_collect_files(root, has_supported_extension, out_files);
}

/* Git blob source: when the repo is a git checkout, files whose stat data
 * matches the .git/index entry (the same contract `git status` trusts) are
 * read from the object store - one shared mmap of each packfile plus zlib
 * inflate - instead of per-file open/read/close. Under eight parse workers
 * the per-file syscall path is the dominant read cost, and the pack path
 * replaces ~4 syscalls per file with one lstat. Anything that does not
 * match with certainty (untracked, modified, racy mtime, symlink, merge
 * stage, assume-valid/skip-worktree bits, sha256 repos, unsupported index
 * or pack format, any object-store error) falls back to reading the
 * working file, so the source can never affect indexed content.
 * CODE_LENS_GIT_BLOBS=0 disables it. */

#define GIT_OID_RAWSZ 20U
#define GIT_MAX_DELTA_DEPTH 64
#define GIT_MAX_OBJECT_SIZE (1024U * 1024U * 1024U)
#define GIT_CACHE_MAX_ENTRY (1024U * 1024U)

typedef struct {
    char *path; /* repo-relative */
    uint32_t size;
    uint32_t mtime_s;
    uint32_t mtime_ns;
    uint8_t oid[GIT_OID_RAWSZ];
} GitIndexEntry;

typedef struct {
    uint8_t *idx; /* mmap'd .idx file */
    size_t idx_len;
    uint8_t *pack; /* mmap'd .pack file */
    size_t pack_len;
    uint32_t object_count;
    const uint8_t *sha_table;
    const uint8_t *offset_table;
    const uint8_t *big_offset_table;
} GitPack;

/* Cache slot shared by the per-thread reader caches and the source's
 * immutable shared cache of pre-resolved delta bases. */
typedef struct {
    uint64_t key; /* (pack_index + 1) << 48 | offset; 0 = empty slot */
    uint8_t *data;
    uint32_t len;
    int type;
} GitCacheSlot;

typedef struct {
    bool active;
    char *root; /* repo root, no trailing slash */
    size_t root_len;
    char *gitdir; /* "<root>/.git" */
    GitIndexEntry *entries; /* sorted by path */
    size_t entry_count;
    GitPack *packs;
    size_t pack_count;
    int64_t index_mtime_s; /* racy-clean boundary */
    GitCacheSlot *shared_slots; /* pre-resolved delta bases; immutable once built */
    size_t shared_cap; /* power of two, 0 when empty */
} GitBlobSource;

/* Per-thread reader: memoizes resolved delta-base objects by
 * (pack, offset) so shared bases along delta chains inflate once. */
typedef struct {
    GitBlobSource *source;
    GitCacheSlot *slots;
    size_t cap; /* power of two, 0 until first insert */
    size_t used;
    z_stream zs; /* reused across loose objects; zs_ready gates init */
    bool zs_ready;
    struct libdeflate_decompressor *ld; /* reused across pack objects */
} GitBlobReader;

static atomic_uint_fast64_t git_blob_reads;
static atomic_uint_fast64_t git_file_reads;

uint64_t code_lens_git_blob_read_count(void)
{
    return (uint64_t)atomic_load_explicit(&git_blob_reads, memory_order_relaxed);
}

uint64_t code_lens_git_file_read_count(void)
{
    return (uint64_t)atomic_load_explicit(&git_file_reads, memory_order_relaxed);
}

static bool git_blobs_enabled(void)
{
    const char *value = getenv("CODE_LENS_GIT_BLOBS");

    return (value == nullptr) || (strcmp(value, "0") != 0);
}

static uint32_t git_be32(const uint8_t *p)
{
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

static uint64_t git_be64(const uint8_t *p)
{
    return ((uint64_t)git_be32(p) << 32) | (uint64_t)git_be32(p + 4);
}

static char *git_strdup_len(const char *s, size_t len)
{
    char *copy = malloc(len + 1U);

    if (copy == nullptr) {
        return nullptr;
    }
    (void)memcpy(copy, s, len);
    copy[len] = '\0';
    return copy;
}

static uint8_t *git_read_entire_file(const char *path, size_t *out_len)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    struct stat st;
    uint8_t *buf;
    size_t size;
    size_t got = 0U;

    if (fd < 0) {
        return nullptr;
    }
    if ((fstat(fd, &st) != 0) || (st.st_size < 0)) {
        (void)close(fd);
        return nullptr;
    }
    size = (size_t)st.st_size;
    buf = malloc(size + 1U);
    if (buf == nullptr) {
        (void)close(fd);
        return nullptr;
    }
    while (got < size) {
        ssize_t n = read(fd, buf + got, size - got);

        if (n <= 0) {
            free(buf);
            (void)close(fd);
            return nullptr;
        }
        got += (size_t)n;
    }
    (void)close(fd);
    buf[size] = 0U;
    *out_len = size;
    return buf;
}

static uint8_t *git_mmap_file(const char *path, size_t *out_len)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    struct stat st;
    void *mapping;

    if (fd < 0) {
        return nullptr;
    }
    if ((fstat(fd, &st) != 0) || (st.st_size <= 0)) {
        (void)close(fd);
        return nullptr;
    }
    mapping = mmap(nullptr, (size_t)st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    (void)close(fd);
    if (mapping == MAP_FAILED) {
        return nullptr;
    }
    *out_len = (size_t)st.st_size;
    return mapping;
}

/* Mirrors the walker's pruning: a tracked path under a skipped directory
 * (e.g. components/build/...) is invisible to the walk and must be
 * invisible here too. */
static bool git_path_is_pruned(const char *rel)
{
    const char *p = rel;

    for (;;) {
        const char *slash = strchr(p, '/');
        char component[256];
        size_t len;

        if (slash == nullptr) {
            return false; /* final component is the file name */
        }
        len = (size_t)(slash - p);
        if (len == 0U || len >= sizeof(component)) {
            return true; /* absolute/degenerate paths never match the walk */
        }
        (void)memcpy(component, p, len);
        component[len] = '\0';
        if (should_skip_dir(component)) {
            return true;
        }
        p = slash + 1;
    }
}

/* Parses .git/index v2/v3 into the filtered, path-sorted entry array.
 * Returns -1 on anything unexpected; the caller then leaves the source
 * inactive. Index extensions after the entry array are ignored. */
static int git_index_parse(GitBlobSource *src)
{
    char *index_path = code_lens_join_path(src->gitdir, "index");
    uint8_t *buf;
    size_t len;
    uint32_t version;
    uint32_t entry_total;
    size_t off = 12U;
    struct stat ist;
    int rc = -1;

    if (index_path == nullptr) {
        return -1;
    }
    if (stat(index_path, &ist) != 0) {
        return -1;
    }
    src->index_mtime_s = (int64_t)ist.st_mtime;
    buf = git_read_entire_file(index_path, &len);
    if (buf == nullptr) {
        return -1;
    }
    if ((len < 12U + GIT_OID_RAWSZ) || (memcmp(buf, "DIRC", 4) != 0)) {
        goto done;
    }
    version = git_be32(buf + 4);
    entry_total = git_be32(buf + 8);
    if ((version != 2U) && (version != 3U)) {
        goto done;
    }

    for (uint32_t i = 0U; i < entry_total; i++) {
        const uint8_t *e;
        uint32_t mode;
        uint16_t flags;
        uint16_t ext_flags = 0U;
        size_t fixed = 62U;
        size_t name_len;
        const char *name;
        size_t entry_len;

        if ((off + 62U) > len) {
            goto done;
        }
        e = buf + off;
        mode = git_be32(e + 24);
        flags = (uint16_t)(((uint16_t)e[60] << 8) | (uint16_t)e[61]);
        if ((flags & 0x4000U) != 0U) { /* extended entry */
            if (version < 3U || (off + 64U) > len) {
                goto done;
            }
            ext_flags = (uint16_t)(((uint16_t)e[62] << 8) | (uint16_t)e[63]);
            fixed = 64U;
        }
        name = (const char *)e + fixed;
        name_len = (size_t)(flags & 0x0FFFU);
        if (name_len == 0x0FFFU) {
            const void *nul = memchr(name, 0, len - off - fixed);

            if (nul == nullptr) {
                goto done;
            }
            name_len = (size_t)((const char *)nul - name);
        }
        entry_len = (fixed + name_len + 8U) & ~(size_t)7U;
        if ((off + entry_len) > len) {
            goto done;
        }

        /* Regular blobs only, no merge stages, no assume-valid, no
         * intent-to-add/skip-worktree: anything else falls back to the
         * working file via a lookup miss. */
        if (((mode >> 12) == 010U) && ((flags & 0x8000U) == 0U) && ((flags & 0x3000U) == 0U) &&
            (ext_flags == 0U) && (memchr(name, 0, name_len) == nullptr) &&
            has_supported_extension(name) && !git_path_is_pruned(name)) {
            GitIndexEntry *ie;

            if ((src->entry_count & (src->entry_count - 1U)) == 0U) {
                size_t new_cap = src->entry_count == 0U ? 1024U : (src->entry_count * 2U);
                GitIndexEntry *grown = realloc(src->entries, new_cap * sizeof(GitIndexEntry));

                if (grown == nullptr) {
                    goto done;
                }
                src->entries = grown;
            }
            ie = &src->entries[src->entry_count];
            ie->path = git_strdup_len(name, name_len);
            if (ie->path == nullptr) {
                goto done;
            }
            ie->mtime_s = git_be32(e + 8);
            ie->mtime_ns = git_be32(e + 12);
            ie->size = git_be32(e + 36);
            (void)memcpy(ie->oid, e + 40, GIT_OID_RAWSZ);
            /* the index is path-sorted; a violation means we misparsed */
            if ((src->entry_count > 0U) &&
                (strcmp(src->entries[src->entry_count - 1U].path, ie->path) >= 0)) {
                free(ie->path);
                goto done;
            }
            src->entry_count++;
        }
        off += entry_len;
    }
    rc = 0;

done:
    free(buf);
    return rc;
}

static int git_pack_open(GitPack *pk, const char *idx_path)
{
    size_t path_len = strlen(idx_path);
    char *pack_path;
    size_t min_len;

    (void)memset(pk, 0, sizeof(*pk));
    pk->idx = git_mmap_file(idx_path, &pk->idx_len);
    if (pk->idx == nullptr) {
        return -1;
    }
    if ((pk->idx_len < 8U + 1024U + GIT_OID_RAWSZ * 2U) || (git_be32(pk->idx) != 0xff744f63U) ||
        (git_be32(pk->idx + 4) != 2U)) {
        goto fail;
    }
    pk->object_count = git_be32(pk->idx + 8U + 255U * 4U);
    min_len = 8U + 1024U + (size_t)pk->object_count * (GIT_OID_RAWSZ + 4U + 4U) + GIT_OID_RAWSZ * 2U;
    if (pk->idx_len < min_len) {
        goto fail;
    }
    pk->sha_table = pk->idx + 8U + 1024U;
    pk->offset_table = pk->sha_table + (size_t)pk->object_count * (GIT_OID_RAWSZ + 4U);
    pk->big_offset_table = pk->offset_table + (size_t)pk->object_count * 4U;

    pack_path = malloc(path_len + 2U);
    if (pack_path == nullptr) {
        goto fail;
    }
    (void)memcpy(pack_path, idx_path, path_len - 4U);
    (void)memcpy(pack_path + path_len - 4U, ".pack", 6U);
    pk->pack = git_mmap_file(pack_path, &pk->pack_len);
    free(pack_path);
    if ((pk->pack == nullptr) || (pk->pack_len < 12U + GIT_OID_RAWSZ)) {
        goto fail;
    }
    return 0;

fail:
    if (pk->pack != nullptr) {
        (void)munmap(pk->pack, pk->pack_len);
    }
    (void)munmap(pk->idx, pk->idx_len);
    (void)memset(pk, 0, sizeof(*pk));
    return -1;
}

static int git_packs_open(GitBlobSource *src)
{
    char *pack_dir = code_lens_join_path(src->gitdir, "objects/pack");
    DIR *dir;
    struct dirent *entry;

    if (pack_dir == nullptr) {
        return -1;
    }
    dir = opendir(pack_dir);
    if (dir == nullptr) {
        return 0; /* no packs is fine: loose objects still work */
    }
    while ((entry = readdir(dir)) != nullptr) {
        size_t name_len = strlen(entry->d_name);

        if ((name_len > 4U) && (strcmp(entry->d_name + name_len - 4U, ".idx") == 0)) {
            char *idx_path = code_lens_join_path(pack_dir, entry->d_name);
            GitPack *grown;

            if (idx_path == nullptr) {
                (void)closedir(dir);
                return -1;
            }
            grown = realloc(src->packs, (src->pack_count + 1U) * sizeof(GitPack));
            if (grown == nullptr) {
                (void)closedir(dir);
                return -1;
            }
            src->packs = grown;
            if (git_pack_open(&src->packs[src->pack_count], idx_path) == 0) {
                src->pack_count++;
            }
        }
    }
    (void)closedir(dir);
    return 0;
}

static int git_pack_find(const GitPack *pk, const uint8_t *oid, uint64_t *out_offset)
{
    uint32_t lo = (oid[0] == 0U) ? 0U : git_be32(pk->idx + 8U + ((size_t)oid[0] - 1U) * 4U);
    uint32_t hi = git_be32(pk->idx + 8U + (size_t)oid[0] * 4U);

    if (hi > pk->object_count) {
        return -1;
    }
    while (lo < hi) {
        uint32_t mid = lo + ((hi - lo) / 2U);
        int cmp = memcmp(oid, pk->sha_table + (size_t)mid * GIT_OID_RAWSZ, GIT_OID_RAWSZ);

        if (cmp == 0) {
            uint32_t raw = git_be32(pk->offset_table + (size_t)mid * 4U);

            if ((raw & 0x80000000U) != 0U) {
                size_t big_index = raw & 0x7FFFFFFFU;

                if ((pk->big_offset_table + ((size_t)big_index + 1U) * 8U) >
                    (pk->idx + pk->idx_len)) {
                    return -1;
                }
                *out_offset = git_be64(pk->big_offset_table + (size_t)big_index * 8U);
            } else {
                *out_offset = raw;
            }
            return 0;
        }
        if (cmp < 0) {
            hi = mid;
        } else {
            lo = mid + 1U;
        }
    }
    return -1;
}

/* Returns the reader's reusable zlib stream, initialized on first use and
 * reset between objects. Only the loose-object path streams through zlib;
 * pack objects go through libdeflate below. */
static z_stream *git_reader_stream(GitBlobReader *reader)
{
    if (!reader->zs_ready) {
        (void)memset(&reader->zs, 0, sizeof(reader->zs));
        if (inflateInit(&reader->zs) != Z_OK) {
            return nullptr;
        }
        reader->zs_ready = true;
        return &reader->zs;
    }
    return (inflateReset(&reader->zs) == Z_OK) ? &reader->zs : nullptr;
}

/* Inflates exactly out_len bytes of a zlib stream at data. Pack objects
 * have a known exact output size, which is libdeflate's whole-buffer
 * sweet spot; the _ex variant tolerates the trailing pack bytes after the
 * stream and still verifies the adler32 checksum. */
static int git_inflate_exact(GitBlobReader *reader, const uint8_t *data, size_t avail,
                             uint8_t *out, size_t out_len)
{
    size_t actual_out = 0U;

    if (reader->ld == nullptr) {
        reader->ld = libdeflate_alloc_decompressor();
        if (reader->ld == nullptr) {
            return -1;
        }
    }
    if (libdeflate_zlib_decompress_ex(reader->ld, data, avail, out, out_len, nullptr,
                                      &actual_out) != LIBDEFLATE_SUCCESS) {
        return -1;
    }
    return (actual_out == out_len) ? 0 : -1;
}

static GitCacheSlot *git_cache_lookup(GitBlobReader *reader, uint64_t key)
{
    size_t mask;
    size_t slot;

    if (reader->cap == 0U) {
        return nullptr;
    }
    mask = reader->cap - 1U;
    slot = (size_t)((key * 0x9E3779B97F4A7C15ULL) >> 32) & mask;
    while (reader->slots[slot].key != 0U) {
        if (reader->slots[slot].key == key) {
            return &reader->slots[slot];
        }
        slot = (slot + 1U) & mask;
    }
    return nullptr;
}

static void git_cache_insert(GitBlobReader *reader, uint64_t key, int type, uint8_t *data,
                             size_t len)
{
    size_t mask;
    size_t slot;

    if (len > GIT_CACHE_MAX_ENTRY) {
        free(data);
        return;
    }
    if ((reader->used * 10U) >= (reader->cap * 7U)) {
        size_t new_cap = reader->cap == 0U ? 256U : (reader->cap * 2U);
        GitCacheSlot *grown = calloc(new_cap, sizeof(GitCacheSlot));

        if (grown == nullptr) {
            free(data);
            return;
        }
        for (size_t i = 0U; i < reader->cap; i++) {
            if (reader->slots[i].key != 0U) {
                size_t s = (size_t)((reader->slots[i].key * 0x9E3779B97F4A7C15ULL) >> 32) &
                           (new_cap - 1U);

                while (grown[s].key != 0U) {
                    s = (s + 1U) & (new_cap - 1U);
                }
                grown[s] = reader->slots[i];
            }
        }
        free(reader->slots);
        reader->slots = grown;
        reader->cap = new_cap;
    }
    mask = reader->cap - 1U;
    slot = (size_t)((key * 0x9E3779B97F4A7C15ULL) >> 32) & mask;
    while (reader->slots[slot].key != 0U) {
        if (reader->slots[slot].key == key) {
            free(data); /* already cached by a deeper recursion */
            return;
        }
        slot = (slot + 1U) & mask;
    }
    reader->slots[slot].key = key;
    reader->slots[slot].type = type;
    reader->slots[slot].data = data;
    reader->slots[slot].len = (uint32_t)len;
    reader->used++;
}

/* Lock-free lookup in the source's shared cache of pre-resolved delta
 * bases; the table is immutable once workers are running. */
static const GitCacheSlot *git_shared_lookup(const GitBlobSource *src, uint64_t key)
{
    size_t mask;
    size_t slot;

    if (src->shared_cap == 0U) {
        return nullptr;
    }
    mask = src->shared_cap - 1U;
    slot = (size_t)((key * 0x9E3779B97F4A7C15ULL) >> 32) & mask;
    while (src->shared_slots[slot].key != 0U) {
        if (src->shared_slots[slot].key == key) {
            return &src->shared_slots[slot];
        }
        slot = (slot + 1U) & mask;
    }
    return nullptr;
}

/* A resolved object. owned == false means data points into a reader cache
 * slot: valid for the reader's lifetime (rehashes move slots, not the heap
 * blocks they point at) and must not be freed by the caller. */
typedef struct {
    uint8_t *data;
    size_t len;
    int type;
    bool owned;
} GitObject;

static int git_object_read_by_oid_borrowed(GitBlobReader *reader, const uint8_t *oid, int depth,
                                           GitObject *obj);

/* Reads and fully resolves the object at a pack offset. Delta bases
 * encountered along the way are memoized in the reader's cache; cache hits
 * are returned borrowed instead of copied, which spares a malloc+memcpy of
 * the whole base every time a shared base is reused. */
static int git_pack_read_object_borrowed(GitBlobReader *reader, size_t pack_index,
                                         uint64_t offset, int depth, GitObject *obj)
{
    const GitPack *pk = &reader->source->packs[pack_index];
    uint64_t key = ((uint64_t)(pack_index + 1U) << 48) | offset;
    const GitCacheSlot *shared = git_shared_lookup(reader->source, key);
    const GitCacheSlot *cached;
    const uint8_t *p;
    const uint8_t *end;
    uint8_t byte;
    int type;
    uint64_t size;
    unsigned int shift;
    uint8_t *out;

    if (shared != nullptr) {
        obj->data = shared->data;
        obj->len = shared->len;
        obj->type = shared->type;
        obj->owned = false;
        return 0;
    }
    cached = git_cache_lookup(reader, key);
    if (cached != nullptr) {
        obj->data = cached->data;
        obj->len = cached->len;
        obj->type = cached->type;
        obj->owned = false;
        return 0;
    }
    if ((depth > GIT_MAX_DELTA_DEPTH) || (offset >= pk->pack_len)) {
        return -1;
    }
    p = pk->pack + offset;
    end = pk->pack + pk->pack_len;
    byte = *p++;
    type = (byte >> 4) & 7;
    size = byte & 15U;
    shift = 4U;
    while ((byte & 0x80U) != 0U) {
        if ((p >= end) || (shift > 60U)) {
            return -1;
        }
        byte = *p++;
        size |= (uint64_t)(byte & 0x7FU) << shift;
        shift += 7U;
    }
    if (size > GIT_MAX_OBJECT_SIZE) {
        return -1;
    }

    if ((type == 6) || (type == 7)) { /* ofs-delta / ref-delta */
        uint64_t base_offset = 0U;
        GitObject base = {nullptr, 0U, 0, false};
        uint8_t *delta;
        const uint8_t *d;
        const uint8_t *dend;
        uint64_t src_size = 0U;
        uint64_t dst_size = 0U;
        size_t written = 0U;
        int rc;

        if (type == 6) {
            if (p >= end) {
                return -1;
            }
            byte = *p++;
            base_offset = byte & 0x7FU;
            while ((byte & 0x80U) != 0U) {
                if (p >= end) {
                    return -1;
                }
                byte = *p++;
                base_offset = ((base_offset + 1U) << 7) | (byte & 0x7FU);
            }
            if (base_offset > offset) {
                return -1;
            }
            base_offset = offset - base_offset;
            rc = git_pack_read_object_borrowed(reader, pack_index, base_offset, depth + 1, &base);
        } else {
            if ((p + GIT_OID_RAWSZ) > end) {
                return -1;
            }
            rc = git_object_read_by_oid_borrowed(reader, p, depth + 1, &base);
            p += GIT_OID_RAWSZ;
        }
        if (rc != 0) {
            return -1;
        }
        delta = malloc(size == 0U ? 1U : (size_t)size);
        if ((delta == nullptr) ||
            (git_inflate_exact(reader, p, (size_t)(end - p), delta, (size_t)size) != 0)) {
            goto delta_fail;
        }
        d = delta;
        dend = delta + size;
        shift = 0U;
        do {
            if (d >= dend) {
                goto delta_fail;
            }
            byte = *d++;
            src_size |= (uint64_t)(byte & 0x7FU) << shift;
            shift += 7U;
        } while ((byte & 0x80U) != 0U);
        shift = 0U;
        do {
            if (d >= dend) {
                goto delta_fail;
            }
            byte = *d++;
            dst_size |= (uint64_t)(byte & 0x7FU) << shift;
            shift += 7U;
        } while ((byte & 0x80U) != 0U);
        if ((src_size != base.len) || (dst_size > GIT_MAX_OBJECT_SIZE)) {
            goto delta_fail;
        }
        out = malloc(dst_size == 0U ? 1U : (size_t)dst_size);
        if (out == nullptr) {
            goto delta_fail;
        }
        while (d < dend) {
            uint8_t cmd = *d++;

            if ((cmd & 0x80U) != 0U) { /* copy from base */
                uint64_t copy_off = 0U;
                uint64_t copy_len = 0U;

                if ((cmd & 0x01U) != 0U) { if (d >= dend) { goto delta_out_fail; } copy_off = *d++; }
                if ((cmd & 0x02U) != 0U) { if (d >= dend) { goto delta_out_fail; } copy_off |= (uint64_t)*d++ << 8; }
                if ((cmd & 0x04U) != 0U) { if (d >= dend) { goto delta_out_fail; } copy_off |= (uint64_t)*d++ << 16; }
                if ((cmd & 0x08U) != 0U) { if (d >= dend) { goto delta_out_fail; } copy_off |= (uint64_t)*d++ << 24; }
                if ((cmd & 0x10U) != 0U) { if (d >= dend) { goto delta_out_fail; } copy_len = *d++; }
                if ((cmd & 0x20U) != 0U) { if (d >= dend) { goto delta_out_fail; } copy_len |= (uint64_t)*d++ << 8; }
                if ((cmd & 0x40U) != 0U) { if (d >= dend) { goto delta_out_fail; } copy_len |= (uint64_t)*d++ << 16; }
                if (copy_len == 0U) {
                    copy_len = 0x10000U;
                }
                if (((copy_off + copy_len) > base.len) || ((written + copy_len) > dst_size)) {
                    goto delta_out_fail;
                }
                (void)memcpy(out + written, base.data + copy_off, (size_t)copy_len);
                written += (size_t)copy_len;
            } else if (cmd != 0U) { /* insert literal */
                if (((d + cmd) > dend) || ((written + cmd) > dst_size)) {
                    goto delta_out_fail;
                }
                (void)memcpy(out + written, d, cmd);
                d += cmd;
                written += cmd;
            } else {
                goto delta_out_fail;
            }
        }
        free(delta);
        if (written != dst_size) {
            if (base.owned) {
                free(base.data);
            }
            free(out);
            return -1;
        }
        /* an owned ofs-delta base is likely shared with sibling deltas;
         * hand it to the cache (borrowed bases already live there) */
        if (base.owned) {
            if (type == 6) {
                git_cache_insert(reader, ((uint64_t)(pack_index + 1U) << 48) | base_offset,
                                 base.type, base.data, base.len);
            } else {
                free(base.data);
            }
        }
        obj->data = out;
        obj->len = (size_t)dst_size;
        obj->type = base.type;
        obj->owned = true;
        return 0;

    delta_out_fail:
        free(out);
    delta_fail:
        if (base.owned) {
            free(base.data);
        }
        free(delta);
        return -1;
    }

    out = malloc(size == 0U ? 1U : (size_t)size);
    if (out == nullptr) {
        return -1;
    }
    if (git_inflate_exact(reader, p, (size_t)(end - p), out, (size_t)size) != 0) {
        free(out);
        return -1;
    }
    obj->data = out;
    obj->len = (size_t)size;
    obj->type = type;
    obj->owned = true;
    return 0;
}

/* Loose object: "<gitdir>/objects/xx/38hex", zlib("<type> <len>\0" + data). */
static uint8_t *git_loose_read(GitBlobReader *reader, const uint8_t *oid, int *out_type,
                               size_t *out_len)
{
    static const char hex_digits[] = "0123456789abcdef";
    const GitBlobSource *src = reader->source;
    char rel[GIT_OID_RAWSZ * 2U + 10U];
    char *path;
    uint8_t *zdata;
    size_t zlen;
    size_t cap = 1U << 16;
    size_t have = 0U;
    uint8_t *out;
    z_stream *zs;
    int zrc;
    const uint8_t *nul;
    size_t header_len;

    (void)memcpy(rel, "objects/", 8U);
    rel[8] = hex_digits[oid[0] >> 4];
    rel[9] = hex_digits[oid[0] & 15U];
    rel[10] = '/';
    for (size_t i = 1U; i < GIT_OID_RAWSZ; i++) {
        rel[9U + i * 2U] = hex_digits[oid[i] >> 4];
        rel[10U + i * 2U] = hex_digits[oid[i] & 15U];
    }
    rel[9U + GIT_OID_RAWSZ * 2U] = '\0';
    path = code_lens_join_path(src->gitdir, rel);
    if (path == nullptr) {
        return nullptr;
    }
    zdata = git_read_entire_file(path, &zlen);
    if (zdata == nullptr) {
        return nullptr;
    }
    out = malloc(cap);
    if (out == nullptr) {
        free(zdata);
        return nullptr;
    }
    zs = git_reader_stream(reader);
    if (zs == nullptr) {
        free(zdata);
        free(out);
        return nullptr;
    }
    zs->next_in = zdata;
    zs->avail_in = (uInt)zlen;
    do {
        if (have == cap) {
            uint8_t *grown;

            if (cap > (GIT_MAX_OBJECT_SIZE / 2U)) {
                zrc = Z_MEM_ERROR;
                break;
            }
            cap *= 2U;
            grown = realloc(out, cap);
            if (grown == nullptr) {
                zrc = Z_MEM_ERROR;
                break;
            }
            out = grown;
        }
        zs->next_out = out + have;
        zs->avail_out = (uInt)(cap - have);
        zrc = inflate(zs, Z_NO_FLUSH);
        have = cap - zs->avail_out;
    } while (zrc == Z_OK);
    free(zdata);
    if (zrc != Z_STREAM_END) {
        free(out);
        return nullptr;
    }
    nul = memchr(out, 0, have);
    if (nul == nullptr) {
        free(out);
        return nullptr;
    }
    header_len = (size_t)(nul - out) + 1U;
    if ((header_len >= 6U) && (memcmp(out, "blob ", 5U) == 0)) {
        *out_type = 3;
    } else {
        *out_type = 0; /* callers only want blobs */
    }
    *out_len = have - header_len;
    (void)memmove(out, out + header_len, *out_len);
    return out;
}

static int git_object_read_by_oid_borrowed(GitBlobReader *reader, const uint8_t *oid, int depth,
                                           GitObject *obj)
{
    const GitBlobSource *src = reader->source;

    if (depth > GIT_MAX_DELTA_DEPTH) {
        return -1;
    }
    for (size_t i = 0U; i < src->pack_count; i++) {
        uint64_t offset;

        if (git_pack_find(&src->packs[i], oid, &offset) == 0) {
            return git_pack_read_object_borrowed(reader, i, offset, depth, obj);
        }
    }
    obj->data = git_loose_read(reader, oid, &obj->type, &obj->len);
    obj->owned = true;
    return (obj->data == nullptr) ? -1 : 0;
}

/* Owning top-level read: the resolved blob's ownership passes to the
 * caller, so borrowed cache hits are copied out here and only here. */
static uint8_t *git_object_read_by_oid(GitBlobReader *reader, const uint8_t *oid, int *out_type,
                                       size_t *out_len)
{
    GitObject obj;

    if (git_object_read_by_oid_borrowed(reader, oid, 0, &obj) != 0) {
        return nullptr;
    }
    if (!obj.owned) {
        uint8_t *copy = malloc(obj.len == 0U ? 1U : obj.len);

        if (copy == nullptr) {
            return nullptr;
        }
        (void)memcpy(copy, obj.data, obj.len);
        obj.data = copy;
    }
    *out_type = obj.type;
    *out_len = obj.len;
    return obj.data;
}

static void git_blob_reader_init(GitBlobReader *reader, GitBlobSource *src)
{
    (void)memset(reader, 0, sizeof(*reader));
    reader->source = src;
}

static void git_blob_reader_destroy(GitBlobReader *reader)
{
    for (size_t i = 0U; i < reader->cap; i++) {
        if (reader->slots[i].key != 0U) {
            free(reader->slots[i].data);
        }
    }
    free(reader->slots);
    if (reader->zs_ready) {
        (void)inflateEnd(&reader->zs);
    }
    libdeflate_free_decompressor(reader->ld); /* NULL-safe */
    (void)memset(reader, 0, sizeof(*reader));
}

/* Open-addressed (pack, offset) -> reference count map used only while
 * planning the shared cache. */
typedef struct {
    uint64_t key;
    uint32_t count;
} GitBaseCount;

typedef struct {
    GitBaseCount *slots;
    size_t cap; /* power of two, 0 until first insert */
    size_t used;
} GitBaseCountMap;

static int git_base_count_bump(GitBaseCountMap *map, uint64_t key)
{
    size_t mask;
    size_t slot;

    if ((map->used * 10U) >= (map->cap * 7U)) {
        size_t new_cap = map->cap == 0U ? 1024U : (map->cap * 2U);
        GitBaseCount *grown = calloc(new_cap, sizeof(GitBaseCount));

        if (grown == nullptr) {
            return -1;
        }
        for (size_t i = 0U; i < map->cap; i++) {
            if (map->slots[i].key != 0U) {
                size_t s = (size_t)((map->slots[i].key * 0x9E3779B97F4A7C15ULL) >> 32) &
                           (new_cap - 1U);

                while (grown[s].key != 0U) {
                    s = (s + 1U) & (new_cap - 1U);
                }
                grown[s] = map->slots[i];
            }
        }
        free(map->slots);
        map->slots = grown;
        map->cap = new_cap;
    }
    mask = map->cap - 1U;
    slot = (size_t)((key * 0x9E3779B97F4A7C15ULL) >> 32) & mask;
    while (map->slots[slot].key != 0U) {
        if (map->slots[slot].key == key) {
            map->slots[slot].count++;
            return 0;
        }
        slot = (slot + 1U) & mask;
    }
    map->slots[slot].key = key;
    map->slots[slot].count = 1U;
    map->used++;
    return 0;
}

/* Walks the delta-chain headers below a pack offset, bumping the count of
 * every base (pack, offset) the chain passes through. Header parsing
 * only - nothing is inflated. */
static void git_count_chain_bases(const GitBlobSource *src, GitBaseCountMap *map,
                                  size_t pack_index, uint64_t offset, int depth)
{
    const GitPack *pk = &src->packs[pack_index];
    const uint8_t *p;
    const uint8_t *end;
    uint8_t byte;
    int type;
    unsigned int shift;

    if ((depth > GIT_MAX_DELTA_DEPTH) || (offset >= pk->pack_len)) {
        return;
    }
    p = pk->pack + offset;
    end = pk->pack + pk->pack_len;
    byte = *p++;
    type = (byte >> 4) & 7;
    shift = 4U;
    while ((byte & 0x80U) != 0U) { /* consume the size varint */
        if ((p >= end) || (shift > 60U)) {
            return;
        }
        byte = *p++;
        shift += 7U;
    }
    if (type == 6) { /* ofs-delta */
        uint64_t base_offset;

        if (p >= end) {
            return;
        }
        byte = *p++;
        base_offset = byte & 0x7FU;
        while ((byte & 0x80U) != 0U) {
            if (p >= end) {
                return;
            }
            byte = *p++;
            base_offset = ((base_offset + 1U) << 7) | (byte & 0x7FU);
        }
        if (base_offset > offset) {
            return;
        }
        base_offset = offset - base_offset;
        if (git_base_count_bump(map, ((uint64_t)(pack_index + 1U) << 48) | base_offset) != 0) {
            return;
        }
        git_count_chain_bases(src, map, pack_index, base_offset, depth + 1);
    } else if (type == 7) { /* ref-delta */
        if ((p + GIT_OID_RAWSZ) > end) {
            return;
        }
        for (size_t i = 0U; i < src->pack_count; i++) {
            uint64_t base_offset;

            if (git_pack_find(&src->packs[i], p, &base_offset) == 0) {
                if (git_base_count_bump(map, ((uint64_t)(i + 1U) << 48) | base_offset) == 0) {
                    git_count_chain_bases(src, map, i, base_offset, depth + 1);
                }
                return;
            }
        }
    }
}

#define GIT_SHARED_CACHE_MAX_BYTES (64U * 1024U * 1024U)

/* Pre-resolves delta bases referenced by two or more chain links into the
 * source's immutable shared cache before any worker starts. The full
 * object set is known from the parsed index, so shared bases inflate once
 * per index run here instead of once per worker thread. Best-effort: any
 * failure just leaves a smaller (or empty) shared cache. */
static void git_shared_cache_build(GitBlobSource *src)
{
    GitBaseCountMap map = {nullptr, 0U, 0U};
    GitBlobReader temp;
    size_t popular = 0U;
    size_t cap = 1U;
    size_t total_bytes = 0U;

    for (size_t e = 0U; e < src->entry_count; e++) {
        for (size_t i = 0U; i < src->pack_count; i++) {
            uint64_t offset;

            if (git_pack_find(&src->packs[i], src->entries[e].oid, &offset) == 0) {
                git_count_chain_bases(src, &map, i, offset, 0);
                break;
            }
        }
    }
    for (size_t i = 0U; i < map.cap; i++) {
        if ((map.slots[i].key != 0U) && (map.slots[i].count >= 2U)) {
            popular++;
        }
    }
    if (popular == 0U) {
        free(map.slots);
        return;
    }
    while ((popular * 10U) >= (cap * 7U)) {
        cap *= 2U;
    }
    src->shared_slots = calloc(cap, sizeof(GitCacheSlot));
    if (src->shared_slots == nullptr) {
        free(map.slots);
        return;
    }
    src->shared_cap = cap;
    git_blob_reader_init(&temp, src);
    for (size_t i = 0U; i < map.cap; i++) {
        uint64_t key = map.slots[i].key;
        GitObject obj;
        uint8_t *data;
        size_t slot;

        if ((key == 0U) || (map.slots[i].count < 2U)) {
            continue;
        }
        /* the key itself is not yet in the shared table, but sub-bases
         * inserted by earlier iterations are reused through it */
        if (git_pack_read_object_borrowed(&temp, (size_t)(key >> 48) - 1U,
                                          key & 0xFFFFFFFFFFFFULL, 0, &obj) != 0) {
            continue;
        }
        if ((obj.len > GIT_CACHE_MAX_ENTRY) ||
            ((total_bytes + obj.len) > GIT_SHARED_CACHE_MAX_BYTES)) {
            if (obj.owned) {
                free(obj.data);
            }
            continue;
        }
        if (obj.owned) {
            data = obj.data;
        } else { /* borrowed from temp's private cache, which dies below */
            data = malloc(obj.len == 0U ? 1U : obj.len);
            if (data == nullptr) {
                continue;
            }
            (void)memcpy(data, obj.data, obj.len);
        }
        slot = (size_t)((key * 0x9E3779B97F4A7C15ULL) >> 32) & (cap - 1U);
        while (src->shared_slots[slot].key != 0U) { /* keys are unique here */
            slot = (slot + 1U) & (cap - 1U);
        }
        src->shared_slots[slot].key = key;
        src->shared_slots[slot].type = obj.type;
        src->shared_slots[slot].data = data;
        src->shared_slots[slot].len = (uint32_t)obj.len;
        total_bytes += obj.len;
    }
    git_blob_reader_destroy(&temp);
    free(map.slots);
}

static void git_blob_source_destroy(GitBlobSource *src)
{
    for (size_t i = 0U; i < src->entry_count; i++) {
        free(src->entries[i].path);
    }
    free(src->entries);
    for (size_t i = 0U; i < src->pack_count; i++) {
        (void)munmap(src->packs[i].idx, src->packs[i].idx_len);
        (void)munmap(src->packs[i].pack, src->packs[i].pack_len);
    }
    free(src->packs);
    for (size_t i = 0U; i < src->shared_cap; i++) {
        if (src->shared_slots[i].key != 0U) {
            free(src->shared_slots[i].data);
        }
    }
    free(src->shared_slots);
    free(src->root);
    /* gitdir came from code_lens_join_path (arena-backed, never freed) */
    (void)memset(src, 0, sizeof(*src));
}

/* Never fails hard: on any problem the source stays inactive and indexing
 * reads working files exactly as before. */
static void git_blob_source_init(GitBlobSource *src, const char *repo_path)
{
    size_t root_len;

    (void)memset(src, 0, sizeof(*src));
    if (!git_blobs_enabled() || (repo_path == nullptr) || (repo_path[0] == '\0')) {
        return;
    }
    root_len = strlen(repo_path);
    while ((root_len > 1U) && (repo_path[root_len - 1U] == '/')) {
        root_len--;
    }
    src->root = git_strdup_len(repo_path, root_len);
    if (src->root == nullptr) {
        return;
    }
    src->root_len = root_len;
    src->gitdir = code_lens_join_path(src->root, ".git");
    if ((src->gitdir == nullptr) || !code_lens_is_directory(src->gitdir)) {
        git_blob_source_destroy(src);
        return;
    }
    if ((git_index_parse(src) != 0) || (git_packs_open(src) != 0)) {
        git_blob_source_destroy(src);
        return;
    }
    src->active = src->entry_count > 0U;
    if (!src->active) {
        git_blob_source_destroy(src);
        return;
    }
    git_shared_cache_build(src);
}

static int git_entry_compare(const void *key, const void *element)
{
    return strcmp((const char *)key, ((const GitIndexEntry *)element)->path);
}

/* Returns 0 and fills out_file when the blob was served from the object
 * store; any other outcome returns nonzero and the caller reads the
 * working file. */
static int git_blob_try_read(GitBlobReader *reader, const char *path,
                             CodeLensMappedFile *out_file)
{
    const GitBlobSource *src;
    const GitIndexEntry *entry;
    const char *rel;
    struct stat st;
    uint8_t *data;
    int type = 0;
    size_t len = 0U;

    if ((reader == nullptr) || (reader->source == nullptr) || !reader->source->active) {
        return 1;
    }
    src = reader->source;
    if ((strncmp(path, src->root, src->root_len) != 0) || (path[src->root_len] != '/')) {
        return 1;
    }
    rel = path + src->root_len + 1U;
    entry = bsearch(rel, src->entries, src->entry_count, sizeof(GitIndexEntry),
                    git_entry_compare);
    if (entry == nullptr) {
        return 1; /* untracked */
    }
    /* The stat contract git itself trusts: regular file, same size, same
     * mtime, and not racily clean (entry mtime at or after the index's own
     * mtime means the file may have been edited without changing its
     * stat signature). */
    if ((lstat(path, &st) != 0) || !S_ISREG(st.st_mode) ||
        ((uint64_t)st.st_size != (uint64_t)entry->size) ||
        ((int64_t)(uint32_t)st.st_mtime != (int64_t)entry->mtime_s) ||
        ((entry->mtime_ns != 0U) && ((uint32_t)stat_mtime_nsec(&st) != entry->mtime_ns)) ||
        ((int64_t)entry->mtime_s >= src->index_mtime_s)) {
        return 1;
    }
    if (entry->size == 0U) {
        (void)memset(out_file, 0, sizeof(*out_file));
        out_file->data = "";
        out_file->len = 0U;
        out_file->has_stat = true;
        out_file->stat_size = (int64_t)st.st_size;
        out_file->stat_mtime_sec = (int64_t)st.st_mtime;
        out_file->stat_mtime_nsec = stat_mtime_nsec(&st);
        atomic_fetch_add_explicit(&git_blob_reads, 1U, memory_order_relaxed);
        return 0;
    }
    data = git_object_read_by_oid(reader, entry->oid, &type, &len);
    if ((data == nullptr) || (type != 3) || (len != (size_t)entry->size)) {
        free(data);
        return 1;
    }
    (void)memset(out_file, 0, sizeof(*out_file));
    out_file->data = (const char *)data;
    out_file->len = len;
    out_file->heap = data;
    out_file->has_stat = true;
    out_file->stat_size = (int64_t)st.st_size;
    out_file->stat_mtime_sec = (int64_t)st.st_mtime;
    out_file->stat_mtime_nsec = stat_mtime_nsec(&st);
    atomic_fetch_add_explicit(&git_blob_reads, 1U, memory_order_relaxed);
    return 0;
}

/* String builder */

typedef struct {
    char *data;
    size_t len;
    size_t cap;
} StringBuilder;

static bool sb_reserve(StringBuilder *sb, size_t extra)
{
    size_t needed = sb->len + extra + 1U;
    size_t new_cap;
    char *new_data;

    if (needed < extra) {
        return false;
    }
    if (needed <= sb->cap) {
        return true;
    }

    new_cap = sb->cap == 0U ? 256U : sb->cap;
    while (new_cap < needed) {
        if (new_cap > (SIZE_MAX / 2U)) {
            return false;
        }
        new_cap *= 2U;
    }

    new_data = code_lens_resize(sb->data, new_cap);
    if (new_data == nullptr) {
        return false;
    }
    sb->data = new_data;
    sb->cap = new_cap;
    return true;
}

static bool sb_append_len(StringBuilder *sb, const char *text, size_t len)
{
    if (!sb_reserve(sb, len)) {
        return false;
    }
    (void)memcpy(sb->data + sb->len, text, len);
    sb->len += len;
    sb->data[sb->len] = '\0';
    return true;
}

static bool sb_append(StringBuilder *sb, const char *text)
{
    return sb_append_len(sb, text, strlen(text));
}

static bool sb_appendf(StringBuilder *sb, const char *format, ...)
{
    va_list args;
    va_list copy;
    int needed;

    va_start(args, format);
    va_copy(copy, args);
    needed = vsnprintf(nullptr, 0, format, copy);
    va_end(copy);
    if (needed < 0) {
        va_end(args);
        return false;
    }
    if (!sb_reserve(sb, (size_t)needed)) {
        va_end(args);
        return false;
    }
    (void)vsnprintf(sb->data + sb->len, sb->cap - sb->len, format, args);
    sb->len += (size_t)needed;
    va_end(args);
    return true;
}

static void sb_free(StringBuilder *sb)
{
    sb->data = nullptr;
    sb->len = 0U;
    sb->cap = 0U;
}

/* Dependency adapters */

const char *code_lens_sqlite_version(void)
{
    return sqlite3_libversion();
}

uint32_t code_lens_clojure_language_abi_version(void)
{
    return ts_language_abi_version(tree_sitter_clojure());
}

uint32_t code_lens_clojure_language_symbol_count(void)
{
    return ts_language_symbol_count(tree_sitter_clojure());
}

uint32_t code_lens_java_language_abi_version(void)
{
    return ts_language_abi_version(tree_sitter_java());
}

uint32_t code_lens_java_language_symbol_count(void)
{
    return ts_language_symbol_count(tree_sitter_java());
}

uint32_t code_lens_c_language_abi_version(void)
{
    return ts_language_abi_version(tree_sitter_c());
}

uint32_t code_lens_c_language_symbol_count(void)
{
    return ts_language_symbol_count(tree_sitter_c());
}

/* Database wrapper */

#define QUERY_TIMEOUT_MS 30000
#define QUERY_TIMEOUT_SECONDS 30.0
#define QUERY_PROGRESS_OPS 10000
#define CODE_LENS_INDEX_FORMAT_VERSION 14

static double profile_now_seconds(void);

static int report_sqlite_error(sqlite3 *handle, const char *prefix)
{
    (void)fprintf(stderr,
                  "code-lens: %s: %s\n",
                  prefix,
                  handle == nullptr ? "unknown" : sqlite3_errmsg(handle));
    return -1;
}

/* Aborts a read query once the connection deadline passes. SQLite has no
 * per-query timeout API, so the progress handler enforces the cap. */
static int query_deadline_expired(void *ctx)
{
    const CodeLensDb *db = ctx;
    return profile_now_seconds() > db->deadline_seconds ? 1 : 0;
}

static int db_read_format_version(CodeLensDb *db, int *out_version)
{
    sqlite3_stmt *stmt = nullptr;
    int rc;

    if (sqlite3_prepare_v2(db->handle, "PRAGMA user_version", -1, &stmt, nullptr) != SQLITE_OK) {
        return report_sqlite_error(db->handle, "read index format version");
    }
    rc = sqlite3_step(stmt);
    if (rc != SQLITE_ROW) {
        (void)sqlite3_finalize(stmt);
        return report_sqlite_error(db->handle, "read index format version");
    }
    *out_version = sqlite3_column_int(stmt, 0);
    (void)sqlite3_finalize(stmt);
    return 0;
}

static int db_open_common(CodeLensDb *db, const char *path, int flags)
{
    if ((db == nullptr) || (path == nullptr)) {
        return -1;
    }

    (void)memset(db, 0, sizeof(*db));
    db->path = copy_bytes(path, strlen(path));
    if (db->path == nullptr) {
        return -1;
    }

    if (sqlite3_open_v2(db->path, &db->handle, flags, nullptr) != SQLITE_OK) {
        (void)report_sqlite_error(db->handle, "open database");
        (void)sqlite3_close(db->handle);
        (void)memset(db, 0, sizeof(*db));
        return -1;
    }

    (void)sqlite3_busy_timeout(db->handle, QUERY_TIMEOUT_MS);
    db->is_open = true;
    return 0;
}

static int db_open_read_any_format(CodeLensDb *db,
                                   const char *path,
                                   int *out_format_version)
{
    if ((out_format_version == nullptr) ||
        (db_open_common(db, path, SQLITE_OPEN_READONLY) != 0)) {
        return -1;
    }
    if (db_read_format_version(db, out_format_version) != 0) {
        code_lens_db_close(db);
        return -1;
    }
    db->deadline_seconds = profile_now_seconds() + QUERY_TIMEOUT_SECONDS;
    sqlite3_progress_handler(db->handle, QUERY_PROGRESS_OPS, query_deadline_expired, db);
    return 0;
}

int code_lens_db_open_read(CodeLensDb *db, const char *path)
{
    int format_version = 0;

    if (db_open_read_any_format(db, path, &format_version) != 0) {
        return -1;
    }

    if (format_version != CODE_LENS_INDEX_FORMAT_VERSION) {
        (void)fprintf(stderr,
                      "code-lens: index %s has format version %d, expected %d; "
                      "re-index required\n",
                      path,
                      format_version,
                      CODE_LENS_INDEX_FORMAT_VERSION);
        code_lens_db_close(db);
        return -1;
    }

    return 0;
}

/* Opens a staging database for a full rebuild. Durability pragmas are off
 * because the finished file is published with an atomic rename; a crash
 * mid-build only leaves a stale staging file behind. The 256MB page cache
 * comfortably holds a large staging index (the benchmark repo builds a
 * ~106MB database); an undersized cache is actively hostile here because
 * mid-build eviction interacts badly with the write-once load and the
 * final flush at COMMIT. */
int code_lens_db_open_build(CodeLensDb *db, const char *path)
{
    static const char build_pragmas[] =
        "PRAGMA page_size=8192;"
        "PRAGMA journal_mode=OFF;"
        "PRAGMA synchronous=OFF;"
        "PRAGMA temp_store=MEMORY;"
        "PRAGMA cache_size=-262144;";

    if (db_open_common(db, path, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE) != 0) {
        return -1;
    }

    if (code_lens_db_exec(db, build_pragmas) != 0) {
        code_lens_db_close(db);
        return -1;
    }
    return 0;
}

void code_lens_db_close(CodeLensDb *db)
{
    if ((db == nullptr) || !db->is_open) {
        return;
    }

    if (sqlite3_close(db->handle) != SQLITE_OK) {
        (void)report_sqlite_error(db->handle, "close database");
    }
    (void)memset(db, 0, sizeof(*db));
}

int code_lens_db_exec(CodeLensDb *db, const char *query)
{
    char *error = nullptr;

    if ((db == nullptr) || !db->is_open || (query == nullptr)) {
        return -1;
    }

    if (sqlite3_exec(db->handle, query, nullptr, nullptr, &error) != SQLITE_OK) {
        (void)fprintf(stderr,
                      "code-lens: query failed: %s\n",
                      error == nullptr ? query : error);
        sqlite3_free(error);
        return -1;
    }
    return 0;
}

static int db_prepare(CodeLensDb *db, const char *sql, sqlite3_stmt **out_stmt)
{
    if ((db == nullptr) || !db->is_open || (sql == nullptr) || (out_stmt == nullptr)) {
        return -1;
    }
    if (sqlite3_prepare_v2(db->handle, sql, -1, out_stmt, nullptr) != SQLITE_OK) {
        (void)fprintf(stderr, "code-lens: query failed: %s\n", sqlite3_errmsg(db->handle));
        return -1;
    }
    return 0;
}

/* Binds a text parameter, mapping NULL to the empty string so the schema
 * never stores SQL NULLs for optional text such as docs. */
static int bind_text(sqlite3_stmt *stmt, int index, const char *value)
{
    if (value == nullptr) {
        value = "";
    }
    return sqlite3_bind_text(stmt, index, value, -1, SQLITE_STATIC) == SQLITE_OK ? 0 : -1;
}

static int bind_text_len(sqlite3_stmt *stmt, int index, const char *value, size_t len)
{
    if (value == nullptr) {
        value = "";
        len = 0U;
    }
    if (len > (size_t)INT_MAX) {
        return -1;
    }
    return sqlite3_bind_text(stmt, index, value, (int)len, SQLITE_STATIC) == SQLITE_OK ? 0 : -1;
}

/* Renders a prepared statement in the pipe format shared by every read
 * command: one header line of column names, then one line per row, values
 * separated by '|', SQL NULL rendered as an empty string, and every line
 * newline-terminated. Stops after max_rows rows (pass SIZE_MAX for no cap)
 * and reports whether the cap cut anything off. */
static char *render_stmt_result_capped(sqlite3_stmt *stmt,
                                       size_t max_rows,
                                       size_t *out_row_count,
                                       bool *out_capped)
{
    StringBuilder out = {0};
    int column_count = sqlite3_column_count(stmt);
    size_t row_count = 0U;
    int rc;

    if (out_row_count != nullptr) {
        *out_row_count = 0U;
    }
    if (out_capped != nullptr) {
        *out_capped = false;
    }

    for (int i = 0; i < column_count; i++) {
        const char *name = sqlite3_column_name(stmt, i);
        if (((i > 0) && !sb_append(&out, "|")) ||
            !sb_append(&out, name == nullptr ? "" : name)) {
            goto fail;
        }
    }
    if (!sb_append(&out, "\n")) {
        goto fail;
    }

    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
        if (row_count >= max_rows) {
            if (out_capped != nullptr) {
                *out_capped = true;
            }
            rc = SQLITE_DONE;
            break;
        }
        for (int i = 0; i < column_count; i++) {
            const unsigned char *value = sqlite3_column_text(stmt, i);
            int bytes = sqlite3_column_bytes(stmt, i);

            if ((i > 0) && !sb_append(&out, "|")) {
                goto fail;
            }
            if ((value != nullptr) && (bytes > 0) &&
                !sb_append_len(&out, (const char *)value, (size_t)bytes)) {
                goto fail;
            }
        }
        if (!sb_append(&out, "\n")) {
            goto fail;
        }
        row_count++;
    }

    if (rc != SQLITE_DONE) {
        (void)report_sqlite_error(sqlite3_db_handle(stmt), "query failed");
        goto fail;
    }

    if (out_row_count != nullptr) {
        *out_row_count = row_count;
    }
    return out.data;

fail:
    sb_free(&out);
    return nullptr;
}

static char *render_stmt_result(sqlite3_stmt *stmt, size_t *out_row_count)
{
    return render_stmt_result_capped(stmt, SIZE_MAX, out_row_count, nullptr);
}

char *code_lens_db_query_to_string(CodeLensDb *db, const char *query)
{
    sqlite3_stmt *stmt = nullptr;
    char *text;

    if ((db == nullptr) || !db->is_open || (query == nullptr)) {
        return nullptr;
    }

    if (db_prepare(db, query, &stmt) != 0) {
        return nullptr;
    }
    text = render_stmt_result(stmt, nullptr);
    (void)sqlite3_finalize(stmt);
    return text;
}

/* Prepares, binds, renders, and finalizes one query: text parameters bind as
 * ?1..?N in order, and the optional int parameter binds after them. */
static char *db_render_query(CodeLensDb *db,
                             const char *sql,
                             const char *const *text_params,
                             size_t text_param_count,
                             const int *int_param,
                             size_t *out_row_count)
{
    sqlite3_stmt *stmt = nullptr;
    char *text = nullptr;
    bool bound = true;

    if (db_prepare(db, sql, &stmt) != 0) {
        return nullptr;
    }

    for (size_t i = 0U; i < text_param_count; i++) {
        if (bind_text(stmt, (int)(i + 1U), text_params[i]) != 0) {
            bound = false;
            break;
        }
    }
    if (bound && (int_param != nullptr) &&
        (sqlite3_bind_int(stmt, (int)(text_param_count + 1U), *int_param) != SQLITE_OK)) {
        bound = false;
    }

    if (!bound) {
        (void)report_sqlite_error(db->handle, "bind query parameter");
    } else {
        text = render_stmt_result(stmt, out_row_count);
    }
    (void)sqlite3_finalize(stmt);
    return text;
}

/* Clojure parser */

/*
 * Node kinds for the custom reader (see "Custom Clojure reader" below).
 * Only the kinds the extraction walk distinguishes get their own value;
 * everything else collapses into LEAF (no children, not annotatable),
 * WRAP (children, metadata-annotatable) or SYMVAL (children, not
 * annotatable).
 */
enum {
    CP_NODE_ROOT = 0,
    CP_NODE_SYM,
    CP_NODE_KWD,
    CP_NODE_STR,
    CP_NODE_LIST,
    CP_NODE_VEC,
    CP_NODE_COMMENT,
    CP_NODE_DIS,
    CP_NODE_META,
    CP_NODE_OLD_META,
    CP_NODE_LEAF,
    CP_NODE_WRAP,
    CP_NODE_SYMVAL
};

typedef uint8_t CpNodeKind;

typedef struct {
    uint32_t start_byte;
    uint32_t end_byte;
    uint32_t value_start; /* SYM: start of the metadata-free symbol text */
    uint32_t name_start;  /* SYM: start of the grammar "name" field text */
    uint32_t start_row;
    uint32_t start_col;
    uint32_t end_row;
    uint32_t first_child;
    uint32_t last_child;
    uint32_t next_sibling;
    CpNodeKind kind;
} CpNode;

struct CodeLensClojureParser {
    TSParser *parser;
    CpNode *nodes; /* reusable node pool for the custom reader */
    uint32_t node_capacity;
    bool force_treesitter;
};

static pthread_once_t clojure_ids_once = PTHREAD_ONCE_INIT;
static TSSymbol clojure_sym_lit;
static TSSymbol clojure_kwd_lit;
static TSSymbol clojure_list_lit;
static TSSymbol clojure_vec_lit;
static TSSymbol clojure_str_lit;
static TSSymbol clojure_comment;
static TSSymbol clojure_dis_expr;
static TSSymbol clojure_meta_lit;
static TSSymbol clojure_old_meta_lit;
static TSSymbol clojure_read_cond_lit;
static TSSymbol clojure_splicing_read_cond_lit;

static void init_clojure_ids(void)
{
    const TSLanguage *language = tree_sitter_clojure();

    clojure_sym_lit = ts_language_symbol_for_name(language, "sym_lit", 7U, true);
    clojure_kwd_lit = ts_language_symbol_for_name(language, "kwd_lit", 7U, true);
    clojure_list_lit = ts_language_symbol_for_name(language, "list_lit", 8U, true);
    clojure_vec_lit = ts_language_symbol_for_name(language, "vec_lit", 7U, true);
    clojure_str_lit = ts_language_symbol_for_name(language, "str_lit", 7U, true);
    clojure_comment = ts_language_symbol_for_name(language, "comment", 7U, true);
    clojure_dis_expr = ts_language_symbol_for_name(language, "dis_expr", 8U, true);
    clojure_meta_lit = ts_language_symbol_for_name(language, "meta_lit", 8U, true);
    clojure_old_meta_lit = ts_language_symbol_for_name(language, "old_meta_lit", 12U, true);
    clojure_read_cond_lit = ts_language_symbol_for_name(language, "read_cond_lit", 13U, true);
    clojure_splicing_read_cond_lit =
        ts_language_symbol_for_name(language, "splicing_read_cond_lit", 22U, true);
}

static void ensure_clojure_ids(void)
{
    (void)pthread_once(&clojure_ids_once, init_clojure_ids);
}

static bool is_sym_lit(TSNode node)
{
    return ts_node_symbol(node) == clojure_sym_lit;
}

static bool is_list_lit(TSNode node)
{
    return ts_node_symbol(node) == clojure_list_lit;
}

static bool is_vec_lit(TSNode node)
{
    return ts_node_symbol(node) == clojure_vec_lit;
}

static bool is_str_lit(TSNode node)
{
    return ts_node_symbol(node) == clojure_str_lit;
}

/* Reader conditionals hold alternating platform keywords and forms as
 * direct children (the grammar inlines them, no inner list node). */
static bool is_read_cond(TSNode node)
{
    TSSymbol symbol = ts_node_symbol(node);

    return (symbol == clojure_read_cond_lit) || (symbol == clojure_splicing_read_cond_lit);
}

static bool is_splicing_read_cond(TSNode node)
{
    return ts_node_symbol(node) == clojure_splicing_read_cond_lit;
}

static bool is_non_value_child(TSNode node)
{
    TSSymbol symbol = ts_node_symbol(node);

    return (symbol == clojure_comment) || (symbol == clojure_dis_expr) ||
           (symbol == clojure_meta_lit) || (symbol == clojure_old_meta_lit);
}

static char *copy_cstr(const char *value)
{
    return copy_bytes(value, strlen(value));
}

typedef struct {
    const char *data;
    size_t len;
    uint32_t start_byte;
    uint32_t end_byte;
} SourceSlice;

static bool node_source_slice(const char *source, TSNode node, SourceSlice *out_slice)
{
    uint32_t start = ts_node_start_byte(node);
    uint32_t end = ts_node_end_byte(node);

    if (end < start) {
        return false;
    }
    out_slice->data = source + start;
    out_slice->len = (size_t)(end - start);
    out_slice->start_byte = start;
    out_slice->end_byte = end;
    return true;
}

static char *copy_source_slice(SourceSlice slice)
{
    return copy_bytes(slice.data, slice.len);
}

static char *node_text(const char *source, TSNode node)
{
    SourceSlice slice;

    if (!node_source_slice(source, node, &slice)) {
        return nullptr;
    }
    return copy_source_slice(slice);
}

static bool node_text_equals(const char *source, TSNode node, const char *text, size_t text_len)
{
    SourceSlice slice;

    if (!node_source_slice(source, node, &slice)) {
        return false;
    }
    return (slice.len == text_len) && (memcmp(slice.data, text, text_len) == 0);
}

static bool source_slice_equals(SourceSlice slice, const char *text, size_t text_len)
{
    return (slice.len == text_len) && (memcmp(slice.data, text, text_len) == 0);
}

static char *string_literal_text(const char *source, TSNode node)
{
    SourceSlice slice;

    if (!node_source_slice(source, node, &slice)) {
        return nullptr;
    }
    if ((slice.len >= 2U) && (slice.data[0] == '"') && (slice.data[slice.len - 1U] == '"')) {
        slice.data++;
        slice.len -= 2U;
        slice.start_byte++;
        slice.end_byte--;
    }
    return copy_source_slice(slice);
}

/*
 * The grammar nests metadata inside the annotated sym_lit node
 * (sym_lit: seq(repeat(_metadata_lit), choice(_sym_qualified, _sym_unqualified))),
 * so the raw node span for `^:private foo` includes the metadata. Slice from the
 * start of the "namespace" field child (or the "name" field child when
 * unqualified) to the end of the node to get the metadata-free symbol value.
 */
static bool symbol_value_slice(const char *source, TSNode node, SourceSlice *out_slice)
{
    TSNode name_node = ts_node_child_by_field_name(node, "name", 4U);
    TSNode namespace_node;
    uint32_t start;
    uint32_t end;

    if (ts_node_is_null(name_node)) {
        return node_source_slice(source, node, out_slice);
    }

    namespace_node = ts_node_child_by_field_name(node, "namespace", 9U);
    start = ts_node_is_null(namespace_node) ? ts_node_start_byte(name_node)
                                            : ts_node_start_byte(namespace_node);
    end = ts_node_end_byte(node);
    if (end < start) {
        return false;
    }
    out_slice->data = source + start;
    out_slice->len = (size_t)(end - start);
    out_slice->start_byte = start;
    out_slice->end_byte = end;
    return true;
}

static char *symbol_text_with_len(const char *source, TSNode node, size_t *out_len)
{
    SourceSlice slice;

    if (!symbol_value_slice(source, node, &slice)) {
        return nullptr;
    }
    if (out_len != nullptr) {
        *out_len = slice.len;
    }
    return copy_source_slice(slice);
}

static char *symbol_text(const char *source, TSNode node)
{
    return symbol_text_with_len(source, node, nullptr);
}

/*
 * Base name of a symbol node: the text of the grammar's "name" field child,
 * which per the grammar is exactly the part after the namespace delimiter for
 * qualified symbols and the whole symbol otherwise. Field access also skips
 * any metadata nested inside the node.
 */
static bool symbol_base_slice(const char *source, TSNode node, SourceSlice *out_slice)
{
    TSNode name_node = ts_node_child_by_field_name(node, "name", 4U);

    if (ts_node_is_null(name_node)) {
        return node_source_slice(source, node, out_slice);
    }
    return node_source_slice(source, name_node, out_slice);
}

static bool is_definition_form_base(SourceSlice base, const char **kind)
{
    if (source_slice_equals(base, "defn", sizeof("defn") - 1U) ||
        source_slice_equals(base, "defn-", sizeof("defn-") - 1U)) {
        *kind = "function";
        return true;
    }
    if (source_slice_equals(base, "def", sizeof("def") - 1U)) {
        *kind = "var";
        return true;
    }
    if (source_slice_equals(base, "defmacro", sizeof("defmacro") - 1U)) {
        *kind = "macro";
        return true;
    }
    if (source_slice_equals(base, "defmulti", sizeof("defmulti") - 1U)) {
        *kind = "multimethod";
        return true;
    }
    if (source_slice_equals(base, "defmethod", sizeof("defmethod") - 1U)) {
        *kind = "method";
        return true;
    }
    if (source_slice_equals(base, "defprotocol", sizeof("defprotocol") - 1U)) {
        *kind = "protocol";
        return true;
    }
    if (source_slice_equals(base, "defrecord", sizeof("defrecord") - 1U)) {
        *kind = "record";
        return true;
    }
    if (source_slice_equals(base, "deftype", sizeof("deftype") - 1U)) {
        *kind = "type";
        return true;
    }
    if (source_slice_equals(base, "deftest", sizeof("deftest") - 1U)) {
        *kind = "test";
        return true;
    }
    return false;
}

static bool reserve_symbols(CodeLensClojureFile *file)
{
    return (file->symbol_count < file->symbol_capacity) ||
           grow_array((void **)&file->symbols,
                      &file->symbol_capacity,
                      sizeof(file->symbols[0]),
                      16U);
}

static bool reserve_aliases(CodeLensClojureFile *file)
{
    return (file->alias_count < file->alias_capacity) ||
           grow_array((void **)&file->aliases,
                      &file->alias_capacity,
                      sizeof(file->aliases[0]),
                      8U);
}

static bool reserve_referred(CodeLensClojureFile *file)
{
    return (file->referred_count < file->referred_capacity) ||
           grow_array((void **)&file->referred,
                      &file->referred_capacity,
                      sizeof(file->referred[0]),
                      8U);
}

static bool reserve_references(CodeLensClojureFile *file)
{
    return (file->reference_count < file->reference_capacity) ||
           grow_array((void **)&file->references,
                      &file->reference_capacity,
                      sizeof(file->references[0]),
                      64U);
}

static bool reserve_keywords(CodeLensClojureFile *file)
{
    return (file->keyword_count < file->keyword_capacity) ||
           grow_array((void **)&file->keywords,
                      &file->keyword_capacity,
                      sizeof(file->keywords[0]),
                      64U);
}

static size_t value_children(TSNode node, TSNode *values, size_t max_values)
{
    size_t value_count = 0U;
    uint32_t count = ts_node_named_child_count(node);

    for (uint32_t i = 0U; i < count; i++) {
        TSNode child = ts_node_named_child(node, i);
        if (is_non_value_child(child)) {
            continue;
        }
        if (value_count < max_values) {
            values[value_count] = child;
        }
        value_count++;
    }

    return value_count;
}

static bool first_value_child(TSNode node, TSNode *out_child)
{
    uint32_t count = ts_node_named_child_count(node);

    for (uint32_t i = 0U; i < count; i++) {
        TSNode child = ts_node_named_child(node, i);
        if (is_non_value_child(child)) {
            continue;
        }
        *out_child = child;
        return true;
    }

    return false;
}

static bool add_alias(CodeLensClojureFile *file, char *namespace_name, char *alias)
{
    CodeLensNamespaceAlias *item;

    if (!reserve_aliases(file)) {
        return false;
    }

    item = &file->aliases[file->alias_count++];
    item->namespace_name = namespace_name;
    item->alias = alias;
    return true;
}

static bool add_referred(CodeLensClojureFile *file, char *namespace_name, char *symbol)
{
    CodeLensReferred *item;

    if ((namespace_name == nullptr) || (symbol == nullptr) || !reserve_referred(file)) {
        return false;
    }

    item = &file->referred[file->referred_count++];
    item->namespace_name = namespace_name;
    item->symbol = symbol;
    return true;
}

/* One require spec, e.g. [my.ns :as m]. Returns false only on allocation
 * failure. Reader conditionals are unwrapped recursively: every branch is
 * treated as a candidate spec (platform keywords fail the vec check and are
 * skipped); splicing branches (#?@) hold a vector OF specs. */
static bool extract_require_spec(const char *source, TSNode spec, CodeLensClojureFile *file)
{
    TSNode spec_values[64];
    size_t spec_count;
    char *namespace_name;
    char *alias = nullptr;

    if (is_read_cond(spec)) {
        TSNode branch_values[64];
        size_t branch_count = value_children(spec, branch_values, 64U);
        bool splicing = is_splicing_read_cond(spec);

        for (size_t i = 0U; i < branch_count; i++) {
            if (splicing && is_vec_lit(branch_values[i])) {
                TSNode inner_values[64];
                size_t inner_count = value_children(branch_values[i], inner_values, 64U);

                for (size_t j = 0U; j < inner_count; j++) {
                    if (!extract_require_spec(source, inner_values[j], file)) {
                        return false;
                    }
                }
            } else if (!extract_require_spec(source, branch_values[i], file)) {
                return false;
            }
        }
        return true;
    }

    if (!is_vec_lit(spec)) {
        return true;
    }

    spec_count = value_children(spec, spec_values, 64U);
    if ((spec_count == 0U) || !is_sym_lit(spec_values[0])) {
        return true;
    }

    namespace_name = symbol_text(source, spec_values[0]);
    if (namespace_name == nullptr) {
        return true;
    }

    for (size_t j = 1U; j + 1U < spec_count; j++) {
        if ((node_text_equals(source, spec_values[j], ":as", sizeof(":as") - 1U) ||
             node_text_equals(source, spec_values[j], ":as-alias", sizeof(":as-alias") - 1U)) &&
            is_sym_lit(spec_values[j + 1U])) {
            alias = symbol_text(source, spec_values[j + 1U]);
            break;
        }
    }

    /* :refer [a b] records one row per symbol; :refer :all records the
     * ":all" sentinel. */
    for (size_t j = 1U; j + 1U < spec_count; j++) {
        if (!node_text_equals(source, spec_values[j], ":refer", sizeof(":refer") - 1U)) {
            continue;
        }
        if (is_vec_lit(spec_values[j + 1U])) {
            TSNode refer_values[128];
            size_t refer_count = value_children(spec_values[j + 1U], refer_values, 128U);

            for (size_t k = 0U; k < refer_count; k++) {
                if (is_sym_lit(refer_values[k]) &&
                    !add_referred(file,
                                  namespace_name,
                                  symbol_text(source, refer_values[k]))) {
                    return false;
                }
            }
        } else if (node_text_equals(source, spec_values[j + 1U], ":all",
                                    sizeof(":all") - 1U)) {
            if (!add_referred(file, namespace_name, copy_cstr(":all"))) {
                return false;
            }
        }
        break;
    }

    if (alias == nullptr) {
        alias = copy_cstr("");
    }

    if (alias == nullptr) {
        return false;
    }

    return add_alias(file, namespace_name, alias);
}

static void extract_require_aliases(const char *source, TSNode require_list, CodeLensClojureFile *file)
{
    TSNode values[256];
    size_t count = value_children(require_list, values, 256U);

    for (size_t i = 1U; i < count; i++) {
        if (!extract_require_spec(source, values[i], file)) {
            return;
        }
    }
}

/* One (:use ...) spec: a bare symbol or a vec whose first element is the
 * namespace. Everything :use brings in is unqualified, so each spec records
 * the ":all" sentinel (:only narrowing is not tracked). */
static bool extract_use_spec(const char *source, TSNode spec, CodeLensClojureFile *file)
{
    if (is_read_cond(spec)) {
        TSNode branch_values[64];
        size_t branch_count = value_children(spec, branch_values, 64U);
        bool splicing = is_splicing_read_cond(spec);

        for (size_t i = 0U; i < branch_count; i++) {
            if (splicing && is_vec_lit(branch_values[i])) {
                TSNode inner_values[64];
                size_t inner_count = value_children(branch_values[i], inner_values, 64U);

                for (size_t j = 0U; j < inner_count; j++) {
                    if (!extract_use_spec(source, inner_values[j], file)) {
                        return false;
                    }
                }
            } else if (!extract_use_spec(source, branch_values[i], file)) {
                return false;
            }
        }
        return true;
    }

    if (is_sym_lit(spec)) {
        char *namespace_name = symbol_text(source, spec);

        if (namespace_name == nullptr) {
            return true;
        }
        return add_referred(file, namespace_name, copy_cstr(":all"));
    }
    if (is_vec_lit(spec)) {
        TSNode spec_values[8];
        size_t spec_count = value_children(spec, spec_values, 8U);

        if ((spec_count >= 1U) && is_sym_lit(spec_values[0])) {
            char *namespace_name = symbol_text(source, spec_values[0]);

            if (namespace_name == nullptr) {
                return true;
            }
            return add_referred(file, namespace_name, copy_cstr(":all"));
        }
    }
    return true;
}

/* Walks (:use ...) clause specs with the same fan-out as
 * extract_require_aliases. */
static void extract_use_referred(const char *source, TSNode use_list, CodeLensClojureFile *file)
{
    TSNode values[256];
    size_t count = value_children(use_list, values, 256U);

    for (size_t i = 1U; i < count; i++) {
        if (!extract_use_spec(source, values[i], file)) {
            return;
        }
    }
}

/* One ns-form clause, e.g. (:require ...). Reader conditionals are unwrapped
 * the same way as in extract_require_spec so that
 * #?(:clj (:require [a :as b])) contributes aliases. */
static void extract_ns_clause(const char *source, TSNode clause, CodeLensClojureFile *file)
{
    TSNode require_values[32];
    size_t require_count;

    if (is_read_cond(clause)) {
        TSNode branch_values[32];
        size_t branch_count = value_children(clause, branch_values, 32U);
        bool splicing = is_splicing_read_cond(clause);

        for (size_t i = 0U; i < branch_count; i++) {
            if (splicing && is_vec_lit(branch_values[i])) {
                TSNode inner_values[32];
                size_t inner_count = value_children(branch_values[i], inner_values, 32U);

                for (size_t j = 0U; j < inner_count; j++) {
                    extract_ns_clause(source, inner_values[j], file);
                }
            } else {
                extract_ns_clause(source, branch_values[i], file);
            }
        }
        return;
    }

    if (!is_list_lit(clause)) {
        return;
    }

    require_count = value_children(clause, require_values, 32U);
    if (require_count == 0U) {
        return;
    }

    if (node_text_equals(source, require_values[0], ":require", sizeof(":require") - 1U)) {
        extract_require_aliases(source, clause, file);
    } else if (node_text_equals(source, require_values[0], ":use", sizeof(":use") - 1U)) {
        extract_use_referred(source, clause, file);
    }
}

static void extract_ns_form(const char *source, TSNode list, CodeLensClojureFile *file)
{
    TSNode values[256];
    size_t count = value_children(list, values, 256U);

    if ((count >= 2U) && is_sym_lit(values[1])) {
        char *namespace_name = symbol_text(source, values[1]);
        if (namespace_name != nullptr) {
            file->namespace_name = namespace_name;
        }
    }

    for (size_t i = 2U; i < count; i++) {
        extract_ns_clause(source, values[i], file);
    }
}

static bool add_definition(const char *source,
                           TSNode list,
                           TSNode name_node,
                           const char *kind,
                           CodeLensClojureFile *file,
                           TSNode *values,
                           size_t count,
                           size_t name_index)
{
    CodeLensSymbol *symbol;

    if (!reserve_symbols(file)) {
        return false;
    }

    symbol = &file->symbols[file->symbol_count];
    (void)memset(symbol, 0, sizeof(*symbol));
    symbol->kind = copy_cstr(kind);
    symbol->name = symbol_text(source, name_node);
    symbol->namespace_name =
        file->namespace_name == nullptr ? copy_cstr("") : copy_cstr(file->namespace_name);
    symbol->source = node_text(source, list);
    symbol->start_line = ts_node_start_point(list).row + 1U;
    symbol->end_line = ts_node_end_point(list).row + 1U;

    for (size_t i = name_index + 1U; i < count; i++) {
        if (is_str_lit(values[i])) {
            symbol->doc = string_literal_text(source, values[i]);
            break;
        }
        if (is_vec_lit(values[i]) || is_list_lit(values[i])) {
            break;
        }
    }

    if ((symbol->kind == nullptr) || (symbol->name == nullptr) ||
        (symbol->namespace_name == nullptr) || (symbol->source == nullptr)) {
        (void)memset(symbol, 0, sizeof(*symbol));
        return false;
    }

    file->symbol_count++;
    return true;
}

static bool extract_definition_form(const char *source,
                                     TSNode list,
                                     const char *kind,
                                     CodeLensClojureFile *file)
{
    TSNode values[512];
    size_t count;

    count = value_children(list, values, 512U);
    for (size_t i = 1U; i < count; i++) {
        if (is_sym_lit(values[i])) {
            return add_definition(source, list, values[i], kind, file, values, count, i);
        }
    }

    return true;
}

static bool add_reference(const char *source, TSNode node, CodeLensClojureFile *file)
{
    CodeLensReference *reference;
    SourceSlice slice;
    char *symbol;

    if (!symbol_value_slice(source, node, &slice)) {
        return false;
    }
    symbol = copy_source_slice(slice);
    if (symbol == nullptr) {
        return false;
    }

    if (!reserve_references(file)) {
        return false;
    }

    reference = &file->references[file->reference_count++];
    reference->symbol = symbol;
    reference->symbol_len = slice.len;
    reference->line = ts_node_start_point(node).row + 1U;
    reference->column = ts_node_start_point(node).column + 1U;
    reference->start_byte = slice.start_byte;
    reference->end_byte = slice.end_byte;
    return true;
}

/* wyhash-style multiply-mix over 8-byte chunks: term_intern runs a few
 * million times per index build, so the byte-at-a-time FNV loop this
 * replaces was a measurable share of coordinator CPU. Only slot
 * placement depends on the hash; term ids stay first-sight ordered.
 * Defined here in the parser section because the parse workers precompute
 * these hashes for every reference and keyword they extract. */
static uint64_t term_hash(const char *text, size_t len)
{
    const uint8_t *p = (const uint8_t *)text;
    uint64_t h = 0x9E3779B97F4A7C15ULL ^ ((uint64_t)len * 0xFF51AFD7ED558CCDULL);
    size_t n = len;

    while (n >= 8U) {
        uint64_t k;

        (void)memcpy(&k, p, 8U);
        h = (h ^ k) * 0x2545F4914F6CDD1DULL;
        h ^= h >> 29U;
        p += 8U;
        n -= 8U;
    }
    if (n > 0U) {
        uint64_t k = 0U;

        (void)memcpy(&k, p, n);
        h = (h ^ k) * 0x2545F4914F6CDD1DULL;
    }
    h ^= h >> 32U;
    h *= 0xD6E8FEB86659FD93ULL;
    h ^= h >> 32U;
    return h;
}

static const CodeLensNamespaceAlias *find_alias(const CodeLensClojureFile *file,
                                                 const char *name,
                                                 size_t name_len)
{
    for (size_t i = 0U; i < file->alias_count; i++) {
        const CodeLensNamespaceAlias *alias = &file->aliases[i];

        if ((alias->alias != nullptr) && (alias->namespace_name != nullptr) &&
            (strlen(alias->alias) == name_len) && (memcmp(alias->alias, name, name_len) == 0)) {
            return alias;
        }
    }
    return nullptr;
}

static bool init_keyword_fields(CodeLensKeyword *keyword, const CodeLensClojureFile *file)
{
    const char *text = keyword->keyword;
    size_t marker_len;
    const char *body;
    size_t body_len;
    const char *slash = nullptr;
    size_t qualifier_len = 0U;
    bool auto_resolved;

    if ((text == nullptr) || (keyword->keyword_len == 0U) || (text[0] != ':')) {
        return false;
    }

    auto_resolved = (keyword->keyword_len >= 2U) && (text[1] == ':');
    marker_len = auto_resolved ? 2U : 1U;
    if (keyword->keyword_len <= marker_len) {
        return false;
    }

    body = text + marker_len;
    body_len = keyword->keyword_len - marker_len;
    for (size_t i = body_len; i > 0U; i--) {
        if (body[i - 1U] == '/') {
            slash = body + i - 1U;
            qualifier_len = i - 1U;
            break;
        }
    }

    if ((slash != nullptr) && (qualifier_len > 0U) && (qualifier_len + 1U < body_len)) {
        const char *base = slash + 1U;
        size_t base_len = body_len - qualifier_len - 1U;

        keyword->qualifier = copy_bytes(body, qualifier_len);
        keyword->qualifier_len = qualifier_len;
        keyword->base = copy_bytes(base, base_len);
        keyword->base_len = base_len;

        if (auto_resolved) {
            const CodeLensNamespaceAlias *alias = find_alias(file, body, qualifier_len);
            if (alias != nullptr) {
                keyword->target_namespace = copy_cstr(alias->namespace_name);
                keyword->target_namespace_len = strlen(alias->namespace_name);
            } else {
                keyword->target_namespace = copy_bytes(body, qualifier_len);
                keyword->target_namespace_len = qualifier_len;
            }
        } else {
            keyword->target_namespace = copy_bytes(body, qualifier_len);
            keyword->target_namespace_len = qualifier_len;
        }
    } else {
        keyword->qualifier = copy_cstr("");
        keyword->qualifier_len = 0U;
        keyword->base = copy_bytes(body, body_len);
        keyword->base_len = body_len;
        if (auto_resolved) {
            const char *namespace_name = file->namespace_name == nullptr ? "" : file->namespace_name;
            keyword->target_namespace = copy_cstr(namespace_name);
            keyword->target_namespace_len = strlen(namespace_name);
        } else {
            keyword->target_namespace = copy_cstr("");
            keyword->target_namespace_len = 0U;
        }
    }

    if ((keyword->base == nullptr) || (keyword->qualifier == nullptr) ||
        (keyword->target_namespace == nullptr)) {
        return false;
    }
    keyword->keyword_hash = term_hash(keyword->keyword, keyword->keyword_len);
    keyword->base_hash = term_hash(keyword->base, keyword->base_len);
    keyword->qualifier_hash = term_hash(keyword->qualifier, keyword->qualifier_len);
    keyword->target_namespace_hash =
        term_hash(keyword->target_namespace, keyword->target_namespace_len);
    return true;
}

static bool add_keyword(const char *source, TSNode node, CodeLensClojureFile *file)
{
    CodeLensKeyword *keyword;
    SourceSlice slice;

    if (!node_source_slice(source, node, &slice)) {
        return false;
    }
    if (!reserve_keywords(file)) {
        return false;
    }

    keyword = &file->keywords[file->keyword_count];
    (void)memset(keyword, 0, sizeof(*keyword));
    keyword->keyword = copy_source_slice(slice);
    keyword->keyword_len = slice.len;
    keyword->line = ts_node_start_point(node).row + 1U;
    keyword->column = ts_node_start_point(node).column + 1U;
    if (!init_keyword_fields(keyword, file)) {
        (void)memset(keyword, 0, sizeof(*keyword));
        return false;
    }

    file->keyword_count++;
    return true;
}

/* ---------------------------------------------------------------------------
 * Custom Clojure reader
 *
 * Hand-written replacement for tree-sitter on the hot indexing path. It
 * lexes tokens exactly as vendor/tree-sitter-clojure/grammar.js defines
 * them and builds a lightweight CpNode tree with the same shape the
 * extraction walk observes from tree-sitter (metadata and gap nodes nested
 * inside annotated nodes, discarded forms still present, read-conditional
 * children inlined, ...). A port of the tree-sitter walk then produces
 * byte-identical CodeLensClojureFile output.
 *
 * Anything the reader cannot model with certainty -- constructs tree-sitter
 * would error-recover (unbalanced delimiters, metadata on non-annotatable
 * forms, `#=` eval forms, invalid dispatch sequences, ...) -- makes it
 * decline the file, and the caller falls back to the tree-sitter backend
 * for that file. Set CODE_LENS_PARSER=treesitter to force the fallback
 * everywhere.
 */

#define CP_MAX_DEPTH 256U
#define CP_MAX_PENDING 64U

typedef struct {
    const char *src;
    uint32_t len;
    uint32_t pos;
    uint32_t row;
    uint32_t line_start;
    CpNode *nodes;
    uint32_t node_count;
    uint32_t node_capacity;
    bool failed;
    bool oom;
} CpReader;

static bool cp_is_ascii_ws(unsigned char c)
{
    return (c == ' ') || (c == '\t') || (c == '\n') || (c == '\r') || (c == '\f') ||
           (c == ',') || (c == 0x0BU) || ((c >= 0x1CU) && (c <= 0x1FU));
}

/*
 * Multibyte whitespace accepted by the grammar: U+1680, U+2000-U+2006,
 * U+2008-U+200A, U+2028, U+2029, U+205F, U+3000. (U+2007, U+00A0 and
 * U+202F are deliberately absent: the grammar treats them as symbol
 * constituents.) All are 3-byte UTF-8 sequences, and no continuation byte
 * can be confused with a lead byte, so byte-wise scanning stays exact.
 */
static uint32_t cp_multibyte_ws_len(const unsigned char *s, uint32_t remaining)
{
    if ((remaining < 3U) || ((s[0] != 0xE1U) && (s[0] != 0xE2U) && (s[0] != 0xE3U))) {
        return 0U;
    }
    if ((s[0] == 0xE1U) && (s[1] == 0x9AU) && (s[2] == 0x80U)) {
        return 3U; /* U+1680 */
    }
    if (s[0] == 0xE2U) {
        if (s[1] == 0x80U) {
            unsigned char t = s[2];
            if (((t >= 0x80U) && (t <= 0x86U)) || ((t >= 0x88U) && (t <= 0x8AU)) ||
                (t == 0xA8U) || (t == 0xA9U)) {
                return 3U; /* U+2000-U+2006, U+2008-U+200A, U+2028, U+2029 */
            }
        }
        if ((s[1] == 0x81U) && (s[2] == 0x9FU)) {
            return 3U; /* U+205F */
        }
    }
    if ((s[0] == 0xE3U) && (s[1] == 0x80U) && (s[2] == 0x80U)) {
        return 3U; /* U+3000 */
    }
    return 0U;
}

static bool cp_is_ascii_delim(unsigned char c)
{
    if (cp_is_ascii_ws(c)) {
        return true;
    }
    switch (c) {
    case '(':
    case ')':
    case '[':
    case ']':
    case '{':
    case '}':
    case '"':
    case '@':
    case '~':
    case '^':
    case ';':
    case '`':
    case '\\':
        return true;
    default:
        return false;
    }
}

/*
 * Length consumed by the token-constituent character at pos, or 0 when the
 * character terminates a token. '/' counts as a constituent here; callers
 * treat it as structural where the grammar does.
 */
static uint32_t cp_body_char_len(const CpReader *r, uint32_t pos)
{
    unsigned char c;

    if (pos >= r->len) {
        return 0U;
    }
    c = (unsigned char)r->src[pos];
    if (c < 0x80U) {
        return cp_is_ascii_delim(c) ? 0U : 1U;
    }
    if (cp_multibyte_ws_len((const unsigned char *)r->src + pos, r->len - pos) != 0U) {
        return 0U;
    }
    return 1U;
}

static uint32_t cp_utf8_len(const unsigned char *s, uint32_t remaining)
{
    if (s[0] < 0x80U) {
        return 1U;
    }
    if ((s[0] & 0xE0U) == 0xC0U) {
        return ((remaining >= 2U) && ((s[1] & 0xC0U) == 0x80U)) ? 2U : 0U;
    }
    if ((s[0] & 0xF0U) == 0xE0U) {
        return ((remaining >= 3U) && ((s[1] & 0xC0U) == 0x80U) && ((s[2] & 0xC0U) == 0x80U))
                   ? 3U
                   : 0U;
    }
    if ((s[0] & 0xF8U) == 0xF0U) {
        return ((remaining >= 4U) && ((s[1] & 0xC0U) == 0x80U) && ((s[2] & 0xC0U) == 0x80U) &&
                ((s[3] & 0xC0U) == 0x80U))
                   ? 4U
                   : 0U;
    }
    return 0U;
}

static void cp_skip_ws(CpReader *r)
{
    while (r->pos < r->len) {
        unsigned char c = (unsigned char)r->src[r->pos];

        if (c == '\n') {
            r->pos += 1U;
            r->row += 1U;
            r->line_start = r->pos;
            continue;
        }
        if (c < 0x80U) {
            if (!cp_is_ascii_ws(c)) {
                return;
            }
            r->pos += 1U;
            continue;
        }
        {
            uint32_t w = cp_multibyte_ws_len((const unsigned char *)r->src + r->pos,
                                             r->len - r->pos);
            if (w == 0U) {
                return;
            }
            r->pos += w;
        }
    }
}

static uint32_t cp_node_new(CpReader *r, CpNodeKind kind)
{
    CpNode *node;
    uint32_t idx;

    if (r->node_count == r->node_capacity) {
        uint32_t new_capacity = (r->node_capacity == 0U) ? 4096U : (r->node_capacity * 2U);
        CpNode *grown = realloc(r->nodes, (size_t)new_capacity * sizeof(CpNode));

        if (grown == nullptr) {
            r->oom = true;
            return 0U;
        }
        r->nodes = grown;
        r->node_capacity = new_capacity;
    }
    idx = r->node_count++;
    node = &r->nodes[idx];
    (void)memset(node, 0, sizeof(*node));
    node->kind = kind;
    node->start_byte = r->pos;
    node->start_row = r->row;
    node->start_col = r->pos - r->line_start;
    return idx;
}

static void cp_node_finish(CpReader *r, uint32_t idx)
{
    CpNode *node = &r->nodes[idx];

    node->end_byte = r->pos;
    node->end_row = r->row;
}

static void cp_append_child(CpReader *r, uint32_t parent, uint32_t child)
{
    CpNode *parent_node = &r->nodes[parent];

    if (parent_node->first_child == 0U) {
        parent_node->first_child = child;
    } else {
        r->nodes[parent_node->last_child].next_sibling = child;
    }
    parent_node->last_child = child;
}

static void cp_scan_body_no_slash(CpReader *r)
{
    for (;;) {
        uint32_t l = cp_body_char_len(r, r->pos);

        if ((l == 0U) || (r->src[r->pos] == '/')) {
            return;
        }
        r->pos += l;
    }
}

static void cp_scan_body_with_slash(CpReader *r)
{
    for (;;) {
        uint32_t l = cp_body_char_len(r, r->pos);

        if (l == 0U) {
            return;
        }
        r->pos += l;
    }
}

/* COMMENT: /(;|#!)[^\n\r\u2028\u2029]*\n?/ */
static uint32_t cp_lex_comment(CpReader *r, uint32_t marker_len)
{
    uint32_t idx = cp_node_new(r, CP_NODE_COMMENT);

    if (r->oom) {
        return 0U;
    }
    r->pos += marker_len;
    while (r->pos < r->len) {
        unsigned char c = (unsigned char)r->src[r->pos];

        if (c == '\n') {
            r->pos += 1U;
            r->row += 1U;
            r->line_start = r->pos;
            break;
        }
        if (c == '\r') {
            break;
        }
        if ((c == 0xE2U) && (r->pos + 2U < r->len) &&
            ((unsigned char)r->src[r->pos + 1U] == 0x80U) &&
            (((unsigned char)r->src[r->pos + 2U] == 0xA8U) ||
             ((unsigned char)r->src[r->pos + 2U] == 0xA9U))) {
            break;
        }
        r->pos += 1U;
    }
    cp_node_finish(r, idx);
    return idx;
}

/*
 * STRING: '"' then unescaped runs ([^"\]) mixed with escapes (\ + /./,
 * which excludes \n, \r, U+2028, U+2029), closed by '"'. Raw newlines are
 * fine; an escape before a line terminator or EOF is not a string token.
 */
static bool cp_scan_string(CpReader *r)
{
    r->pos += 1U; /* opening quote */
    while (r->pos < r->len) {
        unsigned char c = (unsigned char)r->src[r->pos];

        if (c == '"') {
            r->pos += 1U;
            return true;
        }
        if (c == '\\') {
            r->pos += 1U;
            if (r->pos >= r->len) {
                return false;
            }
            c = (unsigned char)r->src[r->pos];
            if ((c == '\n') || (c == '\r')) {
                return false;
            }
            if ((c == 0xE2U) && (r->pos + 2U < r->len) &&
                ((unsigned char)r->src[r->pos + 1U] == 0x80U) &&
                (((unsigned char)r->src[r->pos + 2U] == 0xA8U) ||
                 ((unsigned char)r->src[r->pos + 2U] == 0xA9U))) {
                return false;
            }
            r->pos += 1U;
            continue;
        }
        if (c == '\n') {
            r->pos += 1U;
            r->row += 1U;
            r->line_start = r->pos;
            continue;
        }
        r->pos += 1U;
    }
    return false;
}

/*
 * NUMBER: optional sign, then the longest of HEX (0[xX]hex+N?),
 * OCTAL (0oct+N?), RADIX (digit+[rR]alnum+), RATIO (digit+/digit+),
 * DOUBLE (digit+(.digit*)?([eE][+-]?digit+)?M?) or INTEGER (digit+[MN]?).
 * Returns the token length (0 when no leading digit follows the sign).
 */
static uint32_t cp_lex_number_len(const char *s, uint32_t n)
{
    uint32_t sign = ((n > 0U) && ((s[0] == '+') || (s[0] == '-'))) ? 1U : 0U;
    uint32_t digits_end = sign;
    uint32_t best = 0U;

    while ((digits_end < n) && (s[digits_end] >= '0') && (s[digits_end] <= '9')) {
        digits_end += 1U;
    }
    if (digits_end == sign) {
        return 0U;
    }

    /* INTEGER */
    {
        uint32_t l = digits_end;

        if ((l < n) && ((s[l] == 'M') || (s[l] == 'N'))) {
            l += 1U;
        }
        if (l > best) {
            best = l;
        }
    }
    /* DOUBLE */
    {
        uint32_t l = digits_end;

        if ((l < n) && (s[l] == '.')) {
            l += 1U;
            while ((l < n) && (s[l] >= '0') && (s[l] <= '9')) {
                l += 1U;
            }
        }
        if ((l < n) && ((s[l] == 'e') || (s[l] == 'E'))) {
            uint32_t e = l + 1U;
            uint32_t exp_digits;

            if ((e < n) && ((s[e] == '+') || (s[e] == '-'))) {
                e += 1U;
            }
            exp_digits = e;
            while ((exp_digits < n) && (s[exp_digits] >= '0') && (s[exp_digits] <= '9')) {
                exp_digits += 1U;
            }
            if (exp_digits > e) {
                l = exp_digits;
            }
        }
        if ((l < n) && (s[l] == 'M')) {
            l += 1U;
        }
        if (l > best) {
            best = l;
        }
    }
    /* HEX and OCTAL both require a leading zero */
    if (s[sign] == '0') {
        if ((sign + 1U < n) && ((s[sign + 1U] == 'x') || (s[sign + 1U] == 'X'))) {
            uint32_t h = sign + 2U;

            while ((h < n) && ((((unsigned char)s[h] >= '0') && ((unsigned char)s[h] <= '9')) ||
                               (((unsigned char)s[h] >= 'a') && ((unsigned char)s[h] <= 'f')) ||
                               (((unsigned char)s[h] >= 'A') && ((unsigned char)s[h] <= 'F')))) {
                h += 1U;
            }
            if (h > sign + 2U) {
                if ((h < n) && (s[h] == 'N')) {
                    h += 1U;
                }
                if (h > best) {
                    best = h;
                }
            }
        }
        {
            uint32_t o = sign + 1U;

            while ((o < n) && (s[o] >= '0') && (s[o] <= '7')) {
                o += 1U;
            }
            if (o > sign + 1U) {
                if ((o < n) && (s[o] == 'N')) {
                    o += 1U;
                }
                if (o > best) {
                    best = o;
                }
            }
        }
    }
    /* RADIX */
    if ((digits_end < n) && ((s[digits_end] == 'r') || (s[digits_end] == 'R'))) {
        uint32_t a = digits_end + 1U;

        while ((a < n) && ((((unsigned char)s[a] >= '0') && ((unsigned char)s[a] <= '9')) ||
                           (((unsigned char)s[a] >= 'a') && ((unsigned char)s[a] <= 'z')) ||
                           (((unsigned char)s[a] >= 'A') && ((unsigned char)s[a] <= 'Z')))) {
            a += 1U;
        }
        if ((a > digits_end + 1U) && (a > best)) {
            best = a;
        }
    }
    /* RATIO */
    if ((digits_end < n) && (s[digits_end] == '/')) {
        uint32_t q = digits_end + 1U;

        while ((q < n) && (s[q] >= '0') && (s[q] <= '9')) {
            q += 1U;
        }
        if ((q > digits_end + 1U) && (q > best)) {
            best = q;
        }
    }
    return best;
}

/*
 * CHARACTER: '\' then the longest of a named char, u+4hex, o+1-3 digits or
 * any single code point (the /.|\n/ alternative: excludes \r and
 * U+2028/U+2029). Returns false when no alternative matches so the caller
 * can fall back.
 */
static bool cp_lex_char(CpReader *r)
{
    uint32_t p = r->pos + 1U;
    uint32_t remaining;
    uint32_t best = 0U;
    unsigned char c;

    if (p >= r->len) {
        return false;
    }
    remaining = r->len - p;
    c = (unsigned char)r->src[p];

    if ((remaining >= 9U) && (memcmp(r->src + p, "backspace", 9U) == 0)) {
        best = 9U;
    } else if ((remaining >= 8U) && (memcmp(r->src + p, "formfeed", 8U) == 0)) {
        best = 8U;
    } else if ((remaining >= 7U) && (memcmp(r->src + p, "newline", 7U) == 0)) {
        best = 7U;
    } else if ((remaining >= 6U) && (memcmp(r->src + p, "return", 6U) == 0)) {
        best = 6U;
    } else if ((remaining >= 5U) && (memcmp(r->src + p, "space", 5U) == 0)) {
        best = 5U;
    } else if ((remaining >= 3U) && (memcmp(r->src + p, "tab", 3U) == 0)) {
        best = 3U;
    }

    if ((c == 'u') && (remaining >= 5U)) {
        bool all_hex = true;

        for (uint32_t i = 1U; i <= 4U; i++) {
            unsigned char h = (unsigned char)r->src[p + i];

            if (!(((h >= '0') && (h <= '9')) || ((h >= 'a') && (h <= 'f')) ||
                  ((h >= 'A') && (h <= 'F')))) {
                all_hex = false;
                break;
            }
        }
        if (all_hex && (best < 5U)) {
            best = 5U;
        }
    }
    if (c == 'o') {
        uint32_t k = 0U;

        while ((k < 3U) && (1U + k < remaining) && (r->src[p + 1U + k] >= '0') &&
               (r->src[p + 1U + k] <= '9')) {
            k += 1U;
        }
        if ((k >= 1U) && (best < 1U + k)) {
            best = 1U + k;
        }
    }
    {
        uint32_t any = 0U;

        if (c == '\r') {
            any = 0U;
        } else if (c < 0x80U) {
            any = 1U;
        } else {
            uint32_t l = cp_utf8_len((const unsigned char *)r->src + p, remaining);

            if (l == 0U) {
                return false; /* invalid UTF-8: let tree-sitter decide */
            }
            if ((l == 3U) && ((unsigned char)r->src[p] == 0xE2U) &&
                ((unsigned char)r->src[p + 1U] == 0x80U) &&
                (((unsigned char)r->src[p + 2U] == 0xA8U) ||
                 ((unsigned char)r->src[p + 2U] == 0xA9U))) {
                any = 0U;
            } else {
                any = l;
            }
        }
        if (best < any) {
            best = any;
        }
    }
    if (best == 0U) {
        return false;
    }
    if ((best == 1U) && (c == '\n')) {
        r->pos = p + 1U;
        r->row += 1U;
        r->line_start = r->pos;
        return true;
    }
    r->pos = p + best;
    return true;
}

/*
 * kwd_lit: ':' or '::' marker, then either '/'+greedy body (leading-slash
 * and just-slash forms) or a head char (not ':' or '/') followed by body;
 * a '/' with a constituent after it switches to the greedy namespaced-name
 * body that may itself contain '/'.
 */
static uint32_t cp_lex_keyword(CpReader *r)
{
    uint32_t idx = cp_node_new(r, CP_NODE_KWD);

    if (r->oom) {
        return 0U;
    }
    r->pos += 1U;
    if ((r->pos < r->len) && (r->src[r->pos] == ':')) {
        r->pos += 1U;
    }
    if ((r->pos < r->len) && (r->src[r->pos] == '/')) {
        r->pos += 1U;
        cp_scan_body_with_slash(r);
    } else {
        if ((cp_body_char_len(r, r->pos) == 0U) || (r->src[r->pos] == ':')) {
            r->failed = true;
            return 0U;
        }
        cp_scan_body_no_slash(r);
        if ((r->pos < r->len) && (r->src[r->pos] == '/') &&
            (cp_body_char_len(r, r->pos + 1U) != 0U)) {
            r->pos += 1U;
            cp_scan_body_with_slash(r);
        }
    }
    cp_node_finish(r, idx);
    return idx;
}

/*
 * sym_lit / nil_lit / bool_lit. The base run (up to a structural '/') is
 * checked against nil/true/false first: those dedicated string tokens win
 * the tie against SYMBOL at equal length, become LEAF nodes and never take
 * a namespace suffix. For symbols, a '/' followed by a constituent starts
 * the greedy namespaced name (which may contain further slashes).
 */
static uint32_t cp_lex_symbol(CpReader *r)
{
    uint32_t idx = cp_node_new(r, CP_NODE_SYM);
    uint32_t token_start = r->pos;
    uint32_t base_len;

    if (r->oom) {
        return 0U;
    }
    cp_scan_body_no_slash(r);
    base_len = r->pos - token_start;
    if (base_len == 0U) {
        r->failed = true;
        return 0U;
    }

    if (((base_len == 3U) && (memcmp(r->src + token_start, "nil", 3U) == 0)) ||
        ((base_len == 4U) && (memcmp(r->src + token_start, "true", 4U) == 0)) ||
        ((base_len == 5U) && (memcmp(r->src + token_start, "false", 5U) == 0))) {
        r->nodes[idx].kind = CP_NODE_LEAF;
        cp_node_finish(r, idx);
        return idx;
    }

    r->nodes[idx].value_start = token_start;
    r->nodes[idx].name_start = token_start;
    if ((r->pos < r->len) && (r->src[r->pos] == '/') &&
        (cp_body_char_len(r, r->pos + 1U) != 0U)) {
        r->pos += 1U;
        r->nodes[idx].name_start = r->pos;
        cp_scan_body_with_slash(r);
    }
    cp_node_finish(r, idx);
    return idx;
}

static uint32_t cp_parse_form(CpReader *r, uint32_t depth);
static uint32_t cp_parse_wrapper(CpReader *r, uint32_t depth, CpNodeKind kind,
                                 uint32_t marker_len);

/*
 * Consume whitespace plus any comment / discard-expression gap nodes,
 * appending the gap nodes as children of parent (matching how the grammar
 * places them inside containers, wrappers and dis_expr/meta forms).
 */
static void cp_parse_gaps_into(CpReader *r, uint32_t parent, uint32_t depth)
{
    for (;;) {
        unsigned char c;
        uint32_t gap;

        cp_skip_ws(r);
        if ((r->pos >= r->len) || r->failed || r->oom) {
            return;
        }
        c = (unsigned char)r->src[r->pos];
        if (c == ';') {
            gap = cp_lex_comment(r, 1U);
        } else if ((c == '#') && (r->pos + 1U < r->len) && (r->src[r->pos + 1U] == '!')) {
            gap = cp_lex_comment(r, 2U);
        } else if ((c == '#') && (r->pos + 1U < r->len) && (r->src[r->pos + 1U] == '_')) {
            gap = cp_parse_wrapper(r, depth, CP_NODE_DIS, 2U);
        } else {
            return;
        }
        if (r->failed || r->oom) {
            return;
        }
        cp_append_child(r, parent, gap);
    }
}

/*
 * Shared marker + gaps + value shape used by dis_expr, meta forms, quotes,
 * unquotes, deref, var-quote and ##. The node starts at the marker and
 * ends with its value form.
 */
static uint32_t cp_parse_wrapper(CpReader *r, uint32_t depth, CpNodeKind kind,
                                 uint32_t marker_len)
{
    uint32_t idx = cp_node_new(r, kind);
    uint32_t value;

    if (r->oom) {
        return 0U;
    }
    r->pos += marker_len;
    cp_parse_gaps_into(r, idx, depth);
    if (r->failed || r->oom) {
        return 0U;
    }
    value = cp_parse_form(r, depth + 1U);
    if (r->failed || r->oom) {
        return 0U;
    }
    cp_append_child(r, idx, value);
    cp_node_finish(r, idx);
    return idx;
}

static bool cp_parse_children_until(CpReader *r, uint32_t idx, uint32_t depth, char closer)
{
    for (;;) {
        unsigned char c;
        uint32_t child;

        cp_parse_gaps_into(r, idx, depth);
        if (r->failed || r->oom) {
            return false;
        }
        if (r->pos >= r->len) {
            r->failed = true; /* unbalanced: let tree-sitter error-recover */
            return false;
        }
        c = (unsigned char)r->src[r->pos];
        if (c == (unsigned char)closer) {
            r->pos += 1U;
            cp_node_finish(r, idx);
            return true;
        }
        if ((c == ')') || (c == ']') || (c == '}')) {
            r->failed = true; /* mismatched closer */
            return false;
        }
        child = cp_parse_form(r, depth + 1U);
        if (r->failed || r->oom) {
            return false;
        }
        cp_append_child(r, idx, child);
    }
}

static uint32_t cp_parse_container(CpReader *r, uint32_t depth, CpNodeKind kind,
                                   uint32_t open_len, char closer)
{
    uint32_t idx = cp_node_new(r, kind);

    if (r->oom) {
        return 0U;
    }
    r->pos += open_len;
    if (!cp_parse_children_until(r, idx, depth, closer)) {
        return 0U;
    }
    return idx;
}

/*
 * read_cond_lit / splicing_read_cond_lit: '#?' or '#?@', then plain
 * whitespace only (the grammar allows no comments or discards here), then
 * a bare list whose children inline directly into this node.
 */
static uint32_t cp_parse_read_cond(CpReader *r, uint32_t depth)
{
    uint32_t idx = cp_node_new(r, CP_NODE_WRAP);

    if (r->oom) {
        return 0U;
    }
    r->pos += 2U;
    if ((r->pos < r->len) && (r->src[r->pos] == '@')) {
        r->pos += 1U;
    }
    cp_skip_ws(r);
    if ((r->pos >= r->len) || (r->src[r->pos] != '(')) {
        r->failed = true;
        return 0U;
    }
    r->pos += 1U;
    if (!cp_parse_children_until(r, idx, depth, ')')) {
        return 0U;
    }
    return idx;
}

/*
 * ns_map_lit: '#' + prefix + gaps + map. The prefix is either a keyword
 * (':foo' / '::foo' / '::/x' ... -- indexed like any other keyword) or the
 * bare auto-resolve marker '::', which produces no node.
 */
static uint32_t cp_parse_ns_map(CpReader *r, uint32_t depth)
{
    uint32_t idx = cp_node_new(r, CP_NODE_WRAP);
    bool double_colon;
    uint32_t after;

    if (r->oom) {
        return 0U;
    }
    r->pos += 1U; /* '#' */
    double_colon = (r->pos + 1U < r->len) && (r->src[r->pos + 1U] == ':');
    after = r->pos + (double_colon ? 2U : 1U);
    if ((after < r->len) && ((r->src[after] == '/') ||
                             ((cp_body_char_len(r, after) != 0U) && (r->src[after] != ':')))) {
        uint32_t kwd = cp_lex_keyword(r);

        if (r->failed || r->oom) {
            return 0U;
        }
        cp_append_child(r, idx, kwd);
    } else if (double_colon) {
        r->pos += 2U; /* auto_res_mark: no node */
    } else {
        r->failed = true;
        return 0U;
    }
    cp_parse_gaps_into(r, idx, depth);
    if (r->failed || r->oom) {
        return 0U;
    }
    if ((r->pos >= r->len) || (r->src[r->pos] != '{')) {
        r->failed = true;
        return 0U;
    }
    r->pos += 1U;
    if (!cp_parse_children_until(r, idx, depth, '}')) {
        return 0U;
    }
    return idx;
}

/* tagged_or_ctor_lit: '#' + gaps + symbol tag (a real reference) + gaps + form. */
static uint32_t cp_parse_tagged(CpReader *r, uint32_t depth)
{
    uint32_t idx = cp_node_new(r, CP_NODE_WRAP);
    uint32_t tag;
    uint32_t value;

    if (r->oom) {
        return 0U;
    }
    r->pos += 1U; /* '#' */
    cp_parse_gaps_into(r, idx, depth);
    if (r->failed || r->oom) {
        return 0U;
    }
    tag = cp_parse_form(r, depth + 1U);
    if (r->failed || r->oom) {
        return 0U;
    }
    if (r->nodes[tag].kind != CP_NODE_SYM) {
        r->failed = true;
        return 0U;
    }
    cp_append_child(r, idx, tag);
    cp_parse_gaps_into(r, idx, depth);
    if (r->failed || r->oom) {
        return 0U;
    }
    value = cp_parse_form(r, depth + 1U);
    if (r->failed || r->oom) {
        return 0U;
    }
    cp_append_child(r, idx, value);
    cp_node_finish(r, idx);
    return idx;
}

/*
 * Parse one form. Leading metadata forms (plus the gaps trailing each of
 * them) nest inside the annotated node, which also inherits their start
 * position -- exactly how the grammar shapes sym_lit/list_lit/....
 */
static uint32_t cp_parse_form(CpReader *r, uint32_t depth)
{
    uint32_t pending[CP_MAX_PENDING];
    uint32_t pending_count = 0U;
    uint32_t node = 0U;

    if (depth > CP_MAX_DEPTH) {
        r->failed = true;
        return 0U;
    }

    for (;;) {
        bool old_meta;
        unsigned char c;
        uint32_t meta;

        cp_skip_ws(r);
        if (r->pos >= r->len) {
            r->failed = true; /* metadata (or nothing) with no following form */
            return 0U;
        }
        c = (unsigned char)r->src[r->pos];
        if (c == '^') {
            old_meta = false;
        } else if ((c == '#') && (r->pos + 1U < r->len) && (r->src[r->pos + 1U] == '^')) {
            old_meta = true;
        } else {
            break;
        }
        meta = cp_parse_wrapper(r, depth, old_meta ? CP_NODE_OLD_META : CP_NODE_META,
                                old_meta ? 2U : 1U);
        if (r->failed || r->oom) {
            return 0U;
        }
        if (pending_count >= CP_MAX_PENDING) {
            r->failed = true;
            return 0U;
        }
        pending[pending_count++] = meta;
        for (;;) {
            uint32_t gap;

            cp_skip_ws(r);
            if (r->pos >= r->len) {
                break;
            }
            c = (unsigned char)r->src[r->pos];
            if (c == ';') {
                gap = cp_lex_comment(r, 1U);
            } else if ((c == '#') && (r->pos + 1U < r->len) && (r->src[r->pos + 1U] == '!')) {
                gap = cp_lex_comment(r, 2U);
            } else if ((c == '#') && (r->pos + 1U < r->len) && (r->src[r->pos + 1U] == '_')) {
                gap = cp_parse_wrapper(r, depth, CP_NODE_DIS, 2U);
            } else {
                break;
            }
            if (r->failed || r->oom) {
                return 0U;
            }
            if (pending_count >= CP_MAX_PENDING) {
                r->failed = true;
                return 0U;
            }
            pending[pending_count++] = gap;
        }
    }

    {
        unsigned char c = (unsigned char)r->src[r->pos];

        switch (c) {
        case '(':
            node = cp_parse_container(r, depth, CP_NODE_LIST, 1U, ')');
            break;
        case '[':
            node = cp_parse_container(r, depth, CP_NODE_VEC, 1U, ']');
            break;
        case '{':
            node = cp_parse_container(r, depth, CP_NODE_WRAP, 1U, '}');
            break;
        case '"':
            node = cp_node_new(r, CP_NODE_STR);
            if (r->oom) {
                return 0U;
            }
            if (!cp_scan_string(r)) {
                r->failed = true;
                return 0U;
            }
            cp_node_finish(r, node);
            break;
        case '\\':
            node = cp_node_new(r, CP_NODE_LEAF);
            if (r->oom) {
                return 0U;
            }
            if (!cp_lex_char(r)) {
                r->failed = true;
                return 0U;
            }
            cp_node_finish(r, node);
            break;
        case ':':
            node = cp_lex_keyword(r);
            break;
        case '/':
            /* lone '/' (division): its own token; never absorbs what follows */
            node = cp_node_new(r, CP_NODE_SYM);
            if (r->oom) {
                return 0U;
            }
            r->nodes[node].value_start = r->pos;
            r->nodes[node].name_start = r->pos;
            r->pos += 1U;
            cp_node_finish(r, node);
            break;
        case '@':
        case '\'':
        case '`':
            node = cp_parse_wrapper(r, depth, CP_NODE_WRAP, 1U);
            break;
        case '~':
            node = cp_parse_wrapper(
                r, depth, CP_NODE_WRAP,
                ((r->pos + 1U < r->len) && (r->src[r->pos + 1U] == '@')) ? 2U : 1U);
            break;
        case '#': {
            unsigned char d;

            if (r->pos + 1U >= r->len) {
                r->failed = true;
                return 0U;
            }
            d = (unsigned char)r->src[r->pos + 1U];
            switch (d) {
            case '{':
                node = cp_parse_container(r, depth, CP_NODE_WRAP, 2U, '}');
                break;
            case '(':
                node = cp_parse_container(r, depth, CP_NODE_WRAP, 2U, ')');
                break;
            case '"':
                /* regex_lit: '#' + string token, one leaf node */
                node = cp_node_new(r, CP_NODE_LEAF);
                if (r->oom) {
                    return 0U;
                }
                r->pos += 1U;
                if (!cp_scan_string(r)) {
                    r->failed = true;
                    return 0U;
                }
                cp_node_finish(r, node);
                break;
            case '?':
                node = cp_parse_read_cond(r, depth);
                break;
            case '\'':
                node = cp_parse_wrapper(r, depth, CP_NODE_WRAP, 2U);
                break;
            case '#':
                node = cp_parse_wrapper(r, depth, CP_NODE_SYMVAL, 2U);
                break;
            case ':':
                node = cp_parse_ns_map(r, depth);
                break;
            case '=': /* evaling_lit: rare; not modeled */
            case '_': /* gap: consumed by callers, never a form */
            case '!': /* comment: consumed by callers, never a form */
                r->failed = true;
                return 0U;
            default:
                node = cp_parse_tagged(r, depth);
                break;
            }
            break;
        }
        case ')':
        case ']':
        case '}':
        case ';':
            r->failed = true;
            return 0U;
        default:
            if ((c >= '0') && (c <= '9')) {
                uint32_t nlen = cp_lex_number_len(r->src + r->pos, r->len - r->pos);

                node = cp_node_new(r, CP_NODE_LEAF);
                if (r->oom) {
                    return 0U;
                }
                r->pos += nlen; /* >= 1 given the leading digit */
                cp_node_finish(r, node);
                break;
            }
            if (((c == '+') || (c == '-')) && (r->pos + 1U < r->len) &&
                (r->src[r->pos + 1U] >= '0') && (r->src[r->pos + 1U] <= '9')) {
                /* Longest-match tie-break between NUMBER and SYMBOL: +5x is a
                 * symbol (3 beats 2), +5 is a number (prec 10 wins the tie). */
                uint32_t nlen = cp_lex_number_len(r->src + r->pos, r->len - r->pos);
                uint32_t p = r->pos;

                for (;;) {
                    uint32_t bl = cp_body_char_len(r, p);

                    if ((bl == 0U) || (r->src[p] == '/')) {
                        break;
                    }
                    p += bl;
                }
                if ((p - r->pos) > nlen) {
                    node = cp_lex_symbol(r);
                } else {
                    node = cp_node_new(r, CP_NODE_LEAF);
                    if (r->oom) {
                        return 0U;
                    }
                    r->pos += nlen;
                    cp_node_finish(r, node);
                }
                break;
            }
            node = cp_lex_symbol(r);
            break;
        }
    }

    if (r->failed || r->oom) {
        return 0U;
    }

    if (pending_count > 0U) {
        CpNodeKind kind = r->nodes[node].kind;

        if ((kind != CP_NODE_SYM) && (kind != CP_NODE_LIST) && (kind != CP_NODE_VEC) &&
            (kind != CP_NODE_WRAP)) {
            r->failed = true; /* metadata on a non-annotatable form */
            return 0U;
        }
        for (uint32_t i = 0U; i < pending_count; i++) {
            r->nodes[pending[i]].next_sibling =
                (i + 1U < pending_count) ? pending[i + 1U] : r->nodes[node].first_child;
        }
        if (r->nodes[node].first_child == 0U) {
            r->nodes[node].last_child = pending[pending_count - 1U];
        }
        r->nodes[node].first_child = pending[0];
        r->nodes[node].start_byte = r->nodes[pending[0]].start_byte;
        r->nodes[node].start_row = r->nodes[pending[0]].start_row;
        r->nodes[node].start_col = r->nodes[pending[0]].start_col;
    }

    return node;
}

static bool cp_read_source(CpReader *r)
{
    uint32_t root = cp_node_new(r, CP_NODE_ROOT);

    if (r->oom || (root != 0U)) {
        return false;
    }
    for (;;) {
        cp_parse_gaps_into(r, 0U, 0U);
        if (r->failed || r->oom) {
            return false;
        }
        if (r->pos >= r->len) {
            break;
        }
        {
            unsigned char c = (unsigned char)r->src[r->pos];

            if ((c == ')') || (c == ']') || (c == '}')) {
                r->failed = true;
                return false;
            }
        }
        {
            uint32_t child = cp_parse_form(r, 1U);

            if (r->failed || r->oom) {
                return false;
            }
            cp_append_child(r, 0U, child);
        }
    }
    cp_node_finish(r, 0U);
    return true;
}

/* --- extraction walk over CpNode trees (port of the tree-sitter walk) --- */

static bool cp_is_value_kind(CpNodeKind kind)
{
    return (kind != CP_NODE_COMMENT) && (kind != CP_NODE_DIS) && (kind != CP_NODE_META) &&
           (kind != CP_NODE_OLD_META);
}

static uint32_t cp_first_value(const CpNode *nodes, uint32_t parent)
{
    uint32_t child = nodes[parent].first_child;

    while ((child != 0U) && !cp_is_value_kind(nodes[child].kind)) {
        child = nodes[child].next_sibling;
    }
    return child;
}

static uint32_t cp_next_value(const CpNode *nodes, uint32_t idx)
{
    uint32_t child = nodes[idx].next_sibling;

    while ((child != 0U) && !cp_is_value_kind(nodes[child].kind)) {
        child = nodes[child].next_sibling;
    }
    return child;
}

static SourceSlice cp_span_slice(const char *source, const CpNode *node)
{
    SourceSlice slice;

    slice.data = source + node->start_byte;
    slice.len = (size_t)(node->end_byte - node->start_byte);
    slice.start_byte = node->start_byte;
    slice.end_byte = node->end_byte;
    return slice;
}

/* Metadata-free symbol text (equivalent of symbol_value_slice). */
static SourceSlice cp_value_slice(const char *source, const CpNode *node)
{
    SourceSlice slice;

    slice.data = source + node->value_start;
    slice.len = (size_t)(node->end_byte - node->value_start);
    slice.start_byte = node->value_start;
    slice.end_byte = node->end_byte;
    return slice;
}

/* Grammar "name" field text (equivalent of symbol_base_slice). */
static SourceSlice cp_base_slice(const char *source, const CpNode *node)
{
    SourceSlice slice;

    slice.data = source + node->name_start;
    slice.len = (size_t)(node->end_byte - node->name_start);
    slice.start_byte = node->name_start;
    slice.end_byte = node->end_byte;
    return slice;
}

static bool cp_add_reference(const char *source, const CpNode *node,
                             CodeLensClojureFile *file)
{
    SourceSlice slice = cp_value_slice(source, node);
    char *symbol = copy_source_slice(slice);
    CodeLensReference *reference;

    if (symbol == nullptr) {
        return false;
    }
    if (!reserve_references(file)) {
        return false;
    }
    reference = &file->references[file->reference_count++];
    reference->symbol = symbol;
    reference->symbol_len = slice.len;
    reference->line = node->start_row + 1U;
    reference->column = node->start_col + 1U;
    reference->start_byte = slice.start_byte;
    reference->end_byte = slice.end_byte;
    return true;
}

static bool cp_add_keyword(const char *source, const CpNode *node, CodeLensClojureFile *file)
{
    CodeLensKeyword *keyword;
    SourceSlice slice = cp_span_slice(source, node);

    if (!reserve_keywords(file)) {
        return false;
    }
    keyword = &file->keywords[file->keyword_count];
    (void)memset(keyword, 0, sizeof(*keyword));
    keyword->keyword = copy_source_slice(slice);
    keyword->keyword_len = slice.len;
    keyword->line = node->start_row + 1U;
    keyword->column = node->start_col + 1U;
    if (!init_keyword_fields(keyword, file)) {
        (void)memset(keyword, 0, sizeof(*keyword));
        return false;
    }
    file->keyword_count++;
    return true;
}

static bool cp_add_definition(const char *source,
                              const CpNode *nodes,
                              uint32_t list_idx,
                              uint32_t name_idx,
                              const char *kind,
                              CodeLensClojureFile *file)
{
    const CpNode *list_node = &nodes[list_idx];
    CodeLensSymbol *symbol;

    if (!reserve_symbols(file)) {
        return false;
    }

    symbol = &file->symbols[file->symbol_count];
    (void)memset(symbol, 0, sizeof(*symbol));
    symbol->kind = copy_cstr(kind);
    symbol->name = copy_source_slice(cp_value_slice(source, &nodes[name_idx]));
    symbol->namespace_name =
        file->namespace_name == nullptr ? copy_cstr("") : copy_cstr(file->namespace_name);
    symbol->source = copy_source_slice(cp_span_slice(source, list_node));
    symbol->start_line = list_node->start_row + 1U;
    symbol->end_line = list_node->end_row + 1U;

    for (uint32_t i = cp_next_value(nodes, name_idx); i != 0U; i = cp_next_value(nodes, i)) {
        if (nodes[i].kind == CP_NODE_STR) {
            SourceSlice doc = cp_span_slice(source, &nodes[i]);

            if ((doc.len >= 2U) && (doc.data[0] == '"') && (doc.data[doc.len - 1U] == '"')) {
                doc.data++;
                doc.len -= 2U;
            }
            symbol->doc = copy_source_slice(doc);
            break;
        }
        if ((nodes[i].kind == CP_NODE_VEC) || (nodes[i].kind == CP_NODE_LIST)) {
            break;
        }
    }

    if ((symbol->kind == nullptr) || (symbol->name == nullptr) ||
        (symbol->namespace_name == nullptr) || (symbol->source == nullptr)) {
        (void)memset(symbol, 0, sizeof(*symbol));
        return false;
    }

    file->symbol_count++;
    return true;
}

static bool cp_extract_definition_form(const char *source,
                                       const CpNode *nodes,
                                       uint32_t list_idx,
                                       const char *kind,
                                       CodeLensClojureFile *file)
{
    uint32_t head = cp_first_value(nodes, list_idx);

    for (uint32_t i = cp_next_value(nodes, head); i != 0U; i = cp_next_value(nodes, i)) {
        if (nodes[i].kind == CP_NODE_SYM) {
            return cp_add_definition(source, nodes, list_idx, i, kind, file);
        }
    }
    return true;
}

/* Read-cond nodes are the only CP_NODE_WRAP whose span begins "#?" (the
 * dispatcher sends every "#?" to cp_parse_read_cond). Children inline
 * directly: platform keywords alternating with forms. */
static bool cp_is_read_cond(const char *source, const CpNode *node)
{
    SourceSlice span = cp_span_slice(source, node);

    return (node->kind == CP_NODE_WRAP) && (span.len >= 2U) &&
           (span.data[0] == '#') && (span.data[1] == '?');
}

static bool cp_is_splicing_read_cond(const char *source, const CpNode *node)
{
    SourceSlice span = cp_span_slice(source, node);

    return cp_is_read_cond(source, node) && (span.len >= 3U) && (span.data[2] == '@');
}

/* Mirror of extract_require_spec for the custom reader. */
static bool cp_extract_require_spec(const char *source,
                                    const CpNode *nodes,
                                    uint32_t spec,
                                    CodeLensClojureFile *file)
{
    uint32_t ns_idx;
    char *namespace_name;
    char *alias = nullptr;

    if (cp_is_read_cond(source, &nodes[spec])) {
        bool splicing = cp_is_splicing_read_cond(source, &nodes[spec]);

        for (uint32_t child = cp_first_value(nodes, spec); child != 0U;
             child = cp_next_value(nodes, child)) {
            if (splicing && (nodes[child].kind == CP_NODE_VEC)) {
                for (uint32_t inner = cp_first_value(nodes, child); inner != 0U;
                     inner = cp_next_value(nodes, inner)) {
                    if (!cp_extract_require_spec(source, nodes, inner, file)) {
                        return false;
                    }
                }
            } else if (!cp_extract_require_spec(source, nodes, child, file)) {
                return false;
            }
        }
        return true;
    }

    if (nodes[spec].kind != CP_NODE_VEC) {
        return true;
    }
    ns_idx = cp_first_value(nodes, spec);
    if ((ns_idx == 0U) || (nodes[ns_idx].kind != CP_NODE_SYM)) {
        return true;
    }
    namespace_name = copy_source_slice(cp_value_slice(source, &nodes[ns_idx]));
    if (namespace_name == nullptr) {
        return true;
    }

    for (uint32_t j = cp_next_value(nodes, ns_idx); j != 0U; j = cp_next_value(nodes, j)) {
        uint32_t next = cp_next_value(nodes, j);

        if (next == 0U) {
            break;
        }
        if ((source_slice_equals(cp_span_slice(source, &nodes[j]), ":as",
                                 sizeof(":as") - 1U) ||
             source_slice_equals(cp_span_slice(source, &nodes[j]), ":as-alias",
                                 sizeof(":as-alias") - 1U)) &&
            (nodes[next].kind == CP_NODE_SYM)) {
            alias = copy_source_slice(cp_value_slice(source, &nodes[next]));
            break;
        }
    }

    /* Mirror of the tree-sitter :refer scan. */
    for (uint32_t j = cp_next_value(nodes, ns_idx); j != 0U; j = cp_next_value(nodes, j)) {
        uint32_t next = cp_next_value(nodes, j);

        if (next == 0U) {
            break;
        }
        if (!source_slice_equals(cp_span_slice(source, &nodes[j]), ":refer",
                                 sizeof(":refer") - 1U)) {
            continue;
        }
        if (nodes[next].kind == CP_NODE_VEC) {
            for (uint32_t sym = cp_first_value(nodes, next); sym != 0U;
                 sym = cp_next_value(nodes, sym)) {
                if ((nodes[sym].kind == CP_NODE_SYM) &&
                    !add_referred(file,
                                  namespace_name,
                                  copy_source_slice(cp_value_slice(source, &nodes[sym])))) {
                    return false;
                }
            }
        } else if (source_slice_equals(cp_span_slice(source, &nodes[next]), ":all",
                                       sizeof(":all") - 1U)) {
            if (!add_referred(file, namespace_name, copy_cstr(":all"))) {
                return false;
            }
        }
        break;
    }

    if (alias == nullptr) {
        alias = copy_cstr("");
    }
    if (alias == nullptr) {
        return false;
    }
    return add_alias(file, namespace_name, alias);
}

static void cp_extract_require_aliases(const char *source,
                                       const CpNode *nodes,
                                       uint32_t require_idx,
                                       CodeLensClojureFile *file)
{
    uint32_t head = cp_first_value(nodes, require_idx);

    for (uint32_t spec = cp_next_value(nodes, head); spec != 0U;
         spec = cp_next_value(nodes, spec)) {
        if (!cp_extract_require_spec(source, nodes, spec, file)) {
            return;
        }
    }
}

/* Mirror of extract_use_spec for the custom reader. */
static bool cp_extract_use_spec(const char *source,
                                const CpNode *nodes,
                                uint32_t spec,
                                CodeLensClojureFile *file)
{
    if (cp_is_read_cond(source, &nodes[spec])) {
        bool splicing = cp_is_splicing_read_cond(source, &nodes[spec]);

        for (uint32_t child = cp_first_value(nodes, spec); child != 0U;
             child = cp_next_value(nodes, child)) {
            if (splicing && (nodes[child].kind == CP_NODE_VEC)) {
                for (uint32_t inner = cp_first_value(nodes, child); inner != 0U;
                     inner = cp_next_value(nodes, inner)) {
                    if (!cp_extract_use_spec(source, nodes, inner, file)) {
                        return false;
                    }
                }
            } else if (!cp_extract_use_spec(source, nodes, child, file)) {
                return false;
            }
        }
        return true;
    }

    if (nodes[spec].kind == CP_NODE_SYM) {
        char *namespace_name = copy_source_slice(cp_value_slice(source, &nodes[spec]));

        if (namespace_name == nullptr) {
            return true;
        }
        return add_referred(file, namespace_name, copy_cstr(":all"));
    }
    if (nodes[spec].kind == CP_NODE_VEC) {
        uint32_t ns_idx = cp_first_value(nodes, spec);

        if ((ns_idx != 0U) && (nodes[ns_idx].kind == CP_NODE_SYM)) {
            char *namespace_name = copy_source_slice(cp_value_slice(source, &nodes[ns_idx]));

            if (namespace_name == nullptr) {
                return true;
            }
            return add_referred(file, namespace_name, copy_cstr(":all"));
        }
    }
    return true;
}

static void cp_extract_use_referred(const char *source,
                                    const CpNode *nodes,
                                    uint32_t use_idx,
                                    CodeLensClojureFile *file)
{
    uint32_t head = cp_first_value(nodes, use_idx);

    for (uint32_t spec = cp_next_value(nodes, head); spec != 0U;
         spec = cp_next_value(nodes, spec)) {
        if (!cp_extract_use_spec(source, nodes, spec, file)) {
            return;
        }
    }
}

/* Mirror of extract_ns_clause for the custom reader. */
static void cp_extract_ns_clause(const char *source,
                                 const CpNode *nodes,
                                 uint32_t clause,
                                 CodeLensClojureFile *file)
{
    uint32_t req;

    if (cp_is_read_cond(source, &nodes[clause])) {
        bool splicing = cp_is_splicing_read_cond(source, &nodes[clause]);

        for (uint32_t child = cp_first_value(nodes, clause); child != 0U;
             child = cp_next_value(nodes, child)) {
            if (splicing && (nodes[child].kind == CP_NODE_VEC)) {
                for (uint32_t inner = cp_first_value(nodes, child); inner != 0U;
                     inner = cp_next_value(nodes, inner)) {
                    cp_extract_ns_clause(source, nodes, inner, file);
                }
            } else {
                cp_extract_ns_clause(source, nodes, child, file);
            }
        }
        return;
    }

    if (nodes[clause].kind != CP_NODE_LIST) {
        return;
    }
    req = cp_first_value(nodes, clause);
    if (req == 0U) {
        return;
    }
    if (source_slice_equals(cp_span_slice(source, &nodes[req]), ":require",
                            sizeof(":require") - 1U)) {
        cp_extract_require_aliases(source, nodes, clause, file);
    } else if (source_slice_equals(cp_span_slice(source, &nodes[req]), ":use",
                                   sizeof(":use") - 1U)) {
        cp_extract_use_referred(source, nodes, clause, file);
    }
}

static void cp_extract_ns_form(const char *source,
                               const CpNode *nodes,
                               uint32_t list_idx,
                               CodeLensClojureFile *file)
{
    uint32_t head = cp_first_value(nodes, list_idx);
    uint32_t name = cp_next_value(nodes, head);

    if (name == 0U) {
        return;
    }
    if (nodes[name].kind == CP_NODE_SYM) {
        char *namespace_name = copy_source_slice(cp_value_slice(source, &nodes[name]));

        if (namespace_name != nullptr) {
            file->namespace_name = namespace_name;
        }
    }

    for (uint32_t clause = cp_next_value(nodes, name); clause != 0U;
         clause = cp_next_value(nodes, clause)) {
        cp_extract_ns_clause(source, nodes, clause, file);
    }
}

static bool cp_walk(const char *source,
                    const CpNode *nodes,
                    uint32_t idx,
                    CodeLensClojureFile *file)
{
    const CpNode *node = &nodes[idx];

    if (node->kind == CP_NODE_SYM) {
        if (!cp_add_reference(source, node, file)) {
            return false;
        }
    }

    if (node->kind == CP_NODE_KWD) {
        if (!cp_add_keyword(source, node, file)) {
            return false;
        }
    }

    if (node->kind == CP_NODE_LIST) {
        uint32_t head = cp_first_value(nodes, idx);

        if ((head != 0U) && (nodes[head].kind == CP_NODE_SYM)) {
            SourceSlice base = cp_base_slice(source, &nodes[head]);
            const char *kind;

            if (source_slice_equals(base, "ns", sizeof("ns") - 1U)) {
                cp_extract_ns_form(source, nodes, idx, file);
            } else if (is_definition_form_base(base, &kind) &&
                       !cp_extract_definition_form(source, nodes, idx, kind, file)) {
                return false;
            }
        }
    }

    for (uint32_t child = node->first_child; child != 0U; child = nodes[child].next_sibling) {
        if (!cp_walk(source, nodes, child, file)) {
            return false;
        }
    }

    return true;
}

/*
 * Parse with the custom reader. Returns 0 on success, 1 when the reader
 * declines the file (caller should fall back to tree-sitter; out_file is
 * untouched in that case) and -1 on hard failure (allocation).
 */
static int cp_parse_clojure(CodeLensClojureParser *parser,
                            const char *source,
                            size_t source_len,
                            CodeLensClojureFile *out_file)
{
    CpReader reader;
    bool ok;

    reader.src = source;
    reader.len = (uint32_t)source_len;
    reader.pos = 0U;
    reader.row = 0U;
    reader.line_start = 0U;
    reader.nodes = parser->nodes;
    reader.node_count = 0U;
    reader.node_capacity = parser->node_capacity;
    reader.failed = false;
    reader.oom = false;

    ok = cp_read_source(&reader);
    parser->nodes = reader.nodes;
    parser->node_capacity = reader.node_capacity;
    if (reader.oom) {
        return -1;
    }
    if (!ok || reader.failed) {
        return 1;
    }
    if (!cp_walk(source, reader.nodes, 0U, out_file)) {
        return -1;
    }
    return 0;
}

/* Files the custom reader declined, forcing a tree-sitter fallback parse.
 * Process-wide; reported by CODE_LENS_PROFILE=1. */
static atomic_uint_fast64_t clojure_reader_fallbacks;

uint64_t code_lens_clojure_reader_fallbacks(void)
{
    return (uint64_t)atomic_load_explicit(&clojure_reader_fallbacks, memory_order_relaxed);
}

static bool walk_tree_cursor(const char *source, TSTreeCursor *cursor, CodeLensClojureFile *file)
{
    TSNode node = ts_tree_cursor_current_node(cursor);
    TSSymbol node_symbol = ts_node_symbol(node);

    if (node_symbol == clojure_sym_lit) {
        if (!add_reference(source, node, file)) {
            return false;
        }
    }

    if (node_symbol == clojure_kwd_lit) {
        if (!add_keyword(source, node, file)) {
            return false;
        }
    }

    if (node_symbol == clojure_list_lit) {
        TSNode head;
        if (first_value_child(node, &head) && is_sym_lit(head)) {
            SourceSlice base;
            const char *kind;

            if (!symbol_base_slice(source, head, &base)) {
                return false;
            }
            if (source_slice_equals(base, "ns", sizeof("ns") - 1U)) {
                extract_ns_form(source, node, file);
            } else if (is_definition_form_base(base, &kind) &&
                       !extract_definition_form(source, node, kind, file)) {
                return false;
            }
        }
    }

    if (ts_tree_cursor_goto_first_child(cursor)) {
        do {
            TSNode child = ts_tree_cursor_current_node(cursor);
            if (ts_node_is_named(child) && !walk_tree_cursor(source, cursor, file)) {
                (void)ts_tree_cursor_goto_parent(cursor);
                return false;
            }
        } while (ts_tree_cursor_goto_next_sibling(cursor));
        (void)ts_tree_cursor_goto_parent(cursor);
    }

    return true;
}

CodeLensClojureParser *code_lens_clojure_parser_new(void)
{
    CodeLensClojureParser *parser = code_lens_alloc(sizeof(*parser));

    ensure_clojure_ids();
    if (parser == nullptr) {
        return nullptr;
    }

    parser->parser = ts_parser_new();
    if (parser->parser == nullptr) {
        return nullptr;
    }

    if (!ts_parser_set_language(parser->parser, tree_sitter_clojure())) {
        ts_parser_delete(parser->parser);
        return nullptr;
    }

    parser->nodes = nullptr;
    parser->node_capacity = 0U;
    parser->force_treesitter = false;
    {
        const char *backend = getenv("CODE_LENS_PARSER");

        if ((backend != nullptr) && (strcmp(backend, "treesitter") == 0)) {
            parser->force_treesitter = true;
        }
    }

    return parser;
}

void code_lens_clojure_parser_delete(CodeLensClojureParser *parser)
{
    if (parser == nullptr) {
        return;
    }
    free(parser->nodes);
    ts_parser_delete(parser->parser);
}

/* Post-parse pass: splits each reference symbol on its last '/' into base
 * name and qualifier, then resolves the qualifier through the file's require
 * aliases (complete by now). Unqualified symbols (and degenerate spellings
 * such as '/' or a trailing '/') keep the whole symbol as base with an empty
 * target namespace. Qualifiers that match no alias are stored verbatim,
 * which covers fully-qualified references. Runs on the parse workers so the
 * single-threaded emit path consumes precomputed values. All outputs point
 * into memory that outlives the parsed file. */
static void resolve_reference_targets(CodeLensClojureFile *file)
{
    for (size_t r = 0U; r < file->reference_count; r++) {
        CodeLensReference *reference = &file->references[r];
        size_t qualifier_len = 0U;
        bool qualified = false;
        const CodeLensNamespaceAlias *alias;

        for (size_t i = reference->symbol_len; i > 0U; i--) {
            if (reference->symbol[i - 1U] == '/') {
                qualifier_len = i - 1U;
                qualified = true;
                break;
            }
        }

        if (!qualified || (qualifier_len == 0U) ||
            (qualifier_len + 1U >= reference->symbol_len)) {
            reference->base = reference->symbol;
            reference->base_len = reference->symbol_len;
            reference->target_namespace = "";
            reference->target_namespace_len = 0U;
        } else {
            reference->base = reference->symbol + qualifier_len + 1U;
            reference->base_len = reference->symbol_len - qualifier_len - 1U;

            alias = find_alias(file, reference->symbol, qualifier_len);
            if (alias != nullptr) {
                reference->target_namespace = alias->namespace_name;
                reference->target_namespace_len = strlen(alias->namespace_name);
            } else {
                reference->target_namespace = reference->symbol;
                reference->target_namespace_len = qualifier_len;
            }
        }

        reference->symbol_hash = term_hash(reference->symbol, reference->symbol_len);
        reference->base_hash = term_hash(reference->base, reference->base_len);
        reference->target_namespace_hash =
            term_hash(reference->target_namespace, reference->target_namespace_len);
    }
}

int code_lens_parse_clojure_source_with_parser(CodeLensClojureParser *parser,
                                                const char *file_path,
                                                const char *source,
                                                size_t source_len,
                                                CodeLensClojureFile *out_file)
{
    TSTree *tree;
    bool ok;

    if ((parser == nullptr) || (parser->parser == nullptr) || (file_path == nullptr) ||
        (source == nullptr) || (out_file == nullptr) || (source_len > UINT32_MAX)) {
        return -1;
    }

    ensure_clojure_ids();
    (void)memset(out_file, 0, sizeof(*out_file));
    out_file->file_path = copy_cstr(file_path);
    if (out_file->file_path == nullptr) {
        return -1;
    }

    if (!parser->force_treesitter) {
        int reader_rc = cp_parse_clojure(parser, source, source_len, out_file);

        if (reader_rc < 0) {
            return -1;
        }
        if (reader_rc == 0) {
            if (out_file->namespace_name == nullptr) {
                out_file->namespace_name = copy_cstr("");
                if (out_file->namespace_name == nullptr) {
                    return -1;
                }
            }
            resolve_reference_targets(out_file);
            return 0;
        }
        /* The reader declined the file; fall back to tree-sitter. */
        atomic_fetch_add_explicit(&clojure_reader_fallbacks, 1U, memory_order_relaxed);
    }

    tree = ts_parser_parse_string(parser->parser, nullptr, source, (uint32_t)source_len);
    if (tree == nullptr) {
        return -1;
    }

    {
        TSTreeCursor cursor = ts_tree_cursor_new(ts_tree_root_node(tree));
        ok = walk_tree_cursor(source, &cursor, out_file);
        ts_tree_cursor_delete(&cursor);
    }
    ts_tree_delete(tree);

    if (!ok) {
        return -1;
    }

    if (out_file->namespace_name == nullptr) {
        out_file->namespace_name = copy_cstr("");
        if (out_file->namespace_name == nullptr) {
            return -1;
        }
    }

    resolve_reference_targets(out_file);
    return 0;
}

int code_lens_parse_clojure_source(const char *file_path,
                                    const char *source,
                                    size_t source_len,
                                    CodeLensClojureFile *out_file)
{
    CodeLensClojureParser *parser = code_lens_clojure_parser_new();
    int rc;

    if (parser == nullptr) {
        return -1;
    }

    rc = code_lens_parse_clojure_source_with_parser(parser, file_path, source, source_len, out_file);
    code_lens_clojure_parser_delete(parser);
    return rc;
}

/* Java parser */

struct CodeLensJavaParser {
    TSParser *parser;
};

typedef struct {
    char *name;
    char *target;
    char *super_target;
    uint32_t start_byte;
    uint32_t end_byte;
} JavaTypeEntry;

typedef struct {
    char *name;
    char *target;
    uint32_t declaration_byte;
    uint32_t scope_start;
    uint32_t scope_end;
    bool field;
} JavaBinding;

typedef struct {
    const char *source;
    size_t source_len;
    CodeLensJavaFile *file;
    JavaTypeEntry *types;
    size_t type_count;
    size_t type_capacity;
    JavaBinding *bindings;
    size_t binding_count;
    size_t binding_capacity;
} JavaParseContext;

typedef struct {
    CodeLensClojureParser *clojure;
    CodeLensJavaParser *java;
    CodeLensCParser *c;
} CodeLensSourceParser;

static bool java_node_is(TSNode node, const char *type)
{
    return !ts_node_is_null(node) && (strcmp(ts_node_type(node), type) == 0);
}

static bool parser_heap_grow(void **items,
                           size_t *capacity,
                           size_t elem_size,
                           size_t initial_capacity)
{
    size_t new_capacity = *capacity == 0U ? initial_capacity : *capacity * 2U;
    void *grown;

    if ((new_capacity < *capacity) || (new_capacity > (SIZE_MAX / elem_size))) {
        return false;
    }
    grown = realloc(*items, new_capacity * elem_size);
    if (grown == nullptr) {
        return false;
    }
    *items = grown;
    *capacity = new_capacity;
    return true;
}

static const char *java_type_declaration_kind(TSNode node)
{
    const char *type = ts_node_type(node);

    if (strcmp(type, "class_declaration") == 0) {
        return "class";
    }
    if (strcmp(type, "interface_declaration") == 0) {
        return "interface";
    }
    if (strcmp(type, "enum_declaration") == 0) {
        return "enum";
    }
    if (strcmp(type, "annotation_type_declaration") == 0) {
        return "annotation";
    }
    if (strcmp(type, "record_declaration") == 0) {
        return "record";
    }
    return nullptr;
}

static char *java_join_name(const char *prefix, const char *name)
{
    size_t prefix_len;
    size_t name_len;
    char *joined;

    if ((prefix == nullptr) || (prefix[0] == '\0')) {
        return copy_cstr(name);
    }
    prefix_len = strlen(prefix);
    name_len = strlen(name);
    joined = code_lens_alloc(prefix_len + 1U + name_len + 1U);
    if (joined == nullptr) {
        return nullptr;
    }
    (void)memcpy(joined, prefix, prefix_len);
    joined[prefix_len] = '.';
    (void)memcpy(joined + prefix_len + 1U, name, name_len + 1U);
    return joined;
}

static char *java_append_name_suffix(const char *prefix, const char *suffix)
{
    size_t prefix_len = strlen(prefix);
    size_t suffix_len = strlen(suffix);
    char *joined = code_lens_alloc(prefix_len + suffix_len + 1U);

    if (joined == nullptr) {
        return nullptr;
    }
    (void)memcpy(joined, prefix, prefix_len);
    (void)memcpy(joined + prefix_len, suffix, suffix_len + 1U);
    return joined;
}

static bool java_add_type_entry(JavaParseContext *ctx,
                                TSNode declaration,
                                TSNode name_node,
                                const char *enclosing)
{
    JavaTypeEntry *entry;
    char *name = node_text(ctx->source, name_node);
    const char *prefix = enclosing;

    if (name == nullptr) {
        return false;
    }
    if ((prefix == nullptr) || (prefix[0] == '\0')) {
        prefix = ctx->file->namespace_name;
    }
    if ((ctx->type_count == ctx->type_capacity) &&
        !parser_heap_grow((void **)&ctx->types,
                        &ctx->type_capacity,
                        sizeof(*ctx->types),
                        16U)) {
        return false;
    }
    entry = &ctx->types[ctx->type_count++];
    (void)memset(entry, 0, sizeof(*entry));
    entry->name = name;
    entry->target = java_join_name(prefix, name);
    entry->start_byte = ts_node_start_byte(declaration);
    entry->end_byte = ts_node_end_byte(declaration);
    return entry->target != nullptr;
}

static JavaTypeEntry *java_type_entry_for_declaration(JavaParseContext *ctx, TSNode declaration)
{
    uint32_t start = ts_node_start_byte(declaration);

    for (size_t i = 0U; i < ctx->type_count; i++) {
        if (ctx->types[i].start_byte == start) {
            return &ctx->types[i];
        }
    }
    return nullptr;
}

static bool java_collect_type_entries(JavaParseContext *ctx, TSNode node, const char *enclosing)
{
    const char *kind = java_type_declaration_kind(node);
    const char *child_enclosing = enclosing;

    if (kind != nullptr) {
        TSNode name = ts_node_child_by_field_name(node, "name", 4U);

        if (ts_node_is_null(name) || !java_add_type_entry(ctx, node, name, enclosing)) {
            return false;
        }
        child_enclosing = ctx->types[ctx->type_count - 1U].target;
    }

    for (uint32_t i = 0U; i < ts_node_named_child_count(node); i++) {
        if (!java_collect_type_entries(ctx, ts_node_named_child(node, i), child_enclosing)) {
            return false;
        }
    }
    return true;
}

static bool java_extract_package(JavaParseContext *ctx, TSNode root)
{
    for (uint32_t i = 0U; i < ts_node_named_child_count(root); i++) {
        TSNode child = ts_node_named_child(root, i);

        if (java_node_is(child, "package_declaration")) {
            for (uint32_t j = 0U; j < ts_node_named_child_count(child); j++) {
                TSNode name = ts_node_named_child(child, j);

                if (java_node_is(name, "identifier") || java_node_is(name, "scoped_identifier")) {
                    ctx->file->namespace_name = node_text(ctx->source, name);
                    return ctx->file->namespace_name != nullptr;
                }
            }
        }
    }
    ctx->file->namespace_name = copy_cstr("");
    return ctx->file->namespace_name != nullptr;
}

static char *java_compact_import(JavaParseContext *ctx, TSNode node, bool *out_static)
{
    SourceSlice slice;
    size_t start = 0U;
    size_t end;
    StringBuilder compact = {0};

    *out_static = false;
    if (!node_source_slice(ctx->source, node, &slice)) {
        return nullptr;
    }
    while ((start < slice.len) && isspace((unsigned char)slice.data[start])) {
        start++;
    }
    if ((slice.len - start < sizeof("import") - 1U) ||
        (memcmp(slice.data + start, "import", sizeof("import") - 1U) != 0)) {
        return nullptr;
    }
    start += sizeof("import") - 1U;
    while ((start < slice.len) && isspace((unsigned char)slice.data[start])) {
        start++;
    }
    if ((slice.len - start >= sizeof("static") - 1U) &&
        (memcmp(slice.data + start, "static", sizeof("static") - 1U) == 0) &&
        ((start + sizeof("static") - 1U == slice.len) ||
         isspace((unsigned char)slice.data[start + sizeof("static") - 1U]))) {
        *out_static = true;
        start += sizeof("static") - 1U;
    }
    end = slice.len;
    while ((end > start) &&
           (isspace((unsigned char)slice.data[end - 1U]) || (slice.data[end - 1U] == ';'))) {
        end--;
    }
    for (size_t i = start; i < end; i++) {
        if (!isspace((unsigned char)slice.data[i]) &&
            !sb_append_len(&compact, slice.data + i, 1U)) {
            sb_free(&compact);
            return nullptr;
        }
    }
    return compact.data == nullptr ? copy_cstr("") : compact.data;
}

static bool java_extract_import(JavaParseContext *ctx, TSNode node)
{
    bool is_static;
    char *name = java_compact_import(ctx, node, &is_static);
    size_t len;
    bool wildcard;
    char *last_dot;

    if (name == nullptr) {
        return false;
    }
    len = strlen(name);
    wildcard = (len >= 2U) && (name[len - 2U] == '.') && (name[len - 1U] == '*');
    if (wildcard) {
        name[len - 2U] = '\0';
    }

    if (is_static) {
        if (wildcard) {
            return add_referred(ctx->file, name, copy_cstr(":all"));
        }
        last_dot = strrchr(name, '.');
        if ((last_dot == nullptr) || (last_dot == name) || (last_dot[1] == '\0')) {
            return true;
        }
        *last_dot = '\0';
        return add_referred(ctx->file, name, copy_cstr(last_dot + 1));
    }

    if (wildcard) {
        return add_alias(ctx->file, name, copy_cstr("*"));
    }
    last_dot = strrchr(name, '.');
    return add_alias(ctx->file,
                     name,
                     copy_cstr(last_dot == nullptr ? name : last_dot + 1));
}

static bool java_extract_imports(JavaParseContext *ctx, TSNode root)
{
    for (uint32_t i = 0U; i < ts_node_named_child_count(root); i++) {
        TSNode child = ts_node_named_child(root, i);

        if (java_node_is(child, "import_declaration") && !java_extract_import(ctx, child)) {
            return false;
        }
    }
    return true;
}

static bool java_is_primitive_or_type_variable(const char *name)
{
    static const char *const ignored[] = {
        "boolean", "byte", "char", "double", "float", "int", "long", "short",
        "void", "var", "T", "E", "K", "V", "R",
    };

    for (size_t i = 0U; i < sizeof(ignored) / sizeof(ignored[0]); i++) {
        if (strcmp(name, ignored[i]) == 0) {
            return true;
        }
    }
    return false;
}

static bool java_is_lang_type(const char *name)
{
    static const char *const names[] = {
        "Appendable", "AutoCloseable", "Boolean", "Byte", "Character", "CharSequence",
        "Class", "ClassLoader", "Cloneable", "Comparable", "Double", "Enum", "Error",
        "Exception", "Float", "Integer", "Iterable", "Long", "Math", "Number", "Object",
        "Override", "Record", "Runnable", "Runtime", "RuntimeException", "Short", "StackTraceElement",
        "String", "StringBuffer", "StringBuilder", "System", "Thread", "Throwable", "Void",
    };

    for (size_t i = 0U; i < sizeof(names) / sizeof(names[0]); i++) {
        if (strcmp(name, names[i]) == 0) {
            return true;
        }
    }
    return false;
}

static char *java_type_token(const char *text)
{
    size_t len = strlen(text);
    size_t start = 0U;
    size_t end;

    while ((start < len) && isspace((unsigned char)text[start])) {
        start++;
    }
    if ((len - start >= sizeof("extends") - 1U) &&
        (memcmp(text + start, "extends", sizeof("extends") - 1U) == 0)) {
        start += sizeof("extends") - 1U;
    } else if ((len - start >= sizeof("super") - 1U) &&
               (memcmp(text + start, "super", sizeof("super") - 1U) == 0)) {
        start += sizeof("super") - 1U;
    }
    while ((start < len) && (isspace((unsigned char)text[start]) || (text[start] == '?'))) {
        start++;
    }
    end = start;
    while (end < len) {
        unsigned char ch = (unsigned char)text[end];

        if (isalnum(ch) || (ch == '_') || (ch == '$') || (ch == '.') || (ch >= 0x80U)) {
            end++;
        } else {
            break;
        }
    }
    return end == start ? nullptr : copy_bytes(text + start, end - start);
}

static const char *java_alias_target(const JavaParseContext *ctx,
                                     const char *name,
                                     size_t name_len)
{
    const CodeLensNamespaceAlias *alias = find_alias(ctx->file, name, name_len);

    return alias == nullptr ? nullptr : alias->namespace_name;
}

static const char *java_local_type_target(const JavaParseContext *ctx,
                                          const char *name,
                                          size_t name_len,
                                          const JavaTypeEntry *current)
{
    const char *fallback = nullptr;

    for (size_t i = 0U; i < ctx->type_count; i++) {
        const JavaTypeEntry *entry = &ctx->types[i];

        if ((strlen(entry->name) != name_len) || (memcmp(entry->name, name, name_len) != 0)) {
            continue;
        }
        fallback = entry->target;
        if ((current != nullptr) &&
            ((strcmp(entry->target, current->target) == 0) ||
             ((strncmp(entry->target, current->target, strlen(current->target)) == 0) &&
              (entry->target[strlen(current->target)] == '.')))) {
            return entry->target;
        }
    }
    return fallback;
}

static char *java_resolve_type(JavaParseContext *ctx,
                               const char *raw,
                               const JavaTypeEntry *current)
{
    char *token = java_type_token(raw);
    char *dot;
    const char *mapped;
    size_t first_len;

    if ((token == nullptr) || java_is_primitive_or_type_variable(token)) {
        return nullptr;
    }
    dot = strchr(token, '.');
    first_len = dot == nullptr ? strlen(token) : (size_t)(dot - token);

    mapped = java_alias_target(ctx, token, first_len);
    if (mapped == nullptr) {
        mapped = java_local_type_target(ctx, token, first_len, current);
    }
    if (mapped != nullptr) {
        return dot == nullptr ? copy_cstr(mapped) : java_append_name_suffix(mapped, dot);
    }

    if (dot == nullptr) {
        if (java_is_lang_type(token)) {
            return java_join_name("java.lang", token);
        }
        for (size_t i = 0U; i < ctx->file->alias_count; i++) {
            const CodeLensNamespaceAlias *alias = &ctx->file->aliases[i];

            if ((alias->alias != nullptr) && (strcmp(alias->alias, "*") == 0)) {
                return java_join_name(alias->namespace_name, token);
            }
        }
        return java_join_name(ctx->file->namespace_name, token);
    }

    if (islower((unsigned char)token[0])) {
        return token;
    }
    return java_join_name(ctx->file->namespace_name, token);
}

static TSNode java_first_type_node(TSNode node)
{
    const char *type = ts_node_type(node);

    if ((strcmp(type, "type_identifier") == 0) ||
        (strcmp(type, "scoped_type_identifier") == 0) ||
        (strcmp(type, "generic_type") == 0)) {
        return node;
    }
    for (uint32_t i = 0U; i < ts_node_named_child_count(node); i++) {
        TSNode found = java_first_type_node(ts_node_named_child(node, i));

        if (!ts_node_is_null(found)) {
            return found;
        }
    }
    return (TSNode){0};
}

static char *java_doc_before(const char *source, uint32_t declaration_start)
{
    size_t end = (size_t)declaration_start;
    size_t comment_end;
    size_t open = SIZE_MAX;
    size_t newline_count = 0U;
    StringBuilder out = {0};

    while ((end > 0U) && isspace((unsigned char)source[end - 1U])) {
        newline_count += source[end - 1U] == '\n' ? 1U : 0U;
        end--;
    }
    if ((newline_count > 2U) || (end < 2U) || (source[end - 2U] != '*') ||
        (source[end - 1U] != '/')) {
        return nullptr;
    }
    comment_end = end - 2U;
    for (size_t i = comment_end; i >= 3U; i--) {
        if ((source[i - 3U] == '/') && (source[i - 2U] == '*') &&
            (source[i - 1U] == '*')) {
            open = i;
            break;
        }
    }
    if (open == SIZE_MAX) {
        return nullptr;
    }

    for (size_t pos = open; pos < comment_end;) {
        size_t line_end = pos;
        size_t left;
        size_t right;

        while ((line_end < comment_end) && (source[line_end] != '\n')) {
            line_end++;
        }
        left = pos;
        while ((left < line_end) &&
               ((source[left] == ' ') || (source[left] == '\t') || (source[left] == '\r'))) {
            left++;
        }
        if ((left < line_end) && (source[left] == '*')) {
            left++;
            if ((left < line_end) && (source[left] == ' ')) {
                left++;
            }
        }
        right = line_end;
        while ((right > left) && isspace((unsigned char)source[right - 1U])) {
            right--;
        }
        if ((right > left) && !sb_append_len(&out, source + left, right - left)) {
            sb_free(&out);
            return nullptr;
        }
        if ((line_end < comment_end) && !sb_append(&out, "\n")) {
            sb_free(&out);
            return nullptr;
        }
        pos = line_end < comment_end ? line_end + 1U : comment_end;
    }
    while ((out.len > 0U) && isspace((unsigned char)out.data[out.len - 1U])) {
        out.data[--out.len] = '\0';
    }
    return out.data;
}

static char *java_definition_source(const char *source, TSNode declaration, bool header_only)
{
    TSNode body;
    uint32_t start;
    uint32_t end;
    static const char suffix[] = " { ... }";
    char *text;
    size_t len;

    if (!header_only) {
        return node_text(source, declaration);
    }
    body = ts_node_child_by_field_name(declaration, "body", 4U);
    if (ts_node_is_null(body)) {
        return node_text(source, declaration);
    }
    start = ts_node_start_byte(declaration);
    end = ts_node_start_byte(body);
    while ((end > start) && isspace((unsigned char)source[end - 1U])) {
        end--;
    }
    len = (size_t)(end - start);
    text = code_lens_alloc(len + sizeof(suffix));
    if (text == nullptr) {
        return nullptr;
    }
    (void)memcpy(text, source + start, len);
    (void)memcpy(text + len, suffix, sizeof(suffix));
    return text;
}

static bool java_add_definition(JavaParseContext *ctx,
                                TSNode declaration,
                                TSNode name_node,
                                const char *kind,
                                const char *namespace_name,
                                bool header_only)
{
    CodeLensSymbol *symbol;

    if (ts_node_is_null(name_node) || (namespace_name == nullptr) || !reserve_symbols(ctx->file)) {
        return false;
    }
    symbol = &ctx->file->symbols[ctx->file->symbol_count];
    (void)memset(symbol, 0, sizeof(*symbol));
    symbol->kind = copy_cstr(kind);
    symbol->name = node_text(ctx->source, name_node);
    symbol->namespace_name = copy_cstr(namespace_name);
    symbol->doc = java_doc_before(ctx->source, ts_node_start_byte(declaration));
    symbol->source = java_definition_source(ctx->source, declaration, header_only);
    symbol->start_line = ts_node_start_point(declaration).row + 1U;
    symbol->end_line = ts_node_end_point(declaration).row + 1U;
    if ((symbol->kind == nullptr) || (symbol->name == nullptr) ||
        (symbol->namespace_name == nullptr) || (symbol->source == nullptr)) {
        (void)memset(symbol, 0, sizeof(*symbol));
        return false;
    }
    ctx->file->symbol_count++;
    return true;
}

static bool java_test_annotation_name(const char *name)
{
    static const char *const tests[] = {
        "Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate",
    };
    const char *base = strrchr(name, '.');

    base = base == nullptr ? name : base + 1;
    for (size_t i = 0U; i < sizeof(tests) / sizeof(tests[0]); i++) {
        if (strcmp(base, tests[i]) == 0) {
            return true;
        }
    }
    return false;
}

static bool java_modifiers_have_test_annotation(const char *source, TSNode declaration)
{
    for (uint32_t i = 0U; i < ts_node_named_child_count(declaration); i++) {
        TSNode child = ts_node_named_child(declaration, i);

        if (!java_node_is(child, "modifiers")) {
            continue;
        }
        for (uint32_t j = 0U; j < ts_node_named_child_count(child); j++) {
            TSNode annotation = ts_node_named_child(child, j);
            const char *type = ts_node_type(annotation);

            if ((strcmp(type, "annotation") == 0) || (strcmp(type, "marker_annotation") == 0)) {
                TSNode name_node = ts_node_child_by_field_name(annotation, "name", 4U);
                char *name = ts_node_is_null(name_node) ? nullptr : node_text(source, name_node);

                if ((name != nullptr) && java_test_annotation_name(name)) {
                    return true;
                }
            }
        }
    }
    return false;
}

static bool java_store_binding(JavaParseContext *ctx,
                               TSNode name_node,
                               char *target,
                               uint32_t scope_start,
                               uint32_t scope_end,
                               bool field)
{
    JavaBinding *binding;
    char *name = node_text(ctx->source, name_node);

    if ((name == nullptr) || (target == nullptr)) {
        return false;
    }
    if ((ctx->binding_count == ctx->binding_capacity) &&
        !parser_heap_grow((void **)&ctx->bindings,
                        &ctx->binding_capacity,
                        sizeof(*ctx->bindings),
                        32U)) {
        return false;
    }
    binding = &ctx->bindings[ctx->binding_count++];
    binding->name = name;
    binding->target = target;
    binding->declaration_byte = ts_node_start_byte(name_node);
    binding->scope_start = scope_start;
    binding->scope_end = scope_end;
    binding->field = field;
    return true;
}

static bool java_add_binding(JavaParseContext *ctx,
                             TSNode name_node,
                             TSNode type_node,
                             uint32_t scope_start,
                             uint32_t scope_end,
                             bool field,
                             const JavaTypeEntry *current)
{
    char *raw_type;
    char *target;

    if (ts_node_is_null(name_node) || ts_node_is_null(type_node)) {
        return true;
    }
    raw_type = node_text(ctx->source, type_node);
    target = raw_type == nullptr ? nullptr : java_resolve_type(ctx, raw_type, current);
    if (raw_type == nullptr) {
        return false;
    }
    if (target == nullptr) {
        target = copy_cstr("");
    }
    return java_store_binding(ctx,
                              name_node,
                              target,
                              scope_start,
                              scope_end,
                              field);
}

static const JavaBinding *java_find_binding(const JavaParseContext *ctx,
                                           const char *name,
                                           uint32_t at_byte)
{
    const JavaBinding *best = nullptr;

    for (size_t i = 0U; i < ctx->binding_count; i++) {
        const JavaBinding *binding = &ctx->bindings[i];

        if ((strcmp(binding->name, name) != 0) || (at_byte < binding->scope_start) ||
            (at_byte > binding->scope_end) ||
            (!binding->field && (binding->declaration_byte > at_byte))) {
            continue;
        }
        if ((best == nullptr) || (binding->scope_start > best->scope_start) ||
            ((binding->scope_start == best->scope_start) &&
             (binding->declaration_byte > best->declaration_byte))) {
            best = binding;
        }
    }
    return best;
}

static const char *java_binding_target(const JavaParseContext *ctx,
                                       const char *name,
                                       uint32_t at_byte)
{
    const JavaBinding *binding = java_find_binding(ctx, name, at_byte);

    return binding == nullptr ? nullptr : binding->target;
}

static bool java_add_variable_declaration_bindings(JavaParseContext *ctx,
                                                   TSNode declaration,
                                                   uint32_t scope_start,
                                                   uint32_t scope_end,
                                                   bool field,
                                                   const JavaTypeEntry *current)
{
    TSNode type_node = ts_node_child_by_field_name(declaration, "type", 4U);

    for (uint32_t i = 0U; i < ts_node_named_child_count(declaration); i++) {
        TSNode child = ts_node_named_child(declaration, i);

        if (java_node_is(child, "variable_declarator")) {
            TSNode name = ts_node_child_by_field_name(child, "name", 4U);

            if (!java_add_binding(ctx,
                                  name,
                                  type_node,
                                  scope_start,
                                  scope_end,
                                  field,
                                  current)) {
                return false;
            }
        }
    }
    return true;
}

static bool java_add_field_definitions(JavaParseContext *ctx,
                                       TSNode declaration,
                                       const JavaTypeEntry *current)
{
    if (current == nullptr) {
        return true;
    }
    for (uint32_t i = 0U; i < ts_node_named_child_count(declaration); i++) {
        TSNode child = ts_node_named_child(declaration, i);

        if (java_node_is(child, "variable_declarator")) {
            TSNode name = ts_node_child_by_field_name(child, "name", 4U);

            if (!java_add_definition(ctx,
                                     declaration,
                                     name,
                                     "field",
                                     current->target,
                                     false)) {
                return false;
            }
        }
    }
    return java_add_variable_declaration_bindings(ctx,
                                                   declaration,
                                                   current->start_byte,
                                                   current->end_byte,
                                                   true,
                                                   current);
}

static bool java_extract_declarations(JavaParseContext *ctx,
                                      TSNode node,
                                      JavaTypeEntry *current,
                                      uint32_t scope_start,
                                      uint32_t scope_end)
{
    const char *type = ts_node_type(node);
    const char *type_kind = java_type_declaration_kind(node);
    JavaTypeEntry *child_current = current;
    uint32_t child_scope_start = scope_start;
    uint32_t child_scope_end = scope_end;

    if (type_kind != nullptr) {
        TSNode name = ts_node_child_by_field_name(node, "name", 4U);
        TSNode superclass = ts_node_child_by_field_name(node, "superclass", 10U);

        child_current = java_type_entry_for_declaration(ctx, node);
        if ((child_current == nullptr) ||
            !java_add_definition(ctx,
                                 node,
                                 name,
                                 type_kind,
                                 child_current->target,
                                 true)) {
            return false;
        }
        child_scope_start = child_current->start_byte;
        child_scope_end = child_current->end_byte;
        if (!ts_node_is_null(superclass)) {
            TSNode super_type = java_first_type_node(superclass);
            char *raw = ts_node_is_null(super_type) ? nullptr : node_text(ctx->source, super_type);

            child_current->super_target =
                raw == nullptr ? nullptr : java_resolve_type(ctx, raw, child_current);
        }
        if (strcmp(type_kind, "record") == 0) {
            TSNode parameters = ts_node_child_by_field_name(node, "parameters", 10U);

            for (uint32_t i = 0U; i < ts_node_named_child_count(parameters); i++) {
                TSNode parameter = ts_node_named_child(parameters, i);

                if (java_node_is(parameter, "formal_parameter")) {
                    TSNode component = ts_node_child_by_field_name(parameter, "name", 4U);

                    TSNode component_type =
                        ts_node_child_by_field_name(parameter, "type", 4U);

                    if (!java_add_definition(ctx,
                                             parameter,
                                             component,
                                             "field",
                                             child_current->target,
                                             false) ||
                        !java_add_binding(ctx,
                                          component,
                                          component_type,
                                          child_current->start_byte,
                                          child_current->end_byte,
                                          true,
                                          child_current)) {
                        return false;
                    }
                }
            }
        }
    } else if (strcmp(type, "method_declaration") == 0) {
        TSNode name = ts_node_child_by_field_name(node, "name", 4U);
        const char *kind = java_modifiers_have_test_annotation(ctx->source, node) ? "test" : "method";

        if ((current != nullptr) &&
            !java_add_definition(ctx, node, name, kind, current->target, false)) {
            return false;
        }
        child_scope_start = ts_node_start_byte(node);
        child_scope_end = ts_node_end_byte(node);
    } else if ((strcmp(type, "constructor_declaration") == 0) ||
               (strcmp(type, "compact_constructor_declaration") == 0)) {
        TSNode name = ts_node_child_by_field_name(node, "name", 4U);

        if ((current != nullptr) &&
            !java_add_definition(ctx, node, name, "constructor", current->target, false)) {
            return false;
        }
        child_scope_start = ts_node_start_byte(node);
        child_scope_end = ts_node_end_byte(node);
    } else if ((strcmp(type, "field_declaration") == 0) ||
               (strcmp(type, "constant_declaration") == 0)) {
        if (!java_add_field_definitions(ctx, node, current)) {
            return false;
        }
    } else if (strcmp(type, "enum_constant") == 0) {
        TSNode name = ts_node_child_by_field_name(node, "name", 4U);

        if ((current != nullptr) &&
            (!java_add_definition(ctx,
                                  node,
                                  name,
                                  "enum_constant",
                                  current->target,
                                  false) ||
             !java_store_binding(ctx,
                                 name,
                                 copy_cstr(current->target),
                                 current->start_byte,
                                 current->end_byte,
                                 true))) {
            return false;
        }
    } else if (strcmp(type, "annotation_type_element_declaration") == 0) {
        TSNode name = ts_node_child_by_field_name(node, "name", 4U);

        if ((current != nullptr) &&
            !java_add_definition(ctx, node, name, "method", current->target, false)) {
            return false;
        }
    } else if (strcmp(type, "module_declaration") == 0) {
        TSNode name = ts_node_child_by_field_name(node, "name", 4U);
        char *module_name = ts_node_is_null(name) ? nullptr : node_text(ctx->source, name);

        if ((module_name == nullptr) ||
            !java_add_definition(ctx, node, name, "module", module_name, true)) {
            return false;
        }
    } else if (strcmp(type, "local_variable_declaration") == 0) {
        if (!java_add_variable_declaration_bindings(ctx,
                                                    node,
                                                    scope_start,
                                                    scope_end,
                                                    false,
                                                    current)) {
            return false;
        }
    } else if (strcmp(type, "formal_parameter") == 0) {
        TSNode name = ts_node_child_by_field_name(node, "name", 4U);
        TSNode value_type = ts_node_child_by_field_name(node, "type", 4U);

        if (!java_add_binding(ctx,
                              name,
                              value_type,
                              scope_start,
                              scope_end,
                              false,
                              current)) {
            return false;
        }
    } else if (strcmp(type, "enhanced_for_statement") == 0) {
        TSNode name = ts_node_child_by_field_name(node, "name", 4U);
        TSNode value_type = ts_node_child_by_field_name(node, "type", 4U);

        if (!java_add_binding(ctx,
                              name,
                              value_type,
                              ts_node_start_byte(node),
                              ts_node_end_byte(node),
                              false,
                              current)) {
            return false;
        }
    } else if (strcmp(type, "resource") == 0) {
        TSNode name = ts_node_child_by_field_name(node, "name", 4U);
        TSNode value_type = ts_node_child_by_field_name(node, "type", 4U);

        if (!java_add_binding(ctx,
                              name,
                              value_type,
                              scope_start,
                              scope_end,
                              false,
                              current)) {
            return false;
        }
    } else if (strcmp(type, "block") == 0) {
        child_scope_start = ts_node_start_byte(node);
        child_scope_end = ts_node_end_byte(node);
    }

    for (uint32_t i = 0U; i < ts_node_named_child_count(node); i++) {
        if (!java_extract_declarations(ctx,
                                       ts_node_named_child(node, i),
                                       child_current,
                                       child_scope_start,
                                       child_scope_end)) {
            return false;
        }
    }
    return true;
}

static bool java_add_reference_span(JavaParseContext *ctx,
                                    uint32_t start,
                                    uint32_t end,
                                    uint32_t base_start,
                                    uint32_t base_end,
                                    const char *target_namespace,
                                    TSPoint base_point)
{
    CodeLensReference *reference;

    if ((end <= start) || (base_start < start) || (base_end <= base_start) ||
        (base_end > end) || ((size_t)end > ctx->source_len) || !reserve_references(ctx->file)) {
        return false;
    }
    reference = &ctx->file->references[ctx->file->reference_count];
    (void)memset(reference, 0, sizeof(*reference));
    reference->symbol = copy_bytes(ctx->source + start, (size_t)(end - start));
    reference->symbol_len = (size_t)(end - start);
    if (reference->symbol == nullptr) {
        return false;
    }
    reference->base = reference->symbol + (base_start - start);
    reference->base_len = (size_t)(base_end - base_start);
    reference->target_namespace = copy_cstr(target_namespace == nullptr ? "" : target_namespace);
    if (reference->target_namespace == nullptr) {
        return false;
    }
    reference->target_namespace_len = strlen(reference->target_namespace);
    reference->line = base_point.row + 1U;
    reference->column = base_point.column + 1U;
    reference->start_byte = base_start;
    reference->end_byte = base_end;
    reference->symbol_hash = term_hash(reference->symbol, reference->symbol_len);
    reference->base_hash = term_hash(reference->base, reference->base_len);
    reference->target_namespace_hash =
        term_hash(reference->target_namespace, reference->target_namespace_len);
    ctx->file->reference_count++;
    return true;
}

static bool java_add_reference_nodes(JavaParseContext *ctx,
                                     TSNode symbol_node,
                                     TSNode base_node,
                                     const char *target_namespace)
{
    return java_add_reference_span(ctx,
                                   ts_node_start_byte(symbol_node),
                                   ts_node_end_byte(symbol_node),
                                   ts_node_start_byte(base_node),
                                   ts_node_end_byte(base_node),
                                   target_namespace,
                                   ts_node_start_point(base_node));
}

static const char *java_static_import_target(const JavaParseContext *ctx,
                                             const char *name,
                                             bool *out_wildcard)
{
    *out_wildcard = false;
    for (size_t i = 0U; i < ctx->file->referred_count; i++) {
        const CodeLensReferred *referred = &ctx->file->referred[i];

        if (strcmp(referred->symbol, name) == 0) {
            return referred->namespace_name;
        }
        if (strcmp(referred->symbol, ":all") == 0) {
            *out_wildcard = true;
        }
    }
    return nullptr;
}

static bool java_text_has_uppercase_segment(const char *text)
{
    bool segment_start = true;

    for (size_t i = 0U; text[i] != '\0'; i++) {
        if (segment_start && isupper((unsigned char)text[i])) {
            return true;
        }
        segment_start = text[i] == '.';
    }
    return false;
}

static char *java_resolve_receiver(JavaParseContext *ctx,
                                   TSNode object,
                                   const JavaTypeEntry *current,
                                   uint32_t at_byte)
{
    if (java_node_is(object, "object_creation_expression") ||
        java_node_is(object, "cast_expression")) {
        TSNode type_node = ts_node_child_by_field_name(object, "type", 4U);
        char *raw_type = ts_node_is_null(type_node) ? nullptr : node_text(ctx->source, type_node);
        char *resolved = raw_type == nullptr ? nullptr : java_resolve_type(ctx, raw_type, current);

        if (resolved != nullptr) {
            return resolved;
        }
    }

    char *text = node_text(ctx->source, object);
    const char *binding;
    const char *lookup = text;
    const char *last_dot;

    if (text == nullptr) {
        return nullptr;
    }
    if (strcmp(text, "this") == 0) {
        return current == nullptr ? copy_cstr("") : copy_cstr(current->target);
    }
    if (strcmp(text, "super") == 0) {
        return (current == nullptr) || (current->super_target == nullptr)
                   ? copy_cstr("")
                   : copy_cstr(current->super_target);
    }

    if (strncmp(text, "this.", sizeof("this.") - 1U) == 0) {
        lookup = strrchr(text, '.');
        lookup = lookup == nullptr ? text : lookup + 1;
    }
    last_dot = strrchr(lookup, '.');
    if (last_dot != nullptr) {
        lookup = last_dot + 1;
    }
    binding = java_binding_target(ctx, lookup, at_byte);
    if (binding != nullptr) {
        return copy_cstr(binding);
    }

    if ((strchr(text, '(') == nullptr) && (strchr(text, '[') == nullptr) &&
        (java_text_has_uppercase_segment(text) ||
         (java_alias_target(ctx,
                            text,
                            strchr(text, '.') == nullptr
                                ? strlen(text)
                                : (size_t)(strchr(text, '.') - text)) != nullptr))) {
        char *resolved = java_resolve_type(ctx, text, current);

        if (resolved != nullptr) {
            /* A lowercase suffix is a static field, not a nested type whose
             * class can be inferred without type attribution. */
            const char *suffix = strrchr(text, '.');
            if ((suffix == nullptr) || isupper((unsigned char)suffix[1])) {
                return resolved;
            }
        }
    }
    return copy_cstr("");
}

static bool java_receiver_span_is_simple(const char *source, TSNode object)
{
    uint32_t start = ts_node_start_byte(object);
    uint32_t end = ts_node_end_byte(object);

    for (uint32_t i = start; i < end; i++) {
        if ((source[i] == '(') || (source[i] == '[') || (source[i] == '{')) {
            return false;
        }
    }
    return true;
}

static bool java_type_defines_name(const JavaParseContext *ctx,
                                   const JavaTypeEntry *type,
                                   const char *name)
{
    if (type == nullptr) {
        return false;
    }
    for (size_t i = 0U; i < ctx->file->symbol_count; i++) {
        const CodeLensSymbol *symbol = &ctx->file->symbols[i];

        if ((strcmp(symbol->namespace_name, type->target) == 0) &&
            (strcmp(symbol->name, name) == 0)) {
            return true;
        }
    }
    return false;
}

static bool java_add_method_invocation_reference(JavaParseContext *ctx,
                                                 TSNode node,
                                                 const JavaTypeEntry *current)
{
    TSNode name = ts_node_child_by_field_name(node, "name", 4U);
    TSNode object = ts_node_child_by_field_name(node, "object", 6U);
    char *method_name;
    char *target = nullptr;
    bool wildcard = false;
    uint32_t start;

    if (ts_node_is_null(name)) {
        return true;
    }
    method_name = node_text(ctx->source, name);
    if (method_name == nullptr) {
        return false;
    }
    if (ts_node_is_null(object)) {
        const char *import_target = java_static_import_target(ctx, method_name, &wildcard);

        if (java_type_defines_name(ctx, current, method_name)) {
            target = copy_cstr(current->target);
        } else if (import_target != nullptr) {
            target = copy_cstr(import_target);
        } else if (wildcard) {
            target = copy_cstr("");
        } else if ((current != nullptr) && (current->super_target != nullptr)) {
            target = copy_cstr(current->super_target);
        } else {
            target = current == nullptr ? copy_cstr("") : copy_cstr(current->target);
        }
        start = ts_node_start_byte(name);
    } else {
        target = java_resolve_receiver(ctx, object, current, ts_node_start_byte(name));
        start = java_receiver_span_is_simple(ctx->source, object) ? ts_node_start_byte(object)
                                                                  : ts_node_start_byte(name);
    }
    if (target == nullptr) {
        return false;
    }
    return java_add_reference_span(ctx,
                                   start,
                                   ts_node_end_byte(name),
                                   ts_node_start_byte(name),
                                   ts_node_end_byte(name),
                                   target,
                                   ts_node_start_point(name));
}

static bool java_add_field_access_reference(JavaParseContext *ctx,
                                            TSNode node,
                                            const JavaTypeEntry *current)
{
    TSNode field = ts_node_child_by_field_name(node, "field", 5U);
    TSNode object = ts_node_child_by_field_name(node, "object", 6U);
    char *target;

    if (ts_node_is_null(field) || ts_node_is_null(object)) {
        return true;
    }
    target = java_resolve_receiver(ctx, object, current, ts_node_start_byte(field));
    if (target == nullptr) {
        return false;
    }
    return java_add_reference_span(ctx,
                                   java_receiver_span_is_simple(ctx->source, object)
                                       ? ts_node_start_byte(object)
                                       : ts_node_start_byte(field),
                                   ts_node_end_byte(field),
                                   ts_node_start_byte(field),
                                   ts_node_end_byte(field),
                                   target,
                                   ts_node_start_point(field));
}

static TSNode java_last_named_identifier(TSNode node)
{
    TSNode found = (TSNode){0};

    if (java_node_is(node, "identifier") || java_node_is(node, "type_identifier")) {
        found = node;
    }
    for (uint32_t i = 0U; i < ts_node_named_child_count(node); i++) {
        TSNode child_found = java_last_named_identifier(ts_node_named_child(node, i));

        if (!ts_node_is_null(child_found)) {
            found = child_found;
        }
    }
    return found;
}

static bool java_add_method_reference(JavaParseContext *ctx,
                                      TSNode node,
                                      const JavaTypeEntry *current)
{
    uint32_t count = ts_node_named_child_count(node);
    TSNode object;
    TSNode name;
    char *target;

    if (count < 2U) {
        return true;
    }
    object = ts_node_named_child(node, 0U);
    name = java_last_named_identifier(node);
    if (ts_node_is_null(name) || (ts_node_start_byte(name) <= ts_node_start_byte(object))) {
        return true;
    }
    target = java_resolve_receiver(ctx, object, current, ts_node_start_byte(name));
    if (target == nullptr) {
        return false;
    }
    return java_add_reference_span(ctx,
                                   java_receiver_span_is_simple(ctx->source, object)
                                       ? ts_node_start_byte(object)
                                       : ts_node_start_byte(name),
                                   ts_node_end_byte(name),
                                   ts_node_start_byte(name),
                                   ts_node_end_byte(name),
                                   target,
                                   ts_node_start_point(name));
}

static bool java_add_annotation_reference(JavaParseContext *ctx,
                                          TSNode node,
                                          const JavaTypeEntry *current)
{
    TSNode name = ts_node_child_by_field_name(node, "name", 4U);
    char *raw;
    char *target;
    TSNode base;

    if (ts_node_is_null(name)) {
        return true;
    }
    raw = node_text(ctx->source, name);
    target = raw == nullptr ? nullptr : java_resolve_type(ctx, raw, current);
    if ((raw == nullptr) || (target == nullptr)) {
        return raw != nullptr;
    }
    base = java_last_named_identifier(name);
    if (ts_node_is_null(base)) {
        base = name;
    }
    return java_add_reference_nodes(ctx, name, base, target);
}

static bool java_add_type_reference(JavaParseContext *ctx,
                                    TSNode node,
                                    const JavaTypeEntry *current)
{
    char *raw = node_text(ctx->source, node);
    char *target = raw == nullptr ? nullptr : java_resolve_type(ctx, raw, current);
    TSNode base;

    if (raw == nullptr) {
        return false;
    }
    if (target == nullptr) {
        return true;
    }
    base = java_last_named_identifier(node);
    if (ts_node_is_null(base)) {
        base = node;
    }
    return java_add_reference_nodes(ctx, node, base, target);
}

static bool java_identifier_is_handled_elsewhere(TSNode node)
{
    TSNode parent = ts_node_parent(node);
    const char *parent_type = ts_node_type(parent);
    TSNode name = ts_node_child_by_field_name(parent, "name", 4U);
    TSNode field = ts_node_child_by_field_name(parent, "field", 5U);

    return (!ts_node_is_null(name) && ts_node_eq(node, name)) ||
           (!ts_node_is_null(field) && ts_node_eq(node, field)) ||
           (strcmp(parent_type, "scoped_identifier") == 0) ||
           (strcmp(parent_type, "scoped_type_identifier") == 0) ||
           (strcmp(parent_type, "method_reference") == 0);
}

static bool java_add_identifier_reference(JavaParseContext *ctx,
                                          TSNode node,
                                          const JavaTypeEntry *current)
{
    char *name;
    const JavaBinding *binding;
    const char *target = nullptr;
    bool wildcard = false;

    if (java_identifier_is_handled_elsewhere(node)) {
        return true;
    }
    name = node_text(ctx->source, node);
    if (name == nullptr) {
        return false;
    }
    binding = java_find_binding(ctx, name, ts_node_start_byte(node));
    if (binding != nullptr) {
        if (!binding->field || (current == nullptr)) {
            return true;
        }
        target = current->target;
    } else {
        target = java_static_import_target(ctx, name, &wildcard);
        if ((target == nullptr) && wildcard) {
            target = "";
        }
        if (target == nullptr) {
            return true;
        }
    }
    return java_add_reference_nodes(ctx, node, node, target);
}

static bool java_extract_references(JavaParseContext *ctx,
                                    TSNode node,
                                    JavaTypeEntry *current)
{
    const char *type = ts_node_type(node);
    const char *type_kind = java_type_declaration_kind(node);
    JavaTypeEntry *child_current = current;

    if (type_kind != nullptr) {
        child_current = java_type_entry_for_declaration(ctx, node);
    }
    if ((strcmp(type, "package_declaration") == 0) ||
        (strcmp(type, "import_declaration") == 0)) {
        return true;
    }
    if (strcmp(type, "method_invocation") == 0) {
        if (!java_add_method_invocation_reference(ctx, node, current)) {
            return false;
        }
    } else if (strcmp(type, "field_access") == 0) {
        if (!java_add_field_access_reference(ctx, node, current)) {
            return false;
        }
    } else if (strcmp(type, "method_reference") == 0) {
        if (!java_add_method_reference(ctx, node, current)) {
            return false;
        }
    } else if ((strcmp(type, "annotation") == 0) ||
               (strcmp(type, "marker_annotation") == 0)) {
        if (!java_add_annotation_reference(ctx, node, current)) {
            return false;
        }
    } else if (strcmp(type, "scoped_type_identifier") == 0) {
        TSNode parent = ts_node_parent(node);

        if (!java_node_is(parent, "scoped_type_identifier") &&
            !java_add_type_reference(ctx, node, current)) {
            return false;
        }
    } else if (strcmp(type, "type_identifier") == 0) {
        TSNode parent = ts_node_parent(node);

        if (!java_node_is(parent, "scoped_type_identifier") &&
            !java_add_type_reference(ctx, node, current)) {
            return false;
        }
    } else if ((strcmp(type, "identifier") == 0) &&
               !java_add_identifier_reference(ctx, node, current)) {
        return false;
    }

    for (uint32_t i = 0U; i < ts_node_named_child_count(node); i++) {
        if (!java_extract_references(ctx, ts_node_named_child(node, i), child_current)) {
            return false;
        }
    }
    return true;
}

CodeLensJavaParser *code_lens_java_parser_new(void)
{
    CodeLensJavaParser *parser = code_lens_alloc(sizeof(*parser));

    if (parser == nullptr) {
        return nullptr;
    }
    parser->parser = ts_parser_new();
    if (parser->parser == nullptr) {
        return nullptr;
    }
    if (!ts_parser_set_language(parser->parser, tree_sitter_java())) {
        ts_parser_delete(parser->parser);
        return nullptr;
    }
    return parser;
}

void code_lens_java_parser_delete(CodeLensJavaParser *parser)
{
    if (parser != nullptr) {
        ts_parser_delete(parser->parser);
    }
}

int code_lens_parse_java_source_with_parser(CodeLensJavaParser *parser,
                                             const char *file_path,
                                             const char *source,
                                             size_t source_len,
                                             CodeLensJavaFile *out_file)
{
    TSTree *tree;
    TSNode root;
    JavaParseContext ctx;
    bool ok;

    if ((parser == nullptr) || (parser->parser == nullptr) || (file_path == nullptr) ||
        (source == nullptr) || (out_file == nullptr) || (source_len > UINT32_MAX)) {
        return -1;
    }
    (void)memset(out_file, 0, sizeof(*out_file));
    out_file->file_path = copy_cstr(file_path);
    if (out_file->file_path == nullptr) {
        return -1;
    }
    tree = ts_parser_parse_string(parser->parser, nullptr, source, (uint32_t)source_len);
    if (tree == nullptr) {
        return -1;
    }
    root = ts_tree_root_node(tree);
    (void)memset(&ctx, 0, sizeof(ctx));
    ctx.source = source;
    ctx.source_len = source_len;
    ctx.file = out_file;

    ok = java_extract_package(&ctx, root) && java_extract_imports(&ctx, root) &&
         java_collect_type_entries(&ctx, root, nullptr) &&
         java_extract_declarations(&ctx, root, nullptr, 0U, (uint32_t)source_len) &&
         java_extract_references(&ctx, root, nullptr);

    free(ctx.types);
    free(ctx.bindings);
    ts_tree_delete(tree);
    return ok ? 0 : -1;
}

int code_lens_parse_java_source(const char *file_path,
                                 const char *source,
                                 size_t source_len,
                                 CodeLensJavaFile *out_file)
{
    CodeLensJavaParser *parser = code_lens_java_parser_new();
    int rc;

    if (parser == nullptr) {
        return -1;
    }
    rc = code_lens_parse_java_source_with_parser(parser,
                                                  file_path,
                                                  source,
                                                  source_len,
                                                  out_file);
    code_lens_java_parser_delete(parser);
    return rc;
}

/* C parser */

struct CodeLensCParser {
    TSParser *parser;
};

typedef struct {
    char *name;
    char *target;
} CTypeAlias;

typedef struct {
    uint32_t start_byte;
    char *target;
    const char *kind;
    char *declared_name;
} CTypeSpec;

typedef struct {
    char *name;
    char *type_target;
    char *symbol_namespace;
    uint32_t declaration_byte;
    uint32_t scope_start;
    uint32_t scope_end;
    bool has_symbol;
    bool global_scope;
} CBinding;

typedef struct {
    char *owner;
    char *name;
    char *type_target;
} CFieldType;

typedef struct {
    const char *source;
    size_t source_len;
    CodeLensCFile *file;
    CTypeAlias *aliases;
    size_t alias_count;
    size_t alias_capacity;
    CTypeSpec *types;
    size_t type_count;
    size_t type_capacity;
    CBinding *bindings;
    size_t binding_count;
    size_t binding_capacity;
    CFieldType *fields;
    size_t field_count;
    size_t field_capacity;
} CParseContext;

static bool c_node_is(TSNode node, const char *type)
{
    return !ts_node_is_null(node) && (strcmp(ts_node_type(node), type) == 0);
}

static bool c_node_field_equals(TSNode parent, const char *field_name, TSNode node)
{
    TSNode field = ts_node_child_by_field_name(parent, field_name, (uint32_t)strlen(field_name));

    return !ts_node_is_null(field) && ts_node_eq(field, node);
}

static TSNode c_declarator_name(TSNode node)
{
    const char *type;
    TSNode declarator;

    if (ts_node_is_null(node)) {
        return (TSNode){0};
    }
    type = ts_node_type(node);
    if ((strcmp(type, "identifier") == 0) || (strcmp(type, "type_identifier") == 0) ||
        (strcmp(type, "field_identifier") == 0)) {
        return node;
    }
    declarator = ts_node_child_by_field_name(node, "declarator", 10U);
    if (!ts_node_is_null(declarator)) {
        return c_declarator_name(declarator);
    }
    for (uint32_t i = 0U; i < ts_node_named_child_count(node); i++) {
        TSNode child = ts_node_named_child(node, i);
        const char *child_type = ts_node_type(child);

        if ((strcmp(child_type, "parameter_list") == 0) ||
            (strcmp(child_type, "argument_list") == 0) ||
            (strcmp(child_type, "initializer_list") == 0)) {
            continue;
        }
        {
            TSNode found = c_declarator_name(child);

            if (!ts_node_is_null(found)) {
                return found;
            }
        }
    }
    return (TSNode){0};
}

static bool c_declarator_contains_pointer(TSNode node)
{
    if (ts_node_is_null(node)) {
        return false;
    }
    if (c_node_is(node, "pointer_declarator")) {
        return true;
    }
    {
        TSNode declarator = ts_node_child_by_field_name(node, "declarator", 10U);

        if (!ts_node_is_null(declarator)) {
            return c_declarator_contains_pointer(declarator);
        }
    }
    for (uint32_t i = 0U; i < ts_node_named_child_count(node); i++) {
        TSNode child = ts_node_named_child(node, i);

        if (!c_node_is(child, "parameter_list") && c_declarator_contains_pointer(child)) {
            return true;
        }
    }
    return false;
}

static bool c_declarator_is_function(TSNode node)
{
    if (ts_node_is_null(node)) {
        return false;
    }
    if (c_node_is(node, "function_declarator")) {
        TSNode inner = ts_node_child_by_field_name(node, "declarator", 10U);

        return !c_declarator_contains_pointer(inner);
    }
    {
        TSNode declarator = ts_node_child_by_field_name(node, "declarator", 10U);

        if (!ts_node_is_null(declarator)) {
            return c_declarator_is_function(declarator);
        }
    }
    for (uint32_t i = 0U; i < ts_node_named_child_count(node); i++) {
        TSNode child = ts_node_named_child(node, i);

        if (!c_node_is(child, "parameter_list") && c_declarator_is_function(child)) {
            return true;
        }
    }
    return false;
}

static TSNode c_function_declarator(TSNode node)
{
    if (ts_node_is_null(node)) {
        return (TSNode){0};
    }
    if (c_node_is(node, "function_declarator") && c_declarator_is_function(node)) {
        return node;
    }
    {
        TSNode declarator = ts_node_child_by_field_name(node, "declarator", 10U);

        if (!ts_node_is_null(declarator)) {
            TSNode found = c_function_declarator(declarator);

            if (!ts_node_is_null(found)) {
                return found;
            }
        }
    }
    return (TSNode){0};
}

static bool c_push_alias(CParseContext *ctx, char *name, char *target)
{
    if ((name == nullptr) || (target == nullptr)) {
        return false;
    }
    if ((ctx->alias_count == ctx->alias_capacity) &&
        !parser_heap_grow((void **)&ctx->aliases,
                          &ctx->alias_capacity,
                          sizeof(*ctx->aliases),
                          16U)) {
        return false;
    }
    ctx->aliases[ctx->alias_count++] = (CTypeAlias){.name = name, .target = target};
    return true;
}

static const char *c_alias_target(const CParseContext *ctx, const char *name)
{
    for (size_t i = ctx->alias_count; i > 0U; i--) {
        const CTypeAlias *alias = &ctx->aliases[i - 1U];

        if (strcmp(alias->name, name) == 0) {
            return alias->target;
        }
    }
    return nullptr;
}

static CTypeSpec *c_type_spec_for_node(CParseContext *ctx, TSNode node)
{
    uint32_t start = ts_node_start_byte(node);

    for (size_t i = 0U; i < ctx->type_count; i++) {
        if (ctx->types[i].start_byte == start) {
            return &ctx->types[i];
        }
    }
    return nullptr;
}

static const char *c_specifier_kind(TSNode node)
{
    const char *type = ts_node_type(node);

    if (strcmp(type, "struct_specifier") == 0) {
        return "struct";
    }
    if (strcmp(type, "union_specifier") == 0) {
        return "union";
    }
    if (strcmp(type, "enum_specifier") == 0) {
        return "enum";
    }
    return nullptr;
}

static char *c_anonymous_type_target(const char *file_path, uint32_t start_byte)
{
    char suffix[48];

    (void)snprintf(suffix, sizeof(suffix), "#anonymous-%u", start_byte);
    return java_append_name_suffix(file_path, suffix);
}

static bool c_register_type_spec(CParseContext *ctx, TSNode node, const char *fallback)
{
    CTypeSpec *entry;
    const char *kind = c_specifier_kind(node);
    TSNode name_node;
    char *name;
    char *target;

    if (kind == nullptr) {
        return true;
    }
    if (c_type_spec_for_node(ctx, node) != nullptr) {
        return true;
    }
    name_node = ts_node_child_by_field_name(node, "name", 4U);
    name = ts_node_is_null(name_node) ? nullptr : node_text(ctx->source, name_node);
    if (name != nullptr) {
        target = copy_cstr(name);
    } else if ((fallback != nullptr) && (fallback[0] != '\0')) {
        target = copy_cstr(fallback);
    } else {
        target = c_anonymous_type_target(ctx->file->file_path, ts_node_start_byte(node));
    }
    if (target == nullptr) {
        return false;
    }
    if ((ctx->type_count == ctx->type_capacity) &&
        !parser_heap_grow((void **)&ctx->types,
                          &ctx->type_capacity,
                          sizeof(*ctx->types),
                          16U)) {
        return false;
    }
    entry = &ctx->types[ctx->type_count++];
    entry->start_byte = ts_node_start_byte(node);
    entry->target = target;
    entry->kind = kind;
    entry->declared_name = name;
    return true;
}

static char *c_type_target_from_node(CParseContext *ctx, TSNode node)
{
    const char *type;

    if (ts_node_is_null(node)) {
        return copy_cstr("");
    }
    type = ts_node_type(node);
    if ((strcmp(type, "struct_specifier") == 0) || (strcmp(type, "union_specifier") == 0) ||
        (strcmp(type, "enum_specifier") == 0)) {
        CTypeSpec *spec = c_type_spec_for_node(ctx, node);
        TSNode name_node = ts_node_child_by_field_name(node, "name", 4U);

        if (spec != nullptr) {
            return copy_cstr(spec->target);
        }
        return ts_node_is_null(name_node) ? copy_cstr("") : node_text(ctx->source, name_node);
    }
    if (strcmp(type, "type_identifier") == 0) {
        char *name = node_text(ctx->source, node);
        const char *target = name == nullptr ? nullptr : c_alias_target(ctx, name);

        return target == nullptr ? name : copy_cstr(target);
    }
    if ((strcmp(type, "primitive_type") == 0) || (strcmp(type, "void_type") == 0)) {
        return copy_cstr("");
    }
    {
        TSNode inner = ts_node_child_by_field_name(node, "type", 4U);

        if (!ts_node_is_null(inner) && !ts_node_eq(inner, node)) {
            return c_type_target_from_node(ctx, inner);
        }
    }
    for (uint32_t i = 0U; i < ts_node_named_child_count(node); i++) {
        TSNode child = ts_node_named_child(node, i);
        const char *child_type = ts_node_type(child);

        if ((strstr(child_type, "type") != nullptr) ||
            (strstr(child_type, "specifier") != nullptr)) {
            return c_type_target_from_node(ctx, child);
        }
    }
    return copy_cstr("");
}

static bool c_collect_types(CParseContext *ctx, TSNode node)
{
    if (c_node_is(node, "type_definition")) {
        TSNode type_node = ts_node_child_by_field_name(node, "type", 4U);
        TSNode first_alias_node = (TSNode){0};
        char *first_alias = nullptr;

        for (uint32_t i = 0U; i < ts_node_named_child_count(node); i++) {
            const char *field_name = ts_node_field_name_for_named_child(node, i);

            if ((field_name != nullptr) && (strcmp(field_name, "declarator") == 0)) {
                first_alias_node = c_declarator_name(ts_node_named_child(node, i));
                if (!ts_node_is_null(first_alias_node)) {
                    first_alias = node_text(ctx->source, first_alias_node);
                    break;
                }
            }
        }
        if ((c_specifier_kind(type_node) != nullptr) &&
            !c_register_type_spec(ctx, type_node, first_alias)) {
            return false;
        }
        {
            char *underlying = c_type_target_from_node(ctx, type_node);
            const char *canonical =
                (underlying != nullptr) && (underlying[0] != '\0') ? underlying : first_alias;

            for (uint32_t i = 0U; i < ts_node_named_child_count(node); i++) {
                const char *field_name = ts_node_field_name_for_named_child(node, i);

                if ((field_name != nullptr) && (strcmp(field_name, "declarator") == 0)) {
                    TSNode alias_node = c_declarator_name(ts_node_named_child(node, i));
                    char *alias = ts_node_is_null(alias_node) ? nullptr
                                                              : node_text(ctx->source, alias_node);
                    const char *target = canonical == nullptr ? alias : canonical;

                    if ((alias != nullptr) && !c_push_alias(ctx, alias, copy_cstr(target))) {
                        return false;
                    }
                }
            }
        }
    } else if ((c_specifier_kind(node) != nullptr) && !c_register_type_spec(ctx, node, nullptr)) {
        return false;
    }

    for (uint32_t i = 0U; i < ts_node_named_child_count(node); i++) {
        if (!c_collect_types(ctx, ts_node_named_child(node, i))) {
            return false;
        }
    }
    return true;
}

static bool c_has_storage(const CParseContext *ctx, TSNode node, const char *storage)
{
    for (uint32_t i = 0U; i < ts_node_named_child_count(node); i++) {
        TSNode child = ts_node_named_child(node, i);

        if (c_node_is(child, "storage_class_specifier") &&
            node_text_equals(ctx->source, child, storage, strlen(storage))) {
            return true;
        }
    }
    return false;
}

static bool c_line_doc_bounds(const char *source,
                              size_t line_start,
                              size_t line_end,
                              size_t *out_text_start,
                              size_t *out_text_end)
{
    size_t left = line_start;
    size_t right = line_end;

    while ((left < right) && ((source[left] == ' ') || (source[left] == '\t'))) {
        left++;
    }
    while ((right > left) && ((source[right - 1U] == '\r') ||
                              (source[right - 1U] == ' ') ||
                              (source[right - 1U] == '\t'))) {
        right--;
    }
    if ((right - left < 3U) || (source[left] != '/') || (source[left + 1U] != '/') ||
        ((source[left + 2U] != '/') && (source[left + 2U] != '!'))) {
        return false;
    }
    left += 3U;
    if ((left < right) && (source[left] == ' ')) {
        left++;
    }
    *out_text_start = left;
    *out_text_end = right;
    return true;
}

static char *c_doc_before(const char *source, uint32_t declaration_start)
{
    char *block_doc = java_doc_before(source, declaration_start);
    size_t end = (size_t)declaration_start;
    size_t first_line;
    StringBuilder out = {0};

    if (block_doc != nullptr) {
        return block_doc;
    }
    while ((end > 0U) && ((source[end - 1U] == ' ') || (source[end - 1U] == '\t') ||
                          (source[end - 1U] == '\r') || (source[end - 1U] == '\n'))) {
        end--;
    }
    if (end == 0U) {
        return nullptr;
    }
    first_line = end;
    while ((first_line > 0U) && (source[first_line - 1U] != '\n')) {
        first_line--;
    }
    {
        size_t text_start;
        size_t text_end;

        if (!c_line_doc_bounds(source, first_line, end, &text_start, &text_end)) {
            return nullptr;
        }
    }
    for (;;) {
        size_t previous_end;
        size_t previous_start;
        size_t text_start;
        size_t text_end;

        if (first_line == 0U) {
            break;
        }
        previous_end = first_line - 1U;
        previous_start = previous_end;
        while ((previous_start > 0U) && (source[previous_start - 1U] != '\n')) {
            previous_start--;
        }
        if (!c_line_doc_bounds(source,
                               previous_start,
                               previous_end,
                               &text_start,
                               &text_end)) {
            break;
        }
        first_line = previous_start;
    }

    for (size_t line_start = first_line; line_start < end;) {
        size_t line_end = line_start;
        size_t text_start;
        size_t text_end;

        while ((line_end < end) && (source[line_end] != '\n')) {
            line_end++;
        }
        if (c_line_doc_bounds(source, line_start, line_end, &text_start, &text_end)) {
            if ((out.len > 0U) && !sb_append(&out, "\n")) {
                sb_free(&out);
                return nullptr;
            }
            if ((text_end > text_start) &&
                !sb_append_len(&out, source + text_start, text_end - text_start)) {
                sb_free(&out);
                return nullptr;
            }
        }
        line_start = line_end < end ? line_end + 1U : end;
    }
    return out.data;
}

static bool c_add_definition(CParseContext *ctx,
                             TSNode declaration,
                             TSNode name_node,
                             const char *kind,
                             const char *namespace_name,
                             bool header_only)
{
    CodeLensSymbol *symbol;

    if (ts_node_is_null(name_node) || (namespace_name == nullptr) || !reserve_symbols(ctx->file)) {
        return false;
    }
    symbol = &ctx->file->symbols[ctx->file->symbol_count];
    (void)memset(symbol, 0, sizeof(*symbol));
    symbol->kind = copy_cstr(kind);
    symbol->name = node_text(ctx->source, name_node);
    symbol->namespace_name = copy_cstr(namespace_name);
    symbol->doc = c_doc_before(ctx->source, ts_node_start_byte(declaration));
    symbol->source = header_only ? java_definition_source(ctx->source, declaration, true)
                                 : node_text(ctx->source, declaration);
    symbol->start_line = ts_node_start_point(declaration).row + 1U;
    symbol->end_line = ts_node_end_point(declaration).row + 1U;
    if ((symbol->kind == nullptr) || (symbol->name == nullptr) ||
        (symbol->namespace_name == nullptr) || (symbol->source == nullptr)) {
        (void)memset(symbol, 0, sizeof(*symbol));
        return false;
    }
    ctx->file->symbol_count++;
    return true;
}

static bool c_store_binding(CParseContext *ctx,
                            TSNode name_node,
                            const char *type_target,
                            const char *symbol_namespace,
                            uint32_t scope_start,
                            uint32_t scope_end,
                            bool has_symbol,
                            bool global_scope)
{
    CBinding *binding;
    char *name;

    if (ts_node_is_null(name_node)) {
        return true;
    }
    name = node_text(ctx->source, name_node);
    if (name == nullptr) {
        return false;
    }
    if ((ctx->binding_count == ctx->binding_capacity) &&
        !parser_heap_grow((void **)&ctx->bindings,
                          &ctx->binding_capacity,
                          sizeof(*ctx->bindings),
                          32U)) {
        return false;
    }
    binding = &ctx->bindings[ctx->binding_count++];
    binding->name = name;
    binding->type_target = copy_cstr(type_target == nullptr ? "" : type_target);
    binding->symbol_namespace =
        copy_cstr(symbol_namespace == nullptr ? "" : symbol_namespace);
    binding->declaration_byte = ts_node_start_byte(name_node);
    binding->scope_start = scope_start;
    binding->scope_end = scope_end;
    binding->has_symbol = has_symbol;
    binding->global_scope = global_scope;
    return (binding->type_target != nullptr) && (binding->symbol_namespace != nullptr);
}

static const CBinding *c_find_binding(const CParseContext *ctx,
                                      const char *name,
                                      uint32_t at_byte)
{
    const CBinding *best = nullptr;

    for (size_t i = 0U; i < ctx->binding_count; i++) {
        const CBinding *binding = &ctx->bindings[i];

        if ((strcmp(binding->name, name) != 0) || (at_byte < binding->scope_start) ||
            (at_byte > binding->scope_end) ||
            (!binding->global_scope && (binding->declaration_byte > at_byte))) {
            continue;
        }
        if ((best == nullptr) || (binding->scope_start > best->scope_start) ||
            ((binding->scope_start == best->scope_start) &&
             (binding->declaration_byte > best->declaration_byte))) {
            best = binding;
        }
    }
    return best;
}

static bool c_store_field_type(CParseContext *ctx,
                               const char *owner,
                               TSNode name_node,
                               const char *type_target)
{
    CFieldType *field;

    if ((owner == nullptr) || ts_node_is_null(name_node)) {
        return true;
    }
    if ((ctx->field_count == ctx->field_capacity) &&
        !parser_heap_grow((void **)&ctx->fields,
                          &ctx->field_capacity,
                          sizeof(*ctx->fields),
                          32U)) {
        return false;
    }
    field = &ctx->fields[ctx->field_count++];
    field->owner = copy_cstr(owner);
    field->name = node_text(ctx->source, name_node);
    field->type_target = copy_cstr(type_target == nullptr ? "" : type_target);
    return (field->owner != nullptr) && (field->name != nullptr) &&
           (field->type_target != nullptr);
}

static const char *c_field_type(const CParseContext *ctx,
                                const char *owner,
                                const char *name)
{
    for (size_t i = ctx->field_count; i > 0U; i--) {
        const CFieldType *field = &ctx->fields[i - 1U];

        if ((strcmp(field->owner, owner) == 0) && (strcmp(field->name, name) == 0)) {
            return field->type_target;
        }
    }
    return nullptr;
}

static bool c_add_parameter_bindings(CParseContext *ctx,
                                     TSNode node,
                                     uint32_t scope_start,
                                     uint32_t scope_end)
{
    if (c_node_is(node, "parameter_declaration")) {
        TSNode type_node = ts_node_child_by_field_name(node, "type", 4U);
        TSNode declarator = ts_node_child_by_field_name(node, "declarator", 10U);
        TSNode name = c_declarator_name(declarator);
        char *target = c_type_target_from_node(ctx, type_node);

        return c_store_binding(ctx,
                               name,
                               target,
                               "",
                               scope_start,
                               scope_end,
                               false,
                               false);
    }
    for (uint32_t i = 0U; i < ts_node_named_child_count(node); i++) {
        if (!c_add_parameter_bindings(ctx,
                                      ts_node_named_child(node, i),
                                      scope_start,
                                      scope_end)) {
            return false;
        }
    }
    return true;
}

static bool c_add_declaration_items(CParseContext *ctx,
                                    TSNode declaration,
                                    bool inside_function,
                                    uint32_t scope_start,
                                    uint32_t scope_end)
{
    TSNode type_node = ts_node_child_by_field_name(declaration, "type", 4U);
    char *type_target = c_type_target_from_node(ctx, type_node);
    bool is_static = c_has_storage(ctx, declaration, "static");
    const char *symbol_namespace = is_static ? ctx->file->file_path : "";

    if (type_target == nullptr) {
        return false;
    }
    for (uint32_t i = 0U; i < ts_node_named_child_count(declaration); i++) {
        const char *field_name = ts_node_field_name_for_named_child(declaration, i);
        TSNode declarator;
        TSNode name;
        bool function;

        if ((field_name == nullptr) || (strcmp(field_name, "declarator") != 0)) {
            continue;
        }
        declarator = ts_node_named_child(declaration, i);
        name = c_declarator_name(declarator);
        function = c_declarator_is_function(declarator);
        if (ts_node_is_null(name)) {
            continue;
        }
        if (function) {
            if (!inside_function &&
                !c_add_definition(ctx,
                                  declaration,
                                  name,
                                  "function",
                                  symbol_namespace,
                                  false)) {
                return false;
            }
        } else {
            if (!inside_function &&
                !c_add_definition(ctx,
                                  declaration,
                                  name,
                                  "variable",
                                  symbol_namespace,
                                  false)) {
                return false;
            }
            if (!c_store_binding(ctx,
                                 name,
                                 type_target,
                                 symbol_namespace,
                                 inside_function ? scope_start : 0U,
                                 inside_function ? scope_end : (uint32_t)ctx->source_len,
                                 !inside_function,
                                 !inside_function)) {
                return false;
            }
        }
    }
    return true;
}

static bool c_add_typedef_symbols(CParseContext *ctx, TSNode definition)
{
    for (uint32_t i = 0U; i < ts_node_named_child_count(definition); i++) {
        const char *field_name = ts_node_field_name_for_named_child(definition, i);

        if ((field_name != nullptr) && (strcmp(field_name, "declarator") == 0)) {
            TSNode name = c_declarator_name(ts_node_named_child(definition, i));

            if (!ts_node_is_null(name) &&
                !c_add_definition(ctx, definition, name, "typedef", "", false)) {
                return false;
            }
        }
    }
    return true;
}

static bool c_extract_declarations(CParseContext *ctx,
                                   TSNode node,
                                   const char *current_type,
                                   bool inside_function,
                                   uint32_t scope_start,
                                   uint32_t scope_end)
{
    const char *type = ts_node_type(node);
    const char *child_type = current_type;
    bool child_inside_function = inside_function;
    uint32_t child_scope_start = scope_start;
    uint32_t child_scope_end = scope_end;
    const char *specifier_kind = c_specifier_kind(node);

    if (specifier_kind != nullptr) {
        CTypeSpec *spec = c_type_spec_for_node(ctx, node);
        TSNode body = ts_node_child_by_field_name(node, "body", 4U);
        TSNode name = ts_node_child_by_field_name(node, "name", 4U);

        if (spec == nullptr) {
            return false;
        }
        child_type = spec->target;
        if (!ts_node_is_null(name)) {
            TSNode parent = ts_node_parent(node);
            bool forward_declaration = false;

            if (ts_node_is_null(body) &&
                (c_node_is(parent, "translation_unit") ||
                 c_node_is(parent, "type_definition") ||
                 c_node_is(parent, "preproc_if") ||
                 c_node_is(parent, "preproc_ifdef") ||
                 c_node_is(parent, "preproc_else"))) {
                forward_declaration = true;
            } else if (ts_node_is_null(body) && c_node_is(parent, "declaration")) {
                forward_declaration = true;
                for (uint32_t i = 0U; i < ts_node_named_child_count(parent); i++) {
                    const char *field_name = ts_node_field_name_for_named_child(parent, i);

                    if ((field_name != nullptr) && (strcmp(field_name, "declarator") == 0)) {
                        forward_declaration = false;
                    }
                }
            }
            if ((!ts_node_is_null(body) || forward_declaration) &&
                !c_add_definition(ctx,
                                  forward_declaration &&
                                          (c_node_is(parent, "declaration") ||
                                           c_node_is(parent, "type_definition"))
                                      ? parent
                                      : node,
                                  name,
                                  specifier_kind,
                                  "",
                                  !forward_declaration)) {
                return false;
            }
        }
    } else if (strcmp(type, "type_definition") == 0) {
        if (!c_add_typedef_symbols(ctx, node)) {
            return false;
        }
    } else if (strcmp(type, "function_definition") == 0) {
        TSNode declarator = ts_node_child_by_field_name(node, "declarator", 10U);
        TSNode function_decl = c_function_declarator(declarator);
        TSNode name = c_declarator_name(declarator);
        TSNode body = ts_node_child_by_field_name(node, "body", 4U);
        bool is_static = c_has_storage(ctx, node, "static");
        const char *namespace_name = is_static ? ctx->file->file_path : "";

        if (ts_node_is_null(name) ||
            !c_add_definition(ctx, node, name, "function", namespace_name, false)) {
            return false;
        }
        child_inside_function = true;
        child_scope_start = ts_node_start_byte(node);
        child_scope_end = ts_node_end_byte(node);
        if (!ts_node_is_null(body)) {
            child_scope_start = ts_node_start_byte(body);
            child_scope_end = ts_node_end_byte(body);
        }
        if (!ts_node_is_null(function_decl) &&
            !c_add_parameter_bindings(ctx,
                                      function_decl,
                                      child_scope_start,
                                      child_scope_end)) {
            return false;
        }
    } else if (strcmp(type, "declaration") == 0) {
        if (!c_add_declaration_items(ctx,
                                     node,
                                     inside_function,
                                     scope_start,
                                     scope_end)) {
            return false;
        }
    } else if (strcmp(type, "field_declaration") == 0) {
        TSNode type_node = ts_node_child_by_field_name(node, "type", 4U);
        char *field_target = c_type_target_from_node(ctx, type_node);

        if (field_target == nullptr) {
            return false;
        }
        for (uint32_t i = 0U; i < ts_node_named_child_count(node); i++) {
            const char *field_name = ts_node_field_name_for_named_child(node, i);

            if ((field_name != nullptr) && (strcmp(field_name, "declarator") == 0)) {
                TSNode name = c_declarator_name(ts_node_named_child(node, i));

                if (!ts_node_is_null(name) &&
                    (!c_add_definition(ctx, node, name, "field", current_type, false) ||
                     !c_store_field_type(ctx, current_type, name, field_target))) {
                    return false;
                }
                if (!ts_node_is_null(name)) {
                    for (size_t alias_index = 0U; alias_index < ctx->alias_count;
                         alias_index++) {
                        const CTypeAlias *alias = &ctx->aliases[alias_index];

                        if ((strcmp(alias->target, current_type) == 0) &&
                            (strcmp(alias->name, current_type) != 0) &&
                            (!c_add_definition(ctx,
                                              node,
                                              name,
                                              "field",
                                              alias->name,
                                              false) ||
                             !c_store_field_type(ctx,
                                                 alias->name,
                                                 name,
                                                 field_target))) {
                            return false;
                        }
                    }
                }
            }
        }
    } else if (strcmp(type, "enumerator") == 0) {
        TSNode name = ts_node_child_by_field_name(node, "name", 4U);

        if (!c_add_definition(ctx, node, name, "enum_constant", "", false)) {
            return false;
        }
    } else if ((strcmp(type, "preproc_def") == 0) ||
               (strcmp(type, "preproc_function_def") == 0)) {
        TSNode name = ts_node_child_by_field_name(node, "name", 4U);

        if (!c_add_definition(ctx, node, name, "macro", "", false)) {
            return false;
        }
    } else if (strcmp(type, "compound_statement") == 0) {
        child_scope_start = ts_node_start_byte(node);
        child_scope_end = ts_node_end_byte(node);
    } else if (strcmp(type, "for_statement") == 0) {
        child_scope_start = ts_node_start_byte(node);
        child_scope_end = ts_node_end_byte(node);
    }

    for (uint32_t i = 0U; i < ts_node_named_child_count(node); i++) {
        if (!c_extract_declarations(ctx,
                                    ts_node_named_child(node, i),
                                    child_type,
                                    child_inside_function,
                                    child_scope_start,
                                    child_scope_end)) {
            return false;
        }
    }
    return true;
}

static bool c_declarator_name_node(TSNode node)
{
    TSNode current = node;

    for (size_t depth = 0U; depth < 32U; depth++) {
        TSNode parent = ts_node_parent(current);
        TSNode declarator;
        const char *parent_type;

        if (ts_node_is_null(parent)) {
            return false;
        }
        parent_type = ts_node_type(parent);
        declarator = ts_node_child_by_field_name(parent, "declarator", 10U);
        if (!ts_node_is_null(declarator) && ts_node_eq(declarator, current)) {
            if ((strcmp(parent_type, "declaration") == 0) ||
                (strcmp(parent_type, "function_definition") == 0) ||
                (strcmp(parent_type, "parameter_declaration") == 0) ||
                (strcmp(parent_type, "type_definition") == 0) ||
                (strcmp(parent_type, "field_declaration") == 0)) {
                return true;
            }
            current = parent;
            continue;
        }
        if (strcmp(parent_type, "parenthesized_declarator") == 0) {
            current = parent;
            continue;
        }
        return false;
    }
    return false;
}

static bool c_reference_name_field(TSNode node)
{
    TSNode parent = ts_node_parent(node);
    const char *parent_type = ts_node_type(parent);

    if (c_declarator_name_node(node)) {
        return true;
    }
    if (((strcmp(parent_type, "preproc_def") == 0) ||
         (strcmp(parent_type, "preproc_function_def") == 0) ||
         (strcmp(parent_type, "enumerator") == 0)) &&
        c_node_field_equals(parent, "name", node)) {
        return true;
    }
    return false;
}

static bool c_add_reference_span(CParseContext *ctx,
                                 uint32_t start,
                                 uint32_t end,
                                 TSNode base_node,
                                 const char *target_namespace)
{
    CodeLensReference *reference;
    uint32_t base_start = ts_node_start_byte(base_node);
    uint32_t base_end = ts_node_end_byte(base_node);
    TSPoint point = ts_node_start_point(base_node);

    if ((end <= start) || (base_start < start) || (base_end <= base_start) ||
        (base_end > end) || ((size_t)end > ctx->source_len) || !reserve_references(ctx->file)) {
        return false;
    }
    reference = &ctx->file->references[ctx->file->reference_count];
    (void)memset(reference, 0, sizeof(*reference));
    reference->symbol = copy_bytes(ctx->source + start, (size_t)(end - start));
    reference->symbol_len = (size_t)(end - start);
    reference->target_namespace = copy_cstr(target_namespace == nullptr ? "" : target_namespace);
    if ((reference->symbol == nullptr) || (reference->target_namespace == nullptr)) {
        return false;
    }
    reference->base = reference->symbol + (base_start - start);
    reference->base_len = (size_t)(base_end - base_start);
    reference->target_namespace_len = strlen(reference->target_namespace);
    reference->line = point.row + 1U;
    reference->column = point.column + 1U;
    reference->start_byte = base_start;
    reference->end_byte = base_end;
    reference->symbol_hash = term_hash(reference->symbol, reference->symbol_len);
    reference->base_hash = term_hash(reference->base, reference->base_len);
    reference->target_namespace_hash =
        term_hash(reference->target_namespace, reference->target_namespace_len);
    ctx->file->reference_count++;
    return true;
}

static bool c_symbol_kind_is_value(const char *kind)
{
    return (strcmp(kind, "function") == 0) || (strcmp(kind, "variable") == 0) ||
           (strcmp(kind, "macro") == 0) || (strcmp(kind, "enum_constant") == 0);
}

static const char *c_same_file_symbol_namespace(const CParseContext *ctx,
                                                const char *name,
                                                bool values_only)
{
    const char *global = nullptr;

    for (size_t i = ctx->file->symbol_count; i > 0U; i--) {
        const CodeLensSymbol *symbol = &ctx->file->symbols[i - 1U];

        if ((strcmp(symbol->name, name) != 0) ||
            (values_only && !c_symbol_kind_is_value(symbol->kind))) {
            continue;
        }
        if (strcmp(symbol->namespace_name, ctx->file->file_path) == 0) {
            return symbol->namespace_name;
        }
        if (symbol->namespace_name[0] == '\0') {
            global = symbol->namespace_name;
        }
    }
    return global;
}

static TSNode c_last_identifier(TSNode node)
{
    TSNode found = (TSNode){0};
    const char *type = ts_node_type(node);

    if ((strcmp(type, "identifier") == 0) || (strcmp(type, "field_identifier") == 0) ||
        (strcmp(type, "type_identifier") == 0)) {
        found = node;
    }
    for (uint32_t i = 0U; i < ts_node_named_child_count(node); i++) {
        TSNode child = ts_node_named_child(node, i);
        TSNode nested = c_last_identifier(child);

        if (!ts_node_is_null(nested)) {
            found = nested;
        }
    }
    return found;
}

static char *c_expression_type(CParseContext *ctx, TSNode expression)
{
    const char *type = ts_node_type(expression);

    if (strcmp(type, "identifier") == 0) {
        char *name = node_text(ctx->source, expression);
        const CBinding *binding =
            name == nullptr ? nullptr : c_find_binding(ctx, name, ts_node_start_byte(expression));

        return binding == nullptr ? copy_cstr("") : copy_cstr(binding->type_target);
    }
    if (strcmp(type, "field_expression") == 0) {
        TSNode argument = ts_node_child_by_field_name(expression, "argument", 8U);
        TSNode field_node = ts_node_child_by_field_name(expression, "field", 5U);
        char *owner = c_expression_type(ctx, argument);
        char *field_name = ts_node_is_null(field_node) ? nullptr : node_text(ctx->source, field_node);
        const char *target = ((owner == nullptr) || (field_name == nullptr))
                                 ? nullptr
                                 : c_field_type(ctx, owner, field_name);

        return target == nullptr ? copy_cstr("") : copy_cstr(target);
    }
    if (strcmp(type, "cast_expression") == 0) {
        TSNode type_node = ts_node_child_by_field_name(expression, "type", 4U);

        return c_type_target_from_node(ctx, type_node);
    }
    {
        TSNode argument = ts_node_child_by_field_name(expression, "argument", 8U);

        if (!ts_node_is_null(argument)) {
            return c_expression_type(ctx, argument);
        }
    }
    if (ts_node_named_child_count(expression) == 1U) {
        return c_expression_type(ctx, ts_node_named_child(expression, 0U));
    }
    return copy_cstr("");
}

static bool c_add_call_reference(CParseContext *ctx, TSNode node)
{
    TSNode function = ts_node_child_by_field_name(node, "function", 8U);
    TSNode base = c_last_identifier(function);
    char *name;
    const CBinding *binding;
    const char *target;

    if (ts_node_is_null(function) || ts_node_is_null(base) ||
        c_node_is(function, "field_expression")) {
        return true;
    }
    name = node_text(ctx->source, base);
    if (name == nullptr) {
        return false;
    }
    binding = c_find_binding(ctx, name, ts_node_start_byte(base));
    if (binding != nullptr) {
        if (!binding->has_symbol) {
            return true;
        }
        target = binding->symbol_namespace;
    } else {
        target = c_same_file_symbol_namespace(ctx, name, true);
        if (target == nullptr) {
            target = "";
        }
    }
    return c_add_reference_span(ctx,
                                ts_node_start_byte(function),
                                ts_node_end_byte(function),
                                base,
                                target);
}

static bool c_add_field_reference(CParseContext *ctx, TSNode node)
{
    TSNode argument = ts_node_child_by_field_name(node, "argument", 8U);
    TSNode field = ts_node_child_by_field_name(node, "field", 5U);
    char *target;

    if (ts_node_is_null(argument) || ts_node_is_null(field)) {
        return true;
    }
    target = c_expression_type(ctx, argument);
    if (target == nullptr) {
        return false;
    }
    return c_add_reference_span(ctx,
                                ts_node_start_byte(argument),
                                ts_node_end_byte(field),
                                field,
                                target);
}

static bool c_add_type_reference(CParseContext *ctx, TSNode node)
{
    TSNode parent = ts_node_parent(node);
    const char *parent_type = ts_node_type(parent);

    if (c_declarator_name_node(node)) {
        return true;
    }
    if (((strcmp(parent_type, "struct_specifier") == 0) ||
         (strcmp(parent_type, "union_specifier") == 0) ||
         (strcmp(parent_type, "enum_specifier") == 0)) &&
        c_node_field_equals(parent, "name", node)) {
        TSNode body = ts_node_child_by_field_name(parent, "body", 4U);
        TSNode declaration = ts_node_parent(parent);
        bool definition = !ts_node_is_null(body);

        if (!definition &&
            (c_node_is(declaration, "translation_unit") ||
             c_node_is(declaration, "type_definition") ||
             c_node_is(declaration, "preproc_if") ||
             c_node_is(declaration, "preproc_ifdef") ||
             c_node_is(declaration, "preproc_else"))) {
            definition = true;
        } else if (!definition && c_node_is(declaration, "declaration")) {
            definition = true;
            for (uint32_t i = 0U; i < ts_node_named_child_count(declaration); i++) {
                const char *field_name =
                    ts_node_field_name_for_named_child(declaration, i);

                if ((field_name != nullptr) && (strcmp(field_name, "declarator") == 0)) {
                    definition = false;
                }
            }
        }
        if (definition) {
            return true;
        }
    }
    return c_add_reference_span(ctx,
                                ts_node_start_byte(node),
                                ts_node_end_byte(node),
                                node,
                                "");
}

static bool c_add_identifier_reference(CParseContext *ctx, TSNode node)
{
    TSNode parent = ts_node_parent(node);
    char *name;
    const CBinding *binding;
    const char *target;

    if (c_reference_name_field(node) ||
        (c_node_is(parent, "call_expression") && c_node_field_equals(parent, "function", node))) {
        return true;
    }
    name = node_text(ctx->source, node);
    if (name == nullptr) {
        return false;
    }
    binding = c_find_binding(ctx, name, ts_node_start_byte(node));
    if (binding != nullptr) {
        if (!binding->has_symbol) {
            return true;
        }
        target = binding->symbol_namespace;
    } else {
        target = c_same_file_symbol_namespace(ctx, name, true);
        if (target == nullptr) {
            target = "";
        }
    }
    return c_add_reference_span(ctx,
                                ts_node_start_byte(node),
                                ts_node_end_byte(node),
                                node,
                                target);
}

static bool c_extract_references(CParseContext *ctx, TSNode node)
{
    const char *type = ts_node_type(node);

    if ((strcmp(type, "preproc_def") == 0) ||
        (strcmp(type, "preproc_function_def") == 0) ||
        (strcmp(type, "preproc_include") == 0)) {
        return true;
    }
    if ((strcmp(type, "call_expression") == 0) && !c_add_call_reference(ctx, node)) {
        return false;
    }
    if ((strcmp(type, "field_expression") == 0) && !c_add_field_reference(ctx, node)) {
        return false;
    }
    if ((strcmp(type, "type_identifier") == 0) && !c_add_type_reference(ctx, node)) {
        return false;
    }
    if ((strcmp(type, "identifier") == 0) && !c_add_identifier_reference(ctx, node)) {
        return false;
    }

    for (uint32_t i = 0U; i < ts_node_named_child_count(node); i++) {
        TSNode child = ts_node_named_child(node, i);

        if (c_node_is(node, "field_expression") &&
            c_node_field_equals(node, "field", child)) {
            continue;
        }
        if (!c_extract_references(ctx, child)) {
            return false;
        }
    }
    return true;
}

CodeLensCParser *code_lens_c_parser_new(void)
{
    CodeLensCParser *parser = code_lens_alloc(sizeof(*parser));

    if (parser == nullptr) {
        return nullptr;
    }
    parser->parser = ts_parser_new();
    if (parser->parser == nullptr) {
        return nullptr;
    }
    if (!ts_parser_set_language(parser->parser, tree_sitter_c())) {
        ts_parser_delete(parser->parser);
        return nullptr;
    }
    return parser;
}

void code_lens_c_parser_delete(CodeLensCParser *parser)
{
    if (parser != nullptr) {
        ts_parser_delete(parser->parser);
    }
}

int code_lens_parse_c_source_with_parser(CodeLensCParser *parser,
                                          const char *file_path,
                                          const char *source,
                                          size_t source_len,
                                          CodeLensCFile *out_file)
{
    TSTree *tree;
    TSNode root;
    CParseContext ctx;
    bool ok;

    if ((parser == nullptr) || (parser->parser == nullptr) || (file_path == nullptr) ||
        (source == nullptr) || (out_file == nullptr) || (source_len > UINT32_MAX)) {
        return -1;
    }
    (void)memset(out_file, 0, sizeof(*out_file));
    out_file->file_path = copy_cstr(file_path);
    out_file->namespace_name = copy_cstr("");
    if ((out_file->file_path == nullptr) || (out_file->namespace_name == nullptr)) {
        return -1;
    }
    tree = ts_parser_parse_string(parser->parser, nullptr, source, (uint32_t)source_len);
    if (tree == nullptr) {
        return -1;
    }
    root = ts_tree_root_node(tree);
    (void)memset(&ctx, 0, sizeof(ctx));
    ctx.source = source;
    ctx.source_len = source_len;
    ctx.file = out_file;

    ok = c_collect_types(&ctx, root) &&
         c_extract_declarations(&ctx, root, "", false, 0U, (uint32_t)source_len) &&
         c_extract_references(&ctx, root);

    free(ctx.aliases);
    free(ctx.types);
    free(ctx.bindings);
    free(ctx.fields);
    ts_tree_delete(tree);
    return ok ? 0 : -1;
}

int code_lens_parse_c_source(const char *file_path,
                              const char *source,
                              size_t source_len,
                              CodeLensCFile *out_file)
{
    CodeLensCParser *parser = code_lens_c_parser_new();
    int rc;

    if (parser == nullptr) {
        return -1;
    }
    rc = code_lens_parse_c_source_with_parser(parser,
                                               file_path,
                                               source,
                                               source_len,
                                               out_file);
    code_lens_c_parser_delete(parser);
    return rc;
}

static CodeLensSourceParser *code_lens_source_parser_new(void)
{
    CodeLensSourceParser *parser = code_lens_alloc_zeroed(1U, sizeof(*parser));

    if (parser == nullptr) {
        return nullptr;
    }
    parser->clojure = code_lens_clojure_parser_new();
    parser->java = code_lens_java_parser_new();
    parser->c = code_lens_c_parser_new();
    if ((parser->clojure == nullptr) || (parser->java == nullptr) || (parser->c == nullptr)) {
        if (parser->clojure != nullptr) {
            code_lens_clojure_parser_delete(parser->clojure);
        }
        if (parser->java != nullptr) {
            code_lens_java_parser_delete(parser->java);
        }
        if (parser->c != nullptr) {
            code_lens_c_parser_delete(parser->c);
        }
        return nullptr;
    }
    return parser;
}

static void code_lens_source_parser_delete(CodeLensSourceParser *parser)
{
    if (parser == nullptr) {
        return;
    }
    code_lens_clojure_parser_delete(parser->clojure);
    code_lens_java_parser_delete(parser->java);
    code_lens_c_parser_delete(parser->c);
}

static int code_lens_parse_source_with_parser(CodeLensSourceParser *parser,
                                               const char *file_path,
                                               const char *source,
                                               size_t source_len,
                                               CodeLensSourceFile *out_file)
{
    if (has_java_extension(file_path)) {
        return code_lens_parse_java_source_with_parser(parser->java,
                                                        file_path,
                                                        source,
                                                        source_len,
                                                        out_file);
    }
    if (has_c_extension(file_path)) {
        return code_lens_parse_c_source_with_parser(parser->c,
                                                     file_path,
                                                     source,
                                                     source_len,
                                                     out_file);
    }
    return code_lens_parse_clojure_source_with_parser(parser->clojure,
                                                       file_path,
                                                       source,
                                                       source_len,
                                                       out_file);
}

/* Indexer */

#define DEFAULT_MAX_INDEX_THREADS 8U
#define MAX_INDEX_THREADS 64U

typedef enum {
    IDX_TABLE_REPO,
    IDX_TABLE_FILE,
    IDX_TABLE_SYMBOL,
    IDX_TABLE_REF,
    IDX_TABLE_KEYWORD,
    IDX_TABLE_ALIAS,
    IDX_TABLE_REFERRED,
    IDX_TABLE_COUNT
} IndexTable;

typedef struct {
    sqlite3_stmt *stmts[IDX_TABLE_COUNT];
    /* 16-row VALUES batches for the two high-volume tables; remainder rows
     * (fewer than 16 left in a file) drain through the single-row stmts. */
    sqlite3_stmt *ref_batch;
    sqlite3_stmt *keyword_batch;
} IndexWriters;

/* String interner backing the Term dictionary. Emission is single-threaded,
 * so no locking; slot text pointers reference arena-backed memory that
 * outlives the build (the same guarantee SQLITE_STATIC binds rely on). */
typedef struct {
    const char *text;
    uint32_t len;
    int64_t id;
} TermSlot;

typedef struct {
    TermSlot *slots;
    size_t capacity;
    size_t count;
    sqlite3_stmt *insert_stmt;
} TermInterner;

typedef struct {
    bool enabled;
    double total_start_seconds;
    double db_open_seconds;
    double walk_seconds;
    double collect_seconds;
    double read_seconds;
    double parse_seconds;
    double parallel_parse_wall_seconds;
    double file_write_seconds;
    double symbol_write_seconds;
    double reference_write_seconds;
    double keyword_write_seconds;
    double alias_write_seconds;
    double secondary_index_seconds;
    double fts_seconds;
    double commit_seconds;
    double publish_seconds;
    double repo_write_seconds;
} IndexProfile;

typedef struct {
    bool checked;
    bool stale;
    bool refresh_attempted;
    bool refreshed;
    size_t checked_files;
    size_t missing_files;
    size_t changed_files;
    size_t new_files;
    bool maven_changed;
    bool dependency_sources_changed;
    /* A failed/disabled dependency-only generation can be retried by an
     * explicit index without making its already-current workspace rows
     * unsafe to serve. */
    bool dependency_retry_due;
    double elapsed_seconds;
    double refresh_elapsed_seconds;
} StalenessStatus;

/* Concrete path sets behind a StalenessStatus, collected on request by the
 * same walk/stat pass: indexed files whose stat signature changed, indexed
 * files that no longer stat, and on-disk supported source files the index lacks.
 * Paths in changed/missing are File.path values; added paths come from the
 * directory walk (absolute). All arena-backed. */
typedef struct {
    CodeLensPathList changed;
    CodeLensPathList missing;
    CodeLensPathList added;
} RepoDiff;

typedef struct FtsSidecar FtsSidecar;

/* Mirror of the secondary-index key columns, captured during emission so the
 * indexes can be written bottom-up as raw b-tree pages after COMMIT instead
 * of paying SQLite's insert machinery a second time. Array position doubles
 * as the rowid: Ref/Keyword rowids are the 0-based global emission ordinals
 * and Symbol rowids are 1..N in insert order. Text pointers reference
 * arena-backed memory that outlives the build. */
typedef struct {
    bool active;
    uint32_t *ref_terms;
    size_t ref_count;
    size_t ref_cap;
    uint32_t *keyword_terms;
    size_t keyword_count;
    size_t keyword_cap;
    const char **symbol_names;
    size_t symbol_count;
    size_t symbol_cap;
} RawIndexMirror;

/* Streaming raw table b-tree writer. Rows arrive in strictly increasing
 * rowid order (every table's rowids are dense emission ordinals), so leaf
 * pages fill append-only during the walk; interior levels are built at
 * finalize. Pages use local numbers 1..N inside the tree's own buffer, and
 * every stored page-number field records its byte offset in fixups so the
 * graft can rebase the tree once the final file layout is known. */
typedef struct {
    uint8_t *data;
    size_t page_count;
    size_t page_cap;
    uint64_t *fixups;
    size_t fixup_count;
    size_t fixup_cap;
    /* open leaf state; leaf_pgno == 0 means no open leaf */
    uint32_t leaf_pgno;
    uint32_t leaf_cells;
    uint32_t leaf_top;
    int64_t last_rowid;
    bool has_rows;
    /* per-page (local pgno, max rowid) items pending for the parent level */
    uint32_t *item_pgnos;
    int64_t *item_keys;
    size_t item_count;
    size_t item_cap;
    uint32_t root_local;
} RawTableTree;

/* Everything the raw table emitter accumulates during the walk: one
 * streaming b-tree per table plus the TEXT primary keys needed to build the
 * sqlite_autoindex trees afterwards (arena-backed, rowid = position + 1). */
enum {
    CP_TREE_FILE,
    CP_TREE_SYMBOL,
    CP_TREE_TERM,
    CP_TREE_REFDATA,
    CP_TREE_KEYWORDDATA,
    CP_TREE_ALIAS,
    CP_TREE_REFERRED,
    CP_TREE_AUTO_FILE,
    CP_TREE_AUTO_SYMBOL,
    CP_TREE_AUTO_ALIAS,
    CP_TREE_AUTO_REFERRED,
    CP_TREE_IDX_SYMBOL_NAME,
    CP_TREE_IDX_REF_BASE,
    CP_TREE_IDX_KEYWORD_BASE,
    CP_TREE_IDX_TERM_TEXT,
    /* FTS shadow tables grafted from the in-memory sidecar after the main
     * graft; their roots are written only when the sidecar encode
     * succeeded (root_set gates the in-place overwrite). */
    CP_TREE_FTS_DATA,
    CP_TREE_FTS_DOCSIZE,
    CP_TREE_COUNT
};

typedef struct {
    bool active;
    uint32_t schema_roots[CP_TREE_COUNT];
    RawTableTree file;
    RawTableTree symbol;
    RawTableTree term;
    RawTableTree refdata;
    RawTableTree keyworddata;
    RawTableTree alias;
    RawTableTree referred;
    const char **file_ids;
    size_t file_id_count;
    size_t file_id_cap;
    const char **symbol_ids;
    size_t symbol_id_count;
    size_t symbol_id_cap;
    const char **alias_ids;
    size_t alias_id_count;
    size_t alias_id_cap;
    const char **referred_ids;
    size_t referred_id_count;
    size_t referred_id_cap;
    uint8_t *scratch;
    size_t scratch_cap;
} RawEmitter;

typedef struct {
    const char *repo_path;
    CodeLensIndexStats stats;
    IndexProfile *profile;
    CodeLensSourceParser *parser;
    GitBlobSource *git_source;
    GitBlobReader *git_reader;
    IndexWriters writers;
    TermInterner interner;
    /* rowid of the most recently inserted File row; Ref/Keyword rows for the
     * file being emitted point at it via their fileId column. */
    int64_t current_file_rowid;
    /* When non-null, every Symbol row is mirrored to the FTS sidecar thread;
     * next_symbol_rowid tracks the rowid SQLite assigned (fresh table,
     * single-threaded sequential inserts, so rowids are 1..N in order). */
    FtsSidecar *fts_sidecar;
    int64_t next_symbol_rowid;
    /* First Symbol rowid minus one for rows emitted by this run: 0 on a
     * full build (rowids are 1..N), the surviving maximum on an
     * incremental refresh. File.symbolFirst derives from it. */
    int64_t symbol_rowid_base;
    RawIndexMirror raw_mirror;
    RawEmitter raw_emit;
} IndexContext;

static double profile_now_seconds(void)
{
    struct timespec ts;

#ifdef CLOCK_MONOTONIC
    if (clock_gettime(CLOCK_MONOTONIC, &ts) == 0) {
        return (double)ts.tv_sec + ((double)ts.tv_nsec / 1000000000.0);
    }
#endif

    if (timespec_get(&ts, TIME_UTC) == TIME_UTC) {
        return (double)ts.tv_sec + ((double)ts.tv_nsec / 1000000000.0);
    }
    return 0.0;
}

static void profile_init(IndexProfile *profile)
{
    const char *enabled = getenv("CODE_LENS_PROFILE");

    (void)memset(profile, 0, sizeof(*profile));
    profile->enabled = (enabled != nullptr) && (enabled[0] != '\0') &&
                       (strcmp(enabled, "0") != 0);
    if (profile->enabled) {
        profile->total_start_seconds = profile_now_seconds();
    }
}

static double profile_start(const IndexProfile *profile)
{
    return ((profile != nullptr) && profile->enabled) ? profile_now_seconds() : 0.0;
}

static void profile_add(IndexProfile *profile, double *phase_seconds, double start_seconds)
{
    if ((profile != nullptr) && profile->enabled) {
        *phase_seconds += profile_now_seconds() - start_seconds;
    }
}

/* Emit-path work counters, printed under CODE_LENS_PROFILE=1 so emit-thread
 * changes can be judged by how much interning and leaf-encoding work moved,
 * not just by wall clock. Relaxed atomics per the git_blob_reads precedent;
 * no per-row timing, which would distort the path being measured. */
static atomic_uint_fast64_t term_intern_calls;
static atomic_uint_fast64_t term_intern_misses;
static atomic_uint_fast64_t raw_leaf_closes;

static void profile_report(const IndexProfile *profile, const CodeLensIndexStats *stats)
{
    double total;

    if ((profile == nullptr) || !profile->enabled) {
        return;
    }

    total = profile_now_seconds() - profile->total_start_seconds;
    (void)fprintf(stderr, "code-lens profile: total %.6fs\n", total);
    (void)fprintf(stderr, "code-lens profile: db_open %.6fs\n", profile->db_open_seconds);
    (void)fprintf(stderr,
                  "code-lens profile: walk %.6fs collect %.6fs read %.6fs parse_extract %.6fs "
                  "parallel_parse_wall %.6fs file_write %.6fs symbol_write %.6fs "
                  "reference_write %.6fs keyword_write %.6fs alias_write %.6fs\n",
                  profile->walk_seconds,
                  profile->collect_seconds,
                  profile->read_seconds,
                  profile->parse_seconds,
                  profile->parallel_parse_wall_seconds,
                  profile->file_write_seconds,
                  profile->symbol_write_seconds,
                  profile->reference_write_seconds,
                  profile->keyword_write_seconds,
                  profile->alias_write_seconds);
    (void)fprintf(stderr,
                  "code-lens profile: repo_write %.6fs secondary_indexes %.6fs "
                  "fts %.6fs commit %.6fs publish %.6fs\n",
                  profile->repo_write_seconds,
                  profile->secondary_index_seconds,
                  profile->fts_seconds,
                  profile->commit_seconds,
                  profile->publish_seconds);
    if (stats != nullptr) {
        (void)fprintf(stderr,
                      "code-lens profile: counts %zu files %zu symbols %zu references "
                      "%zu keywords %zu aliases\n",
                      stats->file_count,
                      stats->symbol_count,
                      stats->reference_count,
                      stats->keyword_count,
                      stats->alias_count);
    }
    (void)fprintf(stderr,
                  "code-lens profile: reader_fallbacks %" PRIuFAST64 "\n",
                  (uint_fast64_t)atomic_load_explicit(&clojure_reader_fallbacks,
                                                      memory_order_relaxed));
    (void)fprintf(stderr,
                  "code-lens profile: git_blob_reads %" PRIuFAST64 " git_file_reads %" PRIuFAST64
                  "\n",
                  (uint_fast64_t)atomic_load_explicit(&git_blob_reads, memory_order_relaxed),
                  (uint_fast64_t)atomic_load_explicit(&git_file_reads, memory_order_relaxed));
    (void)fprintf(stderr,
                  "code-lens profile: term_intern_calls %" PRIuFAST64
                  " term_intern_misses %" PRIuFAST64 " raw_leaf_closes %" PRIuFAST64 "\n",
                  (uint_fast64_t)atomic_load_explicit(&term_intern_calls, memory_order_relaxed),
                  (uint_fast64_t)atomic_load_explicit(&term_intern_misses, memory_order_relaxed),
                  (uint_fast64_t)atomic_load_explicit(&raw_leaf_closes, memory_order_relaxed));
}

static char *alloc_printf(const char *format, ...)
{
    va_list args;
    va_list copy;
    int needed;
    char *text;

    va_start(args, format);
    va_copy(copy, args);
    needed = vsnprintf(nullptr, 0, format, copy);
    va_end(copy);
    if (needed < 0) {
        va_end(args);
        return nullptr;
    }

    text = code_lens_alloc((size_t)needed + 1U);
    if (text == nullptr) {
        va_end(args);
        return nullptr;
    }

    (void)vsnprintf(text, (size_t)needed + 1U, format, args);
    va_end(args);
    return text;
}

/* One statement block creates the whole index schema. user_version is the
 * format stamp checked by read opens; bump CODE_LENS_INDEX_FORMAT_VERSION
 * when the schema changes so old indexes report "re-index required" instead
 * of being misread. */
#define CODE_LENS_STRINGIZE_VALUE(x) #x
#define CODE_LENS_STRINGIZE(x) CODE_LENS_STRINGIZE_VALUE(x)

/* Shared between the staging schema and the FTS sidecar database; the two
 * declarations must be identical for the sidecar's shadow tables to be a
 * valid drop-in for the staging table's. */
#define CODE_LENS_SYMBOL_FTS_DDL                                                \
    "CREATE VIRTUAL TABLE SymbolFts USING fts5(name, namespace, filePath, doc," \
    " content, content='Symbol', content_rowid='rowid',"                        \
    " tokenize = 'unicode61 separators ''-./_''')"

static const char index_schema_sql[] =
    "PRAGMA user_version = " CODE_LENS_STRINGIZE(CODE_LENS_INDEX_FORMAT_VERSION) ";"
    "CREATE TABLE Repo (path TEXT PRIMARY KEY, indexedAt TEXT,"
    " fileCount INTEGER, symbolCount INTEGER, referenceCount INTEGER,"
    " keywordCount INTEGER, aliasCount INTEGER);"
    /* The First/Count column pairs record each file's contiguous id ranges
     * in RefData/KeywordData (global emission ordinals) and Symbol (rowids):
     * incremental re-indexing deletes a file's rows with primary-key range
     * scans instead of full-table scans. Values are known before the
     * file's rows emit, from the running counters. */
    "CREATE TABLE File (id TEXT PRIMARY KEY, repo TEXT, path TEXT, namespace TEXT,"
    " size INTEGER, mtimeSec INTEGER, mtimeNsec INTEGER,"
    " refFirst INTEGER, refCount INTEGER, keywordFirst INTEGER, keywordCount INTEGER,"
    " symbolFirst INTEGER, symbolCount INTEGER);"
    "CREATE TABLE Symbol (id TEXT PRIMARY KEY, repo TEXT, name TEXT, kind TEXT,"
    " namespace TEXT, filePath TEXT, startLine INTEGER, endLine INTEGER,"
    " content TEXT, doc TEXT);"
    /* Ref ids are global emission ordinals, so INTEGER PRIMARY KEY makes the
     * id the rowid: 452k-row loads append to the table b-tree instead of
     * churning a separate text-key index. Hot rows are deliberately narrow:
     * fileId references File.rowid, and the repo, file path, and source
     * namespace all come from the joined File row instead of being repeated
     * as TEXT on every reference. Reference and keyword text is dictionary
     * encoded: the corpus has ~11x symbol and ~27x keyword repetition, so
     * RefData/KeywordData store integer ids into the shared Term table and
     * the Ref/Keyword views below reconstruct the classic text columns.
     * symbol keeps the reference text verbatim; symbolBase is the part
     * after the last '/', and targetNamespace is the require-alias-resolved
     * (or verbatim) qualifier, '' for unqualified references. */
    "CREATE TABLE Term (id INTEGER PRIMARY KEY, text TEXT);"
    "CREATE TABLE RefData (id INTEGER PRIMARY KEY, fileId INTEGER,"
    " symbolTerm INTEGER, lineNumber INTEGER, columnNumber INTEGER,"
    " startByte INTEGER, endByte INTEGER, symbolBaseTerm INTEGER,"
    " targetNamespaceTerm INTEGER);"
    "CREATE TABLE KeywordData (id INTEGER PRIMARY KEY, fileId INTEGER,"
    " keywordTerm INTEGER, lineNumber INTEGER, columnNumber INTEGER,"
    " keywordBaseTerm INTEGER, qualifierTerm INTEGER, targetNamespaceTerm INTEGER);"
    /* Compatibility views keep the public query surface (CLI sql tool,
     * readers, tests) identical to the pre-dictionary schema. SQLite
     * flattens these simple join views into outer queries, so predicates
     * like symbolBase = ? become an idx_term_text lookup plus an
     * idx_ref_symbol_base probe. filePath joins File so sql-tool users get
     * the path without knowing that fileId is File.rowid (File.id is a
     * TEXT key, so the natural-looking f.id = fileId join silently matches
     * nothing); LEFT JOIN on the rowid lets SQLite drop the join entirely
     * for queries that never touch filePath. */
    "CREATE VIEW Ref AS SELECT r.id AS id, r.fileId AS fileId,"
    " ts.text AS symbol, r.lineNumber AS lineNumber,"
    " r.columnNumber AS columnNumber, r.startByte AS startByte,"
    " r.endByte AS endByte, tb.text AS symbolBase, tn.text AS targetNamespace,"
    " f.path AS filePath"
    " FROM RefData r JOIN Term ts ON ts.id = r.symbolTerm"
    " JOIN Term tb ON tb.id = r.symbolBaseTerm"
    " JOIN Term tn ON tn.id = r.targetNamespaceTerm"
    " LEFT JOIN File f ON f.rowid = r.fileId;"
    "CREATE VIEW Keyword AS SELECT k.id AS id, k.fileId AS fileId,"
    " tk.text AS keyword, k.lineNumber AS lineNumber,"
    " k.columnNumber AS columnNumber, tb.text AS keywordBase,"
    " tq.text AS qualifier, tn.text AS targetNamespace,"
    " f.path AS filePath"
    " FROM KeywordData k JOIN Term tk ON tk.id = k.keywordTerm"
    " JOIN Term tb ON tb.id = k.keywordBaseTerm"
    " JOIN Term tq ON tq.id = k.qualifierTerm"
    " JOIN Term tn ON tn.id = k.targetNamespaceTerm"
    " LEFT JOIN File f ON f.rowid = k.fileId;"
    "CREATE TABLE Alias (id TEXT PRIMARY KEY, repo TEXT, filePath TEXT,"
    " namespace TEXT, alias TEXT);"
    /* Symbols made visible unqualified by :refer / :refer :all / (:use ...);
     * the sentinel symbol ':all' stands for whole-namespace referral. Read
     * by context's unqualified-reference filter. */
    "CREATE TABLE Referred (id TEXT PRIMARY KEY, repo TEXT, filePath TEXT,"
    " namespace TEXT, symbol TEXT);"
    /* Dependency-source metadata is deliberately separate from the hot
     * File/Symbol/Ref tables. Dependency source files use the same parser and
     * index rows as workspace files; these legacy-named tables identify their
     * origin and retain the build-input snapshot used to decide when Maven,
     * Leiningen, or tools.deps must run again. */
    "CREATE TABLE MavenProject (repo TEXT PRIMARY KEY, rootPom TEXT, toolsDepsAliases TEXT, status TEXT,"
    " resolvedAt TEXT, message TEXT);"
    "CREATE TABLE MavenInput (id TEXT PRIMARY KEY, repo TEXT, path TEXT, size INTEGER,"
    " mtimeSec INTEGER, mtimeNsec INTEGER);"
    "CREATE TABLE DependencyArtifact (id TEXT PRIMARY KEY, repo TEXT, coordinate TEXT,"
    " groupId TEXT, artifactId TEXT, version TEXT, scope TEXT, direct INTEGER,"
    " sourceJar TEXT, sourceRoot TEXT, checksum TEXT);"
    "CREATE TABLE DependencyFile (id TEXT PRIMARY KEY, repo TEXT, filePath TEXT,"
    " artifactId TEXT, sourcePath TEXT, modulePath TEXT);"
    "CREATE INDEX idx_maven_input_repo_path ON MavenInput(repo, path);"
    "CREATE INDEX idx_dependency_artifact_coordinate ON DependencyArtifact(repo, coordinate);"
    "CREATE INDEX idx_dependency_file_path ON DependencyFile(repo, filePath);"
    "CREATE INDEX idx_dependency_file_artifact ON DependencyFile(artifactId);"
    /* Secondary indexes on Symbol/Ref/Keyword are created after the bulk
     * load (see index_secondary_indexes_sql): one sort-based build beats
     * incremental b-tree maintenance across ~700k streamed inserts. */
    /* External-content FTS over Symbol avoids duplicating row storage.
     * unicode61 with extra separators splits Clojure names on - . / _ so
     * name parts match as words. Trailing-* prefix terms are answered from
     * the main term index; dedicated prefix indexes were measured to cost
     * ~10x the FTS build time for a worst-case query win of a few
     * milliseconds at this corpus size. Populated by the FTS sidecar thread
     * during the bulk load (or by an in-place FTS 'rebuild' when the
     * sidecar is disabled or fails); the DDL is shared with the sidecar
     * database, which must build byte-compatible segments. */
    CODE_LENS_SYMBOL_FTS_DDL ";";

static int create_index_schema(CodeLensDb *db)
{
    return code_lens_db_exec(db, index_schema_sql);
}

/* Individual DDL statements are shared between the classic CREATE INDEX path
 * and the raw page writer, which stores the same text verbatim in
 * sqlite_master so both paths produce an identical schema. */
#define CP_IDX_SYMBOL_NAME_DDL "CREATE INDEX idx_symbol_name ON Symbol(name)"
#define CP_IDX_REF_BASE_DDL "CREATE INDEX idx_ref_symbol_base ON RefData(symbolBaseTerm)"
#define CP_IDX_KEYWORD_BASE_DDL "CREATE INDEX idx_keyword_base ON KeywordData(keywordBaseTerm)"
#define CP_IDX_TERM_TEXT_DDL "CREATE UNIQUE INDEX idx_term_text ON Term(text)"

/* Built once after the bulk load, inside the load transaction, so each index
 * is a single sorted build instead of per-insert b-tree maintenance. */
static const char index_secondary_indexes_sql[] = CP_IDX_SYMBOL_NAME_DDL ";" CP_IDX_REF_BASE_DDL
    ";" CP_IDX_KEYWORD_BASE_DDL ";" CP_IDX_TERM_TEXT_DDL ";";

static int create_secondary_indexes(CodeLensDb *db)
{
    return code_lens_db_exec(db, index_secondary_indexes_sql);
}

static const char *const index_insert_sql[IDX_TABLE_COUNT] = {
    [IDX_TABLE_REPO] = "INSERT INTO Repo (path, indexedAt, fileCount,"
                       " symbolCount, referenceCount, keywordCount, aliasCount)"
                       " VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
    [IDX_TABLE_FILE] = "INSERT INTO File (id, repo, path, namespace, size, mtimeSec,"
                       " mtimeNsec, refFirst, refCount, keywordFirst, keywordCount,"
                       " symbolFirst, symbolCount)"
                       " VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)",
    [IDX_TABLE_SYMBOL] = "INSERT INTO Symbol (id, repo, name, kind, namespace,"
                         " filePath, startLine, endLine, content, doc)"
                         " VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)",
    [IDX_TABLE_REF] = "INSERT INTO RefData (id, fileId, symbolTerm, lineNumber,"
                      " columnNumber, startByte, endByte, symbolBaseTerm,"
                      " targetNamespaceTerm)"
                      " VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
    [IDX_TABLE_KEYWORD] = "INSERT INTO KeywordData (id, fileId, keywordTerm, lineNumber,"
                          " columnNumber, keywordBaseTerm, qualifierTerm,"
                          " targetNamespaceTerm)"
                          " VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
    [IDX_TABLE_ALIAS] = "INSERT INTO Alias (id, repo, filePath, namespace, alias)"
                        " VALUES (?1, ?2, ?3, ?4, ?5)",
    [IDX_TABLE_REFERRED] = "INSERT INTO Referred (id, repo, filePath, namespace, symbol)"
                           " VALUES (?1, ?2, ?3, ?4, ?5)",
};

/* Multi-row INSERT batching for Ref and Keyword: one statement carries 16
 * rows, cutting per-row step/reset overhead on the two tables that hold
 * hundreds of thousands of rows. Parameters are unnumbered so they bind
 * sequentially row after row. */
#define CODE_LENS_INSERT_BATCH_ROWS 16
#define CP_REF_ROW "(?, ?, ?, ?, ?, ?, ?, ?, ?)"
#define CP_REF_ROW_4 CP_REF_ROW ", " CP_REF_ROW ", " CP_REF_ROW ", " CP_REF_ROW
#define CP_KW_ROW "(?, ?, ?, ?, ?, ?, ?, ?)"
#define CP_KW_ROW_4 CP_KW_ROW ", " CP_KW_ROW ", " CP_KW_ROW ", " CP_KW_ROW

static const char ref_batch_insert_sql[] =
    "INSERT INTO RefData (id, fileId, symbolTerm, lineNumber, columnNumber,"
    " startByte, endByte, symbolBaseTerm, targetNamespaceTerm) VALUES " CP_REF_ROW_4
    ", " CP_REF_ROW_4 ", " CP_REF_ROW_4 ", " CP_REF_ROW_4;

static const char keyword_batch_insert_sql[] =
    "INSERT INTO KeywordData (id, fileId, keywordTerm, lineNumber, columnNumber,"
    " keywordBaseTerm, qualifierTerm, targetNamespaceTerm) VALUES " CP_KW_ROW_4
    ", " CP_KW_ROW_4 ", " CP_KW_ROW_4 ", " CP_KW_ROW_4;

static void finalize_index_writers(IndexWriters *writers)
{
    if (writers == nullptr) {
        return;
    }
    for (size_t i = 0U; i < IDX_TABLE_COUNT; i++) {
        if (writers->stmts[i] != nullptr) {
            (void)sqlite3_finalize(writers->stmts[i]);
            writers->stmts[i] = nullptr;
        }
    }
    if (writers->ref_batch != nullptr) {
        (void)sqlite3_finalize(writers->ref_batch);
        writers->ref_batch = nullptr;
    }
    if (writers->keyword_batch != nullptr) {
        (void)sqlite3_finalize(writers->keyword_batch);
        writers->keyword_batch = nullptr;
    }
}

static int prepare_index_writers(CodeLensDb *db, IndexWriters *writers)
{
    if ((db == nullptr) || (writers == nullptr)) {
        return -1;
    }
    (void)memset(writers, 0, sizeof(*writers));

    for (size_t i = 0U; i < IDX_TABLE_COUNT; i++) {
        if (db_prepare(db, index_insert_sql[i], &writers->stmts[i]) != 0) {
            finalize_index_writers(writers);
            return -1;
        }
    }
    if ((db_prepare(db, ref_batch_insert_sql, &writers->ref_batch) != 0) ||
        (db_prepare(db, keyword_batch_insert_sql, &writers->keyword_batch) != 0)) {
        finalize_index_writers(writers);
        return -1;
    }
    return 0;
}

/* Executes one prepared INSERT and rearms it for the next row. Bound values
 * use SQLITE_STATIC because the backing memory is arena-allocated and
 * outlives the step. */
static int writer_step(sqlite3_stmt *stmt)
{
    if (sqlite3_step(stmt) != SQLITE_DONE) {
        (void)report_sqlite_error(sqlite3_db_handle(stmt), "insert row");
        (void)sqlite3_reset(stmt);
        return -1;
    }
    (void)sqlite3_reset(stmt);
    return 0;
}

#define TERM_INTERNER_INITIAL_CAPACITY 131072U

/* term_hash is defined in the parser section (the workers precompute term
 * hashes for every reference and keyword they extract). */

static int term_interner_init(CodeLensDb *db, TermInterner *interner, bool with_stmt)
{
    (void)memset(interner, 0, sizeof(*interner));
    interner->slots = calloc(TERM_INTERNER_INITIAL_CAPACITY, sizeof(TermSlot));
    if (interner->slots == nullptr) {
        return -1;
    }
    interner->capacity = TERM_INTERNER_INITIAL_CAPACITY;
    if (with_stmt &&
        (db_prepare(db, "INSERT INTO Term (id, text) VALUES (?1, ?2)", &interner->insert_stmt) !=
         0)) {
        free(interner->slots);
        (void)memset(interner, 0, sizeof(*interner));
        return -1;
    }
    return 0;
}

/* Releases the prepared statement ahead of closing the build database while
 * keeping the slots alive; the raw index writer reads them after COMMIT. */
static void term_interner_finalize_stmt(TermInterner *interner)
{
    if (interner != nullptr) {
        (void)sqlite3_finalize(interner->insert_stmt);
        interner->insert_stmt = nullptr;
    }
}

static void term_interner_destroy(TermInterner *interner)
{
    if (interner == nullptr) {
        return;
    }
    free(interner->slots);
    (void)sqlite3_finalize(interner->insert_stmt);
    (void)memset(interner, 0, sizeof(*interner));
}

static int term_interner_grow(TermInterner *interner)
{
    size_t new_capacity = interner->capacity * 2U;
    TermSlot *new_slots = calloc(new_capacity, sizeof(TermSlot));

    if (new_slots == nullptr) {
        return -1;
    }
    for (size_t i = 0U; i < interner->capacity; i++) {
        const TermSlot *slot = &interner->slots[i];

        if (slot->text != nullptr) {
            size_t j = (size_t)(term_hash(slot->text, slot->len) & (new_capacity - 1U));

            while (new_slots[j].text != nullptr) {
                j = (j + 1U) & (new_capacity - 1U);
            }
            new_slots[j] = *slot;
        }
    }
    free(interner->slots);
    interner->slots = new_slots;
    interner->capacity = new_capacity;
    return 0;
}

/* Returns the Term id for text, inserting a new Term row on first sight.
 * text must be arena-backed (it is retained in the map for the whole build);
 * the empty string is a valid, heavily used term (unqualified namespaces).
 * hash must be term_hash(text, len); the parse workers precompute it so the
 * emit thread never hashes. */
static int term_intern(TermInterner *interner,
                       const char *text,
                       size_t len,
                       uint64_t hash,
                       int64_t *out_id)
{
    size_t i;

    if ((text == nullptr) || (len > UINT32_MAX)) {
        return -1;
    }
    atomic_fetch_add_explicit(&term_intern_calls, 1U, memory_order_relaxed);
    if ((interner->count * 4U) >= (interner->capacity * 3U)) {
        if (term_interner_grow(interner) != 0) {
            return -1;
        }
    }

    i = (size_t)(hash & (interner->capacity - 1U));
    while (interner->slots[i].text != nullptr) {
        const TermSlot *slot = &interner->slots[i];

        if ((slot->len == (uint32_t)len) && (memcmp(slot->text, text, len) == 0)) {
            *out_id = slot->id;
            return 0;
        }
        i = (i + 1U) & (interner->capacity - 1U);
    }

    interner->count++;
    atomic_fetch_add_explicit(&term_intern_misses, 1U, memory_order_relaxed);
    interner->slots[i].text = text;
    interner->slots[i].len = (uint32_t)len;
    interner->slots[i].id = (int64_t)interner->count;
    /* With the raw table emitter the Term table is written as raw pages at
     * graft time, so there is no insert statement to feed here. */
    if (interner->insert_stmt != nullptr) {
        if ((sqlite3_bind_int64(interner->insert_stmt, 1, (int64_t)interner->count) !=
             SQLITE_OK) ||
            (bind_text_len(interner->insert_stmt, 2, text, len) != 0)) {
            return -1;
        }
        if (writer_step(interner->insert_stmt) != 0) {
            return -1;
        }
    }
    *out_id = (int64_t)interner->count;
    return 0;
}

/* Loads an existing Term table into the interner so an incremental build
 * interns against the published dictionary and appends new terms with
 * contiguous ids. Fails (forcing a full rebuild) if the stored ids are not
 * the contiguous 1..N first-sight ordinals every builder produces. */
static int term_interner_preload(CodeLensDb *db, TermInterner *interner)
{
    sqlite3_stmt *stmt = nullptr;
    int rc;

    if (db_prepare(db, "SELECT id, text FROM Term ORDER BY id", &stmt) != 0) {
        return -1;
    }
    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
        int64_t id = sqlite3_column_int64(stmt, 0);
        const unsigned char *text = sqlite3_column_text(stmt, 1);
        int bytes = sqlite3_column_bytes(stmt, 1);
        char *copy;
        size_t i;

        if ((id != (int64_t)(interner->count + 1U)) || (text == nullptr) || (bytes < 0)) {
            (void)sqlite3_finalize(stmt);
            return -1;
        }
        if (((interner->count * 4U) >= (interner->capacity * 3U)) &&
            (term_interner_grow(interner) != 0)) {
            (void)sqlite3_finalize(stmt);
            return -1;
        }
        copy = copy_bytes((const char *)text, (size_t)bytes);
        if (copy == nullptr) {
            (void)sqlite3_finalize(stmt);
            return -1;
        }
        i = (size_t)(term_hash(copy, (size_t)bytes) & (interner->capacity - 1U));
        while (interner->slots[i].text != nullptr) {
            i = (i + 1U) & (interner->capacity - 1U);
        }
        interner->count++;
        interner->slots[i].text = copy;
        interner->slots[i].len = (uint32_t)bytes;
        interner->slots[i].id = id;
    }
    (void)sqlite3_finalize(stmt);
    return rc == SQLITE_DONE ? 0 : -1;
}

/* Raw secondary-index writer: after COMMIT, the four secondary indexes are
 * built bottom-up as raw SQLite b-tree pages from the key columns mirrored
 * during emission, appended to the database file, and adopted by inserting
 * their sqlite_master rows with writable_schema=ON. This skips SQLite's
 * per-row insert machinery (the dominant cost of CREATE INDEX at this
 * scale) while producing a file every stock SQLite can read; the DDL text
 * stored in sqlite_master is byte-identical to the classic path. Any
 * failure restores the file and falls back to plain CREATE INDEX, so the
 * writer can never affect correctness. CODE_LENS_RAW_INDEXES=0 disables
 * it. */
#define CP_RAW_PAGE_SIZE 8192U
/* (usable - 12) * 64 / 255 - 23: largest index payload stored without
 * overflow pages, which this writer does not emit. */
#define CP_RAW_MAX_LOCAL 2030U
#define CP_RAW_MAX_CELLS 2048U
#define CP_RAW_HDR_PAGE_COUNT_OFFSET 28U

static bool raw_indexes_enabled(void)
{
    const char *value = getenv("CODE_LENS_RAW_INDEXES");

    return (value == nullptr) || (value[0] == '\0') || (strcmp(value, "0") != 0);
}

static int raw_u32_push(uint32_t **items, size_t *count, size_t *cap, uint32_t value)
{
    if (*count == *cap) {
        size_t new_cap = *cap == 0U ? 4096U : (*cap * 2U);
        uint32_t *grown = realloc(*items, new_cap * sizeof(uint32_t));

        if (grown == nullptr) {
            return -1;
        }
        *items = grown;
        *cap = new_cap;
    }
    (*items)[(*count)++] = value;
    return 0;
}

static int raw_mirror_push_term(uint32_t **items, size_t *count, size_t *cap, int64_t term)
{
    if ((term < 0) || (term > (int64_t)UINT32_MAX)) {
        return -1;
    }
    return raw_u32_push(items, count, cap, (uint32_t)term);
}

static int raw_mirror_push_symbol(RawIndexMirror *mirror, const char *name)
{
    if (name == nullptr) {
        return -1;
    }
    if (mirror->symbol_count == mirror->symbol_cap) {
        size_t new_cap = mirror->symbol_cap == 0U ? 4096U : (mirror->symbol_cap * 2U);
        const char **grown = realloc(mirror->symbol_names, new_cap * sizeof(const char *));

        if (grown == nullptr) {
            return -1;
        }
        mirror->symbol_names = grown;
        mirror->symbol_cap = new_cap;
    }
    mirror->symbol_names[mirror->symbol_count++] = name;
    return 0;
}

static void raw_mirror_destroy(RawIndexMirror *mirror)
{
    free(mirror->ref_terms);
    free(mirror->keyword_terms);
    free(mirror->symbol_names);
    (void)memset(mirror, 0, sizeof(*mirror));
}

static void raw_store_be(uint8_t *out, uint64_t value, size_t len)
{
    for (size_t i = 0U; i < len; i++) {
        out[i] = (uint8_t)(value >> (8U * (len - 1U - i)));
    }
}

static size_t raw_varint_len(uint64_t value)
{
    size_t len = 1U;

    while (value >= 0x80U) {
        value >>= 7U;
        len++;
    }
    return len;
}

/* Writes a SQLite varint; values written here fit well within 8 groups. */
static size_t raw_varint_put(uint8_t *out, uint64_t value)
{
    uint8_t groups[8];
    size_t count = 0U;

    do {
        groups[count++] = (uint8_t)(value & 0x7FU);
        value >>= 7U;
    } while ((value != 0U) && (count < 8U));
    if (value != 0U) {
        return 0U;
    }
    for (size_t i = 0U; i < count; i++) {
        uint8_t group = groups[count - 1U - i];

        out[i] = (i + 1U) < count ? (uint8_t)(group | 0x80U) : group;
    }
    return count;
}

/* Smallest SQLite integer serial type for a value, plus its body length. */
static uint32_t raw_int_serial_type(int64_t value, size_t *out_body_len)
{
    if (value == 0) {
        *out_body_len = 0U;
        return 8U;
    }
    if (value == 1) {
        *out_body_len = 0U;
        return 9U;
    }
    if ((value >= -128) && (value <= 127)) {
        *out_body_len = 1U;
        return 1U;
    }
    if ((value >= -32768) && (value <= 32767)) {
        *out_body_len = 2U;
        return 2U;
    }
    if ((value >= -8388608) && (value <= 8388607)) {
        *out_body_len = 3U;
        return 3U;
    }
    if ((value >= INT32_MIN) && (value <= INT32_MAX)) {
        *out_body_len = 4U;
        return 4U;
    }
    if ((value >= -140737488355328LL) && (value <= 140737488355327LL)) {
        *out_body_len = 6U;
        return 5U;
    }
    *out_body_len = 8U;
    return 6U;
}

/* Encodes an index record of (integer key, rowid); the header is always
 * three bytes because both serial types are single-digit. */
static size_t raw_record_int_pair(uint8_t *out, int64_t key, int64_t rowid)
{
    size_t key_len;
    size_t rowid_len;
    uint32_t key_type = raw_int_serial_type(key, &key_len);
    uint32_t rowid_type = raw_int_serial_type(rowid, &rowid_len);
    size_t pos = 0U;

    out[pos++] = 3U;
    out[pos++] = (uint8_t)key_type;
    out[pos++] = (uint8_t)rowid_type;
    raw_store_be(out + pos, (uint64_t)key, key_len);
    pos += key_len;
    raw_store_be(out + pos, (uint64_t)rowid, rowid_len);
    pos += rowid_len;
    return pos;
}

/* Encodes an index record of (text key, rowid). The caller bounds len so
 * the whole header stays under the one-byte-varint limit. */
static size_t raw_record_text_key(uint8_t *out, const char *text, uint32_t len, int64_t rowid)
{
    size_t rowid_len;
    uint32_t rowid_type = raw_int_serial_type(rowid, &rowid_len);
    uint64_t text_type = 13U + (2U * (uint64_t)len);
    size_t pos = 1U;

    pos += raw_varint_put(out + pos, text_type);
    out[pos++] = (uint8_t)rowid_type;
    out[0] = (uint8_t)pos;
    (void)memcpy(out + pos, text, len);
    pos += len;
    raw_store_be(out + pos, (uint64_t)rowid, rowid_len);
    pos += rowid_len;
    return pos;
}

/* Sorted index payloads, packed back to back; payload i spans
 * [offsets[i], offsets[i + 1]) in bytes. */
typedef struct {
    uint8_t *bytes;
    size_t bytes_len;
    size_t bytes_cap;
    uint32_t *offsets;
    size_t count;
    size_t offsets_cap;
} RawPayloads;

static void raw_payloads_reset(RawPayloads *payloads)
{
    payloads->bytes_len = 0U;
    payloads->count = 0U;
}

static void raw_payloads_destroy(RawPayloads *payloads)
{
    free(payloads->bytes);
    free(payloads->offsets);
    (void)memset(payloads, 0, sizeof(*payloads));
}

static int raw_payloads_append(RawPayloads *payloads, const uint8_t *record, size_t len)
{
    if ((payloads->count + 2U) > payloads->offsets_cap) {
        size_t new_cap = payloads->offsets_cap == 0U ? 8192U : (payloads->offsets_cap * 2U);
        uint32_t *grown = realloc(payloads->offsets, new_cap * sizeof(uint32_t));

        if (grown == nullptr) {
            return -1;
        }
        payloads->offsets = grown;
        payloads->offsets_cap = new_cap;
    }
    if ((payloads->bytes_len + len) > payloads->bytes_cap) {
        size_t new_cap = payloads->bytes_cap == 0U ? (1U << 20U) : (payloads->bytes_cap * 2U);
        uint8_t *grown;

        while (new_cap < (payloads->bytes_len + len)) {
            new_cap *= 2U;
        }
        grown = realloc(payloads->bytes, new_cap);
        if (grown == nullptr) {
            return -1;
        }
        payloads->bytes = grown;
        payloads->bytes_cap = new_cap;
    }
    if ((payloads->bytes_len + len) > (size_t)UINT32_MAX) {
        return -1;
    }
    if (payloads->count == 0U) {
        payloads->offsets[0] = 0U;
    }
    (void)memcpy(payloads->bytes + payloads->bytes_len, record, len);
    payloads->bytes_len += len;
    payloads->offsets[payloads->count + 1U] = (uint32_t)payloads->bytes_len;
    payloads->count++;
    return 0;
}

static size_t raw_payload_len(const RawPayloads *payloads, uint32_t index)
{
    return payloads->offsets[index + 1U] - payloads->offsets[index];
}

/* Contiguous run of fresh pages appended after the last committed page;
 * page i lives at data + i * page_size and is numbered base_pgno + i. */
typedef struct {
    uint8_t *data;
    size_t count;
    size_t cap;
    uint32_t base_pgno;
} RawPages;

static void raw_pages_destroy(RawPages *pages)
{
    free(pages->data);
    (void)memset(pages, 0, sizeof(*pages));
}

static uint8_t *raw_pages_alloc(RawPages *pages, uint32_t *out_pgno)
{
    uint8_t *page;

    if (pages->count == pages->cap) {
        size_t new_cap = pages->cap == 0U ? 256U : (pages->cap * 2U);
        uint8_t *grown = realloc(pages->data, new_cap * CP_RAW_PAGE_SIZE);

        if (grown == nullptr) {
            return nullptr;
        }
        pages->data = grown;
        pages->cap = new_cap;
    }
    page = pages->data + (pages->count * CP_RAW_PAGE_SIZE);
    (void)memset(page, 0, CP_RAW_PAGE_SIZE);
    *out_pgno = pages->base_pgno + (uint32_t)pages->count;
    pages->count++;
    return page;
}

/* Emits an index leaf page (type 10): 8-byte header, cell pointer array
 * growing down from offset 8, cell content packed upward from the page
 * end. Cells are payload indices, already in key order. */
static int raw_emit_leaf(RawPages *pages,
                         const RawPayloads *payloads,
                         const uint32_t *cells,
                         size_t cell_count,
                         uint32_t *out_pgno)
{
    uint8_t *page = raw_pages_alloc(pages, out_pgno);
    size_t top = CP_RAW_PAGE_SIZE;

    if (page == nullptr) {
        return -1;
    }
    page[0] = 10U;
    for (size_t i = 0U; i < cell_count; i++) {
        size_t payload_len = raw_payload_len(payloads, cells[i]);
        size_t cell_len = raw_varint_len(payload_len) + payload_len;
        size_t written;

        if ((top < cell_len) || ((top - cell_len) < (8U + (2U * cell_count)))) {
            return -1;
        }
        top -= cell_len;
        written = raw_varint_put(page + top, payload_len);
        if (written == 0U) {
            return -1;
        }
        (void)memcpy(page + top + written, payloads->bytes + payloads->offsets[cells[i]],
                     payload_len);
        raw_store_be(page + 8U + (2U * i), top, 2U);
    }
    raw_store_be(page + 3U, cell_count, 2U);
    raw_store_be(page + 5U, top & 0xFFFFU, 2U);
    return 0;
}

/* Emits an index interior page (type 2): 12-byte header with the
 * right-most child at offset 8; each cell is a 4-byte child page number
 * followed by the separator payload. */
static int raw_emit_interior(RawPages *pages,
                             const RawPayloads *payloads,
                             const uint32_t *cell_children,
                             const uint32_t *cell_payloads,
                             size_t cell_count,
                             uint32_t right_most,
                             uint32_t *out_pgno)
{
    uint8_t *page = raw_pages_alloc(pages, out_pgno);
    size_t top = CP_RAW_PAGE_SIZE;

    if (page == nullptr) {
        return -1;
    }
    page[0] = 2U;
    raw_store_be(page + 8U, right_most, 4U);
    for (size_t i = 0U; i < cell_count; i++) {
        size_t payload_len = raw_payload_len(payloads, cell_payloads[i]);
        size_t cell_len = 4U + raw_varint_len(payload_len) + payload_len;
        size_t written;

        if ((top < cell_len) || ((top - cell_len) < (12U + (2U * cell_count)))) {
            return -1;
        }
        top -= cell_len;
        raw_store_be(page + top, cell_children[i], 4U);
        written = raw_varint_put(page + top + 4U, payload_len);
        if (written == 0U) {
            return -1;
        }
        (void)memcpy(page + top + 4U + written,
                     payloads->bytes + payloads->offsets[cell_payloads[i]],
                     payload_len);
        raw_store_be(page + 12U + (2U * i), top, 2U);
    }
    raw_store_be(page + 3U, cell_count, 2U);
    raw_store_be(page + 5U, top & 0xFFFFU, 2U);
    return 0;
}

/* Builds one index b-tree bottom-up from sorted payloads. Index b-trees
 * store each key exactly once: the key between two leaves is promoted to
 * the parent, recursively. Greedy fill with one edge case per level: the
 * final key of a level cannot be promoted (nothing would remain to its
 * right), so the previous key is donated to the parent instead. */
static int raw_build_index_btree(RawPages *pages, const RawPayloads *payloads, uint32_t *out_root)
{
    uint32_t *children = nullptr;
    size_t child_count = 0U;
    size_t child_cap = 0U;
    uint32_t *seps = nullptr;
    size_t sep_count = 0U;
    size_t sep_cap = 0U;
    uint32_t *next_children = nullptr;
    size_t next_child_count = 0U;
    size_t next_child_cap = 0U;
    uint32_t *next_seps = nullptr;
    size_t next_sep_count = 0U;
    size_t next_sep_cap = 0U;
    uint32_t cur[CP_RAW_MAX_CELLS];
    uint32_t cur_children[CP_RAW_MAX_CELLS];
    size_t cur_count = 0U;
    size_t cur_bytes = 0U;
    uint32_t pgno;
    int rc = -1;

    if (payloads->count == 0U) {
        return raw_emit_leaf(pages, payloads, nullptr, 0U, out_root);
    }

    for (size_t i = 0U; i < payloads->count;) {
        size_t payload_len = raw_payload_len(payloads, (uint32_t)i);
        size_t need = raw_varint_len(payload_len) + payload_len + 2U;

        if (payload_len > CP_RAW_MAX_LOCAL) {
            goto done;
        }
        if ((cur_count > 0U) &&
            (((8U + cur_bytes + need) > CP_RAW_PAGE_SIZE) || (cur_count == CP_RAW_MAX_CELLS))) {
            if ((i + 1U) < payloads->count) {
                if ((raw_emit_leaf(pages, payloads, cur, cur_count, &pgno) != 0) ||
                    (raw_u32_push(&children, &child_count, &child_cap, pgno) != 0) ||
                    (raw_u32_push(&seps, &sep_count, &sep_cap, (uint32_t)i) != 0)) {
                    goto done;
                }
                cur_count = 0U;
                cur_bytes = 0U;
                i++;
                continue;
            }
            {
                uint32_t donated = cur[--cur_count];

                if (cur_count == 0U) {
                    goto done;
                }
                if ((raw_emit_leaf(pages, payloads, cur, cur_count, &pgno) != 0) ||
                    (raw_u32_push(&children, &child_count, &child_cap, pgno) != 0) ||
                    (raw_u32_push(&seps, &sep_count, &sep_cap, donated) != 0)) {
                    goto done;
                }
                cur[0] = (uint32_t)i;
                cur_count = 1U;
                cur_bytes = need;
                i++;
                continue;
            }
        }
        cur[cur_count++] = (uint32_t)i;
        cur_bytes += need;
        i++;
    }
    if ((raw_emit_leaf(pages, payloads, cur, cur_count, &pgno) != 0) ||
        (raw_u32_push(&children, &child_count, &child_cap, pgno) != 0)) {
        goto done;
    }

    while (child_count > 1U) {
        size_t m = sep_count;

        if (m != (child_count - 1U)) {
            goto done;
        }
        cur_count = 0U;
        cur_bytes = 0U;
        next_child_count = 0U;
        next_sep_count = 0U;
        for (size_t ci = 0U; ci < m;) {
            uint32_t payload_index = seps[ci];
            size_t payload_len = raw_payload_len(payloads, payload_index);
            size_t cell_len = 4U + raw_varint_len(payload_len) + payload_len + 2U;
            bool close = (cur_count > 0U) && (((12U + cur_bytes + cell_len) > CP_RAW_PAGE_SIZE) ||
                                              (cur_count == CP_RAW_MAX_CELLS));

            if (close && (ci == (m - 1U))) {
                uint32_t prev_child = cur_children[cur_count - 1U];
                uint32_t prev_key = cur[cur_count - 1U];

                cur_count--;
                if (cur_count == 0U) {
                    goto done;
                }
                if ((raw_emit_interior(pages, payloads, cur_children, cur, cur_count,
                                       prev_child, &pgno) != 0) ||
                    (raw_u32_push(&next_children, &next_child_count, &next_child_cap, pgno) !=
                     0) ||
                    (raw_u32_push(&next_seps, &next_sep_count, &next_sep_cap, prev_key) != 0)) {
                    goto done;
                }
                cur_children[0] = children[ci];
                cur[0] = payload_index;
                cur_count = 1U;
                cur_bytes = cell_len;
                ci++;
                continue;
            }
            if (close) {
                if ((raw_emit_interior(pages, payloads, cur_children, cur, cur_count,
                                       children[ci], &pgno) != 0) ||
                    (raw_u32_push(&next_children, &next_child_count, &next_child_cap, pgno) !=
                     0) ||
                    (raw_u32_push(&next_seps, &next_sep_count, &next_sep_cap, payload_index) !=
                     0)) {
                    goto done;
                }
                cur_count = 0U;
                cur_bytes = 0U;
                ci++;
                continue;
            }
            cur_children[cur_count] = children[ci];
            cur[cur_count] = payload_index;
            cur_count++;
            cur_bytes += cell_len;
            ci++;
        }
        if ((raw_emit_interior(pages, payloads, cur_children, cur, cur_count, children[m],
                               &pgno) != 0) ||
            (raw_u32_push(&next_children, &next_child_count, &next_child_cap, pgno) != 0)) {
            goto done;
        }
        {
            uint32_t *swap_items = children;
            size_t swap_cap = child_cap;

            children = next_children;
            child_count = next_child_count;
            child_cap = next_child_cap;
            next_children = swap_items;
            next_child_cap = swap_cap;
            swap_items = seps;
            swap_cap = sep_cap;
            seps = next_seps;
            sep_count = next_sep_count;
            sep_cap = next_sep_cap;
            next_seps = swap_items;
            next_sep_cap = swap_cap;
        }
    }
    *out_root = children[0];
    rc = 0;

done:
    free(children);
    free(seps);
    free(next_children);
    free(next_seps);
    return rc;
}

/* Sorted (term, rowid) payloads by counting sort: term ids are dense small
 * integers and mirror position is the rowid, so bucket-scatter yields
 * (term ASC, rowid ASC) order in O(rows + terms). */
static int raw_payloads_from_terms(const uint32_t *terms,
                                   size_t row_count,
                                   size_t term_count,
                                   RawPayloads *payloads)
{
    uint32_t *buckets = calloc(term_count + 1U, sizeof(uint32_t));
    uint32_t *sorted = row_count == 0U ? nullptr : malloc(row_count * sizeof(uint32_t));
    uint32_t running = 0U;
    int rc = -1;

    if ((buckets == nullptr) || ((row_count > 0U) && (sorted == nullptr)) ||
        (row_count > (size_t)UINT32_MAX)) {
        goto done;
    }
    for (size_t i = 0U; i < row_count; i++) {
        if (terms[i] > term_count) {
            goto done;
        }
        buckets[terms[i]]++;
    }
    for (size_t t = 0U; t <= term_count; t++) {
        uint32_t bucket_size = buckets[t];

        buckets[t] = running;
        running += bucket_size;
    }
    for (size_t i = 0U; i < row_count; i++) {
        sorted[buckets[terms[i]]++] = (uint32_t)i;
    }
    for (size_t t = 0U; t <= term_count; t++) {
        size_t start = t == 0U ? 0U : buckets[t - 1U];

        for (size_t j = start; j < buckets[t]; j++) {
            uint8_t record[32];
            size_t len = raw_record_int_pair(record, (int64_t)t, (int64_t)sorted[j]);

            if (raw_payloads_append(payloads, record, len) != 0) {
                goto done;
            }
        }
    }
    rc = 0;

done:
    free(buckets);
    free(sorted);
    return rc;
}

typedef struct {
    const char *text;
    uint32_t len;
    uint32_t rowid;
} RawTextKey;

/* BINARY collation order (memcmp, shorter first), rowid as tiebreak. */
static int raw_text_key_cmp(const void *left, const void *right)
{
    const RawTextKey *a = left;
    const RawTextKey *b = right;
    uint32_t min_len = a->len < b->len ? a->len : b->len;
    int cmp = min_len == 0U ? 0 : memcmp(a->text, b->text, min_len);

    if (cmp != 0) {
        return cmp;
    }
    if (a->len != b->len) {
        return a->len < b->len ? -1 : 1;
    }
    if (a->rowid != b->rowid) {
        return a->rowid < b->rowid ? -1 : 1;
    }
    return 0;
}

static int raw_payloads_from_text_keys(RawTextKey *keys,
                                       size_t count,
                                       RawPayloads *payloads,
                                       bool reject_duplicates)
{
    uint8_t record[CP_RAW_MAX_LOCAL + 32U];

    if (count > 0U) {
        qsort(keys, count, sizeof(*keys), raw_text_key_cmp);
    }
    for (size_t i = 0U; i < count; i++) {
        size_t len;

        if (keys[i].len > CP_RAW_MAX_LOCAL) {
            return -1;
        }
        /* A UNIQUE index built from duplicate keys would be silently
         * corrupt (integrity_check fails after publish), so refuse and let
         * the classic emitter's real constraints report the problem. */
        if (reject_duplicates && (i > 0U) && (keys[i].len == keys[i - 1U].len) &&
            ((keys[i].len == 0U) ||
             (memcmp(keys[i].text, keys[i - 1U].text, keys[i].len) == 0))) {
            (void)fprintf(stderr,
                          "code-lens: raw emitter found duplicate unique-index key '%.*s'\n",
                          (int)keys[i].len,
                          keys[i].text);
            return -1;
        }
        len = raw_record_text_key(record, keys[i].text, keys[i].len, (int64_t)keys[i].rowid);
        if (raw_payloads_append(payloads, record, len) != 0) {
            return -1;
        }
    }
    return 0;
}

/* Collects the interner's terms as id-ordered text keys (rowid = Term id);
 * every slot must be filled exactly once across ids 1..count. */
static int raw_interner_text_keys(const TermInterner *interner, RawTextKey **out_keys)
{
    RawTextKey *keys = nullptr;
    size_t filled = 0U;

    if (interner->count > 0U) {
        if (interner->count > (size_t)UINT32_MAX) {
            return -1;
        }
        keys = malloc(interner->count * sizeof(RawTextKey));
        if (keys == nullptr) {
            return -1;
        }
        for (size_t i = 0U; i < interner->capacity; i++) {
            const TermSlot *slot = &interner->slots[i];

            if (slot->text == nullptr) {
                continue;
            }
            if ((filled >= interner->count) || (slot->id < 1) ||
                (slot->id > (int64_t)interner->count)) {
                free(keys);
                return -1;
            }
            keys[(size_t)slot->id - 1U].text = slot->text;
            keys[(size_t)slot->id - 1U].len = slot->len;
            keys[(size_t)slot->id - 1U].rowid = (uint32_t)slot->id;
            filled++;
        }
        if (filled != interner->count) {
            free(keys);
            return -1;
        }
    }
    *out_keys = keys;
    return 0;
}

/* Adopts the appended b-trees: with writable_schema=ON, a single INSERT
 * lands all four sqlite_master rows atomically. The schema cookie is
 * bumped first; a bumped cookie without new rows is harmless, while rows
 * without pages would not be, so the INSERT is the commit point. */
static int raw_graft_schema_rows(const char *db_path, const uint32_t roots[4])
{
    sqlite3 *db = nullptr;
    sqlite3_stmt *stmt = nullptr;
    char *sql = nullptr;
    int version = 0;
    int rc = -1;

    if (sqlite3_open(db_path, &db) != SQLITE_OK) {
        goto done;
    }
    if (sqlite3_exec(db, "PRAGMA synchronous=OFF", nullptr, nullptr, nullptr) != SQLITE_OK) {
        goto done;
    }
    if ((sqlite3_prepare_v2(db, "PRAGMA schema_version", -1, &stmt, nullptr) != SQLITE_OK) ||
        (sqlite3_step(stmt) != SQLITE_ROW)) {
        goto done;
    }
    version = sqlite3_column_int(stmt, 0);
    (void)sqlite3_finalize(stmt);
    stmt = nullptr;
    if (sqlite3_exec(db, "PRAGMA writable_schema=ON", nullptr, nullptr, nullptr) != SQLITE_OK) {
        goto done;
    }
    sql = sqlite3_mprintf("PRAGMA schema_version=%d", version + 1);
    if ((sql == nullptr) ||
        (sqlite3_exec(db, sql, nullptr, nullptr, nullptr) != SQLITE_OK)) {
        goto done;
    }
    sqlite3_free(sql);
    sql = sqlite3_mprintf(
        "INSERT INTO sqlite_master (type, name, tbl_name, rootpage, sql) VALUES "
        "('index','idx_symbol_name','Symbol',%u,%Q),"
        "('index','idx_ref_symbol_base','RefData',%u,%Q),"
        "('index','idx_keyword_base','KeywordData',%u,%Q),"
        "('index','idx_term_text','Term',%u,%Q)",
        (unsigned int)roots[0], CP_IDX_SYMBOL_NAME_DDL,
        (unsigned int)roots[1], CP_IDX_REF_BASE_DDL,
        (unsigned int)roots[2], CP_IDX_KEYWORD_BASE_DDL,
        (unsigned int)roots[3], CP_IDX_TERM_TEXT_DDL);
    if ((sql == nullptr) ||
        (sqlite3_exec(db, sql, nullptr, nullptr, nullptr) != SQLITE_OK)) {
        goto done;
    }
    rc = 0;

done:
    (void)sqlite3_finalize(stmt);
    sqlite3_free(sql);
    (void)sqlite3_close(db);
    return rc;
}

/* Undoes a partial graft so the classic path sees the file exactly as
 * COMMIT left it. */
static void raw_graft_restore(const char *db_path, off_t original_size, uint32_t original_pages)
{
    int fd = open(db_path, O_RDWR);
    uint8_t count_be[4];

    if (fd < 0) {
        return;
    }
    (void)ftruncate(fd, original_size);
    raw_store_be(count_be, original_pages, 4U);
    (void)pwrite(fd, count_be, sizeof(count_be), CP_RAW_HDR_PAGE_COUNT_OFFSET);
    (void)close(fd);
}

static int raw_write_all(int fd, const uint8_t *data, size_t len, off_t offset)
{
    size_t written = 0U;

    while (written < len) {
        ssize_t n = pwrite(fd, data + written, len - written, offset + (off_t)written);

        if (n <= 0) {
            return -1;
        }
        written += (size_t)n;
    }
    return 0;
}

/* Builds and grafts all four secondary indexes onto the committed staging
 * database. On any failure the file is restored and the caller falls back
 * to classic CREATE INDEX. */
static int raw_secondary_indexes_graft(const char *db_path, IndexContext *ctx)
{
    RawPages pages = {0};
    RawPayloads payloads = {0};
    RawTextKey *text_keys = nullptr;
    uint32_t roots[4] = {0};
    uint8_t header[40];
    uint8_t count_be[4];
    uint32_t page_size;
    uint32_t old_page_count;
    struct stat st;
    off_t original_size = 0;
    bool file_dirty = false;
    int fd = -1;
    int rc = -1;

    fd = open(db_path, O_RDWR);
    if (fd < 0) {
        return -1;
    }
    if ((fstat(fd, &st) != 0) ||
        (pread(fd, header, sizeof(header), 0) != (ssize_t)sizeof(header))) {
        goto done;
    }
    page_size = ((uint32_t)header[16] << 8U) | (uint32_t)header[17];
    old_page_count = ((uint32_t)header[28] << 24U) | ((uint32_t)header[29] << 16U) |
                     ((uint32_t)header[30] << 8U) | (uint32_t)header[31];
    original_size = st.st_size;
    if ((page_size != CP_RAW_PAGE_SIZE) || (header[20] != 0U) ||
        ((uint64_t)original_size != ((uint64_t)old_page_count * CP_RAW_PAGE_SIZE))) {
        goto done;
    }
    pages.base_pgno = old_page_count + 1U;

    /* idx_symbol_name: Symbol rowids are 1..N in emission order. */
    {
        const RawIndexMirror *mirror = &ctx->raw_mirror;

        if (mirror->symbol_count > 0U) {
            text_keys = malloc(mirror->symbol_count * sizeof(RawTextKey));
            if (text_keys == nullptr) {
                goto done;
            }
            for (size_t i = 0U; i < mirror->symbol_count; i++) {
                size_t len = strlen(mirror->symbol_names[i]);

                if ((len > (size_t)UINT32_MAX) || ((i + 1U) > (size_t)UINT32_MAX)) {
                    goto done;
                }
                text_keys[i].text = mirror->symbol_names[i];
                text_keys[i].len = (uint32_t)len;
                text_keys[i].rowid = (uint32_t)(i + 1U);
            }
        }
        if ((raw_payloads_from_text_keys(text_keys, mirror->symbol_count, &payloads, false) != 0) ||
            (raw_build_index_btree(&pages, &payloads, &roots[0]) != 0)) {
            goto done;
        }
        free(text_keys);
        text_keys = nullptr;
        raw_payloads_reset(&payloads);
    }

    /* idx_ref_symbol_base and idx_keyword_base: rowid = mirror position. */
    if ((raw_payloads_from_terms(ctx->raw_mirror.ref_terms, ctx->raw_mirror.ref_count,
                                 ctx->interner.count, &payloads) != 0) ||
        (raw_build_index_btree(&pages, &payloads, &roots[1]) != 0)) {
        goto done;
    }
    raw_payloads_reset(&payloads);
    if ((raw_payloads_from_terms(ctx->raw_mirror.keyword_terms, ctx->raw_mirror.keyword_count,
                                 ctx->interner.count, &payloads) != 0) ||
        (raw_build_index_btree(&pages, &payloads, &roots[2]) != 0)) {
        goto done;
    }
    raw_payloads_reset(&payloads);

    /* idx_term_text: walk the interner's hash slots; ids are the rowids. */
    {
        if (raw_interner_text_keys(&ctx->interner, &text_keys) != 0) {
            goto done;
        }
        if ((raw_payloads_from_text_keys(text_keys, ctx->interner.count, &payloads, true) != 0) ||
            (raw_build_index_btree(&pages, &payloads, &roots[3]) != 0)) {
            goto done;
        }
        free(text_keys);
        text_keys = nullptr;
    }

    if (((uint64_t)old_page_count + (uint64_t)pages.count) > (uint64_t)UINT32_MAX) {
        goto done;
    }
    file_dirty = true;
    if (raw_write_all(fd, pages.data, pages.count * CP_RAW_PAGE_SIZE, original_size) != 0) {
        goto done;
    }
    raw_store_be(count_be, old_page_count + (uint32_t)pages.count, 4U);
    if (pwrite(fd, count_be, sizeof(count_be), CP_RAW_HDR_PAGE_COUNT_OFFSET) !=
        (ssize_t)sizeof(count_be)) {
        goto done;
    }
    (void)close(fd);
    fd = -1;
    if (raw_graft_schema_rows(db_path, roots) != 0) {
        goto done;
    }
    rc = 0;

done:
    if (fd >= 0) {
        (void)close(fd);
    }
    if ((rc != 0) && file_dirty) {
        raw_graft_restore(db_path, original_size, old_page_count);
    }
    free(text_keys);
    raw_payloads_destroy(&payloads);
    raw_pages_destroy(&pages);
    return rc;
}

/* Classic fallback, run after COMMIT on the closed staging database; the
 * final schema is identical to the in-transaction CREATE INDEX path. */
static int classic_secondary_indexes_after_commit(const char *db_path)
{
    sqlite3 *db = nullptr;
    int rc = -1;

    if ((sqlite3_open(db_path, &db) == SQLITE_OK) &&
        (sqlite3_exec(db, "PRAGMA synchronous=OFF", nullptr, nullptr, nullptr) == SQLITE_OK) &&
        (sqlite3_exec(db, index_secondary_indexes_sql, nullptr, nullptr, nullptr) ==
         SQLITE_OK)) {
        rc = 0;
    }
    (void)sqlite3_close(db);
    return rc;
}

/* Raw table emitter: with CODE_LENS_EMITTER unset (or "raw") the bulk load
 * bypasses SQLite entirely. The schema - tables, views, secondary indexes,
 * and the FTS virtual table - is created empty up front, so SQLite itself
 * writes every sqlite_master row and allocates every root page. During the
 * walk each row is encoded straight into raw table b-tree leaf pages (rowids
 * are dense emission ordinals, so leaves fill append-only); after the tiny
 * COMMIT (repo row plus FTS shadow adoption) the finished b-trees are
 * appended to the file and each schema-allocated root page is overwritten in
 * place with the real root, so no sqlite_master surgery is needed at all.
 * Any failure abandons the staging file and the caller retries the whole
 * build with the classic SQL emitter, so the raw path can never affect
 * correctness. */
#define CP_RAW_TBL_MAX_LOCAL 8157U /* usable - 35 */
#define CP_RAW_TBL_MIN_LOCAL 1003U /* (usable - 12) * 32 / 255 - 23 */
#define CP_RAW_OVFL_DATA (CP_RAW_PAGE_SIZE - 4U)

static bool raw_emitter_enabled(void)
{
    const char *value = getenv("CODE_LENS_EMITTER");

    return (value == nullptr) || (value[0] == '\0') || (strcmp(value, "classic") != 0);
}

static void raw_tree_destroy(RawTableTree *tree)
{
    free(tree->data);
    free(tree->fixups);
    free(tree->item_pgnos);
    free(tree->item_keys);
    (void)memset(tree, 0, sizeof(*tree));
}

static void raw_emitter_destroy(RawEmitter *emit)
{
    raw_tree_destroy(&emit->file);
    raw_tree_destroy(&emit->symbol);
    raw_tree_destroy(&emit->term);
    raw_tree_destroy(&emit->refdata);
    raw_tree_destroy(&emit->keyworddata);
    raw_tree_destroy(&emit->alias);
    raw_tree_destroy(&emit->referred);
    free(emit->file_ids);
    free(emit->symbol_ids);
    free(emit->alias_ids);
    free(emit->referred_ids);
    free(emit->scratch);
    (void)memset(emit, 0, sizeof(*emit));
}

static int raw_ptr_push(const char ***items, size_t *count, size_t *cap, const char *value)
{
    if (*count == *cap) {
        size_t new_cap = *cap == 0U ? 4096U : (*cap * 2U);
        const char **grown = realloc(*items, new_cap * sizeof(const char *));

        if (grown == nullptr) {
            return -1;
        }
        *items = grown;
        *cap = new_cap;
    }
    (*items)[(*count)++] = value;
    return 0;
}

static uint8_t *raw_tree_page(const RawTableTree *tree, uint32_t local_pgno)
{
    return tree->data + (((size_t)local_pgno - 1U) * CP_RAW_PAGE_SIZE);
}

/* Allocates the next local page (numbered from 1); the buffer may move, so
 * callers must re-derive page pointers after every allocation. */
static uint8_t *raw_tree_alloc_page(RawTableTree *tree, uint32_t *out_local)
{
    uint8_t *page;

    if (tree->page_count == tree->page_cap) {
        size_t new_cap = tree->page_cap == 0U ? 64U : (tree->page_cap * 2U);
        uint8_t *grown = realloc(tree->data, new_cap * CP_RAW_PAGE_SIZE);

        if (grown == nullptr) {
            return nullptr;
        }
        tree->data = grown;
        tree->page_cap = new_cap;
    }
    if (tree->page_count >= (size_t)UINT32_MAX) {
        return nullptr;
    }
    page = tree->data + (tree->page_count * CP_RAW_PAGE_SIZE);
    (void)memset(page, 0, CP_RAW_PAGE_SIZE);
    tree->page_count++;
    *out_local = (uint32_t)tree->page_count;
    return page;
}

/* Records the byte offset of a stored local page number so the graft can
 * rebase it once the tree's global page range is known. */
static int raw_tree_fixup_add(RawTableTree *tree, uint64_t offset)
{
    if (tree->fixup_count == tree->fixup_cap) {
        size_t new_cap = tree->fixup_cap == 0U ? 1024U : (tree->fixup_cap * 2U);
        uint64_t *grown = realloc(tree->fixups, new_cap * sizeof(uint64_t));

        if (grown == nullptr) {
            return -1;
        }
        tree->fixups = grown;
        tree->fixup_cap = new_cap;
    }
    tree->fixups[tree->fixup_count++] = offset;
    return 0;
}

static int raw_tree_item_push(RawTableTree *tree, uint32_t pgno, int64_t key)
{
    if (tree->item_count == tree->item_cap) {
        size_t new_cap = tree->item_cap == 0U ? 256U : (tree->item_cap * 2U);
        uint32_t *grown_pgnos = realloc(tree->item_pgnos, new_cap * sizeof(uint32_t));
        int64_t *grown_keys;

        if (grown_pgnos == nullptr) {
            return -1;
        }
        tree->item_pgnos = grown_pgnos;
        grown_keys = realloc(tree->item_keys, new_cap * sizeof(int64_t));
        if (grown_keys == nullptr) {
            return -1;
        }
        tree->item_keys = grown_keys;
        tree->item_cap = new_cap;
    }
    tree->item_pgnos[tree->item_count] = pgno;
    tree->item_keys[tree->item_count] = key;
    tree->item_count++;
    return 0;
}

/* Record encoding: SQLite's record format with minimal serial types. NULL
 * appears only as the stored form of an INTEGER PRIMARY KEY column (the
 * value lives in the rowid); TEXT columns follow bind_text semantics, where
 * a null pointer is stored as the empty string, never as SQL NULL. */
enum { CP_COL_NULL, CP_COL_INT, CP_COL_TEXT, CP_COL_BLOB };

typedef struct {
    uint8_t kind;
    int64_t value;
    const char *text;
    size_t text_len;
} RawColumn;

static int raw_scratch_reserve(RawEmitter *emit, size_t need)
{
    if (need > emit->scratch_cap) {
        size_t new_cap = emit->scratch_cap == 0U ? 65536U : emit->scratch_cap;
        uint8_t *grown;

        while (new_cap < need) {
            new_cap *= 2U;
        }
        grown = realloc(emit->scratch, new_cap);
        if (grown == nullptr) {
            return -1;
        }
        emit->scratch = grown;
        emit->scratch_cap = new_cap;
    }
    return 0;
}

typedef struct {
    uint64_t serials[16];
    size_t body_lens[16];
    size_t header_len;
    size_t total_len;
} RawRecordLayout;

/* Computes the record header/body layout for one row. */
static int raw_record_layout(const RawColumn *cols, size_t count, RawRecordLayout *lay)
{
    size_t serial_bytes = 0U;
    size_t body_total = 0U;
    size_t header_len;

    if (count > 16U) {
        return -1;
    }
    for (size_t i = 0U; i < count; i++) {
        const RawColumn *col = &cols[i];

        if (col->kind == CP_COL_NULL) {
            lay->serials[i] = 0U;
            lay->body_lens[i] = 0U;
        } else if (col->kind == CP_COL_INT) {
            lay->serials[i] = raw_int_serial_type(col->value, &lay->body_lens[i]);
        } else {
            size_t len = (col->text == nullptr) ? 0U : col->text_len;

            lay->serials[i] = (col->kind == CP_COL_BLOB ? 12U : 13U) + (2U * (uint64_t)len);
            lay->body_lens[i] = len;
        }
        serial_bytes += raw_varint_len(lay->serials[i]);
        body_total += lay->body_lens[i];
    }
    header_len = serial_bytes + 1U;
    while ((raw_varint_len(header_len) + serial_bytes) != header_len) {
        header_len = raw_varint_len(header_len) + serial_bytes;
        if (header_len > (serial_bytes + 9U)) {
            return -1;
        }
    }
    lay->header_len = header_len;
    lay->total_len = header_len + body_total;
    return 0;
}

/* Writes the laid-out record at dst, which must hold lay->total_len bytes. */
static int raw_record_write(uint8_t *dst,
                            const RawColumn *cols,
                            size_t count,
                            const RawRecordLayout *lay)
{
    size_t pos = raw_varint_put(dst, lay->header_len);

    for (size_t i = 0U; i < count; i++) {
        pos += raw_varint_put(dst + pos, lay->serials[i]);
    }
    if (pos != lay->header_len) {
        return -1;
    }
    for (size_t i = 0U; i < count; i++) {
        const RawColumn *col = &cols[i];

        if (col->kind == CP_COL_INT) {
            raw_store_be(dst + pos, (uint64_t)col->value, lay->body_lens[i]);
        } else if (((col->kind == CP_COL_TEXT) || (col->kind == CP_COL_BLOB)) &&
                   (lay->body_lens[i] > 0U)) {
            (void)memcpy(dst + pos, col->text, lay->body_lens[i]);
        }
        pos += lay->body_lens[i];
    }
    return pos == lay->total_len ? 0 : -1;
}

static int raw_record_encode(RawEmitter *emit,
                             const RawColumn *cols,
                             size_t count,
                             size_t *out_len)
{
    RawRecordLayout lay;

    if (raw_record_layout(cols, count, &lay) != 0) {
        return -1;
    }
    if (raw_scratch_reserve(emit, lay.total_len) != 0) {
        return -1;
    }
    if (raw_record_write(emit->scratch, cols, count, &lay) != 0) {
        return -1;
    }
    *out_len = lay.total_len;
    return 0;
}

/* Seals the open leaf: header counts land and the page joins the pending
 * parent-item list keyed by the largest rowid it holds. */
static void raw_tree_close_leaf(RawTableTree *tree)
{
    uint8_t *page = raw_tree_page(tree, tree->leaf_pgno);

    atomic_fetch_add_explicit(&raw_leaf_closes, 1U, memory_order_relaxed);
    raw_store_be(page + 3U, tree->leaf_cells, 2U);
    raw_store_be(page + 5U, (uint64_t)tree->leaf_top & 0xFFFFU, 2U);
    tree->leaf_pgno = 0U;
}

/* Reserves space for one non-overflow leaf cell and writes its varint
 * prefix (payload length + rowid), returning the payload destination.
 * All leaf bookkeeping happens here; the caller fills the payload bytes. */
static uint8_t *raw_tree_cell_begin(RawTableTree *tree, int64_t rowid, size_t len)
{
    size_t cell_len;
    uint8_t *page;
    size_t top;
    size_t cell_pos;

    if ((tree->has_rows && (rowid <= tree->last_rowid)) || (len > CP_RAW_TBL_MAX_LOCAL)) {
        return nullptr;
    }
    cell_len = raw_varint_len(len) + raw_varint_len((uint64_t)rowid) + len;
    if ((tree->leaf_pgno != 0U) &&
        ((tree->leaf_top < cell_len) ||
         ((tree->leaf_top - cell_len) < (8U + (2U * ((size_t)tree->leaf_cells + 1U)))))) {
        if (raw_tree_item_push(tree, tree->leaf_pgno, tree->last_rowid) != 0) {
            return nullptr;
        }
        raw_tree_close_leaf(tree);
    }
    if (tree->leaf_pgno == 0U) {
        uint32_t pgno;

        page = raw_tree_alloc_page(tree, &pgno);
        if (page == nullptr) {
            return nullptr;
        }
        page[0] = 13U;
        tree->leaf_pgno = pgno;
        tree->leaf_cells = 0U;
        tree->leaf_top = CP_RAW_PAGE_SIZE;
    }

    page = raw_tree_page(tree, tree->leaf_pgno);
    top = tree->leaf_top - cell_len;
    cell_pos = top;
    cell_pos += raw_varint_put(page + cell_pos, len);
    cell_pos += raw_varint_put(page + cell_pos, (uint64_t)rowid);
    raw_store_be(page + 8U + (2U * tree->leaf_cells), top, 2U);
    tree->leaf_cells++;
    tree->leaf_top = (uint32_t)top;
    tree->last_rowid = rowid;
    tree->has_rows = true;
    return page + cell_pos;
}

/* Appends one row to the streaming table b-tree: encodes the leaf cell
 * (with an overflow chain when the payload exceeds max_local) into the
 * current leaf, starting a new leaf when the cell does not fit. */
static int raw_tree_add_row(RawTableTree *tree, int64_t rowid, const uint8_t *payload, size_t len)
{
    size_t local = len;
    size_t ovfl_pages = 0U;
    size_t cell_len;
    uint8_t *page;
    size_t top;
    size_t cell_pos;

    if (len <= CP_RAW_TBL_MAX_LOCAL) {
        uint8_t *dst = raw_tree_cell_begin(tree, rowid, len);

        if (dst == nullptr) {
            return -1;
        }
        (void)memcpy(dst, payload, len);
        return 0;
    }
    if (tree->has_rows && (rowid <= tree->last_rowid)) {
        return -1;
    }
    {
        size_t surplus = CP_RAW_TBL_MIN_LOCAL +
                         ((len - CP_RAW_TBL_MIN_LOCAL) % CP_RAW_OVFL_DATA);

        local = surplus <= CP_RAW_TBL_MAX_LOCAL ? surplus : CP_RAW_TBL_MIN_LOCAL;
        ovfl_pages = ((len - local) + CP_RAW_OVFL_DATA - 1U) / CP_RAW_OVFL_DATA;
    }
    cell_len = raw_varint_len(len) + raw_varint_len((uint64_t)rowid) + local +
               (ovfl_pages > 0U ? 4U : 0U);

    if ((tree->leaf_pgno != 0U) &&
        ((tree->leaf_top < cell_len) ||
         ((tree->leaf_top - cell_len) < (8U + (2U * ((size_t)tree->leaf_cells + 1U)))))) {
        if (raw_tree_item_push(tree, tree->leaf_pgno, tree->last_rowid) != 0) {
            return -1;
        }
        raw_tree_close_leaf(tree);
    }
    if (tree->leaf_pgno == 0U) {
        uint32_t pgno;

        page = raw_tree_alloc_page(tree, &pgno);
        if (page == nullptr) {
            return -1;
        }
        page[0] = 13U;
        tree->leaf_pgno = pgno;
        tree->leaf_cells = 0U;
        tree->leaf_top = CP_RAW_PAGE_SIZE;
    }

    page = raw_tree_page(tree, tree->leaf_pgno);
    top = tree->leaf_top - cell_len;
    cell_pos = top;
    cell_pos += raw_varint_put(page + cell_pos, len);
    cell_pos += raw_varint_put(page + cell_pos, (uint64_t)rowid);
    (void)memcpy(page + cell_pos, payload, local);
    cell_pos += local;
    if (ovfl_pages > 0U) {
        uint64_t leaf_off = ((uint64_t)tree->leaf_pgno - 1U) * CP_RAW_PAGE_SIZE;
        uint32_t first_ovfl = (uint32_t)tree->page_count + 1U;
        size_t remaining = len - local;
        size_t data_pos = local;

        raw_store_be(page + cell_pos, first_ovfl, 4U);
        if (raw_tree_fixup_add(tree, leaf_off + cell_pos) != 0) {
            return -1;
        }
        for (size_t i = 0U; i < ovfl_pages; i++) {
            size_t chunk = remaining < CP_RAW_OVFL_DATA ? remaining : CP_RAW_OVFL_DATA;
            uint32_t pgno;
            uint8_t *ovfl = raw_tree_alloc_page(tree, &pgno);

            if (ovfl == nullptr) {
                return -1;
            }
            if ((i + 1U) < ovfl_pages) {
                raw_store_be(ovfl, (uint64_t)pgno + 1U, 4U);
                if (raw_tree_fixup_add(tree, ((uint64_t)pgno - 1U) * CP_RAW_PAGE_SIZE) != 0) {
                    return -1;
                }
            }
            (void)memcpy(ovfl + 4U, payload + data_pos, chunk);
            data_pos += chunk;
            remaining -= chunk;
        }
        if (remaining != 0U) {
            return -1;
        }
        page = raw_tree_page(tree, tree->leaf_pgno);
    }
    raw_store_be(page + 8U + (2U * tree->leaf_cells), top, 2U);
    tree->leaf_cells++;
    tree->leaf_top = (uint32_t)top;
    tree->last_rowid = rowid;
    tree->has_rows = true;
    return 0;
}

/* Emits one table interior page (type 5) for items [first, last]: cells for
 * all but the last child, which becomes the right-most pointer. Child page
 * numbers are locals recorded as fixups. */
static int raw_tree_emit_interior(RawTableTree *tree,
                                  size_t first,
                                  size_t last,
                                  uint32_t *out_pgno)
{
    uint32_t pgno;
    uint8_t *page = raw_tree_alloc_page(tree, &pgno);
    uint64_t page_off;
    size_t top = CP_RAW_PAGE_SIZE;
    size_t cell_count = last - first;

    if (page == nullptr) {
        return -1;
    }
    page_off = ((uint64_t)pgno - 1U) * CP_RAW_PAGE_SIZE;
    page[0] = 5U;
    raw_store_be(page + 8U, tree->item_pgnos[last], 4U);
    if (raw_tree_fixup_add(tree, page_off + 8U) != 0) {
        return -1;
    }
    for (size_t j = 0U; j < cell_count; j++) {
        size_t i = first + j;
        size_t cell_len = 4U + raw_varint_len((uint64_t)tree->item_keys[i]);

        if ((top < cell_len) || ((top - cell_len) < (12U + (2U * (j + 1U))))) {
            return -1;
        }
        top -= cell_len;
        raw_store_be(page + top, tree->item_pgnos[i], 4U);
        if (raw_tree_fixup_add(tree, page_off + top) != 0) {
            return -1;
        }
        (void)raw_varint_put(page + top + 4U, (uint64_t)tree->item_keys[i]);
        raw_store_be(page + 12U + (2U * j), top, 2U);
    }
    raw_store_be(page + 3U, cell_count, 2U);
    raw_store_be(page + 5U, top & 0xFFFFU, 2U);
    *out_pgno = pgno;
    return 0;
}

/* Builds the interior levels over the streamed leaves. Every interior page
 * needs at least one cell plus the right-most child, so when a level's
 * final item would be orphaned the previous item is left for it (the same
 * donate-back rule the raw index builder uses). */
static int raw_tree_finalize(RawTableTree *tree)
{
    if (tree->leaf_pgno != 0U) {
        if (raw_tree_item_push(tree, tree->leaf_pgno, tree->last_rowid) != 0) {
            return -1;
        }
        raw_tree_close_leaf(tree);
    }
    if (tree->item_count == 0U) {
        uint32_t pgno;
        uint8_t *page = raw_tree_alloc_page(tree, &pgno);

        if (page == nullptr) {
            return -1;
        }
        page[0] = 13U;
        raw_store_be(page + 5U, (uint64_t)CP_RAW_PAGE_SIZE & 0xFFFFU, 2U);
        tree->root_local = pgno;
        return 0;
    }

    while (tree->item_count > 1U) {
        size_t out = 0U;
        size_t group_start = 0U;
        size_t group_bytes = 0U;

        for (size_t i = 0U; i < tree->item_count; i++) {
            if (i > group_start) {
                /* Item i joining the group turns item i - 1 into a cell. */
                size_t cell_len = 4U + raw_varint_len((uint64_t)tree->item_keys[i - 1U]) + 2U;
                bool fits = ((12U + group_bytes + cell_len) <= CP_RAW_PAGE_SIZE) &&
                            ((i - group_start) < CP_RAW_MAX_CELLS);

                if (!fits) {
                    size_t group_end = i - 1U;
                    uint32_t pgno;

                    if ((i == (tree->item_count - 1U)) && (group_end > group_start)) {
                        /* Final item would be orphaned: leave the previous
                         * item to pair with it. */
                        group_end--;
                    }
                    if (group_end == group_start) {
                        return -1;
                    }
                    if (raw_tree_emit_interior(tree, group_start, group_end, &pgno) != 0) {
                        return -1;
                    }
                    tree->item_pgnos[out] = pgno;
                    tree->item_keys[out] = tree->item_keys[group_end];
                    out++;
                    group_start = group_end + 1U;
                    group_bytes = 0U;
                    if (i > group_start) {
                        for (size_t k = group_start; k < i; k++) {
                            group_bytes +=
                                4U + raw_varint_len((uint64_t)tree->item_keys[k]) + 2U;
                        }
                    }
                    continue;
                }
                group_bytes += cell_len;
            }
        }
        {
            uint32_t pgno;

            if (group_start >= tree->item_count) {
                return -1;
            }
            if (raw_tree_emit_interior(tree, group_start, tree->item_count - 1U, &pgno) != 0) {
                return -1;
            }
            tree->item_pgnos[out] = pgno;
            tree->item_keys[out] = tree->item_keys[tree->item_count - 1U];
            out++;
        }
        tree->item_count = out;
    }
    tree->root_local = tree->item_pgnos[0];
    return 0;
}

/* Emit-side hooks: encode one row into its streaming tree. Text pointers
 * are copied immediately, so no lifetime is required beyond the call; the
 * TEXT primary keys pushed for the autoindexes are arena-backed. */
#define CP_RAW_TEXT(str) \
    {.kind = CP_COL_TEXT, .value = 0, .text = (str), .text_len = (str) == nullptr ? 0U : strlen(str)}
#define CP_RAW_TEXT_LEN(str, len) \
    {.kind = CP_COL_TEXT, .value = 0, .text = (str), .text_len = (len)}
#define CP_RAW_INT(v) {.kind = CP_COL_INT, .value = (v), .text = nullptr, .text_len = 0U}
#define CP_RAW_ROWID_NULL {.kind = CP_COL_NULL, .value = 0, .text = nullptr, .text_len = 0U}

static int raw_emit_encoded(RawEmitter *emit,
                            RawTableTree *tree,
                            int64_t rowid,
                            const RawColumn *cols,
                            size_t count)
{
    RawRecordLayout lay;
    size_t len;

    if (raw_record_layout(cols, count, &lay) != 0) {
        return -1;
    }
    if (lay.total_len <= CP_RAW_TBL_MAX_LOCAL) {
        /* Common case: encode straight into the leaf page, skipping the
         * scratch buffer and its extra memcpy of the whole payload. */
        uint8_t *dst = raw_tree_cell_begin(tree, rowid, lay.total_len);

        if (dst == nullptr) {
            return -1;
        }
        return raw_record_write(dst, cols, count, &lay);
    }
    if (raw_record_encode(emit, cols, count, &len) != 0) {
        return -1;
    }
    return raw_tree_add_row(tree, rowid, emit->scratch, len);
}

/* Reads the root pages SQLite allocated for every raw-written object; the
 * graft overwrites these pages in place so sqlite_master never changes. */
static int raw_capture_schema_roots(CodeLensDb *db, RawEmitter *emit)
{
    static const char *const names[CP_TREE_COUNT] = {
        [CP_TREE_FILE] = "File",
        [CP_TREE_SYMBOL] = "Symbol",
        [CP_TREE_TERM] = "Term",
        [CP_TREE_REFDATA] = "RefData",
        [CP_TREE_KEYWORDDATA] = "KeywordData",
        [CP_TREE_ALIAS] = "Alias",
        [CP_TREE_REFERRED] = "Referred",
        [CP_TREE_AUTO_FILE] = "sqlite_autoindex_File_1",
        [CP_TREE_AUTO_SYMBOL] = "sqlite_autoindex_Symbol_1",
        [CP_TREE_AUTO_ALIAS] = "sqlite_autoindex_Alias_1",
        [CP_TREE_AUTO_REFERRED] = "sqlite_autoindex_Referred_1",
        [CP_TREE_IDX_SYMBOL_NAME] = "idx_symbol_name",
        [CP_TREE_IDX_REF_BASE] = "idx_ref_symbol_base",
        [CP_TREE_IDX_KEYWORD_BASE] = "idx_keyword_base",
        [CP_TREE_IDX_TERM_TEXT] = "idx_term_text",
        [CP_TREE_FTS_DATA] = "SymbolFts_data",
        [CP_TREE_FTS_DOCSIZE] = "SymbolFts_docsize",
    };
    sqlite3_stmt *stmt = nullptr;
    int rc = -1;

    if (db_prepare(db, "SELECT name, rootpage FROM sqlite_master WHERE rootpage > 1", &stmt) !=
        0) {
        return -1;
    }
    while (sqlite3_step(stmt) == SQLITE_ROW) {
        const char *name = (const char *)sqlite3_column_text(stmt, 0);
        int64_t rootpage = sqlite3_column_int64(stmt, 1);

        if (name == nullptr) {
            continue;
        }
        for (size_t i = 0U; i < CP_TREE_COUNT; i++) {
            if (strcmp(name, names[i]) == 0) {
                if ((rootpage <= 1) || (rootpage > (int64_t)UINT32_MAX)) {
                    goto done;
                }
                emit->schema_roots[i] = (uint32_t)rootpage;
                break;
            }
        }
    }
    rc = 0;
    for (size_t i = 0U; i < CP_TREE_COUNT; i++) {
        if (emit->schema_roots[i] <= 1U) {
            rc = -1;
            break;
        }
    }

done:
    (void)sqlite3_finalize(stmt);
    return rc;
}

/* Graft bookkeeping shared by the table-tree and index-tree writers. */
typedef struct {
    int fd;
    uint32_t next_pgno;
    uint8_t root_content[CP_TREE_COUNT][CP_RAW_PAGE_SIZE];
    bool root_set[CP_TREE_COUNT];
    const uint32_t *schema_roots;
} RawGraft;

/* Rebases one streamed table tree into the file: fixups get global page
 * numbers (the root's number is skipped, since the root content moves to
 * its schema-allocated page), the non-root pages are appended, and the
 * root content is captured for the final in-place overwrite. */
/* Patches one finalized table tree's child pointers for its global page
 * range and writes every page except the root, whose content is captured
 * for the in-place overwrite. Does not touch shared graft state. */
static int raw_graft_table_tree_at(RawGraft *graft,
                                   RawTableTree *tree,
                                   size_t tree_index,
                                   uint32_t base)
{
    uint32_t root = tree->root_local;
    size_t total = tree->page_count;

    if ((root == 0U) || (((uint64_t)base + total) - 1U > (uint64_t)UINT32_MAX)) {
        return -1;
    }
    for (size_t i = 0U; i < tree->fixup_count; i++) {
        uint8_t *at = tree->data + tree->fixups[i];
        uint32_t local = ((uint32_t)at[0] << 24U) | ((uint32_t)at[1] << 16U) |
                         ((uint32_t)at[2] << 8U) | (uint32_t)at[3];

        if ((local == 0U) || (local == root) || (local > total)) {
            return -1;
        }
        raw_store_be(at, local < root ? ((uint64_t)base + local) - 1U
                                      : ((uint64_t)base + local) - 2U, 4U);
    }
    (void)memcpy(graft->root_content[tree_index],
                 raw_tree_page(tree, root),
                 CP_RAW_PAGE_SIZE);
    graft->root_set[tree_index] = true;
    if (root > 1U) {
        if (raw_write_all(graft->fd,
                          tree->data,
                          ((size_t)root - 1U) * CP_RAW_PAGE_SIZE,
                          (off_t)((uint64_t)base - 1U) * (off_t)CP_RAW_PAGE_SIZE) != 0) {
            return -1;
        }
    }
    if (root < total) {
        if (raw_write_all(graft->fd,
                          tree->data + ((size_t)root * CP_RAW_PAGE_SIZE),
                          (total - (size_t)root) * CP_RAW_PAGE_SIZE,
                          (off_t)(((uint64_t)base + root) - 2U) * (off_t)CP_RAW_PAGE_SIZE) !=
            0) {
            return -1;
        }
    }
    return 0;
}

/* One table-tree graft job for the parallel writer. */
typedef struct {
    RawGraft *graft;
    RawTableTree *tree;
    size_t tree_index;
    uint32_t base;
    int rc;
} RawTableGraftJob;

typedef struct {
    RawTableGraftJob *jobs[7];
    size_t count;
} RawTableGraftGroup;

static void raw_table_graft_run_group(RawTableGraftGroup *group)
{
    for (size_t i = 0U; i < group->count; i++) {
        RawTableGraftJob *job = group->jobs[i];

        job->rc = raw_graft_table_tree_at(job->graft, job->tree, job->tree_index, job->base);
    }
}

static void *raw_table_graft_thread_main(void *arg)
{
    raw_table_graft_run_group(arg);
    return nullptr;
}

/* Finalizes and grafts all seven table trees. Page ranges are assigned
 * sequentially in tree order; the patch+write work is then spread over
 * three threads (this one plus two helpers) by a greedy largest-first
 * assignment so the groups finish at about the same time. */
static int raw_graft_all_table_trees(RawGraft *graft, RawEmitter *emit)
{
    RawTableTree *trees[7] = {&emit->file,
                              &emit->symbol,
                              &emit->term,
                              &emit->refdata,
                              &emit->keyworddata,
                              &emit->alias,
                              &emit->referred};
    static const size_t tree_indices[7] = {CP_TREE_FILE,
                                           CP_TREE_SYMBOL,
                                           CP_TREE_TERM,
                                           CP_TREE_REFDATA,
                                           CP_TREE_KEYWORDDATA,
                                           CP_TREE_ALIAS,
                                           CP_TREE_REFERRED};
    RawTableGraftJob jobs[7];
    RawTableGraftGroup groups[3] = {0};
    size_t group_pages[3] = {0U, 0U, 0U};
    bool assigned[7] = {false};
    pthread_t threads[2];
    bool started[2] = {false, false};
    uint64_t next = graft->next_pgno;
    int rc = 0;

    for (size_t i = 0U; i < 7U; i++) {
        if (raw_tree_finalize(trees[i]) != 0) {
            return -1;
        }
        jobs[i] = (RawTableGraftJob){.graft = graft,
                                     .tree = trees[i],
                                     .tree_index = tree_indices[i],
                                     .base = (uint32_t)next,
                                     .rc = -1};
        next += trees[i]->page_count - 1U;
        if (next > (uint64_t)UINT32_MAX) {
            return -1;
        }
    }

    for (size_t pick = 0U; pick < 7U; pick++) {
        size_t best = 7U;
        size_t target = 0U;

        for (size_t i = 0U; i < 7U; i++) {
            if (!assigned[i] && ((best == 7U) || (jobs[i].tree->page_count >
                                                  jobs[best].tree->page_count))) {
                best = i;
            }
        }
        assigned[best] = true;
        for (size_t g = 1U; g < 3U; g++) {
            if (group_pages[g] < group_pages[target]) {
                target = g;
            }
        }
        groups[target].jobs[groups[target].count] = &jobs[best];
        groups[target].count++;
        group_pages[target] += jobs[best].tree->page_count;
    }

    for (size_t t = 0U; t < 2U; t++) {
        if (groups[t + 1U].count > 0U) {
            started[t] = pthread_create(&threads[t],
                                        nullptr,
                                        raw_table_graft_thread_main,
                                        &groups[t + 1U]) == 0;
            if (!started[t]) {
                raw_table_graft_run_group(&groups[t + 1U]);
            }
        }
    }
    raw_table_graft_run_group(&groups[0]);
    for (size_t t = 0U; t < 2U; t++) {
        if (started[t]) {
            (void)pthread_join(threads[t], nullptr);
        }
    }

    for (size_t i = 0U; i < 7U; i++) {
        if (jobs[i].rc != 0) {
            rc = -1;
        }
        raw_tree_destroy(trees[i]);
    }
    if (rc == 0) {
        graft->next_pgno = (uint32_t)next;
    }
    return rc;
}

/* Builds one index b-tree from sorted payloads directly at its global page
 * range and writes it; the root (always the last page) is captured for the
 * in-place overwrite. */
static int raw_graft_index_tree(RawGraft *graft, RawPayloads *payloads, size_t tree_index)
{
    RawPages pages = {0};
    uint32_t root = 0U;
    int rc = -1;

    pages.base_pgno = graft->next_pgno;
    if (raw_build_index_btree(&pages, payloads, &root) != 0) {
        goto done;
    }
    if ((pages.count == 0U) ||
        (root != (pages.base_pgno + (uint32_t)pages.count) - 1U)) {
        goto done;
    }
    (void)memcpy(graft->root_content[tree_index],
                 pages.data + ((pages.count - 1U) * CP_RAW_PAGE_SIZE),
                 CP_RAW_PAGE_SIZE);
    graft->root_set[tree_index] = true;
    if (pages.count > 1U) {
        if (raw_write_all(graft->fd,
                          pages.data,
                          (pages.count - 1U) * CP_RAW_PAGE_SIZE,
                          (off_t)((uint64_t)pages.base_pgno - 1U) *
                              (off_t)CP_RAW_PAGE_SIZE) != 0) {
            goto done;
        }
    }
    graft->next_pgno = (pages.base_pgno + (uint32_t)pages.count) - 1U;
    rc = 0;

done:
    raw_pages_destroy(&pages);
    raw_payloads_reset(payloads);
    return rc;
}

static int raw_text_keys_from_ids(const char *const *ids, size_t count, RawTextKey **out_keys)
{
    RawTextKey *keys = nullptr;

    if (count > 0U) {
        keys = malloc(count * sizeof(RawTextKey));
        if (keys == nullptr) {
            return -1;
        }
        for (size_t i = 0U; i < count; i++) {
            size_t len = strlen(ids[i]);

            if ((len > (size_t)UINT32_MAX) || ((i + 1U) > (size_t)UINT32_MAX)) {
                free(keys);
                return -1;
            }
            keys[i].text = ids[i];
            keys[i].len = (uint32_t)len;
            keys[i].rowid = (uint32_t)(i + 1U);
        }
    }
    *out_keys = keys;
    return 0;
}

/* Index-payload prep pool: the eight index payload sets (four autoindexes,
 * four secondary indexes) depend only on data frozen when the walk ends
 * (id vectors, mirrors, interner), so side threads sort and encode them
 * while the coordinator runs the FTS adopt, Repo row, COMMIT and close.
 * The graft then only packs b-tree pages and writes. */
enum {
    CP_PREP_AUTO_FILE,
    CP_PREP_AUTO_SYMBOL,
    CP_PREP_AUTO_ALIAS,
    CP_PREP_AUTO_REFERRED,
    CP_PREP_IDX_SYMBOL_NAME,
    CP_PREP_IDX_REF_BASE,
    CP_PREP_IDX_KEYWORD_BASE,
    CP_PREP_IDX_TERM_TEXT,
    CP_PREP_TASK_COUNT
};

#define CP_PREP_THREADS 3U

typedef struct RawIndexPrep RawIndexPrep;

typedef struct {
    RawIndexPrep *prep;
    size_t group;
} RawPrepThreadArg;

struct RawIndexPrep {
    IndexContext *ctx;
    RawPayloads payloads[CP_PREP_TASK_COUNT];
    int task_rc[CP_PREP_TASK_COUNT];
    pthread_t threads[CP_PREP_THREADS];
    RawPrepThreadArg thread_args[CP_PREP_THREADS];
    bool thread_started[CP_PREP_THREADS];
    bool started;
};

static int raw_prep_task_run(RawIndexPrep *prep, int task)
{
    IndexContext *ctx = prep->ctx;
    const RawEmitter *emit = &ctx->raw_emit;
    const RawIndexMirror *mirror = &ctx->raw_mirror;
    RawPayloads *payloads = &prep->payloads[task];
    RawTextKey *keys = nullptr;
    int rc = -1;

    switch (task) {
    case CP_PREP_AUTO_FILE:
        rc = raw_text_keys_from_ids(emit->file_ids, emit->file_id_count, &keys);
        if (rc == 0) {
            rc = raw_payloads_from_text_keys(keys, emit->file_id_count, payloads, true);
        }
        break;
    case CP_PREP_AUTO_SYMBOL:
        rc = raw_text_keys_from_ids(emit->symbol_ids, emit->symbol_id_count, &keys);
        if (rc == 0) {
            rc = raw_payloads_from_text_keys(keys, emit->symbol_id_count, payloads, true);
        }
        break;
    case CP_PREP_AUTO_ALIAS:
        rc = raw_text_keys_from_ids(emit->alias_ids, emit->alias_id_count, &keys);
        if (rc == 0) {
            rc = raw_payloads_from_text_keys(keys, emit->alias_id_count, payloads, true);
        }
        break;
    case CP_PREP_AUTO_REFERRED:
        rc = raw_text_keys_from_ids(emit->referred_ids, emit->referred_id_count, &keys);
        if (rc == 0) {
            rc = raw_payloads_from_text_keys(keys, emit->referred_id_count, payloads, true);
        }
        break;
    case CP_PREP_IDX_SYMBOL_NAME:
        if (mirror->symbol_count > 0U) {
            keys = malloc(mirror->symbol_count * sizeof(RawTextKey));
            if (keys == nullptr) {
                break;
            }
            for (size_t i = 0U; i < mirror->symbol_count; i++) {
                size_t len = strlen(mirror->symbol_names[i]);

                if ((len > (size_t)UINT32_MAX) || ((i + 1U) > (size_t)UINT32_MAX)) {
                    free(keys);
                    return -1;
                }
                keys[i].text = mirror->symbol_names[i];
                keys[i].len = (uint32_t)len;
                keys[i].rowid = (uint32_t)(i + 1U);
            }
        }
        rc = raw_payloads_from_text_keys(keys, mirror->symbol_count, payloads, false);
        break;
    case CP_PREP_IDX_REF_BASE:
        return raw_payloads_from_terms(mirror->ref_terms,
                                       mirror->ref_count,
                                       ctx->interner.count,
                                       payloads);
    case CP_PREP_IDX_KEYWORD_BASE:
        return raw_payloads_from_terms(mirror->keyword_terms,
                                       mirror->keyword_count,
                                       ctx->interner.count,
                                       payloads);
    case CP_PREP_IDX_TERM_TEXT:
        rc = raw_interner_text_keys(&ctx->interner, &keys);
        if (rc == 0) {
            rc = raw_payloads_from_text_keys(keys, ctx->interner.count, payloads, true);
        }
        break;
    default:
        return -1;
    }
    free(keys);
    return rc;
}

/* Static task partition balanced by row counts: the Ref counting sort is
 * the largest single task and gets its own thread. */
static const int raw_prep_partition[CP_PREP_THREADS][4] = {
    {CP_PREP_AUTO_FILE, CP_PREP_AUTO_SYMBOL, CP_PREP_AUTO_ALIAS, CP_PREP_IDX_SYMBOL_NAME},
    {CP_PREP_IDX_REF_BASE, -1, -1, -1},
    {CP_PREP_IDX_KEYWORD_BASE, CP_PREP_IDX_TERM_TEXT, CP_PREP_AUTO_REFERRED, -1},
};

static void raw_prep_run_group(RawIndexPrep *prep, size_t group)
{
    for (size_t i = 0U; i < 4U; i++) {
        int task = raw_prep_partition[group][i];

        if (task >= 0) {
            prep->task_rc[task] = raw_prep_task_run(prep, task);
        }
    }
}

static void *raw_prep_thread_main(void *arg)
{
    RawPrepThreadArg *targ = arg;

    raw_prep_run_group(targ->prep, targ->group);
    return nullptr;
}

static void raw_index_prep_start(IndexContext *ctx, RawIndexPrep *prep)
{
    (void)memset(prep, 0, sizeof(*prep));
    prep->ctx = ctx;
    for (int t = 0; t < (int)CP_PREP_TASK_COUNT; t++) {
        prep->task_rc[t] = -1;
    }
    prep->started = true;
    for (size_t g = 0U; g < (size_t)CP_PREP_THREADS; g++) {
        prep->thread_args[g].prep = prep;
        prep->thread_args[g].group = g;
        prep->thread_started[g] = pthread_create(&prep->threads[g],
                                                 nullptr,
                                                 raw_prep_thread_main,
                                                 &prep->thread_args[g]) == 0;
        if (!prep->thread_started[g]) {
            raw_prep_run_group(prep, g);
        }
    }
}

static void raw_index_prep_join(RawIndexPrep *prep)
{
    if (!prep->started) {
        return;
    }
    for (size_t g = 0U; g < (size_t)CP_PREP_THREADS; g++) {
        if (prep->thread_started[g]) {
            (void)pthread_join(prep->threads[g], nullptr);
            prep->thread_started[g] = false;
        }
    }
}

static void raw_index_prep_destroy(RawIndexPrep *prep)
{
    raw_index_prep_join(prep);
    if (prep->started) {
        for (int t = 0; t < (int)CP_PREP_TASK_COUNT; t++) {
            raw_payloads_destroy(&prep->payloads[t]);
        }
        prep->started = false;
    }
}

/* Appends every raw b-tree to the committed staging database and overwrites
 * the schema-allocated root pages in place. On failure the staging file is
 * simply abandoned; the caller rebuilds with the classic emitter. */
static int raw_tables_graft(const char *db_path, IndexContext *ctx, RawIndexPrep *prep)
{
    RawEmitter *emit = &ctx->raw_emit;
    RawGraft *graft = nullptr;
    RawTextKey *text_keys = nullptr;
    uint8_t header[40];
    uint8_t count_be[4];
    uint32_t page_size;
    uint32_t old_page_count;
    struct stat st;
    int rc = -1;

    graft = calloc(1U, sizeof(RawGraft));
    if (graft == nullptr) {
        return -1;
    }
    graft->fd = open(db_path, O_RDWR);
    if (graft->fd < 0) {
        free(graft);
        return -1;
    }
    if ((fstat(graft->fd, &st) != 0) ||
        (pread(graft->fd, header, sizeof(header), 0) != (ssize_t)sizeof(header))) {
        goto done;
    }
    page_size = ((uint32_t)header[16] << 8U) | (uint32_t)header[17];
    old_page_count = ((uint32_t)header[28] << 24U) | ((uint32_t)header[29] << 16U) |
                     ((uint32_t)header[30] << 8U) | (uint32_t)header[31];
    if ((page_size != CP_RAW_PAGE_SIZE) || (header[20] != 0U) ||
        ((uint64_t)st.st_size != ((uint64_t)old_page_count * CP_RAW_PAGE_SIZE))) {
        goto done;
    }
    graft->next_pgno = old_page_count + 1U;
    graft->schema_roots = emit->schema_roots;

    /* Term rows stream in id order straight from the interner. */
    if (raw_interner_text_keys(&ctx->interner, &text_keys) != 0) {
        goto done;
    }
    for (size_t i = 0U; i < ctx->interner.count; i++) {
        RawColumn cols[2] = {CP_RAW_ROWID_NULL,
                             CP_RAW_TEXT_LEN(text_keys[i].text, text_keys[i].len)};

        if (raw_emit_encoded(emit, &emit->term, (int64_t)(i + 1U), cols, 2U) != 0) {
            goto done;
        }
    }

    if (raw_graft_all_table_trees(graft, emit) != 0) {
        goto done;
    }

    /* Index trees from the pre-sorted payload sets, in DDL rootpage order:
     * autoindexes on the TEXT primary keys, then the secondary indexes. */
    raw_index_prep_join(prep);
    {
        static const int prep_order[8][2] = {
            {CP_PREP_AUTO_FILE, CP_TREE_AUTO_FILE},
            {CP_PREP_AUTO_SYMBOL, CP_TREE_AUTO_SYMBOL},
            {CP_PREP_AUTO_ALIAS, CP_TREE_AUTO_ALIAS},
            {CP_PREP_AUTO_REFERRED, CP_TREE_AUTO_REFERRED},
            {CP_PREP_IDX_SYMBOL_NAME, CP_TREE_IDX_SYMBOL_NAME},
            {CP_PREP_IDX_REF_BASE, CP_TREE_IDX_REF_BASE},
            {CP_PREP_IDX_KEYWORD_BASE, CP_TREE_IDX_KEYWORD_BASE},
            {CP_PREP_IDX_TERM_TEXT, CP_TREE_IDX_TERM_TEXT},
        };

        for (size_t i = 0U; i < 8U; i++) {
            if ((prep->task_rc[prep_order[i][0]] != 0) ||
                (raw_graft_index_tree(graft,
                                      &prep->payloads[prep_order[i][0]],
                                      (size_t)prep_order[i][1]) != 0)) {
                goto done;
            }
        }
    }

    for (size_t i = 0U; i < CP_TREE_COUNT; i++) {
        if (!graft->root_set[i]) {
            continue; /* e.g. FTS shadow roots, grafted separately later */
        }
        if (raw_write_all(graft->fd,
                          graft->root_content[i],
                          CP_RAW_PAGE_SIZE,
                          (off_t)((uint64_t)emit->schema_roots[i] - 1U) *
                              (off_t)CP_RAW_PAGE_SIZE) != 0) {
            goto done;
        }
    }
    raw_store_be(count_be, (uint64_t)graft->next_pgno - 1U, 4U);
    if (pwrite(graft->fd, count_be, sizeof(count_be), CP_RAW_HDR_PAGE_COUNT_OFFSET) !=
        (ssize_t)sizeof(count_be)) {
        goto done;
    }
    rc = 0;

done:
    (void)close(graft->fd);
    free(graft);
    free(text_keys);
    return rc;
}

/* FTS sidecar: builds SymbolFts in a separate database file on a side
 * thread while the emit thread streams rows, hiding the FTS build behind
 * the emit and secondary-index phases instead of paying for it serially at
 * the tail. The emit thread mirrors each Symbol row (in rowid order) to a
 * FIFO; the side thread tokenizes and inserts into an identically-declared
 * SymbolFts in the sidecar file; after the secondary indexes are built the
 * finished shadow tables are copied into the staging database. The logical
 * FTS content matches an in-place 'rebuild' (same rows, same order - the
 * rebuild scans Symbol in rowid order); segment bytes may differ, which is
 * fine because nothing depends on shadow-table bytes. Any failure at any
 * point falls back to the classic in-place rebuild, so the sidecar can
 * never affect correctness. CODE_LENS_FTS_SIDECAR=0 disables it. */
typedef struct FtsSidecarRow {
    struct FtsSidecarRow *next;
    int64_t rowid;
    /* All five point into the trailing buffer of the same allocation. */
    const char *name;
    const char *namespace_name;
    const char *file_path;
    const char *doc;
    const char *content;
} FtsSidecarRow;

struct FtsSidecar {
    pthread_t thread;
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    FtsSidecarRow *head;
    FtsSidecarRow *tail;
    bool done;
    bool thread_started;
    bool joined;
    bool enqueue_failed;
    int thread_result;
    const char *db_path;
    /* Raw path: the FTS builds in a :memory: database whose connection the
     * thread leaves open; after the join the main thread attaches the
     * staging file to this same connection and copies the shadow tables
     * out (sequential use of one connection, so SQLITE_THREADSAFE=2
     * holds). No sidecar file ever touches disk. The thread also encodes
     * the two big shadow tables into raw page trees so the main thread can
     * graft them instead of copying rows through SQL. */
    bool in_memory;
    bool db_kept_open;
    bool trees_ready;
    RawTableTree data_tree;
    RawTableTree docsize_tree;
    CodeLensDb db;
};

static void fts_sidecar_free_rows(FtsSidecarRow *row)
{
    while (row != nullptr) {
        FtsSidecarRow *next = row->next;

        free(row);
        row = next;
    }
}

/* Encodes the finished in-memory FTS's two big shadow tables into raw page
 * trees on the sidecar thread, so the emit thread grafts pages instead of
 * copying rows through SQL. _idx (a WITHOUT ROWID table the raw writer
 * does not encode) and _config stay SQL-copied; both are tiny. Failure
 * just leaves trees_ready unset and the SQL adopt handles everything. */
static int fts_sidecar_encode_trees(FtsSidecar *sidecar)
{
    static const char *const row_sql[2] = {
        "SELECT id, block FROM SymbolFts_data ORDER BY id",
        "SELECT id, sz FROM SymbolFts_docsize ORDER BY id",
    };
    RawTableTree *trees[2] = {&sidecar->data_tree, &sidecar->docsize_tree};
    RawEmitter scratch = {0};
    int rc = 0;

    for (size_t t = 0U; (rc == 0) && (t < 2U); t++) {
        sqlite3_stmt *stmt = nullptr;
        int step;

        if (db_prepare(&sidecar->db, row_sql[t], &stmt) != 0) {
            rc = -1;
            break;
        }
        while ((step = sqlite3_step(stmt)) == SQLITE_ROW) {
            int64_t rowid = sqlite3_column_int64(stmt, 0);
            const void *blob = sqlite3_column_blob(stmt, 1);
            int bytes = sqlite3_column_bytes(stmt, 1);
            /* FTS5 never stores NULL blobs in its shadow tables, so a null
             * pointer here only ever means a zero-length blob. */
            RawColumn cols[2] = {CP_RAW_ROWID_NULL,
                                 {.kind = CP_COL_BLOB,
                                  .value = 0,
                                  .text = (const char *)blob,
                                  .text_len = (size_t)bytes}};

            if ((bytes < 0) ||
                (raw_emit_encoded(&scratch, trees[t], rowid, cols, 2U) != 0)) {
                rc = -1;
                break;
            }
        }
        if ((rc == 0) && (step != SQLITE_DONE)) {
            rc = -1;
        }
        (void)sqlite3_finalize(stmt);
    }
    free(scratch.scratch);
    if (rc == 0) {
        sidecar->trees_ready = true;
    }
    return rc;
}

/* Pops the whole pending chain, waiting until at least one row is queued or
 * the producer is done. Returns nullptr only when drained and done. */
static FtsSidecarRow *fts_sidecar_pop_all(FtsSidecar *sidecar)
{
    FtsSidecarRow *rows;

    (void)pthread_mutex_lock(&sidecar->mutex);
    while ((sidecar->head == nullptr) && !sidecar->done) {
        (void)pthread_cond_wait(&sidecar->cond, &sidecar->mutex);
    }
    rows = sidecar->head;
    sidecar->head = nullptr;
    sidecar->tail = nullptr;
    (void)pthread_mutex_unlock(&sidecar->mutex);
    return rows;
}

static void *fts_sidecar_thread_main(void *arg)
{
    FtsSidecar *sidecar = arg;
    CodeLensDb db;
    sqlite3_stmt *stmt = nullptr;
    bool db_open = false;
    int rc = 0;

    (void)memset(&db, 0, sizeof(db));
    rc = code_lens_db_open_build(&db, sidecar->in_memory ? ":memory:" : sidecar->db_path);
    if (rc == 0) {
        db_open = true;
        rc = code_lens_db_exec(&db, CODE_LENS_SYMBOL_FTS_DDL ";BEGIN");
    }
    if (rc == 0) {
        /* Let the whole index accumulate in the in-memory hash so the final
         * COMMIT writes one segment instead of flushing several level-0
         * segments and auto-merging them (halves the post-seal tail). The
         * setting is per-connection and does not persist in the database. */
        rc = code_lens_db_exec(
            &db, "INSERT INTO SymbolFts(SymbolFts, rank) VALUES('hashsize', 8388608)");
    }
    if (rc == 0) {
        static const char insert_sql[] =
            "INSERT INTO SymbolFts(rowid, name, namespace, filePath, doc, content)"
            " VALUES (?1, ?2, ?3, ?4, ?5, ?6)";

        if (sqlite3_prepare_v2(db.handle, insert_sql, -1, &stmt, nullptr) != SQLITE_OK) {
            rc = report_sqlite_error(db.handle, "prepare FTS sidecar insert");
        }
    }

    for (;;) {
        FtsSidecarRow *rows = fts_sidecar_pop_all(sidecar);

        if (rows == nullptr) {
            break;
        }
        for (FtsSidecarRow *row = rows; (rc == 0) && (row != nullptr); row = row->next) {
            if ((sqlite3_bind_int64(stmt, 1, row->rowid) != SQLITE_OK) ||
                (bind_text(stmt, 2, row->name) != 0) ||
                (bind_text(stmt, 3, row->namespace_name) != 0) ||
                (bind_text(stmt, 4, row->file_path) != 0) ||
                (bind_text(stmt, 5, row->doc) != 0) ||
                (bind_text(stmt, 6, row->content) != 0)) {
                rc = -1;
            } else {
                rc = writer_step(stmt);
            }
        }
        /* Keep draining after an error so the producer's chain is freed. */
        fts_sidecar_free_rows(rows);
    }

    if (rc == 0) {
        rc = code_lens_db_exec(&db, "COMMIT");
    }
    if (stmt != nullptr) {
        (void)sqlite3_finalize(stmt);
    }
    if (db_open) {
        if ((rc == 0) && sidecar->in_memory) {
            /* Keep the connection (and with it the memory database) alive
             * for the post-COMMIT adopt; the main thread closes it. Encode
             * the big shadow tables into graftable page trees here, off
             * the emit thread's clock. */
            sidecar->db = db;
            sidecar->db_kept_open = true;
            (void)fts_sidecar_encode_trees(sidecar);
        } else {
            code_lens_db_close(&db);
        }
    }
    sidecar->thread_result = rc;
    return nullptr;
}

/* Starts the sidecar thread. On failure the sidecar is left inert (indexing
 * proceeds with the in-place rebuild). in_memory selects the raw path's
 * file-less variant; db_path is only read otherwise. */
static int fts_sidecar_start(FtsSidecar *sidecar, const char *db_path, bool in_memory)
{
    (void)memset(sidecar, 0, sizeof(*sidecar));
    sidecar->db_path = db_path;
    sidecar->in_memory = in_memory;
    if (pthread_mutex_init(&sidecar->mutex, nullptr) != 0) {
        return -1;
    }
    if (pthread_cond_init(&sidecar->cond, nullptr) != 0) {
        (void)pthread_mutex_destroy(&sidecar->mutex);
        return -1;
    }
    if (pthread_create(&sidecar->thread, nullptr, fts_sidecar_thread_main, sidecar) != 0) {
        (void)pthread_cond_destroy(&sidecar->cond);
        (void)pthread_mutex_destroy(&sidecar->mutex);
        return -1;
    }
    sidecar->thread_started = true;
    return 0;
}

/* Packs one Symbol FTS row into a single allocation (header + trailing
 * string buffer). Shared by the emit-thread enqueue fallback and the parse
 * workers, which pre-pack rows with the rowid patched in at emit time. */
static FtsSidecarRow *fts_row_pack(int64_t rowid,
                                   const char *name,
                                   const char *namespace_name,
                                   const char *file_path,
                                   const char *doc,
                                   const char *content)
{
    static const char empty[] = "";
    const char *fields[5];
    size_t lens[5];
    size_t total = sizeof(FtsSidecarRow);
    FtsSidecarRow *row;
    char *cursor;

    fields[0] = name != nullptr ? name : empty;
    fields[1] = namespace_name != nullptr ? namespace_name : empty;
    fields[2] = file_path != nullptr ? file_path : empty;
    fields[3] = doc != nullptr ? doc : empty;
    fields[4] = content != nullptr ? content : empty;
    for (size_t i = 0U; i < 5U; i++) {
        lens[i] = strlen(fields[i]);
        total += lens[i] + 1U;
    }
    row = malloc(total);
    if (row == nullptr) {
        return nullptr;
    }
    row->next = nullptr;
    row->rowid = rowid;
    cursor = (char *)(row + 1);
    for (size_t i = 0U; i < 5U; i++) {
        (void)memcpy(cursor, fields[i], lens[i] + 1U);
        fields[i] = cursor;
        cursor += lens[i] + 1U;
    }
    row->name = fields[0];
    row->namespace_name = fields[1];
    row->file_path = fields[2];
    row->doc = fields[3];
    row->content = fields[4];
    return row;
}

/* Queues one packed row for the side thread, patching in the final rowid.
 * Takes ownership: a poisoned sidecar frees the row and drops it. */
static void fts_sidecar_push_row(FtsSidecar *sidecar, FtsSidecarRow *row, int64_t rowid)
{
    if (sidecar->enqueue_failed) {
        free(row);
        return;
    }
    row->next = nullptr;
    row->rowid = rowid;
    (void)pthread_mutex_lock(&sidecar->mutex);
    if (sidecar->tail != nullptr) {
        sidecar->tail->next = row;
    } else {
        sidecar->head = row;
    }
    sidecar->tail = row;
    (void)pthread_cond_signal(&sidecar->cond);
    (void)pthread_mutex_unlock(&sidecar->mutex);
}

/* Mirrors one Symbol row to the side thread. Copies every field: the parsed
 * data is arena-backed and does not outlive the emit call. A failed copy
 * poisons the sidecar (falls back to rebuild) but never fails the caller. */
static void fts_sidecar_enqueue(FtsSidecar *sidecar,
                                int64_t rowid,
                                const char *name,
                                const char *namespace_name,
                                const char *file_path,
                                const char *doc,
                                const char *content)
{
    FtsSidecarRow *row;

    if (sidecar->enqueue_failed) {
        return;
    }
    row = fts_row_pack(rowid, name, namespace_name, file_path, doc, content);
    if (row == nullptr) {
        sidecar->enqueue_failed = true;
        return;
    }
    fts_sidecar_push_row(sidecar, row, rowid);
}

/* Tells the side thread no more rows are coming so it can commit and close
 * while the main thread builds secondary indexes. */
static void fts_sidecar_seal(FtsSidecar *sidecar)
{
    if (!sidecar->thread_started) {
        return;
    }
    (void)pthread_mutex_lock(&sidecar->mutex);
    sidecar->done = true;
    (void)pthread_cond_signal(&sidecar->cond);
    (void)pthread_mutex_unlock(&sidecar->mutex);
}

/* Joins the side thread; returns 0 only if every row was mirrored and the
 * sidecar database committed cleanly. */
static int fts_sidecar_finish(FtsSidecar *sidecar)
{
    if (!sidecar->thread_started) {
        return -1;
    }
    fts_sidecar_seal(sidecar);
    if (!sidecar->joined) {
        (void)pthread_join(sidecar->thread, nullptr);
        sidecar->joined = true;
    }
    return (sidecar->thread_result == 0 && !sidecar->enqueue_failed) ? 0 : -1;
}

static void fts_sidecar_destroy(FtsSidecar *sidecar)
{
    if (sidecar->thread_started && !sidecar->joined) {
        (void)fts_sidecar_finish(sidecar);
    }
    if (sidecar->db_kept_open) {
        code_lens_db_close(&sidecar->db);
        sidecar->db_kept_open = false;
    }
    raw_tree_destroy(&sidecar->data_tree);
    raw_tree_destroy(&sidecar->docsize_tree);
    if (sidecar->thread_started) {
        (void)pthread_cond_destroy(&sidecar->cond);
        (void)pthread_mutex_destroy(&sidecar->mutex);
    }
    fts_sidecar_free_rows(sidecar->head);
    sidecar->head = nullptr;
    sidecar->tail = nullptr;
    sidecar->thread_started = false;
}

/* Adopts the sidecar's finished FTS shadow tables into the staging database
 * (attached as fts_side), replacing the empty state left by CREATE VIRTUAL
 * TABLE. Runs inside the load transaction on the classic path; the raw path
 * runs it post-COMMIT on its own connection. */
static const char fts_sidecar_copy_sql[] =
    "DELETE FROM main.SymbolFts_data;"
    "DELETE FROM main.SymbolFts_idx;"
    "DELETE FROM main.SymbolFts_docsize;"
    "DELETE FROM main.SymbolFts_config;"
    "INSERT INTO main.SymbolFts_data SELECT * FROM fts_side.SymbolFts_data;"
    "INSERT INTO main.SymbolFts_idx SELECT * FROM fts_side.SymbolFts_idx;"
    "INSERT INTO main.SymbolFts_docsize SELECT * FROM fts_side.SymbolFts_docsize;"
    "INSERT INTO main.SymbolFts_config SELECT * FROM fts_side.SymbolFts_config;";

static bool fts_sidecar_enabled(void)
{
    const char *value = getenv("CODE_LENS_FTS_SIDECAR");

    return (value == nullptr) || (strcmp(value, "0") != 0);
}

/* Grafts the sidecar's encoded _data/_docsize trees into the staging file:
 * pages append past the current count, the schema-allocated roots are
 * overwritten in place, and the header page count is updated - the same
 * mechanics as the main table graft, scoped to two trees. On failure the
 * file is truncated back so the SQL adopt fallback sees it unchanged. */
static int fts_graft_shadow_trees(FtsSidecar *sidecar,
                                  const char *db_path,
                                  const uint32_t *schema_roots)
{
    RawTableTree *trees[2] = {&sidecar->data_tree, &sidecar->docsize_tree};
    static const size_t tree_indices[2] = {CP_TREE_FTS_DATA, CP_TREE_FTS_DOCSIZE};
    RawGraft *graft;
    uint8_t header[40];
    uint8_t count_be[4];
    uint32_t page_size;
    uint32_t old_page_count;
    struct stat st;
    uint64_t next;
    int rc = -1;

    graft = calloc(1U, sizeof(RawGraft));
    if (graft == nullptr) {
        return -1;
    }
    graft->fd = open(db_path, O_RDWR);
    if (graft->fd < 0) {
        free(graft);
        return -1;
    }
    if ((fstat(graft->fd, &st) != 0) ||
        (pread(graft->fd, header, sizeof(header), 0) != (ssize_t)sizeof(header))) {
        goto done;
    }
    page_size = ((uint32_t)header[16] << 8U) | (uint32_t)header[17];
    old_page_count = ((uint32_t)header[28] << 24U) | ((uint32_t)header[29] << 16U) |
                     ((uint32_t)header[30] << 8U) | (uint32_t)header[31];
    if ((page_size != CP_RAW_PAGE_SIZE) || (header[20] != 0U) ||
        ((uint64_t)st.st_size != ((uint64_t)old_page_count * CP_RAW_PAGE_SIZE))) {
        goto done;
    }
    graft->next_pgno = old_page_count + 1U;
    graft->schema_roots = schema_roots;

    next = graft->next_pgno;
    for (size_t i = 0U; i < 2U; i++) {
        if (raw_tree_finalize(trees[i]) != 0) {
            goto done;
        }
        if (raw_graft_table_tree_at(graft, trees[i], tree_indices[i], (uint32_t)next) != 0) {
            goto done;
        }
        next += trees[i]->page_count - 1U;
        if (next > (uint64_t)UINT32_MAX) {
            goto done;
        }
    }
    graft->next_pgno = (uint32_t)next;

    for (size_t i = 0U; i < 2U; i++) {
        if (raw_write_all(graft->fd,
                          graft->root_content[tree_indices[i]],
                          CP_RAW_PAGE_SIZE,
                          (off_t)((uint64_t)schema_roots[tree_indices[i]] - 1U) *
                              (off_t)CP_RAW_PAGE_SIZE) != 0) {
            goto done;
        }
    }
    raw_store_be(count_be, (uint64_t)graft->next_pgno - 1U, 4U);
    if (pwrite(graft->fd, count_be, sizeof(count_be), CP_RAW_HDR_PAGE_COUNT_OFFSET) !=
        (ssize_t)sizeof(count_be)) {
        goto done;
    }
    rc = 0;

done:
    if (rc != 0) {
        /* Appended pages past the recorded count are harmless, but roots
         * may already be overwritten; restore a clean length and let the
         * SQL adopt rewrite the shadow content from scratch. */
        (void)ftruncate(graft->fd, (off_t)old_page_count * (off_t)CP_RAW_PAGE_SIZE);
    }
    (void)close(graft->fd);
    free(graft);
    return rc;
}

static const char fts_adopt_small_tables_sql[] =
    "DELETE FROM staging.SymbolFts_idx;"
    "DELETE FROM staging.SymbolFts_config;"
    "INSERT INTO staging.SymbolFts_idx SELECT * FROM main.SymbolFts_idx;"
    "INSERT INTO staging.SymbolFts_config SELECT * FROM main.SymbolFts_config;";

static const char fts_adopt_all_tables_sql[] =
    "DELETE FROM staging.SymbolFts_data;"
    "DELETE FROM staging.SymbolFts_idx;"
    "DELETE FROM staging.SymbolFts_docsize;"
    "DELETE FROM staging.SymbolFts_config;"
    "INSERT INTO staging.SymbolFts_data SELECT * FROM main.SymbolFts_data;"
    "INSERT INTO staging.SymbolFts_idx SELECT * FROM main.SymbolFts_idx;"
    "INSERT INTO staging.SymbolFts_docsize SELECT * FROM main.SymbolFts_docsize;"
    "INSERT INTO staging.SymbolFts_config SELECT * FROM main.SymbolFts_config;";

/* Post-COMMIT FTS adopt for the raw path: the staging file is attached to
 * the sidecar's still-open connection (whose main database holds the
 * finished FTS in memory) and selected shadow tables are copied out in one
 * short transaction, off the load transaction's critical path. The OFF
 * journal pragmas stay safe because the file is still published by atomic
 * rename. A failed copy rolls back and detaches so the fallback can reuse
 * the connection. */
static int fts_adopt_tables(FtsSidecar *sidecar, const char *db_path, const char *copy_sql)
{
    char *attach_sql = nullptr;
    int rc = -1;

    if ((sidecar == nullptr) || (db_path == nullptr) || (copy_sql == nullptr) ||
        !sidecar->db_kept_open) {
        return -1;
    }
    attach_sql = sqlite3_mprintf("ATTACH %Q AS staging", db_path);
    if ((attach_sql != nullptr) && (code_lens_db_exec(&sidecar->db, attach_sql) == 0) &&
        (code_lens_db_exec(&sidecar->db,
                            "PRAGMA staging.journal_mode=OFF;"
                            "PRAGMA staging.synchronous=OFF") == 0) &&
        (code_lens_db_exec(&sidecar->db, "BEGIN") == 0) &&
        (code_lens_db_exec(&sidecar->db, copy_sql) == 0) &&
        (code_lens_db_exec(&sidecar->db, "COMMIT") == 0)) {
        rc = 0;
    }
    sqlite3_free(attach_sql);
    if (rc != 0) {
        (void)sqlite3_exec(sidecar->db.handle, "ROLLBACK", nullptr, nullptr, nullptr);
        (void)sqlite3_exec(sidecar->db.handle, "DETACH staging", nullptr, nullptr, nullptr);
    }
    return rc;
}

/* Recovery for a failed sidecar on the raw path: Symbol is SQL-visible once
 * the table graft has run, so a plain in-place FTS rebuild transaction
 * restores the index without abandoning the whole attempt. */
static int fts_rebuild_after_commit(const char *db_path)
{
    CodeLensDb db;
    int rc = -1;

    (void)memset(&db, 0, sizeof(db));
    if (code_lens_db_open_build(&db, db_path) != 0) {
        return -1;
    }
    if ((code_lens_db_exec(&db, "BEGIN") == 0) &&
        (code_lens_db_exec(&db, "INSERT INTO SymbolFts(SymbolFts) VALUES('rebuild')") == 0) &&
        (code_lens_db_exec(&db, "COMMIT") == 0)) {
        rc = 0;
    }
    code_lens_db_close(&db);
    return rc;
}

/* Result of one worker-side read+parse, consumed by the emit thread. */
typedef struct {
    CodeLensSourceFile file;
    bool has_file;
    /* File-stat snapshot captured by the worker's own read (git lstat or
     * map fstat), sparing the emit thread a per-file stat call. When
     * has_stat is false the emit path falls back to stat_regular_file. */
    bool has_stat;
    int64_t stat_size;
    int64_t stat_mtime_sec;
    int64_t stat_mtime_nsec;
    /* Pre-packed Symbol FTS rows (one slot per symbol, entries may be
     * nullptr on pack failure), built on the worker so the emit thread only
     * patches rowids and queues. Slots are nulled as ownership transfers;
     * cleanup frees whatever is still non-null. The array itself is
     * arena-backed. */
    FtsSidecarRow **fts_rows;
    /* Row id strings pre-rendered on the worker (arena-backed); any null
     * pointer (or array) makes the emit thread render that id itself. */
    char *file_id;
    char **symbol_ids;
    char **alias_ids;
    char **referred_ids;
} ParsedFileResult;

static int insert_file_row(IndexContext *index,
                           const char *path,
                           const char *namespace_name,
                           const ParsedFileResult *result)
{
    sqlite3_stmt *stmt = index->writers.stmts[IDX_TABLE_FILE];
    char *id = ((result != nullptr) && (result->file_id != nullptr))
                   ? result->file_id
                   : alloc_printf("%s|file|%s|", index->repo_path, path);
    int64_t size = 0;
    int64_t mtime_sec = 0;
    int64_t mtime_nsec = 0;
    /* The file's rows have not emitted yet, so the running counters are its
     * first ids and the parsed counts its extents (ids and rowids are
     * assigned sequentially from here by this same thread). */
    int64_t ref_first = (int64_t)index->stats.reference_count;
    int64_t ref_count = result == nullptr ? 0 : (int64_t)result->file.reference_count;
    int64_t keyword_first = (int64_t)index->stats.keyword_count;
    int64_t keyword_count = result == nullptr ? 0 : (int64_t)result->file.keyword_count;
    int64_t symbol_first = index->symbol_rowid_base + (int64_t)index->stats.symbol_count + 1;
    int64_t symbol_count = result == nullptr ? 0 : (int64_t)result->file.symbol_count;

    if (id == nullptr) {
        return -1;
    }
    if ((result != nullptr) && result->has_stat) {
        /* Stat snapshot captured by the worker's own read; matches what
         * stat_regular_file would have reported at read time. */
        size = result->stat_size;
        mtime_sec = result->stat_mtime_sec;
        mtime_nsec = result->stat_mtime_nsec;
    } else if (stat_regular_file(path, &size, &mtime_sec, &mtime_nsec) != 0) {
        return -1;
    }
    if (index->raw_emit.active) {
        RawEmitter *emit = &index->raw_emit;
        RawColumn cols[13] = {CP_RAW_TEXT(id),
                              CP_RAW_TEXT(index->repo_path),
                              CP_RAW_TEXT(path),
                              CP_RAW_TEXT(namespace_name),
                              CP_RAW_INT(size),
                              CP_RAW_INT(mtime_sec),
                              CP_RAW_INT(mtime_nsec),
                              CP_RAW_INT(ref_first),
                              CP_RAW_INT(ref_count),
                              CP_RAW_INT(keyword_first),
                              CP_RAW_INT(keyword_count),
                              CP_RAW_INT(symbol_first),
                              CP_RAW_INT(symbol_count)};

        if (raw_ptr_push(&emit->file_ids, &emit->file_id_count, &emit->file_id_cap, id) != 0) {
            return -1;
        }
        if (raw_emit_encoded(emit, &emit->file, (int64_t)emit->file_id_count, cols, 13U) != 0) {
            return -1;
        }
        index->current_file_rowid = (int64_t)emit->file_id_count;
        return 0;
    }
    if ((bind_text(stmt, 1, id) != 0) || (bind_text(stmt, 2, index->repo_path) != 0) ||
        (bind_text(stmt, 3, path) != 0) || (bind_text(stmt, 4, namespace_name) != 0) ||
        (sqlite3_bind_int64(stmt, 5, size) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 6, mtime_sec) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 7, mtime_nsec) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 8, ref_first) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 9, ref_count) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 10, keyword_first) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 11, keyword_count) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 12, symbol_first) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 13, symbol_count) != SQLITE_OK)) {
        return -1;
    }
    if (writer_step(stmt) != 0) {
        return -1;
    }
    /* Ref and Keyword rows reference this File row by rowid instead of
     * repeating repo/path/namespace text on every row. */
    index->current_file_rowid = sqlite3_last_insert_rowid(sqlite3_db_handle(stmt));
    return 0;
}

static int insert_symbol_row(IndexContext *index,
                             const char *path,
                             const CodeLensSymbol *symbol,
                             size_t ordinal,
                             char *prebuilt_id,
                             FtsSidecarRow *prebuilt_fts)
{
    sqlite3_stmt *stmt = index->writers.stmts[IDX_TABLE_SYMBOL];
    /* The per-file ordinal keeps ids unique when one line defines the same
     * name twice, e.g. #?(:clj (def x 1) :cljs (def x 2)) in a .cljc file;
     * repo|path|line|name alone collides there. */
    char *id = prebuilt_id != nullptr ? prebuilt_id
                                      : alloc_printf("%s|symbol|%s|%u|%zu|%s",
                                                     index->repo_path,
                                                     path,
                                                     (unsigned int)symbol->start_line,
                                                     ordinal,
                                                     symbol->name);

    if (id == nullptr) {
        return -1;
    }
    if (index->raw_emit.active) {
        RawEmitter *emit = &index->raw_emit;
        RawColumn cols[10] = {CP_RAW_TEXT(id),
                              CP_RAW_TEXT(index->repo_path),
                              CP_RAW_TEXT(symbol->name),
                              CP_RAW_TEXT(symbol->kind),
                              CP_RAW_TEXT(symbol->namespace_name),
                              CP_RAW_TEXT(path),
                              CP_RAW_INT((int64_t)symbol->start_line),
                              CP_RAW_INT((int64_t)symbol->end_line),
                              CP_RAW_TEXT(symbol->source),
                              CP_RAW_TEXT(symbol->doc)};

        if (raw_ptr_push(&emit->symbol_ids, &emit->symbol_id_count, &emit->symbol_id_cap, id) !=
            0) {
            return -1;
        }
        if (raw_emit_encoded(emit, &emit->symbol, (int64_t)emit->symbol_id_count, cols, 10U) !=
            0) {
            return -1;
        }
    } else {
        if ((bind_text(stmt, 1, id) != 0) || (bind_text(stmt, 2, index->repo_path) != 0) ||
            (bind_text(stmt, 3, symbol->name) != 0) || (bind_text(stmt, 4, symbol->kind) != 0) ||
            (bind_text(stmt, 5, symbol->namespace_name) != 0) ||
            (bind_text(stmt, 6, path) != 0) ||
            (sqlite3_bind_int64(stmt, 7, (int64_t)symbol->start_line) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, 8, (int64_t)symbol->end_line) != SQLITE_OK) ||
            (bind_text(stmt, 9, symbol->source) != 0) || (bind_text(stmt, 10, symbol->doc) != 0)) {
            return -1;
        }
        if (writer_step(stmt) != 0) {
            return -1;
        }
    }
    if (index->raw_mirror.active &&
        (raw_mirror_push_symbol(&index->raw_mirror, symbol->name) != 0)) {
        return -1;
    }
    if (index->fts_sidecar != nullptr) {
        index->next_symbol_rowid++;
        if (prebuilt_fts != nullptr) {
            fts_sidecar_push_row(index->fts_sidecar, prebuilt_fts, index->next_symbol_rowid);
        } else {
            fts_sidecar_enqueue(index->fts_sidecar,
                                index->next_symbol_rowid,
                                symbol->name,
                                symbol->namespace_name,
                                path,
                                symbol->doc,
                                symbol->source);
        }
    } else {
        free(prebuilt_fts);
    }
    return 0;
}

/* Reference base/target-namespace resolution happens on the parse workers
 * (resolve_reference_targets); the emit path reads the precomputed fields. */
static int insert_reference_row_with_lengths(IndexContext *index,
                                             const CodeLensReference *reference,
                                             size_t reference_ordinal)
{
    sqlite3_stmt *stmt = index->writers.stmts[IDX_TABLE_REF];
    int64_t symbol_term;
    int64_t base_term;
    int64_t target_term;

    if ((term_intern(&index->interner,
                     reference->symbol,
                     reference->symbol_len,
                     reference->symbol_hash,
                     &symbol_term) != 0) ||
        (term_intern(&index->interner,
                     reference->base,
                     reference->base_len,
                     reference->base_hash,
                     &base_term) != 0) ||
        (term_intern(&index->interner,
                     reference->target_namespace,
                     reference->target_namespace_len,
                     reference->target_namespace_hash,
                     &target_term) != 0)) {
        return -1;
    }
    if (index->raw_mirror.active &&
        (raw_mirror_push_term(&index->raw_mirror.ref_terms,
                              &index->raw_mirror.ref_count,
                              &index->raw_mirror.ref_cap,
                              base_term) != 0)) {
        return -1;
    }
    if (index->raw_emit.active) {
        RawColumn cols[9] = {CP_RAW_ROWID_NULL,
                             CP_RAW_INT(index->current_file_rowid),
                             CP_RAW_INT(symbol_term),
                             CP_RAW_INT((int64_t)reference->line),
                             CP_RAW_INT((int64_t)reference->column),
                             CP_RAW_INT((int64_t)reference->start_byte),
                             CP_RAW_INT((int64_t)reference->end_byte),
                             CP_RAW_INT(base_term),
                             CP_RAW_INT(target_term)};

        return raw_emit_encoded(&index->raw_emit,
                                &index->raw_emit.refdata,
                                (int64_t)reference_ordinal,
                                cols,
                                9U);
    }
    if ((sqlite3_bind_int64(stmt, 1, (int64_t)reference_ordinal) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 2, index->current_file_rowid) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 3, symbol_term) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 4, (int64_t)reference->line) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 5, (int64_t)reference->column) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 6, (int64_t)reference->start_byte) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 7, (int64_t)reference->end_byte) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 8, base_term) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 9, target_term) != SQLITE_OK)) {
        return -1;
    }
    return writer_step(stmt);
}

static int insert_keyword_row_with_lengths(IndexContext *index,
                                           const CodeLensKeyword *keyword,
                                           size_t keyword_ordinal)
{
    sqlite3_stmt *stmt = index->writers.stmts[IDX_TABLE_KEYWORD];
    int64_t keyword_term;
    int64_t base_term;
    int64_t qualifier_term;
    int64_t target_term;

    if ((term_intern(&index->interner,
                     keyword->keyword,
                     keyword->keyword_len,
                     keyword->keyword_hash,
                     &keyword_term) != 0) ||
        (term_intern(&index->interner,
                     keyword->base,
                     keyword->base_len,
                     keyword->base_hash,
                     &base_term) != 0) ||
        (term_intern(&index->interner,
                     keyword->qualifier,
                     keyword->qualifier_len,
                     keyword->qualifier_hash,
                     &qualifier_term) != 0) ||
        (term_intern(&index->interner,
                     keyword->target_namespace,
                     keyword->target_namespace_len,
                     keyword->target_namespace_hash,
                     &target_term) != 0)) {
        return -1;
    }
    if (index->raw_mirror.active &&
        (raw_mirror_push_term(&index->raw_mirror.keyword_terms,
                              &index->raw_mirror.keyword_count,
                              &index->raw_mirror.keyword_cap,
                              base_term) != 0)) {
        return -1;
    }
    if (index->raw_emit.active) {
        RawColumn cols[8] = {CP_RAW_ROWID_NULL,
                             CP_RAW_INT(index->current_file_rowid),
                             CP_RAW_INT(keyword_term),
                             CP_RAW_INT((int64_t)keyword->line),
                             CP_RAW_INT((int64_t)keyword->column),
                             CP_RAW_INT(base_term),
                             CP_RAW_INT(qualifier_term),
                             CP_RAW_INT(target_term)};

        return raw_emit_encoded(&index->raw_emit,
                                &index->raw_emit.keyworddata,
                                (int64_t)keyword_ordinal,
                                cols,
                                8U);
    }
    if ((sqlite3_bind_int64(stmt, 1, (int64_t)keyword_ordinal) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 2, index->current_file_rowid) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 3, keyword_term) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 4, (int64_t)keyword->line) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 5, (int64_t)keyword->column) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 6, base_term) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 7, qualifier_term) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 8, target_term) != SQLITE_OK)) {
        return -1;
    }
    return writer_step(stmt);
}

static int insert_reference_batch(IndexContext *index,
                                  const CodeLensSourceFile *parsed,
                                  size_t first,
                                  size_t ordinal_base)
{
    sqlite3_stmt *stmt = index->writers.ref_batch;

    if (index->raw_emit.active) {
        for (size_t r = 0U; r < (size_t)CODE_LENS_INSERT_BATCH_ROWS; r++) {
            if (insert_reference_row_with_lengths(index,
                                                  &parsed->references[first + r],
                                                  ordinal_base + r) != 0) {
                return -1;
            }
        }
        return 0;
    }
    for (size_t r = 0U; r < (size_t)CODE_LENS_INSERT_BATCH_ROWS; r++) {
        const CodeLensReference *reference = &parsed->references[first + r];
        int64_t symbol_term;
        int64_t base_term;
        int64_t target_term;
        int base = (int)(r * 9U);

        if ((term_intern(&index->interner,
                         reference->symbol,
                         reference->symbol_len,
                         reference->symbol_hash,
                         &symbol_term) != 0) ||
            (term_intern(&index->interner,
                         reference->base,
                         reference->base_len,
                         reference->base_hash,
                         &base_term) != 0) ||
            (term_intern(&index->interner,
                         reference->target_namespace,
                         reference->target_namespace_len,
                         reference->target_namespace_hash,
                         &target_term) != 0)) {
            (void)sqlite3_reset(stmt);
            return -1;
        }
        if (index->raw_mirror.active &&
            (raw_mirror_push_term(&index->raw_mirror.ref_terms,
                                  &index->raw_mirror.ref_count,
                                  &index->raw_mirror.ref_cap,
                                  base_term) != 0)) {
            (void)sqlite3_reset(stmt);
            return -1;
        }
        if ((sqlite3_bind_int64(stmt, base + 1, (int64_t)(ordinal_base + r)) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, base + 2, index->current_file_rowid) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, base + 3, symbol_term) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, base + 4, (int64_t)reference->line) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, base + 5, (int64_t)reference->column) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, base + 6, (int64_t)reference->start_byte) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, base + 7, (int64_t)reference->end_byte) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, base + 8, base_term) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, base + 9, target_term) != SQLITE_OK)) {
            (void)sqlite3_reset(stmt);
            return -1;
        }
    }
    return writer_step(stmt);
}

static int insert_keyword_batch(IndexContext *index,
                                const CodeLensSourceFile *parsed,
                                size_t first,
                                size_t ordinal_base)
{
    sqlite3_stmt *stmt = index->writers.keyword_batch;

    if (index->raw_emit.active) {
        for (size_t r = 0U; r < (size_t)CODE_LENS_INSERT_BATCH_ROWS; r++) {
            if (insert_keyword_row_with_lengths(index,
                                                &parsed->keywords[first + r],
                                                ordinal_base + r) != 0) {
                return -1;
            }
        }
        return 0;
    }
    for (size_t r = 0U; r < (size_t)CODE_LENS_INSERT_BATCH_ROWS; r++) {
        const CodeLensKeyword *keyword = &parsed->keywords[first + r];
        int64_t keyword_term;
        int64_t base_term;
        int64_t qualifier_term;
        int64_t target_term;
        int base = (int)(r * 8U);

        if ((term_intern(&index->interner,
                         keyword->keyword,
                         keyword->keyword_len,
                         keyword->keyword_hash,
                         &keyword_term) != 0) ||
            (term_intern(&index->interner,
                         keyword->base,
                         keyword->base_len,
                         keyword->base_hash,
                         &base_term) != 0) ||
            (term_intern(&index->interner,
                         keyword->qualifier,
                         keyword->qualifier_len,
                         keyword->qualifier_hash,
                         &qualifier_term) != 0) ||
            (term_intern(&index->interner,
                         keyword->target_namespace,
                         keyword->target_namespace_len,
                         keyword->target_namespace_hash,
                         &target_term) != 0)) {
            (void)sqlite3_reset(stmt);
            return -1;
        }
        if (index->raw_mirror.active &&
            (raw_mirror_push_term(&index->raw_mirror.keyword_terms,
                                  &index->raw_mirror.keyword_count,
                                  &index->raw_mirror.keyword_cap,
                                  base_term) != 0)) {
            (void)sqlite3_reset(stmt);
            return -1;
        }
        if ((sqlite3_bind_int64(stmt, base + 1, (int64_t)(ordinal_base + r)) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, base + 2, index->current_file_rowid) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, base + 3, keyword_term) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, base + 4, (int64_t)keyword->line) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, base + 5, (int64_t)keyword->column) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, base + 6, base_term) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, base + 7, qualifier_term) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, base + 8, target_term) != SQLITE_OK)) {
            (void)sqlite3_reset(stmt);
            return -1;
        }
    }
    return writer_step(stmt);
}

static int insert_alias_row(IndexContext *index,
                            const char *path,
                            const CodeLensNamespaceAlias *alias,
                            size_t alias_index,
                            char *prebuilt_id)
{
    sqlite3_stmt *stmt = index->writers.stmts[IDX_TABLE_ALIAS];
    char *id = prebuilt_id != nullptr ? prebuilt_id
                                      : alloc_printf("%s|alias|%s|%s|alias|%s|%zu",
                                                     index->repo_path,
                                                     path,
                                                     alias->namespace_name,
                                                     alias->alias,
                                                     alias_index);

    if (id == nullptr) {
        return -1;
    }
    if (index->raw_emit.active) {
        RawEmitter *emit = &index->raw_emit;
        RawColumn cols[5] = {CP_RAW_TEXT(id),
                             CP_RAW_TEXT(index->repo_path),
                             CP_RAW_TEXT(path),
                             CP_RAW_TEXT(alias->namespace_name),
                             CP_RAW_TEXT(alias->alias)};

        if (raw_ptr_push(&emit->alias_ids, &emit->alias_id_count, &emit->alias_id_cap, id) !=
            0) {
            return -1;
        }
        return raw_emit_encoded(emit, &emit->alias, (int64_t)emit->alias_id_count, cols, 5U);
    }
    if ((bind_text(stmt, 1, id) != 0) || (bind_text(stmt, 2, index->repo_path) != 0) ||
        (bind_text(stmt, 3, path) != 0) || (bind_text(stmt, 4, alias->namespace_name) != 0) ||
        (bind_text(stmt, 5, alias->alias) != 0)) {
        return -1;
    }
    return writer_step(stmt);
}

static int insert_referred_row(IndexContext *index,
                               const char *path,
                               const CodeLensReferred *referred,
                               size_t referred_index,
                               char *prebuilt_id)
{
    sqlite3_stmt *stmt = index->writers.stmts[IDX_TABLE_REFERRED];
    char *id = prebuilt_id != nullptr ? prebuilt_id
                                      : alloc_printf("%s|referred|%s|%s|referred|%s|%zu",
                                                     index->repo_path,
                                                     path,
                                                     referred->namespace_name,
                                                     referred->symbol,
                                                     referred_index);

    if (id == nullptr) {
        return -1;
    }
    if (index->raw_emit.active) {
        RawEmitter *emit = &index->raw_emit;
        RawColumn cols[5] = {CP_RAW_TEXT(id),
                             CP_RAW_TEXT(index->repo_path),
                             CP_RAW_TEXT(path),
                             CP_RAW_TEXT(referred->namespace_name),
                             CP_RAW_TEXT(referred->symbol)};

        if (raw_ptr_push(&emit->referred_ids,
                         &emit->referred_id_count,
                         &emit->referred_id_cap,
                         id) != 0) {
            return -1;
        }
        return raw_emit_encoded(emit,
                                &emit->referred,
                                (int64_t)emit->referred_id_count,
                                cols,
                                5U);
    }
    if ((bind_text(stmt, 1, id) != 0) || (bind_text(stmt, 2, index->repo_path) != 0) ||
        (bind_text(stmt, 3, path) != 0) ||
        (bind_text(stmt, 4, referred->namespace_name) != 0) ||
        (bind_text(stmt, 5, referred->symbol) != 0)) {
        return -1;
    }
    return writer_step(stmt);
}

/* Streamed file discovery: a dedicated walker thread appends paths while
 * parse workers consume them, so directory traversal overlaps parsing
 * instead of serializing in front of it. Entries live in fixed-size chunks
 * that never move once published, so workers and the emitter can touch an
 * entry without holding the mutex after claiming it. */
#define STREAM_CHUNK_SHIFT 10U
#define STREAM_CHUNK_SIZE ((size_t)1U << STREAM_CHUNK_SHIFT)
#define STREAM_CHUNK_TABLE_MAX 4096U

typedef struct {
    const char *path;
    ParsedFileResult result;
    bool ready;
} StreamEntry;

typedef struct {
    StreamEntry **chunks;
    size_t count;
    size_t next_index;
    bool collect_done;
    bool prepack_fts;
    const char *repo_path;
    size_t worker_count;
    size_t finished_workers;
    bool wall_recorded;
    bool stop;
    int rc;
    double wall_start_seconds;
    double wall_seconds;
    pthread_mutex_t mutex;
    pthread_cond_t cond;
} ParallelParseWork;

static StreamEntry *stream_entry(const ParallelParseWork *work, size_t index)
{
    return &work->chunks[index >> STREAM_CHUNK_SHIFT][index & (STREAM_CHUNK_SIZE - 1U)];
}

typedef struct {
    ParallelParseWork *work;
    bool profile_enabled;
    double read_seconds;
    double parse_seconds;
    GitBlobReader git_reader;
} ParallelParseWorker;

static int parse_file_result(CodeLensSourceParser *parser,
                             GitBlobReader *git_reader,
                             const char *path,
                             ParsedFileResult *out_result,
                             const char *repo_path,
                             bool prepack_fts,
                             bool profile_enabled,
                             double *read_seconds,
                             double *parse_seconds)
{
    CodeLensMappedFile content;
    CodeLensSourceFile parsed;
    double start;
    int rc;

    if ((parser == nullptr) || (path == nullptr) || (out_result == nullptr)) {
        return -1;
    }

    (void)memset(&parsed, 0, sizeof(parsed));
    (void)memset(&content, 0, sizeof(content));

    start = profile_enabled ? profile_now_seconds() : 0.0;
    rc = git_blob_try_read(git_reader, path, &content);
    if (rc != 0) {
        if ((git_reader != nullptr) && (git_reader->source != nullptr) &&
            git_reader->source->active) {
            atomic_fetch_add_explicit(&git_file_reads, 1U, memory_order_relaxed);
        }
        rc = code_lens_map_file(path, &content);
    }
    if (profile_enabled && (read_seconds != nullptr)) {
        *read_seconds += profile_now_seconds() - start;
    }
    if (rc != 0) {
        (void)fprintf(stderr, "code-lens: failed to read %s\n", path);
        return -1;
    }

    /* Copy the read's stat snapshot before mapped_file_free zeroes it. */
    out_result->has_stat = content.has_stat;
    out_result->stat_size = content.stat_size;
    out_result->stat_mtime_sec = content.stat_mtime_sec;
    out_result->stat_mtime_nsec = content.stat_mtime_nsec;

    start = profile_enabled ? profile_now_seconds() : 0.0;
    rc = code_lens_parse_source_with_parser(parser, path, content.data, content.len, &parsed);
    if (profile_enabled && (parse_seconds != nullptr)) {
        *parse_seconds += profile_now_seconds() - start;
    }
    code_lens_mapped_file_free(&content);
    if (rc != 0) {
        (void)fprintf(stderr, "code-lens: failed to parse %s\n", path);
        return -1;
    }

    out_result->file = parsed;
    out_result->has_file = true;
    if (prepack_fts && (parsed.symbol_count > 0U)) {
        /* Pre-pack the Symbol FTS rows here on the worker; the emit thread
         * then only patches rowids and queues. A failed allocation leaves
         * the slot (or whole array) null and the emit-side fallback packs
         * that row itself. */
        FtsSidecarRow **rows = code_lens_alloc(parsed.symbol_count * sizeof(*rows));

        if (rows != nullptr) {
            for (size_t i = 0U; i < parsed.symbol_count; i++) {
                const CodeLensSymbol *symbol = &parsed.symbols[i];

                rows[i] = fts_row_pack(0,
                                       symbol->name,
                                       symbol->namespace_name,
                                       parsed.file_path,
                                       symbol->doc,
                                       symbol->source);
            }
            out_result->fts_rows = rows;
        }
    }
    if (repo_path != nullptr) {
        /* Pre-render the row id strings so the emit thread skips its
         * per-row alloc_printf; any allocation failure just leaves the
         * emit-side fallback to render that id. */
        out_result->file_id = alloc_printf("%s|file|%s|", repo_path, parsed.file_path);
        if (parsed.symbol_count > 0U) {
            char **ids = code_lens_alloc(parsed.symbol_count * sizeof(*ids));

            if (ids != nullptr) {
                for (size_t i = 0U; i < parsed.symbol_count; i++) {
                    const CodeLensSymbol *symbol = &parsed.symbols[i];

                    ids[i] = alloc_printf("%s|symbol|%s|%u|%zu|%s",
                                          repo_path,
                                          parsed.file_path,
                                          (unsigned int)symbol->start_line,
                                          i,
                                          symbol->name);
                }
                out_result->symbol_ids = ids;
            }
        }
        if (parsed.alias_count > 0U) {
            char **ids = code_lens_alloc(parsed.alias_count * sizeof(*ids));

            if (ids != nullptr) {
                for (size_t i = 0U; i < parsed.alias_count; i++) {
                    const CodeLensNamespaceAlias *alias = &parsed.aliases[i];

                    ids[i] = alloc_printf("%s|alias|%s|%s|alias|%s|%zu",
                                          repo_path,
                                          parsed.file_path,
                                          alias->namespace_name,
                                          alias->alias,
                                          i);
                }
                out_result->alias_ids = ids;
            }
        }
        if (parsed.referred_count > 0U) {
            char **ids = code_lens_alloc(parsed.referred_count * sizeof(*ids));

            if (ids != nullptr) {
                for (size_t i = 0U; i < parsed.referred_count; i++) {
                    const CodeLensReferred *referred = &parsed.referred[i];

                    ids[i] = alloc_printf("%s|referred|%s|%s|referred|%s|%zu",
                                          repo_path,
                                          parsed.file_path,
                                          referred->namespace_name,
                                          referred->symbol,
                                          i);
                }
                out_result->referred_ids = ids;
            }
        }
    }
    return 0;
}

/* Frees any pre-packed FTS rows still owned by the result (ownership is
 * transferred slot by slot during emit; unconsumed slots stay non-null). */
static void parsed_result_free_fts_rows(ParsedFileResult *result)
{
    if ((result == nullptr) || (result->fts_rows == nullptr) || !result->has_file) {
        return;
    }
    for (size_t i = 0U; i < result->file.symbol_count; i++) {
        free(result->fts_rows[i]);
        result->fts_rows[i] = nullptr;
    }
}

static int emit_parsed_file(IndexContext *index, ParsedFileResult *result)
{
    const CodeLensSourceFile *parsed;
    double start;
    int rc;

    if ((index == nullptr) || (result == nullptr) || !result->has_file ||
        (result->file.file_path == nullptr)) {
        return -1;
    }
    parsed = &result->file;

    start = profile_start(index->profile);
    rc = insert_file_row(index, parsed->file_path, parsed->namespace_name, result);
    profile_add(index->profile, &index->profile->file_write_seconds, start);
    if (rc != 0) {
        (void)fprintf(stderr, "code-lens: failed to write File row\n");
        return -1;
    }
    index->stats.file_count++;

    start = profile_start(index->profile);
    rc = 0;
    for (size_t i = 0U; (rc == 0) && (i < parsed->symbol_count); i++) {
        FtsSidecarRow *prebuilt = nullptr;
        char *prebuilt_id =
            result->symbol_ids != nullptr ? result->symbol_ids[i] : nullptr;

        if (result->fts_rows != nullptr) {
            prebuilt = result->fts_rows[i];
            result->fts_rows[i] = nullptr;
        }
        rc = insert_symbol_row(index,
                               parsed->file_path,
                               &parsed->symbols[i],
                               i,
                               prebuilt_id,
                               prebuilt);
        if (rc == 0) {
            index->stats.symbol_count++;
        } else {
            /* insert_symbol_row consumes the row only on success. */
            free(prebuilt);
        }
    }
    profile_add(index->profile, &index->profile->symbol_write_seconds, start);
    if (rc != 0) {
        (void)fprintf(stderr, "code-lens: failed to write Symbol row\n");
        return -1;
    }

    start = profile_start(index->profile);
    {
        size_t i = 0U;

        while ((rc == 0) &&
               ((i + (size_t)CODE_LENS_INSERT_BATCH_ROWS) <= parsed->reference_count)) {
            rc = insert_reference_batch(index, parsed, i, index->stats.reference_count);
            if (rc == 0) {
                index->stats.reference_count += (size_t)CODE_LENS_INSERT_BATCH_ROWS;
                i += (size_t)CODE_LENS_INSERT_BATCH_ROWS;
            }
        }
        for (; (rc == 0) && (i < parsed->reference_count); i++) {
            rc = insert_reference_row_with_lengths(index,
                                                   &parsed->references[i],
                                                   index->stats.reference_count);
            if (rc == 0) {
                index->stats.reference_count++;
            }
        }
    }
    profile_add(index->profile, &index->profile->reference_write_seconds, start);
    if (rc != 0) {
        (void)fprintf(stderr, "code-lens: failed to write Ref row\n");
        return -1;
    }

    start = profile_start(index->profile);
    {
        size_t i = 0U;

        while ((rc == 0) &&
               ((i + (size_t)CODE_LENS_INSERT_BATCH_ROWS) <= parsed->keyword_count)) {
            rc = insert_keyword_batch(index, parsed, i, index->stats.keyword_count);
            if (rc == 0) {
                index->stats.keyword_count += (size_t)CODE_LENS_INSERT_BATCH_ROWS;
                i += (size_t)CODE_LENS_INSERT_BATCH_ROWS;
            }
        }
        for (; (rc == 0) && (i < parsed->keyword_count); i++) {
            rc = insert_keyword_row_with_lengths(index,
                                                 &parsed->keywords[i],
                                                 index->stats.keyword_count);
            if (rc == 0) {
                index->stats.keyword_count++;
            }
        }
    }
    profile_add(index->profile, &index->profile->keyword_write_seconds, start);
    if (rc != 0) {
        (void)fprintf(stderr, "code-lens: failed to write Keyword row\n");
        return -1;
    }

    start = profile_start(index->profile);
    for (size_t i = 0U; (rc == 0) && (i < parsed->alias_count); i++) {
        rc = insert_alias_row(index,
                              parsed->file_path,
                              &parsed->aliases[i],
                              i,
                              result->alias_ids != nullptr ? result->alias_ids[i] : nullptr);
        if (rc == 0) {
            index->stats.alias_count++;
        }
    }
    for (size_t i = 0U; (rc == 0) && (i < parsed->referred_count); i++) {
        rc = insert_referred_row(index,
                                 parsed->file_path,
                                 &parsed->referred[i],
                                 i,
                                 result->referred_ids != nullptr ? result->referred_ids[i]
                                                                 : nullptr);
    }
    profile_add(index->profile, &index->profile->alias_write_seconds, start);
    if (rc != 0) {
        (void)fprintf(stderr, "code-lens: failed to write Alias row\n");
        return -1;
    }

    return 0;
}

static int index_file_callback(const char *path, void *ctx)
{
    IndexContext *index = ctx;
    ParsedFileResult result;
    double read_seconds = 0.0;
    double parse_seconds = 0.0;
    bool profile_enabled;
    int rc;

    (void)memset(&result, 0, sizeof(result));
    profile_enabled = (index->profile != nullptr) && index->profile->enabled;

    /* Sequential mode shares one thread between parse and emit, so the
     * pre-pack work (FTS rows, id strings) that pays on the parallel
     * pipeline would be pure overhead here: skip it and let the emit-side
     * fallbacks build those values as before. */
    rc = parse_file_result(index->parser,
                           index->git_reader,
                           path,
                           &result,
                           nullptr,
                           false,
                           profile_enabled,
                           &read_seconds,
                           &parse_seconds);
    if (profile_enabled) {
        index->profile->read_seconds += read_seconds;
        index->profile->parse_seconds += parse_seconds;
    }
    if (rc == 0) {
        rc = emit_parsed_file(index, &result);
        /* Frees whatever emit did not consume (no-op on success). */
        parsed_result_free_fts_rows(&result);
    }

    return rc;
}

static bool parallel_parse_next(ParallelParseWork *work, StreamEntry **out_entry)
{
    bool found = false;

    (void)pthread_mutex_lock(&work->mutex);
    for (;;) {
        if (work->stop) {
            break;
        }
        if (work->next_index < work->count) {
            *out_entry = stream_entry(work, work->next_index);
            work->next_index++;
            found = true;
            break;
        }
        if (work->collect_done) {
            break;
        }
        (void)pthread_cond_wait(&work->cond, &work->mutex);
    }
    (void)pthread_mutex_unlock(&work->mutex);
    return found;
}

/* Records the parallel parse wall time once all workers have finished.
 * The caller must hold work->mutex. */
static void parallel_parse_record_wall_locked(ParallelParseWork *work)
{
    if (!work->wall_recorded && (work->finished_workers >= work->worker_count)) {
        work->wall_seconds = profile_now_seconds() - work->wall_start_seconds;
        work->wall_recorded = true;
        (void)pthread_cond_broadcast(&work->cond);
    }
}

static void parallel_parse_record_worker_finished(ParallelParseWork *work)
{
    (void)pthread_mutex_lock(&work->mutex);
    work->finished_workers++;
    parallel_parse_record_wall_locked(work);
    (void)pthread_mutex_unlock(&work->mutex);
}

static void parallel_parse_publish_result(ParallelParseWork *work, StreamEntry *entry)
{
    (void)pthread_mutex_lock(&work->mutex);
    if (!work->stop) {
        entry->ready = true;
        (void)pthread_cond_broadcast(&work->cond);
    }
    (void)pthread_mutex_unlock(&work->mutex);
}

static void parallel_parse_abort(ParallelParseWork *work)
{
    (void)pthread_mutex_lock(&work->mutex);
    if (work->rc == 0) {
        work->rc = -1;
    }
    work->stop = true;
    (void)pthread_cond_broadcast(&work->cond);
    (void)pthread_mutex_unlock(&work->mutex);
}

static void *parallel_parse_worker_main(void *ctx)
{
    ParallelParseWorker *worker = ctx;
    CodeLensSourceParser *parser;
    StreamEntry *entry = nullptr;

    parser = code_lens_source_parser_new();
    if (parser == nullptr) {
        (void)fprintf(stderr, "code-lens: failed to create source parser worker\n");
        parallel_parse_abort(worker->work);
        parallel_parse_record_worker_finished(worker->work);
        return nullptr;
    }

    while (parallel_parse_next(worker->work, &entry)) {
        if (parse_file_result(parser,
                               &worker->git_reader,
                               entry->path,
                               &entry->result,
                               worker->work->repo_path,
                               worker->work->prepack_fts,
                               worker->profile_enabled,
                               &worker->read_seconds,
                               &worker->parse_seconds) != 0) {
            parallel_parse_abort(worker->work);
            break;
        }
        parallel_parse_publish_result(worker->work, entry);
    }

    code_lens_source_parser_delete(parser);
    parallel_parse_record_worker_finished(worker->work);
    return nullptr;
}

/* Walker-thread side of the stream: paths are copied into the walker's
 * arena outside the lock and published in batches, so workers see one
 * broadcast per batch instead of one per file. Chunk slabs never move
 * once linked in. */
#define STREAM_APPEND_BATCH 32U

typedef struct {
    ParallelParseWork *work;
    char *paths[STREAM_APPEND_BATCH];
    size_t count;
} StreamAppendBatch;

static int stream_flush_batch(StreamAppendBatch *batch)
{
    ParallelParseWork *work = batch->work;
    int rc = 0;

    if (batch->count == 0U) {
        return 0;
    }
    (void)pthread_mutex_lock(&work->mutex);
    if (work->stop) {
        rc = -1;
    } else {
        for (size_t i = 0U; i < batch->count; i++) {
            size_t chunk = work->count >> STREAM_CHUNK_SHIFT;

            if ((work->count & (STREAM_CHUNK_SIZE - 1U)) == 0U) {
                work->chunks[chunk] = (chunk < (size_t)STREAM_CHUNK_TABLE_MAX)
                                          ? code_lens_alloc_zeroed(STREAM_CHUNK_SIZE,
                                                                    sizeof(StreamEntry))
                                          : nullptr;
            }
            if (work->chunks[chunk] == nullptr) {
                rc = -1;
                break;
            }
            stream_entry(work, work->count)->path = batch->paths[i];
            work->count++;
        }
        (void)pthread_cond_broadcast(&work->cond);
    }
    (void)pthread_mutex_unlock(&work->mutex);
    batch->count = 0U;
    return rc;
}

static int stream_append_path(const char *path, void *ctx)
{
    StreamAppendBatch *batch = ctx;
    char *copy = copy_bytes(path, strlen(path));

    if (copy == nullptr) {
        return -1;
    }
    batch->paths[batch->count++] = copy;
    if (batch->count == (size_t)STREAM_APPEND_BATCH) {
        return stream_flush_batch(batch);
    }
    return 0;
}

typedef struct {
    ParallelParseWork *work;
    const char *repo_path;
    double collect_seconds;
    bool profile_enabled;
} StreamWalker;

static void *stream_walk_main(void *ctx)
{
    StreamWalker *walker = ctx;
    ParallelParseWork *work = walker->work;
    StreamAppendBatch batch = {.work = work, .paths = {nullptr}, .count = 0U};
    double start = walker->profile_enabled ? profile_now_seconds() : 0.0;
    int rc = code_lens_walk_source_files(walker->repo_path, stream_append_path, &batch);

    if ((rc == 0) && (stream_flush_batch(&batch) != 0)) {
        rc = -1;
    }

    if (walker->profile_enabled) {
        walker->collect_seconds = profile_now_seconds() - start;
    }
    (void)pthread_mutex_lock(&work->mutex);
    if ((rc != 0) && (work->rc == 0)) {
        work->rc = -1;
        work->stop = true;
    }
    work->collect_done = true;
    (void)pthread_cond_broadcast(&work->cond);
    (void)pthread_mutex_unlock(&work->mutex);
    return nullptr;
}

static int index_source_files_parallel(IndexContext *index,
                                        const char *repo_path,
                                        size_t thread_count)
{
    ParallelParseWork work;
    StreamWalker walker;
    ParallelParseWorker *workers = nullptr;
    pthread_t *threads = nullptr;
    pthread_t walk_thread;
    bool walk_started = false;
    bool completed = false;
    size_t started = 0U;
    size_t emit_index = 0U;
    IndexProfile *profile = index == nullptr ? nullptr : index->profile;
    bool profile_enabled = (profile != nullptr) && profile->enabled;
    int err;

    if ((index == nullptr) || (repo_path == nullptr) || (thread_count == 0U)) {
        return -1;
    }

    workers = code_lens_alloc_zeroed(thread_count, sizeof(*workers));
    threads = code_lens_alloc_zeroed(thread_count, sizeof(*threads));
    if ((workers == nullptr) || (threads == nullptr)) {
        return -1;
    }

    (void)memset(&work, 0, sizeof(work));
    work.chunks = code_lens_alloc_zeroed(STREAM_CHUNK_TABLE_MAX, sizeof(*work.chunks));
    if (work.chunks == nullptr) {
        return -1;
    }
    work.worker_count = thread_count;
    work.prepack_fts = index->fts_sidecar != nullptr;
    work.repo_path = index->repo_path;
    work.wall_start_seconds = profile_now_seconds();
    if (pthread_mutex_init(&work.mutex, nullptr) != 0) {
        return -1;
    }
    if (pthread_cond_init(&work.cond, nullptr) != 0) {
        (void)pthread_mutex_destroy(&work.mutex);
        return -1;
    }

    (void)memset(&walker, 0, sizeof(walker));
    walker.work = &work;
    walker.repo_path = repo_path;
    walker.profile_enabled = profile_enabled;
    err = pthread_create(&walk_thread, nullptr, stream_walk_main, &walker);
    if (err != 0) {
        (void)fprintf(stderr, "code-lens: failed to create walker thread: %s\n", strerror(err));
        parallel_parse_abort(&work);
    } else {
        walk_started = true;
        for (size_t i = 0U; i < thread_count; i++) {
            workers[i].work = &work;
            workers[i].profile_enabled = profile_enabled;
            git_blob_reader_init(&workers[i].git_reader, index->git_source);
            err = pthread_create(&threads[i], nullptr, parallel_parse_worker_main, &workers[i]);
            if (err != 0) {
                (void)fprintf(stderr,
                              "code-lens: failed to create parser worker thread: %s\n",
                              strerror(err));
                parallel_parse_abort(&work);
                break;
            }
            started++;
        }
    }
    if (started != thread_count) {
        (void)pthread_mutex_lock(&work.mutex);
        work.worker_count = started;
        parallel_parse_record_wall_locked(&work);
        (void)pthread_mutex_unlock(&work.mutex);
    }

    for (;;) {
        StreamEntry *entry = nullptr;
        bool done = false;

        (void)pthread_mutex_lock(&work.mutex);
        while (!work.stop &&
               !((emit_index < work.count) ? stream_entry(&work, emit_index)->ready
                                           : work.collect_done)) {
            (void)pthread_cond_wait(&work.cond, &work.mutex);
        }
        if (!work.stop && (emit_index < work.count)) {
            entry = stream_entry(&work, emit_index);
        } else {
            done = true;
            completed = !work.stop;
        }
        (void)pthread_mutex_unlock(&work.mutex);

        if (done) {
            break;
        }
        if (!entry->result.has_file || (emit_parsed_file(index, &entry->result) != 0)) {
            parallel_parse_abort(&work);
            break;
        }
        emit_index++;
    }

    for (size_t i = 0U; i < started; i++) {
        (void)pthread_join(threads[i], nullptr);
    }
    if (walk_started) {
        (void)pthread_join(walk_thread, nullptr);
    }
    for (size_t i = 0U; i < thread_count; i++) {
        git_blob_reader_destroy(&workers[i].git_reader);
    }
    /* After the joins no one mutates entries; free pre-packed FTS rows the
     * emit loop never consumed (no-op on a completed run). */
    for (size_t i = emit_index; i < work.count; i++) {
        parsed_result_free_fts_rows(&stream_entry(&work, i)->result);
    }

    if (profile_enabled) {
        for (size_t i = 0U; i < thread_count; i++) {
            profile->read_seconds += workers[i].read_seconds;
            profile->parse_seconds += workers[i].parse_seconds;
        }
        profile->parallel_parse_wall_seconds += work.wall_recorded
                                                    ? work.wall_seconds
                                                    : (profile_now_seconds() - work.wall_start_seconds);
        profile->collect_seconds += walker.collect_seconds;
    }

    if (work.rc != 0) {
        completed = false;
    }
    (void)pthread_cond_destroy(&work.cond);
    (void)pthread_mutex_destroy(&work.mutex);
    return completed ? 0 : -1;
}

static char *timestamp_now(void)
{
    time_t now = time(nullptr);
    struct tm tm_value;
    char *text = code_lens_alloc(32U);

    if (text == nullptr) {
        return nullptr;
    }

    tm_value = *gmtime(&now);
    if (strftime(text, 32U, "%Y-%m-%dT%H:%M:%SZ", &tm_value) == 0U) {
        return nullptr;
    }
    return text;
}

static int insert_repo_row(IndexContext *index)
{
    sqlite3_stmt *stmt = index->writers.stmts[IDX_TABLE_REPO];
    char *indexed_at = timestamp_now();

    if ((bind_text(stmt, 1, index->repo_path) != 0) || (bind_text(stmt, 2, indexed_at) != 0) ||
        (sqlite3_bind_int64(stmt, 3, (int64_t)index->stats.file_count) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 4, (int64_t)index->stats.symbol_count) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 5, (int64_t)index->stats.reference_count) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 6, (int64_t)index->stats.keyword_count) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 7, (int64_t)index->stats.alias_count) != SQLITE_OK) ||
        (writer_step(stmt) != 0)) {
        (void)fprintf(stderr, "code-lens: failed to write Repo row\n");
        return -1;
    }

    return 0;
}

typedef struct {
    char *repo_dir;
    char *staging_db_path;
    char *final_db_path;
} IndexPaths;

static size_t default_index_thread_count(void)
{
    long cpu_count = sysconf(_SC_NPROCESSORS_ONLN);
    size_t thread_count;

    if (cpu_count <= 1L) {
        return 1U;
    }
    thread_count = (size_t)cpu_count;
    return thread_count > DEFAULT_MAX_INDEX_THREADS ? DEFAULT_MAX_INDEX_THREADS : thread_count;
}

static size_t configured_index_thread_count(void)
{
    const char *text = getenv("CODE_LENS_INDEX_THREADS");
    char *end = nullptr;
    unsigned long value;

    if ((text == nullptr) || (text[0] == '\0')) {
        return default_index_thread_count();
    }
    if (text[0] == '-') {
        (void)fprintf(stderr,
                      "code-lens: invalid CODE_LENS_INDEX_THREADS=%s; using default\n",
                      text);
        return default_index_thread_count();
    }

    errno = 0;
    value = strtoul(text, &end, 10);
    if ((end == text) || (end == nullptr) || (*end != '\0') || (errno == ERANGE)) {
        (void)fprintf(stderr,
                      "code-lens: invalid CODE_LENS_INDEX_THREADS=%s; using default\n",
                      text);
        return default_index_thread_count();
    }
    if (value <= 1UL) {
        return 1U;
    }
    if (value > (unsigned long)MAX_INDEX_THREADS) {
        (void)fprintf(stderr,
                      "code-lens: CODE_LENS_INDEX_THREADS=%s exceeds max %u; using %u\n",
                      text,
                      MAX_INDEX_THREADS,
                      MAX_INDEX_THREADS);
        return MAX_INDEX_THREADS;
    }
    return (size_t)value;
}

static char *sanitize_repo_component(const char *repo_name)
{
    size_t len;
    char *component;

    if ((repo_name == nullptr) || (repo_name[0] == '\0')) {
        return nullptr;
    }

    len = strlen(repo_name);
    component = code_lens_alloc(len + 1U);
    if (component == nullptr) {
        return nullptr;
    }

    for (size_t i = 0U; i < len; i++) {
        unsigned char ch = (unsigned char)repo_name[i];
        component[i] = (char)((isalnum(ch) || (ch == '-') || (ch == '_') || (ch == '.')) ? ch :
                                                                                             '_');
    }
    component[len] = '\0';
    return component;
}

static uint64_t fnv1a64(const char *text)
{
    uint64_t hash = 0xcbf29ce484222325ULL;

    for (size_t i = 0U; text[i] != '\0'; i++) {
        hash ^= (uint64_t)(unsigned char)text[i];
        hash *= 0x00000100000001b3ULL;
    }
    return hash;
}

/* Canonicalizes a user-supplied repository path into the repo id: a "~/"
 * prefix expands against $HOME and realpath resolves symlinks and relative
 * segments. When the path does not resolve (for example the tree was
 * deleted), a trailing-slash-stripped copy is returned so messages can
 * still name what the caller meant. */
static char *canonical_repo_path(const char *input)
{
    char resolved[PATH_MAX];
    const char *expanded = input;
    char *copy;
    size_t len;

    if ((input == nullptr) || (input[0] == '\0')) {
        return nullptr;
    }
    if ((input[0] == '~') && ((input[1] == '/') || (input[1] == '\0'))) {
        const char *home = getenv("HOME");
#ifdef _WIN32
        if ((home == nullptr) || (home[0] == '\0')) {
            home = getenv("USERPROFILE");
        }
#endif

        if ((home != nullptr) && (home[0] != '\0')) {
            char *joined = alloc_printf("%s%s", home, input + 1);

            if (joined == nullptr) {
                return nullptr;
            }
            expanded = joined;
        }
    }
    if (realpath(expanded, resolved) != nullptr) {
        return alloc_printf("%s", resolved);
    }
    copy = alloc_printf("%s", expanded);
    if (copy == nullptr) {
        return nullptr;
    }
    len = strlen(copy);
    while ((len > 1U) && (copy[len - 1U] == '/')) {
        copy[--len] = '\0';
    }
    return copy;
}

/* MCP resolves a caller's path to its containing worktree without invoking
 * Git. The cache key and maintenance machinery still receive one canonical
 * root, while callers get grep-like path semantics and never need to know
 * which roots have already been seen by code-lens. */
static char *mcp_read_git_path_file(const char *path, const char *prefix)
{
    CodeLensMappedFile mapped = {0};
    size_t prefix_len = prefix == nullptr ? 0U : strlen(prefix);
    size_t start = prefix_len;
    size_t end;
    char *result = nullptr;

    if ((code_lens_map_file(path, &mapped) != 0) || (mapped.data == nullptr) ||
        (mapped.len < prefix_len) ||
        ((prefix != nullptr) && (memcmp(mapped.data, prefix, prefix_len) != 0))) {
        goto done;
    }
    end = mapped.len;
    if ((end > start) && (mapped.data[end - 1U] == '\n')) {
        end--;
    }
    if ((end > start) && (mapped.data[end - 1U] == '\r')) {
        end--;
    }
    if ((end <= start) || (memchr(mapped.data + start, '\n', end - start) != nullptr) ||
        (memchr(mapped.data + start, '\r', end - start) != nullptr)) {
        goto done;
    }
    result = copy_bytes(mapped.data + start, end - start);

done:
    code_lens_mapped_file_free(&mapped);
    return result;
}

static char *mcp_resolve_git_path(const char *base, const char *path)
{
    char resolved[PATH_MAX];
    char *candidate;

    if ((base == nullptr) || (path == nullptr) || (path[0] == '\0')) {
        return nullptr;
    }
#ifdef _WIN32
    candidate = ((path[0] == '/') || (path[0] == '\\') ||
                 (isalpha((unsigned char)path[0]) && (path[1] == ':')))
                    ? alloc_printf("%s", path)
                    : code_lens_join_path(base, path);
#else
    candidate = path[0] == '/' ? alloc_printf("%s", path) : code_lens_join_path(base, path);
#endif
    if ((candidate == nullptr) || (realpath(candidate, resolved) == nullptr)) {
        return nullptr;
    }
    return alloc_printf("%s", resolved);
}

static bool mcp_git_dir_valid(const char *git_dir)
{
    struct stat metadata;
    char *head_path;
    char *commondir_path;
    char *common_dir = nullptr;
    char *objects_path;
    bool valid = false;

    if ((git_dir == nullptr) || !code_lens_is_directory(git_dir)) {
        return false;
    }
    head_path = code_lens_join_path(git_dir, "HEAD");
    commondir_path = code_lens_join_path(git_dir, "commondir");
    if ((head_path == nullptr) || (commondir_path == nullptr) ||
        (stat(head_path, &metadata) != 0) || !S_ISREG(metadata.st_mode)) {
        return false;
    }
    if (stat(commondir_path, &metadata) == 0) {
        char *commondir = nullptr;

        if (!S_ISREG(metadata.st_mode)) {
            return false;
        }
        commondir = mcp_read_git_path_file(commondir_path, nullptr);
        common_dir = mcp_resolve_git_path(git_dir, commondir);
        if (common_dir == nullptr) {
            return false;
        }
    } else if (errno == ENOENT) {
        common_dir = alloc_printf("%s", git_dir);
    }
    if (common_dir == nullptr) {
        return false;
    }
    objects_path = code_lens_join_path(common_dir, "objects");
    valid = (objects_path != nullptr) && code_lens_is_directory(objects_path);
    return valid;
}

static bool mcp_path_parent_in_place(char *path)
{
    size_t len;
    char *slash;

    if ((path == nullptr) || (path[0] == '\0')) {
        return false;
    }
    len = strlen(path);
    while ((len > 1U) && (path[len - 1U] == '/')) {
#ifdef _WIN32
        if ((len == 3U) && isalpha((unsigned char)path[0]) && (path[1] == ':')) {
            break;
        }
#endif
        path[--len] = '\0';
    }
    slash = strrchr(path, '/');
    if (slash == nullptr) {
        return false;
    }
#ifdef _WIN32
    if ((slash == path + 2) && isalpha((unsigned char)path[0]) && (path[1] == ':')) {
        if (slash[1] == '\0') {
            return false;
        }
        slash[1] = '\0';
        return true;
    }
#endif
    if (slash == path) {
        if (slash[1] == '\0') {
            return false;
        }
        slash[1] = '\0';
        return true;
    }
    *slash = '\0';
    return true;
}

static char *mcp_worktree_root(const char *input)
{
    struct stat metadata;
    const char *requested = ((input == nullptr) || (input[0] == '\0')) ? "." : input;
    char *probe = canonical_repo_path(requested);

    if ((probe == nullptr) || (stat(probe, &metadata) != 0)) {
        return nullptr;
    }
    if (!S_ISDIR(metadata.st_mode) && !mcp_path_parent_in_place(probe)) {
        return nullptr;
    }

    for (;;) {
        char *git_entry = code_lens_join_path(probe, ".git");

        if (git_entry == nullptr) {
            return nullptr;
        }
        if (stat(git_entry, &metadata) == 0) {
            char *git_dir;

            if (S_ISDIR(metadata.st_mode)) {
                return mcp_git_dir_valid(git_entry) ? probe : nullptr;
            }
            if (!S_ISREG(metadata.st_mode)) {
                return nullptr;
            }
            git_dir = mcp_read_git_path_file(git_entry, "gitdir: ");
            git_dir = mcp_resolve_git_path(probe, git_dir);
            return mcp_git_dir_valid(git_dir) ? probe : nullptr;
        }
        if ((errno != ENOENT) || !mcp_path_parent_in_place(probe)) {
            return nullptr;
        }
    }
}

/* Directory component for a repo id (its canonical path): the sanitized
 * final path component for debuggability plus a hash of the whole path, so
 * distinct repositories never share a directory even when basenames
 * collide, sanitizing collides, or the filesystem ignores case. */
static char *repo_dir_component(const char *repo_path)
{
    const char *base = strrchr(repo_path, '/');
    char *safe;

    base = base == nullptr ? repo_path : base + 1;
    safe = sanitize_repo_component(base[0] == '\0' ? "repo" : base);
    if (safe == nullptr) {
        return nullptr;
    }
    return alloc_printf("%s-%016llx", safe, (unsigned long long)fnv1a64(repo_path));
}

static char *repos_root(void)
{
    char *home = code_lens_default_home();

    if (home == nullptr) {
        return nullptr;
    }
    return code_lens_join_path(home, "repos");
}

static char *repo_dir_path(const char *repo_name)
{
    char *root = repos_root();
    char *component = repo_dir_component(repo_name);

    if ((root == nullptr) || (component == nullptr)) {
        return nullptr;
    }
    return code_lens_join_path(root, component);
}

static char *repo_db_path(const char *repo_name)
{
    char *dir = repo_dir_path(repo_name);

    if (dir == nullptr) {
        return nullptr;
    }
    return code_lens_join_path(dir, "index.sqlite");
}

/* Build-tool dependency-source support ---------------------------------- */

#define MAVEN_DEPENDENCY_PLUGIN_VERSION "3.8.1"
#define MAVEN_DEFAULT_TIMEOUT_MS 120000U
#define MAVEN_SOURCE_JAR_MAX_UNCOMPRESSED_BYTES (1024ULL * 1024ULL * 1024ULL)
#define DEPENDENCY_SOURCE_CACHE_MARKER "sources-v3"

typedef struct {
    char *path;
    int64_t size;
    int64_t mtime_sec;
    int64_t mtime_nsec;
} MavenInputSnapshot;

typedef struct {
    char *id;
    char *coordinate;
    char *group_id;
    char *artifact_id;
    char *version;
    char *scope;
    int direct;
    char *source_jar;
    char *source_root;
    char *checksum;
    CodeLensPathList files;
} MavenSourceArtifact;

typedef enum {
    DEPENDENCY_PROJECT_NONE,
    DEPENDENCY_PROJECT_MAVEN,
    DEPENDENCY_PROJECT_LEININGEN,
    DEPENDENCY_PROJECT_TOOLS_DEPS,
} DependencyProjectKind;

typedef struct {
    bool active;
    bool reused;
    const char *repo_path;
    DependencyProjectKind project_kind;
    /* Legacy database column name: this is the root build file for every
     * supported project kind, not only a Maven POM. Empty for projects with
     * no recognized dependency build file. */
    char *root_pom;
    /* Exact Clojure CLI alias string selected for tools.deps, or empty. */
    const char *tools_deps_aliases;
    char *status;
    char *message;
    MavenInputSnapshot *inputs;
    size_t input_count;
    size_t input_capacity;
    MavenSourceArtifact *artifacts;
    size_t artifact_count;
    size_t artifact_capacity;
    /* Normal Maven-layout JARs from a Leiningen/tools.deps classpath. They
     * are gathered before materialization so tools.deps can fetch any missing
     * source classifiers in one or more follow-up resolution calls. */
    CodeLensPathList classpath_jars;
    size_t skipped_artifacts;
} MavenDependencySet;

static const char *dependency_project_name(DependencyProjectKind kind)
{
    switch (kind) {
        case DEPENDENCY_PROJECT_MAVEN:
            return "Maven";
        case DEPENDENCY_PROJECT_LEININGEN:
            return "Leiningen";
        case DEPENDENCY_PROJECT_TOOLS_DEPS:
            return "tools.deps";
        case DEPENDENCY_PROJECT_NONE:
            return "dependency";
    }
    return "dependency";
}

static int dependency_project_detect(const char *repo_path, MavenDependencySet *set)
{
    static const struct {
        const char *name;
        DependencyProjectKind kind;
    } candidates[] = {
        {"pom.xml", DEPENDENCY_PROJECT_MAVEN},
        {"project.clj", DEPENDENCY_PROJECT_LEININGEN},
        {"deps.edn", DEPENDENCY_PROJECT_TOOLS_DEPS},
    };

    if ((repo_path == nullptr) || (set == nullptr)) {
        return -1;
    }
    set->project_kind = DEPENDENCY_PROJECT_NONE;
    for (size_t i = 0U; i < sizeof(candidates) / sizeof(candidates[0]); i++) {
        char *path = code_lens_join_path(repo_path, candidates[i].name);

        if (path == nullptr) {
            return -1;
        }
        if (code_lens_path_exists(path)) {
            set->project_kind = candidates[i].kind;
            set->root_pom = path;
            return 0;
        }
    }
    set->root_pom = copy_bytes("", 0U);
    return set->root_pom == nullptr ? -1 : 0;
}

static bool maven_enabled(void)
{
    const char *value = getenv("CODE_LENS_MAVEN");

    return (value == nullptr) || (value[0] == '\0') || (strcmp(value, "0") != 0);
}

static uint32_t maven_timeout_ms(void)
{
    const char *value = getenv("CODE_LENS_MAVEN_TIMEOUT_MS");
    char *end = nullptr;
    unsigned long parsed;

    if ((value == nullptr) || (value[0] == '\0') || (value[0] == '-')) {
        return MAVEN_DEFAULT_TIMEOUT_MS;
    }
    errno = 0;
    parsed = strtoul(value, &end, 10);
    if ((errno == ERANGE) || (end == value) || (end == nullptr) || (*end != '\0') ||
        (parsed == 0UL) || (parsed > (unsigned long)UINT32_MAX)) {
        return MAVEN_DEFAULT_TIMEOUT_MS;
    }
    return (uint32_t)parsed;
}

static int run_process_with_timeout(const char *cwd,
                                    const char *const argv[],
                                    uint32_t timeout_ms)
{
#ifdef _WIN32
    return cp_run_process(cwd, argv, timeout_ms);
#else
    pid_t child;
    double deadline;
    int status = 0;

    if ((argv == nullptr) || (argv[0] == nullptr) || (argv[0][0] == '\0')) {
        return -1;
    }
    child = fork();
    if (child < 0) {
        return -1;
    }
    if (child == 0) {
        /* MCP owns stdout for JSON-RPC framing; build-tool chatter belongs on stderr. */
        if (dup2(STDERR_FILENO, STDOUT_FILENO) < 0) {
            _exit(127);
        }
        if ((cwd != nullptr) && (chdir(cwd) != 0)) {
            _exit(127);
        }
        if (strchr(argv[0], '/') != nullptr) {
            execv(argv[0], (char *const *)argv);
        } else {
            execvp(argv[0], (char *const *)argv);
        }
        _exit(127);
    }

    deadline = profile_now_seconds() + ((double)timeout_ms / 1000.0);
    for (;;) {
        pid_t waited = waitpid(child, &status, WNOHANG);

        if (waited == child) {
            if (WIFEXITED(status)) {
                return WEXITSTATUS(status);
            }
            return 1;
        }
        if (waited < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
        if ((timeout_ms > 0U) && (profile_now_seconds() >= deadline)) {
            (void)kill(child, SIGTERM);
            {
                struct timespec grace = {.tv_sec = 0, .tv_nsec = 100000000L};

                (void)nanosleep(&grace, nullptr);
            }
            if (waitpid(child, &status, WNOHANG) == 0) {
                (void)kill(child, SIGKILL);
            }
            (void)waitpid(child, &status, 0);
            return 124;
        }
        {
            struct timespec pause = {.tv_sec = 0, .tv_nsec = 10000000L};

            (void)nanosleep(&pause, nullptr);
        }
    }
#endif
}

static int run_process_with_timeout_output(const char *cwd,
                                           const char *const argv[],
                                           uint32_t timeout_ms,
                                           const char *output_path)
{
#ifdef _WIN32
    return cp_run_process_output(cwd, argv, timeout_ms, output_path);
#else
    pid_t child;
    double deadline;
    int status = 0;

    if ((argv == nullptr) || (argv[0] == nullptr) || (argv[0][0] == '\0') ||
        (output_path == nullptr)) {
        return -1;
    }
    child = fork();
    if (child < 0) {
        return -1;
    }
    if (child == 0) {
        int output_fd = open(output_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);

        if ((output_fd < 0) || (dup2(output_fd, STDOUT_FILENO) < 0)) {
            _exit(127);
        }
        (void)close(output_fd);
        if ((cwd != nullptr) && (chdir(cwd) != 0)) {
            _exit(127);
        }
        if (strchr(argv[0], '/') != nullptr) {
            execv(argv[0], (char *const *)argv);
        } else {
            execvp(argv[0], (char *const *)argv);
        }
        _exit(127);
    }

    deadline = profile_now_seconds() + ((double)timeout_ms / 1000.0);
    for (;;) {
        pid_t waited = waitpid(child, &status, WNOHANG);

        if (waited == child) {
            if (WIFEXITED(status)) {
                return WEXITSTATUS(status);
            }
            return 1;
        }
        if (waited < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
        if ((timeout_ms > 0U) && (profile_now_seconds() >= deadline)) {
            (void)kill(child, SIGTERM);
            {
                struct timespec grace = {.tv_sec = 0, .tv_nsec = 100000000L};

                (void)nanosleep(&grace, nullptr);
            }
            if (waitpid(child, &status, WNOHANG) == 0) {
                (void)kill(child, SIGKILL);
            }
            (void)waitpid(child, &status, 0);
            return 124;
        }
        {
            struct timespec pause = {.tv_sec = 0, .tv_nsec = 10000000L};

            (void)nanosleep(&pause, nullptr);
        }
    }
#endif
}

static bool has_maven_input_name(const char *path)
{
    const char *name = strrchr(path, '/');

    name = name == nullptr ? path : name + 1;
    return (strcmp(name, "pom.xml") == 0) || (strcmp(name, "mvnw") == 0) ||
           (strcmp(name, "mvnw.cmd") == 0);
}

static bool accepts_every_file(const char *path)
{
    (void)path;
    return true;
}

static int maven_input_callback(const char *path, void *ctx)
{
    MavenDependencySet *set = ctx;
    MavenInputSnapshot *input;

    if ((set->input_count == set->input_capacity) &&
        !grow_array((void **)&set->inputs,
                    &set->input_capacity,
                    sizeof(*set->inputs),
                    16U)) {
        return -1;
    }
    input = &set->inputs[set->input_count];
    (void)memset(input, 0, sizeof(*input));
    input->path = copy_bytes(path, strlen(path));
    if ((input->path == nullptr) ||
        (stat_regular_file(path,
                           &input->size,
                           &input->mtime_sec,
                           &input->mtime_nsec) != 0)) {
        return -1;
    }
    set->input_count++;
    return 0;
}

static int maven_input_compare(const void *left, const void *right)
{
    const MavenInputSnapshot *a = left;
    const MavenInputSnapshot *b = right;

    return strcmp(a->path, b->path);
}

static bool maven_input_already_collected(const MavenDependencySet *set, const char *path)
{
    for (size_t i = 0U; i < set->input_count; i++) {
        if (strcmp(set->inputs[i].path, path) == 0) {
            return true;
        }
    }
    return false;
}

static char *maven_parent_relative_path(const CodeLensMappedFile *pom)
{
    char *xml = copy_bytes(pom->data, pom->len);
    char *parent;
    char *parent_end;
    char *relative;
    char *tag_end;
    char *close;
    char *start;
    char *end;

    if (xml == nullptr) {
        return nullptr;
    }
    parent = strstr(xml, "<parent");
    if (parent == nullptr) {
        return nullptr;
    }
    parent = strchr(parent, '>');
    parent_end = parent == nullptr ? nullptr : strstr(parent + 1, "</parent>");
    if (parent_end == nullptr) {
        return nullptr;
    }
    relative = strstr(parent + 1, "<relativePath");
    if ((relative == nullptr) || (relative >= parent_end)) {
        return copy_bytes("../pom.xml", strlen("../pom.xml"));
    }
    tag_end = strchr(relative, '>');
    if ((tag_end == nullptr) || (tag_end >= parent_end) ||
        ((tag_end > relative) && (tag_end[-1] == '/'))) {
        return nullptr;
    }
    close = strstr(tag_end + 1, "</relativePath>");
    if ((close == nullptr) || (close > parent_end)) {
        return nullptr;
    }
    start = tag_end + 1;
    end = close;
    while ((start < end) && isspace((unsigned char)*start)) start++;
    while ((end > start) && isspace((unsigned char)end[-1])) end--;
    return end == start ? nullptr : copy_bytes(start, (size_t)(end - start));
}

static int maven_collect_parent_poms(MavenDependencySet *set)
{
    for (size_t i = 0U; i < set->input_count; i++) {
        MavenInputSnapshot *input = &set->inputs[i];
        const char *slash = strrchr(input->path, '/');
        const char *name = slash == nullptr ? input->path : slash + 1;
        CodeLensMappedFile pom = {0};
        char *relative;
        char *directory;
        char *candidate;
        char resolved[PATH_MAX];
        size_t directory_len;

        if ((slash == nullptr) || (strcmp(name, "pom.xml") != 0)) {
            continue;
        }
        if (code_lens_map_file(input->path, &pom) != 0) {
            return -1;
        }
        relative = maven_parent_relative_path(&pom);
        code_lens_mapped_file_free(&pom);
        if (relative == nullptr) {
            continue;
        }
        directory_len = (size_t)(slash - input->path);
        directory = copy_bytes(input->path, directory_len);
        candidate = directory == nullptr ? nullptr : code_lens_join_path(directory, relative);
        if ((candidate == nullptr) || (realpath(candidate, resolved) == nullptr) ||
            maven_input_already_collected(set, resolved)) {
            continue;
        }
        if (maven_input_callback(resolved, set) != 0) {
            return -1;
        }
    }
    return 0;
}

static int maven_collect_inputs(const char *repo_path, MavenDependencySet *set)
{
    char *maven_dir;

    if (set->project_kind == DEPENDENCY_PROJECT_NONE) {
        return 0;
    }
    if ((set->project_kind == DEPENDENCY_PROJECT_LEININGEN) ||
        (set->project_kind == DEPENDENCY_PROJECT_TOOLS_DEPS)) {
        return maven_input_callback(set->root_pom, set);
    }

    if (code_lens_walk_files(repo_path,
                             has_maven_input_name,
                             maven_input_callback,
                             set) != 0) {
        return -1;
    }
    maven_dir = code_lens_join_path(repo_path, ".mvn");
    if ((maven_dir != nullptr) && code_lens_is_directory(maven_dir) &&
        (code_lens_walk_all_files(maven_dir,
                                  accepts_every_file,
                                  maven_input_callback,
                                  set) != 0)) {
        return -1;
    }
    if (maven_collect_parent_poms(set) != 0) {
        return -1;
    }
    if (set->input_count > 1U) {
        size_t unique = 1U;
        qsort(set->inputs,
              set->input_count,
              sizeof(*set->inputs),
              maven_input_compare);
        for (size_t i = 1U; i < set->input_count; i++) {
            if (strcmp(set->inputs[i].path, set->inputs[unique - 1U].path) != 0) {
                set->inputs[unique++] = set->inputs[i];
            }
        }
        set->input_count = unique;
    }
    return 0;
}

static bool has_source_jar_suffix(const char *path)
{
    static const char suffix[] = "-sources.jar";
    size_t len = strlen(path);

    return (len >= sizeof(suffix) - 1U) &&
           (strcmp(path + len - (sizeof(suffix) - 1U), suffix) == 0);
}

static uint16_t zip_u16(const uint8_t *value)
{
    return (uint16_t)((uint16_t)value[0] | ((uint16_t)value[1] << 8U));
}

static uint32_t zip_u32(const uint8_t *value)
{
    return (uint32_t)value[0] | ((uint32_t)value[1] << 8U) |
           ((uint32_t)value[2] << 16U) | ((uint32_t)value[3] << 24U);
}

static bool zip_dependency_source_extension(const char *name, size_t len)
{
    static const char *const suffixes[] = {".clj", ".cljc", ".cljs", ".bb", ".java"};

    for (size_t i = 0U; i < sizeof(suffixes) / sizeof(suffixes[0]); i++) {
        size_t suffix_len = strlen(suffixes[i]);

        if ((len >= suffix_len) &&
            (memcmp(name + len - suffix_len, suffixes[i], suffix_len) == 0)) {
            return true;
        }
    }
    return false;
}

static bool zip_source_path_safe(const char *name, size_t len)
{
    size_t segment_start = 0U;

    if ((len == 0U) || (name[0] == '/') || (name[0] == '\\') ||
        !zip_dependency_source_extension(name, len)) {
        return false;
    }
    for (size_t i = 0U; i <= len; i++) {
        if ((i < len) && ((name[i] == '\\') || (name[i] == ':') ||
                          ((unsigned char)name[i] < 32U))) {
            return false;
        }
        if ((i == len) || (name[i] == '/')) {
            size_t segment_len = i - segment_start;

            if ((segment_len == 0U) ||
                ((segment_len == 1U) && (name[segment_start] == '.')) ||
                ((segment_len == 2U) && (name[segment_start] == '.') &&
                 (name[segment_start + 1U] == '.'))) {
                return false;
            }
            segment_start = i + 1U;
        }
    }
    return true;
}

static int mkdir_parent_for_file(char *path)
{
    char *slash = strrchr(path, '/');

    if (slash == nullptr) {
        return 0;
    }
    *slash = '\0';
    if (code_lens_mkdir_p(path) != 0) {
        *slash = '/';
        return -1;
    }
    *slash = '/';
    return 0;
}

static uint32_t zip_crc32(const uint8_t *data, size_t len)
{
    uLong crc = crc32(0L, Z_NULL, 0);
    size_t offset = 0U;

    while (offset < len) {
        size_t remaining = len - offset;
        uInt chunk = remaining > (size_t)UINT_MAX ? UINT_MAX : (uInt)remaining;

        crc = crc32(crc, data + offset, chunk);
        offset += (size_t)chunk;
    }
    return (uint32_t)crc;
}

static int zip_write_source_entry(const CodeLensMappedFile *jar,
                                const uint8_t *central,
                                const char *entry_name,
                                size_t entry_name_len,
                                const char *source_root)
{
    uint16_t flags = zip_u16(central + 8U);
    uint16_t method = zip_u16(central + 10U);
    uint32_t expected_crc = zip_u32(central + 16U);
    uint32_t compressed_size = zip_u32(central + 20U);
    uint32_t uncompressed_size = zip_u32(central + 24U);
    uint32_t local_offset = zip_u32(central + 42U);
    const uint8_t *local;
    size_t data_offset;
    uint8_t *content = nullptr;
    char *relative = nullptr;
    char *output_path = nullptr;
    FILE *output = nullptr;
    int rc = -1;

    if (((flags & 1U) != 0U) || ((method != 0U) && (method != 8U)) ||
        (compressed_size == UINT32_MAX) || (uncompressed_size == UINT32_MAX) ||
        (uncompressed_size > 64U * 1024U * 1024U) ||
        ((uint64_t)local_offset + 30U > (uint64_t)jar->len)) {
        return -1;
    }
    local = (const uint8_t *)jar->data + local_offset;
    if (zip_u32(local) != 0x04034b50U) {
        return -1;
    }
    data_offset = (size_t)local_offset + 30U + (size_t)zip_u16(local + 26U) +
                  (size_t)zip_u16(local + 28U);
    if ((data_offset > jar->len) ||
        ((size_t)compressed_size > jar->len - data_offset)) {
        return -1;
    }

    content = malloc(uncompressed_size == 0U ? 1U : (size_t)uncompressed_size);
    if (content == nullptr) {
        return -1;
    }
    if (method == 0U) {
        if (compressed_size != uncompressed_size) {
            goto done;
        }
        if (uncompressed_size > 0U) {
            (void)memcpy(content, (const uint8_t *)jar->data + data_offset, uncompressed_size);
        }
    } else {
        struct libdeflate_decompressor *decompressor = libdeflate_alloc_decompressor();
        size_t actual = 0U;
        enum libdeflate_result result;

        if (decompressor == nullptr) {
            goto done;
        }
        result = libdeflate_deflate_decompress(decompressor,
                                               (const uint8_t *)jar->data + data_offset,
                                               compressed_size,
                                               content,
                                               uncompressed_size,
                                               &actual);
        libdeflate_free_decompressor(decompressor);
        if ((result != LIBDEFLATE_SUCCESS) || (actual != (size_t)uncompressed_size)) {
            goto done;
        }
    }
    if (zip_crc32(content, uncompressed_size) != expected_crc) {
        goto done;
    }

    relative = copy_bytes(entry_name, entry_name_len);
    output_path = relative == nullptr ? nullptr : code_lens_join_path(source_root, relative);
    if ((output_path == nullptr) || (mkdir_parent_for_file(output_path) != 0)) {
        goto done;
    }
    output = fopen(output_path, "wb");
    if (output == nullptr) {
        goto done;
    }
    if ((uncompressed_size > 0U) &&
        (fwrite(content, 1U, uncompressed_size, output) != uncompressed_size)) {
        goto done;
    }
    if (fclose(output) != 0) {
        output = nullptr;
        goto done;
    }
    output = nullptr;
    rc = 0;

done:
    if (output != nullptr) {
        (void)fclose(output);
    }
    free(content);
    return rc;
}

static int zip_extract_source_entries(const char *jar_path,
                                      const char *source_root,
                                      size_t *out_file_count)
{
    CodeLensMappedFile jar = {0};
    const uint8_t *bytes;
    size_t search_start;
    size_t eocd = SIZE_MAX;
    uint16_t entry_count;
    uint32_t central_size;
    uint32_t central_offset;
    size_t position;
    size_t extracted = 0U;
    uint64_t total_uncompressed = 0U;
    int rc = -1;

    if ((code_lens_map_file(jar_path, &jar) != 0) || (jar.len < 22U)) {
        return -1;
    }
    bytes = (const uint8_t *)jar.data;
    search_start = jar.len > 65557U ? jar.len - 65557U : 0U;
    for (size_t pos = jar.len - 22U;; pos--) {
        if (zip_u32(bytes + pos) == 0x06054b50U) {
            eocd = pos;
            break;
        }
        if (pos == search_start) {
            break;
        }
    }
    if ((eocd == SIZE_MAX) || (zip_u16(bytes + eocd + 4U) != 0U) ||
        (zip_u16(bytes + eocd + 6U) != 0U) ||
        (zip_u16(bytes + eocd + 8U) != zip_u16(bytes + eocd + 10U)) ||
        (eocd + 22U + (size_t)zip_u16(bytes + eocd + 20U) != jar.len)) {
        goto done;
    }
    entry_count = zip_u16(bytes + eocd + 10U);
    central_size = zip_u32(bytes + eocd + 12U);
    central_offset = zip_u32(bytes + eocd + 16U);
    if ((central_offset == UINT32_MAX) || (central_size == UINT32_MAX) ||
        ((uint64_t)central_offset + central_size > (uint64_t)jar.len)) {
        goto done;
    }

    position = central_offset;
    for (uint32_t i = 0U; i < (uint32_t)entry_count; i++) {
        const uint8_t *central;
        uint16_t name_len;
        uint16_t extra_len;
        uint16_t comment_len;
        size_t next;

        if ((position > jar.len) || (jar.len - position < 46U)) {
            goto done;
        }
        central = bytes + position;
        if (zip_u32(central) != 0x02014b50U) {
            goto done;
        }
        name_len = zip_u16(central + 28U);
        extra_len = zip_u16(central + 30U);
        comment_len = zip_u16(central + 32U);
        next = position + 46U + (size_t)name_len + (size_t)extra_len + (size_t)comment_len;
        if ((next < position) || (next > jar.len)) {
            goto done;
        }
        if (zip_source_path_safe((const char *)central + 46U, name_len)) {
            uint32_t entry_size = zip_u32(central + 24U);

            if ((entry_size == UINT32_MAX) ||
                ((uint64_t)entry_size >
                 MAVEN_SOURCE_JAR_MAX_UNCOMPRESSED_BYTES - total_uncompressed)) {
                goto done;
            }
            total_uncompressed += (uint64_t)entry_size;
            if (zip_write_source_entry(&jar,
                                       central,
                                       (const char *)central + 46U,
                                       name_len,
                                       source_root) != 0) {
                goto done;
            }
            extracted++;
        }
        position = next;
    }
    if (out_file_count != nullptr) {
        *out_file_count = extracted;
    }
    rc = 0;

done:
    code_lens_mapped_file_free(&jar);
    return rc;
}

/* A main JAR that already carries supported source files needs no sibling
 * -sources artifact. Inspect only ZIP metadata here; full extraction remains
 * in maven_materialize_source_jar after the preferred artifact is chosen. */
static bool zip_has_source_entries(const char *jar_path)
{
    CodeLensMappedFile jar = {0};
    const uint8_t *bytes;
    size_t search_start;
    size_t eocd = SIZE_MAX;
    uint16_t entry_count;
    uint32_t central_size;
    uint32_t central_offset;
    size_t position;
    bool found = false;

    if ((code_lens_map_file(jar_path, &jar) != 0) || (jar.len < 22U)) {
        return false;
    }
    bytes = (const uint8_t *)jar.data;
    search_start = jar.len > 65557U ? jar.len - 65557U : 0U;
    for (size_t pos = jar.len - 22U;; pos--) {
        if (zip_u32(bytes + pos) == 0x06054b50U) {
            eocd = pos;
            break;
        }
        if (pos == search_start) {
            break;
        }
    }
    if ((eocd == SIZE_MAX) || (zip_u16(bytes + eocd + 4U) != 0U) ||
        (zip_u16(bytes + eocd + 6U) != 0U) ||
        (zip_u16(bytes + eocd + 8U) != zip_u16(bytes + eocd + 10U)) ||
        (eocd + 22U + (size_t)zip_u16(bytes + eocd + 20U) != jar.len)) {
        goto done;
    }
    entry_count = zip_u16(bytes + eocd + 10U);
    central_size = zip_u32(bytes + eocd + 12U);
    central_offset = zip_u32(bytes + eocd + 16U);
    if ((central_offset == UINT32_MAX) || (central_size == UINT32_MAX) ||
        ((uint64_t)central_offset + central_size > (uint64_t)jar.len)) {
        goto done;
    }
    position = central_offset;
    for (uint32_t i = 0U; i < (uint32_t)entry_count; i++) {
        const uint8_t *central;
        uint16_t name_len;
        uint16_t extra_len;
        uint16_t comment_len;
        size_t next;

        if ((position > jar.len) || (jar.len - position < 46U)) {
            goto done;
        }
        central = bytes + position;
        if (zip_u32(central) != 0x02014b50U) {
            goto done;
        }
        name_len = zip_u16(central + 28U);
        extra_len = zip_u16(central + 30U);
        comment_len = zip_u16(central + 32U);
        next = position + 46U + (size_t)name_len + (size_t)extra_len + (size_t)comment_len;
        if ((next < position) || (next > jar.len)) {
            goto done;
        }
        if (zip_source_path_safe((const char *)central + 46U, name_len)) {
            found = true;
            break;
        }
        position = next;
    }

done:
    code_lens_mapped_file_free(&jar);
    return found;
}

static uint64_t file_fnv1a64(const CodeLensMappedFile *file)
{
    uint64_t hash = 0xcbf29ce484222325ULL;

    for (size_t i = 0U; i < file->len; i++) {
        hash ^= (uint64_t)(unsigned char)file->data[i];
        hash *= 0x00000100000001b3ULL;
    }
    return hash;
}

static bool maven_path_component_safe(const char *value)
{
    if ((value == nullptr) || (value[0] == '\0') || (strcmp(value, ".") == 0) ||
        (strcmp(value, "..") == 0)) {
        return false;
    }
    for (size_t i = 0U; value[i] != '\0'; i++) {
        unsigned char ch = (unsigned char)value[i];

        if (!isalnum(ch) && (ch != '.') && (ch != '-') && (ch != '_')) {
            return false;
        }
    }
    return true;
}

static char *maven_group_path(const char *group_id)
{
    char *path = copy_bytes(group_id, strlen(group_id));

    if (path != nullptr) {
        for (size_t i = 0U; path[i] != '\0'; i++) {
            if (path[i] == '.') {
                path[i] = '/';
            }
        }
    }
    return path;
}

static int maven_cache_lock_acquire(uint64_t checksum, int *out_fd)
{
    char *home = code_lens_default_home();
    char *lock_dir = home == nullptr ? nullptr : code_lens_join_path(home, "dependencies/.locks");
    char *lock_path = lock_dir == nullptr
                          ? nullptr
                          : alloc_printf("%s/%016llx.lock",
                                         lock_dir,
                                         (unsigned long long)checksum);
    int fd;
#ifdef _WIN32
    int rc;
#else
    struct flock request = {
        .l_type = F_WRLCK,
        .l_whence = SEEK_SET,
        .l_start = 0,
        .l_len = 0,
    };
    int rc;
#endif

    if ((lock_path == nullptr) || (code_lens_mkdir_p(lock_dir) != 0)) {
        return -1;
    }
    fd = open(lock_path, O_RDWR | O_CREAT | O_CLOEXEC, 0644);
    if (fd < 0) {
        return -1;
    }
#ifdef _WIN32
    rc = cp_lock_fd(fd);
#else
    do {
        rc = fcntl(fd, F_SETLKW, &request);
    } while ((rc != 0) && (errno == EINTR));
#endif
    if (rc != 0) {
        (void)close(fd);
        return -1;
    }
    *out_fd = fd;
    return 0;
}

static void maven_cache_lock_release(int fd)
{
#ifdef _WIN32
    (void)cp_unlock_fd(fd);
#else
    struct flock request = {
        .l_type = F_UNLCK,
        .l_whence = SEEK_SET,
        .l_start = 0,
        .l_len = 0,
    };

    (void)fcntl(fd, F_SETLK, &request);
#endif
    (void)close(fd);
}

static bool maven_source_cache_valid(const char *source_root,
                                      const char *marker,
                                      uint64_t checksum)
{
    FILE *file;
    char line[256];
    unsigned long long expected_hash;
    size_t expected_count;
    CodeLensPathList sources = {0};
    int64_t marker_size = 0;
    int64_t marker_sec = 0;
    int64_t marker_nsec = 0;

    if (!code_lens_path_exists(marker) ||
        (stat_regular_file(marker, &marker_size, &marker_sec, &marker_nsec) != 0)) {
        return false;
    }
    file = fopen(marker, "rb");
    if (file == nullptr) {
        return false;
    }
    if ((fgets(line, sizeof(line), file) == nullptr) ||
        (strstr(line, ":" DEPENDENCY_SOURCE_CACHE_MARKER "\n") == nullptr) ||
        (fscanf(file, "%llx\n%zu", &expected_hash, &expected_count) != 2)) {
        (void)fclose(file);
        return false;
    }
    (void)fclose(file);
    if (expected_hash != (unsigned long long)checksum) {
        return false;
    }
    if ((code_lens_collect_all_files(source_root, has_supported_extension, &sources) != 0) ||
        (sources.count != expected_count)) {
        return false;
    }
    for (size_t i = 0U; i < sources.count; i++) {
        int64_t size = 0;
        int64_t sec = 0;
        int64_t nsec = 0;

        if ((stat_regular_file(sources.paths[i], &size, &sec, &nsec) != 0) ||
            (sec > marker_sec) || ((sec == marker_sec) && (nsec > marker_nsec))) {
            return false;
        }
    }
    return true;
}

static bool maven_source_cache_marker_current(const char *source_root,
                                               const char *checksum_text)
{
    char *marker;
    FILE *file;
    char line[256];
    unsigned long long marker_hash;
    unsigned long long expected_hash;
    char *end = nullptr;

    if ((source_root == nullptr) || (checksum_text == nullptr)) {
        return false;
    }
    errno = 0;
    expected_hash = strtoull(checksum_text, &end, 16);
    if ((errno == ERANGE) || (end == checksum_text) || (end == nullptr) ||
        (*end != '\0')) {
        return false;
    }
    marker = code_lens_join_path(source_root, ".complete");
    file = marker == nullptr ? nullptr : fopen(marker, "rb");
    if (file == nullptr) {
        return false;
    }
    if ((fgets(line, sizeof(line), file) == nullptr) ||
        (strstr(line, ":" DEPENDENCY_SOURCE_CACHE_MARKER "\n") == nullptr) ||
        (fscanf(file, "%llx", &marker_hash) != 1)) {
        (void)fclose(file);
        return false;
    }
    (void)fclose(file);
    return marker_hash == expected_hash;
}

static int maven_materialize_source_jar(const char *jar_path,
                                        const char *group_id,
                                        const char *artifact_id,
                                        const char *version,
                                        char **out_root,
                                        char **out_checksum)
{
    CodeLensMappedFile jar = {0};
    char *home;
    char *group_path;
    uint64_t checksum;
    char *root;
    char *marker;
    int lock_fd = -1;
    size_t extracted_count = 0U;
    int rc = -1;

    if (code_lens_map_file(jar_path, &jar) != 0) {
        return -1;
    }
    checksum = file_fnv1a64(&jar);
    code_lens_mapped_file_free(&jar);
    home = code_lens_default_home();
    group_path = maven_group_path(group_id);
    root = (home == nullptr) || (group_path == nullptr)
               ? nullptr
               : alloc_printf("%s/dependencies/sources/%s/%s/%s/%016llx",
                              home,
                              group_path,
                              artifact_id,
                              version,
                              (unsigned long long)checksum);
    marker = root == nullptr ? nullptr : code_lens_join_path(root, ".complete");
    if ((root == nullptr) || (marker == nullptr) ||
        (maven_cache_lock_acquire(checksum, &lock_fd) != 0)) {
        return -1;
    }
    if (!maven_source_cache_valid(root, marker, checksum)) {
        FILE *complete;

        if ((code_lens_remove_tree(root) != 0) || (code_lens_mkdir_p(root) != 0) ||
            (zip_extract_source_entries(jar_path, root, &extracted_count) != 0)) {
            (void)code_lens_remove_tree(root);
            goto done;
        }
        complete = fopen(marker, "wb");
        if (complete == nullptr) {
            (void)code_lens_remove_tree(root);
            goto done;
        }
        (void)fprintf(complete,
                      "%s:%s:%s:%s\n%016llx\n%zu\n",
                      group_id,
                      artifact_id,
                      version,
                      DEPENDENCY_SOURCE_CACHE_MARKER,
                      (unsigned long long)checksum,
                      extracted_count);
        if (fclose(complete) != 0) {
            (void)code_lens_remove_tree(root);
            goto done;
        }
    }
    {
        char resolved[PATH_MAX];

        if (realpath(root, resolved) != nullptr) {
            root = alloc_printf("%s", resolved);
        }
    }
    *out_root = root;
    *out_checksum = alloc_printf("%016llx", (unsigned long long)checksum);
    rc = (*out_root == nullptr) || (*out_checksum == nullptr) ? -1 : 0;

done:
    maven_cache_lock_release(lock_fd);
    return rc;
}

static int maven_path_ptr_compare(const void *left, const void *right)
{
    const char *const *a = left;
    const char *const *b = right;

    return strcmp(*a, *b);
}

static int maven_artifact_push(MavenDependencySet *set,
                               const char *coordinate,
                               const char *group_id,
                               const char *artifact_id,
                               const char *version,
                               const char *scope,
                               int direct,
                               const char *source_jar,
                               const char *source_root,
                               const char *checksum,
                               const char *existing_id)
{
    MavenSourceArtifact *artifact;

    if ((set->artifact_count == set->artifact_capacity) &&
        !grow_array((void **)&set->artifacts,
                    &set->artifact_capacity,
                    sizeof(*set->artifacts),
                    16U)) {
        return -1;
    }
    artifact = &set->artifacts[set->artifact_count];
    (void)memset(artifact, 0, sizeof(*artifact));
    artifact->coordinate = copy_bytes(coordinate, strlen(coordinate));
    artifact->group_id = copy_bytes(group_id, strlen(group_id));
    artifact->artifact_id = copy_bytes(artifact_id, strlen(artifact_id));
    artifact->version = copy_bytes(version, strlen(version));
    artifact->scope = copy_bytes(scope, strlen(scope));
    artifact->source_jar = copy_bytes(source_jar, strlen(source_jar));
    artifact->source_root = copy_bytes(source_root, strlen(source_root));
    artifact->checksum = copy_bytes(checksum, strlen(checksum));
    artifact->direct = direct;
    artifact->id = existing_id == nullptr
                       ? alloc_printf("%s|dependency|%s|%s",
                                      set->repo_path,
                                      coordinate,
                                      checksum)
                       : copy_bytes(existing_id, strlen(existing_id));
    if ((artifact->coordinate == nullptr) || (artifact->group_id == nullptr) ||
        (artifact->artifact_id == nullptr) || (artifact->version == nullptr) ||
        (artifact->scope == nullptr) || (artifact->source_jar == nullptr) ||
        (artifact->source_root == nullptr) || (artifact->checksum == nullptr) ||
        (artifact->id == nullptr) || !code_lens_is_directory(artifact->source_root) ||
        (code_lens_collect_all_files(artifact->source_root,
                                     has_supported_extension,
                                     &artifact->files) != 0)) {
        return -1;
    }
    if (artifact->files.count > 1U) {
        qsort(artifact->files.paths,
              artifact->files.count,
              sizeof(*artifact->files.paths),
              maven_path_ptr_compare);
    }
    set->artifact_count++;
    return 0;
}

static int maven_artifact_compare(const void *left, const void *right)
{
    const MavenSourceArtifact *a = left;
    const MavenSourceArtifact *b = right;
    int coordinate = strcmp(a->coordinate, b->coordinate);

    return coordinate != 0 ? coordinate : strcmp(a->checksum, b->checksum);
}

typedef struct {
    MavenDependencySet *set;
    const char *output_root;
} MavenJarCollectContext;

static bool has_jar_suffix(const char *path)
{
    size_t len = strlen(path);

    return (len >= 4U) && (strcmp(path + len - 4U, ".jar") == 0);
}

/* Parse repository-layout group/artifact/version paths once for both Maven's
 * copied source tree and classpaths printed by Leiningen/tools.deps. The jar
 * used to derive the coordinate may be a main jar while source_jar is its
 * preferred sibling -sources.jar. */
static int maven_repository_jar_add(MavenDependencySet *set,
                                    const char *coordinate_jar,
                                    const char *source_jar,
                                    const char *repository_root,
                                    bool require_sources_suffix,
                                    const char *scope)
{
    size_t root_len = strlen(repository_root);
    const char *relative;
    char *copy;
    char *parts[128];
    size_t part_count = 0U;
    char *scan;
    StringBuilder group = {0};
    char *coordinate = nullptr;
    char *source_root = nullptr;
    char *checksum = nullptr;

    if ((strncmp(coordinate_jar, repository_root, root_len) != 0) ||
        (coordinate_jar[root_len] != '/') || !has_jar_suffix(coordinate_jar) ||
        (require_sources_suffix && !has_source_jar_suffix(coordinate_jar))) {
        set->skipped_artifacts++;
        return 0;
    }
    relative = coordinate_jar + root_len + 1U;
    copy = copy_bytes(relative, strlen(relative));
    if (copy == nullptr) {
        return -1;
    }
    scan = copy;
    while ((part_count < sizeof(parts) / sizeof(parts[0])) && (scan[0] != '\0')) {
        char *slash;

        parts[part_count++] = scan;
        slash = strchr(scan, '/');
        if (slash == nullptr) {
            break;
        }
        *slash = '\0';
        scan = slash + 1;
    }
    if ((part_count < 4U) || !maven_path_component_safe(parts[part_count - 3U]) ||
        !maven_path_component_safe(parts[part_count - 2U])) {
        set->skipped_artifacts++;
        return 0;
    }
    for (size_t i = 0U; i + 3U < part_count; i++) {
        if (!maven_path_component_safe(parts[i]) ||
            ((i > 0U) && !sb_append(&group, ".")) || !sb_append(&group, parts[i])) {
            sb_free(&group);
            set->skipped_artifacts++;
            return 0;
        }
    }
    coordinate = alloc_printf("%s:%s:%s",
                              group.data,
                              parts[part_count - 3U],
                              parts[part_count - 2U]);
    if (coordinate == nullptr) {
        sb_free(&group);
        return -1;
    }
    for (size_t i = 0U; i < set->artifact_count; i++) {
        if ((strcmp(set->artifacts[i].coordinate, coordinate) == 0) &&
            (strcmp(set->artifacts[i].source_jar, source_jar) == 0)) {
            sb_free(&group);
            return 0;
        }
    }
    if ((maven_materialize_source_jar(source_jar,
                                     group.data,
                                     parts[part_count - 3U],
                                     parts[part_count - 2U],
                                     &source_root,
                                     &checksum) != 0) ||
        (maven_artifact_push(set,
                             coordinate,
                             group.data,
                             parts[part_count - 3U],
                             parts[part_count - 2U],
                             scope,
                             -1,
                             source_jar,
                             source_root,
                             checksum,
                             nullptr) != 0)) {
        /* One malformed or unreadable dependency must not fail workspace
         * indexing or hide other usable artifacts. */
        set->skipped_artifacts++;
    }
    sb_free(&group);
    return 0;
}

static int maven_source_jar_callback(const char *jar_path, void *ctx)
{
    MavenJarCollectContext *collect = ctx;

    return maven_repository_jar_add(collect->set,
                                    jar_path,
                                    jar_path,
                                    collect->output_root,
                                    true,
                                    "test-classpath");
}

static char *maven_resolution_output(const char *repo_path)
{
    char *home = code_lens_default_home();
    char *component = repo_dir_component(repo_path);

    return (home == nullptr) || (component == nullptr)
               ? nullptr
               : alloc_printf("%s/dependencies/projects/%s/artifacts", home, component);
}

static const char *maven_command(const char *repo_path)
{
    const char *configured = getenv("CODE_LENS_MAVEN_COMMAND");
    char *wrapper;

    if ((configured != nullptr) && (configured[0] != '\0')) {
        return configured;
    }
#ifdef _WIN32
    wrapper = code_lens_join_path(repo_path, "mvnw.cmd");
    return (wrapper != nullptr) && code_lens_path_exists(wrapper) ? wrapper : "mvn.cmd";
#else
    wrapper = code_lens_join_path(repo_path, "mvnw");
    return (wrapper != nullptr) && code_lens_path_exists(wrapper) ? wrapper : "mvn";
#endif
}

typedef int (*DependencyClasspathCallback)(const char *entry, void *ctx);

static int dependency_classpath_split(const char *classpath,
                                      size_t len,
                                      char separator,
                                      DependencyClasspathCallback callback,
                                      void *ctx)
{
    size_t start = 0U;

    if ((classpath == nullptr) || (callback == nullptr)) {
        return -1;
    }
    for (size_t i = 0U; i <= len; i++) {
        if ((i == len) || (classpath[i] == separator)) {
            size_t entry_start = start;
            size_t entry_end = i;
            char *entry;

            while ((entry_start < entry_end) &&
                   isspace((unsigned char)classpath[entry_start])) {
                entry_start++;
            }
            while ((entry_end > entry_start) &&
                   isspace((unsigned char)classpath[entry_end - 1U])) {
                entry_end--;
            }
            start = i + 1U;
            if (entry_end == entry_start) {
                continue;
            }
            entry = copy_bytes(classpath + entry_start, entry_end - entry_start);
            if ((entry == nullptr) || (callback(entry, ctx) != 0)) {
                return -1;
            }
        }
    }
    return 0;
}

#ifdef CODE_LENS_NO_MAIN
typedef struct {
    size_t count;
} DependencyClasspathCount;

static int dependency_classpath_count_callback(const char *entry, void *ctx)
{
    DependencyClasspathCount *count = ctx;

    (void)entry;
    count->count++;
    return 0;
}

size_t code_lens_test_classpath_entry_count(const char *classpath, char separator)
{
    DependencyClasspathCount count = {0};

    if ((classpath == nullptr) ||
        (dependency_classpath_split(classpath,
                                    strlen(classpath),
                                    separator,
                                    dependency_classpath_count_callback,
                                    &count) != 0)) {
        return 0U;
    }
    return count.count;
}
#endif

static bool dependency_path_absolute(const char *path)
{
    size_t len = path == nullptr ? 0U : strlen(path);

    return (len > 0U) &&
           ((path[0] == '/') ||
            ((len >= 3U) && isalpha((unsigned char)path[0]) && (path[1] == ':') &&
             (path[2] == '/')));
}

static void dependency_normalize_slashes(char *path)
{
    if (path == nullptr) {
        return;
    }
    for (size_t i = 0U; path[i] != '\0'; i++) {
        if (path[i] == '\\') {
            path[i] = '/';
        }
    }
}

static char *dependency_repository_root(const char *jar_path)
{
    static const char marker_text[] = "/repository/";
    const char *marker = strstr(jar_path, marker_text);
    const char *next;

    while ((marker != nullptr) &&
           ((next = strstr(marker + 1U, marker_text)) != nullptr)) {
        marker = next;
    }
    if (marker == nullptr) {
        return nullptr;
    }
    return copy_bytes(jar_path,
                      (size_t)(marker - jar_path) + strlen("/repository"));
}

static char *dependency_sources_sibling(const char *jar_path)
{
    size_t len = strlen(jar_path);
    const char *file_slash;
    const char *version_slash;
    const char *artifact_slash;

    if (has_source_jar_suffix(jar_path)) {
        return copy_bytes(jar_path, len);
    }
    if ((len < 4U) || (strcmp(jar_path + len - 4U, ".jar") != 0)) {
        return nullptr;
    }
    /* Maven source classifiers use artifact-version-sources.jar even when the
     * resolved main classpath entry has an OS/architecture classifier. */
    file_slash = strrchr(jar_path, '/');
    if (file_slash != nullptr) {
        char *directory = copy_bytes(jar_path, (size_t)(file_slash - jar_path));

        if (directory != nullptr) {
            version_slash = strrchr(directory, '/');
            if (version_slash != nullptr) {
                char *artifact_directory = copy_bytes(directory,
                                                       (size_t)(version_slash - directory));

                if (artifact_directory != nullptr) {
                    artifact_slash = strrchr(artifact_directory, '/');
                    if ((artifact_slash != nullptr) && (artifact_slash[1] != '\0') &&
                        (version_slash[1] != '\0')) {
                        return alloc_printf("%s/%s-%s-sources.jar",
                                            directory,
                                            artifact_slash + 1U,
                                            version_slash + 1U);
                    }
                }
            }
        }
    }
    return alloc_printf("%.*s-sources.jar", (int)(len - 4U), jar_path);
}

typedef struct {
    MavenDependencySet *set;
} DependencyClasspathCollect;

static int dependency_classpath_jar_callback(const char *entry, void *ctx)
{
    DependencyClasspathCollect *collect = ctx;
    MavenDependencySet *set = collect->set;
    char *normalized = copy_bytes(entry, strlen(entry));
    char resolved[PATH_MAX];
    char *coordinate_jar;
    char *repository_root;

    if (normalized == nullptr) {
        return -1;
    }
    dependency_normalize_slashes(normalized);
    if (!dependency_path_absolute(normalized) || !has_jar_suffix(normalized)) {
        return 0; /* workspace directories and relative classpath entries */
    }
    if (realpath(normalized, resolved) == nullptr) {
        set->skipped_artifacts++;
        return 0;
    }
    coordinate_jar = copy_bytes(resolved, strlen(resolved));
    if (coordinate_jar == nullptr) {
        return -1;
    }
    dependency_normalize_slashes(coordinate_jar);
    repository_root = dependency_repository_root(coordinate_jar);
    if (repository_root == nullptr) {
        set->skipped_artifacts++;
        return 0;
    }
    return path_list_append(&set->classpath_jars, coordinate_jar);
}

static const char *lein_command(void)
{
    const char *configured = getenv("CODE_LENS_LEIN_COMMAND");

    if ((configured != nullptr) && (configured[0] != '\0')) {
        return configured;
    }
#ifdef _WIN32
    return "lein.bat";
#else
    return "lein";
#endif
}

static const char *clojure_command(void)
{
    const char *configured = getenv("CODE_LENS_CLOJURE_COMMAND");

    if ((configured != nullptr) && (configured[0] != '\0')) {
        return configured;
    }
#ifdef _WIN32
    return "clojure.exe";
#else
    return "clojure";
#endif
}

static const char *tools_deps_aliases_or_empty(const char *aliases)
{
    return aliases == nullptr ? "" : aliases;
}

/* The aliases are passed as one exact argv value after a fixed "-M" prefix,
 * never evaluated by a shell. Keep validation deliberately light so valid
 * Clojure alias names remain Clojure's responsibility, while refusing values
 * that cannot represent the documented :a:b form. */
static bool tools_deps_aliases_valid(const char *aliases)
{
    if ((aliases == nullptr) || (aliases[0] == '\0')) {
        return true;
    }
    if ((aliases[0] != ':') || (aliases[1] == '\0')) {
        return false;
    }
    for (size_t i = 0U; aliases[i] != '\0'; i++) {
        if (isspace((unsigned char)aliases[i])) {
            return false;
        }
    }
    return true;
}

/* Build one tools.deps source-classifier entry from a normal Maven-layout JAR.
 * Newer Clojure CLIs model classifiers in the lib name (artifact$sources), not
 * as a :classifier coordinate key. Maven path segments are already constrained
 * to safe identifiers before they become EDN symbols or quoted versions. */
static char *tools_deps_source_spec(const char *jar_path)
{
    char *repository_root = dependency_repository_root(jar_path);
    size_t root_len;
    char *copy;
    char *parts[128];
    size_t part_count = 0U;
    char *scan;
    StringBuilder group = {0};
    char *spec = nullptr;

    if (repository_root == nullptr) {
        return nullptr;
    }
    root_len = strlen(repository_root);
    if ((strncmp(jar_path, repository_root, root_len) != 0) ||
        (jar_path[root_len] != '/') || !has_jar_suffix(jar_path)) {
        return nullptr;
    }
    copy = copy_bytes(jar_path + root_len + 1U, strlen(jar_path + root_len + 1U));
    if (copy == nullptr) {
        return nullptr;
    }
    scan = copy;
    while ((part_count < sizeof(parts) / sizeof(parts[0])) && (scan[0] != '\0')) {
        char *slash;

        parts[part_count++] = scan;
        slash = strchr(scan, '/');
        if (slash == nullptr) {
            break;
        }
        *slash = '\0';
        scan = slash + 1U;
    }
    if ((part_count < 4U) || !maven_path_component_safe(parts[part_count - 3U]) ||
        !maven_path_component_safe(parts[part_count - 2U]) ||
        !maven_path_component_safe(parts[part_count - 1U])) {
        return nullptr;
    }
    for (size_t i = 0U; i + 3U < part_count; i++) {
        if (!maven_path_component_safe(parts[i]) ||
            ((i > 0U) && !sb_append(&group, ".")) || !sb_append(&group, parts[i])) {
            sb_free(&group);
            return nullptr;
        }
    }
    spec = alloc_printf("%s/%s$sources {:mvn/version \"%s\"}",
                        group.data,
                        parts[part_count - 3U],
                        parts[part_count - 2U]);
    sb_free(&group);
    return spec;
}

static bool path_list_contains(const CodeLensPathList *list, const char *value)
{
    if ((list == nullptr) || (value == nullptr)) {
        return false;
    }
    for (size_t i = 0U; i < list->count; i++) {
        if (strcmp(list->paths[i], value) == 0) {
            return true;
        }
    }
    return false;
}

#define TOOLS_DEPS_SOURCE_BATCH_SIZE 32U

static char *dependency_source_classpath_output(const char *repo_path)
{
    char *home = code_lens_default_home();
    char *component = repo_dir_component(repo_path);
    char *directory = (home == nullptr) || (component == nullptr)
                          ? nullptr
                          : alloc_printf("%s/dependencies/projects/%s/classpath", home, component);

    if ((directory == nullptr) || (code_lens_mkdir_p(directory) != 0)) {
        return nullptr;
    }
    return code_lens_join_path(directory, "source-classpath.txt");
}

static int tools_deps_fetch_source_batch(const MavenDependencySet *set,
                                         const CodeLensPathList *specs,
                                         size_t first,
                                         size_t count)
{
    const char *command = clojure_command();
    const char *argv[6];
    StringBuilder deps = {0};
    char *aliases_arg = nullptr;
    char *output_path = nullptr;
    int rc;

    if ((set == nullptr) || (specs == nullptr) || (count == 0U) ||
        (first > specs->count) || (count > specs->count - first) ||
        (command == nullptr) || !sb_append(&deps, "{:deps {")) {
        sb_free(&deps);
        return -1;
    }
    for (size_t i = 0U; i < count; i++) {
        if (!sb_appendf(&deps, "%s%s", i == 0U ? "" : " ", specs->paths[first + i])) {
            sb_free(&deps);
            return -1;
        }
    }
    if (!sb_append(&deps, "}}")) {
        sb_free(&deps);
        return -1;
    }
    if (tools_deps_aliases_or_empty(set->tools_deps_aliases)[0] != '\0') {
        aliases_arg = alloc_printf("-M%s", set->tools_deps_aliases);
        if (aliases_arg == nullptr) {
            sb_free(&deps);
            return -1;
        }
    }
    argv[0] = command;
    argv[1] = "-Sdeps";
    argv[2] = deps.data;
    argv[3] = "-Spath";
    argv[4] = aliases_arg;
    argv[5] = nullptr;
    output_path = dependency_source_classpath_output(set->repo_path);
    rc = output_path == nullptr
             ? -1
             : run_process_with_timeout_output(
                   set->repo_path, argv, maven_timeout_ms(), output_path);
    sb_free(&deps);
    return rc;
}

/* Fetch unavailable source classifiers in batches. A source-less artifact can
 * make one Clojure CLI batch fail after other sources in that batch have still
 * been downloaded. Keep those successful downloads and fall back only the
 * remaining artifacts to their main JARs; source availability must not turn a
 * workspace index into an outage or trigger hundreds of serial subprocesses. */
static int tools_deps_fetch_missing_sources(MavenDependencySet *set)
{
    CodeLensPathList specs = {0};

    if ((set == nullptr) || (set->project_kind != DEPENDENCY_PROJECT_TOOLS_DEPS)) {
        return 0;
    }
    for (size_t i = 0U; i < set->classpath_jars.count; i++) {
        const char *jar = set->classpath_jars.paths[i];
        char *source = dependency_sources_sibling(jar);
        char *spec;

        if ((source != nullptr) && code_lens_path_exists(source)) {
            continue;
        }
        if (zip_has_source_entries(jar)) {
            continue;
        }
        spec = tools_deps_source_spec(jar);
        if ((spec != nullptr) && !path_list_contains(&specs, spec) &&
            (path_list_append(&specs, spec) != 0)) {
            return -1;
        }
    }
    for (size_t first = 0U; first < specs.count; first += TOOLS_DEPS_SOURCE_BATCH_SIZE) {
        size_t count = specs.count - first;

        if (count > TOOLS_DEPS_SOURCE_BATCH_SIZE) {
            count = TOOLS_DEPS_SOURCE_BATCH_SIZE;
        }
        if (tools_deps_fetch_source_batch(set, &specs, first, count) != 0) {
            (void)fprintf(stderr,
                          "code-lens: warning: one or more tools.deps source classifiers "
                          "were unavailable; retaining main-JAR fallbacks\n");
        }
    }
    return 0;
}

static int dependency_classpath_materialize_jars(MavenDependencySet *set)
{
    if (set == nullptr) {
        return -1;
    }
    for (size_t i = 0U; i < set->classpath_jars.count; i++) {
        const char *coordinate_jar = set->classpath_jars.paths[i];
        char *repository_root = dependency_repository_root(coordinate_jar);
        char *sources_candidate;
        const char *source_jar;

        if (repository_root == nullptr) {
            set->skipped_artifacts++;
            continue;
        }
        sources_candidate = dependency_sources_sibling(coordinate_jar);
        source_jar = (sources_candidate != nullptr) && code_lens_path_exists(sources_candidate)
                         ? sources_candidate
                         : coordinate_jar;
        if (maven_repository_jar_add(set,
                                     coordinate_jar,
                                     source_jar,
                                     repository_root,
                                     false,
                                     "classpath") != 0) {
            return -1;
        }
    }
    return 0;
}

static int dependency_resolution_finish(MavenDependencySet *set)
{
    if (set->artifact_count > 1U) {
        qsort(set->artifacts,
              set->artifact_count,
              sizeof(*set->artifacts),
              maven_artifact_compare);
    }
    set->status = copy_bytes(set->artifact_count == 0U ? "empty" : "resolved",
                             set->artifact_count == 0U ? strlen("empty") : strlen("resolved"));
    set->message = set->skipped_artifacts == 0U
                       ? alloc_printf("resolved %zu source artifacts", set->artifact_count)
                       : alloc_printf("resolved %zu source artifacts; %zu skipped",
                                      set->artifact_count,
                                      set->skipped_artifacts);
    return (set->status == nullptr) || (set->message == nullptr) ? -1 : 0;
}

static int maven_resolve_sources(MavenDependencySet *set)
{
    char *output = maven_resolution_output(set->repo_path);
    char *output_arg;
    const char *command = maven_command(set->repo_path);
    const char *argv[12];
    MavenJarCollectContext collect;
    char resolved_output[PATH_MAX];
    int process_rc;

    if ((output == nullptr) || (command == nullptr) ||
        (code_lens_remove_tree(output) != 0) || (code_lens_mkdir_p(output) != 0) ||
        (realpath(output, resolved_output) == nullptr)) {
        return -1;
    }
    output = copy_bytes(resolved_output, strlen(resolved_output));
    output_arg = output == nullptr ? nullptr : alloc_printf("-DoutputDirectory=%s", output);
    if (output_arg == nullptr) {
        return -1;
    }
    argv[0] = command;
    argv[1] = "-B";
    argv[2] = "-ntp";
    argv[3] = "org.apache.maven.plugins:maven-dependency-plugin:"
              MAVEN_DEPENDENCY_PLUGIN_VERSION ":copy-dependencies";
    argv[4] = "-Dclassifier=sources";
    argv[5] = "-DincludeScope=test";
    argv[6] = "-DexcludeReactor=true";
    argv[7] = "-Dmdep.failOnMissingClassifierArtifact=false";
    argv[8] = "-Dmdep.useRepositoryLayout=true";
    argv[9] = output_arg;
    argv[10] = nullptr;
    process_rc = run_process_with_timeout(set->repo_path, argv, maven_timeout_ms());
    if (process_rc != 0) {
        set->message = alloc_printf("Maven source resolution failed with exit code %d", process_rc);
        return -1;
    }

    collect.set = set;
    collect.output_root = output;
    if (code_lens_walk_all_files(output,
                             has_source_jar_suffix,
                             maven_source_jar_callback,
                             &collect) != 0) {
        set->message = alloc_printf("Maven resolved sources, but the artifact cache could not be read");
        return -1;
    }
    return dependency_resolution_finish(set);
}

static char *dependency_classpath_output(const char *repo_path)
{
    char *home = code_lens_default_home();
    char *component = repo_dir_component(repo_path);
    char *directory = (home == nullptr) || (component == nullptr)
                          ? nullptr
                          : alloc_printf("%s/dependencies/projects/%s/classpath", home, component);

    if ((directory == nullptr) || (code_lens_mkdir_p(directory) != 0)) {
        return nullptr;
    }
    return code_lens_join_path(directory, "classpath.txt");
}

static int maven_resolve_classpath(MavenDependencySet *set)
{
    const char *command;
    const char *argv[4];
    char *aliases_arg = nullptr;
    char *output_path = dependency_classpath_output(set->repo_path);
    CodeLensMappedFile output = {0};
    DependencyClasspathCollect collect = {.set = set};
    int process_rc;
#ifdef _WIN32
    const char separator = ';';
#else
    const char separator = ':';
#endif

    if (set->project_kind == DEPENDENCY_PROJECT_LEININGEN) {
        command = lein_command();
        argv[0] = command;
        argv[1] = "classpath";
        argv[2] = nullptr;
        argv[3] = nullptr;
    } else {
        command = clojure_command();
        if ((set->project_kind == DEPENDENCY_PROJECT_TOOLS_DEPS) &&
            (tools_deps_aliases_or_empty(set->tools_deps_aliases)[0] != '\0')) {
            aliases_arg = alloc_printf("-M%s", set->tools_deps_aliases);
        }
        argv[0] = command;
        argv[1] = "-Spath";
        argv[2] = aliases_arg;
        argv[3] = nullptr;
    }
    if ((output_path == nullptr) || (command == nullptr) ||
        ((set->project_kind == DEPENDENCY_PROJECT_TOOLS_DEPS) &&
         (tools_deps_aliases_or_empty(set->tools_deps_aliases)[0] != '\0') &&
         (aliases_arg == nullptr))) {
        return -1;
    }
    process_rc = run_process_with_timeout_output(
        set->repo_path, argv, maven_timeout_ms(), output_path);
    if (process_rc != 0) {
        set->message = alloc_printf("%s classpath resolution failed with exit code %d",
                                    dependency_project_name(set->project_kind),
                                    process_rc);
        return -1;
    }
    if ((code_lens_map_file(output_path, &output) != 0) ||
        (dependency_classpath_split(output.data,
                                    output.len,
                                    separator,
                                    dependency_classpath_jar_callback,
                                    &collect) != 0)) {
        code_lens_mapped_file_free(&output);
        set->message = alloc_printf("%s resolved a classpath that could not be read",
                                    dependency_project_name(set->project_kind));
        return -1;
    }
    code_lens_mapped_file_free(&output);
    if ((set->project_kind == DEPENDENCY_PROJECT_TOOLS_DEPS) &&
        (tools_deps_fetch_missing_sources(set) != 0)) {
        set->message = alloc_printf("tools.deps source-classifier resolution could not be prepared");
        return -1;
    }
    if (dependency_classpath_materialize_jars(set) != 0) {
        set->message = alloc_printf("%s resolved dependency artifacts that could not be materialized",
                                    dependency_project_name(set->project_kind));
        return -1;
    }
    return dependency_resolution_finish(set);
}

static bool maven_inputs_match_db(CodeLensDb *db, MavenDependencySet *set)
{
    sqlite3_stmt *count_stmt = nullptr;
    sqlite3_stmt *input_stmt = nullptr;
    bool match = false;

    if ((db_prepare(db,
                    "SELECT COUNT(*) FROM MavenInput WHERE repo = ?1",
                    &count_stmt) != 0) ||
        (bind_text(count_stmt, 1, set->repo_path) != 0) ||
        (sqlite3_step(count_stmt) != SQLITE_ROW) ||
        ((size_t)sqlite3_column_int64(count_stmt, 0) != set->input_count) ||
        (db_prepare(db,
                    "SELECT size, mtimeSec, mtimeNsec FROM MavenInput"
                    " WHERE repo = ?1 AND path = ?2",
                    &input_stmt) != 0)) {
        goto done;
    }
    for (size_t i = 0U; i < set->input_count; i++) {
        MavenInputSnapshot *input = &set->inputs[i];

        (void)sqlite3_reset(input_stmt);
        (void)sqlite3_clear_bindings(input_stmt);
        if ((bind_text(input_stmt, 1, set->repo_path) != 0) ||
            (bind_text(input_stmt, 2, input->path) != 0) ||
            (sqlite3_step(input_stmt) != SQLITE_ROW) ||
            (sqlite3_column_int64(input_stmt, 0) != input->size) ||
            (sqlite3_column_int64(input_stmt, 1) != input->mtime_sec) ||
            (sqlite3_column_int64(input_stmt, 2) != input->mtime_nsec)) {
            goto done;
        }
    }
    match = true;

done:
    (void)sqlite3_finalize(count_stmt);
    (void)sqlite3_finalize(input_stmt);
    return match;
}

static int maven_load_existing_artifacts(CodeLensDb *db, MavenDependencySet *set)
{
    sqlite3_stmt *project = nullptr;
    sqlite3_stmt *artifacts = nullptr;
    sqlite3_stmt *file_stat = nullptr;
    int step;
    int rc = -1;

    if ((db_prepare(db,
                    "SELECT rootPom, toolsDepsAliases, status, message FROM MavenProject WHERE repo = ?1",
                    &project) != 0) || (bind_text(project, 1, set->repo_path) != 0) ||
        (sqlite3_step(project) != SQLITE_ROW)) {
        goto done;
    }
    {
        const unsigned char *root_pom = sqlite3_column_text(project, 0);
        const unsigned char *aliases = sqlite3_column_text(project, 1);
        const unsigned char *status = sqlite3_column_text(project, 2);
        const unsigned char *message = sqlite3_column_text(project, 3);

        if ((root_pom == nullptr) || (aliases == nullptr) || (status == nullptr) ||
            (message == nullptr) || (strcmp((const char *)root_pom, set->root_pom) != 0) ||
            (strcmp((const char *)aliases, tools_deps_aliases_or_empty(set->tools_deps_aliases)) != 0)) {
            goto done;
        }
        /* A transient first-index failure must not become a permanent cached
         * result. A disabled result is reusable only while execution remains
         * disabled; enabling dependency resolution later should retry without
         * requiring a build-file edit. A no-project result is reusable only
         * while detection still finds no recognized build file. */
        if ((strcmp((const char *)status, "resolved") != 0) &&
            (strcmp((const char *)status, "empty") != 0) &&
            !((strcmp((const char *)status, "disabled") == 0) && !maven_enabled()) &&
            !((strcmp((const char *)status, "none") == 0) &&
              (set->project_kind == DEPENDENCY_PROJECT_NONE))) {
            goto done;
        }
        set->status = copy_bytes((const char *)status,
                                 (size_t)sqlite3_column_bytes(project, 2));
        set->message = copy_bytes((const char *)message,
                                  (size_t)sqlite3_column_bytes(project, 3));
    }
    if ((set->status == nullptr) || (set->message == nullptr) ||
        (db_prepare(db,
                    "SELECT a.id, a.coordinate, a.groupId, a.artifactId, a.version, a.scope,"
                    " a.direct, a.sourceJar, a.sourceRoot, a.checksum,"
                    " (SELECT COUNT(*) FROM DependencyFile df WHERE df.artifactId = a.id)"
                    " FROM DependencyArtifact a WHERE a.repo = ?1"
                    " ORDER BY a.coordinate, a.checksum",
                    &artifacts) != 0) || (bind_text(artifacts, 1, set->repo_path) != 0) ||
        (db_prepare(db,
                    "SELECT f.size, f.mtimeSec, f.mtimeNsec FROM File f "
                    "JOIN DependencyFile df ON df.repo = f.repo AND df.filePath = f.path "
                    "WHERE df.artifactId = ?1 AND f.path = ?2",
                    &file_stat) != 0)) {
        goto done;
    }
    while ((step = sqlite3_step(artifacts)) == SQLITE_ROW) {
        const char *values[9];

        for (size_t i = 0U; i < 6U; i++) {
            values[i] = (const char *)sqlite3_column_text(artifacts, (int)i);
        }
        values[6] = (const char *)sqlite3_column_text(artifacts, 7);
        values[7] = (const char *)sqlite3_column_text(artifacts, 8);
        values[8] = (const char *)sqlite3_column_text(artifacts, 9);
        for (size_t i = 0U; i < 9U; i++) {
            if (values[i] == nullptr) {
                goto done;
            }
        }
        if (!maven_source_cache_marker_current(values[7], values[8])) {
            goto done;
        }
        if (maven_artifact_push(set,
                                values[1],
                                values[2],
                                values[3],
                                values[4],
                                values[5],
                                sqlite3_column_int(artifacts, 6),
                                values[6],
                                values[7],
                                values[8],
                                values[0]) != 0) {
            goto done;
        }
        if ((set->artifact_count == 0U) ||
            (set->artifacts[set->artifact_count - 1U].files.count !=
             (size_t)sqlite3_column_int64(artifacts, 10))) {
            goto done;
        }
        {
            const MavenSourceArtifact *loaded = &set->artifacts[set->artifact_count - 1U];

            for (size_t f = 0U; f < loaded->files.count; f++) {
                int64_t size = 0;
                int64_t mtime_sec = 0;
                int64_t mtime_nsec = 0;

                (void)sqlite3_reset(file_stat);
                (void)sqlite3_clear_bindings(file_stat);
                if ((stat_regular_file(loaded->files.paths[f],
                                       &size,
                                       &mtime_sec,
                                       &mtime_nsec) != 0) ||
                    (bind_text(file_stat, 1, loaded->id) != 0) ||
                    (bind_text(file_stat, 2, loaded->files.paths[f]) != 0) ||
                    (sqlite3_step(file_stat) != SQLITE_ROW) ||
                    (sqlite3_column_int64(file_stat, 0) != size) ||
                    (sqlite3_column_int64(file_stat, 1) != mtime_sec) ||
                    (sqlite3_column_int64(file_stat, 2) != mtime_nsec)) {
                    goto done;
                }
            }
        }
    }
    if (step != SQLITE_DONE) {
        goto done;
    }
    set->reused = true;
    rc = 0;

done:
    (void)sqlite3_finalize(project);
    (void)sqlite3_finalize(artifacts);
    (void)sqlite3_finalize(file_stat);
    return rc;
}

static int maven_try_reuse(const char *db_path, MavenDependencySet *set)
{
    CodeLensDb db = {0};
    int rc = -1;

    if ((db_path == nullptr) || !code_lens_path_exists(db_path) ||
        (code_lens_db_open_read(&db, db_path) != 0)) {
        return -1;
    }
    if (maven_inputs_match_db(&db, set)) {
        rc = maven_load_existing_artifacts(&db, set);
    }
    code_lens_db_close(&db);
    if (rc != 0) {
        set->artifact_count = 0U;
        set->status = nullptr;
        set->message = nullptr;
    }
    return rc;
}

/* Failure isolation normally preserves a published dependency index when a
 * resolver invocation fails. A failed/disabled first generation, however,
 * deliberately has no dependency rows at all; retaining it buys nothing and
 * prevents a changed workspace from being refreshed. Be conservative on an
 * unreadable/old database: treat any uncertainty as dependency data to keep. */
static bool maven_existing_dependency_data(const char *db_path, const char *repo_path)
{
    static const char sql[] =
        "SELECT EXISTS(SELECT 1 FROM DependencyArtifact WHERE repo = ?1) OR "
        "EXISTS(SELECT 1 FROM DependencyFile WHERE repo = ?1)";
    CodeLensDb db = {0};
    sqlite3_stmt *stmt = nullptr;
    bool has_data = true;

    if ((db_path == nullptr) || (repo_path == nullptr) ||
        (code_lens_db_open_read(&db, db_path) != 0)) {
        return true;
    }
    if ((db_prepare(&db, sql, &stmt) == 0) && (bind_text(stmt, 1, repo_path) == 0) &&
        (sqlite3_step(stmt) == SQLITE_ROW)) {
        has_data = sqlite3_column_int(stmt, 0) != 0;
    }
    (void)sqlite3_finalize(stmt);
    code_lens_db_close(&db);
    return has_data;
}

/* Prepares the exact source artifacts for a full rebuild. A matching build
 * input snapshot reuses prior materialized sources without running Maven,
 * Leiningen, or tools.deps. If a refresh fails while an older index exists,
 * the caller leaves that published index untouched; a first index still
 * succeeds with workspace-only data and records the partial-index warning. */
static size_t maven_dependency_file_count(const MavenDependencySet *set);

static int maven_dependencies_prepare(const char *repo_path,
                                      const char *tools_deps_aliases,
                                      MavenDependencySet *set)
{
    char *db_path;
    bool had_index;

    if (!tools_deps_aliases_valid(tools_deps_aliases)) {
        return -1;
    }
    (void)memset(set, 0, sizeof(*set));
    set->repo_path = repo_path;
    set->tools_deps_aliases = tools_deps_aliases_or_empty(tools_deps_aliases);
    set->active = true;
    if ((dependency_project_detect(repo_path, set) != 0) ||
        (maven_collect_inputs(repo_path, set) != 0)) {
        return -1;
    }
    db_path = repo_db_path(repo_path);
    had_index = (db_path != nullptr) && code_lens_path_exists(db_path);
    if (had_index && (maven_try_reuse(db_path, set) == 0)) {
        return 0;
    }
    if (set->project_kind == DEPENDENCY_PROJECT_NONE) {
        set->status = copy_bytes("none", strlen("none"));
        set->message = copy_bytes(
            "no pom.xml, project.clj, or deps.edn; no supported dependency project was detected",
            strlen("no pom.xml, project.clj, or deps.edn; no supported dependency project was detected"));
        return (set->status == nullptr) || (set->message == nullptr) ? -1 : 0;
    }
    if (!maven_enabled()) {
        set->status = copy_bytes("disabled", strlen("disabled"));
        set->message = alloc_printf(
            "%s dependency source resolution disabled by CODE_LENS_MAVEN=0",
            dependency_project_name(set->project_kind));
        return (set->status == nullptr) || (set->message == nullptr) ? -1 : 0;
    }

    (void)fprintf(stderr,
                  "code-lens: resolving %s dependency sources for %s\n",
                  dependency_project_name(set->project_kind),
                  repo_path);
    if (((set->project_kind == DEPENDENCY_PROJECT_MAVEN)
             ? maven_resolve_sources(set)
             : maven_resolve_classpath(set)) == 0) {
        if (set->artifact_count == 0U) {
            (void)fprintf(stderr,
                          "code-lens: warning: %s resolved no dependency source artifacts; "
                          "continuing with a workspace-only partial index\n",
                          dependency_project_name(set->project_kind));
        } else {
            (void)fprintf(stderr,
                          "code-lens: %s dependency sources: %zu artifacts, %zu source files\n",
                          dependency_project_name(set->project_kind),
                          set->artifact_count,
                          maven_dependency_file_count(set));
        }
        return 0;
    }
    (void)fprintf(stderr,
                  "code-lens: warning: %s\n",
                  set->message == nullptr ? "dependency source resolution failed" : set->message);
    if (had_index && maven_existing_dependency_data(db_path, repo_path)) {
        return -1;
    }
    /* This is either the first index or an existing workspace-only partial
     * index. Publishing another partial generation keeps workspace search
     * current without discarding any previously resolved dependency source. */
    set->artifact_count = 0U;
    set->status = copy_bytes("failed", strlen("failed"));
    if (set->message == nullptr) {
        set->message = copy_bytes("dependency source resolution failed",
                                  strlen("dependency source resolution failed"));
    }
    return (set->status == nullptr) || (set->message == nullptr) ? -1 : 0;
}

static int insert_maven_metadata(CodeLensDb *db,
                                 const char *repo_path,
                                 const MavenDependencySet *set)
{
    sqlite3_stmt *project = nullptr;
    sqlite3_stmt *input = nullptr;
    sqlite3_stmt *artifact = nullptr;
    sqlite3_stmt *file = nullptr;
    char *resolved_at;
    int rc = -1;

    if ((set == nullptr) || !set->active) {
        return 0;
    }
    resolved_at = timestamp_now();
    if ((resolved_at == nullptr) ||
        (db_prepare(db,
                    "INSERT INTO MavenProject (repo, rootPom, toolsDepsAliases, status, resolvedAt, message)"
                    " VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                    &project) != 0) ||
        (db_prepare(db,
                    "INSERT INTO MavenInput (id, repo, path, size, mtimeSec, mtimeNsec)"
                    " VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                    &input) != 0) ||
        (db_prepare(db,
                    "INSERT INTO DependencyArtifact (id, repo, coordinate, groupId, artifactId,"
                    " version, scope, direct, sourceJar, sourceRoot, checksum)"
                    " VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
                    &artifact) != 0) ||
        (db_prepare(db,
                    "INSERT INTO DependencyFile (id, repo, filePath, artifactId, sourcePath,"
                    " modulePath) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                    &file) != 0)) {
        goto done;
    }
    if ((bind_text(project, 1, repo_path) != 0) ||
        (bind_text(project, 2, set->root_pom) != 0) ||
        (bind_text(project, 3, tools_deps_aliases_or_empty(set->tools_deps_aliases)) != 0) ||
        (bind_text(project, 4, set->status == nullptr ? "failed" : set->status) != 0) ||
        (bind_text(project, 5, resolved_at) != 0) ||
        (bind_text(project, 6, set->message == nullptr ? "" : set->message) != 0) ||
        (writer_step(project) != 0)) {
        goto done;
    }
    for (size_t i = 0U; i < set->input_count; i++) {
        const MavenInputSnapshot *value = &set->inputs[i];
        char *id = alloc_printf("%s|maven-input|%s", repo_path, value->path);

        if ((id == nullptr) || (bind_text(input, 1, id) != 0) ||
            (bind_text(input, 2, repo_path) != 0) ||
            (bind_text(input, 3, value->path) != 0) ||
            (sqlite3_bind_int64(input, 4, value->size) != SQLITE_OK) ||
            (sqlite3_bind_int64(input, 5, value->mtime_sec) != SQLITE_OK) ||
            (sqlite3_bind_int64(input, 6, value->mtime_nsec) != SQLITE_OK) ||
            (writer_step(input) != 0)) {
            goto done;
        }
    }
    for (size_t i = 0U; i < set->artifact_count; i++) {
        const MavenSourceArtifact *value = &set->artifacts[i];

        if ((bind_text(artifact, 1, value->id) != 0) ||
            (bind_text(artifact, 2, repo_path) != 0) ||
            (bind_text(artifact, 3, value->coordinate) != 0) ||
            (bind_text(artifact, 4, value->group_id) != 0) ||
            (bind_text(artifact, 5, value->artifact_id) != 0) ||
            (bind_text(artifact, 6, value->version) != 0) ||
            (bind_text(artifact, 7, value->scope) != 0) ||
            (sqlite3_bind_int(artifact, 8, value->direct) != SQLITE_OK) ||
            (bind_text(artifact, 9, value->source_jar) != 0) ||
            (bind_text(artifact, 10, value->source_root) != 0) ||
            (bind_text(artifact, 11, value->checksum) != 0) ||
            (writer_step(artifact) != 0)) {
            goto done;
        }
        for (size_t f = 0U; f < value->files.count; f++) {
            const char *path = value->files.paths[f];
            size_t root_len = strlen(value->source_root);
            const char *relative = path;
            char *id;

            if ((strncmp(path, value->source_root, root_len) == 0) &&
                (path[root_len] == '/')) {
                relative = path + root_len + 1U;
            }
            id = alloc_printf("%s|dependency-file|%s", repo_path, path);
            if ((id == nullptr) || (bind_text(file, 1, id) != 0) ||
                (bind_text(file, 2, repo_path) != 0) ||
                (bind_text(file, 3, path) != 0) ||
                (bind_text(file, 4, value->id) != 0) ||
                (bind_text(file, 5, relative) != 0) ||
                (bind_text(file, 6, repo_path) != 0) || (writer_step(file) != 0)) {
                goto done;
            }
        }
    }
    rc = 0;

done:
    (void)sqlite3_finalize(project);
    (void)sqlite3_finalize(input);
    (void)sqlite3_finalize(artifact);
    (void)sqlite3_finalize(file);
    return rc;
}

static size_t maven_dependency_file_count(const MavenDependencySet *set)
{
    size_t count = 0U;

    if (set != nullptr) {
        for (size_t i = 0U; i < set->artifact_count; i++) {
            count += set->artifacts[i].files.count;
        }
    }
    return count;
}

static int index_maven_dependency_files(IndexContext *index,
                                        const MavenDependencySet *set)
{
    if ((set == nullptr) || (set->artifact_count == 0U)) {
        return 0;
    }
    if (index->parser == nullptr) {
        index->parser = code_lens_source_parser_new();
        if (index->parser == nullptr) {
            return -1;
        }
    }
    for (size_t i = 0U; i < set->artifact_count; i++) {
        const MavenSourceArtifact *artifact = &set->artifacts[i];

        for (size_t f = 0U; f < artifact->files.count; f++) {
            if (index_file_callback(artifact->files.paths[f], index) != 0) {
                return -1;
            }
        }
    }
    return 0;
}


typedef struct {
    int fd;
} RepoWriteLock;

/* Serializes index publication and removal across processes. The lock lives
 * outside the removable repo directory and intentionally persists: unlinking
 * an advisory lock file can let a third process lock a new inode while a
 * waiter still holds the old one. */
static int repo_write_lock_acquire(const char *repo_name, RepoWriteLock *lock)
{
    char *root;
    char *lock_dir;
    char *component;
    char *lock_path;
#ifndef _WIN32
    struct flock request = {
        .l_type = F_WRLCK,
        .l_whence = SEEK_SET,
        .l_start = 0,
        .l_len = 0,
    };
#endif
    int fd;
    int rc;

    if ((repo_name == nullptr) || (lock == nullptr)) {
        return -1;
    }
    lock->fd = -1;
    root = repos_root();
    lock_dir = root == nullptr ? nullptr : code_lens_join_path(root, ".locks");
    component = repo_dir_component(repo_name);
    lock_path = (lock_dir == nullptr) || (component == nullptr)
                    ? nullptr
                    : alloc_printf("%s/%s.lock", lock_dir, component);
    if ((lock_path == nullptr) || (code_lens_mkdir_p(lock_dir) != 0)) {
        (void)fprintf(stderr, "code-lens: failed to prepare repository lock directory\n");
        return -1;
    }

    fd = open(lock_path, O_RDWR | O_CREAT | O_CLOEXEC, 0644);
    if (fd < 0) {
        (void)fprintf(stderr,
                      "code-lens: failed to open repository lock %s: %s\n",
                      lock_path,
                      strerror(errno));
        return -1;
    }
#ifdef _WIN32
    rc = cp_lock_fd(fd);
#else
    do {
        rc = fcntl(fd, F_SETLKW, &request);
    } while ((rc != 0) && (errno == EINTR));
#endif
    if (rc != 0) {
        (void)fprintf(stderr,
                      "code-lens: failed to acquire repository lock %s: %s\n",
                      lock_path,
                      strerror(errno));
        (void)close(fd);
        return -1;
    }
    lock->fd = fd;
    return 0;
}

static void repo_write_lock_release(RepoWriteLock *lock)
{
#ifndef _WIN32
    struct flock request = {
        .l_type = F_UNLCK,
        .l_whence = SEEK_SET,
        .l_start = 0,
        .l_len = 0,
    };
#endif
    int rc;

    if ((lock == nullptr) || (lock->fd < 0)) {
        return;
    }
#ifdef _WIN32
    rc = cp_unlock_fd(lock->fd);
#else
    do {
        rc = fcntl(lock->fd, F_SETLK, &request);
    } while ((rc != 0) && (errno == EINTR));
#endif
    if (rc != 0) {
        (void)fprintf(stderr,
                      "code-lens: failed to release repository lock: %s\n",
                      strerror(errno));
    }
    (void)close(lock->fd);
    lock->fd = -1;
}

#ifdef CODE_LENS_NO_MAIN
int code_lens_test_repo_write_lock_acquire(const char *repo_name)
{
    RepoWriteLock lock = {.fd = -1};
    char *canonical = canonical_repo_path(repo_name);

    if ((canonical == nullptr) || (repo_write_lock_acquire(canonical, &lock) != 0)) {
        return -1;
    }
    return lock.fd;
}

void code_lens_test_repo_write_lock_release(int fd)
{
    RepoWriteLock lock = {.fd = fd};

    repo_write_lock_release(&lock);
}
#endif

/* Maps user input to a repo id: the canonical path itself when its index
 * exists, otherwise the nearest indexed ancestor directory, so any path
 * inside an indexed repository identifies it. Returns the canonical input
 * when nothing matches, so callers can report the path the user meant. */
static char *resolve_repo_id(const char *input)
{
    char *canon = canonical_repo_path(input);
    char *probe;

    if (canon == nullptr) {
        return nullptr;
    }
    probe = alloc_printf("%s", canon);
    if (probe == nullptr) {
        return canon;
    }
    for (;;) {
        char *db_path = repo_db_path(probe);
        char *slash;

        if ((db_path != nullptr) && code_lens_path_exists(db_path)) {
            return probe;
        }
        slash = strrchr(probe, '/');
        if ((slash == nullptr) || (slash == probe)) {
            return canon;
        }
        *slash = '\0';
    }
}

static void cleanup_index_paths(IndexPaths *paths, bool remove_staging)
{
    if (paths == nullptr) {
        return;
    }

    if (remove_staging && (paths->staging_db_path != nullptr)) {
        if ((unlink(paths->staging_db_path) != 0) && (errno != ENOENT)) {
            (void)fprintf(stderr,
                          "code-lens: failed to remove staging index %s\n",
                          paths->staging_db_path);
        }
    }

    (void)memset(paths, 0, sizeof(*paths));
}

static int init_index_paths(const char *repo_name, IndexPaths *paths)
{
    char *staging_template = nullptr;
    int staging_fd;
    int rc = -1;

    if (paths == nullptr) {
        return -1;
    }
    (void)memset(paths, 0, sizeof(*paths));

    paths->repo_dir = repo_dir_path(repo_name);
    if (paths->repo_dir == nullptr) {
        goto done;
    }
    if (code_lens_mkdir_p(paths->repo_dir) != 0) {
        goto done;
    }

    staging_template = alloc_printf("%s/staging-XXXXXX", paths->repo_dir);
    if (staging_template == nullptr) {
        goto done;
    }
    staging_fd = mkstemp(staging_template);
    if (staging_fd < 0) {
        (void)fprintf(stderr, "code-lens: failed to create staging index file\n");
        goto done;
    }
    if (close(staging_fd) != 0) {
        goto done;
    }
    paths->staging_db_path = staging_template;

    paths->final_db_path = code_lens_join_path(paths->repo_dir, "index.sqlite");
    if (paths->final_db_path == nullptr) {
        goto done;
    }

    rc = 0;

done:
    if (rc != 0) {
        cleanup_index_paths(paths, true);
    }
    return rc;
}

static int publish_index(const IndexPaths *paths)
{
    if (rename(paths->staging_db_path, paths->final_db_path) != 0) {
        (void)fprintf(stderr,
                      "code-lens: failed to publish index %s to %s: %s\n",
                      paths->staging_db_path,
                      paths->final_db_path,
                      strerror(errno));
        return -1;
    }
    return 0;
}

/* Removes leftovers from interrupted or pre-SQLite index builds: stale
 * staging files, legacy index.lbug databases, and legacy icebug-* staging
 * directories. Runs after publish, so the published index.sqlite is the only
 * artifact this leaves behind. */
static void cleanup_stale_index_artifacts(const IndexPaths *paths)
{
    DIR *dir;
    struct dirent *entry;

    if ((paths == nullptr) || (paths->repo_dir == nullptr)) {
        return;
    }

    dir = opendir(paths->repo_dir);
    if (dir == nullptr) {
        (void)fprintf(stderr,
                      "code-lens: failed to open repo index directory for cleanup\n");
        return;
    }

    while ((entry = readdir(dir)) != nullptr) {
        char *path;

        if ((strncmp(entry->d_name, "staging-", strlen("staging-")) != 0) &&
            (strncmp(entry->d_name, "icebug-", strlen("icebug-")) != 0) &&
            (strcmp(entry->d_name, "index.lbug") != 0)) {
            continue;
        }

        path = code_lens_join_path(paths->repo_dir, entry->d_name);
        if (path == nullptr) {
            (void)fprintf(stderr, "code-lens: failed to allocate cleanup path\n");
            continue;
        }
        if (code_lens_remove_tree(path) != 0) {
            (void)fprintf(stderr,
                          "code-lens: failed to remove stale index artifact %s\n",
                          path);
        }
    }

    (void)closedir(dir);
}

/* --- Incremental re-indexing ---------------------------------------- */

/* Defined in the search section below; declared here because the
 * incremental path reuses the read-time staleness diff. */
static int check_repo_staleness_diff(CodeLensDb *db,
                                     const char *repo_name,
                                     const char *tools_deps_aliases,
                                     StalenessStatus *out_status,
                                     RepoDiff *diff);

static bool incremental_enabled(void)
{
    const char *value = getenv("CODE_LENS_INCREMENTAL");

    return (value == nullptr) || (value[0] == '\0') || (strcmp(value, "0") != 0);
}

/* CODE_LENS_INCREMENTAL=force skips the small-diff budget so tests can
 * drive the incremental path on tiny fixtures. */
static bool incremental_forced(void)
{
    const char *value = getenv("CODE_LENS_INCREMENTAL");

    return (value != nullptr) && (strcmp(value, "force") == 0);
}

/* Copies src over dst (dst is replaced). On APFS this is an instant
 * copy-on-write clone; elsewhere it streams the bytes. */
static int copy_file_bytes(const char *src, const char *dst)
{
#if defined(__APPLE__) && defined(__MACH__)
    if (copyfile(src, dst, nullptr, COPYFILE_CLONE | COPYFILE_UNLINK | COPYFILE_DATA) == 0) {
        return 0;
    }
    /* Fall through to the portable copy on any cloning failure. */
#endif
    {
        int in = open(src, O_RDONLY | O_CLOEXEC);
        int out;
        char buffer[1U << 16U];
        ssize_t got;

        if (in < 0) {
            return -1;
        }
        out = open(dst, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
        if (out < 0) {
            (void)close(in);
            return -1;
        }
        while ((got = read(in, buffer, sizeof(buffer))) > 0) {
            ssize_t written = 0;

            while (written < got) {
                ssize_t n = write(out, buffer + written, (size_t)(got - written));

                if (n <= 0) {
                    (void)close(in);
                    (void)close(out);
                    return -1;
                }
                written += n;
            }
        }
        (void)close(in);
        if ((got < 0) || (close(out) != 0)) {
            return -1;
        }
        return 0;
    }
}

static int64_t db_query_int64(CodeLensDb *db, const char *sql, int64_t fallback)
{
    sqlite3_stmt *stmt = nullptr;
    int64_t value = fallback;

    if (db_prepare(db, sql, &stmt) != 0) {
        return fallback;
    }
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        value = sqlite3_column_int64(stmt, 0);
    }
    (void)sqlite3_finalize(stmt);
    return value;
}

/* Per-table delete tallies reported by the incremental cleanup so the
 * caller can update totals without COUNT(*) scans. */
typedef struct {
    int64_t files;
    int64_t symbols;
    int64_t references;
    int64_t keywords;
    int64_t aliases;
} IncrementalDeleted;


/* Range-delete rewrite (format 12): each File row records its contiguous
 * RefData/KeywordData id ranges and Symbol rowid range, so the big tables
 * are cleaned with primary-key range deletes - O(diff), never a full-table
 * scan. The small path-keyed tables (Alias, Referred, File) delete by
 * path. */
enum {
    INC_STMT_RANGES,
    INC_STMT_FTS_DELETE,
    INC_STMT_REF_RANGE,
    INC_STMT_KEYWORD_RANGE,
    INC_STMT_SYMBOL_RANGE,
    INC_STMT_ALIAS,
    INC_STMT_REFERRED,
    INC_STMT_FILE,
    INC_STMT_COUNT
};

static int incremental_delete_files_ranged(CodeLensDb *db,
                                           const char *repo_name,
                                           const CodeLensPathList *changed,
                                           const CodeLensPathList *missing,
                                           IncrementalDeleted *deleted)
{
    static const char *const sql[INC_STMT_COUNT] = {
        [INC_STMT_RANGES] = "SELECT refFirst, refCount, keywordFirst, keywordCount,"
                            " symbolFirst, symbolCount FROM File WHERE repo = ?1 AND path = ?2",
        /* External-content FTS needs the old column values fed to 'delete'
         * commands before the Symbol rows disappear. */
        [INC_STMT_FTS_DELETE] =
            "INSERT INTO SymbolFts(SymbolFts, rowid, name, namespace, filePath, doc, content)"
            " SELECT 'delete', rowid, name, namespace, filePath, doc, content FROM Symbol"
            " WHERE rowid BETWEEN ?1 AND ?2",
        [INC_STMT_REF_RANGE] = "DELETE FROM RefData WHERE id BETWEEN ?1 AND ?2",
        [INC_STMT_KEYWORD_RANGE] = "DELETE FROM KeywordData WHERE id BETWEEN ?1 AND ?2",
        [INC_STMT_SYMBOL_RANGE] = "DELETE FROM Symbol WHERE rowid BETWEEN ?1 AND ?2",
        [INC_STMT_ALIAS] = "DELETE FROM Alias WHERE repo = ?1 AND filePath = ?2",
        [INC_STMT_REFERRED] = "DELETE FROM Referred WHERE repo = ?1 AND filePath = ?2",
        [INC_STMT_FILE] = "DELETE FROM File WHERE repo = ?1 AND path = ?2",
    };
    sqlite3_stmt *stmts[INC_STMT_COUNT] = {nullptr};
    int rc = 0;

    for (size_t i = 0U; (rc == 0) && (i < (size_t)INC_STMT_COUNT); i++) {
        rc = db_prepare(db, sql[i], &stmts[i]);
    }

    for (size_t list = 0U; (rc == 0) && (list < 2U); list++) {
        const CodeLensPathList *paths = list == 0U ? changed : missing;

        for (size_t i = 0U; (rc == 0) && (i < paths->count); i++) {
            const char *path = paths->paths[i];
            int64_t ranges[6] = {0};

            (void)sqlite3_reset(stmts[INC_STMT_RANGES]);
            if ((bind_text(stmts[INC_STMT_RANGES], 1, repo_name) != 0) ||
                (bind_text(stmts[INC_STMT_RANGES], 2, path) != 0) ||
                (sqlite3_step(stmts[INC_STMT_RANGES]) != SQLITE_ROW)) {
                rc = -1;
                break;
            }
            for (size_t r = 0U; r < 6U; r++) {
                ranges[r] = sqlite3_column_int64(stmts[INC_STMT_RANGES], (int)r);
            }
            if ((ranges[1] < 0) || (ranges[3] < 0) || (ranges[5] < 0)) {
                rc = -1; /* corrupt row: let the full rebuild recover */
                break;
            }

            if (ranges[5] > 0) {
                int64_t last = (ranges[4] + ranges[5]) - 1;

                (void)sqlite3_reset(stmts[INC_STMT_FTS_DELETE]);
                (void)sqlite3_reset(stmts[INC_STMT_SYMBOL_RANGE]);
                if ((sqlite3_bind_int64(stmts[INC_STMT_FTS_DELETE], 1, ranges[4]) != SQLITE_OK) ||
                    (sqlite3_bind_int64(stmts[INC_STMT_FTS_DELETE], 2, last) != SQLITE_OK) ||
                    (writer_step(stmts[INC_STMT_FTS_DELETE]) != 0) ||
                    (sqlite3_bind_int64(stmts[INC_STMT_SYMBOL_RANGE], 1, ranges[4]) !=
                     SQLITE_OK) ||
                    (sqlite3_bind_int64(stmts[INC_STMT_SYMBOL_RANGE], 2, last) != SQLITE_OK) ||
                    (writer_step(stmts[INC_STMT_SYMBOL_RANGE]) != 0)) {
                    rc = -1;
                    break;
                }
                if (deleted != nullptr) {
                    deleted->symbols += sqlite3_changes64(db->handle);
                }
            }
            if (ranges[1] > 0) {
                (void)sqlite3_reset(stmts[INC_STMT_REF_RANGE]);
                if ((sqlite3_bind_int64(stmts[INC_STMT_REF_RANGE], 1, ranges[0]) != SQLITE_OK) ||
                    (sqlite3_bind_int64(stmts[INC_STMT_REF_RANGE],
                                        2,
                                        (ranges[0] + ranges[1]) - 1) != SQLITE_OK) ||
                    (writer_step(stmts[INC_STMT_REF_RANGE]) != 0)) {
                    rc = -1;
                    break;
                }
                if (deleted != nullptr) {
                    deleted->references += sqlite3_changes64(db->handle);
                }
            }
            if (ranges[3] > 0) {
                (void)sqlite3_reset(stmts[INC_STMT_KEYWORD_RANGE]);
                if ((sqlite3_bind_int64(stmts[INC_STMT_KEYWORD_RANGE], 1, ranges[2]) !=
                     SQLITE_OK) ||
                    (sqlite3_bind_int64(stmts[INC_STMT_KEYWORD_RANGE],
                                        2,
                                        (ranges[2] + ranges[3]) - 1) != SQLITE_OK) ||
                    (writer_step(stmts[INC_STMT_KEYWORD_RANGE]) != 0)) {
                    rc = -1;
                    break;
                }
                if (deleted != nullptr) {
                    deleted->keywords += sqlite3_changes64(db->handle);
                }
            }

            for (size_t s = (size_t)INC_STMT_ALIAS; s <= (size_t)INC_STMT_FILE; s++) {
                (void)sqlite3_reset(stmts[s]);
                if ((bind_text(stmts[s], 1, repo_name) != 0) ||
                    (bind_text(stmts[s], 2, path) != 0) || (writer_step(stmts[s]) != 0)) {
                    rc = -1;
                    break;
                }
                if (deleted != nullptr) {
                    if (s == (size_t)INC_STMT_ALIAS) {
                        deleted->aliases += sqlite3_changes64(db->handle);
                    } else if (s == (size_t)INC_STMT_FILE) {
                        deleted->files += sqlite3_changes64(db->handle);
                    }
                }
            }
        }
    }

    for (size_t i = 0U; i < (size_t)INC_STMT_COUNT; i++) {
        if (stmts[i] != nullptr) {
            (void)sqlite3_finalize(stmts[i]);
        }
    }
    return rc;
}

/* Incremental refresh: when a current-format index exists and only a small
 * fraction of files changed, clone the published database, delete the rows
 * of changed/deleted files, parse and insert the changed/added files with
 * the classic statements (terms interned against the preloaded dictionary,
 * fresh RefData/KeywordData ids continuing after the existing maximum),
 * rebuild the Symbol FTS table in place, and publish by rename. Readers
 * treat rowids as opaque and order results themselves, so the refreshed
 * database is observably equivalent to a full rebuild. Returns 1 when the
 * refresh (or an up-to-date no-op) handled the request, 0 when the caller
 * should run a full rebuild. */
static int index_repository_incremental(const char *repo_path,
                                        GitBlobSource *git_source,
                                        const char *tools_deps_aliases,
                                        CodeLensIndexStats *out_stats)
{
    StalenessStatus status = {0};
    RepoDiff diff = {0};
    IndexPaths paths = {0};
    CodeLensDb db;
    IndexContext ctx;
    IndexProfile profile;
    GitBlobReader reader;
    CodeLensPathList reparse = {0};
    size_t total_changed;
    int64_t fts_catchup_from = 0;
    IncrementalDeleted dropped = {0};
    int64_t old_totals[5] = {0};
    size_t seed_references = 0U;
    size_t seed_keywords = 0U;
    double start;
    bool db_open = false;
    bool in_txn = false;
    int rc = -1;

    (void)memset(&db, 0, sizeof(db));
    (void)memset(&ctx, 0, sizeof(ctx));
    profile_init(&profile);
    git_blob_reader_init(&reader, git_source);

    /* Diff the published index against the working tree via a read-only
     * connection; any surprise (missing, wrong format, unreadable) falls
     * back to the full rebuild. */
    start = profile_start(&profile);
    {
        char *final_path;
        char *repo_dir = repo_dir_path(repo_path);
        int64_t version;

        if (repo_dir == nullptr) {
            goto fallback;
        }
        final_path = code_lens_join_path(repo_dir, "index.sqlite");
        if ((final_path == nullptr) || !code_lens_path_exists(final_path)) {
            goto fallback;
        }
        if (code_lens_db_open_read(&db, final_path) != 0) {
            goto fallback;
        }
        db_open = true;
        version = db_query_int64(&db, "PRAGMA user_version", -1);
        if (version != (int64_t)CODE_LENS_INDEX_FORMAT_VERSION) {
            goto fallback;
        }
        if (check_repo_staleness_diff(&db, repo_path, tools_deps_aliases, &status, &diff) != 0) {
            goto fallback;
        }
        code_lens_db_close(&db);
        db_open = false;
    }
    profile_add(&profile, &profile.collect_seconds, start);

    if (status.maven_changed || status.dependency_sources_changed ||
        status.dependency_retry_due) {
        /* A retry-due partial generation is readable by MCP, but an explicit
         * index must still fall through to dependency resolution. */
        goto fallback;
    }

    if (!status.stale) {
        /* Already current: report the stored totals without rebuilding. */
        if (out_stats != nullptr) {
            (void)memset(out_stats, 0, sizeof(*out_stats));
        }
        rc = 1;
        goto report_totals;
    }

    total_changed = diff.changed.count + diff.missing.count + diff.added.count;
    if (!incremental_forced() &&
        ((status.checked_files == 0U) || (total_changed * 5U > status.checked_files))) {
        goto fallback; /* over the 20% budget: a full rebuild is cheap enough */
    }

    start = profile_start(&profile);
    if (init_index_paths(repo_path, &paths) != 0) {
        goto fallback;
    }
    if (copy_file_bytes(paths.final_db_path, paths.staging_db_path) != 0) {
        goto fallback;
    }
    if (code_lens_db_open_build(&db, paths.staging_db_path) != 0) {
        goto fallback;
    }
    db_open = true;

    ctx.repo_path = repo_path;
    ctx.profile = &profile;
    ctx.git_source = git_source;
    ctx.git_reader = &reader;
    if (prepare_index_writers(&db, &ctx.writers) != 0) {
        goto fallback;
    }
    if ((term_interner_init(&db, &ctx.interner, true) != 0) ||
        (term_interner_preload(&db, &ctx.interner) != 0)) {
        goto fallback;
    }
    if (code_lens_db_exec(&db, "BEGIN") != 0) {
        goto fallback;
    }
    in_txn = true;
    profile_add(&profile, &profile.db_open_seconds, start);

    start = profile_start(&profile);
    if (incremental_delete_files_ranged(&db, repo_path, &diff.changed, &diff.missing, &dropped) !=
        0) {
        goto fallback;
    }

    /* Stored totals feed the delta arithmetic that replaces COUNT(*) scans
     * over the big tables at the end. */
    {
        sqlite3_stmt *stmt = nullptr;

        if (db_prepare(&db,
                       "SELECT fileCount, symbolCount, referenceCount, keywordCount,"
                       " aliasCount FROM Repo WHERE path = ?1",
                       &stmt) != 0) {
            goto fallback;
        }
        if ((bind_text(stmt, 1, repo_path) != 0) || (sqlite3_step(stmt) != SQLITE_ROW)) {
            (void)sqlite3_finalize(stmt);
            goto fallback;
        }
        for (size_t i = 0U; i < 5U; i++) {
            old_totals[i] = sqlite3_column_int64(stmt, (int)i);
        }
        (void)sqlite3_finalize(stmt);
    }

    /* New RefData/KeywordData ids continue after the surviving maximum, and
     * the surviving Symbol rowid maximum marks where the FTS catch-up insert
     * starts (every re-parsed row lands past it). */
    ctx.stats.reference_count =
        (size_t)(db_query_int64(&db, "SELECT COALESCE(MAX(id), -1) FROM RefData", -1) + 1);
    ctx.stats.keyword_count =
        (size_t)(db_query_int64(&db, "SELECT COALESCE(MAX(id), -1) FROM KeywordData", -1) + 1);
    seed_references = ctx.stats.reference_count;
    seed_keywords = ctx.stats.keyword_count;
    fts_catchup_from = db_query_int64(&db, "SELECT COALESCE(MAX(rowid), 0) FROM Symbol", -1);
    if (fts_catchup_from < 0) {
        goto fallback;
    }
    /* Symbol rowids for re-parsed files continue past the surviving
     * maximum; File.symbolFirst derives from this base. */
    ctx.symbol_rowid_base = fts_catchup_from;
    profile_add(&profile, &profile.secondary_index_seconds, start);

    start = profile_start(&profile);
    ctx.parser = code_lens_source_parser_new();
    if (ctx.parser == nullptr) {
        goto fallback;
    }
    for (size_t list = 0U; list < 2U; list++) {
        const CodeLensPathList *paths_list = list == 0U ? &diff.changed : &diff.added;

        for (size_t i = 0U; i < paths_list->count; i++) {
            if (path_list_append(&reparse, paths_list->paths[i]) != 0) {
                goto fallback;
            }
        }
    }
    for (size_t i = 0U; i < reparse.count; i++) {
        if (index_file_callback(reparse.paths[i], &ctx) != 0) {
            goto fallback;
        }
    }

    /* Repo row: fresh timestamp plus totals computed as stored - deleted +
     * inserted, avoiding COUNT(*) scans over the big tables. */
    start = profile_start(&profile);
    {
        sqlite3_stmt *stmt = nullptr;
        char *indexed_at = timestamp_now();
        int64_t files = old_totals[0] - dropped.files + (int64_t)ctx.stats.file_count;
        int64_t symbols = old_totals[1] - dropped.symbols + (int64_t)ctx.stats.symbol_count;
        int64_t references = old_totals[2] - dropped.references +
                             (int64_t)(ctx.stats.reference_count - seed_references);
        int64_t keywords =
            old_totals[3] - dropped.keywords + (int64_t)(ctx.stats.keyword_count - seed_keywords);
        int64_t aliases = old_totals[4] - dropped.aliases + (int64_t)ctx.stats.alias_count;

        if ((indexed_at == nullptr) || (files < 0) || (symbols < 0) || (references < 0) ||
            (keywords < 0) || (aliases < 0)) {
            goto fallback;
        }
        if (db_prepare(&db,
                       "UPDATE Repo SET indexedAt = ?2, fileCount = ?3, symbolCount = ?4,"
                       " referenceCount = ?5, keywordCount = ?6, aliasCount = ?7"
                       " WHERE path = ?1",
                       &stmt) != 0) {
            goto fallback;
        }
        if ((bind_text(stmt, 1, repo_path) != 0) || (bind_text(stmt, 2, indexed_at) != 0) ||
            (sqlite3_bind_int64(stmt, 3, files) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, 4, symbols) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, 5, references) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, 6, keywords) != SQLITE_OK) ||
            (sqlite3_bind_int64(stmt, 7, aliases) != SQLITE_OK) || (writer_step(stmt) != 0)) {
            (void)sqlite3_finalize(stmt);
            goto fallback;
        }
        (void)sqlite3_finalize(stmt);
        if (out_stats != nullptr) {
            (void)memset(out_stats, 0, sizeof(*out_stats));
            out_stats->file_count = (size_t)files;
            out_stats->symbol_count = (size_t)symbols;
            out_stats->reference_count = (size_t)references;
            out_stats->keyword_count = (size_t)keywords;
            out_stats->alias_count = (size_t)aliases;
        }
    }
    profile_add(&profile, &profile.repo_write_seconds, start);

    /* FTS catch-up: doomed rows were fed as 'delete' commands before the
     * Symbol deletes; every re-parsed row has a rowid past the surviving
     * maximum, so one targeted insert replaces the full-table rebuild. */
    start = profile_start(&profile);
    {
        sqlite3_stmt *stmt = nullptr;

        if (db_prepare(&db,
                       "INSERT INTO SymbolFts(rowid, name, namespace, filePath, doc, content)"
                       " SELECT rowid, name, namespace, filePath, doc, content FROM Symbol"
                       " WHERE rowid > ?1",
                       &stmt) != 0) {
            goto fallback;
        }
        if ((sqlite3_bind_int64(stmt, 1, fts_catchup_from) != SQLITE_OK) ||
            (writer_step(stmt) != 0)) {
            (void)sqlite3_finalize(stmt);
            goto fallback;
        }
        (void)sqlite3_finalize(stmt);
    }
    profile_add(&profile, &profile.fts_seconds, start);
    start = profile_start(&profile);
    if (code_lens_db_exec(&db, "COMMIT") != 0) {
        in_txn = false;
        goto fallback;
    }
    in_txn = false;
    profile_add(&profile, &profile.commit_seconds, start);

    start = profile_start(&profile);
    finalize_index_writers(&ctx.writers);
    term_interner_finalize_stmt(&ctx.interner);
    code_lens_db_close(&db);
    db_open = false;
    if (publish_index(&paths) != 0) {
        goto fallback;
    }
    cleanup_stale_index_artifacts(&paths);
    profile_add(&profile, &profile.publish_seconds, start);
    (void)fprintf(stderr,
                  "code-lens: incremental refresh: %zu updated, %zu added, %zu removed files\n",
                  diff.changed.count,
                  diff.added.count,
                  diff.missing.count);
    profile_report(&profile, out_stats);
    term_interner_destroy(&ctx.interner);
    if (ctx.parser != nullptr) {
        code_lens_source_parser_delete(ctx.parser);
    }
    git_blob_reader_destroy(&reader);
    cleanup_index_paths(&paths, false);
    return 1;

report_totals:
    /* Fill out_stats with the published totals so the caller's summary line
     * stays truthful for the up-to-date case. */
    {
        char *repo_dir = repo_dir_path(repo_path);
        char *final_path = repo_dir == nullptr ? nullptr
                                               : code_lens_join_path(repo_dir, "index.sqlite");

        if ((out_stats != nullptr) && (final_path != nullptr) &&
            (code_lens_db_open_read(&db, final_path) == 0)) {
            sqlite3_stmt *stmt = nullptr;

            if (db_prepare(&db,
                           "SELECT fileCount, symbolCount, referenceCount, keywordCount,"
                           " aliasCount FROM Repo WHERE path = ?1",
                           &stmt) == 0) {
                if ((bind_text(stmt, 1, repo_path) == 0) && (sqlite3_step(stmt) == SQLITE_ROW)) {
                    out_stats->file_count = (size_t)sqlite3_column_int64(stmt, 0);
                    out_stats->symbol_count = (size_t)sqlite3_column_int64(stmt, 1);
                    out_stats->reference_count = (size_t)sqlite3_column_int64(stmt, 2);
                    out_stats->keyword_count = (size_t)sqlite3_column_int64(stmt, 3);
                    out_stats->alias_count = (size_t)sqlite3_column_int64(stmt, 4);
                }
                (void)sqlite3_finalize(stmt);
            }
            code_lens_db_close(&db);
        }
        (void)fprintf(stderr, "code-lens: index already current; no rebuild needed\n");
    }
    git_blob_reader_destroy(&reader);
    return rc;

fallback:
    if (in_txn) {
        (void)code_lens_db_exec(&db, "ROLLBACK");
    }
    finalize_index_writers(&ctx.writers);
    term_interner_destroy(&ctx.interner);
    if (db_open) {
        code_lens_db_close(&db);
    }
    if (ctx.parser != nullptr) {
        code_lens_source_parser_delete(ctx.parser);
    }
    git_blob_reader_destroy(&reader);
    cleanup_index_paths(&paths, true);
    return 0;
}

/* One full indexing attempt with either the raw table emitter or the
 * classic SQL emitter. A failed attempt removes its staging file, so the
 * public wrapper can simply retry with the classic emitter. */
static int index_repository_attempt(const char *repo_path,
                                    GitBlobSource *git_source,
                                    const MavenDependencySet *maven_dependencies,
                                    CodeLensIndexStats *out_stats,
                                    bool raw_emitter)
{
    CodeLensDb index_db;
    IndexContext ctx;
    IndexProfile profile;
    IndexPaths paths;
    FtsSidecar fts_sidecar;
    GitBlobReader seq_reader;
    RawIndexPrep raw_prep = {0};
    char *fts_sidecar_path = nullptr;
    bool fts_sidecar_active = false;
    double start;
    size_t thread_count;
    bool index_db_open = false;
    int rc = -1;

    profile_init(&profile);
    (void)memset(&ctx, 0, sizeof(ctx));
    (void)memset(&index_db, 0, sizeof(index_db));
    git_blob_reader_init(&seq_reader, git_source);
    ctx.git_source = git_source;
    ctx.git_reader = &seq_reader;
    thread_count = configured_index_thread_count();

    if (init_index_paths(repo_path, &paths) != 0) {
        return -1;
    }

    ctx.repo_path = repo_path;
    ctx.profile = &profile;
    ctx.raw_emit.active = raw_emitter;
    ctx.raw_mirror.active = raw_emitter || raw_indexes_enabled();

    /* Schema plus every row for the repo load inside one transaction; the
     * atomic publish rename provides all the durability that matters. */
    start = profile_start(&profile);
    rc = code_lens_db_open_build(&index_db, paths.staging_db_path);
    if (rc == 0) {
        index_db_open = true;
        rc = create_index_schema(&index_db);
    }
    if ((rc == 0) && raw_emitter) {
        /* Empty tables make CREATE INDEX instant; creating the whole schema
         * up front lets SQLite allocate every root page and write every
         * sqlite_master row itself, so the graft never edits the schema. */
        rc = create_secondary_indexes(&index_db);
        if (rc == 0) {
            rc = raw_capture_schema_roots(&index_db, &ctx.raw_emit);
        }
    }
    if (rc == 0) {
        rc = prepare_index_writers(&index_db, &ctx.writers);
    }
    if (rc == 0) {
        rc = term_interner_init(&index_db, &ctx.interner, !raw_emitter);
    }
    /* FTS sidecar setup happens before BEGIN because ATTACH is not allowed
     * inside a transaction. In classic mode the sidecar builds in a file
     * attached to the main connection (creating it) while still untouched
     * by the side thread, so the in-transaction shadow-table copy can run.
     * The raw path builds the sidecar in memory and adopts it post-COMMIT
     * over the sidecar's own connection; no sidecar file exists at all.
     * Setup failure just means the tail falls back to the in-place
     * rebuild. */
    if ((rc == 0) && fts_sidecar_enabled()) {
        bool ready = raw_emitter;

        if (!raw_emitter) {
            fts_sidecar_path = alloc_printf("%s.fts", paths.staging_db_path);
            if (fts_sidecar_path != nullptr) {
                char *attach_sql = sqlite3_mprintf("ATTACH %Q AS fts_side", fts_sidecar_path);

                ready = (attach_sql != nullptr) &&
                        (code_lens_db_exec(&index_db, attach_sql) == 0);
                sqlite3_free(attach_sql);
            }
        }
        if (ready && (fts_sidecar_start(&fts_sidecar, fts_sidecar_path, raw_emitter) == 0)) {
            fts_sidecar_active = true;
            ctx.fts_sidecar = &fts_sidecar;
        }
    }
    /* The raw emitter leaves Symbol invisible to SQL until the post-COMMIT
     * graft, so the in-place FTS rebuild fallback would index nothing. The
     * sidecar is mandatory in raw mode: without it this attempt fails and
     * the caller retries with the classic emitter. */
    if ((rc == 0) && raw_emitter && !fts_sidecar_active) {
        rc = -1;
    }
    if (rc == 0) {
        rc = code_lens_db_exec(&index_db, "BEGIN");
    }
    profile_add(&profile, &profile.db_open_seconds, start);
    if (rc != 0) {
        goto cleanup;
    }

    start = profile_start(&profile);
    if (thread_count <= 1U) {
        ctx.parser = code_lens_source_parser_new();
        rc = ctx.parser == nullptr
                 ? -1
                 : code_lens_walk_source_files(repo_path, index_file_callback, &ctx);
    } else {
        rc = index_source_files_parallel(&ctx, repo_path, thread_count);
    }
    if (rc == 0) {
        rc = index_maven_dependency_files(&ctx, maven_dependencies);
    }
    profile_add(&profile, &profile.walk_seconds, start);
    if (rc != 0) {
        goto cleanup;
    }
    if (ctx.raw_emit.active) {
        /* Sort and encode the seven index payload sets on side threads
         * while the FTS adopt, Repo row, COMMIT and close run here. */
        raw_index_prep_start(&ctx, &raw_prep);
    }
    if (fts_sidecar_active) {
        /* All Symbol rows are mirrored; let the side thread commit and close
         * while the repo row and secondary indexes are written. */
        fts_sidecar_seal(&fts_sidecar);
    }

    start = profile_start(&profile);
    rc = insert_repo_row(&ctx);
    if (rc == 0) {
        rc = insert_maven_metadata(&index_db, repo_path, maven_dependencies);
    }
    profile_add(&profile, &profile.repo_write_seconds, start);
    if (rc != 0) {
        goto cleanup;
    }

    /* With the raw writer active the secondary indexes are grafted after
     * COMMIT instead of built inside the load transaction. */
    if (!ctx.raw_mirror.active) {
        start = profile_start(&profile);
        rc = create_secondary_indexes(&index_db);
        profile_add(&profile, &profile.secondary_index_seconds, start);
        if (rc != 0) {
            goto cleanup;
        }
    }

    /* Classic mode adopts (or rebuilds) the FTS content inside the load
     * transaction. The raw path defers all FTS work to after COMMIT: the
     * sidecar's commit tail then overlaps the table graft instead of
     * stalling this thread here. */
    if (!raw_emitter) {
        start = profile_start(&profile);
        rc = -1;
        if (fts_sidecar_active && (fts_sidecar_finish(&fts_sidecar) == 0)) {
            rc = code_lens_db_exec(&index_db, fts_sidecar_copy_sql);
        }
        if (rc != 0) {
            /* Sidecar disabled or failed: classic in-place rebuild. Safe
             * even after a partial shadow-table copy, because a rebuild
             * discards all existing index content first. */
            rc = code_lens_db_exec(&index_db,
                                    "INSERT INTO SymbolFts(SymbolFts) VALUES('rebuild')");
        }
        profile_add(&profile, &profile.fts_seconds, start);
        if (rc != 0) {
            goto cleanup;
        }
    }

    start = profile_start(&profile);
    rc = code_lens_db_exec(&index_db, "COMMIT");
    profile_add(&profile, &profile.commit_seconds, start);
    if (rc != 0) {
        goto cleanup;
    }

    finalize_index_writers(&ctx.writers);
    term_interner_finalize_stmt(&ctx.interner);
    code_lens_db_close(&index_db);
    index_db_open = false;

    if (ctx.raw_emit.active) {
        /* Whole-bulk graft: table b-trees streamed during the walk plus all
         * index b-trees are appended and adopted in one pass. Failure fails
         * the attempt; the wrapper retries with the classic emitter. */
        start = profile_start(&profile);
        rc = raw_tables_graft(paths.staging_db_path, &ctx, &raw_prep);
        profile_add(&profile, &profile.secondary_index_seconds, start);
        if (rc != 0) {
            (void)fprintf(stderr, "code-lens: raw table write failed\n");
            goto cleanup;
        }

        /* FTS adopt, deferred past COMMIT and the graft so the sidecar's
         * own tail (memory commit + tree encode) ran concurrently with
         * both. Preferred path grafts the two big shadow trees as raw
         * pages and copies only the tiny _idx/_config over SQL; any
         * shortfall falls back to the full SQL adopt, and a failed sidecar
         * to an in-place rebuild (Symbol is readable now). */
        start = profile_start(&profile);
        rc = -1;
        if (fts_sidecar_finish(&fts_sidecar) == 0) {
            if (fts_sidecar.trees_ready &&
                (fts_graft_shadow_trees(&fts_sidecar,
                                        paths.staging_db_path,
                                        ctx.raw_emit.schema_roots) == 0)) {
                rc = fts_adopt_tables(&fts_sidecar,
                                      paths.staging_db_path,
                                      fts_adopt_small_tables_sql);
            }
            if (rc != 0) {
                rc = fts_adopt_tables(
                    &fts_sidecar, paths.staging_db_path, fts_adopt_all_tables_sql);
            }
        }
        if (rc != 0) {
            rc = fts_rebuild_after_commit(paths.staging_db_path);
        }
        profile_add(&profile, &profile.fts_seconds, start);
        if (rc != 0) {
            goto cleanup;
        }
    } else if (ctx.raw_mirror.active) {
        start = profile_start(&profile);
        rc = raw_secondary_indexes_graft(paths.staging_db_path, &ctx);
        if (rc != 0) {
            (void)fprintf(stderr,
                          "code-lens: raw index write failed; falling back to CREATE INDEX\n");
            rc = classic_secondary_indexes_after_commit(paths.staging_db_path);
        }
        profile_add(&profile, &profile.secondary_index_seconds, start);
        if (rc != 0) {
            goto cleanup;
        }
    }
    term_interner_destroy(&ctx.interner);

#ifdef _WIN32
    /* Windows does not allow an attached/open database file to be renamed.
     * POSIX keeps these sidecar resources alive until cleanup because rename
     * is permitted there; close them first on Windows. */
    raw_index_prep_destroy(&raw_prep);
    if (fts_sidecar_active) {
        fts_sidecar_destroy(&fts_sidecar);
        fts_sidecar_active = false;
    }
#endif

    start = profile_start(&profile);
    rc = publish_index(&paths);
    if (rc == 0) {
        cleanup_stale_index_artifacts(&paths);
    }
    profile_add(&profile, &profile.publish_seconds, start);

cleanup:
    raw_index_prep_destroy(&raw_prep);
    if (fts_sidecar_active) {
        fts_sidecar_destroy(&fts_sidecar);
    }
    finalize_index_writers(&ctx.writers);
    term_interner_destroy(&ctx.interner);
    raw_mirror_destroy(&ctx.raw_mirror);
    raw_emitter_destroy(&ctx.raw_emit);
    if (index_db_open) {
        code_lens_db_close(&index_db);
    }
    if (fts_sidecar_path != nullptr) {
        if ((unlink(fts_sidecar_path) != 0) && (errno != ENOENT)) {
            (void)fprintf(stderr,
                          "code-lens: failed to remove FTS sidecar %s\n",
                          fts_sidecar_path);
        }
    }
    if (ctx.parser != nullptr) {
        code_lens_source_parser_delete(ctx.parser);
    }
    if ((rc == 0) && (out_stats != nullptr)) {
        *out_stats = ctx.stats;
    }
    git_blob_reader_destroy(&seq_reader);
    profile_report(&profile, &ctx.stats);
    cleanup_index_paths(&paths, rc != 0);
    return rc;
}

int code_lens_index_repository_ex(const char *repo_path,
                                  const CodeLensDependencyOptions *options,
                                  CodeLensIndexStats *out_stats)
{
    GitBlobSource git_source;
    MavenDependencySet maven_dependencies;
    RepoWriteLock write_lock = {.fd = -1};
    double start;
    uint64_t blob_reads_start;
    char *canonical;
    int rc;

    const char *tools_deps_aliases =
        options == nullptr ? nullptr : options->tools_deps_aliases;

    if (!tools_deps_aliases_valid(tools_deps_aliases)) {
        return -1;
    }
    canonical = canonical_repo_path(repo_path);
    if ((canonical == nullptr) || !code_lens_is_directory(canonical)) {
        return -1;
    }
    start = profile_now_seconds();
    if (repo_write_lock_acquire(canonical, &write_lock) != 0) {
        return -1;
    }
    blob_reads_start = code_lens_git_blob_read_count();
    git_blob_source_init(&git_source, canonical);
    (void)memset(&maven_dependencies, 0, sizeof(maven_dependencies));
    rc = -1;
    if (incremental_enabled() &&
        (index_repository_incremental(canonical, &git_source, tools_deps_aliases, out_stats) == 1)) {
        rc = 0;
    }
    if ((rc != 0) &&
        (maven_dependencies_prepare(canonical, tools_deps_aliases, &maven_dependencies) != 0)) {
        goto done;
    }
    if ((rc != 0) && raw_emitter_enabled()) {
        rc = index_repository_attempt(
            canonical, &git_source, &maven_dependencies, out_stats, true);
        if (rc != 0) {
            /* A failed attempt unlinked its staging file, so a clean classic
             * retry is always possible. */
            (void)fprintf(stderr,
                          "code-lens: raw emitter failed; retrying with classic emitter\n");
        }
    }
    if (rc != 0) {
        rc = index_repository_attempt(
            canonical, &git_source, &maven_dependencies, out_stats, false);
    }
done:
    git_blob_source_destroy(&git_source);
    if ((rc == 0) && (out_stats != nullptr)) {
        out_stats->elapsed_seconds = profile_now_seconds() - start;
        out_stats->git_blob_reads = code_lens_git_blob_read_count() - blob_reads_start;
    }
    repo_write_lock_release(&write_lock);
    return rc;
}

int code_lens_index_repository(const char *repo_path, CodeLensIndexStats *out_stats)
{
    return code_lens_index_repository_ex(repo_path, nullptr, out_stats);
}

/* Search */

#define MAX_QUERY_TERMS 16U

static char *copy_token(const char *start, size_t len)
{
    char *token = code_lens_alloc(len + 1U);
    if (token == nullptr) {
        return nullptr;
    }
    for (size_t i = 0U; i < len; i++) {
        token[i] = (char)tolower((unsigned char)start[i]);
    }
    token[len] = '\0';
    return token;
}

static bool token_char(unsigned char ch)
{
    return isalnum(ch) || (ch == '_') || (ch == '-') || (ch == '?') || (ch == '!') ||
           (ch == '*') || (ch == '.') || (ch == '/');
}

static size_t tokenize(const char *query, char *terms[MAX_QUERY_TERMS])
{
    size_t count = 0U;
    size_t i = 0U;

    while ((query[i] != '\0') && (count < MAX_QUERY_TERMS)) {
        size_t start;
        size_t len;

        while ((query[i] != '\0') && !token_char((unsigned char)query[i])) {
            i++;
        }
        start = i;
        while ((query[i] != '\0') && token_char((unsigned char)query[i])) {
            i++;
        }
        len = i - start;
        if (len >= 2U) {
            terms[count] = copy_token(query + start, len);
            if (terms[count] == nullptr) {
                break;
            }
            count++;
        }
    }

    return count;
}

static char *repo_reindex_required_message(const char *repo_name)
{
    return alloc_printf("repo \"%s\" index is missing, unreadable, stale, or has an obsolete "
                        "format; run code-lens index --repo \"%s\" to build a fresh index\n",
                        repo_name,
                        repo_name);
}

/* Like code_lens_db_query_to_string, but hands the SQLite error text of a
 * failed prepare or step back to the caller. The errcode filter keeps the
 * capture to genuine statement failures: successful calls leave SQLITE_OK /
 * SQLITE_ROW / SQLITE_DONE behind, so an allocation failure inside result
 * rendering does not pick up a stale message. Rendering stops at
 * SQL_TOOL_MAX_ROWS and says so in a trailing note. */
#define SQL_TOOL_MAX_ROWS 100U

static char *db_query_to_string_capture(CodeLensDb *db, const char *query, char **out_error)
{
    sqlite3_stmt *stmt = nullptr;
    char *text;
    bool capped = false;
    int errcode;

    *out_error = nullptr;
    if ((db == nullptr) || !db->is_open || (query == nullptr)) {
        return nullptr;
    }

    if (sqlite3_prepare_v2(db->handle, query, -1, &stmt, nullptr) != SQLITE_OK) {
        *out_error = alloc_printf("%s", sqlite3_errmsg(db->handle));
        (void)report_sqlite_error(db->handle, "query failed");
        return nullptr;
    }
    text = render_stmt_result_capped(stmt, SQL_TOOL_MAX_ROWS, nullptr, &capped);
    errcode = sqlite3_errcode(db->handle);
    if ((text == nullptr) && (errcode != SQLITE_OK) && (errcode != SQLITE_ROW) &&
        (errcode != SQLITE_DONE)) {
        *out_error = alloc_printf("%s", sqlite3_errmsg(db->handle));
    }
    (void)sqlite3_finalize(stmt);
    if ((text != nullptr) && capped) {
        text = alloc_printf("%snote: output capped at %u rows\n", text, SQL_TOOL_MAX_ROWS);
    }
    return text;
}

/* The sql tool accepts arbitrary text, so the connection is locked down in
 * depth: read-only open, a statement deadline (progress handler), and this
 * authorizer, which permits reads of the repo database only -- no ATTACH
 * (reading arbitrary files on disk), no PRAGMA, no writes, no schema or
 * connection state changes. */
static int sql_tool_authorizer(void *user_data,
                               int action,
                               const char *detail1,
                               const char *detail2,
                               const char *detail3,
                               const char *detail4)
{
    (void)user_data;
    (void)detail1;
    (void)detail2;
    (void)detail3;
    (void)detail4;
    switch (action) {
    case SQLITE_SELECT:
    case SQLITE_READ:
    case SQLITE_FUNCTION:
    case SQLITE_RECURSIVE:
        return SQLITE_OK;
    default:
        return SQLITE_DENY;
    }
}

/* Runs one query for the sql tool. A failed open keeps the re-index advice
 * (the index really is missing, unreadable, or obsolete), but a statement
 * that fails to prepare or step reports the real SQLite error text instead:
 * advising an agent to re-index over a typo'd table name sends it down a
 * dead end, and stderr is invisible to MCP clients. */
static char *query_with_open_repo_db(CodeLensDb *db, const char *query, bool *succeeded)
{
    char *error = nullptr;
    char *result;

    if (succeeded != nullptr) {
        *succeeded = false;
    }
    if ((db == nullptr) || !db->is_open) {
        return nullptr;
    }
    (void)sqlite3_set_authorizer(db->handle, sql_tool_authorizer, nullptr);
    result = db_query_to_string_capture(db, query, &error);
    (void)sqlite3_set_authorizer(db->handle, nullptr, nullptr);
    if (result != nullptr) {
        if (succeeded != nullptr) {
            *succeeded = true;
        }
        return result;
    }
    return error == nullptr ? nullptr : alloc_printf("SQL error: %s\n", error);
}

/* Returns 1 when the repo is indexed, 0 when it is not, -1 on query failure.
 * Confirms the path recorded inside the repo's own database rather than
 * trusting the directory name, which is sanitized and hashed. */
static int repo_exists(const char *repo_name)
{
    CodeLensDb db;
    sqlite3_stmt *stmt = nullptr;
    char *db_path;
    int rc = -1;

    if (repo_name == nullptr) {
        return -1;
    }
    db_path = repo_db_path(repo_name);
    if (db_path == nullptr) {
        return -1;
    }
    if (!code_lens_path_exists(db_path)) {
        return 0;
    }

    if (code_lens_db_open_read(&db, db_path) != 0) {
        return -1;
    }
    if ((db_prepare(&db, "SELECT COUNT(*) FROM Repo WHERE path = ?1", &stmt) == 0) &&
        (bind_text(stmt, 1, repo_name) == 0)) {
        if (sqlite3_step(stmt) == SQLITE_ROW) {
            rc = sqlite3_column_int(stmt, 0) > 0 ? 1 : 0;
        } else {
            (void)report_sqlite_error(db.handle, "query failed");
        }
    }
    (void)sqlite3_finalize(stmt);
    code_lens_db_close(&db);
    return rc;
}

static int compare_paths(const void *left, const void *right)
{
    return strcmp(*(char *const *)left, *(char *const *)right);
}

/* Collects the published database path of every repo directory under
 * $CODE_LENS_HOME/repos, in sorted directory order. Directories without a
 * published index.sqlite (for example an interrupted first index) are
 * skipped; directories holding only a legacy pre-SQLite index get a warning
 * that a re-index is required. A missing repos directory yields an empty
 * list. */
static int for_each_repo(CodeLensPathList *out_db_paths)
{
    char *root = repos_root();
    DIR *dir;
    struct dirent *entry;

    if ((out_db_paths == nullptr) || (root == nullptr)) {
        return -1;
    }
    (void)memset(out_db_paths, 0, sizeof(*out_db_paths));

    dir = opendir(root);
    if (dir == nullptr) {
        return 0;
    }

    while ((entry = readdir(dir)) != nullptr) {
        char *repo_dir;
        char *db_path;

        if (entry->d_name[0] == '.') {
            continue;
        }
        repo_dir = code_lens_join_path(root, entry->d_name);
        if ((repo_dir == nullptr) || !code_lens_is_directory(repo_dir)) {
            continue;
        }
        db_path = code_lens_join_path(repo_dir, "index.sqlite");
        if ((db_path == nullptr) || !code_lens_path_exists(db_path)) {
            char *legacy_path = code_lens_join_path(repo_dir, "index.lbug");

            if ((legacy_path != nullptr) && code_lens_path_exists(legacy_path)) {
                (void)fprintf(stderr,
                              "code-lens: skipping repo %s: index format changed, "
                              "re-index required\n",
                              entry->d_name);
            }
            continue;
        }
        if (path_list_append(out_db_paths, db_path) != 0) {
            (void)closedir(dir);
            return -1;
        }
    }

    (void)closedir(dir);
    if (out_db_paths->count > 1U) {
        qsort(out_db_paths->paths, out_db_paths->count, sizeof(*out_db_paths->paths),
              compare_paths);
    }
    return 0;
}

/* Appends the repository paths recorded in one repo database to the
 * message, comma-separating entries. Unreadable databases are skipped with
 * a warning, matching the listing behavior elsewhere. */
static bool append_repo_names_from_db(StringBuilder *message, const char *db_path, size_t *listed)
{
    CodeLensDb db;
    sqlite3_stmt *stmt = nullptr;
    bool ok = true;
    int rc;

    if (code_lens_db_open_read(&db, db_path) != 0) {
        (void)fprintf(stderr, "code-lens: skipping unreadable repo index %s\n", db_path);
        return true;
    }
    if (db_prepare(&db, "SELECT path FROM Repo", &stmt) != 0) {
        (void)fprintf(stderr, "code-lens: skipping unreadable repo index %s\n", db_path);
        code_lens_db_close(&db);
        return true;
    }

    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
        const unsigned char *value = sqlite3_column_text(stmt, 0);
        int bytes = sqlite3_column_bytes(stmt, 0);

        if ((value == nullptr) || (bytes <= 0)) {
            continue;
        }
        if (!sb_append(message, *listed == 0U ? " " : ", ") ||
            !sb_append_len(message, (const char *)value, (size_t)bytes)) {
            ok = false;
            break;
        }
        (*listed)++;
    }
    if (ok && (rc != SQLITE_ROW) && (rc != SQLITE_DONE)) {
        (void)fprintf(stderr, "code-lens: skipping unreadable repo index %s\n", db_path);
    }

    (void)sqlite3_finalize(stmt);
    code_lens_db_close(&db);
    return ok;
}

/* Builds the error text for a repo that is not in the index, listing the
 * repos that are. */
static char *unknown_repo_message(const char *repo_name)
{
    CodeLensPathList repos = {0};
    StringBuilder message = {0};
    char *result = nullptr;
    size_t listed = 0U;

    if (!sb_appendf(&message, "unknown repo \"%s\"; indexed repos:", repo_name)) {
        goto done;
    }

    if (for_each_repo(&repos) == 0) {
        for (size_t i = 0U; i < repos.count; i++) {
            if (!append_repo_names_from_db(&message, repos.paths[i], &listed)) {
                goto done;
            }
        }
    }

    if ((listed == 0U) && !sb_append(&message, " (none)")) {
        goto done;
    }
    if (!sb_append(&message, "\n")) {
        goto done;
    }
    if (!sb_append(&message,
                   "If the repository exists on disk, run code-lens index --repo <path> to "
                   "build or refresh its index.\n")) {
        goto done;
    }

    result = message.data;
    message.data = nullptr;

done:
    sb_free(&message);
    return result;
}

/* Replaces an empty query/context result with an actionable message: either
 * the repo is unknown, or it is indexed and the search simply had no hits. */
static char *empty_result_message(const char *repo_name, const char *what, const char *search_text)
{
    if (repo_exists(repo_name) == 0) {
        return unknown_repo_message(repo_name);
    }
    return alloc_printf("no %s matching \"%s\" in repo \"%s\"\n", what, search_text, repo_name);
}

typedef struct {
    CodeLensDb *db;
    const char *repo_name;
    const char *repo_path;
    char **indexed_paths;
    size_t indexed_path_count;
    StalenessStatus *status;
    RepoDiff *diff;
} NewFileCheckContext;

static int repo_metadata(CodeLensDb *db, const char *repo_name, char **out_path)
{
    static const char sql[] = "SELECT path FROM Repo WHERE path = ?1";
    sqlite3_stmt *stmt = nullptr;
    int rc;

    if ((db == nullptr) || (repo_name == nullptr) || (out_path == nullptr)) {
        return -1;
    }
    *out_path = nullptr;
    if (db_prepare(db, sql, &stmt) != 0) {
        return -1;
    }
    if (bind_text(stmt, 1, repo_name) != 0) {
        (void)sqlite3_finalize(stmt);
        return -1;
    }

    rc = sqlite3_step(stmt);
    if (rc == SQLITE_ROW) {
        const unsigned char *path = sqlite3_column_text(stmt, 0);
        int bytes = sqlite3_column_bytes(stmt, 0);

        if ((path != nullptr) && (bytes > 0)) {
            *out_path = copy_bytes((const char *)path, (size_t)bytes);
        }
        (void)sqlite3_finalize(stmt);
        return *out_path == nullptr ? -1 : 0;
    }

    if (rc != SQLITE_DONE) {
        (void)report_sqlite_error(db->handle, "query repo metadata");
    }
    (void)sqlite3_finalize(stmt);
    return -1;
}

static char *read_repo_path(CodeLensDb *db)
{
    sqlite3_stmt *stmt = nullptr;
    char *repo_path = nullptr;

    if (db_prepare(db, "SELECT path FROM Repo LIMIT 1", &stmt) != 0) {
        return nullptr;
    }
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        const unsigned char *path = sqlite3_column_text(stmt, 0);
        int bytes = sqlite3_column_bytes(stmt, 0);

        if ((path != nullptr) && (bytes > 0)) {
            repo_path = copy_bytes((const char *)path, (size_t)bytes);
        }
    }
    (void)sqlite3_finalize(stmt);
    return repo_path;
}

static bool maven_artifact_cache_markers_current(CodeLensDb *db, const char *repo_path)
{
    sqlite3_stmt *stmt = nullptr;
    bool current = false;
    int step;

    if ((db_prepare(db,
                    "SELECT sourceRoot, checksum FROM DependencyArtifact WHERE repo=?1",
                    &stmt) != 0) || (bind_text(stmt, 1, repo_path) != 0)) {
        (void)sqlite3_finalize(stmt);
        return false;
    }
    while ((step = sqlite3_step(stmt)) == SQLITE_ROW) {
        const char *source_root = (const char *)sqlite3_column_text(stmt, 0);
        const char *checksum = (const char *)sqlite3_column_text(stmt, 1);

        if (!maven_source_cache_marker_current(source_root, checksum)) {
            goto done;
        }
    }
    current = step == SQLITE_DONE;

done:
    (void)sqlite3_finalize(stmt);
    return current;
}

static int check_maven_inputs_changed(CodeLensDb *db,
                                      const char *repo_path,
                                      const char *tools_deps_aliases,
                                      bool *out_changed,
                                      bool *out_retry_due)
{
    MavenDependencySet current;
    sqlite3_stmt *stmt = nullptr;
    bool retryable = false;
    int step;

    if ((db == nullptr) || (repo_path == nullptr) || (out_changed == nullptr) ||
        (out_retry_due == nullptr)) {
        return -1;
    }
    *out_changed = false;
    *out_retry_due = false;
    (void)memset(&current, 0, sizeof(current));
    current.repo_path = repo_path;
    current.tools_deps_aliases = tools_deps_aliases_or_empty(tools_deps_aliases);
    if (!tools_deps_aliases_valid(tools_deps_aliases) ||
        (dependency_project_detect(repo_path, &current) != 0)) {
        return -1;
    }
    if ((db_prepare(db,
                    "SELECT rootPom, toolsDepsAliases, status FROM MavenProject WHERE repo = ?1 LIMIT 1",
                    &stmt) != 0) || (bind_text(stmt, 1, repo_path) != 0)) {
        (void)sqlite3_finalize(stmt);
        return -1;
    }
    step = sqlite3_step(stmt);
    if (step == SQLITE_DONE) {
        /* Indexes created before dependency-project status rows were added
         * rebuild once, including repositories with no recognized build. */
        *out_changed = true;
        (void)sqlite3_finalize(stmt);
        return 0;
    }
    if (step != SQLITE_ROW) {
        (void)sqlite3_finalize(stmt);
        return -1;
    }
    {
        const char *root_build = (const char *)sqlite3_column_text(stmt, 0);
        const char *stored_aliases = (const char *)sqlite3_column_text(stmt, 1);
        const char *status = (const char *)sqlite3_column_text(stmt, 2);

        if ((root_build == nullptr) || (stored_aliases == nullptr) || (status == nullptr) ||
            (strcmp(root_build, current.root_pom) != 0) ||
            (strcmp(stored_aliases, tools_deps_aliases_or_empty(tools_deps_aliases)) != 0)) {
            *out_changed = true;
            (void)sqlite3_finalize(stmt);
            return 0;
        }
        retryable = (strcmp(status, "failed") == 0) ||
                    (strcmp(status, "disabled") == 0);
        if (((strcmp(status, "none") == 0) !=
             (current.project_kind == DEPENDENCY_PROJECT_NONE)) ||
            ((strcmp(status, "resolved") != 0) && (strcmp(status, "empty") != 0) &&
             (strcmp(status, "none") != 0) && !retryable)) {
            *out_changed = true;
            (void)sqlite3_finalize(stmt);
            return 0;
        }
    }
    (void)sqlite3_finalize(stmt);
    if (maven_collect_inputs(repo_path, &current) != 0) {
        return -1;
    }
    *out_changed = !maven_inputs_match_db(db, &current) ||
                   !maven_artifact_cache_markers_current(db, repo_path);
    if (!*out_changed && retryable && maven_enabled()) {
        /* A failed/disabled first generation has no dependency rows to go
         * stale. Keep its current workspace index available to MCP reads;
         * explicit indexing still sees this flag and retries resolution. */
        *out_retry_due = true;
    }
    return 0;
}

static const char *relative_repo_path(const char *repo_path, const char *path)
{
    size_t repo_len;

    if ((repo_path == nullptr) || (path == nullptr)) {
        return path;
    }
    repo_len = strlen(repo_path);
    while ((repo_len > 1U) && (repo_path[repo_len - 1U] == '/')) {
        repo_len--;
    }
    if ((strncmp(path, repo_path, repo_len) == 0) && (path[repo_len] == '/')) {
        return path + repo_len + 1U;
    }
    return path;
}

/* One indexed file's stat comparison, prepared single-threaded and executed
 * on a small worker pool: the staleness pass is ~one stat() per indexed
 * file, and on thousands of files the filesystem work dominates the check. */
typedef struct {
    char *path;
    int64_t size;
    int64_t mtime_sec;
    int64_t mtime_nsec;
    bool dependency;
    uint8_t verdict;
} StaleCheckEntry;

enum { STALE_VERDICT_OK, STALE_VERDICT_CHANGED, STALE_VERDICT_MISSING };

#define STALE_STAT_THREADS 8U
#define STALE_STAT_MIN_PARALLEL 128U

typedef struct {
    StaleCheckEntry **sorted;
    const size_t *group_begin; /* group g spans [group_begin[g], group_begin[g+1]) */
    size_t group_count;
    size_t stripe;
    size_t stride;
} StaleStatSlice;

static void stale_stat_entry(StaleCheckEntry *entry)
{
    int64_t size = 0;
    int64_t mtime_sec = 0;
    int64_t mtime_nsec = 0;

    if (stat_regular_file(entry->path, &size, &mtime_sec, &mtime_nsec) != 0) {
        entry->verdict = STALE_VERDICT_MISSING;
    } else if ((size != entry->size) || (mtime_sec != entry->mtime_sec) ||
               (mtime_nsec != entry->mtime_nsec)) {
        entry->verdict = STALE_VERDICT_CHANGED;
    } else {
        entry->verdict = STALE_VERDICT_OK;
    }
}

#ifndef _WIN32
/* Applies one fstatat result against the indexed snapshot. */
static void stale_apply_stat(StaleCheckEntry *entry, const struct stat *st, bool ok)
{
    if (!ok || !S_ISREG(st->st_mode) || (st->st_size < 0)) {
        entry->verdict = STALE_VERDICT_MISSING;
    } else if (((int64_t)st->st_size != entry->size) ||
               ((int64_t)st->st_mtime != entry->mtime_sec) ||
               (stat_mtime_nsec(st) != entry->mtime_nsec)) {
        entry->verdict = STALE_VERDICT_CHANGED;
    } else {
        entry->verdict = STALE_VERDICT_OK;
    }
}
#endif

static int stale_entry_path_compare(const void *left, const void *right)
{
    const StaleCheckEntry *const *a = left;
    const StaleCheckEntry *const *b = right;

    return strcmp((*a)->path, (*b)->path);
}

static size_t stale_dir_len(const char *path)
{
    const char *slash = strrchr(path, '/');

    return slash == nullptr ? 0U : (size_t)(slash - path);
}

/* Stats one run of entries that share a directory through a single dirfd:
 * each fstatat resolves one name instead of re-walking the whole path
 * prefix. Any surprise (unopenable directory, odd paths) falls back to the
 * plain full-path stat per entry. */
static void stale_stat_dir_group(StaleCheckEntry **sorted, size_t begin, size_t end)
{
#ifdef _WIN32
    /* Win32 has no directory-relative stat equivalent in the CRT. */
    for (size_t i = begin; i < end; i++) {
        stale_stat_entry(sorted[i]);
    }
#else
    const char *dir_path = sorted[begin]->path;
    size_t dir_len = stale_dir_len(dir_path);
    char dir_buf[PATH_MAX];
    int dir_fd = -1;

    if ((dir_len > 0U) && (dir_len < sizeof(dir_buf))) {
        (void)memcpy(dir_buf, dir_path, dir_len);
        dir_buf[dir_len] = '\0';
        dir_fd = open(dir_buf, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    }
    if (dir_fd < 0) {
        for (size_t i = begin; i < end; i++) {
            stale_stat_entry(sorted[i]);
        }
        return;
    }
    for (size_t i = begin; i < end; i++) {
        struct stat st;
        const char *name = sorted[i]->path + dir_len + 1U;
        bool ok = fstatat(dir_fd, name, &st, 0) == 0;

        stale_apply_stat(sorted[i], &st, ok);
    }
    (void)close(dir_fd);
#endif
}

static void *stale_stat_worker(void *arg)
{
    StaleStatSlice *slice = arg;

    for (size_t g = slice->stripe; g < slice->group_count; g += slice->stride) {
        stale_stat_dir_group(slice->sorted, slice->group_begin[g], slice->group_begin[g + 1U]);
    }
    return nullptr;
}

/* Stats every entry: paths sort so each directory run shares one dirfd, and
 * the runs spread over a few threads when the repo is big enough to pay
 * for the spawns. Workers write disjoint entries and allocate nothing, so
 * no locking is needed; any setup shortfall falls back to plain per-entry
 * stats. */
static void stale_stat_all(StaleCheckEntry *entries, size_t count)
{
    StaleStatSlice slices[STALE_STAT_THREADS];
    pthread_t threads[STALE_STAT_THREADS];
    bool started[STALE_STAT_THREADS] = {false};
    StaleCheckEntry **sorted;
    size_t *group_begin;
    size_t group_count = 0U;

    if (count == 0U) {
        return;
    }
    sorted = code_lens_alloc(count * sizeof(*sorted));
    group_begin = code_lens_alloc((count + 1U) * sizeof(*group_begin));
    if ((sorted == nullptr) || (group_begin == nullptr)) {
        for (size_t i = 0U; i < count; i++) {
            stale_stat_entry(&entries[i]);
        }
        return;
    }
    for (size_t i = 0U; i < count; i++) {
        sorted[i] = &entries[i];
    }
    qsort(sorted, count, sizeof(*sorted), stale_entry_path_compare);
    for (size_t i = 0U; i < count; i++) {
        if (i == 0U) {
            group_begin[group_count++] = 0U;
        } else {
            size_t prev_len = stale_dir_len(sorted[i - 1U]->path);
            size_t this_len = stale_dir_len(sorted[i]->path);

            if ((prev_len != this_len) ||
                (memcmp(sorted[i - 1U]->path, sorted[i]->path, this_len) != 0)) {
                group_begin[group_count++] = i;
            }
        }
    }
    group_begin[group_count] = count;

    if (count < (size_t)STALE_STAT_MIN_PARALLEL) {
        for (size_t g = 0U; g < group_count; g++) {
            stale_stat_dir_group(sorted, group_begin[g], group_begin[g + 1U]);
        }
        return;
    }
    for (size_t t = 0U; t < (size_t)STALE_STAT_THREADS; t++) {
        slices[t] = (StaleStatSlice){.sorted = sorted,
                                     .group_begin = group_begin,
                                     .group_count = group_count,
                                     .stripe = t,
                                     .stride = (size_t)STALE_STAT_THREADS};
        if (t > 0U) {
            started[t] =
                pthread_create(&threads[t], nullptr, stale_stat_worker, &slices[t]) == 0;
        }
    }
    for (size_t t = 1U; t < (size_t)STALE_STAT_THREADS; t++) {
        if (!started[t]) {
            (void)stale_stat_worker(&slices[t]);
        }
    }
    (void)stale_stat_worker(&slices[0]);
    for (size_t t = 1U; t < (size_t)STALE_STAT_THREADS; t++) {
        if (started[t]) {
            (void)pthread_join(threads[t], nullptr);
        }
    }
}

/* Reads the indexed-file rows into stat entries and the sorted path list,
 * without touching the filesystem. */
static int read_indexed_file_entries(CodeLensDb *db,
                                     const char *repo_name,
                                     StalenessStatus *status,
                                     RepoDiff *diff,
                                     CodeLensPathList *indexed_paths,
                                     StaleCheckEntry **out_entries,
                                     size_t *out_count)
{
    static const char sql[] =
        "SELECT f.path, f.size, f.mtimeSec, f.mtimeNsec,"
        " EXISTS (SELECT 1 FROM DependencyFile d WHERE d.repo = f.repo"
        " AND d.filePath = f.path)"
        " FROM File f WHERE f.repo = ?1";
    sqlite3_stmt *stmt = nullptr;
    StaleCheckEntry *entries = nullptr;
    size_t count = 0U;
    size_t capacity = 0U;
    int rc;

    if (db_prepare(db, sql, &stmt) != 0) {
        return -1;
    }
    if (bind_text(stmt, 1, repo_name) != 0) {
        (void)sqlite3_finalize(stmt);
        return -1;
    }

    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
        const unsigned char *path_text = sqlite3_column_text(stmt, 0);
        int path_bytes = sqlite3_column_bytes(stmt, 0);
        char *path;

        if ((path_text == nullptr) || (path_bytes <= 0)) {
            status->missing_files++;
            if (diff != nullptr) {
                /* A row with no usable path cannot be diffed against disk;
                 * callers requesting a diff need the full-rebuild path. */
                (void)sqlite3_finalize(stmt);
                return -1;
            }
            continue;
        }
        path = copy_bytes((const char *)path_text, (size_t)path_bytes);
        if (path == nullptr) {
            (void)sqlite3_finalize(stmt);
            return -1;
        }
        if ((count == capacity) &&
            !grow_array((void **)&entries, &capacity, sizeof(*entries), 512U)) {
            (void)sqlite3_finalize(stmt);
            return -1;
        }
        entries[count++] = (StaleCheckEntry){.path = path,
                                             .size = sqlite3_column_int64(stmt, 1),
                                             .mtime_sec = sqlite3_column_int64(stmt, 2),
                                             .mtime_nsec = sqlite3_column_int64(stmt, 3),
                                             .dependency = sqlite3_column_int(stmt, 4) != 0,
                                             .verdict = STALE_VERDICT_OK};
        if (path_list_append(indexed_paths, path) != 0) {
            (void)sqlite3_finalize(stmt);
            return -1;
        }
    }

    if (rc != SQLITE_DONE) {
        (void)report_sqlite_error(db->handle, "query indexed files");
        (void)sqlite3_finalize(stmt);
        return -1;
    }
    (void)sqlite3_finalize(stmt);
    if (indexed_paths->count > 1U) {
        qsort(indexed_paths->paths, indexed_paths->count, sizeof(*indexed_paths->paths), compare_paths);
    }
    *out_entries = entries;
    *out_count = count;
    return 0;
}

/* Folds stat verdicts in row order so counts and diff lists are identical
 * to the sequential pass this machinery replaces. */
static int fold_stale_entries(StalenessStatus *status,
                              RepoDiff *diff,
                              StaleCheckEntry *entries,
                              size_t count)
{
    for (size_t i = 0U; i < count; i++) {
        StaleCheckEntry *entry = &entries[i];

        status->checked_files++;
        if (entry->dependency && (entry->verdict != STALE_VERDICT_OK)) {
            status->dependency_sources_changed = true;
        }
        if (entry->verdict == STALE_VERDICT_MISSING) {
            status->missing_files++;
            if ((diff != nullptr) && (path_list_append(&diff->missing, entry->path) != 0)) {
                return -1;
            }
        } else if (entry->verdict == STALE_VERDICT_CHANGED) {
            status->changed_files++;
            if ((diff != nullptr) && (path_list_append(&diff->changed, entry->path) != 0)) {
                return -1;
            }
        }
    }
    return 0;
}

/* Unordered parallel supported-source walk, used by the staleness check where
 * result order is irrelevant (the indexing walk keeps its deterministic
 * DFS order; see AGENTS.md). The root and its depth-1 subdirectories
 * expand serially into a task list of depth-2 subtrees plus shallow files;
 * a few workers then claim subtrees and walk them with plain recursion,
 * collecting hits locally and flushing them through the shared callback
 * once per worker, so the lock is taken a handful of times, not per file.
 * Unlike the build pipeline - where a parallel walker measurably lost to
 * core oversubscription - the staleness check runs on an otherwise idle
 * machine. */
#ifndef _WIN32
#define STALE_WALK_THREADS 4U

typedef struct {
    char **dirs;
    size_t count;
    size_t capacity;
    size_t next_task;
    CodeLensFileCallback callback;
    void *ctx;
    int rc;
    pthread_mutex_t mutex;
} StaleWalk;

static int stale_walk_collect(const char *path, void *arg)
{
    CodeLensPathList *found = arg;

    return path_list_append(found, path);
}

static void *stale_walk_worker(void *arg)
{
    StaleWalk *walk = arg;
    CodeLensPathList found = {0};
    int rc = 0;

    for (;;) {
        char *dir = nullptr;

        (void)pthread_mutex_lock(&walk->mutex);
        if ((walk->rc == 0) && (walk->next_task < walk->count)) {
            dir = walk->dirs[walk->next_task];
            walk->next_task++;
        }
        (void)pthread_mutex_unlock(&walk->mutex);
        if (dir == nullptr) {
            break;
        }
        {
            PathBuffer buffer = {0};
            int fd;

            rc = -1;
            if (path_buffer_init(&buffer, dir) == 0) {
                fd = open(buffer.path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
                rc = fd < 0
                         ? -1
                         : walk_path(fd,
                                     &buffer,
                                     has_supported_extension,
                                     stale_walk_collect,
                                     &found);
            }
            if (rc != 0) {
                break;
            }
        }
    }

    /* One flush per worker: the shared callback (a bsearch plus counter
     * bumps) runs under the lock, but each worker takes it exactly once. */
    (void)pthread_mutex_lock(&walk->mutex);
    if ((rc != 0) && (walk->rc == 0)) {
        walk->rc = -1;
    }
    for (size_t i = 0U; (walk->rc == 0) && (i < found.count); i++) {
        if (walk->callback(found.paths[i], walk->ctx) != 0) {
            walk->rc = -1;
        }
    }
    (void)pthread_mutex_unlock(&walk->mutex);
    return nullptr;
}

static int stale_walk_push_dir(StaleWalk *walk, const char *path)
{
    char *copy = copy_bytes(path, strlen(path));

    if (copy == nullptr) {
        return -1;
    }
    if ((walk->count == walk->capacity) &&
        !grow_array((void **)&walk->dirs, &walk->capacity, sizeof(*walk->dirs), 64U)) {
        return -1;
    }
    walk->dirs[walk->count++] = copy;
    return 0;
}

/* Expands one directory serially: shallow supported files go straight to the
 * callback (still single-threaded here), subdirectories become worker
 * tasks. Mirrors walk_path's skip rules. */
static int stale_walk_expand(StaleWalk *walk, int dir_fd, PathBuffer *buffer, int depth)
{
    DIR *dir = fdopendir(dir_fd);
    struct dirent *entry;

    if (dir == nullptr) {
        (void)close(dir_fd);
        return -1;
    }
    while ((entry = readdir(dir)) != nullptr) {
        size_t mark;
        int rc = 0;

        if ((strcmp(entry->d_name, ".") == 0) || (strcmp(entry->d_name, "..") == 0)) {
            continue;
        }
        if (dirent_can_skip_without_stat(entry, has_supported_extension)) {
            continue;
        }
        if (path_sb_append(buffer, entry->d_name, &mark) != 0) {
            (void)closedir(dir);
            return -1;
        }
        if (dirent_is_directory_at(dirfd(dir), entry)) {
            if ((buffer->include_ignored_dirs || !should_skip_dir(entry->d_name)) &&
                !path_buffer_is_excluded(buffer)) {
                if (depth > 0) {
                    int child_fd =
                        openat(dirfd(dir), entry->d_name, O_RDONLY | O_DIRECTORY | O_CLOEXEC);

                    rc = (child_fd < 0) ? -1
                                        : stale_walk_expand(walk, child_fd, buffer, depth - 1);
                } else {
                    rc = stale_walk_push_dir(walk, buffer->path);
                }
            }
        } else if (has_supported_extension(entry->d_name)) {
            rc = walk->callback(buffer->path, walk->ctx);
        }
        path_buffer_restore(buffer, mark);
        if (rc != 0) {
            (void)closedir(dir);
            return -1;
        }
    }
    (void)closedir(dir);
    return 0;
}

static int stale_walk_source_files(const char *root, CodeLensFileCallback callback, void *ctx)
{
    StaleWalk walk = {0};
    PathBuffer buffer = {0};
    pthread_t threads[STALE_WALK_THREADS];
    size_t started = 0U;
    int root_fd;
    int rc;

    if ((root == nullptr) || (callback == nullptr) || !code_lens_is_directory(root)) {
        return -1;
    }
    walk.callback = callback;
    walk.ctx = ctx;
    if (pthread_mutex_init(&walk.mutex, nullptr) != 0) {
        return -1;
    }
    rc = path_buffer_init(&buffer, root);
    root_fd = rc == 0 ? open(buffer.path, O_RDONLY | O_DIRECTORY | O_CLOEXEC) : -1;
    rc = root_fd < 0 ? -1 : stale_walk_expand(&walk, root_fd, &buffer, 1);

    if (rc == 0) {
        size_t want = walk.count < (size_t)STALE_WALK_THREADS ? walk.count
                                                              : (size_t)STALE_WALK_THREADS;

        for (size_t t = 0U; t + 1U < want; t++) {
            if (pthread_create(&threads[started], nullptr, stale_walk_worker, &walk) != 0) {
                break;
            }
            started++;
        }
        (void)stale_walk_worker(&walk); /* this thread joins the pool */
        for (size_t t = 0U; t < started; t++) {
            (void)pthread_join(threads[t], nullptr);
        }
        rc = walk.rc;
    }
    (void)pthread_mutex_destroy(&walk.mutex);
    return rc;
}
#else
static int stale_walk_source_files(const char *root, CodeLensFileCallback callback, void *ctx)
{
    return code_lens_walk_source_files(root, callback, ctx);
}
#endif

static bool indexed_path_exists(const NewFileCheckContext *check,
                                const char *absolute_path,
                                const char *relative_path)
{
    if ((check == nullptr) || (check->indexed_paths == nullptr)) {
        return false;
    }
    if (bsearch(&absolute_path,
                check->indexed_paths,
                check->indexed_path_count,
                sizeof(*check->indexed_paths),
                compare_paths) != nullptr) {
        return true;
    }
    return (relative_path != nullptr) &&
           (bsearch(&relative_path,
                    check->indexed_paths,
                    check->indexed_path_count,
                    sizeof(*check->indexed_paths),
                    compare_paths) != nullptr);
}

static int new_file_check_callback(const char *path, void *ctx)
{
    NewFileCheckContext *check = ctx;
    const char *relative_path;

    if ((check == nullptr) || (path == nullptr)) {
        return -1;
    }
    relative_path = relative_repo_path(check->repo_path, path);
    if (!indexed_path_exists(check, path, relative_path)) {
        check->status->new_files++;
        if (check->diff != nullptr) {
            char *copy = copy_bytes(path, strlen(path));

            if ((copy == nullptr) || (path_list_append(&check->diff->added, copy) != 0)) {
                return -1;
            }
        }
    }
    return 0;
}

static int check_new_files_current(CodeLensDb *db,
                                   const char *repo_name,
                                   const char *repo_path,
                                   const CodeLensPathList *indexed_paths,
                                   StalenessStatus *status,
                                   RepoDiff *diff)
{
    NewFileCheckContext ctx = {
        .db = db,
        .repo_name = repo_name,
        .repo_path = repo_path,
        .indexed_paths = indexed_paths == nullptr ? nullptr : indexed_paths->paths,
        .indexed_path_count = indexed_paths == nullptr ? 0U : indexed_paths->count,
        .status = status,
        .diff = diff,
    };

    if (!code_lens_is_directory(repo_path)) {
        status->missing_files++;
        return diff != nullptr ? -1 : 0;
    }
    return stale_walk_source_files(repo_path, new_file_check_callback, &ctx);
}

/* Shared staleness engine: fills the summary counters, and when diff is
 * non-null also the concrete path sets (a diff failure just means callers
 * must fall back to a full rebuild; the summary-only path is unchanged). */
static int check_repo_staleness_diff(CodeLensDb *db,
                                     const char *repo_name,
                                     const char *tools_deps_aliases,
                                     StalenessStatus *out_status,
                                     RepoDiff *diff)
{
    StalenessStatus status = {0};
    CodeLensPathList indexed_paths = {0};
    StaleCheckEntry *entries = nullptr;
    size_t entry_count = 0U;
    double start = profile_now_seconds();
    char *repo_path = nullptr;
    int rc = -1;

    if ((db == nullptr) || (repo_name == nullptr) || (out_status == nullptr)) {
        return -1;
    }

    if ((repo_metadata(db, repo_name, &repo_path) == 0) &&
        (read_indexed_file_entries(db,
                                   repo_name,
                                   &status,
                                   diff,
                                   &indexed_paths,
                                   &entries,
                                   &entry_count) == 0)) {
        /* Two parallel filesystem phases, run sequentially: concurrent
         * stat and walk passes were measured to fight over the same
         * directory vnode locks and burn 5x the CPU for no wall win. */
        stale_stat_all(entries, entry_count);
        if ((check_new_files_current(db, repo_name, repo_path, &indexed_paths, &status, diff) ==
             0) &&
            (fold_stale_entries(&status, diff, entries, entry_count) == 0) &&
            (check_maven_inputs_changed(db,
                                        repo_path,
                                        tools_deps_aliases,
                                        &status.maven_changed,
                                        &status.dependency_retry_due) == 0)) {
            /* The parallel walk finds new files in nondeterministic order;
             * sort so downstream consumers (the incremental re-parse) stay
             * deterministic per machine. */
            if ((diff != nullptr) && (diff->added.count > 1U)) {
                qsort(diff->added.paths,
                      diff->added.count,
                      sizeof(*diff->added.paths),
                      compare_paths);
            }
            rc = 0;
        }
    }

    status.checked = rc == 0;
    /* dependency_retry_due is deliberately not staleness: a first failed or
     * disabled dependency generation has no dependency rows, while its
     * workspace snapshot remains safe for MCP reads. */
    status.stale = (status.missing_files > 0U) || (status.changed_files > 0U) ||
                   (status.new_files > 0U) || status.maven_changed ||
                   status.dependency_sources_changed;
    status.elapsed_seconds = profile_now_seconds() - start;
    *out_status = status;
    return rc;
}

static int check_repo_staleness(CodeLensDb *db,
                                const char *repo_name,
                                const char *tools_deps_aliases,
                                StalenessStatus *out_status)
{
    return check_repo_staleness_diff(db, repo_name, tools_deps_aliases, out_status, nullptr);
}

static char *staleness_note(const char *repo_name, const StalenessStatus *status)
{
    const char *dependency_detail;

    if ((repo_name == nullptr) || (status == nullptr)) {
        return nullptr;
    }
    if (status->maven_changed && status->dependency_sources_changed) {
        dependency_detail = ", dependency build state and source cache changed";
    } else if (status->maven_changed) {
        dependency_detail = ", dependency build state changed";
    } else if (status->dependency_sources_changed) {
        dependency_detail = ", dependency source cache changed";
    } else {
        dependency_detail = "";
    }
    if (!status->checked) {
        return alloc_printf("warning: repo \"%s\" staleness check failed after %.6fs\n",
                            repo_name,
                            status->elapsed_seconds);
    }
    if (status->refreshed) {
        return alloc_printf("note: repo \"%s\" auto-refreshed its stale index in %.6fs after "
                            "checking %zu files in %.6fs (%zu missing, %zu changed, %zu new%s)\n",
                            repo_name,
                            status->refresh_elapsed_seconds,
                            status->checked_files,
                            status->elapsed_seconds,
                            status->missing_files,
                            status->changed_files,
                            status->new_files,
                            dependency_detail);
    }
    if (status->stale) {
        return alloc_printf("warning: repo \"%s\" index is stale; checked %zu files in %.6fs "
                            "(%zu missing, %zu changed, %zu new%s); %s\n",
                            repo_name,
                            status->checked_files,
                            status->elapsed_seconds,
                            status->missing_files,
                            status->changed_files,
                            status->new_files,
                            dependency_detail,
                            status->refresh_attempted
                                ? "automatic refresh failed; run code-lens index --repo <path> "
                                  "to retry"
                                : "run code-lens index --repo <path> to re-index");
    }
    return alloc_printf("note: repo \"%s\" staleness check passed for %zu files in %.6fs\n",
                        repo_name,
                        status->checked_files,
                        status->elapsed_seconds);
}

static char *prepend_staleness_note(const char *repo_name,
                                    const StalenessStatus *status,
                                    char *result)
{
    char *note;

    if (result == nullptr) {
        return nullptr;
    }
    note = staleness_note(repo_name, status);
    if (note == nullptr) {
        return result;
    }
    return alloc_printf("%s\n%s", note, result);
}

/* Direct search reads report staleness and continue against the published
 * database. MCP reads own their one-handle maintenance flow in
 * mcp_repo_session_open below. */
static int open_search_db(CodeLensDb *db,
                          const char *db_path,
                          const char *repo_name,
                          const char *tools_deps_aliases,
                          StalenessStatus *staleness)
{
    if (code_lens_db_open_read(db, db_path) != 0) {
        return -1;
    }
    (void)check_repo_staleness(db, repo_name, tools_deps_aliases, staleness);
    return 0;
}

typedef struct {
    char *repo_id;
    CodeLensDb db;
} McpRepoSession;

static void mcp_repo_session_close(McpRepoSession *session)
{
    if ((session != nullptr) && session->db.is_open) {
        code_lens_db_close(&session->db);
    }
}

static bool mcp_repo_path_is_absolute(const char *repo)
{
    if ((repo == nullptr) || (repo[0] == '\0')) {
        return false;
    }
    if ((repo[0] == '/') ||
        ((repo[0] == '~') && ((repo[1] == '/') || (repo[1] == '\0')))) {
        return true;
    }
#ifdef _WIN32
    return (isalpha((unsigned char)repo[0]) && (repo[1] == ':')) ||
           ((repo[0] == '\\') && (repo[1] == '\\'));
#else
    return false;
#endif
}

static char *mcp_repo_path_error(const char *repo)
{
    const char *requested = ((repo == nullptr) || (repo[0] == '\0')) ? "." : repo;

    return alloc_printf(
        "repo \"%s\" is not a file or directory inside a non-bare Git worktree\n",
        requested);
}

static char *mcp_repo_relative_error(const char *repo)
{
    char *server_root = canonical_repo_path(".");

    if (server_root == nullptr) {
        return alloc_printf("repo \"%s\" is relative; pass an absolute path\n", repo);
    }
    return alloc_printf("repo \"%s\" is relative; pass an absolute path "
                        "(this server resolves relative paths against %s)\n",
                        repo,
                        server_root);
}

static char *mcp_repo_prepare_error(const char *repo)
{
    return alloc_printf("code search is unavailable for repo \"%s\"; retry the request or "
                        "check the code-lens server logs\n",
                        repo == nullptr ? "" : repo);
}

/* Opens one current database generation for the lifetime of an MCP read.
 * Preparing and refreshing the backing data is deliberately invisible to
 * callers: a request either sees the current worktree or gets one ordinary
 * search-availability error. */
static char *mcp_repo_session_open(McpRepoSession *session,
                                  const char *repo,
                                  const char *tools_deps_aliases)
{
    char *db_path;

    if (session == nullptr) {
        return nullptr;
    }
    if (!tools_deps_aliases_valid(tools_deps_aliases)) {
        return alloc_printf("aliases must be a non-empty colon-prefixed string such as :dev:reporting\n");
    }
    (void)memset(session, 0, sizeof(*session));
    if ((repo != nullptr) && (repo[0] != '\0') && !mcp_repo_path_is_absolute(repo)) {
        return mcp_repo_relative_error(repo);
    }
    session->repo_id = mcp_worktree_root(repo);
    if (session->repo_id == nullptr) {
        return mcp_repo_path_error(repo);
    }
    db_path = repo_db_path(session->repo_id);
    if (db_path == nullptr) {
        return mcp_repo_prepare_error(session->repo_id);
    }

    if (code_lens_path_exists(db_path) &&
        (code_lens_db_open_read(&session->db, db_path) == 0)) {
        StalenessStatus status = {0};

        if ((check_repo_staleness(&session->db, session->repo_id, tools_deps_aliases, &status) == 0) &&
            !status.stale) {
            return nullptr;
        }
        code_lens_db_close(&session->db);
    }

    if ((code_lens_index_repository_ex(session->repo_id,
                                        &(CodeLensDependencyOptions){.tools_deps_aliases = tools_deps_aliases},
                                        nullptr) != 0) ||
        (code_lens_db_open_read(&session->db, db_path) != 0)) {
        return mcp_repo_prepare_error(session->repo_id);
    }
    return nullptr;
}

static char *maven_project_status_note(CodeLensDb *db, const char *repo_name)
{
    sqlite3_stmt *stmt = nullptr;
    char *note = nullptr;

    if ((db == nullptr) || (repo_name == nullptr) ||
        (db_prepare(db,
                    "SELECT status, message FROM MavenProject WHERE repo = ?1",
                    &stmt) != 0) || (bind_text(stmt, 1, repo_name) != 0)) {
        (void)sqlite3_finalize(stmt);
        return nullptr;
    }
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        const char *status = (const char *)sqlite3_column_text(stmt, 0);
        const char *message = (const char *)sqlite3_column_text(stmt, 1);

        if ((status != nullptr) && (strcmp(status, "resolved") != 0)) {
            const char *detail = strcmp(status, "disabled") == 0 ? "" : message;

            note = alloc_printf("%s: dependency sources for repo \"%s\" are %s%s%s\n",
                                strcmp(status, "failed") == 0 ? "warning" : "note",
                                repo_name,
                                status,
                                (detail != nullptr) && (detail[0] != '\0') ? ": " : "",
                                detail == nullptr ? "" : detail);
        }
    }
    (void)sqlite3_finalize(stmt);
    return note;
}

static char *resolved_dependency_empty_note(CodeLensDb *db, const char *repo_name)
{
    sqlite3_stmt *stmt = nullptr;
    char *note = nullptr;

    if ((db == nullptr) || (repo_name == nullptr) ||
        (db_prepare(db,
                    "SELECT p.status, p.message, COUNT(df.id) FROM MavenProject p "
                    "LEFT JOIN DependencyFile df ON df.repo=p.repo WHERE p.repo=?1 "
                    "GROUP BY p.repo, p.status, p.message",
                    &stmt) != 0) || (bind_text(stmt, 1, repo_name) != 0)) {
        (void)sqlite3_finalize(stmt);
        return nullptr;
    }
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        const char *status = (const char *)sqlite3_column_text(stmt, 0);
        const char *message = (const char *)sqlite3_column_text(stmt, 1);
        sqlite3_int64 file_count = sqlite3_column_int64(stmt, 2);

        if ((status != nullptr) && (strcmp(status, "resolved") == 0) &&
            (file_count == 0)) {
            note = alloc_printf(
                "note: dependency sources for repo \"%s\" are resolved%s%s, but no "
                "dependency source files are available\n",
                repo_name,
                (message != nullptr) && (message[0] != '\0') ? ": " : "",
                message == nullptr ? "" : message);
        }
    }
    (void)sqlite3_finalize(stmt);
    return note;
}

#define REFERENCE_SNIPPET_CONTEXT_LINES 2U
#define REFERENCE_SNIPPET_MAX_BYTES 8192U

static size_t line_start_before(const char *data, size_t pos)
{
    while ((pos > 0U) && (data[pos - 1U] != '\n')) {
        pos--;
    }
    return pos;
}

static size_t line_end_after(const char *data, size_t len, size_t pos)
{
    while ((pos < len) && (data[pos] != '\n')) {
        pos++;
    }
    return pos;
}

static bool append_reference_snippet(StringBuilder *out,
                                     const CodeLensMappedFile *file,
                                     uint32_t line_number,
                                     uint32_t start_byte,
                                     uint32_t end_byte)
{
    size_t start;
    size_t end;
    size_t first;
    size_t last;
    size_t line_start;
    uint32_t first_line = line_number;
    uint32_t current_line;

    if ((out == nullptr) || (file == nullptr) || (file->data == nullptr) ||
        ((size_t)start_byte > (size_t)end_byte) || ((size_t)end_byte > file->len) ||
        (line_number == 0U)) {
        return sb_append(out, "snippet unavailable: the source changed while it was being read; "
                              "retry the search\n");
    }

    start = (size_t)start_byte;
    end = (size_t)end_byte;
    line_start = line_start_before(file->data, start);
    first = line_start;
    for (uint32_t i = 0U; (i < REFERENCE_SNIPPET_CONTEXT_LINES) && (first > 0U); i++) {
        first = line_start_before(file->data, first - 1U);
        if (first_line > 1U) {
            first_line--;
        }
    }

    last = line_end_after(file->data, file->len, end);
    for (uint32_t i = 0U; (i < REFERENCE_SNIPPET_CONTEXT_LINES) && (last < file->len); i++) {
        last++;
        last = line_end_after(file->data, file->len, last);
    }
    if ((last < file->len) && (file->data[last] == '\n')) {
        last++;
    }

    if (last > first + REFERENCE_SNIPPET_MAX_BYTES) {
        return sb_append(out, "snippet unavailable: snippet window exceeds size cap\n");
    }

    current_line = first_line;
    for (size_t pos = first; pos <= last && pos < file->len;) {
        size_t next = line_end_after(file->data, file->len, pos);
        size_t display_end = next;

        if ((display_end > pos) && (file->data[display_end - 1U] == '\r')) {
            display_end--;
        }
        if (!sb_appendf(out, "%c %u | ", current_line == line_number ? '>' : ' ', current_line) ||
            !sb_append_len(out, file->data + pos, display_end - pos) || !sb_append(out, "\n")) {
            return false;
        }
        if (next >= file->len) {
            break;
        }
        pos = next + 1U;
        current_line++;
    }
    return true;
}

static char *query_reference_snippets(CodeLensDb *db,
                                      const char *repo_name,
                                      const char *symbol_base,
                                      const char *namespace_filter,
                                      const char *path_filter,
                                      const char *definition_scope,
                                      int exclude_tests)
{
    static const char references_sql[] =
        "WITH candidates(namespace) AS ("
        "SELECT DISTINCT s.namespace FROM Symbol s "
        "LEFT JOIN DependencyFile df ON df.repo = s.repo AND df.filePath = s.filePath "
        "WHERE s.repo = ?1 AND s.name = ?2 "
        "AND (?3 = '' OR s.namespace = ?3) "
        "AND (?4 = '' OR instr(lower(s.filePath), lower(?4)) > 0) "
        "AND ((?5 = 'workspace' AND df.id IS NULL) OR (?5 = 'dependencies' AND df.id IS NOT NULL)) "
        "AND (?6 = 0 OR (s.kind != 'test' "
        "AND instr(lower(replace(s.filePath, '\\', '/')), '/test/') = 0 "
        "AND instr(lower(replace(s.filePath, '\\', '/')), '/tests/') = 0))) "
        "SELECT f.path, r.lineNumber, r.columnNumber, r.startByte, r.endByte, "
        "r.symbol, r.targetNamespace FROM Ref r JOIN File f ON f.rowid = r.fileId "
        "LEFT JOIN DependencyFile rdf ON rdf.repo = f.repo AND rdf.filePath = f.path "
        "WHERE f.repo = ?1 AND r.symbolBase = ?2 "
        "AND (CASE WHEN r.targetNamespace = '' THEN "
        "(f.namespace IN candidates OR EXISTS (SELECT 1 FROM Referred rf "
        "WHERE rf.repo = ?1 AND rf.filePath = f.path "
        "AND (rf.symbol = ?2 OR rf.symbol = ':all') AND rf.namespace IN candidates)) "
        "ELSE r.targetNamespace IN candidates END) "
        "AND (?6 = 0 OR (instr(lower(replace(f.path, '\\', '/')), '/test/') = 0 "
        "AND instr(lower(replace(f.path, '\\', '/')), '/tests/') = 0)) "
        "ORDER BY CASE WHEN rdf.id IS NULL THEN 0 ELSE 1 END, f.path, r.lineNumber LIMIT 25";
    sqlite3_stmt *stmt = nullptr;
    StringBuilder out = {0};
    CodeLensMappedFile mapped = {0};
    char *mapped_path = nullptr;
    bool has_mapping = false;
    int rc;

    if ((db == nullptr) || (repo_name == nullptr) || (symbol_base == nullptr)) {
        return nullptr;
    }
    if (db_prepare(db, references_sql, &stmt) != 0) {
        return nullptr;
    }
    if ((bind_text(stmt, 1, repo_name) != 0) || (bind_text(stmt, 2, symbol_base) != 0) ||
        (bind_text(stmt, 3, namespace_filter) != 0) || (bind_text(stmt, 4, path_filter) != 0) ||
        (bind_text(stmt, 5, definition_scope) != 0) ||
        (sqlite3_bind_int(stmt, 6, exclude_tests) != SQLITE_OK)) {
        (void)sqlite3_finalize(stmt);
        return nullptr;
    }

    if (!sb_append(&out, "Reference snippets\n==================\n")) {
        (void)sqlite3_finalize(stmt);
        return nullptr;
    }

    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
        const char *path = (const char *)sqlite3_column_text(stmt, 0);
        uint32_t line = (uint32_t)sqlite3_column_int64(stmt, 1);
        uint32_t column = (uint32_t)sqlite3_column_int64(stmt, 2);
        uint32_t start_byte = (uint32_t)sqlite3_column_int64(stmt, 3);
        uint32_t end_byte = (uint32_t)sqlite3_column_int64(stmt, 4);
        const char *symbol = (const char *)sqlite3_column_text(stmt, 5);
        const char *target_namespace = (const char *)sqlite3_column_text(stmt, 6);

        if (path == nullptr) {
            continue;
        }
        if ((mapped_path == nullptr) || (strcmp(mapped_path, path) != 0)) {
            if (has_mapping) {
                code_lens_mapped_file_free(&mapped);
                has_mapping = false;
            }
            mapped_path = copy_cstr(path);
            if ((mapped_path != nullptr) && (code_lens_map_file(mapped_path, &mapped) == 0)) {
                has_mapping = true;
            }
        }

        if (!sb_appendf(&out,
                        "%s:%u:%u %s%s%s\n",
                        path,
                        line,
                        column,
                        symbol == nullptr ? "" : symbol,
                        ((target_namespace != nullptr) && (target_namespace[0] != '\0')) ? " -> " : "",
                        ((target_namespace != nullptr) && (target_namespace[0] != '\0'))
                            ? target_namespace
                            : "")) {
            goto fail;
        }
        if (!has_mapping) {
            if (!sb_append(&out, "snippet unavailable: source file could not be read\n\n")) {
                goto fail;
            }
            continue;
        }
        if (!append_reference_snippet(&out, &mapped, line, start_byte, end_byte) ||
            !sb_append(&out, "\n")) {
            goto fail;
        }
    }

    if (rc != SQLITE_DONE) {
        (void)report_sqlite_error(db->handle, "query reference snippets");
        goto fail;
    }

    if (has_mapping) {
        code_lens_mapped_file_free(&mapped);
    }
    (void)sqlite3_finalize(stmt);
    return out.data;

fail:
    if (has_mapping) {
        code_lens_mapped_file_free(&mapped);
    }
    (void)sqlite3_finalize(stmt);
    sb_free(&out);
    return nullptr;
}

static char *list_repos_internal(void)
{
    static const char list_query[] =
        "SELECT path AS path, indexedAt AS indexedAt, "
        "fileCount AS filesAtIndexTime, symbolCount AS symbolsAtIndexTime, "
        "referenceCount AS referencesAtIndexTime, keywordCount AS keywordsAtIndexTime, "
        "aliasCount AS aliasesAtIndexTime, "
        "(SELECT COUNT(*) FROM DependencyArtifact d WHERE d.repo = Repo.path) "
        "AS dependencyArtifactsAtIndexTime, "
        "(SELECT COUNT(*) FROM DependencyFile f WHERE f.repo = Repo.path) "
        "AS dependencyFilesAtIndexTime FROM Repo ORDER BY path";
    static const char legacy_list_query[] =
        "SELECT path AS path, indexedAt AS indexedAt, "
        "fileCount AS filesAtIndexTime, symbolCount AS symbolsAtIndexTime, "
        "referenceCount AS referencesAtIndexTime, keywordCount AS keywordsAtIndexTime, "
        "aliasCount AS aliasesAtIndexTime, 0 AS dependencyArtifactsAtIndexTime, "
        "0 AS dependencyFilesAtIndexTime FROM Repo ORDER BY path";
    CodeLensPathList repos = {0};
    StringBuilder output = {0};
    StringBuilder notes = {0};
    char *result = nullptr;
    size_t emitted = 0U;

    if (for_each_repo(&repos) != 0) {
        return nullptr;
    }

    for (size_t i = 0U; i < repos.count; i++) {
        CodeLensDb db = {0};
        char *rows;
        const char *data;
        char *repo_name = nullptr;
        StalenessStatus staleness = {0};
        char *note = nullptr;
        int format_version;
        bool current_format;

        if (db_open_read_any_format(&db, repos.paths[i], &format_version) != 0) {
            (void)fprintf(stderr,
                          "code-lens: skipping unreadable repo index %s\n",
                          repos.paths[i]);
            continue;
        }
        current_format = format_version == CODE_LENS_INDEX_FORMAT_VERSION;
        rows = code_lens_db_query_to_string(
            &db, current_format ? list_query : legacy_list_query);
        if (rows == nullptr) {
            (void)fprintf(stderr,
                          "code-lens: skipping unreadable repo index %s\n",
                          repos.paths[i]);
            code_lens_db_close(&db);
            continue;
        }
        data = rows;
        if (emitted > 0U) {
            /* Emit the header line only once across repos. */
            data = strchr(rows, '\n');
            data = data == nullptr ? "" : data + 1;
        }
        if (!sb_append(&output, data)) {
            code_lens_db_close(&db);
            goto done;
        }
        if ((output.len > 0U) && (output.data[output.len - 1U] != '\n') &&
            !sb_append(&output, "\n")) {
            code_lens_db_close(&db);
            goto done;
        }
        repo_name = read_repo_path(&db);
        if ((repo_name != nullptr) && !current_format) {
            note = alloc_printf("warning: repo \"%s\" index format version %d"
                                ", expected %d; re-index required\n",
                                repo_name,
                                format_version,
                                CODE_LENS_INDEX_FORMAT_VERSION);
            if ((note != nullptr) && !sb_append(&notes, note)) {
                code_lens_db_close(&db);
                goto done;
            }
        } else if ((repo_name != nullptr) &&
                   (check_repo_staleness(&db, repo_name, nullptr, &staleness) == 0)) {
            note = staleness_note(repo_name, &staleness);
            if ((note != nullptr) && !sb_append(&notes, note)) {
                code_lens_db_close(&db);
                goto done;
            }
        }
        code_lens_db_close(&db);
        emitted++;
    }

    if (emitted == 0U) {
        result = alloc_printf("No readable repositories indexed. Run code-lens index "
                              "--repo <path> before direct query, context, or sql reads.\n");
        goto done;
    }

    if ((notes.len > 0U) && (!sb_append(&output, "\n") || !sb_append(&output, notes.data))) {
        goto done;
    }

    result = output.data;
    output.data = nullptr;

done:
    sb_free(&notes);
    sb_free(&output);
    return result;
}

char *code_lens_list_repos(void)
{
    return list_repos_internal();
}

char *code_lens_remove_repo(const char *repo_name)
{
    RepoWriteLock write_lock = {.fd = -1};
    char *repo_id;
    char *dir;
    char *db_path;
    char *result = nullptr;

    if (repo_name == nullptr) {
        return nullptr;
    }

    repo_id = resolve_repo_id(repo_name);
    if (repo_id == nullptr) {
        return nullptr;
    }
    if (repo_write_lock_acquire(repo_id, &write_lock) != 0) {
        return nullptr;
    }
    dir = repo_dir_path(repo_id);
    db_path = repo_db_path(repo_id);
    if ((dir == nullptr) || (db_path == nullptr)) {
        goto done;
    }

    if (!code_lens_path_exists(db_path)) {
        result = unknown_repo_message(repo_id);
    } else if (code_lens_remove_tree(dir) != 0) {
        (void)fprintf(stderr, "code-lens: failed to remove repo index directory %s\n", dir);
    } else {
        char *maven_output = maven_resolution_output(repo_id);

        if ((maven_output != nullptr) && (code_lens_remove_tree(maven_output) != 0)) {
            (void)fprintf(stderr,
                          "code-lens: warning: failed to remove Maven resolution cache %s\n",
                          maven_output);
        }
        result = alloc_printf("removed repo \"%s\"\n", repo_id);
    }

done:
    repo_write_lock_release(&write_lock);
    return result;
}

static bool term_has_alnum(const char *term)
{
    for (size_t i = 0U; term[i] != '\0'; i++) {
        if (isalnum((unsigned char)term[i])) {
            return true;
        }
    }
    return false;
}

/* Appends one FTS5 MATCH term: the token wrapped in double quotes (internal
 * quotes doubled) with a trailing * so every term is a prefix query. Quoting
 * keeps FTS5 query operators in user input inert. */
static bool append_fts_term(StringBuilder *match, const char *term)
{
    if (!sb_append(match, "\"")) {
        return false;
    }
    for (size_t i = 0U; term[i] != '\0'; i++) {
        if ((term[i] == '"') && !sb_append(match, "\"")) {
            return false;
        }
        if (!sb_append_len(match, &term[i], 1U)) {
            return false;
        }
    }
    return sb_append(match, "\"*");
}

/* Builds a full FTS5 MATCH string by joining the usable query terms with the
 * given separator: " " for the implicit-AND pass and " OR " for the relaxed
 * any-term fallback. Separator-only tokens tokenize to nothing inside FTS5,
 * so they are skipped to avoid an unmatchable empty phrase. */
static bool build_fts_match(StringBuilder *match,
                            char *const terms[],
                            size_t term_count,
                            const char *separator,
                            size_t *out_used_terms)
{
    size_t used_terms = 0U;

    for (size_t i = 0U; i < term_count; i++) {
        if (!term_has_alnum(terms[i])) {
            continue;
        }
        if ((used_terms > 0U) && !sb_append(match, separator)) {
            return false;
        }
        if (!append_fts_term(match, terms[i])) {
            return false;
        }
        used_terms++;
    }

    *out_used_terms = used_terms;
    return true;
}

static char *keyword_match_pattern(const char *term)
{
    StringBuilder pattern = {0};

    for (size_t i = 0U; term[i] != '\0'; i++) {
        if ((term[i] == '\\') || (term[i] == '%') || (term[i] == '_')) {
            if (!sb_append(&pattern, "\\")) {
                sb_free(&pattern);
                return nullptr;
            }
        }
        if (!sb_append_len(&pattern, &term[i], 1U)) {
            sb_free(&pattern);
            return nullptr;
        }
    }
    if (!sb_append(&pattern, "%")) {
        sb_free(&pattern);
        return nullptr;
    }
    return pattern.data;
}

static char *query_keyword_rows(CodeLensDb *db,
                                const char *repo_name,
                                char *const terms[],
                                size_t term_count,
                                const char *separator,
                                const CodeLensQueryOptions *options,
                                size_t *out_row_count)
{
    /* Filters run on integer term ids: each LIKE resolves once against the
     * small Term dictionary (vocabulary, not occurrences) and the 245k-row
     * KeywordData scan then tests set membership; Term joins only decorate
     * qualifying rows. ROW_NUMBER ranks matching occurrences within each
     * file so the outer query returns one hit per file within each scope
     * before second hits, preventing a single noisy file from consuming a
     * small result limit.
     * Keep the SQL in two literals below C's portable 4095-character limit;
     * it is joined immediately before prepare. */
    static const char keyword_sql_head[] =
        "WITH matching_keywords AS ("
        "SELECT tk.text AS keywordText, f.namespace AS sourceNamespace, "
        "f.path AS filePath, k.lineNumber AS lineNumber, "
        "k.columnNumber AS columnNumber, tb.text AS keywordBase, "
        "tq.text AS qualifier, tn.text AS targetNamespace, "
        "CASE WHEN df.id IS NULL THEN 'workspace' ELSE 'dependency' END AS resultScope, "
        "COALESCE(da.coordinate,'') AS dependencyCoordinate, "
        "CASE WHEN df.id IS NULL THEN 0 ELSE 1 END AS scopeRank, "
        "ROW_NUMBER() OVER (PARTITION BY k.fileId "
        "ORDER BY k.lineNumber, k.columnNumber, k.id) AS occurrenceRound "
        "FROM KeywordData k JOIN File f ON f.rowid=k.fileId "
        "JOIN Term tk ON tk.id=k.keywordTerm "
        "JOIN Term tb ON tb.id=k.keywordBaseTerm "
        "JOIN Term tq ON tq.id=k.qualifierTerm "
        "JOIN Term tn ON tn.id=k.targetNamespaceTerm "
        "LEFT JOIN DependencyFile df ON df.repo=f.repo AND df.filePath=f.path "
        "LEFT JOIN DependencyArtifact da ON da.id=df.artifactId "
        "WHERE f.repo=?1 "
        "AND (?2 = '' OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?2 ESCAPE '\\') OR ?18 = 'OR') "
        "AND (?3 = '' OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?3 ESCAPE '\\') OR ?18 = 'OR') "
        "AND (?4 = '' OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?4 ESCAPE '\\') OR ?18 = 'OR') "
        "AND (?5 = '' OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?5 ESCAPE '\\') OR ?18 = 'OR') "
        "AND (?6 = '' OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?6 ESCAPE '\\') OR ?18 = 'OR') "
        "AND (?7 = '' OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?7 ESCAPE '\\') OR ?18 = 'OR') "
        "AND (?8 = '' OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?8 ESCAPE '\\') OR ?18 = 'OR') "
        "AND (?9 = '' OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?9 ESCAPE '\\') OR ?18 = 'OR') "
        "AND (?10 = '' OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?10 ESCAPE '\\') OR ?18 = 'OR') "
        "AND (?11 = '' OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?11 ESCAPE '\\') OR ?18 = 'OR') "
        "AND (?12 = '' OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?12 ESCAPE '\\') OR ?18 = 'OR') "
        "AND (?13 = '' OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?13 ESCAPE '\\') OR ?18 = 'OR') "
        "AND (?14 = '' OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?14 ESCAPE '\\') OR ?18 = 'OR') "
        "AND (?15 = '' OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?15 ESCAPE '\\') OR ?18 = 'OR') "
        "AND (?16 = '' OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?16 ESCAPE '\\') OR ?18 = 'OR') "
        "AND (?17 = '' OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?17 ESCAPE '\\') OR ?18 = 'OR') ";
    static const char keyword_sql_tail[] =
        "AND (?18 = 'AND' "
        "OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?2 ESCAPE "
        "'\\') OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?3 "
        "ESCAPE '\\') OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text "
        "LIKE ?4 ESCAPE '\\') OR k.keywordBaseTerm IN (SELECT id FROM Term "
        "WHERE text LIKE ?5 ESCAPE '\\') OR k.keywordBaseTerm IN (SELECT id "
        "FROM Term WHERE text LIKE ?6 ESCAPE '\\') OR k.keywordBaseTerm IN "
        "(SELECT id FROM Term WHERE text LIKE ?7 ESCAPE '\\') OR "
        "k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?8 ESCAPE "
        "'\\') OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?9 "
        "ESCAPE '\\') OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text "
        "LIKE ?10 ESCAPE '\\') OR k.keywordBaseTerm IN (SELECT id FROM Term "
        "WHERE text LIKE ?11 ESCAPE '\\') OR k.keywordBaseTerm IN (SELECT id "
        "FROM Term WHERE text LIKE ?12 ESCAPE '\\') OR k.keywordBaseTerm IN "
        "(SELECT id FROM Term WHERE text LIKE ?13 ESCAPE '\\') OR "
        "k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?14 ESCAPE "
        "'\\') OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text LIKE ?15 "
        "ESCAPE '\\') OR k.keywordBaseTerm IN (SELECT id FROM Term WHERE text "
        "LIKE ?16 ESCAPE '\\') OR k.keywordBaseTerm IN (SELECT id FROM Term "
        "WHERE text LIKE ?17 ESCAPE '\\') "
        ") "
        "AND (?19 = 0 OR (instr(lower(replace(f.path, '\\', '/')), '/test/') = 0 "
        "AND instr(lower(replace(f.path, '\\', '/')), '/tests/') = 0)) "
        "AND (?20 = '' OR instr(lower(f.path), lower(?20)) > 0) "
        "AND (?21 = 'all' OR (?21 = 'workspace' AND df.id IS NULL) "
        "OR (?21 = 'dependencies' AND df.id IS NOT NULL)) "
        "AND (?22 = '' OR (da.coordinate IS NOT NULL AND da.coordinate GLOB ?22)) "
        ") "
        "SELECT keywordText AS \"k.keyword\", sourceNamespace AS \"k.sourceNamespace\", "
        "filePath AS \"k.filePath\", lineNumber AS \"k.lineNumber\", "
        "columnNumber AS \"k.columnNumber\", keywordBase AS \"k.keywordBase\", "
        "qualifier AS \"k.qualifier\", targetNamespace AS \"k.targetNamespace\", "
        "resultScope AS \"k.scope\", dependencyCoordinate AS \"k.dependency\" "
        "FROM matching_keywords "
        "ORDER BY scopeRank, occurrenceRound, filePath, lineNumber, columnNumber LIMIT ?23";
    StringBuilder keyword_sql = {0};
    sqlite3_stmt *stmt = nullptr;
    char *patterns[MAX_QUERY_TERMS] = {nullptr};
    char *result = nullptr;
    bool bound = true;
    bool capped = false;
    size_t used = 0U;
    int exclude_tests = options->exclude_tests ? 1 : 0;
    const char *path_filter = options->path == nullptr ? "" : options->path;
    const char *scope_filter = ((options->scope != nullptr) &&
                                ((strcmp(options->scope, "workspace") == 0) ||
                                 (strcmp(options->scope, "dependencies") == 0) ||
                                 (strcmp(options->scope, "all") == 0)))
                                   ? options->scope
                                   : "workspace";
    const char *dependency_filter =
        options->dependency == nullptr ? "" : options->dependency;

    if (out_row_count != nullptr) {
        *out_row_count = 0U;
    }
    if (!sb_append(&keyword_sql, keyword_sql_head) ||
        !sb_append(&keyword_sql, keyword_sql_tail)) {
        sb_free(&keyword_sql);
        return nullptr;
    }
    if (db_prepare(db, keyword_sql.data, &stmt) != 0) {
        sb_free(&keyword_sql);
        return nullptr;
    }
    /* sqlite3_prepare_v2 has copied the statement text into stmt. */
    sb_free(&keyword_sql);

    if (bind_text(stmt, 1, repo_name) != 0) {
        bound = false;
    }
    for (size_t i = 0U; (i < term_count) && (used < MAX_QUERY_TERMS); i++) {
        if (!term_has_alnum(terms[i])) {
            continue;
        }
        patterns[used] = keyword_match_pattern(terms[i]);
        if (patterns[used] == nullptr) {
            bound = false;
            break;
        }
        used++;
    }
    for (size_t i = 0U; bound && (i < MAX_QUERY_TERMS); i++) {
        const char *pattern = i < used ? patterns[i] : "";
        if (bind_text(stmt, (int)i + 2, pattern) != 0) {
            bound = false;
        }
    }
    if (bound && (bind_text(stmt, 18, strcmp(separator, " OR ") == 0 ? "OR" : "AND") != 0)) {
        bound = false;
    }
    if (bound && (sqlite3_bind_int(stmt, 19, exclude_tests) != SQLITE_OK)) {
        bound = false;
    }
    if (bound && (bind_text(stmt, 20, path_filter) != 0)) {
        bound = false;
    }
    if (bound && (bind_text(stmt, 21, scope_filter) != 0)) {
        bound = false;
    }
    if (bound && (bind_text(stmt, 22, dependency_filter) != 0)) {
        bound = false;
    }
    if (bound &&
        (sqlite3_bind_int64(stmt, 23, (sqlite3_int64)options->limit + 1) != SQLITE_OK)) {
        bound = false;
    }

    if (!bound) {
        (void)report_sqlite_error(db->handle, "bind keyword query parameter");
    } else {
        result = render_stmt_result_capped(
            stmt, (size_t)options->limit, out_row_count, &capped);
        if ((result != nullptr) && capped) {
            result = alloc_printf("%snote: additional keyword matches were omitted; "
                                  "narrow with path/--path or increase limit\n",
                                  result);
        }
    }
    (void)sqlite3_finalize(stmt);
    return result;
}

static char *render_query_sections(char *symbol_rows, char *keyword_rows)
{
    StringBuilder output = {0};

    if (!sb_append(&output, "Symbols\n=======\n") || !sb_append(&output, symbol_rows) ||
        !sb_append(&output, "\n\nKeywords\n========\n") || !sb_append(&output, keyword_rows)) {
        sb_free(&output);
        return nullptr;
    }
    return output.data;
}

static void normalize_query_options(const CodeLensQueryOptions *options,
                                    CodeLensQueryOptions *out_options)
{
    (void)memset(out_options, 0, sizeof(*out_options));
    if (options != nullptr) {
        *out_options = *options;
    }
    if (out_options->limit <= 0) {
        out_options->limit = 10;
    }
    if ((out_options->kind != nullptr) && (out_options->kind[0] == '\0')) {
        out_options->kind = nullptr;
    }
    if ((out_options->path != nullptr) && (out_options->path[0] == '\0')) {
        out_options->path = nullptr;
    }
    if ((out_options->scope == nullptr) || (out_options->scope[0] == '\0')) {
        out_options->scope = "workspace";
    }
    if ((out_options->dependency != nullptr) && (out_options->dependency[0] == '\0')) {
        out_options->dependency = nullptr;
    }
}

static bool query_scope_known(const char *scope)
{
    return (strcmp(scope, "workspace") == 0) || (strcmp(scope, "dependencies") == 0) ||
           (strcmp(scope, "all") == 0);
}

/* Symbol kinds assigned by the Clojure, Java, and C extractors, plus the
 * pseudo-kind "keyword" accepted by query filtering. Shared by the
 * unknown-kind note and the MCP tool schema documentation. */
static const char query_kind_list[] =
    "function, var, macro, multimethod, method, protocol, record, type, test, class, "
    "interface, enum, annotation, constructor, field, enum_constant, module, variable, "
    "struct, union, typedef, keyword";

static bool is_known_symbol_kind(const char *kind)
{
    static const char *const kinds[] = {
        "function",
        "var",
        "macro",
        "multimethod",
        "method",
        "protocol",
        "record",
        "type",
        "test",
        "class",
        "interface",
        "enum",
        "annotation",
        "constructor",
        "field",
        "enum_constant",
        "module",
        "variable",
        "struct",
        "union",
        "typedef",
    };

    for (size_t i = 0U; i < sizeof(kinds) / sizeof(kinds[0]); i++) {
        if (strcmp(kind, kinds[i]) == 0) {
            return true;
        }
    }
    return false;
}

static char *query_symbols_ex_internal(const char *repo_name,
                                       const char *query_text,
                                       const CodeLensQueryOptions *options,
                                       CodeLensDb *prepared_db)
{
    static const char search_sql[] =
        "SELECT s.name AS \"s.name\", s.kind AS \"s.kind\", "
        "s.namespace AS \"s.namespace\", s.filePath AS \"s.filePath\", "
        "s.startLine AS \"s.startLine\", s.endLine AS \"s.endLine\", "
        "CAST(-bm25(SymbolFts) * 100 AS INTEGER) AS score, s.doc AS \"s.doc\", "
        "CASE WHEN df.id IS NULL THEN 'workspace' ELSE 'dependency' END AS \"s.scope\", "
        "COALESCE(da.coordinate, '') AS \"s.dependency\", "
        "COALESCE(da.scope, '') AS \"s.dependencyScope\" "
        "FROM SymbolFts JOIN Symbol s ON s.rowid = SymbolFts.rowid "
        "LEFT JOIN DependencyFile df ON df.repo = s.repo AND df.filePath = s.filePath "
        "LEFT JOIN DependencyArtifact da ON da.id = df.artifactId "
        "WHERE SymbolFts MATCH ?1 AND s.repo = ?2 "
        "AND (?3 = 0 OR (s.kind != 'test' "
        "AND instr(lower(replace(s.filePath, '\\', '/')), '/test/') = 0 "
        "AND instr(lower(replace(s.filePath, '\\', '/')), '/tests/') = 0)) "
        "AND (?4 = '' OR s.kind = ?4) "
        "AND (?5 = '' OR instr(lower(s.filePath), lower(?5)) > 0) "
        "AND (?6 = 'all' OR (?6 = 'workspace' AND df.id IS NULL) "
        "OR (?6 = 'dependencies' AND df.id IS NOT NULL)) "
        "AND (?7 = '' OR (da.coordinate IS NOT NULL AND da.coordinate GLOB ?7)) "
        "ORDER BY CASE WHEN df.id IS NULL THEN 0 ELSE 1 END, bm25(SymbolFts), s.name LIMIT ?8";
    char *terms[MAX_QUERY_TERMS] = {nullptr};
    size_t term_count;
    size_t used_terms = 0U;
    StringBuilder match = {0};
    CodeLensDb owned_db;
    CodeLensDb *db = prepared_db;
    char *db_path;
    sqlite3_stmt *stmt = nullptr;
    size_t symbol_row_count = 0U;
    size_t keyword_row_count = 0U;
    StalenessStatus staleness = {0};
    CodeLensQueryOptions query_options;
    bool keyword_kind;
    bool unknown_kind;
    bool unknown_scope;
    const char *kind_filter;
    const char *path_filter;
    const char *scope_filter;
    const char *dependency_filter;
    int exclude_tests;
    bool owns_db = false;
    char *maven_note = nullptr;
    char *dependency_empty_note = nullptr;
    char *result;

    if ((repo_name == nullptr) || (query_text == nullptr)) {
        return nullptr;
    }
    if (prepared_db == nullptr) {
        repo_name = resolve_repo_id(repo_name);
        if (repo_name == nullptr) {
            return nullptr;
        }
    }

    term_count = tokenize(query_text, terms);
    if (!build_fts_match(&match, terms, term_count, " ", &used_terms)) {
        sb_free(&match);
        return nullptr;
    }
    if (used_terms == 0U) {
        sb_free(&match);
        return prepared_db == nullptr
                   ? empty_result_message(repo_name, "symbols or keywords", query_text)
                   : alloc_printf("no symbols or keywords matching \"%s\" in repo \"%s\"\n",
                                  query_text,
                                  repo_name);
    }

    normalize_query_options(options, &query_options);
    /* kind = "keyword" flips the filter around: the Keywords section runs
     * and the Symbols section comes back header-only ("keyword" matches no
     * symbol kind). Unrecognized kinds still filter everything out, but get
     * a note naming the valid values instead of a silent empty result. */
    keyword_kind = (query_options.kind != nullptr) && (strcmp(query_options.kind, "keyword") == 0);
    unknown_kind = (query_options.kind != nullptr) && !keyword_kind &&
                   !is_known_symbol_kind(query_options.kind);
    unknown_scope = !query_scope_known(query_options.scope);
    kind_filter = query_options.kind == nullptr ? "" : query_options.kind;
    path_filter = query_options.path == nullptr ? "" : query_options.path;
    scope_filter = unknown_scope ? "workspace" : query_options.scope;
    dependency_filter = query_options.dependency == nullptr ? "" : query_options.dependency;
    exclude_tests = query_options.exclude_tests ? 1 : 0;

    if (prepared_db == nullptr) {
        db_path = repo_db_path(repo_name);
        if ((db_path == nullptr) || !code_lens_path_exists(db_path)) {
            return repo_exists(repo_name) == 0 ? unknown_repo_message(repo_name) : nullptr;
        }
        if (open_search_db(&owned_db,
                           db_path,
                           repo_name,
                           query_options.tools_deps_aliases,
                           &staleness) != 0) {
            return repo_reindex_required_message(repo_name);
        }
        db = &owned_db;
        owns_db = true;
    }

    result = nullptr;
    if (db_prepare(db, search_sql, &stmt) == 0) {
        bool bound = (bind_text(stmt, 1, match.data) == 0) &&
                     (bind_text(stmt, 2, repo_name) == 0) &&
                     (sqlite3_bind_int(stmt, 3, exclude_tests) == SQLITE_OK) &&
                     (bind_text(stmt, 4, kind_filter) == 0) &&
                     (bind_text(stmt, 5, path_filter) == 0) &&
                     (bind_text(stmt, 6, scope_filter) == 0) &&
                     (bind_text(stmt, 7, dependency_filter) == 0) &&
                     (sqlite3_bind_int(stmt, 8, query_options.limit) == SQLITE_OK);

        if (!bound) {
            (void)report_sqlite_error(db->handle, "bind query parameter");
        } else {
            result = render_stmt_result(stmt, &symbol_row_count);
        }
        (void)sqlite3_finalize(stmt);
        stmt = nullptr;
    }
    if (result != nullptr) {
        char *keyword_rows = (query_options.kind == nullptr) || keyword_kind
                                 ? query_keyword_rows(db,
                                                      repo_name,
                                                      terms,
                                                      term_count,
                                                      " ",
                                                      &query_options,
                                                      &keyword_row_count)
                                 : alloc_printf("k.keyword|k.sourceNamespace|k.filePath|k.lineNumber|"
                                                "k.columnNumber|k.keywordBase|k.qualifier|"
                                                "k.targetNamespace|k.scope|k.dependency\n");
        if (keyword_rows == nullptr) {
            result = nullptr;
        } else {
            result = render_query_sections(result, keyword_rows);
        }
    }

    /* Multi-term queries AND their terms together, which often over-narrows
     * exploratory searches to zero rows. Relax the empty AND result to an OR
     * of the same terms: BM25 still ranks rows matching more terms first,
     * and a notice line flags that the strict query had no hits. */
    if ((result != nullptr) && (symbol_row_count == 0U) && (keyword_row_count == 0U) &&
        (used_terms >= 2U)) {
        StringBuilder or_match = {0};
        size_t or_used_terms = 0U;

        if (build_fts_match(&or_match, terms, term_count, " OR ", &or_used_terms)) {
            char *or_symbol_rows;
            char *or_keyword_rows;
            size_t or_symbol_row_count = 0U;
            size_t or_keyword_row_count = 0U;

            or_symbol_rows = nullptr;
            if (db_prepare(db, search_sql, &stmt) == 0) {
                bool bound = (bind_text(stmt, 1, or_match.data) == 0) &&
                             (bind_text(stmt, 2, repo_name) == 0) &&
                             (sqlite3_bind_int(stmt, 3, exclude_tests) == SQLITE_OK) &&
                             (bind_text(stmt, 4, kind_filter) == 0) &&
                             (bind_text(stmt, 5, path_filter) == 0) &&
                             (bind_text(stmt, 6, scope_filter) == 0) &&
                             (bind_text(stmt, 7, dependency_filter) == 0) &&
                             (sqlite3_bind_int(stmt, 8, query_options.limit) == SQLITE_OK);

                if (!bound) {
                    (void)report_sqlite_error(db->handle, "bind query parameter");
                } else {
                    or_symbol_rows = render_stmt_result(stmt, &or_symbol_row_count);
                }
                (void)sqlite3_finalize(stmt);
                stmt = nullptr;
            }
            or_keyword_rows = (query_options.kind == nullptr) || keyword_kind
                                  ? query_keyword_rows(db,
                                                       repo_name,
                                                       terms,
                                                       term_count,
                                                       " OR ",
                                                       &query_options,
                                                       &or_keyword_row_count)
                                  : alloc_printf("k.keyword|k.sourceNamespace|k.filePath|"
                                                 "k.lineNumber|k.columnNumber|k.keywordBase|"
                                                 "k.qualifier|k.targetNamespace|k.scope|k.dependency\n");
            if ((or_symbol_rows != nullptr) && (or_keyword_rows != nullptr) &&
                ((or_symbol_row_count > 0U) || (or_keyword_row_count > 0U))) {
                char *or_result = render_query_sections(or_symbol_rows, or_keyword_rows);
                if (or_result != nullptr) {
                    symbol_row_count = or_symbol_row_count;
                    keyword_row_count = or_keyword_row_count;
                    result = alloc_printf("note: no symbols or keywords match all terms; "
                                          "showing any-term matches\n\n%s",
                                          or_result);
                }
            }
        }
        sb_free(&or_match);
    }

    if ((strcmp(scope_filter, "dependencies") == 0) ||
        (strcmp(scope_filter, "all") == 0)) {
        dependency_empty_note = resolved_dependency_empty_note(db, repo_name);
    }
    if (owns_db || (strcmp(scope_filter, "dependencies") == 0) ||
        (strcmp(scope_filter, "all") == 0)) {
        maven_note = maven_project_status_note(db, repo_name);
    }
    if (owns_db) {
        code_lens_db_close(db);
    }

    if (result == nullptr) {
        return prepared_db == nullptr
                   ? (repo_exists(repo_name) == 0 ? unknown_repo_message(repo_name) : nullptr)
                   : nullptr;
    }
    if ((symbol_row_count == 0U) && (keyword_row_count == 0U)) {
        result = prepared_db == nullptr
                     ? empty_result_message(repo_name, "symbols or keywords", query_text)
                     : alloc_printf("no symbols or keywords matching \"%s\" in repo \"%s\"\n",
                                    query_text,
                                    repo_name);
    }
    if (prepared_db == nullptr) {
        result = prepend_staleness_note(repo_name, &staleness, result);
    }
    if ((result != nullptr) && (maven_note != nullptr)) {
        result = alloc_printf("%s\n%s", maven_note, result);
    }
    if ((result != nullptr) && (dependency_empty_note != nullptr)) {
        result = alloc_printf("%s\n%s", dependency_empty_note, result);
    }
    if ((result != nullptr) && unknown_kind) {
        result = alloc_printf("note: unknown kind \"%s\"; valid kinds: %s\n%s",
                              query_options.kind,
                              query_kind_list,
                              result);
    }
    if ((result != nullptr) && unknown_scope) {
        result = alloc_printf("note: unknown scope \"%s\"; valid scopes: workspace, dependencies, all\n%s",
                              query_options.scope,
                              result);
    }
    return result;
}

char *code_lens_query_symbols_ex(const char *repo_name,
                                  const char *query_text,
                                  const CodeLensQueryOptions *options)
{
    return query_symbols_ex_internal(repo_name, query_text, options, nullptr);
}

char *code_lens_query_symbols(const char *repo_name, const char *query_text, int limit)
{
    CodeLensQueryOptions options = {.limit = limit};

    return code_lens_query_symbols_ex(repo_name, query_text, &options);
}

static const char *context_base_name(CodeLensDb *db,
                                     const char *repo_name,
                                     const char *symbol_name)
{
    sqlite3_stmt *stmt = nullptr;
    bool exact_definition = false;
    const char *base = symbol_name;
    const char *separator = nullptr;

    if ((db != nullptr) && (repo_name != nullptr) && (symbol_name != nullptr) &&
        (db_prepare(db,
                    "SELECT 1 FROM Symbol WHERE repo = ?1 AND name = ?2 LIMIT 1",
                    &stmt) == 0)) {
        if ((bind_text(stmt, 1, repo_name) == 0) && (bind_text(stmt, 2, symbol_name) == 0)) {
            exact_definition = sqlite3_step(stmt) == SQLITE_ROW;
        }
        (void)sqlite3_finalize(stmt);
    }
    if (exact_definition) {
        return symbol_name;
    }

    separator = strrchr(symbol_name, '/');
    if ((separator == nullptr) || (separator == symbol_name) || (separator[1] == '\0')) {
        separator = strrchr(symbol_name, '#');
    }
    if ((separator == nullptr) || (separator == symbol_name) || (separator[1] == '\0')) {
        const char *scan = symbol_name;
        const char *arrow = nullptr;

        while ((scan = strstr(scan, "->")) != nullptr) {
            arrow = scan;
            scan += 2;
        }
        separator = arrow == nullptr ? nullptr : arrow + 1;
    }
    if ((separator == nullptr) || (separator == symbol_name) || (separator[1] == '\0')) {
        separator = strrchr(symbol_name, '.');
    }
    if ((separator != nullptr) && (separator != symbol_name) && (separator[1] != '\0')) {
        base = separator + 1;
    }
    return base;
}

static bool context_has_workspace_definition(CodeLensDb *db,
                                             const char *repo_name,
                                             const char *symbol_base,
                                             const char *namespace_filter,
                                             const char *path_filter,
                                             int exclude_tests)
{
    static const char sql[] =
        "SELECT 1 FROM Symbol s WHERE s.repo = ?1 "
        "AND (s.name = ?2 OR instr(lower(s.name), lower(?2)) > 0) "
        "AND (?3 = '' OR s.namespace = ?3) "
        "AND (?4 = '' OR instr(lower(s.filePath), lower(?4)) > 0) "
        "AND (?5 = 0 OR (s.kind != 'test' "
        "AND instr(lower(replace(s.filePath, '\\', '/')), '/test/') = 0 "
        "AND instr(lower(replace(s.filePath, '\\', '/')), '/tests/') = 0)) "
        "AND NOT EXISTS (SELECT 1 FROM DependencyFile df WHERE df.repo = s.repo "
        "AND df.filePath = s.filePath) LIMIT 1";
    sqlite3_stmt *stmt = nullptr;
    bool found = false;

    if ((db_prepare(db, sql, &stmt) == 0) &&
        (bind_text(stmt, 1, repo_name) == 0) &&
        (bind_text(stmt, 2, symbol_base) == 0) &&
        (bind_text(stmt, 3, namespace_filter) == 0) &&
        (bind_text(stmt, 4, path_filter) == 0) &&
        (sqlite3_bind_int(stmt, 5, exclude_tests) == SQLITE_OK)) {
        found = sqlite3_step(stmt) == SQLITE_ROW;
    }
    (void)sqlite3_finalize(stmt);
    return found;
}

static const char *context_infer_java_namespace(CodeLensDb *db,
                                                const char *repo_name,
                                                const char *symbol_name,
                                                const char *symbol_base)
{
    sqlite3_stmt *stmt = nullptr;
    const char *separator;
    char *qualifier;
    char *found = nullptr;
    int step;

    if ((db == nullptr) || (repo_name == nullptr) || (symbol_name == nullptr) ||
        (symbol_base == nullptr) || (symbol_base == symbol_name) ||
        (strchr(symbol_name, '/') != nullptr) || (strstr(symbol_name, "->") != nullptr)) {
        return nullptr;
    }
    separator = strrchr(symbol_name, '#');
    if (separator == nullptr) {
        separator = strrchr(symbol_name, '.');
    }
    if ((separator == nullptr) || (separator == symbol_name) ||
        (strcmp(separator + 1, symbol_base) != 0)) {
        return nullptr;
    }
    qualifier = copy_bytes(symbol_name, (size_t)(separator - symbol_name));
    if ((qualifier == nullptr) ||
        (db_prepare(db,
                    "SELECT DISTINCT namespace FROM Symbol WHERE repo = ?1 AND name = ?2 "
                    "AND (namespace = ?3 OR namespace LIKE '%.' || ?3 "
                    "OR namespace = ?3 || '.' || ?2 "
                    "OR namespace LIKE '%.' || ?3 || '.' || ?2) "
                    "ORDER BY namespace LIMIT 2",
                    &stmt) != 0) || (bind_text(stmt, 1, repo_name) != 0) ||
        (bind_text(stmt, 2, symbol_base) != 0) || (bind_text(stmt, 3, qualifier) != 0)) {
        (void)sqlite3_finalize(stmt);
        return nullptr;
    }
    step = sqlite3_step(stmt);
    if (step == SQLITE_ROW) {
        const unsigned char *value = sqlite3_column_text(stmt, 0);
        int bytes = sqlite3_column_bytes(stmt, 0);

        if ((value != nullptr) && (bytes > 0)) {
            found = copy_bytes((const char *)value, (size_t)bytes);
        }
        if (sqlite3_step(stmt) == SQLITE_ROW) {
            found = nullptr;
        }
    }
    (void)sqlite3_finalize(stmt);
    return found;
}

static bool context_path_is_workspace_file(CodeLensDb *db,
                                           const char *repo_name,
                                           const char *path_filter)
{
    static const char sql[] =
        "SELECT 1 FROM File f WHERE f.repo = ?1 "
        "AND instr(lower(f.path), lower(?2)) > 0 "
        "AND NOT EXISTS (SELECT 1 FROM DependencyFile df WHERE df.repo = f.repo "
        "AND df.filePath = f.path) LIMIT 1";
    sqlite3_stmt *stmt = nullptr;
    bool found = false;

    if ((path_filter == nullptr) || (path_filter[0] == '\0')) {
        return false;
    }
    if ((db_prepare(db, sql, &stmt) == 0) && (bind_text(stmt, 1, repo_name) == 0) &&
        (bind_text(stmt, 2, path_filter) == 0)) {
        found = sqlite3_step(stmt) == SQLITE_ROW;
    }
    (void)sqlite3_finalize(stmt);
    return found;
}

/* Java type context is rendered as a bounded semantic dossier rather than the
 * generic definition/reference tables. The index intentionally stays
 * language-neutral: the dossier derives hierarchy, supporting types, and
 * usage categories from Java Symbol/Ref rows and current source text. */
#define CLASS_DOSSIER_MAX_TYPES 3U
#define CLASS_DOSSIER_MAX_MEMBER_LOAD 256U
#define CLASS_DOSSIER_MAX_MEMBERS CLASS_DOSSIER_MAX_MEMBER_LOAD
#define CLASS_DOSSIER_MAX_LINKS 16U
#define CLASS_DOSSIER_MAX_ANCESTORS 16U
#define CLASS_DOSSIER_MAX_OVERRIDES 24U
#define CLASS_DOSSIER_MAX_INHERITED 24U
#define CLASS_DOSSIER_MAX_INHERITED_METHOD_LOAD 512U
#define CLASS_DOSSIER_MAX_SUPPORTING 10U
#define CLASS_DOSSIER_MAX_USAGE_LOAD 512U
#define CLASS_DOSSIER_USAGES_PER_GROUP 5U
#define CLASS_DOSSIER_SOURCE_MAX 640U
#define CLASS_DOSSIER_SNIPPET_MAX 320U

typedef struct {
    char *name;
    char *kind;
    char *namespace_name;
    char *file_path;
    uint32_t start_line;
    uint32_t end_line;
    char *content;
    char *doc;
    char *scope;
    char *dependency;
    char *dependency_scope;
} ClassDossierSymbol;

typedef struct {
    char *relation;
    char *target_namespace;
    char *reference_name;
    ClassDossierSymbol definition;
} ClassDossierLink;

typedef struct {
    char *relation;
    char *current_name;
    char *current_content;
    char *other_namespace;
    char *other_name;
    char *other_content;
    char *other_path;
    uint32_t other_line;
    bool incoming;
} ClassDossierOverride;

typedef struct {
    ClassDossierSymbol definition;
    unsigned int depth;
} ClassDossierAncestor;

typedef struct {
    ClassDossierSymbol method;
    char *reason;
    unsigned int depth;
    int score;
} ClassDossierInherited;

typedef enum {
    CLASS_USAGE_CONSTRUCTION = 0,
    CLASS_USAGE_CALL,
    CLASS_USAGE_FIELD,
    CLASS_USAGE_TYPE_CHECK,
    CLASS_USAGE_TEST,
    CLASS_USAGE_OTHER,
    CLASS_USAGE_GROUP_COUNT
} ClassDossierUsageGroup;

typedef struct {
    ClassDossierUsageGroup group;
    char *path;
    uint32_t line;
    uint32_t column;
    char *symbol;
    char *base;
    char *scope;
    char *dependency;
    char *snippet;
    int score;
} ClassDossierUsage;

typedef enum {
    CLASS_DOSSIER_NOT_A_TYPE = 0,
    CLASS_DOSSIER_RENDERED,
    CLASS_DOSSIER_ERROR
} ClassDossierStatus;

static char *class_dossier_column_copy(sqlite3_stmt *stmt, int column)
{
    const unsigned char *value = sqlite3_column_text(stmt, column);
    int bytes = sqlite3_column_bytes(stmt, column);

    return copy_bytes(value == nullptr ? "" : (const char *)value,
                      value == nullptr ? 0U : (size_t)bytes);
}

static bool class_dossier_java_type_kind(const char *kind)
{
    return (kind != nullptr) &&
           ((strcmp(kind, "class") == 0) || (strcmp(kind, "interface") == 0) ||
            (strcmp(kind, "enum") == 0) || (strcmp(kind, "annotation") == 0) ||
            (strcmp(kind, "record") == 0));
}

static bool class_dossier_method_kind(const char *kind)
{
    return (kind != nullptr) &&
           ((strcmp(kind, "method") == 0) || (strcmp(kind, "test") == 0));
}

static bool class_dossier_fill_symbol(sqlite3_stmt *stmt,
                                      int first_column,
                                      ClassDossierSymbol *out)
{
    (void)memset(out, 0, sizeof(*out));
    out->name = class_dossier_column_copy(stmt, first_column);
    out->kind = class_dossier_column_copy(stmt, first_column + 1);
    out->namespace_name = class_dossier_column_copy(stmt, first_column + 2);
    out->file_path = class_dossier_column_copy(stmt, first_column + 3);
    out->start_line = (uint32_t)sqlite3_column_int64(stmt, first_column + 4);
    out->end_line = (uint32_t)sqlite3_column_int64(stmt, first_column + 5);
    out->content = class_dossier_column_copy(stmt, first_column + 6);
    out->doc = class_dossier_column_copy(stmt, first_column + 7);
    out->scope = class_dossier_column_copy(stmt, first_column + 8);
    out->dependency = class_dossier_column_copy(stmt, first_column + 9);
    out->dependency_scope = class_dossier_column_copy(stmt, first_column + 10);
    return (out->name != nullptr) && (out->kind != nullptr) &&
           (out->namespace_name != nullptr) && (out->file_path != nullptr) &&
           (out->content != nullptr) && (out->doc != nullptr) && (out->scope != nullptr) &&
           (out->dependency != nullptr) && (out->dependency_scope != nullptr);
}

static bool class_dossier_identifier_char(unsigned char ch)
{
    return isalnum(ch) || (ch == '_') || (ch == '$') || (ch >= 0x80U);
}

static bool class_dossier_token_equals(const char *start, size_t len, const char *word)
{
    return (strlen(word) == len) && (memcmp(start, word, len) == 0);
}

static bool class_dossier_has_token_before(const char *text,
                                           size_t limit,
                                           const char *word)
{
    size_t word_len = strlen(word);

    if (text == nullptr) {
        return false;
    }
    for (size_t i = 0U; (i + word_len) <= limit; i++) {
        bool left = (i == 0U) || !class_dossier_identifier_char((unsigned char)text[i - 1U]);
        bool right = (i + word_len == limit) ||
                     !class_dossier_identifier_char((unsigned char)text[i + word_len]);

        if (left && right && (memcmp(text + i, word, word_len) == 0)) {
            return true;
        }
    }
    return false;
}

static size_t class_dossier_newline_count(const char *text, size_t limit)
{
    size_t count = 0U;

    if (text == nullptr) {
        return 0U;
    }
    for (size_t i = 0U; (i < limit) && (text[i] != '\0'); i++) {
        count += text[i] == '\n' ? 1U : 0U;
    }
    return count;
}

/* Finds the declaration body rather than annotation-array braces. */
static size_t class_dossier_java_body_open(const char *text)
{
    size_t len;
    unsigned int paren_depth = 0U;
    unsigned int bracket_depth = 0U;
    bool in_string = false;
    bool in_character = false;
    bool in_line_comment = false;
    bool in_block_comment = false;
    bool escaped = false;

    if (text == nullptr) {
        return SIZE_MAX;
    }
    len = strlen(text);
    for (size_t i = 0U; i < len; i++) {
        unsigned char ch = (unsigned char)text[i];

        if (escaped) {
            escaped = false;
            continue;
        }
        if (in_string || in_character) {
            if (ch == '\\') {
                escaped = true;
            } else if (in_string && (ch == '"')) {
                in_string = false;
            } else if (in_character && (ch == '\'')) {
                in_character = false;
            }
            continue;
        }
        if (in_line_comment) {
            in_line_comment = ch != '\n';
            continue;
        }
        if (in_block_comment) {
            if ((ch == '*') && (i + 1U < len) && (text[i + 1U] == '/')) {
                in_block_comment = false;
                i++;
            }
            continue;
        }
        if ((ch == '/') && (i + 1U < len) && (text[i + 1U] == '/')) {
            in_line_comment = true;
            i++;
        } else if ((ch == '/') && (i + 1U < len) && (text[i + 1U] == '*')) {
            in_block_comment = true;
            i++;
        } else if (ch == '"') {
            in_string = true;
        } else if (ch == '\'') {
            in_character = true;
        } else if (ch == '(') {
            paren_depth++;
        } else if ((ch == ')') && (paren_depth > 0U)) {
            paren_depth--;
        } else if (ch == '[') {
            bracket_depth++;
        } else if ((ch == ']') && (bracket_depth > 0U)) {
            bracket_depth--;
        } else if ((ch == '{') && (paren_depth == 0U) && (bracket_depth == 0U)) {
            return i;
        }
    }
    return SIZE_MAX;
}

static char *class_dossier_compact_text(const char *text, size_t limit, bool add_ellipsis)
{
    StringBuilder out = {0};
    size_t len;
    bool pending_space = false;
    bool truncated = false;

    if (text == nullptr) {
        return copy_cstr("");
    }
    len = strlen(text);
    for (size_t i = 0U; i < len; i++) {
        unsigned char ch = (unsigned char)text[i];

        if (isspace(ch)) {
            pending_space = out.len > 0U;
            continue;
        }
        if (pending_space) {
            if (out.len + 1U >= limit) {
                truncated = true;
                break;
            }
            if (!sb_append(&out, " ")) {
                return nullptr;
            }
            pending_space = false;
        }
        if (out.len + 1U >= limit) {
            truncated = true;
            break;
        }
        if (!sb_append_len(&out, text + i, 1U)) {
            return nullptr;
        }
    }
    while ((out.len > 0U) && isspace((unsigned char)out.data[out.len - 1U])) {
        out.data[--out.len] = '\0';
    }
    if ((truncated || add_ellipsis) && !sb_append(&out, " ...")) {
        return nullptr;
    }
    return out.data == nullptr ? copy_cstr("") : out.data;
}

static char *class_dossier_member_source(const ClassDossierSymbol *member,
                                          bool prefer_signature)
{
    size_t len;
    size_t body;
    bool method_like;
    bool signature_only;
    char *prefix;
    char *compact;

    if ((member == nullptr) || (member->content == nullptr)) {
        return copy_cstr("");
    }
    len = strlen(member->content);
    method_like = class_dossier_method_kind(member->kind) ||
                  (strcmp(member->kind, "constructor") == 0);
    signature_only = method_like &&
                     (prefer_signature || (len > CLASS_DOSSIER_SOURCE_MAX) ||
                      (class_dossier_newline_count(member->content, len) > 6U));
    body = signature_only ? class_dossier_java_body_open(member->content) : SIZE_MAX;
    if (body != SIZE_MAX) {
        while ((body > 0U) && isspace((unsigned char)member->content[body - 1U])) {
            body--;
        }
        prefix = copy_bytes(member->content, body);
        if (prefix == nullptr) {
            return nullptr;
        }
        compact = class_dossier_compact_text(prefix, CLASS_DOSSIER_SOURCE_MAX, false);
        return compact == nullptr ? nullptr : alloc_printf("%s { ... }", compact);
    }
    return class_dossier_compact_text(member->content,
                                      CLASS_DOSSIER_SOURCE_MAX,
                                      len >= CLASS_DOSSIER_SOURCE_MAX);
}

/* Returns extends/implements/permits only when the referenced top-level type
 * occurs in that clause. Generic bounds and generic arguments are ignored. */
static const char *class_dossier_java_relation(const char *declaration,
                                               const char *reference_base)
{
    const char *relation = nullptr;
    size_t len;
    unsigned int angle_depth = 0U;

    if ((declaration == nullptr) || (reference_base == nullptr) ||
        (reference_base[0] == '\0')) {
        return nullptr;
    }
    len = class_dossier_java_body_open(declaration);
    if (len == SIZE_MAX) {
        len = strlen(declaration);
    }
    for (size_t i = 0U; i < len;) {
        unsigned char ch = (unsigned char)declaration[i];

        if (ch == '<') {
            angle_depth++;
            i++;
            continue;
        }
        if (ch == '>') {
            if (angle_depth > 0U) {
                angle_depth--;
            }
            i++;
            continue;
        }
        if (!class_dossier_identifier_char(ch)) {
            i++;
            continue;
        }
        size_t start = i;
        const char *token;
        size_t token_len;
        size_t previous = start;

        while ((i < len) && class_dossier_identifier_char((unsigned char)declaration[i])) {
            i++;
        }
        token = declaration + start;
        token_len = i - start;
        while ((previous > 0U) && isspace((unsigned char)declaration[previous - 1U])) {
            previous--;
        }
        if (angle_depth != 0U) {
            continue;
        }
        if (class_dossier_token_equals(token, token_len, "extends")) {
            relation = "extends";
        } else if (class_dossier_token_equals(token, token_len, "implements")) {
            relation = "implements";
        } else if (class_dossier_token_equals(token, token_len, "permits")) {
            relation = "permits";
        } else if ((relation != nullptr) &&
                   class_dossier_token_equals(token, token_len, reference_base) &&
                   ((previous == 0U) || (declaration[previous - 1U] != '@'))) {
            return relation;
        }
    }
    return nullptr;
}

static int class_dossier_load_type_candidates(CodeLensDb *db,
                                               const char *repo_name,
                                               const char *symbol_base,
                                               const char *namespace_filter,
                                               const char *path_filter,
                                               const char *definition_scope,
                                               int exclude_tests,
                                               ClassDossierSymbol *types,
                                               size_t *out_count,
                                               bool *out_more)
{
    static const char sql[] =
        "SELECT s.name, s.kind, s.namespace, s.filePath, s.startLine, s.endLine, "
        "s.content, s.doc, "
        "CASE WHEN df.id IS NULL THEN 'workspace' ELSE 'dependency' END, "
        "COALESCE(da.coordinate, ''), COALESCE(da.scope, '') "
        "FROM Symbol s LEFT JOIN DependencyFile df "
        "ON df.repo=s.repo AND df.filePath=s.filePath "
        "LEFT JOIN DependencyArtifact da ON da.id=df.artifactId "
        "WHERE s.repo=?1 AND s.name=?2 "
        "AND s.kind IN ('class','interface','enum','annotation','record') "
        "AND lower(substr(s.filePath, -5))='.java' "
        "AND (?3='' OR s.namespace=?3) "
        "AND (?4='' OR instr(lower(s.filePath), lower(?4))>0) "
        "AND ((?5='workspace' AND df.id IS NULL) "
        "OR (?5='dependencies' AND df.id IS NOT NULL)) "
        "AND (?6=0 OR (instr(lower(replace(s.filePath, '\\', '/')), '/test/')=0 "
        "AND instr(lower(replace(s.filePath, '\\', '/')), '/tests/')=0)) "
        "ORDER BY s.namespace, s.filePath, s.startLine LIMIT 4";
    sqlite3_stmt *stmt = nullptr;
    size_t count = 0U;
    int rc;

    *out_count = 0U;
    *out_more = false;
    if ((db_prepare(db, sql, &stmt) != 0) || (bind_text(stmt, 1, repo_name) != 0) ||
        (bind_text(stmt, 2, symbol_base) != 0) ||
        (bind_text(stmt, 3, namespace_filter) != 0) ||
        (bind_text(stmt, 4, path_filter) != 0) ||
        (bind_text(stmt, 5, definition_scope) != 0) ||
        (sqlite3_bind_int(stmt, 6, exclude_tests) != SQLITE_OK)) {
        (void)sqlite3_finalize(stmt);
        return -1;
    }
    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
        if (count >= CLASS_DOSSIER_MAX_TYPES) {
            *out_more = true;
            continue;
        }
        if (!class_dossier_fill_symbol(stmt, 0, &types[count])) {
            (void)sqlite3_finalize(stmt);
            return -1;
        }
        count++;
    }
    if (rc != SQLITE_DONE) {
        (void)report_sqlite_error(db->handle, "query Java type candidates");
        (void)sqlite3_finalize(stmt);
        return -1;
    }
    (void)sqlite3_finalize(stmt);
    *out_count = count;
    return 0;
}

static int class_dossier_load_members(CodeLensDb *db,
                                      const char *repo_name,
                                      const ClassDossierSymbol *type,
                                      int exclude_tests,
                                      ClassDossierSymbol *members,
                                      size_t *out_count,
                                      bool *out_more)
{
    static const char sql[] =
        "SELECT s.name, s.kind, s.namespace, s.filePath, s.startLine, s.endLine, "
        "s.content, s.doc, "
        "CASE WHEN df.id IS NULL THEN 'workspace' ELSE 'dependency' END, "
        "COALESCE(da.coordinate, ''), COALESCE(da.scope, '') "
        "FROM Symbol s LEFT JOIN DependencyFile df "
        "ON df.repo=s.repo AND df.filePath=s.filePath "
        "LEFT JOIN DependencyArtifact da ON da.id=df.artifactId "
        "WHERE s.repo=?1 AND ("
        "(s.namespace=?2 AND NOT (s.name=?3 AND s.kind=?4 "
        "AND s.filePath=?5 AND s.startLine=?6)) OR "
        "(s.filePath=?5 AND s.kind IN ('class','interface','enum','annotation','record') "
        "AND s.namespace LIKE ?2 || '.%' "
        "AND instr(substr(s.namespace, length(?2)+2), '.')=0)) "
        "AND (?7=0 OR (s.kind!='test' "
        "AND instr(lower(replace(s.filePath, '\\', '/')), '/test/')=0 "
        "AND instr(lower(replace(s.filePath, '\\', '/')), '/tests/')=0)) "
        "ORDER BY s.startLine, "
        "CASE s.kind WHEN 'field' THEN 0 WHEN 'enum_constant' THEN 1 "
        "WHEN 'constructor' THEN 2 WHEN 'method' THEN 3 WHEN 'test' THEN 4 ELSE 5 END, "
        "s.name LIMIT 257";
    sqlite3_stmt *stmt = nullptr;
    size_t count = 0U;
    int rc;

    *out_count = 0U;
    *out_more = false;
    if ((db_prepare(db, sql, &stmt) != 0) || (bind_text(stmt, 1, repo_name) != 0) ||
        (bind_text(stmt, 2, type->namespace_name) != 0) ||
        (bind_text(stmt, 3, type->name) != 0) || (bind_text(stmt, 4, type->kind) != 0) ||
        (bind_text(stmt, 5, type->file_path) != 0) ||
        (sqlite3_bind_int64(stmt, 6, (sqlite3_int64)type->start_line) != SQLITE_OK) ||
        (sqlite3_bind_int(stmt, 7, exclude_tests) != SQLITE_OK)) {
        (void)sqlite3_finalize(stmt);
        return -1;
    }
    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
        if (count >= CLASS_DOSSIER_MAX_MEMBER_LOAD) {
            *out_more = true;
            continue;
        }
        if (!class_dossier_fill_symbol(stmt, 0, &members[count])) {
            (void)sqlite3_finalize(stmt);
            return -1;
        }
        count++;
    }
    if (rc != SQLITE_DONE) {
        (void)report_sqlite_error(db->handle, "query Java type members");
        (void)sqlite3_finalize(stmt);
        return -1;
    }
    (void)sqlite3_finalize(stmt);
    *out_count = count;
    return 0;
}

static bool class_dossier_load_type_definition(CodeLensDb *db,
                                                const char *repo_name,
                                                const char *namespace_name,
                                                ClassDossierSymbol *out)
{
    static const char sql[] =
        "SELECT s.name, s.kind, s.namespace, s.filePath, s.startLine, s.endLine, "
        "s.content, s.doc, "
        "CASE WHEN df.id IS NULL THEN 'workspace' ELSE 'dependency' END, "
        "COALESCE(da.coordinate, ''), COALESCE(da.scope, '') "
        "FROM Symbol s LEFT JOIN DependencyFile df "
        "ON df.repo=s.repo AND df.filePath=s.filePath "
        "LEFT JOIN DependencyArtifact da ON da.id=df.artifactId "
        "WHERE s.repo=?1 AND s.namespace=?2 "
        "AND s.kind IN ('class','interface','enum','annotation','record') "
        "AND lower(substr(s.filePath, -5))='.java' "
        "ORDER BY CASE WHEN df.id IS NULL THEN 0 ELSE 1 END, s.startLine LIMIT 1";
    sqlite3_stmt *stmt = nullptr;
    bool found = false;

    (void)memset(out, 0, sizeof(*out));
    if ((db_prepare(db, sql, &stmt) == 0) && (bind_text(stmt, 1, repo_name) == 0) &&
        (bind_text(stmt, 2, namespace_name) == 0) &&
        (sqlite3_step(stmt) == SQLITE_ROW)) {
        found = class_dossier_fill_symbol(stmt, 0, out);
    }
    (void)sqlite3_finalize(stmt);
    return found;
}

static bool class_dossier_load_referenced_type_definition(
    CodeLensDb *db,
    const char *repo_name,
    const ClassDossierSymbol *source_type,
    const char *reference_name,
    const char *target_namespace,
    ClassDossierSymbol *out)
{
    static const char unique_sql[] =
        "SELECT s.name, s.kind, s.namespace, s.filePath, s.startLine, s.endLine, "
        "s.content, s.doc, "
        "CASE WHEN df.id IS NULL THEN 'workspace' ELSE 'dependency' END, "
        "COALESCE(da.coordinate, ''), COALESCE(da.scope, '') "
        "FROM Symbol s LEFT JOIN DependencyFile df "
        "ON df.repo=s.repo AND df.filePath=s.filePath "
        "LEFT JOIN DependencyArtifact da ON da.id=df.artifactId "
        "WHERE s.repo=?1 AND s.name=?2 "
        "AND s.kind IN ('class','interface','enum','annotation','record') "
        "AND lower(substr(s.filePath, -5))='.java' "
        "ORDER BY CASE WHEN df.id IS NULL THEN 0 ELSE 1 END, s.namespace LIMIT 2";
    sqlite3_stmt *stmt = nullptr;
    const char *separator;
    char *sibling_namespace = nullptr;
    bool found = false;

    if ((target_namespace != nullptr) && (target_namespace[0] != '\0') &&
        class_dossier_load_type_definition(db, repo_name, target_namespace, out)) {
        return true;
    }

    /* Syntax-only Java resolution can prefer a wildcard import over a type in
     * the current package. Recover the declaration from the lexical sibling
     * before considering a repository-wide unique simple name. */
    separator = (source_type == nullptr) || (source_type->namespace_name == nullptr)
                    ? nullptr
                    : strrchr(source_type->namespace_name, '.');
    if ((separator != nullptr) && (reference_name != nullptr) &&
        (reference_name[0] != '\0')) {
        sibling_namespace = alloc_printf("%.*s.%s",
                                         (int)(separator - source_type->namespace_name),
                                         source_type->namespace_name,
                                         reference_name);
        if ((sibling_namespace != nullptr) &&
            class_dossier_load_type_definition(db,
                                               repo_name,
                                               sibling_namespace,
                                               out)) {
            return true;
        }
    }

    (void)memset(out, 0, sizeof(*out));
    if ((reference_name == nullptr) || (reference_name[0] == '\0') ||
        (db_prepare(db, unique_sql, &stmt) != 0) ||
        (bind_text(stmt, 1, repo_name) != 0) ||
        (bind_text(stmt, 2, reference_name) != 0)) {
        (void)sqlite3_finalize(stmt);
        return false;
    }
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        found = class_dossier_fill_symbol(stmt, 0, out);
        if (sqlite3_step(stmt) == SQLITE_ROW) {
            found = false;
            (void)memset(out, 0, sizeof(*out));
        }
    }
    (void)sqlite3_finalize(stmt);
    return found;
}

static bool class_dossier_link_exists(const ClassDossierLink *links,
                                      size_t count,
                                      const char *relation,
                                      const char *target_namespace)
{
    for (size_t i = 0U; i < count; i++) {
        if ((strcmp(links[i].relation, relation) == 0) &&
            (strcmp(links[i].target_namespace, target_namespace) == 0)) {
            return true;
        }
    }
    return false;
}

static bool class_dossier_add_link(CodeLensDb *db,
                                   const char *repo_name,
                                   const ClassDossierSymbol *source_type,
                                   ClassDossierLink *links,
                                   size_t *count,
                                   const char *relation,
                                   const char *target_namespace,
                                   const char *reference_name)
{
    ClassDossierLink *link;
    ClassDossierSymbol definition;
    bool has_definition;
    const char *canonical_namespace;

    if ((target_namespace == nullptr) || (target_namespace[0] == '\0')) {
        return true;
    }
    has_definition = class_dossier_load_referenced_type_definition(db,
                                                                   repo_name,
                                                                   source_type,
                                                                   reference_name,
                                                                   target_namespace,
                                                                   &definition);
    canonical_namespace = has_definition ? definition.namespace_name : target_namespace;
    if (class_dossier_link_exists(links, *count, relation, canonical_namespace)) {
        return true;
    }
    if (*count >= CLASS_DOSSIER_MAX_LINKS) {
        return true;
    }
    link = &links[(*count)++];
    (void)memset(link, 0, sizeof(*link));
    link->relation = copy_cstr(relation);
    link->target_namespace = copy_cstr(canonical_namespace);
    link->reference_name = copy_cstr(reference_name == nullptr ? "" : reference_name);
    if ((link->relation == nullptr) || (link->target_namespace == nullptr) ||
        (link->reference_name == nullptr)) {
        return false;
    }
    if (has_definition) {
        link->definition = definition;
    }
    return true;
}

static int class_dossier_load_direct_links(CodeLensDb *db,
                                           const char *repo_name,
                                           const ClassDossierSymbol *type,
                                           ClassDossierLink *links,
                                           size_t *out_count)
{
    static const char sql[] =
        "SELECT r.symbolBase, r.targetNamespace FROM Ref r "
        "JOIN File f ON f.rowid=r.fileId "
        "WHERE f.repo=?1 AND f.path=?2 AND r.lineNumber>=?3 AND r.lineNumber<=?4 "
        "AND r.targetNamespace!='' ORDER BY r.lineNumber, r.columnNumber";
    sqlite3_stmt *stmt = nullptr;
    uint32_t header_end = type->start_line +
                          (uint32_t)class_dossier_newline_count(type->content,
                                                               strlen(type->content));
    size_t count = 0U;
    int rc;

    *out_count = 0U;
    if ((db_prepare(db, sql, &stmt) != 0) || (bind_text(stmt, 1, repo_name) != 0) ||
        (bind_text(stmt, 2, type->file_path) != 0) ||
        (sqlite3_bind_int64(stmt, 3, (sqlite3_int64)type->start_line) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 4, (sqlite3_int64)header_end) != SQLITE_OK)) {
        (void)sqlite3_finalize(stmt);
        return -1;
    }
    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
        const char *base = (const char *)sqlite3_column_text(stmt, 0);
        const char *target = (const char *)sqlite3_column_text(stmt, 1);
        const char *relation = class_dossier_java_relation(type->content, base);

        if ((relation != nullptr) && (target != nullptr) &&
            (strcmp(target, type->namespace_name) != 0) &&
            !class_dossier_add_link(db,
                                    repo_name,
                                    type,
                                    links,
                                    &count,
                                    relation,
                                    target,
                                    base)) {
            (void)sqlite3_finalize(stmt);
            return -1;
        }
    }
    if (rc != SQLITE_DONE) {
        (void)report_sqlite_error(db->handle, "query Java direct hierarchy");
        (void)sqlite3_finalize(stmt);
        return -1;
    }
    (void)sqlite3_finalize(stmt);
    *out_count = count;
    return 0;
}

static const char *class_dossier_inverse_relation(const char *relation)
{
    if (strcmp(relation, "extends") == 0) {
        return "extended by";
    }
    if (strcmp(relation, "implements") == 0) {
        return "implemented by";
    }
    if (strcmp(relation, "permits") == 0) {
        return "permitted by";
    }
    return nullptr;
}

static int class_dossier_load_incoming_links(CodeLensDb *db,
                                             const char *repo_name,
                                             const ClassDossierSymbol *type,
                                             ClassDossierLink *links,
                                             size_t *in_out_count)
{
    static const char sql[] =
        "SELECT child.name, child.kind, child.namespace, child.filePath, "
        "child.startLine, child.endLine, child.content, child.doc, "
        "CASE WHEN df.id IS NULL THEN 'workspace' ELSE 'dependency' END, "
        "COALESCE(da.coordinate, ''), COALESCE(da.scope, ''), r.symbolBase, "
        "r.targetNamespace "
        "FROM Ref r JOIN File f ON f.rowid=r.fileId "
        "JOIN Symbol child ON child.repo=f.repo AND child.filePath=f.path "
        "LEFT JOIN DependencyFile df ON df.repo=child.repo AND df.filePath=child.filePath "
        "LEFT JOIN DependencyArtifact da ON da.id=df.artifactId "
        "WHERE f.repo=?1 AND r.symbolBase=?2 "
        "AND child.kind IN ('class','interface','enum','annotation','record') "
        "AND lower(substr(child.filePath, -5))='.java' "
        "AND r.lineNumber>=child.startLine "
        "AND r.lineNumber<=child.startLine + "
        "(length(child.content)-length(replace(child.content, char(10), ''))) "
        "ORDER BY CASE WHEN df.id IS NULL THEN 0 ELSE 1 END, child.namespace LIMIT 128";
    sqlite3_stmt *stmt = nullptr;
    size_t count = *in_out_count;
    int rc;

    if ((db_prepare(db, sql, &stmt) != 0) || (bind_text(stmt, 1, repo_name) != 0) ||
        (bind_text(stmt, 2, type->name) != 0)) {
        (void)sqlite3_finalize(stmt);
        return -1;
    }
    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
        ClassDossierSymbol child;
        const char *base = (const char *)sqlite3_column_text(stmt, 11);
        const char *target = (const char *)sqlite3_column_text(stmt, 12);
        const char *relation;
        const char *inverse;
        ClassDossierSymbol resolved;
        ClassDossierLink *link;

        if (!class_dossier_fill_symbol(stmt, 0, &child)) {
            (void)sqlite3_finalize(stmt);
            return -1;
        }
        relation = class_dossier_java_relation(child.content, base);
        inverse = relation == nullptr ? nullptr : class_dossier_inverse_relation(relation);
        if ((inverse == nullptr) ||
            !class_dossier_load_referenced_type_definition(db,
                                                            repo_name,
                                                            &child,
                                                            base,
                                                            target,
                                                            &resolved) ||
            (strcmp(resolved.namespace_name, type->namespace_name) != 0) ||
            (strcmp(child.namespace_name, type->namespace_name) == 0) ||
            class_dossier_link_exists(links, count, inverse, child.namespace_name)) {
            continue;
        }
        if (count >= CLASS_DOSSIER_MAX_LINKS) {
            continue;
        }
        link = &links[count++];
        (void)memset(link, 0, sizeof(*link));
        link->relation = copy_cstr(inverse);
        link->target_namespace = copy_cstr(child.namespace_name);
        link->reference_name = copy_cstr(child.name);
        link->definition = child;
        if ((link->relation == nullptr) || (link->target_namespace == nullptr) ||
            (link->reference_name == nullptr)) {
            (void)sqlite3_finalize(stmt);
            return -1;
        }
    }
    if (rc != SQLITE_DONE) {
        (void)report_sqlite_error(db->handle, "query Java incoming hierarchy");
        (void)sqlite3_finalize(stmt);
        return -1;
    }
    (void)sqlite3_finalize(stmt);
    *in_out_count = count;
    return 0;
}

static bool class_dossier_parent_relation(const char *relation)
{
    return (relation != nullptr) &&
           ((strcmp(relation, "extends") == 0) ||
            (strcmp(relation, "implements") == 0));
}

static bool class_dossier_ancestor_exists(const ClassDossierAncestor *ancestors,
                                           size_t count,
                                           const char *namespace_name)
{
    for (size_t i = 0U; i < count; i++) {
        if (strcmp(ancestors[i].definition.namespace_name, namespace_name) == 0) {
            return true;
        }
    }
    return false;
}

/* Build a small breadth-first lineage for override and inherited-behavior
 * selection. The hierarchy section remains direct: transitive ancestors are
 * loaded only so a class such as CProjNode can connect its Node overrides and
 * calls without dumping Node's entire API. */
static int class_dossier_load_ancestors(CodeLensDb *db,
                                        const char *repo_name,
                                        const ClassDossierSymbol *type,
                                        const ClassDossierLink *direct_links,
                                        size_t direct_link_count,
                                        ClassDossierAncestor *ancestors,
                                        size_t *out_count,
                                        bool *out_more)
{
    size_t count = 0U;

    *out_count = 0U;
    *out_more = false;
    for (size_t i = 0U; i < direct_link_count; i++) {
        const ClassDossierLink *link = &direct_links[i];

        if (!class_dossier_parent_relation(link->relation) ||
            (link->definition.name == nullptr) ||
            (link->definition.namespace_name == nullptr) ||
            (link->definition.namespace_name[0] == '\0') ||
            (strcmp(link->definition.namespace_name, type->namespace_name) == 0) ||
            class_dossier_ancestor_exists(ancestors,
                                          count,
                                          link->definition.namespace_name)) {
            continue;
        }
        if (count >= CLASS_DOSSIER_MAX_ANCESTORS) {
            *out_more = true;
            continue;
        }
        ancestors[count].definition = link->definition;
        ancestors[count].depth = 1U;
        count++;
    }

    for (size_t index = 0U; index < count; index++) {
        ClassDossierLink parent_links[CLASS_DOSSIER_MAX_LINKS];
        size_t parent_count = 0U;

        if (class_dossier_load_direct_links(db,
                                            repo_name,
                                            &ancestors[index].definition,
                                            parent_links,
                                            &parent_count) != 0) {
            return -1;
        }
        for (size_t i = 0U; i < parent_count; i++) {
            const ClassDossierLink *link = &parent_links[i];

            if (!class_dossier_parent_relation(link->relation) ||
                (link->definition.name == nullptr) ||
                (link->definition.namespace_name == nullptr) ||
                (link->definition.namespace_name[0] == '\0') ||
                (strcmp(link->definition.namespace_name, type->namespace_name) == 0) ||
                class_dossier_ancestor_exists(ancestors,
                                              count,
                                              link->definition.namespace_name)) {
                continue;
            }
            if (count >= CLASS_DOSSIER_MAX_ANCESTORS) {
                *out_more = true;
                continue;
            }
            ancestors[count].definition = link->definition;
            ancestors[count].depth = ancestors[index].depth + 1U;
            count++;
        }
    }
    *out_count = count;
    return 0;
}

static bool class_dossier_method_bounds(const char *content,
                                        const char *name,
                                        size_t *out_name_start,
                                        size_t *out_open,
                                        size_t *out_close)
{
    size_t len;
    size_t name_len;

    if ((content == nullptr) || (name == nullptr)) {
        return false;
    }
    len = strlen(content);
    name_len = strlen(name);
    for (size_t i = 0U; (i + name_len) <= len; i++) {
        size_t open;
        unsigned int depth = 1U;
        bool in_string = false;
        bool in_character = false;
        bool escaped = false;

        if ((memcmp(content + i, name, name_len) != 0) ||
            ((i > 0U) && class_dossier_identifier_char((unsigned char)content[i - 1U])) ||
            ((i + name_len < len) &&
             class_dossier_identifier_char((unsigned char)content[i + name_len]))) {
            continue;
        }
        open = i + name_len;
        while ((open < len) && isspace((unsigned char)content[open])) {
            open++;
        }
        if ((open >= len) || (content[open] != '(')) {
            continue;
        }
        for (size_t pos = open + 1U; pos < len; pos++) {
            unsigned char ch = (unsigned char)content[pos];

            if (escaped) {
                escaped = false;
                continue;
            }
            if (in_string || in_character) {
                if (ch == '\\') {
                    escaped = true;
                } else if (in_string && (ch == '"')) {
                    in_string = false;
                } else if (in_character && (ch == '\'')) {
                    in_character = false;
                }
                continue;
            }
            if (ch == '"') {
                in_string = true;
            } else if (ch == '\'') {
                in_character = true;
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0U) {
                    *out_name_start = i;
                    *out_open = open;
                    *out_close = pos;
                    return true;
                }
            }
        }
    }
    return false;
}

static int class_dossier_method_arity(const ClassDossierSymbol *member)
{
    size_t name_start;
    size_t open;
    size_t close;
    unsigned int paren_depth = 0U;
    unsigned int angle_depth = 0U;
    unsigned int bracket_depth = 0U;
    int commas = 0;
    bool has_content = false;

    if ((member == nullptr) ||
        !class_dossier_method_bounds(member->content,
                                     member->name,
                                     &name_start,
                                     &open,
                                     &close)) {
        return -1;
    }
    (void)name_start;
    for (size_t i = open + 1U; i < close; i++) {
        unsigned char ch = (unsigned char)member->content[i];

        if (!isspace(ch)) {
            has_content = true;
        }
        if (ch == '(') {
            paren_depth++;
        } else if ((ch == ')') && (paren_depth > 0U)) {
            paren_depth--;
        } else if (ch == '<') {
            angle_depth++;
        } else if ((ch == '>') && (angle_depth > 0U)) {
            angle_depth--;
        } else if (ch == '[') {
            bracket_depth++;
        } else if ((ch == ']') && (bracket_depth > 0U)) {
            bracket_depth--;
        } else if ((ch == ',') && (paren_depth == 0U) && (angle_depth == 0U) &&
                   (bracket_depth == 0U)) {
            commas++;
        }
    }
    return has_content ? commas + 1 : 0;
}

static char *class_dossier_parameter_shape(const ClassDossierSymbol *member)
{
    StringBuilder shape = {0};
    size_t name_start;
    size_t open;
    size_t close;
    size_t segment_start;
    unsigned int angle_depth = 0U;
    unsigned int paren_depth = 0U;
    unsigned int bracket_depth = 0U;

    if ((member == nullptr) ||
        !class_dossier_method_bounds(member->content,
                                     member->name,
                                     &name_start,
                                     &open,
                                     &close)) {
        return nullptr;
    }
    (void)name_start;
    segment_start = open + 1U;
    for (size_t pos = segment_start; pos <= close; pos++) {
        unsigned char ch = pos < close ? (unsigned char)member->content[pos] : ',';
        bool delimiter = (ch == ',') && (angle_depth == 0U) &&
                         (paren_depth == 0U) && (bracket_depth == 0U);

        if (!delimiter) {
            if (ch == '<') {
                angle_depth++;
            } else if ((ch == '>') && (angle_depth > 0U)) {
                angle_depth--;
            } else if (ch == '(') {
                paren_depth++;
            } else if ((ch == ')') && (paren_depth > 0U)) {
                paren_depth--;
            } else if (ch == '[') {
                bracket_depth++;
            } else if ((ch == ']') && (bracket_depth > 0U)) {
                bracket_depth--;
            }
            continue;
        }

        bool segment_has_content = false;

        for (size_t scan = segment_start; scan < pos; scan++) {
            if (!isspace((unsigned char)member->content[scan])) {
                segment_has_content = true;
                break;
            }
        }
        if (!segment_has_content) {
            segment_start = pos + 1U;
            continue;
        }

        size_t first_identifier = SIZE_MAX;
        size_t previous_identifier = SIZE_MAX;
        size_t last_identifier = SIZE_MAX;
        size_t previous_length = 0U;
        size_t last_length = 0U;
        unsigned int local_angle = 0U;
        unsigned int local_paren = 0U;
        unsigned int array_depth = 0U;
        bool varargs = false;

        for (size_t scan = segment_start; scan < pos;) {
            unsigned char current = (unsigned char)member->content[scan];

            if (current == '<') {
                local_angle++;
                scan++;
                continue;
            }
            if ((current == '>') && (local_angle > 0U)) {
                local_angle--;
                scan++;
                continue;
            }
            if (current == '(') {
                local_paren++;
                scan++;
                continue;
            }
            if ((current == ')') && (local_paren > 0U)) {
                local_paren--;
                scan++;
                continue;
            }
            if ((local_angle == 0U) && (local_paren == 0U) &&
                (current == '[')) {
                array_depth++;
            }
            if ((local_angle == 0U) && (local_paren == 0U) &&
                (scan + 2U < pos) && (member->content[scan] == '.') &&
                (member->content[scan + 1U] == '.') &&
                (member->content[scan + 2U] == '.')) {
                varargs = true;
                scan += 3U;
                continue;
            }
            if ((local_angle != 0U) || (local_paren != 0U) ||
                !class_dossier_identifier_char(current)) {
                scan++;
                continue;
            }
            size_t token_start = scan;

            while ((scan < pos) && class_dossier_identifier_char(
                                       (unsigned char)member->content[scan])) {
                scan++;
            }
            first_identifier = first_identifier == SIZE_MAX ? token_start : first_identifier;
            previous_identifier = last_identifier;
            previous_length = last_length;
            last_identifier = token_start;
            last_length = scan - token_start;
        }
        (void)first_identifier;
        (void)last_identifier;
        (void)last_length;
        if (previous_identifier == SIZE_MAX) {
            return nullptr;
        }
        if ((shape.len > 0U) && !sb_append(&shape, ";")) {
            return nullptr;
        }
        if (!sb_append_len(&shape,
                           member->content + previous_identifier,
                           previous_length)) {
            return nullptr;
        }
        for (unsigned int dimension = 0U;
             dimension < array_depth + (varargs ? 1U : 0U);
             dimension++) {
            if (!sb_append(&shape, "[]")) {
                return nullptr;
            }
        }
        segment_start = pos + 1U;
    }
    return shape.data == nullptr ? copy_cstr("") : shape.data;
}

static char *class_dossier_short_signature(const char *content, const char *name)
{
    size_t name_start;
    size_t open;
    size_t close;
    char *slice;

    if (!class_dossier_method_bounds(content, name, &name_start, &open, &close)) {
        return copy_cstr(name == nullptr ? "" : name);
    }
    (void)open;
    slice = copy_bytes(content + name_start, close - name_start + 1U);
    return slice == nullptr ? nullptr : class_dossier_compact_text(slice, 240U, false);
}

static bool class_dossier_non_overridable(const ClassDossierSymbol *member)
{
    size_t body;
    size_t limit;

    if ((member == nullptr) || (member->content == nullptr)) {
        return true;
    }
    body = class_dossier_java_body_open(member->content);
    limit = body == SIZE_MAX ? strlen(member->content) : body;
    return class_dossier_has_token_before(member->content, limit, "static") ||
           class_dossier_has_token_before(member->content, limit, "private");
}

static int class_dossier_load_methods_for_namespace(CodeLensDb *db,
                                                    const char *repo_name,
                                                    const char *namespace_name,
                                                    int exclude_tests,
                                                    ClassDossierSymbol *methods,
                                                    size_t capacity,
                                                    size_t *out_count)
{
    static const char sql[] =
        "SELECT s.name, s.kind, s.namespace, s.filePath, s.startLine, s.endLine, "
        "s.content, s.doc, "
        "CASE WHEN df.id IS NULL THEN 'workspace' ELSE 'dependency' END, "
        "COALESCE(da.coordinate, ''), COALESCE(da.scope, '') "
        "FROM Symbol s LEFT JOIN DependencyFile df "
        "ON df.repo=s.repo AND df.filePath=s.filePath "
        "LEFT JOIN DependencyArtifact da ON da.id=df.artifactId "
        "WHERE s.repo=?1 AND s.namespace=?2 AND s.kind IN ('method','test') "
        "AND (?3=0 OR (s.kind!='test' "
        "AND instr(lower(replace(s.filePath, '\\', '/')), '/test/')=0 "
        "AND instr(lower(replace(s.filePath, '\\', '/')), '/tests/')=0)) "
        "ORDER BY s.name, s.startLine LIMIT 128";
    sqlite3_stmt *stmt = nullptr;
    size_t count = 0U;
    int rc;

    *out_count = 0U;
    if ((db_prepare(db, sql, &stmt) != 0) || (bind_text(stmt, 1, repo_name) != 0) ||
        (bind_text(stmt, 2, namespace_name) != 0) ||
        (sqlite3_bind_int(stmt, 3, exclude_tests) != SQLITE_OK)) {
        (void)sqlite3_finalize(stmt);
        return -1;
    }
    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
        if (count >= capacity) {
            continue;
        }
        if (!class_dossier_fill_symbol(stmt, 0, &methods[count])) {
            (void)sqlite3_finalize(stmt);
            return -1;
        }
        count++;
    }
    if (rc != SQLITE_DONE) {
        (void)report_sqlite_error(db->handle, "query related Java methods");
        (void)sqlite3_finalize(stmt);
        return -1;
    }
    (void)sqlite3_finalize(stmt);
    *out_count = count;
    return 0;
}

static bool class_dossier_override_exists(const ClassDossierOverride *items,
                                          size_t count,
                                          const ClassDossierSymbol *current,
                                          const ClassDossierSymbol *other,
                                          bool incoming)
{
    for (size_t i = 0U; i < count; i++) {
        if ((items[i].incoming == incoming) &&
            (strcmp(items[i].current_name, current->name) == 0) &&
            (strcmp(items[i].other_namespace, other->namespace_name) == 0) &&
            (strcmp(items[i].other_name, other->name) == 0) &&
            (items[i].other_line == other->start_line)) {
            return true;
        }
    }
    return false;
}

static bool class_dossier_add_override(ClassDossierOverride *items,
                                       size_t *count,
                                       const char *relation,
                                       const ClassDossierSymbol *current,
                                       const ClassDossierSymbol *other,
                                       bool incoming)
{
    ClassDossierOverride *item;

    if (class_dossier_override_exists(items, *count, current, other, incoming) ||
        (*count >= CLASS_DOSSIER_MAX_OVERRIDES)) {
        return true;
    }
    item = &items[(*count)++];
    item->relation = copy_cstr(relation);
    item->current_name = copy_cstr(current->name);
    item->current_content = copy_cstr(current->content);
    item->other_namespace = copy_cstr(other->namespace_name);
    item->other_name = copy_cstr(other->name);
    item->other_content = copy_cstr(other->content);
    item->other_path = copy_cstr(other->file_path);
    item->other_line = other->start_line;
    item->incoming = incoming;
    return (item->relation != nullptr) && (item->current_name != nullptr) &&
           (item->current_content != nullptr) && (item->other_namespace != nullptr) &&
           (item->other_name != nullptr) && (item->other_content != nullptr) &&
           (item->other_path != nullptr);
}

static bool class_dossier_methods_override_match(const ClassDossierSymbol *current,
                                                  const ClassDossierSymbol *other)
{
    int current_arity;
    int other_arity;

    if (!class_dossier_method_kind(current->kind) ||
        !class_dossier_method_kind(other->kind) ||
        class_dossier_non_overridable(current) ||
        class_dossier_non_overridable(other) ||
        (strcmp(current->name, other->name) != 0)) {
        return false;
    }
    current_arity = class_dossier_method_arity(current);
    other_arity = class_dossier_method_arity(other);
    if ((current_arity < 0) || (current_arity != other_arity)) {
        return false;
    }
    char *current_shape = class_dossier_parameter_shape(current);
    char *other_shape = class_dossier_parameter_shape(other);

    return (current_shape != nullptr) && (other_shape != nullptr) &&
           (strcmp(current_shape, other_shape) == 0);
}

static int class_dossier_load_overrides(CodeLensDb *db,
                                        const char *repo_name,
                                        const ClassDossierSymbol *type,
                                        const ClassDossierSymbol *members,
                                        size_t member_count,
                                        const ClassDossierLink *links,
                                        size_t link_count,
                                        const ClassDossierAncestor *ancestors,
                                        size_t ancestor_count,
                                        int exclude_tests,
                                        ClassDossierOverride *items,
                                        size_t *out_count)
{
    ClassDossierSymbol related[128];
    size_t count = 0U;

    /* A Java override may target a grandparent declaration. Compare against
     * the bounded lineage rather than only the direct superclass. */
    for (size_t ancestor_index = 0U; ancestor_index < ancestor_count;
         ancestor_index++) {
        const ClassDossierAncestor *ancestor = &ancestors[ancestor_index];
        size_t related_count = 0U;
        const char *relation =
            (strcmp(ancestor->definition.kind, "interface") == 0) &&
                    (strcmp(type->kind, "interface") != 0)
                ? "implements"
                : "overrides";

        if (class_dossier_load_methods_for_namespace(
                db,
                repo_name,
                ancestor->definition.namespace_name,
                exclude_tests,
                related,
                sizeof(related) / sizeof(related[0]),
                &related_count) != 0) {
            return -1;
        }
        for (size_t member_index = 0U; member_index < member_count; member_index++) {
            const ClassDossierSymbol *member = &members[member_index];

            for (size_t related_index = 0U; related_index < related_count;
                 related_index++) {
                if (class_dossier_methods_override_match(member,
                                                         &related[related_index]) &&
                    !class_dossier_add_override(items,
                                                &count,
                                                relation,
                                                member,
                                                &related[related_index],
                                                false)) {
                    return -1;
                }
            }
        }
    }

    /* Direct known subtypes are useful too, but do not recursively enumerate
     * a potentially huge descendant tree. */
    for (size_t link_index = 0U; link_index < link_count; link_index++) {
        const ClassDossierLink *link = &links[link_index];
        bool incoming = (strcmp(link->relation, "extended by") == 0) ||
                        (strcmp(link->relation, "implemented by") == 0);
        size_t related_count = 0U;
        const char *relation;

        if (!incoming) {
            continue;
        }
        relation = (strcmp(link->relation, "implemented by") == 0) ||
                           (strcmp(type->kind, "interface") == 0)
                       ? "implements"
                       : "overrides";
        if (class_dossier_load_methods_for_namespace(db,
                                                     repo_name,
                                                     link->target_namespace,
                                                     exclude_tests,
                                                     related,
                                                     sizeof(related) / sizeof(related[0]),
                                                     &related_count) != 0) {
            return -1;
        }
        for (size_t member_index = 0U; member_index < member_count; member_index++) {
            const ClassDossierSymbol *member = &members[member_index];

            for (size_t related_index = 0U; related_index < related_count;
                 related_index++) {
                if (class_dossier_methods_override_match(member,
                                                         &related[related_index]) &&
                    !class_dossier_add_override(items,
                                                &count,
                                                relation,
                                                member,
                                                &related[related_index],
                                                true)) {
                    return -1;
                }
            }
        }
    }
    *out_count = count;
    return 0;
}

static bool class_dossier_signature_contains(const ClassDossierSymbol *member,
                                             const char *base,
                                             uint32_t reference_line)
{
    size_t limit;
    uint32_t signature_end_line;

    if ((member == nullptr) || (base == nullptr) || (member->content == nullptr)) {
        return false;
    }
    limit = class_dossier_java_body_open(member->content);
    if (limit == SIZE_MAX) {
        limit = strlen(member->content);
    }
    if ((strcmp(member->kind, "field") == 0) ||
        (strcmp(member->kind, "enum_constant") == 0)) {
        for (size_t i = 0U; i < limit; i++) {
            if (member->content[i] == '=') {
                limit = i;
                break;
            }
        }
    }
    signature_end_line = member->start_line +
                         (uint32_t)class_dossier_newline_count(member->content, limit);
    return (reference_line >= member->start_line) && (reference_line <= signature_end_line) &&
           class_dossier_has_token_before(member->content, limit, base);
}

static bool class_dossier_target_in_links(const ClassDossierLink *links,
                                          size_t count,
                                          const char *target_namespace)
{
    for (size_t i = 0U; i < count; i++) {
        if (strcmp(links[i].target_namespace, target_namespace) == 0) {
            return true;
        }
    }
    return false;
}

static const ClassDossierSymbol *class_dossier_member_at_line(
    const ClassDossierSymbol *members,
    size_t count,
    uint32_t line)
{
    const ClassDossierSymbol *best = nullptr;

    for (size_t i = 0U; i < count; i++) {
        if ((line < members[i].start_line) || (line > members[i].end_line)) {
            continue;
        }
        if ((best == nullptr) ||
            ((members[i].end_line - members[i].start_line) <
             (best->end_line - best->start_line))) {
            best = &members[i];
        }
    }
    return best;
}

static char *class_dossier_support_relation(const ClassDossierSymbol *owner,
                                             bool in_signature)
{
    if (!in_signature) {
        if (strcmp(owner->kind, "field") == 0) {
            return alloc_printf("field initializer `%s`", owner->name);
        }
        return alloc_printf("used by `%s`", owner->name);
    }
    if (strcmp(owner->kind, "field") == 0) {
        return copy_cstr("field type");
    }
    if (strcmp(owner->kind, "constructor") == 0) {
        return copy_cstr("constructor signature");
    }
    if (class_dossier_method_kind(owner->kind)) {
        return copy_cstr("method signature");
    }
    return copy_cstr("member declaration");
}

static int class_dossier_load_supporting(CodeLensDb *db,
                                         const char *repo_name,
                                         const ClassDossierSymbol *type,
                                         const ClassDossierSymbol *members,
                                         size_t member_count,
                                         const ClassDossierLink *links,
                                         size_t link_count,
                                         ClassDossierLink *supporting,
                                         size_t *out_count)
{
    static const char sql[] =
        "SELECT r.symbolBase, r.targetNamespace, r.lineNumber FROM Ref r "
        "JOIN File f ON f.rowid=r.fileId "
        "WHERE f.repo=?1 AND f.path=?2 AND r.lineNumber>=?3 AND r.lineNumber<=?4 "
        "AND r.targetNamespace!='' ORDER BY r.lineNumber, r.columnNumber LIMIT 512";
    sqlite3_stmt *stmt = nullptr;
    size_t count = 0U;
    int rc;

    *out_count = 0U;
    if ((db_prepare(db, sql, &stmt) != 0) || (bind_text(stmt, 1, repo_name) != 0) ||
        (bind_text(stmt, 2, type->file_path) != 0) ||
        (sqlite3_bind_int64(stmt, 3, (sqlite3_int64)type->start_line) != SQLITE_OK) ||
        (sqlite3_bind_int64(stmt, 4, (sqlite3_int64)type->end_line) != SQLITE_OK)) {
        (void)sqlite3_finalize(stmt);
        return -1;
    }
    /* Behavior references outrank signature-only types when the cap is hit.
     * Each pass preserves source order within its relevance tier. */
    for (unsigned int pass = 0U; pass < 2U; pass++) {
        if ((pass > 0U) && (sqlite3_reset(stmt) != SQLITE_OK)) {
            (void)sqlite3_finalize(stmt);
            return -1;
        }
        while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
            const char *base = (const char *)sqlite3_column_text(stmt, 0);
            const char *target = (const char *)sqlite3_column_text(stmt, 1);
            uint32_t line = (uint32_t)sqlite3_column_int64(stmt, 2);
            const ClassDossierSymbol *owner;
            ClassDossierSymbol definition;
            bool in_signature;

            if ((base == nullptr) || (target == nullptr) ||
                (strcmp(target, type->namespace_name) == 0) ||
                class_dossier_target_in_links(links, link_count, target) ||
                class_dossier_target_in_links(supporting, count, target)) {
                continue;
            }
            owner = class_dossier_member_at_line(members, member_count, line);
            in_signature = (owner != nullptr) &&
                           class_dossier_signature_contains(owner, base, line);
            if ((owner == nullptr) ||
                (((pass == 0U) && in_signature) ||
                 ((pass == 1U) && !in_signature)) ||
                !class_dossier_load_referenced_type_definition(db,
                                                                repo_name,
                                                                type,
                                                                base,
                                                                target,
                                                                &definition)) {
                continue;
            }
            if (class_dossier_target_in_links(links,
                                              link_count,
                                              definition.namespace_name) ||
                class_dossier_target_in_links(supporting,
                                              count,
                                              definition.namespace_name)) {
                continue;
            }
            if (count >= CLASS_DOSSIER_MAX_SUPPORTING) {
                continue;
            }
            supporting[count].relation =
                class_dossier_support_relation(owner, in_signature);
            supporting[count].target_namespace = copy_cstr(definition.namespace_name);
            supporting[count].reference_name = copy_cstr(base);
            supporting[count].definition = definition;
            if ((supporting[count].relation == nullptr) ||
                (supporting[count].target_namespace == nullptr) ||
                (supporting[count].reference_name == nullptr)) {
                (void)sqlite3_finalize(stmt);
                return -1;
            }
            count++;
        }
        if (rc != SQLITE_DONE) {
            (void)report_sqlite_error(db->handle,
                                      "query Java supporting definitions");
            (void)sqlite3_finalize(stmt);
            return -1;
        }
    }
    (void)sqlite3_finalize(stmt);
    *out_count = count;
    return 0;
}

static const char *class_dossier_member_kind_for_name(const ClassDossierSymbol *members,
                                                       size_t count,
                                                       const char *name)
{
    for (size_t i = 0U; i < count; i++) {
        if (strcmp(members[i].name, name) == 0) {
            return members[i].kind;
        }
    }
    return nullptr;
}

static bool class_dossier_reference_preceded_by(const CodeLensMappedFile *file,
                                                uint32_t start_byte,
                                                const char *word)
{
    size_t pos = (size_t)start_byte;
    size_t end;
    size_t start;

    if ((file == nullptr) || (file->data == nullptr) || (pos > file->len)) {
        return false;
    }
    while ((pos > 0U) &&
           (class_dossier_identifier_char((unsigned char)file->data[pos - 1U]) ||
            (file->data[pos - 1U] == '.'))) {
        pos--;
    }
    while ((pos > 0U) && isspace((unsigned char)file->data[pos - 1U])) {
        pos--;
    }
    end = pos;
    while ((pos > 0U) && class_dossier_identifier_char((unsigned char)file->data[pos - 1U])) {
        pos--;
    }
    start = pos;
    return class_dossier_token_equals(file->data + start, end - start, word);
}

static bool class_dossier_reference_followed_by(const CodeLensMappedFile *file,
                                                uint32_t end_byte,
                                                const char *text)
{
    size_t pos = (size_t)end_byte;
    size_t text_len = strlen(text);

    if ((file == nullptr) || (file->data == nullptr) || (pos > file->len)) {
        return false;
    }
    while ((pos < file->len) && isspace((unsigned char)file->data[pos])) {
        pos++;
    }
    return (text_len <= file->len - pos) && (memcmp(file->data + pos, text, text_len) == 0);
}

static bool class_dossier_reference_has_foreign_receiver(
    const CodeLensMappedFile *file,
    uint32_t start_byte)
{
    size_t pos = (size_t)start_byte;
    size_t end;
    size_t start;

    if ((file == nullptr) || (file->data == nullptr) || (pos > file->len)) {
        return false;
    }
    while ((pos > 0U) && isspace((unsigned char)file->data[pos - 1U])) {
        pos--;
    }
    if ((pos == 0U) || (file->data[pos - 1U] != '.')) {
        return false;
    }
    pos--;
    while ((pos > 0U) && isspace((unsigned char)file->data[pos - 1U])) {
        pos--;
    }
    end = pos;
    while ((pos > 0U) && class_dossier_identifier_char(
                              (unsigned char)file->data[pos - 1U])) {
        pos--;
    }
    start = pos;
    return !class_dossier_token_equals(file->data + start, end - start, "this") &&
           !class_dossier_token_equals(file->data + start, end - start, "super");
}

static bool class_dossier_reference_is_cast(const CodeLensMappedFile *file,
                                            uint32_t start_byte,
                                            uint32_t end_byte)
{
    size_t before = (size_t)start_byte;
    size_t after = (size_t)end_byte;

    if ((file == nullptr) || (file->data == nullptr) || (before > file->len) ||
        (after > file->len)) {
        return false;
    }
    while ((before > 0U) &&
           (class_dossier_identifier_char((unsigned char)file->data[before - 1U]) ||
            (file->data[before - 1U] == '.'))) {
        before--;
    }
    while ((before > 0U) && isspace((unsigned char)file->data[before - 1U])) {
        before--;
    }
    while ((after < file->len) && isspace((unsigned char)file->data[after])) {
        after++;
    }
    return (before > 0U) && (file->data[before - 1U] == '(') && (after < file->len) &&
           (file->data[after] == ')');
}

static char *class_dossier_source_line(const CodeLensMappedFile *file,
                                       uint32_t start_byte,
                                       uint32_t end_byte)
{
    size_t start;
    size_t end;
    char *line;

    if ((file == nullptr) || (file->data == nullptr) ||
        ((size_t)start_byte > (size_t)end_byte) || ((size_t)end_byte > file->len)) {
        return copy_cstr("snippet unavailable");
    }
    start = line_start_before(file->data, (size_t)start_byte);
    end = line_end_after(file->data, file->len, (size_t)end_byte);
    while ((start < end) && isspace((unsigned char)file->data[start])) {
        start++;
    }
    while ((end > start) && isspace((unsigned char)file->data[end - 1U])) {
        end--;
    }
    line = copy_bytes(file->data + start, end - start);
    return line == nullptr
               ? nullptr
               : class_dossier_compact_text(line, CLASS_DOSSIER_SNIPPET_MAX, false);
}

static bool class_dossier_usage_receiver_matches_type(
    const CodeLensMappedFile *file,
    uint32_t start_byte,
    const char *symbol,
    const char *type_name)
{
    const char *dot;
    const char *receiver;
    size_t receiver_len;
    size_t type_len;
    size_t end;
    size_t begin;

    if ((file == nullptr) || (file->data == nullptr) || (symbol == nullptr) ||
        (type_name == nullptr) || ((dot = strrchr(symbol, '.')) == nullptr)) {
        return false;
    }
    receiver = dot;
    while ((receiver > symbol) &&
           class_dossier_identifier_char((unsigned char)receiver[-1])) {
        receiver--;
    }
    receiver_len = (size_t)(dot - receiver);
    if (receiver_len == 0U) {
        return false;
    }
    if (class_dossier_token_equals(receiver, receiver_len, type_name)) {
        return true;
    }

    end = (size_t)start_byte > file->len ? file->len : (size_t)start_byte;
    begin = end > 8192U ? end - 8192U : 0U;
    type_len = strlen(type_name);
    for (size_t pos = begin; (pos + type_len) <= end; pos++) {
        size_t scan;
        unsigned int angle_depth = 0U;

        if ((memcmp(file->data + pos, type_name, type_len) != 0) ||
            ((pos > 0U) && class_dossier_identifier_char(
                              (unsigned char)file->data[pos - 1U])) ||
            ((pos + type_len < file->len) && class_dossier_identifier_char(
                                                (unsigned char)file->data[pos + type_len]))) {
            continue;
        }
        scan = pos + type_len;
        while ((scan < end) && isspace((unsigned char)file->data[scan])) {
            scan++;
        }
        if ((scan < end) && (file->data[scan] == '<')) {
            do {
                if (file->data[scan] == '<') {
                    angle_depth++;
                } else if ((file->data[scan] == '>') && (angle_depth > 0U)) {
                    angle_depth--;
                }
                scan++;
            } while ((scan < end) && (angle_depth > 0U));
            while ((scan < end) && isspace((unsigned char)file->data[scan])) {
                scan++;
            }
        }
        while ((scan + 1U < end) && (file->data[scan] == '[') &&
               (file->data[scan + 1U] == ']')) {
            scan += 2U;
            while ((scan < end) && isspace((unsigned char)file->data[scan])) {
                scan++;
            }
        }
        if ((receiver_len <= end - scan) &&
            (memcmp(file->data + scan, receiver, receiver_len) == 0) &&
            ((scan + receiver_len == file->len) ||
             !class_dossier_identifier_char(
                 (unsigned char)file->data[scan + receiver_len]))) {
            return true;
        }
    }
    return false;
}

static ClassDossierUsageGroup class_dossier_classify_usage(
    const CodeLensMappedFile *file,
    uint32_t start_byte,
    uint32_t end_byte,
    const char *base,
    const char *type_name,
    const char *member_kind,
    bool is_test)
{
    if (is_test) {
        return CLASS_USAGE_TEST;
    }
    if ((strcmp(base, type_name) == 0) &&
        (class_dossier_reference_preceded_by(file, start_byte, "new") ||
         class_dossier_reference_followed_by(file, end_byte, "::new"))) {
        return CLASS_USAGE_CONSTRUCTION;
    }
    if ((strcmp(base, type_name) == 0) &&
        (class_dossier_reference_preceded_by(file, start_byte, "instanceof") ||
         class_dossier_reference_preceded_by(file, start_byte, "case") ||
         class_dossier_reference_is_cast(file, start_byte, end_byte) ||
         class_dossier_reference_followed_by(file, end_byte, ".class"))) {
        return CLASS_USAGE_TYPE_CHECK;
    }
    if ((member_kind != nullptr) && class_dossier_method_kind(member_kind)) {
        return CLASS_USAGE_CALL;
    }
    if ((member_kind != nullptr) &&
        ((strcmp(member_kind, "field") == 0) ||
         (strcmp(member_kind, "enum_constant") == 0))) {
        return CLASS_USAGE_FIELD;
    }
    if ((strcmp(base, type_name) != 0) &&
        class_dossier_reference_followed_by(file, end_byte, "(")) {
        return CLASS_USAGE_CALL;
    }
    return CLASS_USAGE_OTHER;
}

static bool class_dossier_text_contains_case(const char *text, const char *needle)
{
    size_t needle_len;

    if ((text == nullptr) || (needle == nullptr) || (needle[0] == '\0')) {
        return false;
    }
    needle_len = strlen(needle);
    for (size_t i = 0U; text[i] != '\0'; i++) {
        size_t matched = 0U;

        while ((matched < needle_len) && (text[i + matched] != '\0') &&
               (tolower((unsigned char)text[i + matched]) ==
                tolower((unsigned char)needle[matched]))) {
            matched++;
        }
        if (matched == needle_len) {
            return true;
        }
    }
    return false;
}

static int class_dossier_usage_score(ClassDossierUsageGroup group,
                                     const char *scope,
                                     const char *path,
                                     const char *type_path,
                                     const char *snippet,
                                     const char *member_kind)
{
    static const int group_scores[CLASS_USAGE_GROUP_COUNT] = {100, 85, 75, 95, 80, 55};
    bool has_snippet = (snippet != nullptr) &&
                       (strcmp(snippet, "snippet unavailable") != 0);
    int score = group_scores[group];

    score += strcmp(scope, "workspace") == 0 ? 40 : 0;
    score += strcmp(path, type_path) == 0 ? -20 : 15;
    score += has_snippet ? 5 : 0;
    if (!has_snippet) {
        return score;
    }

    /* Lexical intent signals break the many same-scope ties without relying
     * on file order. They deliberately remain small compared with scope and
     * purpose so ranking stays predictable. */
    if ((strchr(snippet, '=') != nullptr) ||
        class_dossier_text_contains_case(snippet, "return ")) {
        score += 6;
    }
    if ((strlen(snippet) >= 24U) && (strlen(snippet) <= 240U)) {
        score += 3;
    }
    switch (group) {
        case CLASS_USAGE_CONSTRUCTION:
            score += strchr(snippet, '"') != nullptr ? 6 : 0;
            score += class_dossier_text_contains_case(snippet, "return ") ? 4 : 0;
            break;
        case CLASS_USAGE_CALL:
            score += (member_kind != nullptr) ? 8 : 0;
            score += class_dossier_text_contains_case(snippet, "if (") ? 3 : 0;
            break;
        case CLASS_USAGE_FIELD:
            score += strcmp(path, type_path) == 0 ? 0 : 8;
            break;
        case CLASS_USAGE_TYPE_CHECK:
            score += class_dossier_text_contains_case(snippet, "if (") ? 8 : 0;
            score += class_dossier_text_contains_case(snippet, "switch") ? 5 : 0;
            break;
        case CLASS_USAGE_TEST:
            score += class_dossier_text_contains_case(snippet, "assert") ? 18 : 0;
            score += (class_dossier_text_contains_case(snippet, "expect") ||
                      class_dossier_text_contains_case(snippet, "verif") ||
                      class_dossier_text_contains_case(snippet, "should"))
                         ? 10
                         : 0;
            break;
        case CLASS_USAGE_OTHER:
        case CLASS_USAGE_GROUP_COUNT:
            break;
    }
    return score;
}

static int class_dossier_compare_usages(const void *left_value, const void *right_value)
{
    const ClassDossierUsage *left = left_value;
    const ClassDossierUsage *right = right_value;
    int path_order;

    if (left->group != right->group) {
        return left->group < right->group ? -1 : 1;
    }
    if (left->score != right->score) {
        return left->score > right->score ? -1 : 1;
    }
    path_order = strcmp(left->path, right->path);
    if (path_order != 0) {
        return path_order;
    }
    if (left->line != right->line) {
        return left->line < right->line ? -1 : 1;
    }
    return left->column < right->column ? -1 : left->column != right->column;
}

static int class_dossier_load_usages(CodeLensDb *db,
                                     const char *repo_name,
                                     const ClassDossierSymbol *type,
                                     const ClassDossierSymbol *members,
                                     size_t member_count,
                                     int exclude_tests,
                                     ClassDossierUsage *usages,
                                     size_t *out_count,
                                     bool *out_more)
{
    static const char sql[] =
        "WITH type_files(fileId) AS MATERIALIZED ("
        "SELECT DISTINCT tr.fileId FROM Ref tr "
        "WHERE tr.symbolBase=?4 AND tr.targetNamespace=?2) "
        "SELECT f.path, r.lineNumber, r.columnNumber, r.startByte, r.endByte, "
        "r.symbol, r.symbolBase, "
        "CASE WHEN df.id IS NULL THEN 'workspace' ELSE 'dependency' END, "
        "COALESCE(da.coordinate, ''), "
        "CASE WHEN r.targetNamespace='' AND EXISTS (SELECT 1 FROM Referred erf "
        "WHERE erf.repo=f.repo AND erf.filePath=f.path AND erf.namespace=?2 "
        "AND (erf.symbol=r.symbolBase OR erf.symbol=':all')) THEN ?2 "
        "ELSE r.targetNamespace END, "
        "CASE WHEN instr(lower(replace(f.path, '\\', '/')), '/test/')>0 "
        "OR instr(lower(replace(f.path, '\\', '/')), '/tests/')>0 "
        "OR EXISTS (SELECT 1 FROM Symbol tst "
        "WHERE tst.rowid BETWEEN f.symbolFirst AND f.symbolFirst+f.symbolCount-1 "
        "AND tst.repo=f.repo AND tst.filePath=f.path AND tst.kind='test' "
        "AND r.lineNumber BETWEEN tst.startLine AND tst.endLine) THEN 1 ELSE 0 END "
        "FROM Ref r JOIN File f ON f.rowid=r.fileId "
        "LEFT JOIN DependencyFile df ON df.repo=f.repo AND df.filePath=f.path "
        "LEFT JOIN DependencyArtifact da ON da.id=df.artifactId "
        "WHERE f.repo=?1 AND (r.targetNamespace=?2 OR "
        "(r.targetNamespace='' AND EXISTS (SELECT 1 FROM Referred rf "
        "WHERE rf.repo=f.repo AND rf.filePath=f.path AND rf.namespace=?2 "
        "AND (rf.symbol=r.symbolBase OR rf.symbol=':all'))) OR "
        "(r.targetNamespace='' AND EXISTS (SELECT 1 FROM Symbol ms "
        "WHERE ms.repo=f.repo AND ms.namespace=?2 AND ms.name=r.symbolBase "
        "AND ms.kind IN ('method','test','field','enum_constant')) "
        "AND r.fileId IN (SELECT fileId FROM type_files))) "
        "AND (?3=0 OR (instr(lower(replace(f.path, '\\', '/')), '/test/')=0 "
        "AND instr(lower(replace(f.path, '\\', '/')), '/tests/')=0 "
        /* A File's symbols occupy a contiguous rowid range, including after an
         * incremental refresh. Without the range SQLite scans every Symbol
         * for each Ref when exclude-tests is enabled. */
        "AND NOT EXISTS (SELECT 1 FROM Symbol tst "
        "WHERE tst.rowid BETWEEN f.symbolFirst AND f.symbolFirst+f.symbolCount-1 "
        "AND tst.repo=f.repo AND tst.filePath=f.path AND tst.kind='test' "
        "AND r.lineNumber BETWEEN tst.startLine AND tst.endLine))) "
        "ORDER BY CASE WHEN df.id IS NULL THEN 0 ELSE 1 END, "
        "CASE WHEN r.targetNamespace=?2 THEN 0 ELSE 1 END, f.path, r.lineNumber, "
        "r.columnNumber LIMIT 513";
    sqlite3_stmt *stmt = nullptr;
    CodeLensMappedFile mapped = {0};
    char *mapped_path = nullptr;
    bool has_mapping = false;
    size_t count = 0U;
    int rc;

    *out_count = 0U;
    *out_more = false;
    if ((db_prepare(db, sql, &stmt) != 0) || (bind_text(stmt, 1, repo_name) != 0) ||
        (bind_text(stmt, 2, type->namespace_name) != 0) ||
        (sqlite3_bind_int(stmt, 3, exclude_tests) != SQLITE_OK) ||
        (bind_text(stmt, 4, type->name) != 0)) {
        (void)sqlite3_finalize(stmt);
        return -1;
    }
    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
        const char *path = (const char *)sqlite3_column_text(stmt, 0);
        uint32_t line = (uint32_t)sqlite3_column_int64(stmt, 1);
        uint32_t column = (uint32_t)sqlite3_column_int64(stmt, 2);
        uint32_t start_byte = (uint32_t)sqlite3_column_int64(stmt, 3);
        uint32_t end_byte = (uint32_t)sqlite3_column_int64(stmt, 4);
        const char *symbol = (const char *)sqlite3_column_text(stmt, 5);
        const char *base = (const char *)sqlite3_column_text(stmt, 6);
        const char *scope = (const char *)sqlite3_column_text(stmt, 7);
        const char *dependency = (const char *)sqlite3_column_text(stmt, 8);
        const char *target = (const char *)sqlite3_column_text(stmt, 9);
        bool is_test = sqlite3_column_int(stmt, 10) != 0;
        const char *member_kind;
        ClassDossierUsage *usage;

        if (count >= CLASS_DOSSIER_MAX_USAGE_LOAD) {
            *out_more = true;
            continue;
        }
        if ((path == nullptr) || (base == nullptr)) {
            continue;
        }
        if ((mapped_path == nullptr) || (strcmp(mapped_path, path) != 0)) {
            if (has_mapping) {
                code_lens_mapped_file_free(&mapped);
                has_mapping = false;
            }
            mapped_path = copy_cstr(path);
            if ((mapped_path != nullptr) && (code_lens_map_file(mapped_path, &mapped) == 0)) {
                has_mapping = true;
            }
        }
        if ((target != nullptr) && (target[0] == '\0') &&
            (!has_mapping ||
             !class_dossier_usage_receiver_matches_type(&mapped,
                                                         start_byte,
                                                         symbol,
                                                         type->name))) {
            continue;
        }
        usage = &usages[count];
        (void)memset(usage, 0, sizeof(*usage));
        member_kind = class_dossier_member_kind_for_name(members, member_count, base);
        usage->group = class_dossier_classify_usage(has_mapping ? &mapped : nullptr,
                                                     start_byte,
                                                     end_byte,
                                                     base,
                                                     type->name,
                                                     member_kind,
                                                     is_test);
        usage->path = copy_cstr(path);
        usage->line = line;
        usage->column = column;
        usage->symbol = copy_cstr(symbol == nullptr ? base : symbol);
        usage->base = copy_cstr(base);
        usage->scope = copy_cstr(scope == nullptr ? "workspace" : scope);
        usage->dependency = copy_cstr(dependency == nullptr ? "" : dependency);
        usage->snippet = has_mapping
                             ? class_dossier_source_line(&mapped, start_byte, end_byte)
                             : copy_cstr("snippet unavailable");
        if ((usage->path == nullptr) || (usage->symbol == nullptr) ||
            (usage->base == nullptr) || (usage->scope == nullptr) ||
            (usage->dependency == nullptr) ||
            (usage->snippet == nullptr)) {
            if (has_mapping) {
                code_lens_mapped_file_free(&mapped);
            }
            (void)sqlite3_finalize(stmt);
            return -1;
        }
        usage->score = class_dossier_usage_score(usage->group,
                                                  usage->scope,
                                                  usage->path,
                                                  type->file_path,
                                                  usage->snippet,
                                                  member_kind);
        count++;
    }
    if (has_mapping) {
        code_lens_mapped_file_free(&mapped);
    }
    if (rc != SQLITE_DONE) {
        (void)report_sqlite_error(db->handle, "query Java type usages");
        (void)sqlite3_finalize(stmt);
        return -1;
    }
    (void)sqlite3_finalize(stmt);
    qsort(usages, count, sizeof(*usages), class_dossier_compare_usages);
    *out_count = count;
    return 0;
}

static bool class_dossier_declares_method(const ClassDossierSymbol *members,
                                           size_t count,
                                           const char *name)
{
    for (size_t i = 0U; i < count; i++) {
        if (class_dossier_method_kind(members[i].kind) &&
            (strcmp(members[i].name, name) == 0)) {
            return true;
        }
    }
    return false;
}

static bool class_dossier_namespace_is_ancestor(
    const ClassDossierAncestor *ancestors,
    size_t count,
    const char *namespace_name)
{
    if ((namespace_name == nullptr) || (namespace_name[0] == '\0')) {
        return false;
    }
    for (size_t i = 0U; i < count; i++) {
        if (strcmp(ancestors[i].definition.namespace_name, namespace_name) == 0) {
            return true;
        }
    }
    return false;
}

static size_t class_dossier_find_inherited_method(
    const ClassDossierSymbol *methods,
    const unsigned int *depths,
    size_t method_count,
    const ClassDossierAncestor *ancestors,
    size_t ancestor_count,
    const char *name,
    const char *target_namespace)
{
    size_t best = SIZE_MAX;
    bool target_is_ancestor = class_dossier_namespace_is_ancestor(ancestors,
                                                                  ancestor_count,
                                                                  target_namespace);

    if ((target_namespace != nullptr) && (target_namespace[0] != '\0') &&
        !target_is_ancestor) {
        return SIZE_MAX;
    }
    for (size_t i = 0U; i < method_count; i++) {
        bool exact_target;
        bool best_exact_target;

        if (strcmp(methods[i].name, name) != 0) {
            continue;
        }
        exact_target = target_is_ancestor &&
                       (strcmp(methods[i].namespace_name, target_namespace) == 0);
        best_exact_target = (best != SIZE_MAX) && target_is_ancestor &&
                            (strcmp(methods[best].namespace_name,
                                    target_namespace) == 0);
        if ((best == SIZE_MAX) || (exact_target && !best_exact_target) ||
            (exact_target == best_exact_target && depths[i] < depths[best]) ||
            (exact_target == best_exact_target && depths[i] == depths[best] &&
             methods[i].start_line < methods[best].start_line)) {
            best = i;
        }
    }
    return best;
}

static bool class_dossier_inherited_exists(const ClassDossierInherited *items,
                                            size_t count,
                                            const ClassDossierSymbol *method,
                                            size_t *out_index)
{
    for (size_t i = 0U; i < count; i++) {
        if ((strcmp(items[i].method.namespace_name, method->namespace_name) == 0) &&
            (strcmp(items[i].method.name, method->name) == 0) &&
            (items[i].method.start_line == method->start_line)) {
            if (out_index != nullptr) {
                *out_index = i;
            }
            return true;
        }
    }
    return false;
}

static bool class_dossier_add_inherited(ClassDossierInherited *items,
                                         size_t *count,
                                         const ClassDossierSymbol *method,
                                         unsigned int depth,
                                         const char *reason,
                                         int score,
                                         bool *out_more)
{
    size_t existing = 0U;

    if (class_dossier_inherited_exists(items, *count, method, &existing)) {
        if (score > items[existing].score) {
            items[existing].reason = copy_cstr(reason);
            items[existing].score = score;
            items[existing].depth = depth;
            if (items[existing].reason == nullptr) {
                return false;
            }
        }
        return true;
    }
    if (*count >= CLASS_DOSSIER_MAX_INHERITED) {
        *out_more = true;
        return true;
    }
    items[*count].method = *method;
    items[*count].reason = copy_cstr(reason);
    items[*count].depth = depth;
    items[*count].score = score;
    if (items[*count].reason == nullptr) {
        return false;
    }
    (*count)++;
    return true;
}

static int class_dossier_compare_inherited(const void *left_value,
                                            const void *right_value)
{
    const ClassDossierInherited *left = left_value;
    const ClassDossierInherited *right = right_value;
    int order;

    if (left->score != right->score) {
        return left->score > right->score ? -1 : 1;
    }
    if (left->depth != right->depth) {
        return left->depth < right->depth ? -1 : 1;
    }
    order = strcmp(left->method.namespace_name, right->method.namespace_name);
    if (order != 0) {
        return order;
    }
    if (left->method.start_line != right->method.start_line) {
        return left->method.start_line < right->method.start_line ? -1 : 1;
    }
    return 0;
}

static int class_dossier_load_relevant_inherited(
    CodeLensDb *db,
    const char *repo_name,
    const ClassDossierSymbol *type,
    const ClassDossierSymbol *members,
    size_t member_count,
    const ClassDossierAncestor *ancestors,
    size_t ancestor_count,
    const ClassDossierOverride *overrides,
    size_t override_count,
    const ClassDossierUsage *usages,
    size_t usage_count,
    int exclude_tests,
    ClassDossierInherited *items,
    size_t *out_count,
    bool *out_more)
{
    static const char refs_sql[] =
        "SELECT r.symbolBase, r.targetNamespace, r.lineNumber, r.startByte, r.endByte "
        "FROM Ref r JOIN File f ON f.rowid=r.fileId "
        "WHERE f.repo=?1 AND f.path=?2 AND r.lineNumber>=?3 AND r.lineNumber<=?4 "
        "ORDER BY r.lineNumber, r.columnNumber LIMIT 512";
    ClassDossierSymbol methods[CLASS_DOSSIER_MAX_INHERITED_METHOD_LOAD];
    unsigned int depths[CLASS_DOSSIER_MAX_INHERITED_METHOD_LOAD];
    size_t method_count = 0U;
    size_t count = 0U;
    sqlite3_stmt *stmt = nullptr;
    CodeLensMappedFile mapped = {0};
    bool has_mapping = false;
    int rc = SQLITE_DONE;

    *out_count = 0U;
    *out_more = false;
    for (size_t ancestor_index = 0U; ancestor_index < ancestor_count;
         ancestor_index++) {
        size_t loaded = 0U;
        size_t capacity = CLASS_DOSSIER_MAX_INHERITED_METHOD_LOAD - method_count;

        if (capacity == 0U) {
            *out_more = true;
            break;
        }
        if (class_dossier_load_methods_for_namespace(
                db,
                repo_name,
                ancestors[ancestor_index].definition.namespace_name,
                exclude_tests,
                methods + method_count,
                capacity,
                &loaded) != 0) {
            return -1;
        }
        for (size_t i = 0U; i < loaded; i++) {
            depths[method_count + i] = ancestors[ancestor_index].depth;
        }
        method_count += loaded;
    }

    /* Override targets are behaviorally relevant even when the subclass does
     * not call them. This also captures abstract/interface contracts. */
    for (size_t override_index = 0U; override_index < override_count;
         override_index++) {
        const ClassDossierOverride *override = &overrides[override_index];

        if (override->incoming) {
            continue;
        }
        for (size_t method_index = 0U; method_index < method_count; method_index++) {
            if ((strcmp(methods[method_index].namespace_name,
                        override->other_namespace) == 0) &&
                (strcmp(methods[method_index].name, override->other_name) == 0) &&
                (methods[method_index].start_line == override->other_line)) {
                const char *reason = strcmp(override->relation, "implements") == 0
                                         ? "implemented contract"
                                         : "overridden declaration";

                if (!class_dossier_add_inherited(items,
                                                 &count,
                                                 &methods[method_index],
                                                 depths[method_index],
                                                 reason,
                                                 120 - (int)depths[method_index],
                                                 out_more)) {
                    return -1;
                }
                break;
            }
        }
    }

    if ((method_count > 0U) &&
        (db_prepare(db, refs_sql, &stmt) == 0) &&
        (bind_text(stmt, 1, repo_name) == 0) &&
        (bind_text(stmt, 2, type->file_path) == 0) &&
        (sqlite3_bind_int64(stmt, 3, (sqlite3_int64)type->start_line) == SQLITE_OK) &&
        (sqlite3_bind_int64(stmt, 4, (sqlite3_int64)type->end_line) == SQLITE_OK)) {
        has_mapping = code_lens_map_file(type->file_path, &mapped) == 0;
        while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
            const char *base = (const char *)sqlite3_column_text(stmt, 0);
            const char *target = (const char *)sqlite3_column_text(stmt, 1);
            uint32_t line = (uint32_t)sqlite3_column_int64(stmt, 2);
            uint32_t start_byte = (uint32_t)sqlite3_column_int64(stmt, 3);
            uint32_t end_byte = (uint32_t)sqlite3_column_int64(stmt, 4);
            const ClassDossierSymbol *owner;
            size_t method_index;
            char *reason;

            if ((base == nullptr) || (target == nullptr) ||
                (has_mapping &&
                 (!class_dossier_reference_followed_by(&mapped, end_byte, "(") ||
                  class_dossier_reference_has_foreign_receiver(&mapped,
                                                               start_byte)))) {
                continue;
            }
            if (class_dossier_declares_method(members, member_count, base) &&
                !class_dossier_namespace_is_ancestor(ancestors,
                                                     ancestor_count,
                                                     target)) {
                continue;
            }
            method_index = class_dossier_find_inherited_method(methods,
                                                               depths,
                                                               method_count,
                                                               ancestors,
                                                               ancestor_count,
                                                               base,
                                                               target);
            if (method_index == SIZE_MAX) {
                continue;
            }
            owner = class_dossier_member_at_line(members, member_count, line);
            if ((owner == nullptr) || class_dossier_java_type_kind(owner->kind)) {
                continue;
            }
            reason = alloc_printf("called by `%s`", owner->name);
            if ((reason == nullptr) ||
                !class_dossier_add_inherited(items,
                                             &count,
                                             &methods[method_index],
                                             depths[method_index],
                                             reason,
                                             100 - (int)depths[method_index],
                                             out_more)) {
                if (has_mapping) {
                    code_lens_mapped_file_free(&mapped);
                }
                (void)sqlite3_finalize(stmt);
                return -1;
            }
        }
    } else if (method_count > 0U) {
        (void)sqlite3_finalize(stmt);
        return -1;
    }
    if (has_mapping) {
        code_lens_mapped_file_free(&mapped);
    }
    if (stmt != nullptr) {
        if (rc != SQLITE_DONE) {
            (void)report_sqlite_error(db->handle, "query inherited Java calls");
            (void)sqlite3_finalize(stmt);
            return -1;
        }
        (void)sqlite3_finalize(stmt);
    }

    /* Calls made on instances at representative usage sites reveal inherited
     * public behavior (for example Node.peephole on CProjNode) without
     * enumerating every inherited method. */
    for (size_t usage_index = 0U; usage_index < usage_count; usage_index++) {
        size_t method_index;

        if ((usages[usage_index].group != CLASS_USAGE_CALL) ||
            class_dossier_declares_method(members,
                                          member_count,
                                          usages[usage_index].base)) {
            continue;
        }
        method_index = class_dossier_find_inherited_method(methods,
                                                           depths,
                                                           method_count,
                                                           ancestors,
                                                           ancestor_count,
                                                           usages[usage_index].base,
                                                           "");
        if ((method_index != SIZE_MAX) &&
            !class_dossier_add_inherited(items,
                                         &count,
                                         &methods[method_index],
                                         depths[method_index],
                                         "called on instances at usage sites",
                                         70 - (int)depths[method_index],
                                         out_more)) {
            return -1;
        }
    }
    qsort(items, count, sizeof(*items), class_dossier_compare_inherited);
    *out_count = count;
    return 0;
}

static bool class_dossier_append_code_block(StringBuilder *out,
                                            const char *content,
                                            size_t max_bytes)
{
    size_t len = content == nullptr ? 0U : strlen(content);
    size_t shown = len > max_bytes ? max_bytes : len;

    while ((shown > 0U) && (shown < len) &&
           (((unsigned char)content[shown] & 0xc0U) == 0x80U)) {
        shown--;
    }
    return sb_append(out, "```java\n") && sb_append_len(out, content == nullptr ? "" : content, shown) &&
           ((shown == 0U) || (content[shown - 1U] == '\n') || sb_append(out, "\n")) &&
           ((shown == len) || sb_append(out, "// ... declaration truncated by dossier cap\n")) &&
           sb_append(out, "```\n");
}

static bool class_dossier_append_type_header(StringBuilder *out,
                                             const ClassDossierSymbol *type)
{
    char *doc = class_dossier_compact_text(type->doc, 800U, false);

    if (doc == nullptr) {
        return false;
    }
    if (!sb_append(out,
                   "name|kind|namespace|filePath|startLine|endLine|scope|dependency\n") ||
        !sb_appendf(out,
                    "%s|%s|%s|%s|%u|%u|%s|%s\n",
                    type->name,
                    type->kind,
                    type->namespace_name,
                    type->file_path,
                    type->start_line,
                    type->end_line,
                    type->scope,
                    type->dependency)) {
        return false;
    }
    if ((doc[0] != '\0') &&
        (!sb_append(out, "\nDocumentation: ") || !sb_append(out, doc) ||
         !sb_append(out, "\n"))) {
        return false;
    }
    return sb_append(out, "\nDeclaration\n-----------\n") &&
           class_dossier_append_code_block(out, type->content, 4096U);
}

static bool class_dossier_append_members(StringBuilder *out,
                                         const ClassDossierSymbol *members,
                                         size_t member_count,
                                         bool more_members)
{
    size_t shown = member_count > CLASS_DOSSIER_MAX_MEMBERS
                       ? CLASS_DOSSIER_MAX_MEMBERS
                       : member_count;

    if (!sb_appendf(out, "\nMembers (%zu", member_count) ||
        (more_members && !sb_append(out, "+")) || !sb_append(out, ")\n-------") ||
        !sb_append(out, "\n")) {
        return false;
    }
    if (shown == 0U) {
        return sb_append(out, "(none indexed)\n");
    }
    for (size_t i = 0U; i < shown; i++) {
        char *source = class_dossier_member_source(&members[i], member_count > 64U);
        char *doc = class_dossier_compact_text(members[i].doc, 240U, false);

        if ((source == nullptr) || (doc == nullptr) ||
            !sb_appendf(out,
                        "- %s `%s` (lines %u-%u)\n  %s\n",
                        members[i].kind,
                        members[i].name,
                        members[i].start_line,
                        members[i].end_line,
                        source)) {
            return false;
        }
        if ((doc[0] != '\0') &&
            (!sb_append(out, "  doc: ") || !sb_append(out, doc) || !sb_append(out, "\n"))) {
            return false;
        }
    }
    if ((shown < member_count) || more_members) {
        return sb_appendf(out,
                          "note: showing the first %zu members; additional members omitted by "
                          "the dossier cap\n",
                          shown);
    }
    return true;
}

static bool class_dossier_append_hierarchy(StringBuilder *out,
                                           const ClassDossierLink *links,
                                           size_t count)
{
    if (!sb_appendf(out, "\nInheritance and subtypes (%zu)\n-----------------------------\n", count)) {
        return false;
    }
    if (count == 0U) {
        return sb_append(out, "(no indexed direct relationships)\n");
    }
    for (size_t i = 0U; i < count; i++) {
        const ClassDossierSymbol *definition = &links[i].definition;

        if (!sb_appendf(out, "- %s `%s`", links[i].relation, links[i].target_namespace)) {
            return false;
        }
        if ((definition->kind != nullptr) && (definition->kind[0] != '\0') &&
            (!sb_appendf(out,
                         " (%s) — %s:%u",
                         definition->kind,
                         definition->file_path,
                         definition->start_line))) {
            return false;
        }
        if (!sb_append(out, "\n")) {
            return false;
        }
    }
    return true;
}

static bool class_dossier_append_overrides(StringBuilder *out,
                                           const ClassDossierSymbol *type,
                                           const ClassDossierOverride *items,
                                           size_t count)
{
    if (!sb_appendf(out,
                    "\nOverrides and implementations (%zu)\n"
                    "-------------------------------------\n",
                    count)) {
        return false;
    }
    if (count == 0U) {
        return sb_append(out, "(no direct name/arity matches)\n");
    }
    for (size_t i = 0U; i < count; i++) {
        char *current_signature =
            class_dossier_short_signature(items[i].current_content, items[i].current_name);
        char *other_signature =
            class_dossier_short_signature(items[i].other_content, items[i].other_name);

        if ((current_signature == nullptr) || (other_signature == nullptr)) {
            return false;
        }
        if (items[i].incoming) {
            if (!sb_appendf(out,
                            "- `%s.%s` %s `%s.%s` — %s:%u\n",
                            items[i].other_namespace,
                            other_signature,
                            items[i].relation,
                            type->namespace_name,
                            current_signature,
                            items[i].other_path,
                            items[i].other_line)) {
                return false;
            }
        } else if (!sb_appendf(out,
                               "- `%s.%s` %s `%s.%s` — %s:%u\n",
                               type->namespace_name,
                               current_signature,
                               items[i].relation,
                               items[i].other_namespace,
                               other_signature,
                               items[i].other_path,
                               items[i].other_line)) {
            return false;
        }
    }
    return true;
}

static bool class_dossier_append_inherited(StringBuilder *out,
                                           const ClassDossierInherited *items,
                                           size_t count,
                                           bool more)
{
    if (!sb_appendf(out,
                    "\nRelevant inherited methods (%zu%s)\n"
                    "--------------------------------\n",
                    count,
                    more ? "+" : "")) {
        return false;
    }
    if (count == 0U) {
        return sb_append(out, "(none selected)\n");
    }
    for (size_t i = 0U; i < count; i++) {
        char *signature = class_dossier_short_signature(items[i].method.content,
                                                         items[i].method.name);
        char *source = class_dossier_member_source(&items[i].method, false);

        if ((signature == nullptr) || (source == nullptr) ||
            !sb_appendf(out,
                        "- `%s.%s` — %s — %s:%u\n  %s\n",
                        items[i].method.namespace_name,
                        signature,
                        items[i].reason,
                        items[i].method.file_path,
                        items[i].method.start_line,
                        source)) {
            return false;
        }
    }
    if (more) {
        return sb_append(out,
                         "note: additional inherited candidates were omitted by "
                         "the dossier cap\n");
    }
    return true;
}

static bool class_dossier_append_supporting(StringBuilder *out,
                                            const ClassDossierLink *supporting,
                                            size_t count)
{
    if (!sb_appendf(out,
                    "\nSupporting definitions (%zu)\n---------------------------\n",
                    count)) {
        return false;
    }
    if (count == 0U) {
        return sb_append(out, "(none selected)\n");
    }
    for (size_t i = 0U; i < count; i++) {
        const ClassDossierSymbol *definition = &supporting[i].definition;
        char *declaration = class_dossier_compact_text(definition->content, 480U, false);

        if ((declaration == nullptr) ||
            !sb_appendf(out,
                        "- %s `%s` (%s) — %s:%u\n  %s\n",
                        supporting[i].relation,
                        supporting[i].target_namespace,
                        definition->kind,
                        definition->file_path,
                        definition->start_line,
                        declaration)) {
            return false;
        }
    }
    return true;
}

static bool class_dossier_usage_path_selected(const ClassDossierUsage *usages,
                                               const size_t *selected,
                                               size_t selected_count,
                                               const char *path)
{
    for (size_t i = 0U; i < selected_count; i++) {
        if (strcmp(usages[selected[i]].path, path) == 0) {
            return true;
        }
    }
    return false;
}

static bool class_dossier_append_usage_group(StringBuilder *out,
                                             const ClassDossierUsage *usages,
                                             size_t usage_count,
                                             ClassDossierUsageGroup group,
                                             const char *title)
{
    size_t selected[CLASS_DOSSIER_USAGES_PER_GROUP];
    size_t selected_count = 0U;
    size_t total = 0U;

    for (size_t i = 0U; i < usage_count; i++) {
        total += usages[i].group == group ? 1U : 0U;
    }
    for (unsigned int pass = 0U;
         (pass < 2U) && (selected_count < CLASS_DOSSIER_USAGES_PER_GROUP);
         pass++) {
        for (size_t i = 0U;
             (i < usage_count) && (selected_count < CLASS_DOSSIER_USAGES_PER_GROUP);
             i++) {
            if (usages[i].group != group) {
                continue;
            }
            if ((pass == 0U) && class_dossier_usage_path_selected(usages,
                                                                  selected,
                                                                  selected_count,
                                                                  usages[i].path)) {
                continue;
            }
            bool already_selected = false;

            for (size_t j = 0U; j < selected_count; j++) {
                if (selected[j] == i) {
                    already_selected = true;
                    break;
                }
            }
            if (!already_selected) {
                selected[selected_count++] = i;
            }
        }
    }
    if (!sb_appendf(out, "\n%s (%zu", title, total) ||
        ((selected_count < total) && !sb_appendf(out, "; showing %zu", selected_count)) ||
        !sb_append(out, ")\n")) {
        return false;
    }
    if (selected_count == 0U) {
        return sb_append(out, "- none\n");
    }
    for (size_t i = 0U; i < selected_count; i++) {
        const ClassDossierUsage *usage = &usages[selected[i]];

        if (!sb_appendf(out,
                        "- %s:%u:%u [%s%s%s] %s\n  > %s\n",
                        usage->path,
                        usage->line,
                        usage->column,
                        usage->scope,
                        usage->dependency[0] == '\0' ? "" : " ",
                        usage->dependency,
                        usage->symbol,
                        usage->snippet)) {
            return false;
        }
    }
    return true;
}

static bool class_dossier_append_usages(StringBuilder *out,
                                        const ClassDossierUsage *usages,
                                        size_t count,
                                        bool more)
{
    static const char *const titles[CLASS_USAGE_GROUP_COUNT] = {
        "Construction", "Calls", "Field access", "Type checks", "Tests",
        "Other type references",
    };

    if (!sb_appendf(out,
                    "\nUsages — representative references (%zu%s)\n"
                    "============================================\n"
                    "Ranked by explanatory signals, workspace-first, and diversified by "
                    "source file.\n",
                    count,
                    more ? "+" : "")) {
        return false;
    }
    for (size_t group = 0U; group < CLASS_USAGE_GROUP_COUNT; group++) {
        if (!class_dossier_append_usage_group(out,
                                              usages,
                                              count,
                                              (ClassDossierUsageGroup)group,
                                              titles[group])) {
            return false;
        }
    }
    if (more) {
        return sb_append(out,
                         "note: additional references were omitted by the dossier scan cap\n");
    }
    return true;
}

static bool class_dossier_render_one(CodeLensDb *db,
                                     const char *repo_name,
                                     const ClassDossierSymbol *type,
                                     int exclude_tests,
                                     StringBuilder *out)
{
    ClassDossierSymbol members[CLASS_DOSSIER_MAX_MEMBER_LOAD];
    ClassDossierLink links[CLASS_DOSSIER_MAX_LINKS];
    ClassDossierAncestor ancestors[CLASS_DOSSIER_MAX_ANCESTORS];
    ClassDossierOverride overrides[CLASS_DOSSIER_MAX_OVERRIDES];
    ClassDossierInherited inherited[CLASS_DOSSIER_MAX_INHERITED];
    ClassDossierLink supporting[CLASS_DOSSIER_MAX_SUPPORTING];
    ClassDossierUsage usages[CLASS_DOSSIER_MAX_USAGE_LOAD];
    size_t member_count = 0U;
    size_t link_count = 0U;
    size_t ancestor_count = 0U;
    size_t override_count = 0U;
    size_t inherited_count = 0U;
    size_t supporting_count = 0U;
    size_t usage_count = 0U;
    bool more_members = false;
    bool more_ancestors = false;
    bool more_inherited = false;
    bool more_usages = false;

    (void)memset(links, 0, sizeof(links));
    (void)memset(supporting, 0, sizeof(supporting));
    if (!class_dossier_java_type_kind(type->kind) ||
        (class_dossier_load_members(db,
                                    repo_name,
                                    type,
                                    exclude_tests,
                                    members,
                                    &member_count,
                                    &more_members) != 0) ||
        (class_dossier_load_direct_links(db,
                                         repo_name,
                                         type,
                                         links,
                                         &link_count) != 0) ||
        (class_dossier_load_incoming_links(db,
                                           repo_name,
                                           type,
                                           links,
                                           &link_count) != 0) ||
        (class_dossier_load_ancestors(db,
                                      repo_name,
                                      type,
                                      links,
                                      link_count,
                                      ancestors,
                                      &ancestor_count,
                                      &more_ancestors) != 0) ||
        (class_dossier_load_overrides(db,
                                      repo_name,
                                      type,
                                      members,
                                      member_count,
                                      links,
                                      link_count,
                                      ancestors,
                                      ancestor_count,
                                      exclude_tests,
                                      overrides,
                                      &override_count) != 0) ||
        (class_dossier_load_supporting(db,
                                       repo_name,
                                       type,
                                       members,
                                       member_count,
                                       links,
                                       link_count,
                                       supporting,
                                       &supporting_count) != 0) ||
        (class_dossier_load_usages(db,
                                   repo_name,
                                   type,
                                   members,
                                   member_count,
                                   exclude_tests,
                                   usages,
                                   &usage_count,
                                   &more_usages) != 0) ||
        (class_dossier_load_relevant_inherited(db,
                                               repo_name,
                                               type,
                                               members,
                                               member_count,
                                               ancestors,
                                               ancestor_count,
                                               overrides,
                                               override_count,
                                               usages,
                                               usage_count,
                                               exclude_tests,
                                               inherited,
                                               &inherited_count,
                                               &more_inherited) != 0)) {
        return false;
    }
    more_inherited = more_inherited || more_ancestors;
    return class_dossier_append_type_header(out, type) &&
           class_dossier_append_members(out, members, member_count, more_members) &&
           class_dossier_append_hierarchy(out, links, link_count) &&
           class_dossier_append_overrides(out, type, overrides, override_count) &&
           class_dossier_append_inherited(out,
                                          inherited,
                                          inherited_count,
                                          more_inherited) &&
           class_dossier_append_supporting(out, supporting, supporting_count) &&
           class_dossier_append_usages(out, usages, usage_count, more_usages);
}

static char *class_dossier_render(CodeLensDb *db,
                                  const char *repo_name,
                                  const char *symbol_base,
                                  const char *namespace_filter,
                                  const char *path_filter,
                                  const char *definition_scope,
                                  int exclude_tests,
                                  ClassDossierStatus *out_status)
{
    ClassDossierSymbol types[CLASS_DOSSIER_MAX_TYPES];
    size_t type_count = 0U;
    bool more_types = false;
    StringBuilder out = {0};

    *out_status = CLASS_DOSSIER_ERROR;
    if (class_dossier_load_type_candidates(db,
                                           repo_name,
                                           symbol_base,
                                           namespace_filter,
                                           path_filter,
                                           definition_scope,
                                           exclude_tests,
                                           types,
                                           &type_count,
                                           &more_types) != 0) {
        return nullptr;
    }
    if (type_count == 0U) {
        *out_status = CLASS_DOSSIER_NOT_A_TYPE;
        return nullptr;
    }
    if (!sb_append(&out,
                   "Class Dossier\n=============\n"
                   "Bounded semantic neighborhood; unrelated inherited APIs and "
                   "repetitive usages are omitted.\n")) {
        return nullptr;
    }
    if ((type_count > 1U) || more_types) {
        if (!sb_appendf(&out,
                        "note: the name is ambiguous; showing %zu matching types%s. "
                        "Use namespace or path to select one.\n\n",
                        type_count,
                        more_types ? " (additional matches omitted)" : "")) {
            return nullptr;
        }
    }
    for (size_t i = 0U; i < type_count; i++) {
        if ((i > 0U) && !sb_append(&out, "\n\n")) {
            return nullptr;
        }
        if (!sb_appendf(&out, "Type: `%s`\n---------------\n", types[i].namespace_name) ||
            !class_dossier_render_one(db, repo_name, &types[i], exclude_tests, &out)) {
            return nullptr;
        }
    }
    *out_status = CLASS_DOSSIER_RENDERED;
    return out.data;
}

static char *context_symbol_ex_internal(const char *repo_name,
                                         const char *symbol_name,
                                         const CodeLensContextOptions *options,
                                        CodeLensDb *prepared_db)
{
    /* Substring fallback rows return location but only exact definitions carry content. */
    static const char definitions_sql[] =
        "SELECT s.name AS \"s.name\", s.kind AS \"s.kind\", "
        "s.namespace AS \"s.namespace\", s.filePath AS \"s.filePath\", "
        "s.startLine AS \"s.startLine\", s.endLine AS \"s.endLine\", "
        "s.doc AS \"s.doc\", CASE WHEN s.name = ?2 THEN s.content ELSE '' END AS \"s.content\", "
        "CASE WHEN df.id IS NULL THEN 'workspace' ELSE 'dependency' END AS \"s.scope\", "
        "COALESCE(da.coordinate, '') AS \"s.dependency\", "
        "COALESCE(da.scope, '') AS \"s.dependencyScope\" "
        "FROM Symbol s LEFT JOIN DependencyFile df "
        "ON df.repo = s.repo AND df.filePath = s.filePath "
        "LEFT JOIN DependencyArtifact da ON da.id = df.artifactId "
        "WHERE s.repo = ?1 AND (s.name = ?2 OR instr(lower(s.name), lower(?2)) > 0) "
        "AND (?3 = '' OR s.namespace = ?3) "
        "AND (?4 = '' OR instr(lower(s.filePath), lower(?4)) > 0) "
        "AND ((?5 = 'workspace' AND df.id IS NULL) OR (?5 = 'dependencies' AND df.id IS NOT NULL)) "
        "AND (?6 = 0 OR (s.kind != 'test' "
        "AND instr(lower(replace(s.filePath, '\\', '/')), '/test/') = 0 "
        "AND instr(lower(replace(s.filePath, '\\', '/')), '/tests/') = 0)) "
        "ORDER BY CASE WHEN s.name = ?2 THEN 0 ELSE 1 END, s.name LIMIT 5";
    static const char references_sql[] =
        "WITH candidates(namespace) AS ("
        "SELECT DISTINCT s.namespace FROM Symbol s "
        "LEFT JOIN DependencyFile df ON df.repo = s.repo AND df.filePath = s.filePath "
        "WHERE s.repo = ?1 AND s.name = ?2 "
        "AND (?3 = '' OR s.namespace = ?3) "
        "AND (?4 = '' OR instr(lower(s.filePath), lower(?4)) > 0) "
        "AND ((?5 = 'workspace' AND df.id IS NULL) OR (?5 = 'dependencies' AND df.id IS NOT NULL)) "
        "AND (?6 = 0 OR (s.kind != 'test' "
        "AND instr(lower(replace(s.filePath, '\\', '/')), '/test/') = 0 "
        "AND instr(lower(replace(s.filePath, '\\', '/')), '/tests/') = 0))) "
        "SELECT f.path AS \"r.filePath\", r.lineNumber AS \"r.lineNumber\", "
        "r.columnNumber AS \"r.columnNumber\", r.startByte AS \"r.startByte\", "
        "r.endByte AS \"r.endByte\", r.symbol AS \"r.symbol\", "
        "r.targetNamespace AS \"r.targetNamespace\", "
        "CASE WHEN rdf.id IS NULL THEN 'workspace' ELSE 'dependency' END AS \"r.scope\", "
        "COALESCE(rda.coordinate, '') AS \"r.dependency\" "
        "FROM Ref r JOIN File f ON f.rowid = r.fileId "
        "LEFT JOIN DependencyFile rdf ON rdf.repo = f.repo AND rdf.filePath = f.path "
        "LEFT JOIN DependencyArtifact rda ON rda.id = rdf.artifactId "
        "WHERE f.repo = ?1 AND r.symbolBase = ?2 "
        "AND (CASE WHEN r.targetNamespace = '' THEN "
        "(f.namespace IN candidates OR EXISTS (SELECT 1 FROM Referred rf "
        "WHERE rf.repo = ?1 AND rf.filePath = f.path "
        "AND (rf.symbol = ?2 OR rf.symbol = ':all') AND rf.namespace IN candidates)) "
        "ELSE r.targetNamespace IN candidates END) "
        "AND (?6 = 0 OR (instr(lower(replace(f.path, '\\', '/')), '/test/') = 0 "
        "AND instr(lower(replace(f.path, '\\', '/')), '/tests/') = 0)) "
        "ORDER BY CASE WHEN rdf.id IS NULL THEN 0 ELSE 1 END, f.path, r.lineNumber LIMIT 25";
    CodeLensDb owned_db;
    CodeLensDb *db = prepared_db;
    char *db_path;
    const char *params[5];
    const char *definition_scope;
    const char *symbol_base;
    int exclude_tests = (options != nullptr) && options->exclude_tests ? 1 : 0;
    const char *namespace_filter =
        ((options != nullptr) && (options->namespace_name != nullptr)) ? options->namespace_name
                                                                        : "";
    const char *path_filter =
        ((options != nullptr) && (options->path != nullptr)) ? options->path : "";
    char *symbol_rows;
    char *ref_rows = nullptr;
    char *snippet_rows = nullptr;
    ClassDossierStatus dossier_status = CLASS_DOSSIER_NOT_A_TYPE;
    char *dossier = nullptr;
    size_t symbol_row_count = 0U;
    size_t ref_row_count = 0U;
    StalenessStatus staleness = {0};
    StringBuilder output = {0};
    bool owns_db = false;
    char *maven_note = nullptr;
    char *result;

    if ((repo_name == nullptr) || (symbol_name == nullptr)) {
        return nullptr;
    }
    if (prepared_db == nullptr) {
        repo_name = resolve_repo_id(repo_name);
        if (repo_name == nullptr) {
            return nullptr;
        }
    }

    if (prepared_db == nullptr) {
        db_path = repo_db_path(repo_name);
        if ((db_path == nullptr) || !code_lens_path_exists(db_path)) {
            return repo_exists(repo_name) == 0 ? unknown_repo_message(repo_name) : nullptr;
        }
        if (open_search_db(&owned_db,
                           db_path,
                           repo_name,
                           options == nullptr ? nullptr : options->tools_deps_aliases,
                           &staleness) != 0) {
            return repo_reindex_required_message(repo_name);
        }
        db = &owned_db;
        owns_db = true;
    }

    /* Preserve exact dotted Clojure names when they exist; otherwise accept
     * Clojure alias/name, Java Type.member/package.Type/Type#member, or C
     * ptr->field spellings and run the lookup on the final name component. */
    symbol_base = context_base_name(db, repo_name, symbol_name);
    if (((options == nullptr) || (options->namespace_name == nullptr) ||
         (options->namespace_name[0] == '\0'))) {
        const char *inferred =
            context_infer_java_namespace(db, repo_name, symbol_name, symbol_base);

        if (inferred != nullptr) {
            namespace_filter = inferred;
        }
    }
    definition_scope = context_has_workspace_definition(db,
                                                        repo_name,
                                                        symbol_base,
                                                        namespace_filter,
                                                        path_filter,
                                                        exclude_tests)
                           ? "workspace"
                           : "dependencies";

    if ((strcmp(definition_scope, "dependencies") == 0) &&
        context_path_is_workspace_file(db, repo_name, path_filter)) {
        /* With no definition in the selected workspace file, interpret path as
         * the calling source context and let dependency fallback search its
         * materialized source set. */
        path_filter = "";
    }

    dossier = class_dossier_render(db,
                                    repo_name,
                                    symbol_base,
                                    namespace_filter,
                                    path_filter,
                                    definition_scope,
                                    exclude_tests,
                                    &dossier_status);
    if (dossier_status == CLASS_DOSSIER_ERROR) {
        if (owns_db) {
            code_lens_db_close(db);
        }
        return nullptr;
    }
    if (dossier_status == CLASS_DOSSIER_RENDERED) {
        if (owns_db || (strcmp(definition_scope, "dependencies") == 0)) {
            maven_note = maven_project_status_note(db, repo_name);
        }
        if (owns_db) {
            code_lens_db_close(db);
        }
        if ((maven_note != nullptr) &&
            (!sb_append(&output, maven_note) || !sb_append(&output, "\n"))) {
            return nullptr;
        }
        if ((strcmp(definition_scope, "dependencies") == 0) &&
            !sb_append(&output,
                       "note: no workspace definition matched; showing dependency "
                       "definitions\n\n")) {
            return nullptr;
        }
        if (!sb_append(&output, dossier)) {
            return nullptr;
        }
        return prepared_db == nullptr
                   ? prepend_staleness_note(repo_name, &staleness, output.data)
                   : output.data;
    }

    params[0] = repo_name;
    params[1] = symbol_base;
    params[2] = namespace_filter;
    params[3] = path_filter;
    params[4] = definition_scope;
    symbol_rows =
        db_render_query(db, definitions_sql, params, 5U, &exclude_tests, &symbol_row_count);
    if (symbol_rows != nullptr) {
        ref_rows = db_render_query(db,
                                   references_sql,
                                   params,
                                   5U,
                                   &exclude_tests,
                                   &ref_row_count);
    }
    if (ref_rows != nullptr) {
        snippet_rows = query_reference_snippets(
            db,
            repo_name,
            symbol_base,
            namespace_filter,
            path_filter,
            definition_scope,
            exclude_tests);
    }
    if (owns_db || (strcmp(definition_scope, "dependencies") == 0)) {
        maven_note = maven_project_status_note(db, repo_name);
    }
    if (owns_db) {
        code_lens_db_close(db);
    }

    if ((symbol_rows == nullptr) || (ref_rows == nullptr) || (snippet_rows == nullptr)) {
        return prepared_db == nullptr
                   ? (repo_exists(repo_name) == 0 ? unknown_repo_message(repo_name) : nullptr)
                   : nullptr;
    }

    if ((symbol_row_count == 0U) && (ref_row_count == 0U)) {
        result = prepared_db == nullptr
                     ? empty_result_message(repo_name, "symbols or references", symbol_name)
                     : alloc_printf("no symbols or references matching \"%s\" in repo \"%s\"\n",
                                    symbol_name,
                                    repo_name);
        if (maven_note != nullptr) {
            result = alloc_printf("%s\n%s", maven_note, result);
        }
        return prepared_db == nullptr ? prepend_staleness_note(repo_name, &staleness, result)
                                      : result;
    }

    if ((maven_note != nullptr) &&
        (!sb_append(&output, maven_note) || !sb_append(&output, "\n"))) {
        sb_free(&output);
        return nullptr;
    }
    if ((strcmp(definition_scope, "dependencies") == 0) && (symbol_row_count > 0U) &&
        !sb_append(&output,
                   "note: no workspace definition matched; showing dependency definitions\n\n")) {
        sb_free(&output);
        return nullptr;
    }
    if (!sb_append(&output, "Definitions\n===========\n") || !sb_append(&output, symbol_rows) ||
        !sb_append(&output, "\n\nReferences\n==========\n") || !sb_append(&output, ref_rows) ||
        !sb_append(&output, "\n\n") || !sb_append(&output, snippet_rows)) {
        sb_free(&output);
        return nullptr;
    }

    return prepared_db == nullptr ? prepend_staleness_note(repo_name, &staleness, output.data)
                                  : output.data;
}

char *code_lens_context_symbol_ex(const char *repo_name,
                                   const char *symbol_name,
                                   const CodeLensContextOptions *options)
{
    return context_symbol_ex_internal(repo_name, symbol_name, options, nullptr);
}

char *code_lens_context_symbol(const char *repo_name, const char *symbol_name)
{
    CodeLensContextOptions options = {0};

    return code_lens_context_symbol_ex(repo_name, symbol_name, &options);
}

char *code_lens_run_sql_ex(const char *repo_name,
                            const char *sql,
                            const CodeLensDependencyOptions *options)
{
    const char *tools_deps_aliases = options == nullptr ? nullptr : options->tools_deps_aliases;
    CodeLensDb db = {0};
    StalenessStatus staleness = {0};
    char *db_path;
    char *result;

    if ((repo_name == nullptr) || (sql == nullptr) ||
        !tools_deps_aliases_valid(tools_deps_aliases)) {
        return nullptr;
    }
    repo_name = resolve_repo_id(repo_name);
    if (repo_name == nullptr) {
        return nullptr;
    }

    db_path = repo_db_path(repo_name);
    if ((db_path == nullptr) || !code_lens_path_exists(db_path)) {
        return repo_exists(repo_name) == 0 ? unknown_repo_message(repo_name) : nullptr;
    }
    if (open_search_db(&db, db_path, repo_name, tools_deps_aliases, &staleness) != 0) {
        return repo_reindex_required_message(repo_name);
    }

    /* Row output is capped in the render loop (SQL_TOOL_MAX_ROWS), writes
     * are rejected by the read-only open plus the authorizer, and the
     * progress-handler deadline backstops runaway queries. Only the first
     * statement of a multi-statement string runs (prepare stops at ';'). */
    result = query_with_open_repo_db(&db, sql, nullptr);
    code_lens_db_close(&db);
    if (result == nullptr) {
        return repo_reindex_required_message(repo_name);
    }
    return staleness.stale ? prepend_staleness_note(repo_name, &staleness, result) : result;
}

char *code_lens_run_sql(const char *repo_name, const char *sql)
{
    return code_lens_run_sql_ex(repo_name, sql, nullptr);
}

/* MCP server */

typedef enum {
    MCP_FRAMING_CONTENT_LENGTH,
    MCP_FRAMING_NEWLINE
} McpFraming;

static McpFraming response_framing = MCP_FRAMING_CONTENT_LENGTH;

static bool json_escape_append(StringBuilder *sb, const char *text)
{
    if (!sb_append_len(sb, "\"", 1U)) {
        return false;
    }

    for (size_t i = 0U; text[i] != '\0'; i++) {
        unsigned char ch = (unsigned char)text[i];
        char escaped[8];

        if (ch == '"' || ch == '\\') {
            escaped[0] = '\\';
            escaped[1] = (char)ch;
            if (!sb_append_len(sb, escaped, 2U)) {
                return false;
            }
        } else if (ch == '\n') {
            if (!sb_append_len(sb, "\\n", 2U)) {
                return false;
            }
        } else if (ch == '\r') {
            if (!sb_append_len(sb, "\\r", 2U)) {
                return false;
            }
        } else if (ch == '\t') {
            if (!sb_append_len(sb, "\\t", 2U)) {
                return false;
            }
        } else if (ch < 32U) {
            (void)snprintf(escaped, sizeof(escaped), "\\u%04x", ch);
            if (!sb_append(sb, escaped)) {
                return false;
            }
        } else {
            char plain = (char)ch;
            if (!sb_append_len(sb, &plain, 1U)) {
                return false;
            }
        }
    }

    return sb_append_len(sb, "\"", 1U);
}

static const char *skip_ws(const char *p)
{
    while (isspace((unsigned char)*p)) {
        p++;
    }
    return p;
}

/* Position p at '"'; return the byte after the closing quote (raw scan,
 * escape-aware), or nullptr on an unterminated string. */
static const char *json_skip_string(const char *p)
{
    if (*p != '"') {
        return nullptr;
    }
    p++;
    while ((*p != '\0') && (*p != '"')) {
        if (*p == '\\') {
            p++;
            if (*p == '\0') {
                return nullptr;
            }
        }
        p++;
    }
    return (*p == '"') ? p + 1 : nullptr;
}

/* Skip one JSON value (string, object, array, or literal); return the byte
 * after it, or nullptr on malformed input. */
static const char *json_skip_value(const char *p)
{
    p = skip_ws(p);
    if (*p == '"') {
        return json_skip_string(p);
    }
    if ((*p == '{') || (*p == '[')) {
        int depth = 0;

        while (*p != '\0') {
            if (*p == '"') {
                p = json_skip_string(p);
                if (p == nullptr) {
                    return nullptr;
                }
                continue;
            }
            if ((*p == '{') || (*p == '[')) {
                depth++;
            } else if ((*p == '}') || (*p == ']')) {
                depth--;
                if (depth == 0) {
                    return p + 1;
                }
            }
            p++;
        }
        return nullptr;
    }
    while ((*p != '\0') && (*p != ',') && (*p != '}') && (*p != ']') &&
           !isspace((unsigned char)*p)) {
        p++;
    }
    return p;
}

static char *parse_json_string_at(const char *quote);

/* Scoped object-member lookup: `object` points at (whitespace before) '{'.
 * Walks the members of THIS object only -- nested objects and string values
 * are skipped wholesale, so a "name" key inside `arguments` can never shadow
 * the "name" member of `params`. Returns a pointer to the first byte of the
 * value, or nullptr when the key is absent or the input is malformed. */
static const char *json_object_value(const char *object, const char *key)
{
    const char *p;

    if (object == nullptr) {
        return nullptr;
    }
    p = skip_ws(object);
    if (*p != '{') {
        return nullptr;
    }
    p = skip_ws(p + 1);
    while ((*p != '\0') && (*p != '}')) {
        char *member_key;
        bool match;

        if (*p != '"') {
            return nullptr;
        }
        member_key = parse_json_string_at(p);
        if (member_key == nullptr) {
            return nullptr;
        }
        match = strcmp(member_key, key) == 0;
        p = json_skip_string(p);
        if (p == nullptr) {
            return nullptr;
        }
        p = skip_ws(p);
        if (*p != ':') {
            return nullptr;
        }
        p = skip_ws(p + 1);
        if (match) {
            return p;
        }
        p = json_skip_value(p);
        if (p == nullptr) {
            return nullptr;
        }
        p = skip_ws(p);
        if (*p == ',') {
            p = skip_ws(p + 1);
        }
    }
    return nullptr;
}

static bool parse_four_hex(const char *p, unsigned int *out)
{
    unsigned int value = 0U;

    for (size_t i = 0U; i < 4U; i++) {
        char c = p[i];
        unsigned int digit;

        if ((c >= '0') && (c <= '9')) {
            digit = (unsigned int)(c - '0');
        } else if ((c >= 'a') && (c <= 'f')) {
            digit = (unsigned int)(c - 'a') + 10U;
        } else if ((c >= 'A') && (c <= 'F')) {
            digit = (unsigned int)(c - 'A') + 10U;
        } else {
            return false;
        }
        value = (value << 4) | digit;
    }
    *out = value;
    return true;
}

static bool sb_append_utf8(StringBuilder *sb, unsigned int code)
{
    char buf[4];
    size_t len;

    if (code < 0x80U) {
        buf[0] = (char)code;
        len = 1U;
    } else if (code < 0x800U) {
        buf[0] = (char)(0xC0U | (code >> 6));
        buf[1] = (char)(0x80U | (code & 0x3FU));
        len = 2U;
    } else if (code < 0x10000U) {
        buf[0] = (char)(0xE0U | (code >> 12));
        buf[1] = (char)(0x80U | ((code >> 6) & 0x3FU));
        buf[2] = (char)(0x80U | (code & 0x3FU));
        len = 3U;
    } else {
        buf[0] = (char)(0xF0U | (code >> 18));
        buf[1] = (char)(0x80U | ((code >> 12) & 0x3FU));
        buf[2] = (char)(0x80U | ((code >> 6) & 0x3FU));
        buf[3] = (char)(0x80U | (code & 0x3FU));
        len = 4U;
    }
    return sb_append_len(sb, buf, len);
}

static char *parse_json_string_at(const char *quote)
{
    StringBuilder out = {0};
    const char *p;

    if ((quote == nullptr) || (*quote != '"')) {
        return nullptr;
    }

    p = quote + 1;
    while ((*p != '\0') && (*p != '"')) {
        if (*p == '\\') {
            p++;
            if (*p == '\0') {
                sb_free(&out);
                return nullptr;
            }
            if (*p == 'n') {
                if (!sb_append(&out, "\n")) {
                    sb_free(&out);
                    return nullptr;
                }
            } else if (*p == 'r') {
                if (!sb_append(&out, "\r")) {
                    sb_free(&out);
                    return nullptr;
                }
            } else if (*p == 't') {
                if (!sb_append(&out, "\t")) {
                    sb_free(&out);
                    return nullptr;
                }
            } else if (*p == 'b') {
                if (!sb_append(&out, "\b")) {
                    sb_free(&out);
                    return nullptr;
                }
            } else if (*p == 'f') {
                if (!sb_append(&out, "\f")) {
                    sb_free(&out);
                    return nullptr;
                }
            } else if (*p == 'u') {
                unsigned int code;

                if (!parse_four_hex(p + 1, &code)) {
                    sb_free(&out);
                    return nullptr;
                }
                p += 4;
                if ((code >= 0xD800U) && (code <= 0xDBFFU)) {
                    unsigned int low;

                    /* High surrogate: a \uDC00-\uDFFF pair half must follow. */
                    if ((p[1] == '\\') && (p[2] == 'u') && parse_four_hex(p + 3, &low) &&
                        (low >= 0xDC00U) && (low <= 0xDFFFU)) {
                        code = 0x10000U + ((code - 0xD800U) << 10) + (low - 0xDC00U);
                        p += 6;
                    } else {
                        sb_free(&out);
                        return nullptr;
                    }
                } else if ((code >= 0xDC00U) && (code <= 0xDFFFU)) {
                    sb_free(&out);
                    return nullptr;
                }
                if (!sb_append_utf8(&out, code)) {
                    sb_free(&out);
                    return nullptr;
                }
            } else {
                char ch[2] = {*p, '\0'};
                if (!sb_append(&out, ch)) {
                    sb_free(&out);
                    return nullptr;
                }
            }
        } else {
            char ch[2] = {*p, '\0'};
            if (!sb_append(&out, ch)) {
                sb_free(&out);
                return nullptr;
            }
        }
        p++;
    }

    if (*p != '"') {
        sb_free(&out);
        return nullptr;
    }

    if (out.data == nullptr) {
        return code_lens_alloc_zeroed(1U, 1U);
    }
    return out.data;
}

/* The json_get_* helpers below all take a pointer to an OBJECT (its '{')
 * and look up a member of that object only. */
static char *json_get_string(const char *object, const char *key)
{
    const char *value = json_object_value(object, key);

    if ((value == nullptr) || (*value != '"')) {
        return nullptr;
    }
    return parse_json_string_at(value);
}

static int json_get_int(const char *object, const char *key, int default_value)
{
    const char *value = json_object_value(object, key);

    if (value == nullptr) {
        return default_value;
    }
    return atoi(value);
}

static bool json_get_bool(const char *object, const char *key, bool default_value)
{
    const char *value = json_object_value(object, key);

    if (value == nullptr) {
        return default_value;
    }
    if (strncmp(value, "true", 4U) == 0) {
        return true;
    }
    if (strncmp(value, "false", 5U) == 0) {
        return false;
    }
    return default_value;
}

static char *json_get_id_raw(const char *object)
{
    const char *start = json_object_value(object, "id");
    const char *end;

    if (start == nullptr) {
        return nullptr;
    }
    end = json_skip_value(start);
    if ((end == nullptr) || (end == start)) {
        return nullptr;
    }
    return copy_bytes(start, (size_t)(end - start));
}

static char *json_get_arguments_object(const char *params)
{
    const char *start = json_object_value(params, "arguments");
    const char *end;

    if ((start == nullptr) || (*start != '{')) {
        return nullptr;
    }
    end = json_skip_value(start);
    if (end == nullptr) {
        return nullptr;
    }
    return copy_bytes(start, (size_t)(end - start));
}

static void write_message(const char *json)
{
    if (response_framing == MCP_FRAMING_NEWLINE) {
        (void)printf("%s\n", json);
    } else {
        size_t len = strlen(json);
        (void)printf("Content-Length: %zu\r\n\r\n%s", len, json);
    }
    (void)fflush(stdout);
}

static void respond_preamble(StringBuilder *out, const char *id)
{
    (void)sb_append(out, "{\"jsonrpc\":\"2.0\",\"id\":");
    (void)sb_append(out, id == nullptr ? "null" : id);
}

static void respond_error(const char *id, int code, const char *message)
{
    StringBuilder out = {0};
    char code_text[32];

    respond_preamble(&out, id);
    (void)sb_append(&out, ",\"error\":{\"code\":");
    (void)snprintf(code_text, sizeof(code_text), "%d", code);
    (void)sb_append(&out, code_text);
    (void)sb_append(&out, ",\"message\":");
    (void)json_escape_append(&out, message);
    (void)sb_append(&out, "}}");
    write_message(out.data);
    sb_free(&out);
}

static void respond_text(const char *id, const char *text)
{
    StringBuilder out = {0};

    respond_preamble(&out, id);
    (void)sb_append(&out, ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":");
    (void)json_escape_append(&out, text);
    (void)sb_append(&out, "}]}}");
    write_message(out.data);
    sb_free(&out);
}

static void respond_initialize(const char *id)
{
    StringBuilder out = {0};
    static const char instructions[] =
        "code-lens provides code intelligence for Clojure, Java, and C repositories. "
        "When working with these languages, prefer query and context over text search: "
        "query finds symbol definitions and Clojure :keyword usages by name, while context "
        "returns a symbol's definitions and resolved call sites, or a semantic Class Dossier "
        "when the target is a Java type. It understands Clojure "
        ":as/:refer, Java imports and receiver types, and C linkage and member types. Set "
        "repo to an absolute repository root or any file or directory inside it; omit repo to "
        "use the directory where the server was started, which may not be your working "
        "directory. sql runs read-only structural queries over the "
        "same repository.";

    respond_preamble(&out, id);
    (void)sb_append(
        &out,
        ",\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{}},"
        "\"serverInfo\":{\"name\":\"code-lens\",\"version\":");
    (void)json_escape_append(&out, CODE_LENS_VERSION);
    (void)sb_append(&out, "},\"instructions\":");
    (void)json_escape_append(&out, instructions);
    (void)sb_append(&out, "}}");
    write_message(out.data);
    sb_free(&out);
}

static void respond_tools_list(const char *id)
{
    static const char query_tool[] =
        "{\"name\":\"query\",\"description\":\"Ranked prefix search over definitions and"
        " Clojure keywords. Keyword results cover distinct files before repeated uses from"
        " one file. Workspace is the default; scope can opt into resolved dependency"
        " sources from Maven, Leiningen, or tools.deps projects. repo may be an absolute"
        " repository root or any path inside it. When omitted, it defaults to the directory"
        " the code-lens server was started in, which is not necessarily your working directory.\","
        "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"repo\":{\"type\":\"string\","
        "\"description\":\"Repository root or any file or directory inside it. Must be absolute."
        " Defaults to the directory the code-lens server was started in, which is not"
        " necessarily your working directory.\"},"
        "\"aliases\":{\"type\":\"string\",\"description\":\"Clojure tools.deps aliases such as :dev:reporting used while resolving dependency sources.\"},"
        "\"query\":{\"type\":\"string\"},\"limit\":{\"type\":\"number\",\"default\":10},"
        "\"excludeTests\":{\"type\":\"boolean\",\"default\":false},"
        "\"kind\":{\"type\":\"string\",\"enum\":[\"function\",\"var\",\"macro\",\"multimethod\","
        "\"method\",\"protocol\",\"record\",\"type\",\"test\",\"class\",\"interface\","
        "\"enum\",\"annotation\",\"constructor\",\"field\",\"enum_constant\",\"module\","
        "\"variable\",\"struct\",\"union\",\"typedef\",\"keyword\"],"
        "\"description\":\"Restrict results to one kind. Symbol kinds filter the Symbols"
        " section and suppress the Keywords section; 'keyword' returns Clojure keyword"
        " usages only.\"},"
        "\"scope\":{\"type\":\"string\",\"enum\":[\"workspace\",\"dependencies\",\"all\"],"
        "\"default\":\"workspace\",\"description\":\"Search workspace definitions by"
        " default; resolved Maven, Leiningen, or tools.deps sources are opt-in.\"},"
        "\"dependency\":{\"type\":\"string\",\"description\":\"Optional GAV glob, for"
        " example org.jline:*.\"},"
        "\"path\":{\"type\":\"string\",\"description\":\"Case-insensitive substring of"
        " result file paths; use it to narrow common keywords to a component or directory.\"}},"
        "\"required\":[\"query\"]}}";
    static const char context_tool[] =
        "{\"name\":\"context\",\"description\":\"Show definitions and resolved call sites"
        " with snippets. An exact Java type automatically returns a bounded Class Dossier"
        " with members, hierarchy, relevant inherited behavior, support types, overrides,"
        " and purpose-grouped representative usages. Resolves Clojure :as/:refer, Java"
        " imports and receiver types, and C"
        " linkage and aggregate members. Dependency definitions are an automatic fallback;"
        " workspace call sites rank first. Accepts str/join, Type.member, Type#member,"
        " package.Type, and ptr->field qualifiers. repo may be an absolute repository root"
        " or any path inside it. When omitted, it defaults to the directory the code-lens"
        " server was started in, which is not necessarily your working directory. namespace"
        " and path narrow candidate definitions and their references.\","
        "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"repo\":{\"type\":\"string\","
        "\"description\":\"Repository root or any file or directory inside it. Must be absolute."
        " Defaults to the directory the code-lens server was started in, which is not"
        " necessarily your working directory.\"},"
        "\"aliases\":{\"type\":\"string\",\"description\":\"Clojure tools.deps aliases such as :dev:reporting used while resolving dependency sources.\"},"
        "\"name\":{\"type\":\"string\",\"description\":\"Symbol name, optionally qualified"
        " with a Clojure namespace or alias, Java type or package, or C aggregate.\"},"
        "\"namespace\":{\"type\":\"string\",\"description\":\"Exact namespace of matching"
        " definitions (Java enclosing type; C file or type scope).\"},"
        "\"path\":{\"type\":\"string\",\"description\":\"Case-insensitive substring of"
        " matching definition file paths. A workspace calling file is also accepted as"
        " dependency-resolution context.\"},"
        "\"excludeTests\":{\"type\":\"boolean\",\"default\":false,"
        "\"description\":\"Drop definitions, references, and snippets in test files and"
        " test-kind definitions.\"}},\"required\":[\"name\"]}}";
    static const char sql_tool[] =
        "{\"name\":\"sql\",\"description\":\"Run a read-only SQLite SELECT over extracted"
        " code data when query and context do not fit. ATTACH, PRAGMA, and writes are denied;"
        " output is capped at 100 rows and only the first statement runs. Tables and views:"
        " Symbol(repo, name, kind, namespace, filePath, startLine, endLine, content, doc),"
        " Ref(fileId, filePath, symbol, symbolBase, targetNamespace, lineNumber, columnNumber,"
        " startByte, endByte), Keyword(fileId, filePath, keyword, keywordBase, qualifier,"
        " targetNamespace, lineNumber, columnNumber), File(rowid, repo, path, namespace, size),"
        " Alias(repo, filePath, namespace, alias), Referred(repo, filePath, namespace, symbol),"
        " DependencyArtifact(repo, coordinate, groupId, artifactId, version, scope, direct,"
        " sourceJar, sourceRoot, checksum), and DependencyFile(repo, filePath, artifactId,"
        " sourcePath, modulePath). For Java, Symbol.namespace is the fully qualified enclosing"
        " type, File.namespace is the package, Alias rows are normal imports, and Referred"
        " rows are static imports. For C, global namespaces are empty, static namespaces are"
        " file paths, and field namespaces are aggregate names. Join File via File.rowid ="
        " fileId. repo may be an absolute repository root or any path inside it. When"
        " omitted, it defaults to the directory the code-lens server was started in, which"
        " is not necessarily your working directory.\","
        "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"repo\":{\"type\":\"string\","
        "\"description\":\"Repository root or any file or directory inside it. Must be absolute."
        " Defaults to the directory the code-lens server was started in, which is not"
        " necessarily your working directory.\"},"
        "\"aliases\":{\"type\":\"string\",\"description\":\"Clojure tools.deps aliases such as :dev:reporting used while resolving dependency sources.\"},"
        "\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}}";
    StringBuilder out = {0};

    respond_preamble(&out, id);
    if (!sb_append(&out, ",\"result\":{\"tools\":[") || !sb_append(&out, query_tool) ||
        !sb_append(&out, ",") || !sb_append(&out, context_tool) || !sb_append(&out, ",") ||
        !sb_append(&out, sql_tool) || !sb_append(&out, "]}}")) {
        sb_free(&out);
        respond_error(id, -32603, "out of memory");
        return;
    }
    write_message(out.data);
    sb_free(&out);
}

static char *call_tool(const char *tool_name, const char *args)
{
    char *repo = json_get_string(args, "repo");
    char *query = json_get_string(args, "query");
    char *name = json_get_string(args, "name");
    char *kind = json_get_string(args, "kind");
    char *path = json_get_string(args, "path");
    char *scope = json_get_string(args, "scope");
    char *dependency = json_get_string(args, "dependency");
    char *namespace_name = json_get_string(args, "namespace");
    char *tools_deps_aliases = json_get_string(args, "aliases");
    int limit = json_get_int(args, "limit", 10);
    char *result = nullptr;

    if (strcmp(tool_name, "query") == 0) {
        CodeLensQueryOptions options = {
            .limit = limit,
            .exclude_tests = json_get_bool(args, "excludeTests", false),
            .kind = kind,
            .path = path,
            .scope = scope,
            .dependency = dependency,
            .tools_deps_aliases = tools_deps_aliases,
        };
        McpRepoSession session;
        char *error = mcp_repo_session_open(&session, repo, tools_deps_aliases);

        if (error != nullptr) {
            result = error;
        } else {
            result = query_symbols_ex_internal(
                session.repo_id, query, &options, &session.db);
            result = result == nullptr
                         ? alloc_printf("failed to search repo \"%s\"\n", session.repo_id)
                         : result;
            mcp_repo_session_close(&session);
        }
    } else if (strcmp(tool_name, "context") == 0) {
        CodeLensContextOptions options = {
            .exclude_tests = json_get_bool(args, "excludeTests", false),
            .namespace_name = namespace_name,
            .path = path,
            .tools_deps_aliases = tools_deps_aliases,
        };
        McpRepoSession session;
        char *error = mcp_repo_session_open(&session, repo, tools_deps_aliases);

        if (error != nullptr) {
            result = error;
        } else {
            result = context_symbol_ex_internal(
                session.repo_id, name, &options, &session.db);
            result = result == nullptr
                         ? alloc_printf("failed to read context from repo \"%s\"\n",
                                        session.repo_id)
                         : result;
            mcp_repo_session_close(&session);
        }
    } else if (strcmp(tool_name, "sql") == 0) {
        McpRepoSession session;
        char *error = mcp_repo_session_open(&session, repo, tools_deps_aliases);

        if (error != nullptr) {
            result = error;
        } else {
            bool succeeded = false;

            result = query_with_open_repo_db(&session.db, query, &succeeded);
            if (result == nullptr) {
                result = alloc_printf("failed to execute SQL against repo \"%s\"\n",
                                      session.repo_id);
            } else if (succeeded) {
                char *prefixed = alloc_printf("repo: %s\n%s", session.repo_id, result);

                if (prefixed != nullptr) {
                    result = prefixed;
                }
            }
            mcp_repo_session_close(&session);
        }
    }

    return result;
}

#ifdef CODE_LENS_NO_MAIN
char *code_lens_test_mcp_call_tool(const char *tool_name, const char *args)
{
    return call_tool(tool_name, args);
}

const char *code_lens_test_json_object_value(const char *object, const char *key)
{
    return json_object_value(object, key);
}

char *code_lens_test_json_get_string(const char *object, const char *key)
{
    return json_get_string(object, key);
}

char *code_lens_test_json_get_id_raw(const char *object)
{
    return json_get_id_raw(object);
}

char *code_lens_test_json_get_arguments(const char *params)
{
    return json_get_arguments_object(params);
}
#endif

static void handle_message(const char *message)
{
    char *id = json_get_id_raw(message);
    char *method = json_get_string(message, "method");

    if (method == nullptr) {
        respond_error(id, -32600, "missing method");
        return;
    }

    if (strcmp(method, "initialize") == 0) {
        respond_initialize(id);
    } else if (strcmp(method, "notifications/initialized") == 0) {
        /* Notification: no response required. */
    } else if (strcmp(method, "tools/list") == 0) {
        respond_tools_list(id);
    } else if (strcmp(method, "tools/call") == 0) {
        const char *params = json_object_value(message, "params");
        char *tool_name = json_get_string(params, "name");
        char *args = json_get_arguments_object(params);
        char *result;

        if ((tool_name == nullptr) || (args == nullptr)) {
            respond_error(id, -32602, "tools/call requires name and arguments");
        } else {
            result = call_tool(tool_name, args);
            if (result == nullptr) {
                respond_error(id, -32603, "tool execution failed");
            } else {
                respond_text(id, result);
            }
        }
    } else {
        respond_error(id, -32601, "unknown method");
    }
}

static char *read_content_length_message(const char *first_line)
{
    size_t length = 0U;
    char header[4096];
    char *body;

    if (sscanf(first_line, "Content-Length: %zu", &length) != 1 || length == 0U) {
        return nullptr;
    }

    while (fgets(header, sizeof(header), stdin) != nullptr) {
        if ((strcmp(header, "\r\n") == 0) || (strcmp(header, "\n") == 0)) {
            break;
        }
    }

    body = code_lens_alloc(length + 1U);
    if (body == nullptr) {
        return nullptr;
    }
    if (fread(body, 1U, length, stdin) != length) {
        return nullptr;
    }
    body[length] = '\0';
    return body;
}

int code_lens_mcp_main(void)
{
    char line[65536];

#ifdef _WIN32
    /* Content-Length is a byte count; CRT text translation would otherwise
     * alter MCP framing on stdin/stdout. */
    cp_set_binary_stdio();
#endif

    while (fgets(line, sizeof(line), stdin) != nullptr) {
        CodeLensArenaMark mark = code_lens_arena_mark();
        char *message;

        if (strncmp(line, "Content-Length:", strlen("Content-Length:")) == 0) {
            response_framing = MCP_FRAMING_CONTENT_LENGTH;
            message = read_content_length_message(line);
        } else {
            response_framing = MCP_FRAMING_NEWLINE;
            message = copy_bytes(line, strlen(line));
        }

        if (message == nullptr) {
            code_lens_arena_reset(mark);
            continue;
        }
        handle_message(message);
        code_lens_arena_reset(mark);
    }

    return 0;
}

/* CLI */

static void print_usage(FILE *stream)
{
    (void)fprintf(stream,
                  "code-lens %s (%s)\n"
                  "\n"
                  "Usage:\n"
                  "  code-lens help\n"
                  "  code-lens version\n"
                  "  code-lens index --repo <path> [--aliases :a[:b...]]\n"
                  "  code-lens remove --repo <path>\n"
                  "  code-lens list\n"
                  "  code-lens query --repo <path> [--aliases :a[:b...]] [--exclude-tests] [--kind <kind>] "
                  "[--path <substring>] [--scope workspace|dependencies|all] "
                  "[--dependency <gav-glob>] [--limit <n>] \"<terms>\"\n"
                  "  code-lens context --repo <path> --name <symbol> [--aliases :a[:b...]] [--namespace <namespace>] "
                  "[--path <substring>] [--exclude-tests]\n"
                  "  code-lens sql --repo <path> [--aliases :a[:b...]] \"<query>\"\n"
                  "  code-lens mcp\n"
                  "\n"
                  "A repository is identified by its path; any path inside an indexed\n"
                  "repository resolves to it.\n",
                  CODE_LENS_VERSION,
                  CODE_LENS_C_STANDARD);
}

static const char *arg_value(int argc, char **argv, const char *name)
{
    for (int i = 2; i + 1 < argc; i++) {
        if (strcmp(argv[i], name) == 0) {
            return argv[i + 1];
        }
    }
    return nullptr;
}

static bool arg_present(int argc, char **argv, const char *name)
{
    for (int i = 2; i < argc; i++) {
        if (strcmp(argv[i], name) == 0) {
            return true;
        }
    }
    return false;
}

static int parse_positive_int_arg(const char *text, int default_value)
{
    char *end = nullptr;
    long value;

    if ((text == nullptr) || (text[0] == '\0') || (text[0] == '-')) {
        return default_value;
    }
    errno = 0;
    value = strtol(text, &end, 10);
    if ((end == text) || (end == nullptr) || (*end != '\0') || (errno == ERANGE) ||
        (value <= 0L) || (value > INT_MAX)) {
        return default_value;
    }
    return (int)value;
}

static bool arg_consumes_value(const char *arg)
{
    return (strcmp(arg, "--repo") == 0) || (strcmp(arg, "--name") == 0) ||
           (strcmp(arg, "--limit") == 0) || (strcmp(arg, "--kind") == 0) ||
           (strcmp(arg, "--path") == 0) || (strcmp(arg, "--namespace") == 0) ||
           (strcmp(arg, "--scope") == 0) || (strcmp(arg, "--dependency") == 0) ||
           (strcmp(arg, "--aliases") == 0);
}

static bool arg_is_standalone_flag(const char *arg)
{
    return strcmp(arg, "--exclude-tests") == 0;
}

static char *free_arg_text(int argc, char **argv)
{
    size_t len = 0U;
    char *text;
    size_t pos = 0U;

    for (int i = 2; i < argc; i++) {
        if (arg_consumes_value(argv[i])) {
            i++;
            continue;
        }
        if (arg_is_standalone_flag(argv[i])) {
            continue;
        }
        len += strlen(argv[i]) + 1U;
    }

    if (len == 0U) {
        return nullptr;
    }

    text = code_lens_alloc(len);
    if (text == nullptr) {
        return nullptr;
    }
    text[0] = '\0';

    for (int i = 2; i < argc; i++) {
        if (arg_consumes_value(argv[i])) {
            i++;
            continue;
        }
        if (arg_is_standalone_flag(argv[i])) {
            continue;
        }
        if (pos > 0U) {
            text[pos++] = ' ';
        }
        size_t arg_len = strlen(argv[i]);
        (void)memcpy(text + pos, argv[i], arg_len);
        pos += arg_len;
        text[pos] = '\0';
    }

    return text;
}

static int run_index(int argc, char **argv)
{
    const char *path = arg_value(argc, argv, "--repo");
    const char *aliases = arg_value(argc, argv, "--aliases");
    CodeLensDependencyOptions options = {.tools_deps_aliases = aliases};
    CodeLensIndexStats stats;

    if (path == nullptr) {
        /* Old muscle memory: index used to take --path alongside a name. */
        path = arg_value(argc, argv, "--path");
    }
    if (path == nullptr) {
        (void)fprintf(stderr, "code-lens: index requires --repo <path>\n");
        return 2;
    }

    if (!tools_deps_aliases_valid(aliases)) {
        (void)fprintf(stderr, "code-lens: --aliases requires a non-empty colon-prefixed alias string\n");
        return 2;
    }
    if (code_lens_index_repository_ex(path, &options, &stats) != 0) {
        (void)fprintf(stderr, "code-lens: indexing failed for %s\n", path);
        return 1;
    }

    (void)printf("indexed %s: %zu files, %zu symbols, %zu references, %zu keywords, "
                 "%zu aliases in %.3fs (git %" PRIu64 "/%zu)\n",
                 path,
                 stats.file_count,
                 stats.symbol_count,
                 stats.reference_count,
                 stats.keyword_count,
                 stats.alias_count,
                 stats.elapsed_seconds,
                 stats.git_blob_reads,
                 stats.file_count);
    return 0;
}

static int run_remove(int argc, char **argv)
{
    const char *repo = arg_value(argc, argv, "--repo");
    char *result;

    if (repo == nullptr) {
        (void)fprintf(stderr, "code-lens: remove requires --repo <path>\n");
        return 2;
    }

    result = code_lens_remove_repo(repo);
    if (result == nullptr) {
        (void)fprintf(stderr, "code-lens: remove failed for %s\n", repo);
        return 1;
    }
    (void)printf("%s", result);
    return 0;
}

static int run_list(void)
{
    char *text = code_lens_list_repos();
    if (text == nullptr) {
        return 1;
    }
    (void)printf("%s\n", text);
    return 0;
}

static int run_query_like(int argc, char **argv, const char *command)
{
    const char *repo = arg_value(argc, argv, "--repo");
    const char *aliases = arg_value(argc, argv, "--aliases");
    CodeLensDependencyOptions dependency_options = {.tools_deps_aliases = aliases};
    char *text = free_arg_text(argc, argv);
    char *result = nullptr;

    if (repo == nullptr) {
        (void)fprintf(stderr, "code-lens: %s requires --repo <repo> and query text\n", command);
        return 2;
    }

    if (!tools_deps_aliases_valid(aliases)) {
        (void)fprintf(stderr, "code-lens: --aliases requires a non-empty colon-prefixed alias string\n");
        return 2;
    }

    if ((strcmp(command, "context") != 0) && (text == nullptr)) {
        (void)fprintf(stderr, "code-lens: %s requires query text\n", command);
        return 2;
    }

    if (strcmp(command, "query") == 0) {
        CodeLensQueryOptions options = {
            .limit = parse_positive_int_arg(arg_value(argc, argv, "--limit"), 10),
            .exclude_tests = arg_present(argc, argv, "--exclude-tests"),
            .kind = arg_value(argc, argv, "--kind"),
            .path = arg_value(argc, argv, "--path"),
            .scope = arg_value(argc, argv, "--scope"),
            .dependency = arg_value(argc, argv, "--dependency"),
            .tools_deps_aliases = aliases,
        };

        result = code_lens_query_symbols_ex(repo, text, &options);
    } else if (strcmp(command, "context") == 0) {
        const char *name = arg_value(argc, argv, "--name");
        CodeLensContextOptions options = {
            .exclude_tests = arg_present(argc, argv, "--exclude-tests"),
            .namespace_name = arg_value(argc, argv, "--namespace"),
            .path = arg_value(argc, argv, "--path"),
            .tools_deps_aliases = aliases,
        };

        if ((name == nullptr) && (text == nullptr)) {
            (void)fprintf(stderr, "code-lens: context requires --name <symbol> or text\n");
            return 2;
        }
        result = code_lens_context_symbol_ex(repo, name == nullptr ? text : name, &options);
    } else {
        result = code_lens_run_sql_ex(repo, text, &dependency_options);
    }

    if (result == nullptr) {
        (void)fprintf(stderr, "code-lens: %s failed\n", command);
        return 1;
    }
    (void)printf("%s\n", result);
    return 0;
}

int code_lens_cli_main(int argc, char **argv)
{
    const char *command;

    if (argc < 2) {
        print_usage(stdout);
        return 0;
    }

    command = argv[1];

    if ((strcmp(command, "help") == 0) || (strcmp(command, "--help") == 0) ||
        (strcmp(command, "-h") == 0)) {
        print_usage(stdout);
        return 0;
    }

    if ((strcmp(command, "version") == 0) || (strcmp(command, "--version") == 0)) {
        if ((argc >= 3) && (strcmp(argv[2], "--deps") == 0)) {
            (void)printf("%s\n", CODE_LENS_VERSION);
            (void)printf("sqlite %s\n", code_lens_sqlite_version());
            (void)printf("tree-sitter-clojure abi=%u symbols=%u\n",
                         code_lens_clojure_language_abi_version(),
                         code_lens_clojure_language_symbol_count());
            (void)printf("tree-sitter-java abi=%u symbols=%u\n",
                         code_lens_java_language_abi_version(),
                         code_lens_java_language_symbol_count());
            (void)printf("tree-sitter-c abi=%u symbols=%u\n",
                         code_lens_c_language_abi_version(),
                         code_lens_c_language_symbol_count());
        } else {
            (void)printf("%s\n", CODE_LENS_VERSION);
        }
        return 0;
    }

    if (strcmp(command, "index") == 0) {
        return run_index(argc, argv);
    }

    if (strcmp(command, "remove") == 0) {
        return run_remove(argc, argv);
    }

    if (strcmp(command, "list") == 0) {
        return run_list();
    }

    if ((strcmp(command, "query") == 0) || (strcmp(command, "context") == 0) ||
        (strcmp(command, "sql") == 0)) {
        return run_query_like(argc, argv, command);
    }

    if (strcmp(command, "mcp") == 0) {
        return code_lens_mcp_main();
    }

    (void)fprintf(stderr, "code-lens: unknown command '%s'\n", command);
    print_usage(stderr);
    return 2;
}

/* Program entry point */

#ifndef CODE_LENS_NO_MAIN
int main(int argc, char **argv)
{
    return code_lens_cli_main(argc, argv);
}
#endif
