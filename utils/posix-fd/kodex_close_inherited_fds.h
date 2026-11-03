#ifndef KODEX_CLOSE_INHERITED_FDS_H
#define KODEX_CLOSE_INHERITED_FDS_H

#ifdef __linux__
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/syscall.h>
#include <unistd.h>

struct kodex_linux_dirent64 {
    uint64_t inode;
    int64_t offset;
    unsigned short record_length;
    unsigned char type;
    char name[];
};

/* Called only in the child after stdio is installed; uses stack storage and syscalls. */
static inline int kodex_close_inherited_fds(void) {
#ifdef SYS_close_range
    if (syscall(SYS_close_range, 3U, UINT_MAX, 0U) == 0) return 0;
#endif
    int directory = open("/proc/self/fd", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (directory >= 0) {
        union { uint64_t alignment; char bytes[4096]; } buffer;
        int valid = 1;
        while (valid) {
            long count = syscall(SYS_getdents64, directory, buffer.bytes, sizeof(buffer.bytes));
            if (count < 0 && errno == EINTR) continue;
            if (count < 0) { valid = 0; break; }
            if (count == 0) break;
            long offset = 0;
            while (offset < count) {
                struct kodex_linux_dirent64 *entry = (struct kodex_linux_dirent64 *)(buffer.bytes + offset);
                if (count - offset < (long)offsetof(struct kodex_linux_dirent64, name) + 1 ||
                    entry->record_length < offsetof(struct kodex_linux_dirent64, name) + 1 ||
                    entry->record_length > count - offset) { valid = 0; break; }
                unsigned int number = 0;
                unsigned int position = 0;
                unsigned int capacity = entry->record_length - offsetof(struct kodex_linux_dirent64, name);
                while (position < capacity && entry->name[position] >= '0' && entry->name[position] <= '9') {
                    unsigned int digit = (unsigned int)(entry->name[position++] - '0');
                    if (number > ((unsigned int)INT_MAX - digit) / 10U) { valid = 0; break; }
                    number = number * 10U + digit;
                }
                if (!valid) break;
                if (position > 0 && position < capacity && entry->name[position] == '\0' &&
                    number >= 3 && (int)number != directory) {
                    if (close((int)number) != 0 && errno != EBADF) { valid = 0; break; }
                }
                offset += entry->record_length;
            }
        }
        close(directory);
        if (valid) return 0;
    }
    /* A resource limit may be lowered after higher-numbered FDs were opened.
       Without either kernel close_range or proc enumeration, fail closed. */
    return -1;
}
#endif
#endif
