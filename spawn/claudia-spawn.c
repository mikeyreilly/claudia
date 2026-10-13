/*
 * claudia-spawn: runs a tool program without Claudia's terminal.
 *
 *     claudia-spawn PATH ARGV0 [ARG...]
 *
 * Tool processes would otherwise share the terminal that Claudia's line editor
 * reads. A program that opens /dev/tty for a password or confirmation (ssh for
 * a key passphrase, git credential prompts, sudo, gpg) then competes with
 * Claudia for keystrokes, splitting key sequences between them. If it is
 * killed mid-prompt it cannot restore the terminal modes it changed, and
 * Ctrl-C and Ctrl-D stop reaching Claudia.
 *
 * On macOS and Linux this calls setsid(), so the program runs in a new session
 * with no controlling terminal and opening /dev/tty fails, then replaces
 * itself with PATH (an absolute path; there is no PATH search) using ARGV0 as
 * its argv[0]. The program keeps this process's PID, standard streams,
 * environment and working directory, and its exit status is the program's.
 * When PATH cannot be executed, this prints the reason on stderr and exits
 * with 127 (not found) or 126 (any other failure), as shells do.
 *
 * Claudia (com.quaxt.claudia.shell.DetachedProcess) looks for this program at
 * spawn/build/claudia-spawn in the checkout it was built from; install.sh
 * builds it there. When it is absent, tools share Claudia's terminal.
 *
 * Windows uses claudia-spawn.exe PATH [ARG...] (no separate ARGV0). It frees
 * Claudia's console and starts PATH with CREATE_NO_WINDOW, forwarding Java's
 * command line unchanged and inheriting only the three standard handles. A
 * kill-on-close job takes the child tree down if the launcher is terminated;
 * normal completion clears that limit so intentional background work survives.
 * The launcher waits and returns the child's exit code. Console prompts wait
 * unseen until the tool timeout; load SSH keys into ssh-agent first. Java leaves
 * .bat/.cmd commands attached to preserve its batch-specific quoting.
 */
#if defined(_WIN32)
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0601
#endif
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <wchar.h>

/* argv[0] has no backslash escaping, unlike the remaining CRT arguments. */
static wchar_t *skip_program(wchar_t *text) {
    if (*text == L'"') {
        ++text;
        while (*text && *text != L'"') ++text;
        if (*text) ++text;
    } else {
        while (*text && *text != L' ' && *text != L'\t') ++text;
    }
    while (*text == L' ' || *text == L'\t') ++text;
    return text;
}

/* UTF-8 diagnostics also work when stderr is a Java pipe, not a console. */
static int report(HANDLE error_handle, const wchar_t *program, DWORD error) {
    wchar_t *message = NULL;
    FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM |
                   FORMAT_MESSAGE_IGNORE_INSERTS, NULL, error, 0, (wchar_t *)&message, 0, NULL);
    const wchar_t *reason = message ? message : L"Windows process launch failed\r\n";
    size_t length = wcslen(program) + wcslen(reason) + 3;
    wchar_t *text = calloc(length, sizeof(wchar_t));
    if (text) {
        swprintf(text, length, L"%ls: %ls", program, reason);
        int bytes = WideCharToMultiByte(CP_UTF8, 0, text, -1, NULL, 0, NULL, NULL);
        char *utf8 = malloc((size_t)bytes);
        if (utf8 && bytes > 0) {
            WideCharToMultiByte(CP_UTF8, 0, text, -1, utf8, bytes, NULL, NULL);
            DWORD written;
            WriteFile(error_handle, utf8, (DWORD)(bytes - 1), &written, NULL);
        }
        free(utf8);
        free(text);
    }
    if (message) LocalFree(message);
    return error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND ? 127 : 126;
}

