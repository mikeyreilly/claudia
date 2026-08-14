#include "windows_compat.h"

#include <limits.h>
#include <stdarg.h>
#include <stdbool.h>
#include <string.h>

static void cp_set_errno_from_win32(DWORD error)
{
    switch (error) {
    case ERROR_FILE_NOT_FOUND:
    case ERROR_PATH_NOT_FOUND:
        errno = ENOENT;
        break;
    case ERROR_ACCESS_DENIED:
    case ERROR_SHARING_VIOLATION:
    case ERROR_LOCK_VIOLATION:
        errno = EACCES;
        break;
    case ERROR_ALREADY_EXISTS:
    case ERROR_FILE_EXISTS:
        errno = EEXIST;
        break;
    case ERROR_DISK_FULL:
        errno = ENOSPC;
        break;
    default:
        errno = EIO;
        break;
    }
}

static void cp_slashes_to_forward(char *path)
{
    if (path == NULL) {
        return;
    }
    for (; *path != '\0'; path++) {
        if (*path == '\\') {
            *path = '/';
        }
    }
}

int cp_mkdir(const char *path, int mode)
{
    (void)mode;
    return _mkdir(path);
}

int cp_mkstemp(char *path_template)
{
    size_t len;

    if (path_template == NULL) {
        errno = EINVAL;
        return -1;
    }
    len = strlen(path_template) + 1U;
    if (_mktemp_s(path_template, len) != 0) {
        errno = EEXIST;
        return -1;
    }
    return _open(path_template, _O_RDWR | _O_CREAT | _O_EXCL | _O_BINARY,
                 _S_IREAD | _S_IWRITE);
}

