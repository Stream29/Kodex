#ifndef KODEX_WINDOWS_PIPE_H
#define KODEX_WINDOWS_PIPE_H

#include <windows.h>
#include <aclapi.h>
#include <bcrypt.h>
#include <wchar.h>

#ifndef PIPE_REJECT_REMOTE_CLIENTS
#define PIPE_REJECT_REMOTE_CLIENTS 0x00000008
#endif

/* The server is the overlapped, non-inheritable parent end. */
static inline DWORD kodex_windows_private_pipe(
    HANDLE *parent_out, HANDLE *child_out, BOOL parent_reads, BOOL child_inherits
) {
    static volatile LONG sequence = 0;
    HANDLE token = NULL;
    TOKEN_USER *user = NULL;
    PACL acl = NULL;
    EXPLICIT_ACCESSW access;
    SECURITY_DESCRIPTOR descriptor;
    SECURITY_ATTRIBUTES server_security = { sizeof(SECURITY_ATTRIBUTES), &descriptor, FALSE };
    SECURITY_ATTRIBUTES client_security = { sizeof(SECURITY_ATTRIBUTES), NULL, child_inherits };
    HANDLE parent = INVALID_HANDLE_VALUE;
    HANDLE child = INVALID_HANDLE_VALUE;
    DWORD size = 0;
    DWORD error;
    ULONG entropy[2];
    wchar_t name[96];

    *parent_out = *child_out = NULL;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return GetLastError();
    if (GetTokenInformation(token, TokenUser, NULL, 0, &size) ||
        GetLastError() != ERROR_INSUFFICIENT_BUFFER) {
        error = ERROR_GEN_FAILURE;
        goto finish;
    }
    user = (TOKEN_USER *)HeapAlloc(GetProcessHeap(), 0, size);
    if (user == NULL) {
        error = ERROR_NOT_ENOUGH_MEMORY;
        goto finish;
    }
    if (!GetTokenInformation(token, TokenUser, user, size, &size)) {
        error = GetLastError();
        goto finish;
    }

    ZeroMemory(&access, sizeof(access));
    access.grfAccessPermissions = FILE_ALL_ACCESS;
    access.grfAccessMode = SET_ACCESS;
    access.Trustee.TrusteeForm = TRUSTEE_IS_SID;
    access.Trustee.TrusteeType = TRUSTEE_IS_USER;
    access.Trustee.ptstrName = (LPWSTR)user->User.Sid;
    error = SetEntriesInAclW(1, &access, NULL, &acl);
    if (error != ERROR_SUCCESS) goto finish;
    if (!InitializeSecurityDescriptor(&descriptor, SECURITY_DESCRIPTOR_REVISION) ||
        !SetSecurityDescriptorDacl(&descriptor, TRUE, acl, FALSE)) {
        error = GetLastError();
        goto finish;
    }
    if (BCryptGenRandom(NULL, (PUCHAR)entropy, sizeof(entropy), BCRYPT_USE_SYSTEM_PREFERRED_RNG) != 0) {
        error = ERROR_GEN_FAILURE;
        goto finish;
    }
    if (swprintf(name, sizeof(name) / sizeof(*name), L"\\\\.\\pipe\\LOCAL\\kodex-%08lx-%08lx%08lx-%08lx",
        (unsigned long)GetCurrentProcessId(), (unsigned long)entropy[0], (unsigned long)entropy[1],
        (unsigned long)InterlockedIncrement(&sequence)) < 0) {
        error = ERROR_GEN_FAILURE;
        goto finish;
    }
    parent = CreateNamedPipeW(name,
        (parent_reads ? PIPE_ACCESS_INBOUND : PIPE_ACCESS_OUTBOUND) |
            FILE_FLAG_OVERLAPPED | FILE_FLAG_FIRST_PIPE_INSTANCE,
        PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
        1, 65536, 65536, 0, &server_security);
    if (parent == INVALID_HANDLE_VALUE) {
        error = GetLastError();
        goto finish;
    }
    /* CreateFile can connect before ConnectNamedPipe; the pair is already
       connected on success, so no overlapped ConnectNamedPipe needs draining. */
    child = CreateFileW(name, parent_reads ? GENERIC_WRITE : GENERIC_READ,
        0, &client_security, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (child == INVALID_HANDLE_VALUE) {
        error = GetLastError();
        goto finish;
    }
    *parent_out = parent;
    *child_out = child;
    error = ERROR_SUCCESS;

finish:
    if (error != ERROR_SUCCESS && parent != INVALID_HANDLE_VALUE) CloseHandle(parent);
    if (acl != NULL) LocalFree(acl);
    if (user != NULL) HeapFree(GetProcessHeap(), 0, user);
    CloseHandle(token);
    return error;
}

static inline DWORD kodex_windows_create_process_job(HANDLE process, HANDLE *job_out) {
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits;
    *job_out = NULL;
    HANDLE job = CreateJobObjectW(NULL, NULL);
    if (job == NULL) return GetLastError();
    ZeroMemory(&limits, sizeof(limits));
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits)) ||
        !AssignProcessToJobObject(job, process)) {
        DWORD error = GetLastError();
        CloseHandle(job);
        return error;
    }
    *job_out = job;
    return ERROR_SUCCESS;
}

static inline DWORD kodex_windows_terminate_tree(HANDLE process, HANDLE job) {
    if (job != NULL) return TerminateJobObject(job, 1) ? ERROR_SUCCESS : GetLastError();
    if (TerminateProcess(process, 1)) return ERROR_SUCCESS;
    DWORD error = GetLastError();
    /* An already exited root does not need termination. This is not polling. */
    return WaitForSingleObject(process, 0) == WAIT_OBJECT_0 ? ERROR_SUCCESS : error;
}

#endif
