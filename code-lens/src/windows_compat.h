#ifndef CODE_LENS_WINDOWS_COMPAT_H
#define CODE_LENS_WINDOWS_COMPAT_H

/* Small POSIX compatibility surface used by code-lens's Windows build.
 * Keep this private to the application: vendored code uses its own native
 * Windows paths and configuration. */

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>

#include <direct.h>
#include <errno.h>
#include <fcntl.h>
#include <io.h>
#include <process.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <sys/types.h>

#ifndef PATH_MAX
#define PATH_MAX 32768
#endif
#ifndef O_CLOEXEC
#define O_CLOEXEC 0
#endif
#ifndef O_DIRECTORY
#define O_DIRECTORY 0
#endif
#ifndef S_ISDIR
#define S_ISDIR(mode) (((mode) & _S_IFMT) == _S_IFDIR)
#endif
#ifndef S_ISREG
#define S_ISREG(mode) (((mode) & _S_IFMT) == _S_IFREG)
#endif

/* The CRT defaults descriptors to text mode. All code-lens descriptor I/O
 * is byte-oriented, including SQLite page grafts and git object files. */
#undef O_RDONLY
#undef O_WRONLY
#undef O_RDWR
#define O_RDONLY (_O_RDONLY | _O_BINARY)
#define O_WRONLY (_O_WRONLY | _O_BINARY)
#define O_RDWR (_O_RDWR | _O_BINARY)
#ifndef O_CREAT
#define O_CREAT _O_CREAT
#endif
#ifndef O_TRUNC
#define O_TRUNC _O_TRUNC
#endif

#define open _open
#define close _close
#define unlink _unlink
#define rmdir _rmdir
#define stat _stat64
#define fstat _fstat64
#define lstat _stat64
#define ssize_t intptr_t
#define off_t int64_t

int cp_mkdir(const char *path, int mode);
int cp_mkstemp(char *path_template);
char *cp_realpath(const char *path, char *resolved);
int cp_rename_replace(const char *old_path, const char *new_path);
intptr_t cp_read(int fd, void *buffer, size_t count);
intptr_t cp_write(int fd, const void *buffer, size_t count);
intptr_t cp_pread(int fd, void *buffer, size_t count, int64_t offset);
intptr_t cp_pwrite(int fd, const void *buffer, size_t count, int64_t offset);
int cp_ftruncate(int fd, int64_t length);
long cp_processor_count(void);
int cp_lock_fd(int fd);
int cp_unlock_fd(int fd);
void cp_set_binary_stdio(void);
/* Spawn argv[0] in cwd and wait up to timeout_ms. Returns the child exit
 * code, 124 on timeout, or -1 when the process could not be started. */
int cp_run_process(const char *cwd, const char *const argv[], uint32_t timeout_ms);

#define mkdir cp_mkdir
#define mkstemp cp_mkstemp
#define realpath cp_realpath
#define rename cp_rename_replace
#define read cp_read
#define write cp_write
#define pread cp_pread
#define pwrite cp_pwrite
#define ftruncate cp_ftruncate
#define _SC_NPROCESSORS_ONLN 1
#define sysconf(what) cp_processor_count()

#define PROT_READ 1
#define MAP_PRIVATE 2
#define MAP_FAILED ((void *)(intptr_t)-1)
void *cp_mmap(void *address, size_t length, int protection, int flags, int fd, int64_t offset);
int cp_munmap(void *address, size_t length);
#define mmap cp_mmap
#define munmap cp_munmap

#define DT_UNKNOWN 0
#define DT_DIR 4
#define DT_REG 8
#define DT_LNK 10
struct dirent {
    char d_name[PATH_MAX];
    unsigned char d_type;
};
typedef struct CpDir DIR;
DIR *cp_opendir(const char *path);
struct dirent *cp_readdir(DIR *dir);
int cp_closedir(DIR *dir);
#define opendir cp_opendir
#define readdir cp_readdir
#define closedir cp_closedir

/* pthread-sized API implemented with Win32 synchronization primitives. */
typedef HANDLE pthread_t;
typedef SRWLOCK pthread_mutex_t;
typedef CONDITION_VARIABLE pthread_cond_t;
typedef INIT_ONCE pthread_once_t;
#define PTHREAD_ONCE_INIT INIT_ONCE_STATIC_INIT

int pthread_create(pthread_t *thread, const void *attributes,
                   void *(*start_routine)(void *), void *argument);
int pthread_join(pthread_t thread, void **result);
int pthread_mutex_init(pthread_mutex_t *mutex, const void *attributes);
int pthread_mutex_destroy(pthread_mutex_t *mutex);
int pthread_mutex_lock(pthread_mutex_t *mutex);
int pthread_mutex_unlock(pthread_mutex_t *mutex);
int pthread_cond_init(pthread_cond_t *condition, const void *attributes);
int pthread_cond_destroy(pthread_cond_t *condition);
int pthread_cond_wait(pthread_cond_t *condition, pthread_mutex_t *mutex);
int pthread_cond_signal(pthread_cond_t *condition);
int pthread_cond_broadcast(pthread_cond_t *condition);
int pthread_once(pthread_once_t *once, void (*routine)(void));

#endif