char *cp_realpath(const char *path, char *resolved)
{
    HANDLE handle;
    char final_path[PATH_MAX];
    DWORD len;
    const char *source;

    if ((path == NULL) || (resolved == NULL)) {
        errno = EINVAL;
        return NULL;
    }
    handle = CreateFileA(path, FILE_READ_ATTRIBUTES,
                         FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                         NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (handle == INVALID_HANDLE_VALUE) {
        cp_set_errno_from_win32(GetLastError());
        return NULL;
    }
    len = GetFinalPathNameByHandleA(handle, final_path, (DWORD)sizeof(final_path),
                                    FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
    CloseHandle(handle);
    if ((len == 0U) || (len >= (DWORD)sizeof(final_path))) {
        cp_set_errno_from_win32(GetLastError());
        return NULL;
    }
    source = final_path;
    if (strncmp(source, "\\\\?\\UNC\\", 8U) == 0) {
        if ((len - 6U) >= PATH_MAX) {
            errno = ENAMETOOLONG;
            return NULL;
        }
        resolved[0] = '/';
        resolved[1] = '/';
        (void)memcpy(resolved + 2, source + 8, len - 7U);
    } else {
        if (strncmp(source, "\\\\?\\", 4U) == 0) {
            source += 4;
            len -= 4U;
        }
        if (len >= PATH_MAX) {
            errno = ENAMETOOLONG;
            return NULL;
        }
        (void)memcpy(resolved, source, (size_t)len + 1U);
    }
    cp_slashes_to_forward(resolved);
    if ((resolved[0] >= 'a') && (resolved[0] <= 'z') && (resolved[1] == ':')) {
        resolved[0] = (char)(resolved[0] - ('a' - 'A'));
    }
    return resolved;
}

int cp_rename_replace(const char *old_path, const char *new_path)
{
    if (MoveFileExA(old_path, new_path,
                    MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH) != 0) {
        return 0;
    }
    cp_set_errno_from_win32(GetLastError());
    return -1;
}

intptr_t cp_read(int fd, void *buffer, size_t count)
{
    unsigned int chunk = count > (size_t)INT_MAX ? (unsigned int)INT_MAX : (unsigned int)count;
    return (intptr_t)_read(fd, buffer, chunk);
}

intptr_t cp_write(int fd, const void *buffer, size_t count)
{
    unsigned int chunk = count > (size_t)INT_MAX ? (unsigned int)INT_MAX : (unsigned int)count;
    return (intptr_t)_write(fd, buffer, chunk);
}

static SRWLOCK cp_positioned_io_lock = SRWLOCK_INIT;

static intptr_t cp_positioned_io(int fd, void *buffer, size_t count, int64_t offset, int writing)
{
    int64_t original;
    int64_t positioned;
    intptr_t result;
    unsigned int chunk = count > (size_t)INT_MAX ? (unsigned int)INT_MAX : (unsigned int)count;

    /* CRT descriptors are not opened FILE_FLAG_OVERLAPPED. Serialize the
     * seek/I/O/restore sequence so pwrite calls from raw graft workers retain
     * POSIX positioned-I/O semantics instead of racing on the shared offset. */
    AcquireSRWLockExclusive(&cp_positioned_io_lock);
    original = _lseeki64(fd, 0, SEEK_CUR);
    positioned = original < 0 ? -1 : _lseeki64(fd, offset, SEEK_SET);
    if (positioned < 0) {
        result = -1;
    } else {
        result = writing ? (intptr_t)_write(fd, buffer, chunk)
                         : (intptr_t)_read(fd, buffer, chunk);
        if (_lseeki64(fd, original, SEEK_SET) < 0) {
            result = -1;
        }
    }
    ReleaseSRWLockExclusive(&cp_positioned_io_lock);
    return result;
}

intptr_t cp_pread(int fd, void *buffer, size_t count, int64_t offset)
{
    return cp_positioned_io(fd, buffer, count, offset, 0);
}

intptr_t cp_pwrite(int fd, const void *buffer, size_t count, int64_t offset)
{
    return cp_positioned_io(fd, (void *)buffer, count, offset, 1);
}

int cp_ftruncate(int fd, int64_t length)
{
    errno_t rc = _chsize_s(fd, (uint64_t)length);

    if (rc != 0) {
        errno = (int)rc;
        return -1;
    }
    return 0;
}

long cp_processor_count(void)
{
    DWORD count = GetActiveProcessorCount(ALL_PROCESSOR_GROUPS);
    return count == 0U ? 1L : (long)count;
}

void cp_set_binary_stdio(void)
{
    (void)_setmode(_fileno(stdin), _O_BINARY);
    (void)_setmode(_fileno(stdout), _O_BINARY);
}

int cp_lock_fd(int fd)
{
    HANDLE handle = (HANDLE)_get_osfhandle(fd);
    OVERLAPPED overlap;

    if (handle == INVALID_HANDLE_VALUE) {
        errno = EBADF;
        return -1;
    }
    memset(&overlap, 0, sizeof(overlap));
    if (LockFileEx(handle, LOCKFILE_EXCLUSIVE_LOCK, 0, MAXDWORD, MAXDWORD, &overlap) == 0) {
        cp_set_errno_from_win32(GetLastError());
        return -1;
    }
    return 0;
}

int cp_unlock_fd(int fd)
{
    HANDLE handle = (HANDLE)_get_osfhandle(fd);
    OVERLAPPED overlap;

    if (handle == INVALID_HANDLE_VALUE) {
        errno = EBADF;
        return -1;
    }
    memset(&overlap, 0, sizeof(overlap));
    if (UnlockFileEx(handle, 0, MAXDWORD, MAXDWORD, &overlap) == 0) {
        cp_set_errno_from_win32(GetLastError());
        return -1;
    }
    return 0;
}

void *cp_mmap(void *address, size_t length, int protection, int flags, int fd, int64_t offset)
{
    HANDLE file = (HANDLE)_get_osfhandle(fd);
    HANDLE mapping;
    void *view;

    (void)address;
    (void)protection;
    (void)flags;
    if ((file == INVALID_HANDLE_VALUE) || (length == 0U)) {
        errno = EINVAL;
        return MAP_FAILED;
    }
    mapping = CreateFileMappingW(file, NULL, PAGE_READONLY, 0, 0, NULL);
    if (mapping == NULL) {
        cp_set_errno_from_win32(GetLastError());
        return MAP_FAILED;
    }
    view = MapViewOfFile(mapping, FILE_MAP_READ, (DWORD)((uint64_t)offset >> 32),
                         (DWORD)((uint64_t)offset & 0xffffffffU), length);
    CloseHandle(mapping);
    if (view == NULL) {
        cp_set_errno_from_win32(GetLastError());
        return MAP_FAILED;
    }
    return view;
}

int cp_munmap(void *address, size_t length)
{
    (void)length;
    if (UnmapViewOfFile(address) == 0) {
        cp_set_errno_from_win32(GetLastError());
        return -1;
    }
    return 0;
}

struct CpDir {
    HANDLE find;
    WIN32_FIND_DATAA data;
    int first;
    struct dirent entry;
};

DIR *cp_opendir(const char *path)
{
    DIR *dir;
    size_t len;
    char *pattern;

    if (path == NULL) {
        errno = EINVAL;
        return NULL;
    }
    len = strlen(path);
    pattern = malloc(len + 3U);
    dir = calloc(1U, sizeof(*dir));
    if ((pattern == NULL) || (dir == NULL)) {
        free(pattern);
        free(dir);
        errno = ENOMEM;
        return NULL;
    }
    memcpy(pattern, path, len);
    if ((len > 0U) && (path[len - 1U] != '/') && (path[len - 1U] != '\\')) {
        pattern[len++] = '/';
    }
    pattern[len++] = '*';
    pattern[len] = '\0';
    dir->find = FindFirstFileA(pattern, &dir->data);
    free(pattern);
    if (dir->find == INVALID_HANDLE_VALUE) {
        cp_set_errno_from_win32(GetLastError());
        free(dir);
        return NULL;
    }
    dir->first = 1;
    return dir;
}

struct dirent *cp_readdir(DIR *dir)
{
    if (dir == NULL) {
        errno = EINVAL;
        return NULL;
    }
    if (dir->first != 0) {
        dir->first = 0;
    } else if (FindNextFileA(dir->find, &dir->data) == 0) {
        DWORD error = GetLastError();
        if (error != ERROR_NO_MORE_FILES) {
            cp_set_errno_from_win32(error);
        }
        return NULL;
    }
    (void)strncpy_s(dir->entry.d_name, sizeof(dir->entry.d_name),
                    dir->data.cFileName, _TRUNCATE);
    if ((dir->data.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0U) {
        dir->entry.d_type = DT_LNK;
    } else if ((dir->data.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0U) {
        dir->entry.d_type = DT_DIR;
    } else {
        dir->entry.d_type = DT_REG;
    }
    return &dir->entry;
}

int cp_closedir(DIR *dir)
{
    int rc = 0;

    if (dir == NULL) {
        errno = EINVAL;
        return -1;
    }
    if ((dir->find != INVALID_HANDLE_VALUE) && (FindClose(dir->find) == 0)) {
        cp_set_errno_from_win32(GetLastError());
        rc = -1;
    }
    free(dir);
    return rc;
}

typedef struct {
    void *(*routine)(void *);
    void *argument;
    void *result;
} CpThreadStart;

static unsigned __stdcall cp_thread_start(void *argument)
{
    CpThreadStart *start = argument;
    void *(*routine)(void *) = start->routine;
    void *routine_argument = start->argument;

    free(start);
    (void)routine(routine_argument);
    return 0U;
}

int pthread_create(pthread_t *thread, const void *attributes,
                   void *(*start_routine)(void *), void *argument)
{
    CpThreadStart *start;
    uintptr_t handle;

    (void)attributes;
    if ((thread == NULL) || (start_routine == NULL)) {
        return EINVAL;
    }
    start = malloc(sizeof(*start));
    if (start == NULL) {
        return ENOMEM;
    }
    start->routine = start_routine;
    start->argument = argument;
    start->result = NULL;
    handle = _beginthreadex(NULL, 0U, cp_thread_start, start, 0U, NULL);
    if (handle == 0U) {
        int error = errno == 0 ? EAGAIN : errno;
        free(start);
        return error;
    }
    *thread = (HANDLE)handle;
    return 0;
}

int pthread_join(pthread_t thread, void **result)
{
    DWORD wait;

    if (thread == NULL) {
        return EINVAL;
    }
    wait = WaitForSingleObject(thread, INFINITE);
    if (wait != WAIT_OBJECT_0) {
        return EINVAL;
    }
    CloseHandle(thread);
    if (result != NULL) {
        *result = NULL;
    }
    return 0;
}

int pthread_mutex_init(pthread_mutex_t *mutex, const void *attributes)
{
    (void)attributes;
    InitializeSRWLock(mutex);
    return 0;
}

int pthread_mutex_destroy(pthread_mutex_t *mutex)
{
    (void)mutex;
    return 0;
}

int pthread_mutex_lock(pthread_mutex_t *mutex)
{
    AcquireSRWLockExclusive(mutex);
    return 0;
}

int pthread_mutex_unlock(pthread_mutex_t *mutex)
{
    ReleaseSRWLockExclusive(mutex);
    return 0;
}

int pthread_cond_init(pthread_cond_t *condition, const void *attributes)
{
    (void)attributes;
    InitializeConditionVariable(condition);
    return 0;
}

int pthread_cond_destroy(pthread_cond_t *condition)
{
    (void)condition;
    return 0;
}

int pthread_cond_wait(pthread_cond_t *condition, pthread_mutex_t *mutex)
{
    return SleepConditionVariableSRW(condition, mutex, INFINITE, 0U) != 0 ? 0 : EINVAL;
}

int pthread_cond_signal(pthread_cond_t *condition)
{
    WakeConditionVariable(condition);
    return 0;
}

int pthread_cond_broadcast(pthread_cond_t *condition)
{
    WakeAllConditionVariable(condition);
    return 0;
}

static BOOL CALLBACK cp_once_callback(PINIT_ONCE once, PVOID parameter, PVOID *context)
{
    void (*routine)(void) = parameter;
    (void)once;
    (void)context;
    routine();
    return TRUE;
}

int pthread_once(pthread_once_t *once, void (*routine)(void))
{
    return InitOnceExecuteOnce(once, cp_once_callback, (PVOID)routine, NULL) != 0 ? 0 : EINVAL;
}

static int cp_run_process_redirect(const char *cwd,
                                   const char *const argv[],
                                   uint32_t timeout_ms,
                                   FILE *child_stdout)
{
    char previous[PATH_MAX];
    intptr_t child;
    DWORD wait_result;
    DWORD exit_code = 1U;
    bool changed_directory = false;
    int saved_stdout = -1;

    if ((argv == NULL) || (argv[0] == NULL) || (argv[0][0] == '\0') ||
        (child_stdout == NULL)) {
        return -1;
    }
    if ((cwd != NULL) && (cwd[0] != '\0')) {
        if ((_getcwd(previous, sizeof(previous)) == NULL) || (_chdir(cwd) != 0)) {
            return -1;
        }
        changed_directory = true;
    }
    (void)fflush(stdout);
    (void)fflush(child_stdout);
    saved_stdout = _dup(_fileno(stdout));
    if ((saved_stdout < 0) || (_dup2(_fileno(child_stdout), _fileno(stdout)) != 0)) {
        if (saved_stdout >= 0) {
            (void)_close(saved_stdout);
        }
        if (changed_directory) {
            (void)_chdir(previous);
        }
        return -1;
    }
    child = _spawnvp(_P_NOWAIT, argv[0], argv);
    (void)fflush(stdout);
    (void)_dup2(saved_stdout, _fileno(stdout));
    (void)_close(saved_stdout);
    if (changed_directory) {
        (void)_chdir(previous);
    }
    if (child == (intptr_t)-1) {
        return -1;
    }

    wait_result = WaitForSingleObject((HANDLE)child,
                                      timeout_ms == 0U ? INFINITE : (DWORD)timeout_ms);
    if (wait_result == WAIT_TIMEOUT) {
        (void)TerminateProcess((HANDLE)child, 124U);
        (void)WaitForSingleObject((HANDLE)child, INFINITE);
        (void)CloseHandle((HANDLE)child);
        return 124;
    }
    if ((wait_result != WAIT_OBJECT_0) ||
        (GetExitCodeProcess((HANDLE)child, &exit_code) == 0)) {
        (void)CloseHandle((HANDLE)child);
        return -1;
    }
    (void)CloseHandle((HANDLE)child);
    return exit_code <= (DWORD)INT_MAX ? (int)exit_code : 1;
}

int cp_run_process(const char *cwd, const char *const argv[], uint32_t timeout_ms)
{
    return cp_run_process_redirect(cwd, argv, timeout_ms, stderr);
}

int cp_run_process_output(const char *cwd,
                          const char *const argv[],
                          uint32_t timeout_ms,
                          const char *output_path)
{
    FILE *output;
    int rc;

    if (output_path == NULL) {
        return -1;
    }
    output = fopen(output_path, "wb");
    if (output == NULL) {
        return -1;
    }
    rc = cp_run_process_redirect(cwd, argv, timeout_ms, output);
    if (fclose(output) != 0) {
        return -1;
    }
    return rc;
}