int main(void) {
    /* FreeConsole can invalidate console handles; capture Java's pipes first. */
    HANDLE input = GetStdHandle(STD_INPUT_HANDLE);
    HANDLE output = GetStdHandle(STD_OUTPUT_HANDLE);
    HANDLE error_handle = GetStdHandle(STD_ERROR_HANDLE);
    (void)FreeConsole();
    wchar_t *remainder = skip_program(GetCommandLineW());
    if (!*remainder) {
        const char usage[] = "usage: claudia-spawn PATH [ARG...]\n";
        DWORD written;
        WriteFile(error_handle, usage, sizeof(usage) - 1, &written, NULL);
        return 127;
    }
    const wchar_t *start = remainder;
    const wchar_t *end;
    if (*start == L'"') {
        ++start;
        end = start;
        while (*end && *end != L'"') ++end;
    } else {
        end = start;
        while (*end && *end != L' ' && *end != L'\t') ++end;
    }
    size_t program_length = (size_t)(end - start);
    wchar_t *program = calloc(program_length + 1, sizeof(wchar_t));
    wchar_t *command = calloc(wcslen(remainder) + 1, sizeof(wchar_t));
    if (!program || !command) {
        free(program); free(command);
        return report(error_handle, L"claudia-spawn", ERROR_NOT_ENOUGH_MEMORY);
    }
    wmemcpy(program, start, program_length);
    wcscpy(command, remainder); /* CreateProcessW may modify this buffer. */
    STARTUPINFOEXW startup = {0};
    startup.StartupInfo.cb = sizeof(startup);
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdInput = input;
    startup.StartupInfo.hStdOutput = output;
    startup.StartupInfo.hStdError = error_handle;
    HANDLE standard[] = {input, output, error_handle}, handles[3];
    DWORD count = 0, error = ERROR_SUCCESS;
    for (DWORD i = 0; i < 3; ++i) {
        HANDLE handle = standard[i];
        if (!handle || handle == INVALID_HANDLE_VALUE) continue;
        DWORD j = 0;
        while (j < count && handles[j] != handle) ++j;
        if (j < count) continue;
        DWORD flags;
        if (!GetHandleInformation(handle, &flags) ||
            (!(flags & HANDLE_FLAG_INHERIT) &&
             !SetHandleInformation(handle, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT))) {
            error = GetLastError();
            goto cleanup;
        }
        handles[count++] = handle;
    }
    if (count) {
        SIZE_T bytes = 0;
        InitializeProcThreadAttributeList(NULL, 1, 0, &bytes);
        startup.lpAttributeList = malloc(bytes);
        if (!startup.lpAttributeList) { error = ERROR_NOT_ENOUGH_MEMORY; goto cleanup; }
        if (!InitializeProcThreadAttributeList(startup.lpAttributeList, 1, 0, &bytes)) {
            error = GetLastError();
            free(startup.lpAttributeList); startup.lpAttributeList = NULL;
            goto cleanup;
        }
        if (!UpdateProcThreadAttribute(startup.lpAttributeList, 0,
                PROC_THREAD_ATTRIBUTE_HANDLE_LIST, handles, count * sizeof(HANDLE), NULL, NULL)) {
            error = GetLastError(); goto cleanup;
        }
    }
    PROCESS_INFORMATION process = {0};
    if (!CreateProcessW(program, command, NULL, NULL, count != 0,
            CREATE_NO_WINDOW | CREATE_SUSPENDED | EXTENDED_STARTUPINFO_PRESENT,
            NULL, NULL, &startup.StartupInfo, &process)) {
        error = GetLastError(); goto cleanup;
    }
    HANDLE job = CreateJobObjectW(NULL, NULL);
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits = {0};
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    /* Nesting may be forbidden by the host's job; isolation still works then. */
    if (job && (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits)) ||
                !AssignProcessToJobObject(job, process.hProcess))) {
        CloseHandle(job); job = NULL;
    }
    if (ResumeThread(process.hThread) == (DWORD)-1) {
        error = GetLastError();
        TerminateProcess(process.hProcess, 126);
    } else if (WaitForSingleObject(process.hProcess, INFINITE) != WAIT_OBJECT_0) {
        error = GetLastError();
        TerminateProcess(process.hProcess, 126);
    }
    DWORD exit_code = 126;
    if (error == ERROR_SUCCESS && !GetExitCodeProcess(process.hProcess, &exit_code)) error = GetLastError();
    if (job) {
        if (error == ERROR_SUCCESS) {
            /* Do not kill intentional background grandchildren on normal exit. */
            limits.BasicLimitInformation.LimitFlags = 0;
            if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits)))
                error = GetLastError();
        }
        CloseHandle(job);
    }
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    if (startup.lpAttributeList) {
        DeleteProcThreadAttributeList(startup.lpAttributeList);
        free(startup.lpAttributeList);
    }
    free(command);
    if (error != ERROR_SUCCESS) {
        int result = report(error_handle, program, error);
        free(program);
        return result;
    }
    free(program);
    ExitProcess(exit_code); /* Preserve all 32 bits, not just a POSIX status byte. */

cleanup:
    if (startup.lpAttributeList) {
        DeleteProcThreadAttributeList(startup.lpAttributeList);
        free(startup.lpAttributeList);
    }
    free(command);
    int result = report(error_handle, program, error);
    free(program);
    return result;
}

#else
#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 3) {
        fputs("usage: claudia-spawn PATH ARGV0 [ARG...]\n", stderr);
        return 127;
    }
    /*
     * This fails only for a process group leader, which a freshly started
     * child is not. Run the program anyway: it would merely share the
     * terminal, as it does when this launcher is not installed.
     */
    (void)setsid();
    execv(argv[1], argv + 2);
    int error = errno;
    fprintf(stderr, "%s: %s\n", argv[1], strerror(error));
    return error == ENOENT ? 127 : 126;
}
#endif
