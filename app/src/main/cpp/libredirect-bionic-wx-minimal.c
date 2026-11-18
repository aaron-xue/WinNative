/*
 * libredirect-bionic-wx-minimal.c
 *
 * Open-source, minimal W^X (write-xor-execute) preload shim for Android 16
 * (targetSdk 36). This replaces the closed-source GameNative
 * "libredirect-bionic-wx-minimal.so" while keeping its two responsibilities:
 *
 *   1. W^X memory protection emulation for JIT engines. Android 16 hard-forces
 *      W^X on executable pages, so any mmap/mprotect request that asks for
 *      PROT_READ|PROT_WRITE|PROT_EXEC would otherwise fail (or fault at first
 *      execution). We downgrade RWX requests to RW, remember the range, and
 *      then flip the faulting page between R-X and R-W from a SIGSEGV handler
 *      (SEGV_ACCERR). This gives FEXCore/Wine the illusion of RWX memory while
 *      the kernel never sees a writable+executable page.
 *
 *   2. Bionic path redirection. On Android 16 libc.so lives at
 *      /apex/com.android.runtime/lib64/bionic/libc.so and there is no
 *      /system/lib64/libc.so symlink. We rewrite /system/lib{64}/<name> paths
 *      to their /apex/com.android.runtime counterparts when those exist.
 *
 * Unlike the full libredirect-bionic-wx.so, this shim does NOT hardcode any
 * package name, so it works for WinNative's own application id.
 *
 * The library is built by the top-level CMakeLists.txt and staged into the
 * imagefs usr/lib directory by GuestProgramLauncherComponent via
 * ensureImageFsNativeLibrary, then preloaded through LD_PRELOAD.
 */

#define _GNU_SOURCE 1

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <ucontext.h>
#include <unistd.h>

#define TAG "wn-wx-shim"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

/* Diagnostic log throttling: mmap/mprotect and the SIGSEGV handler are hot
 * paths, so we only log the first few events. This is enough to pinpoint a
 * silent hang (e.g. "is the shim loaded?", "is JIT code being executed?"). */
static int g_log_mmap_rwx;
static int g_log_sigaction;
static int g_log_fault;
static int g_log_fault_detail;
static int g_log_misaligned;

static int log_budget(int *counter, int limit) {
    return __atomic_fetch_add(counter, 1, __ATOMIC_RELAXED) < limit;
}

/* ----------------------------------------------------------------------------
 * Raw syscall helpers (aarch64 only). Used from the SIGSEGV handler so we never
 * depend on PLT/dlsym state while inside a signal.
 * -------------------------------------------------------------------------- */

#if defined(__aarch64__)
static long raw_syscall6(long n, long a1, long a2, long a3, long a4, long a5,
                         long a6) {
    register long x0 __asm__("x0") = a1;
    register long x1 __asm__("x1") = a2;
    register long x2 __asm__("x2") = a3;
    register long x3 __asm__("x3") = a4;
    register long x4 __asm__("x4") = a5;
    register long x5 __asm__("x5") = a6;
    register long x8 __asm__("x8") = n;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5), "r"(x8)
                     : "memory", "cc");
    return x0;
}
#else
#error "libredirect-bionic-wx-minimal only supports aarch64"
#endif

#ifndef SYS_mprotect
#define SYS_mprotect 226
#endif
#ifndef SYS_openat
#define SYS_openat 56
#endif
#ifndef SYS_read
#define SYS_read 63
#endif
#ifndef SYS_close
#define SYS_close 57
#endif
#ifndef SYS_memfd_create
#define SYS_memfd_create 279
#endif
#ifndef SYS_ftruncate
#define SYS_ftruncate 46
#endif
#ifndef SYS_munmap
#define SYS_munmap 215
#endif
#ifndef SYS_pread64
#define SYS_pread64 67
#endif
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

/* ----------------------------------------------------------------------------
 * W^X region tracking.
 *
 * We track every range the JIT asked to make executable (PROT_EXEC), not just
 * RWX. RWX requests are downgraded to RW at the syscall boundary, but a JIT may
 * also map a buffer RW, mprotect it RX after writing code, and then write to it
 * again for self-modifying code. Without tracking those RX ranges the SIGSEGV
 * handler cannot flip the page back to RW, so the fault would be forwarded to
 * the guest and crash it.
 *
 * Regions are kept in a dense array sorted by `start`, so the SIGSEGV handler
 * can locate a faulting page with a lock-free binary search. Writers mutate the
 * array under a mutex (mmap/mprotect/munmap are rare); they fully write a new
 * entry before releasing `g_region_count`, and on growth they publish a fresh
 * array pointer without freeing the old one (a reader may still scan it).
 * -------------------------------------------------------------------------- */

#define WX_MAX_REGIONS 16384
#define WX_INIT_REGIONS 256

typedef struct {
    uintptr_t start;    /* RW view (the address the JIT holds); primary sort key */
    size_t len;         /* >0 for live entries; the array stays dense (no tombstones) */
    uintptr_t rx_start; /* dual-map RX mirror base; 0 = no mirror (mprotect flip path) */
    int memfd;          /* dual-map backing memfd; -1 = none */
} wx_region_t;

/* The region table is a dynamically grown, sorted, dense array. The SIGSEGV
 * handler reads it with a lock-free binary search guarded by a seqlock
 * (g_region_seq): an even value means the table is stable, an odd value means a
 * writer is mutating it. A reader that observes an odd sequence or a sequence
 * change falls back to /proc/self/maps, so it never reads a torn table and
 * never blocks on the writer (it must stay signal-safe and deadlock-free). */
static wx_region_t *g_regions;
static int g_region_count;
static int g_region_capacity;
static pthread_mutex_t g_region_mutex = PTHREAD_MUTEX_INITIALIZER;
static unsigned g_region_seq; /* even = stable, odd = writer active */
static long g_page_size;

/* One-slot hot cache for the SIGSEGV handler: it records the last dual-mapped
 * region an execute fault landed in, so the very common "keep executing the
 * same JIT region" case can redirect the PC straight to the RX mirror without
 * the seqlock read + binary search. Guarded by g_hot_seq (same even/odd
 * convention as g_region_seq); start == 0 means "no cached entry". */
static uintptr_t g_hot_start; /* RW view start */
static uintptr_t g_hot_end;   /* RW view end (exclusive) */
static uintptr_t g_hot_rx;    /* RX mirror base */
static unsigned g_hot_seq;    /* even = stable, odd = writer active */

/* Index of the first entry with start >= key (insertion point). */
static int region_lower_bound(const wx_region_t *arr, int n, uintptr_t key) {
    int lo = 0, hi = n;
    while (lo < hi) {
        int mid = lo + ((hi - lo) >> 1);
        if (arr[mid].start < key) {
            lo = mid + 1;
        } else {
            hi = mid;
        }
    }
    return lo;
}

/* Index of the first entry with start > key (so idx-1 is the last start <= key). */
static int region_upper_bound(const wx_region_t *arr, int n, uintptr_t key) {
    int lo = 0, hi = n;
    while (lo < hi) {
        int mid = lo + ((hi - lo) >> 1);
        if (arr[mid].start <= key) {
            lo = mid + 1;
        } else {
            hi = mid;
        }
    }
    return lo;
}

/* Grow the table to hold at least `needed` entries. Caller holds the mutex and
 * has already marked g_region_seq odd. The old buffer is intentionally not
 * freed: a reader that loaded the old pointer before the swap may still touch it
 * (the seqlock makes it detect the change only after the fact). Growth doubles
 * the capacity, so the sum of all retired buffers stays below ~2x the final
 * capacity. */
static int ensure_capacity_locked(int needed) {
    int new_cap;
    wx_region_t *na;

    if (needed > WX_MAX_REGIONS) return -1;
    new_cap = g_region_capacity > 0 ? g_region_capacity : WX_INIT_REGIONS;
    while (new_cap < needed) {
        new_cap <<= 1;
        if (new_cap > WX_MAX_REGIONS) new_cap = WX_MAX_REGIONS;
    }

    na = (wx_region_t *)calloc((size_t)new_cap, sizeof(wx_region_t));
    if (!na) return -1;
    if (g_regions && g_region_count > 0) {
        memcpy(na, g_regions, (size_t)g_region_count * sizeof(wx_region_t));
    }
    __atomic_store_n(&g_regions, na, __ATOMIC_RELEASE);
    g_region_capacity = new_cap;
    return 0;
}

/* Close a memfd without going through the PLT (usable from a signal context if
 * ever needed, though it is only called from the writer path here). */
static void close_memfd_if_any(int fd) {
    if (fd >= 0) {
        raw_syscall6(SYS_close, (long)fd, 0, 0, 0, 0, 0);
    }
}

/* Publish a dual-mapped region as the handler's hot entry. Only ever called from
 * the writer path (g_region_mutex held), so it is the single g_hot_* writer and
 * the seqlock stays well-formed for lock-free reads in the handler. */
static void publish_hot_region(uintptr_t start, uintptr_t end, uintptr_t rx) {
    __atomic_fetch_add(&g_hot_seq, 1, __ATOMIC_ACQ_REL);
    __atomic_store_n(&g_hot_start, start, __ATOMIC_RELEASE);
    __atomic_store_n(&g_hot_end, end, __ATOMIC_RELEASE);
    __atomic_store_n(&g_hot_rx, rx, __ATOMIC_RELEASE);
    __atomic_fetch_add(&g_hot_seq, 1, __ATOMIC_ACQ_REL);
}

/* Drop the hot cache: called from the writer path right before an RX mirror is
 * unmapped, so the handler never redirects a PC into a stale (unmapped) mirror.
 * Wrapped in g_hot_seq so a concurrent handler read never observes a torn
 * start/end/rx triple. */
static void invalidate_hot_region(void) {
    __atomic_fetch_add(&g_hot_seq, 1, __ATOMIC_ACQ_REL);
    __atomic_store_n(&g_hot_start, 0, __ATOMIC_RELEASE);
    __atomic_fetch_add(&g_hot_seq, 1, __ATOMIC_ACQ_REL);
}

/* Insert/merge [start, start+len) into the sorted table. Caller holds the mutex
 * and has already marked g_region_seq odd. Overlapping or adjacent regions are
 * coalesced so the table stays non-overlapping, which is what makes the
 * lock-free binary search in find_region_info() exact.
 *
 * A dual-map entry (rx_start != 0, memfd >= 0) carries its own RX mirror. If a
 * merge window swallows existing entries, their mirrors cannot be combined into
 * one coherent mirror, so we drop the mirror on the merged result (it falls back
 * to the mprotect flip) and close every memfd in the window. */
static void track_region_locked(uintptr_t start, size_t len, uintptr_t rx_start, int memfd) {
    uintptr_t end = start + len;
    int n = __atomic_load_n(&g_region_count, __ATOMIC_RELAXED);
    int lo, hi, a, b, src, trailing, new_n, k, merged;
    uintptr_t new_start = start, new_end = end;
    uintptr_t new_rx = rx_start;
    int new_memfd = memfd;

    if (len == 0 || start == 0) {
        close_memfd_if_any(memfd);
        return;
    }

    if (n == 0 || g_regions == NULL) {
        if (ensure_capacity_locked(1) != 0) {
            LOGW("region table full; dropping tracked region 0x%lx", (unsigned long)start);
            close_memfd_if_any(memfd);
            return;
        }
        g_regions[0].start = start;
        g_regions[0].len = len;
        g_regions[0].rx_start = rx_start;
        g_regions[0].memfd = memfd;
        __atomic_store_n(&g_region_count, 1, __ATOMIC_RELEASE);
        if (rx_start != 0 && memfd >= 0) {
            publish_hot_region(start, start + len, rx_start);
        }
        return;
    }

    lo = region_lower_bound(g_regions, n, start);
    hi = region_lower_bound(g_regions, n, end);

    /* Extend the merge window left if the previous entry touches [start,end). */
    a = lo;
    if (lo > 0) {
        const wx_region_t *prev = &g_regions[lo - 1];
        if (prev->start + prev->len >= start) a = lo - 1;
    }
    /* Extend the merge window right if the entry at `end` is adjacent. */
    b = hi;
    if (hi < n && g_regions[hi].start == end) b = hi + 1;

    merged = (a < b);

    if (merged) {
        if (g_regions[a].start < new_start) new_start = g_regions[a].start;
        if (g_regions[b - 1].start + g_regions[b - 1].len > new_end)
            new_end = g_regions[b - 1].start + g_regions[b - 1].len;

        /* Drop all mirrors in the merge window plus the one being inserted:
         * unmap their RX views and close their memfds. The merged region falls
         * back to the mprotect flip. */
        for (k = a; k < b; k++) {
            if (g_regions[k].memfd >= 0) {
                if (g_regions[k].rx_start != 0) {
                    raw_syscall6(SYS_munmap, (long)g_regions[k].rx_start,
                                 (long)g_regions[k].len, 0, 0, 0, 0);
                    invalidate_hot_region();
                }
                close_memfd_if_any(g_regions[k].memfd);
            }
        }
        if (memfd >= 0) {
            if (rx_start != 0) {
                raw_syscall6(SYS_munmap, (long)rx_start, (long)len, 0, 0, 0, 0);
                invalidate_hot_region();
            }
            close_memfd_if_any(memfd);
        }
        new_rx = 0;
        new_memfd = -1;
    }

    new_n = n - (b - a) + 1;
    if (ensure_capacity_locked(new_n) != 0) {
        LOGW("region table full; dropping tracked region 0x%lx", (unsigned long)start);
        close_memfd_if_any(new_memfd);
        return;
    }

    /* Move the trailing block out of the way, then write the merged entry. For a
     * pure insert (a == b) this shifts [a,n) right by one; for a merge (a < b)
     * it shifts [b,n) left to a+1. memmove copes with either direction. */
    src = (a < b) ? b : a;
    trailing = n - src;
    if (trailing > 0) {
        memmove(&g_regions[a + 1], &g_regions[src], (size_t)trailing * sizeof(wx_region_t));
    }
    g_regions[a].start = new_start;
    g_regions[a].len = new_end - new_start;
    g_regions[a].rx_start = new_rx;
    g_regions[a].memfd = new_memfd;
    __atomic_store_n(&g_region_count, new_n, __ATOMIC_RELEASE);

    /* Publish the newest dual-mapped region as the handler's hot entry. */
    if (new_rx != 0 && new_memfd >= 0) {
        publish_hot_region(new_start, new_end, new_rx);
    }
}

static void track_region(uintptr_t start, size_t len) {
    if (len == 0 || start == 0) return;
    pthread_mutex_lock(&g_region_mutex);
    __atomic_fetch_add(&g_region_seq, 1, __ATOMIC_ACQ_REL);
    track_region_locked(start, len, 0, -1);
    __atomic_fetch_add(&g_region_seq, 1, __ATOMIC_ACQ_REL);
    pthread_mutex_unlock(&g_region_mutex);
}

/* Track a dual-mapped RWX region: `rw_start` is the address returned to the JIT
 * (the RW view), `rx_start` its RX mirror backed by the same `memfd` pages. */
static void track_region_dual(uintptr_t rw_start, size_t len, uintptr_t rx_start, int memfd) {
    if (len == 0 || rw_start == 0 || rx_start == 0 || memfd < 0) {
        close_memfd_if_any(memfd);
        return;
    }
    pthread_mutex_lock(&g_region_mutex);
    __atomic_fetch_add(&g_region_seq, 1, __ATOMIC_ACQ_REL);
    track_region_locked(rw_start, len, rx_start, memfd);
    __atomic_fetch_add(&g_region_seq, 1, __ATOMIC_ACQ_REL);
    pthread_mutex_unlock(&g_region_mutex);
}

/* Remove [start, start+len) from the sorted table. Caller holds the mutex and
 * has already marked g_region_seq odd. */
static void untrack_range_locked(uintptr_t start, size_t len) {
    uintptr_t end = start + len;
    int n = __atomic_load_n(&g_region_count, __ATOMIC_RELAXED);
    int lo, hi, a, b, w, trailing, new_n, k;
    int has_head = 0, has_tail = 0;
    wx_region_t head, tail;

    if (len == 0 || n == 0 || g_regions == NULL) return;

    lo = region_lower_bound(g_regions, n, start);
    hi = region_lower_bound(g_regions, n, end);

    a = lo;
    if (lo > 0) {
        const wx_region_t *prev = &g_regions[lo - 1];
        if (prev->start + prev->len > start) a = lo - 1;
    }
    b = hi; /* first entry with start >= end; overlaps are indices [a, b) */

    if (a >= b) return; /* no overlap */

    /* A dual-map region cannot be split while keeping a coherent mirror, so any
     * region we touch loses its mirror (survivors fall back to the mprotect
     * flip). Unmap the RX mirror and close the memfd exactly once per region. */
    for (k = a; k < b; k++) {
        wx_region_t *r = &g_regions[k];
        if (r->memfd >= 0) {
            if (r->rx_start != 0) {
                raw_syscall6(SYS_munmap, (long)r->rx_start, (long)r->len, 0, 0, 0, 0);
                invalidate_hot_region();
            }
            close_memfd_if_any(r->memfd);
        }
    }

    /* Survivor on the left edge (the entry containing `start`). */
    {
        const wx_region_t *first = &g_regions[a];
        if (first->start < start) {
            head.start = first->start;
            head.len = start - first->start;
            head.rx_start = 0;
            head.memfd = -1;
            has_head = 1;
        }
    }
    /* Survivor on the right edge (the entry containing `end`). */
    {
        const wx_region_t *last = &g_regions[b - 1];
        if (last->start + last->len > end) {
            tail.start = end;
            tail.len = (last->start + last->len) - end;
            tail.rx_start = 0;
            tail.memfd = -1;
            has_tail = 1;
        }
    }

    new_n = n - (b - a) + has_head + has_tail;
    if (new_n > WX_MAX_REGIONS) {
        /* Degenerate: table is full and this split would need one more slot.
         * Drop the tail survivor to stay within the cap; future faults on that
         * tail simply fall through to the /proc/self/maps path. */
        has_tail = 0;
        new_n = n - (b - a) + has_head;
    }
    if (ensure_capacity_locked(new_n) != 0) {
        LOGW("region table allocation failed during untrack");
        return;
    }

    w = a;
    if (has_head) g_regions[w++] = head;
    if (has_tail) g_regions[w++] = tail;
    trailing = n - b;
    if (trailing > 0) {
        memmove(&g_regions[w], &g_regions[b], (size_t)trailing * sizeof(wx_region_t));
    }
    __atomic_store_n(&g_region_count, new_n, __ATOMIC_RELEASE);
}

static void untrack_range(uintptr_t start, size_t len) {
    if (len == 0) return;
    pthread_mutex_lock(&g_region_mutex);
    __atomic_fetch_add(&g_region_seq, 1, __ATOMIC_ACQ_REL);
    untrack_range_locked(start, len);
    __atomic_fetch_add(&g_region_seq, 1, __ATOMIC_ACQ_REL);
    pthread_mutex_unlock(&g_region_mutex);
}

/* Lock-free lookup used by the SIGSEGV handler. See the seqlock comment above.
 * On success fills *out with a local snapshot of the region entry (so the
 * handler can read rx_start/memfd without racing the writer) and returns the
 * entry index; returns -1 on miss or if a writer was active during the read. */
static int find_region_info(uintptr_t page, wx_region_t *out) {
    unsigned s0, s1;
    const wx_region_t *arr;
    int n, idx;

    s0 = __atomic_load_n(&g_region_seq, __ATOMIC_ACQUIRE);
    if (s0 & 1u) return -1;

    arr = __atomic_load_n(&g_regions, __ATOMIC_ACQUIRE);
    n = __atomic_load_n(&g_region_count, __ATOMIC_ACQUIRE);
    if (!arr || n <= 0) return -1;

    idx = region_upper_bound(arr, n, page) - 1;
    if (idx < 0 || arr[idx].len == 0 || page >= arr[idx].start + arr[idx].len) {
        idx = -1;
    } else if (out) {
        *out = arr[idx];
    }

    s1 = __atomic_load_n(&g_region_seq, __ATOMIC_ACQUIRE);
    if (s1 != s0) return -1;
    return idx;
}

/* ----------------------------------------------------------------------------
 * /proc/self/maps fallback lookup (mirrors the closed-source shim's find_vma_at).
 *
 * The internal tracking table above can miss executable regions that were
 * created without going through our mmap/mprotect interceptors, e.g. mappings
 * moved by mremap, created with a raw SYS_mmap, or established before this
 * library's constructor ran. SEGV_ACCERR tells us the faulting page IS mapped
 * but that the current permissions disallow the access, so on a table miss we
 * parse /proc/self/maps to learn the page's VMA and its current execute bit.
 *
 * Everything here uses raw syscalls and stack buffers only: no libc, no malloc,
 * so it is safe to run from inside the SIGSEGV handler.
 * -------------------------------------------------------------------------- */

static int hex_digit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/* Parse one /proc/self/maps line:
 *   7f8b000000-7f8b001000 r-xp 00000000 00:00 0  /path
 * If `addr` falls inside the line's [start, end) range, return 1 and fill in
 * the range plus whether the mapping is currently executable (perms[2] == 'x').
 */
static int parse_maps_line(const char *line, uintptr_t addr, uintptr_t *out_start,
                           uintptr_t *out_end, int *out_exec) {
    uintptr_t start = 0, end = 0;
    const char *p = line;
    int d;

    while ((d = hex_digit(*p)) >= 0) {
        start = (start << 4) | (uintptr_t)d;
        p++;
    }
    if (*p != '-') return 0;
    p++;
    while ((d = hex_digit(*p)) >= 0) {
        end = (end << 4) | (uintptr_t)d;
        p++;
    }
    if (*p != ' ') return 0;
    p++;
    /* perms string is "rwxp" (or with '-' for unset bits): [0]=r [1]=w [2]=x */
    if (addr >= start && addr < end) {
        if (out_start) *out_start = start;
        if (out_end) *out_end = end;
        if (out_exec) *out_exec = (p[2] == 'x');
        return 1;
    }
    return 0;
}

static int find_vma_at(uintptr_t addr, uintptr_t *out_start, uintptr_t *out_end,
                       int *out_exec) {
    char buf[2048];
    char line[512];
    size_t line_len = 0;
    long fd, n, i;
    int found = 0;

    fd = raw_syscall6(SYS_openat, (long)AT_FDCWD, (long)"/proc/self/maps",
                      (long)(O_RDONLY | O_CLOEXEC), 0, 0, 0);
    if (fd < 0) return 0;

    for (;;) {
        n = raw_syscall6(SYS_read, fd, (long)buf, (long)sizeof(buf), 0, 0, 0);
        if (n <= 0) break;
        for (i = 0; i < n; i++) {
            if (buf[i] == '\n') {
                line[line_len] = '\0';
                if (parse_maps_line(line, addr, out_start, out_end, out_exec)) {
                    found = 1;
                    goto out;
                }
                line_len = 0;
            } else if (line_len < sizeof(line) - 1) {
                line[line_len++] = buf[i];
            }
        }
    }

out:
    raw_syscall6(SYS_close, fd, 0, 0, 0, 0, 0);
    return found;
}

/* ----------------------------------------------------------------------------
 * Real libc entry points (resolved eagerly in the constructor).
 * -------------------------------------------------------------------------- */

static int (*real_mprotect)(void *, size_t, int);
static void *(*real_mmap)(void *, size_t, int, int, int, off_t);
static void *(*real_mmap64)(void *, size_t, int, int, int, off_t);
static int (*real_munmap)(void *, size_t);
static int (*real_open)(const char *, int, mode_t);
static int (*real_openat)(int, const char *, int, mode_t);
static int (*real_open64)(const char *, int, mode_t);
static int (*real_openat64)(int, const char *, int, mode_t);
static int (*real_access)(const char *, int);
static int (*real_faccessat)(int, const char *, int, int);
static int (*real_stat)(const char *, struct stat *);
static int (*real_lstat)(const char *, struct stat *);
static int (*real_fstatat)(int, const char *, struct stat *, int);
static int (*real_memfd_create)(const char *, unsigned int);
static int (*real_sigaction)(int, const struct sigaction *, struct sigaction *);
static sighandler_t (*real_signal)(int, sighandler_t);
static void (*real_exit)(int);
static void (*real__exit)(int);
static int (*real_execve)(const char *, char *const[], char *const[]);
static int (*real_execv)(const char *, char *const[]);
static int (*real_ioctl)(int, int, void *);
static ssize_t (*real_read)(int, void *, size_t);
static int (*real_close)(int);

static void resolve_all(void) {
    real_mprotect = (int (*)(void *, size_t, int))dlsym(RTLD_NEXT, "mprotect");
    real_mmap = (void *(*)(void *, size_t, int, int, int, off_t))dlsym(RTLD_NEXT, "mmap");
    real_mmap64 = (void *(*)(void *, size_t, int, int, int, off_t))dlsym(RTLD_NEXT, "mmap64");
    if (!real_mmap64) real_mmap64 = real_mmap;
    real_munmap = (int (*)(void *, size_t))dlsym(RTLD_NEXT, "munmap");
    real_open = (int (*)(const char *, int, mode_t))dlsym(RTLD_NEXT, "open");
    real_openat = (int (*)(int, const char *, int, mode_t))dlsym(RTLD_NEXT, "openat");
    real_open64 = (int (*)(const char *, int, mode_t))dlsym(RTLD_NEXT, "open64");
    if (!real_open64) real_open64 = real_open;
    real_openat64 = (int (*)(int, const char *, int, mode_t))dlsym(RTLD_NEXT, "openat64");
    if (!real_openat64) real_openat64 = real_openat;
    real_access = (int (*)(const char *, int))dlsym(RTLD_NEXT, "access");
    real_faccessat = (int (*)(int, const char *, int, int))dlsym(RTLD_NEXT, "faccessat");
    real_stat = (int (*)(const char *, struct stat *))dlsym(RTLD_NEXT, "stat");
    real_lstat = (int (*)(const char *, struct stat *))dlsym(RTLD_NEXT, "lstat");
    real_fstatat = (int (*)(int, const char *, struct stat *, int))dlsym(RTLD_NEXT, "fstatat");
    real_memfd_create = (int (*)(const char *, unsigned int))dlsym(RTLD_NEXT, "memfd_create");
    real_sigaction = (int (*)(int, const struct sigaction *, struct sigaction *))dlsym(RTLD_NEXT, "sigaction");
    real_signal = (sighandler_t (*)(int, sighandler_t))dlsym(RTLD_NEXT, "signal");
    real_exit = (void (*)(int))dlsym(RTLD_NEXT, "exit");
    real__exit = (void (*)(int))dlsym(RTLD_NEXT, "_exit");
    real_execve = (int (*)(const char *, char *const[], char *const[]))dlsym(RTLD_NEXT, "execve");
    real_execv = (int (*)(const char *, char *const[]))dlsym(RTLD_NEXT, "execv");
    real_ioctl = (int (*)(int, int, void *))dlsym(RTLD_NEXT, "ioctl");
    real_read = (ssize_t (*)(int, void *, size_t))dlsym(RTLD_NEXT, "read");
    real_close = (int (*)(int))dlsym(RTLD_NEXT, "close");
}

/* ----------------------------------------------------------------------------
 * Startup tracing: keep a ring buffer of the last few open()/openat() calls and
 * dump it at process exit so we can see what Wine touched right before it
 * silently quit. These logs go through logcat and are unaffected by the
 * stdout/stderr -> /dev/null redirect that hides Wine's own output.
 * -------------------------------------------------------------------------- */

#define OPEN_HIST_N 32
#define OPEN_HIST_PATH 192

static char g_open_hist[OPEN_HIST_N][OPEN_HIST_PATH];
static int g_open_hist_flags[OPEN_HIST_N];
static volatile int g_open_hist_count;

static void record_open(const char *path, int flags) {
    int n = __atomic_fetch_add(&g_open_hist_count, 1, __ATOMIC_RELAXED);
    int i = n % OPEN_HIST_N;
    if (path) {
        strncpy(g_open_hist[i], path, OPEN_HIST_PATH - 1);
        g_open_hist[i][OPEN_HIST_PATH - 1] = '\0';
    } else {
        g_open_hist[i][0] = '\0';
    }
    g_open_hist_flags[i] = flags;
}

static void dump_open_history(void) {
    int n = __atomic_load_n(&g_open_hist_count, __ATOMIC_RELAXED);
    if (n == 0) {
        LOGI("open history: (none)");
        return;
    }
    int count = n < OPEN_HIST_N ? n : OPEN_HIST_N;
    int start = (n - count) % OPEN_HIST_N;
    LOGI("=== last %d open()/openat() calls ===", count);
    for (int k = 0; k < count; k++) {
        int i = (start + k) % OPEN_HIST_N;
        LOGI("  open[%d] flags=0x%x path=%s", k, g_open_hist_flags[i], g_open_hist[i]);
    }
}

/* Exit-path interceptors: tell us HOW and WHY Wine is quitting. */
void exit(int status) {
    LOGI("exit(status=%d) called", status);
    dump_open_history();
    if (!real_exit) real_exit = (void (*)(int))dlsym(RTLD_NEXT, "exit");
    if (real_exit) real_exit(status);
    for (;;) syscall(SYS_exit_group, status);
}

void _exit(int status) {
    LOGI("_exit(status=%d) called", status);
    dump_open_history();
    if (!real__exit) real__exit = (void (*)(int))dlsym(RTLD_NEXT, "_exit");
    if (real__exit) real__exit(status);
    syscall(SYS_exit_group, status);
    for (;;) {}
}

/* ----------------------------------------------------------------------------
 * execve/execv rewriting (matches the closed-source shim):
 *
 *   - Reject exec of the Wine preloader so Wine falls back to its normal load
 *     path instead of exec'ing the preloader directly (which cannot run under
 *     Android 16's linker).
 *   - exec binaries under Android system partitions directly.
 *   - For any other ELF binary, re-exec it through /system/bin/linker64 with an
 *     argv of [realpath, realpath, argv[1]...]. This is how Android manually
 *     loads a non-system ELF: the first argv entry names the program, the
 *     second becomes the program's argv[0].
 * -------------------------------------------------------------------------- */

static int has_prefix(const char *s, const char *prefix) {
    if (!s || !prefix) return 0;
    return strncmp(s, prefix, strlen(prefix)) == 0;
}

static int ends_with(const char *s, const char *suffix) {
    size_t ls, lf;
    if (!s || !suffix) return 0;
    ls = strlen(s);
    lf = strlen(suffix);
    if (lf > ls) return 0;
    return memcmp(s + ls - lf, suffix, lf) == 0;
}

static int should_direct_exec(const char *path) {
    static const char *const prefixes[] = {
        "/apex/", "/odm/", "/product/", "/sbin/",
        "/system/", "/system_ext/", "/vendor/",
    };
    size_t i;
    if (!path) return 0;
    for (i = 0; i < sizeof(prefixes) / sizeof(prefixes[0]); i++) {
        if (has_prefix(path, prefixes[i])) return 1;
    }
    return 0;
}

static int is_elf_binary(const char *path) {
    unsigned char hdr[4];
    int fd, n;
    if (!path) return 0;
    if (!real_open) resolve_all();
    if (!real_read) resolve_all();
    if (!real_close) resolve_all();
    if (!real_open || !real_read || !real_close) return 0;
    fd = real_open(path, O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) return 0;
    n = real_read(fd, hdr, 4);
    real_close(fd);
    return n == 4 && hdr[0] == 0x7f && hdr[1] == 'E' && hdr[2] == 'L' && hdr[3] == 'F';
}

/* ----------------------------------------------------------------------------
 * 16KB-page runtime alignment probe (read-only diagnostic).
 *
 * `-z,max-page-size=16384` fixes alignment at link time for ELFs we build, but
 * guest binaries loaded from the imagefs (box64/wine, uncompressed resources)
 * may still carry 4KB assumptions. On a 16KB-page device the kernel rejects an
 * mmap offset that is not a multiple of the real page size (EINVAL) and
 * linker64/box64 refuse PT_LOAD segments whose p_align is smaller than the page
 * size. We only *assert* here (log early), never rewrite mappings: silently
 * changing mmap/ELF semantics in a preload shim is far more dangerous than a
 * clear diagnostic.
 * -------------------------------------------------------------------------- */

typedef struct {
    unsigned char e_ident[16];
    uint16_t e_type;
    uint16_t e_machine;
    uint32_t e_version;
    uint64_t e_entry;
    uint64_t e_phoff;
    uint64_t e_shoff;
    uint32_t e_flags;
    uint16_t e_ehsize;
    uint16_t e_phentsize;
    uint16_t e_phnum;
    uint16_t e_shentsize;
    uint16_t e_shnum;
    uint16_t e_shstrndx;
} wn_elf64_ehdr_t;

typedef struct {
    uint32_t p_type;
    uint32_t p_flags;
    uint64_t p_offset;
    uint64_t p_vaddr;
    uint64_t p_paddr;
    uint64_t p_filesz;
    uint64_t p_memsz;
    uint64_t p_align;
} wn_elf64_phdr_t;

#define WN_PT_LOAD 1

static void check_elf_load_alignment(const char *path) {
    wn_elf64_ehdr_t eh;
    wn_elf64_phdr_t ph;
    uint64_t i;
    int fd;
    long n;

    if (!path || g_page_size <= 0) return;
    if (!real_open) resolve_all();
    if (!real_close) resolve_all();
    if (!real_open || !real_close) return;

    fd = real_open(path, O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) return;

    n = raw_syscall6(SYS_pread64, (long)fd, (long)&eh, (long)sizeof(eh), 0, 0, 0);
    if (n != (long)sizeof(eh)) goto out;
    if (eh.e_ident[0] != 0x7f || eh.e_ident[1] != 'E' ||
        eh.e_ident[2] != 'L' || eh.e_ident[3] != 'F') goto out;
    if (eh.e_ident[4] != 2 /* ELFCLASS64 */) goto out;
    if (eh.e_phoff == 0 || eh.e_phnum == 0 ||
        eh.e_phentsize != sizeof(ph)) goto out;

    for (i = 0; i < eh.e_phnum; i++) {
        uint64_t off = eh.e_phoff + i * (uint64_t)eh.e_phentsize;
        n = raw_syscall6(SYS_pread64, (long)fd, (long)&ph, (long)sizeof(ph),
                         (long)off, 0, 0);
        if (n != (long)sizeof(ph)) break;
        if (ph.p_type == WN_PT_LOAD && ph.p_align != 0 && ph.p_align != 1 &&
            ph.p_align < (uint64_t)g_page_size) {
            LOGW("guest ELF %s PT_LOAD p_align=0x%llx < page_size=%ld "
                 "(not loadable on a 16KB-page device)",
                 path, (unsigned long long)ph.p_align, g_page_size);
        }
    }

out:
    real_close(fd);
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    char rp[PATH_MAX];
    const char *resolved;
    char **new_argv;
    int argc, i, rc;

    LOGI("execve(path=%s)", path ? path : "(null)");
    if (!real_execve)
        real_execve = (int (*)(const char *, char *const[], char *const[]))dlsym(RTLD_NEXT, "execve");
    if (!real_execve) {
        errno = ENOSYS;
        return -1;
    }
    if (!path) {
        errno = EINVAL;
        return -1;
    }

    if (ends_with(path, "/wine-preloader")) {
        LOGI("execve rejecting wine-preloader (EACCES)");
        errno = EACCES;
        return -1;
    }

    if (should_direct_exec(path)) {
        return real_execve(path, argv, envp);
    }

    resolved = path;
    if (realpath(path, rp)) {
        resolved = rp;
    }

    if (!is_elf_binary(resolved)) {
        return real_execve(path, argv, envp);
    }

    check_elf_load_alignment(resolved);

    argc = 0;
    if (argv) {
        while (argv[argc]) argc++;
    }

    new_argv = (char **)calloc((size_t)argc + 3, sizeof(char *));
    if (!new_argv) {
        errno = ENOMEM;
        return -1;
    }

    new_argv[0] = (char *)resolved;
    new_argv[1] = (char *)resolved;
    for (i = 1; i < argc; i++) {
        new_argv[i + 1] = argv[i];
    }
    new_argv[argc + 1] = NULL;

    LOGI("execve wrapping %s via /system/bin/linker64 (argc=%d)", resolved, argc);
    rc = real_execve("/system/bin/linker64", new_argv, envp);
    free(new_argv);
    if (rc != 0) {
        LOGI("execve FAILED linker64 path=%s rc=%d errno=%d (%s)", resolved, rc, errno,
             strerror(errno));
    }
    return rc;
}

int execv(const char *path, char *const argv[]) {
    LOGI("execv(path=%s)", path ? path : "(null)");
    return execve(path, argv, environ);
}

/* ----------------------------------------------------------------------------
 * Input device shielding (matches the closed-source shim).
 *
 * Wine on Android must not read physical /dev/input/event* nodes directly;
 * input is delivered through the app. We make ioctl() return ENOTTY and read()
 * return EAGAIN on those nodes so Wine treats them as unavailable.
 * -------------------------------------------------------------------------- */

static int is_event_node(const char *path) {
    return path && strncmp(path, "/dev/input/event", 16) == 0;
}

int ioctl(int fd, int request, ...) {
    char fdpath[64];
    char link[64];
    ssize_t n;
    void *arg;

    if (!real_ioctl) {
        real_ioctl = (int (*)(int, int, void *))dlsym(RTLD_NEXT, "ioctl");
    }
    if (!real_ioctl) {
        errno = ENOSYS;
        return -1;
    }

    /* ioctl's third variadic argument is a pointer/integer in x2 on aarch64. */
    __asm__ volatile("mov %0, x2" : "=r"(arg));

    snprintf(fdpath, sizeof(fdpath), "/proc/self/fd/%d", fd);
    n = readlink(fdpath, link, sizeof(link) - 1);
    if (n > 0) {
        link[n] = '\0';
        if (is_event_node(link)) {
            errno = ENOTTY;
            return -1;
        }
    }

    return real_ioctl(fd, request, arg);
}

ssize_t read(int fd, void *buf, size_t count) {
    char fdpath[64];
    char link[64];
    ssize_t n;

    if (!real_read) {
        real_read = (ssize_t (*)(int, void *, size_t))dlsym(RTLD_NEXT, "read");
    }
    if (!real_read) {
        errno = ENOSYS;
        return -1;
    }

    snprintf(fdpath, sizeof(fdpath), "/proc/self/fd/%d", fd);
    n = readlink(fdpath, link, sizeof(link) - 1);
    if (n > 0) {
        link[n] = '\0';
        if (is_event_node(link)) {
            errno = EAGAIN;
            return -1;
        }
    }

    return real_read(fd, buf, count);
}

/* ----------------------------------------------------------------------------
 * SIGSEGV handler: flip tracked pages between R-X and R-W, then retry.
 * -------------------------------------------------------------------------- */

static struct sigaction g_prev_segv;
static int g_prev_segv_valid;

static void wx_sigsegv_handler(int sig, siginfo_t *si, void *ctx);

/* Re-install our own SIGSEGV handler as the kernel's current disposition.
 * Uses real_sigaction directly so we never recurse through the sigaction/signal
 * interceptors defined at the bottom of this file. */
static void reinstall_self_handler(void) {
    struct sigaction sa;
    if (!real_sigaction) return;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = wx_sigsegv_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO | SA_NODEFER;
    real_sigaction(SIGSEGV, &sa, NULL);
}

/* Return the guest's most recently registered SIGSEGV disposition, or SIG_DFL
 * if none has been registered yet. */
static void query_guest_sigsegv(struct sigaction *out) {
    if (!out) return;
    if (g_prev_segv_valid) {
        *out = g_prev_segv;
    } else {
        memset(out, 0, sizeof(*out));
        out->sa_handler = SIG_DFL;
    }
}

static void save_guest_sigsegv_sigaction(const struct sigaction *act) {
    if (!act) return;
    g_prev_segv = *act;
    g_prev_segv_valid = 1;
    reinstall_self_handler();
}

static void save_guest_sigsegv_handler(sighandler_t handler) {
    memset(&g_prev_segv, 0, sizeof(g_prev_segv));
    g_prev_segv.sa_handler = handler;
    g_prev_segv.sa_flags = 0;
    g_prev_segv_valid = 1;
    reinstall_self_handler();
}

static void forward_signal(int sig, siginfo_t *si, void *ctx) {
    if (g_prev_segv_valid) {
        if (g_prev_segv.sa_flags & SA_SIGINFO) {
            g_prev_segv.sa_sigaction(sig, si, ctx);
            return;
        }
        if (g_prev_segv.sa_handler == SIG_DFL) {
            if (real_sigaction) real_sigaction(SIGSEGV, &g_prev_segv, NULL);
            raise(sig);
            return;
        }
        if (g_prev_segv.sa_handler == SIG_IGN) {
            return;
        }
        g_prev_segv.sa_handler(sig);
        return;
    }
    if (real_sigaction) real_sigaction(SIGSEGV, &g_prev_segv, NULL);
    raise(sig);
}

static void wx_sigsegv_handler(int sig, siginfo_t *si, void *ctx) {
    uintptr_t fault, pc, page;
    ucontext_t *uc = (ucontext_t *)ctx;
    int exec_fault, prot, region;
    wx_region_t r;
    long rc;

    fault = (uintptr_t)si->si_addr;
    pc = (uintptr_t)uc->uc_mcontext.pc;

    if (log_budget(&g_log_fault, 40)) {
        LOGI("SIGSEGV code=%d fault=%p pc=%p", si->si_code, (void *)fault, (void *)pc);
    }

    if (si->si_code != SEGV_ACCERR) {
        if (log_budget(&g_log_fault_detail, 20)) {
            LOGI("SIGSEGV non-ACCERR -> forwarding to guest");
        }
        forward_signal(sig, si, ctx);
        return;
    }

    page = fault & ~(uintptr_t)(g_page_size - 1);

    /* Execute fault: the faulting address is the instruction that could not be
     * fetched (PC). Data fault: the faulting address is a data location that is
     * not writable. Flip the page accordingly and retry the access. */
    exec_fault = (pc >= page && pc < page + (uintptr_t)g_page_size);

    /* Hot-cache fast path: an execute fault whose PC still falls inside the last
     * dual-mapped region published by the writer path is redirected straight to
     * the RX mirror — no seqlock region read, no binary search. */
    if (exec_fault) {
        unsigned hs0 = __atomic_load_n(&g_hot_seq, __ATOMIC_ACQUIRE);
        if (!(hs0 & 1u)) {
            uintptr_t hs = __atomic_load_n(&g_hot_start, __ATOMIC_ACQUIRE);
            uintptr_t he = __atomic_load_n(&g_hot_end, __ATOMIC_ACQUIRE);
            uintptr_t hr = __atomic_load_n(&g_hot_rx, __ATOMIC_ACQUIRE);
            unsigned hs1 = __atomic_load_n(&g_hot_seq, __ATOMIC_ACQUIRE);
            if (hs1 == hs0 && hs != 0 && pc >= hs && pc < he) {
                uintptr_t new_pc = hr + (pc - hs);
                if (log_budget(&g_log_fault_detail, 20)) {
                    LOGI("SIGSEGV hot-cache exec redirect pc=%p -> %p",
                         (void *)pc, (void *)new_pc);
                }
                uc->uc_mcontext.pc = new_pc;
                return;
            }
        }
    }

    r.start = 0;
    r.len = 0;
    r.rx_start = 0;
    r.memfd = -1;
    region = find_region_info(page, &r);

    if (region < 0) {
        /* Fallback: consult /proc/self/maps for executable regions the tracking
         * table may have missed (mremap moves, raw-syscall maps, pre-constructor
         * maps). SEGV_ACCERR guarantees the page is mapped but the current
         * permissions disallow this access. */
        uintptr_t vma_start = 0, vma_end = 0;
        int vma_exec = 0;

        if (!find_vma_at(page, &vma_start, &vma_end, &vma_exec)) {
            if (log_budget(&g_log_fault_detail, 20)) {
                LOGI("SIGSEGV page not mapped -> forwarding to guest (fault=%p)", (void *)fault);
            }
            forward_signal(sig, si, ctx);
            return;
        }

        /* A write fault on a page that is not currently executable is a plain
         * data fault, not a JIT self-modifying write; leave it to the guest. */
        if (!exec_fault && !vma_exec) {
            if (log_budget(&g_log_fault_detail, 20)) {
                LOGI("SIGSEGV write fault on non-exec page -> forwarding to guest (fault=%p)",
                     (void *)fault);
            }
            forward_signal(sig, si, ctx);
            return;
        }

        if (log_budget(&g_log_fault_detail, 20)) {
            LOGI("SIGSEGV maps fallback vma=[%p,%p) exec=%d fault=%p",
                 (void *)vma_start, (void *)vma_end, vma_exec, (void *)fault);
        }

        /* No mirror known for the maps fallback; treat it as a flip region. */
        r.start = vma_start;
        r.len = vma_end - vma_start;
        r.rx_start = 0;
        r.memfd = -1;
    }

    /* Dual-map fast path: an execute fault inside a region that has an RX mirror
     * is satisfied by redirecting the program counter to the same physical page's
     * executable view. No mprotect, no TLB shootdown, no syscall. */
    if (exec_fault && r.rx_start != 0 && pc >= r.start && pc < r.start + r.len) {
        uintptr_t new_pc = r.rx_start + (pc - r.start);
        if (log_budget(&g_log_fault_detail, 20)) {
            LOGI("SIGSEGV dual-map exec redirect pc=%p -> %p", (void *)pc, (void *)new_pc);
        }
        uc->uc_mcontext.pc = new_pc;
        return; /* retry the faulting instruction from the RX mirror */
    }

    prot = exec_fault ? (PROT_READ | PROT_EXEC) : (PROT_READ | PROT_WRITE);

    if (log_budget(&g_log_fault_detail, 20)) {
        LOGI("SIGSEGV flip region=%d page=%p to %s", region, (void *)page,
             exec_fault ? "RX" : "RW");
    }

    rc = raw_syscall6(SYS_mprotect, (long)page, (long)g_page_size, (long)prot, 0, 0, 0);
    if (rc == 0) {
        return; /* retry the faulting instruction/data access */
    }

    LOGW("SIGSEGV mprotect failed rc=%ld -> forwarding to guest", rc);
    forward_signal(sig, si, ctx);
}

static void install_sigsegv_handler(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = wx_sigsegv_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO | SA_NODEFER;
    if (real_sigaction && real_sigaction(SIGSEGV, &sa, &g_prev_segv) == 0) {
        g_prev_segv_valid = 1;
    }
}

/* ----------------------------------------------------------------------------
 * Bionic path redirection for /system/lib{64}/<name>.
 * -------------------------------------------------------------------------- */

static int path_exists(const char *path) {
    if (!real_access) return 0;
    return real_access(path, F_OK) == 0;
}

static const char *redirect_bionic_path(const char *path) {
    static __thread char bionic_buf[PATH_MAX];
    static __thread char apex_buf[PATH_MAX];

    const char *name;
    const char *bionic_prefix;
    const char *apex_prefix;
    int n;

    if (!path || path[0] != '/') return path;
    if (strncmp(path, "/apex/", 6) == 0) return path;

    if (strncmp(path, "/system/lib64/", 14) == 0) {
        name = path + 14;
        bionic_prefix = "/apex/com.android.runtime/lib64/bionic/";
        apex_prefix = "/apex/com.android.runtime/lib64/";
    } else if (strncmp(path, "/system/lib/", 12) == 0) {
        name = path + 12;
        bionic_prefix = "/apex/com.android.runtime/lib/bionic/";
        apex_prefix = "/apex/com.android.runtime/lib/";
    } else {
        return path;
    }

    if (*name == '\0') return path;

    n = snprintf(bionic_buf, sizeof(bionic_buf), "%s%s", bionic_prefix, name);
    if (n > 0 && n < (int)sizeof(bionic_buf) && path_exists(bionic_buf)) {
        return bionic_buf;
    }

    n = snprintf(apex_buf, sizeof(apex_buf), "%s%s", apex_prefix, name);
    if (n > 0 && n < (int)sizeof(apex_buf) && path_exists(apex_buf)) {
        return apex_buf;
    }

    return path;
}

/* ----------------------------------------------------------------------------
 * Wine install path redirection (matches the closed-source shim).
 *
 * When Wine is loaded through /system/bin/linker64, /proc/self/exe points at
 * the linker instead of Wine, so Wine derives its data/library directories from
 * /apex/com.android.runtime/bin and tries to open files under
 * ".../bin/../share/wine" and ".../bin/../lib/wine", which do not exist. We
 * rewrite those prefixes to the real Wine install directory, discovered from
 * the REDIRECT_EXEC__PROC_SELF_EXE environment variable (set by the launcher to
 * "<wineDir>/bin/wine").
 * -------------------------------------------------------------------------- */

static char g_wine_share_dir[PATH_MAX];
static char g_wine_lib_dir[PATH_MAX];
static int g_wine_redirect_valid;
static int g_log_wine_redirect;

static void init_wine_redirect(void) {
    const char *self_exe = getenv("REDIRECT_EXEC__PROC_SELF_EXE");
    const char *suffix = "/bin/wine";
    size_t len, slen;
    char base[PATH_MAX];
    int n;

    if (!self_exe) return;
    len = strlen(self_exe);
    slen = strlen(suffix);
    if (len <= slen || strcmp(self_exe + len - slen, suffix) != 0) return;

    memcpy(base, self_exe, len - slen);
    base[len - slen] = '\0';

    n = snprintf(g_wine_share_dir, sizeof(g_wine_share_dir), "%s/share/wine", base);
    if (n <= 0 || n >= (int)sizeof(g_wine_share_dir)) return;
    n = snprintf(g_wine_lib_dir, sizeof(g_wine_lib_dir), "%s/lib/wine", base);
    if (n <= 0 || n >= (int)sizeof(g_wine_lib_dir)) return;

    g_wine_redirect_valid = 1;
    LOGI("Wine redirect: share=%s lib=%s", g_wine_share_dir, g_wine_lib_dir);
}

static const char *redirect_wine_path(const char *path) {
    static __thread char wine_buf[PATH_MAX];
    static const char *const share_prefixes[] = {
        "/apex/com.android.runtime/bin/../share/wine",
        "/apex/com.android.runtime/bin//../share/wine",
    };
    static const char *const lib_prefixes[] = {
        "/apex/com.android.runtime/bin/../lib/wine",
        "/apex/com.android.runtime/bin//../lib/wine",
    };
    size_t i, n;
    int rc;

    if (!g_wine_redirect_valid || !path || path[0] != '/') return path;

    for (i = 0; i < sizeof(share_prefixes) / sizeof(share_prefixes[0]); i++) {
        n = strlen(share_prefixes[i]);
        if (strncmp(path, share_prefixes[i], n) == 0) {
            rc = snprintf(wine_buf, sizeof(wine_buf), "%s%s", g_wine_share_dir, path + n);
            if (rc <= 0 || rc >= (int)sizeof(wine_buf)) return path;
            if (log_budget(&g_log_wine_redirect, 16)) {
                LOGI("rewrite wine path: %s -> %s", path, wine_buf);
            }
            return wine_buf;
        }
    }

    for (i = 0; i < sizeof(lib_prefixes) / sizeof(lib_prefixes[0]); i++) {
        n = strlen(lib_prefixes[i]);
        if (strncmp(path, lib_prefixes[i], n) == 0) {
            rc = snprintf(wine_buf, sizeof(wine_buf), "%s%s", g_wine_lib_dir, path + n);
            if (rc <= 0 || rc >= (int)sizeof(wine_buf)) return path;
            if (log_budget(&g_log_wine_redirect, 16)) {
                LOGI("rewrite wine path: %s -> %s", path, wine_buf);
            }
            return wine_buf;
        }
    }

    return path;
}

/* Combined redirect: Wine install paths first, then bionic /system/lib paths. */
static const char *redirect_path(const char *path) {
    return redirect_bionic_path(redirect_wine_path(path));
}

/* ----------------------------------------------------------------------------
 * Interceptors.
 * -------------------------------------------------------------------------- */

/* Can this RWX mmap request be satisfied with a memfd dual-map? We only take
 * that path for anonymous, non-fixed mappings without a file backing, because
 * those are exactly the mappings a JIT allocates for code. Anything else (fixed
 * addresses, MAP_SHARED file mappings, fd-backed mappings) keeps the original
 * downgrade-to-RW + mprotect-flip behavior. */
static int can_dual_map(void *addr, int flags, int fd) {
    if (addr != NULL) return 0;
    if (fd != -1) return 0;
    if (flags & MAP_FIXED) return 0;
#ifdef MAP_FIXED_NOREPLACE
    if (flags & MAP_FIXED_NOREPLACE) return 0;
#endif
#ifdef MAP_ANONYMOUS
    if (!(flags & MAP_ANONYMOUS)) return 0;
#endif
    return 1;
}

/* Allocate a dual-mapped RWX region backed by an anonymous memfd:
 *   - create + ftruncate the memfd to `len`
 *   - map it once RW (the address returned to the JIT) and once RX (the mirror)
 * On any failure, unwind cleanly and return MAP_FAILED so the caller falls back
 * to the downgrade-to-RW path. */
static void *mmap_rwx_dual(size_t len) {
    long memfd;
    void *rw, *rx;
    size_t file_len;

    memfd = raw_syscall6(SYS_memfd_create, (long)"wn-wx-jit", (long)MFD_CLOEXEC, 0, 0, 0, 0);
    if (memfd < 0) return MAP_FAILED;

    /* The kernel rounds mmap length up to a page, so the backing file must be at
     * least that large or the tail page would fault with SIGBUS on access. */
    file_len = (len + (size_t)g_page_size - 1) & ~((size_t)g_page_size - 1);
    if (raw_syscall6(SYS_ftruncate, memfd, (long)file_len, 0, 0, 0, 0) != 0) {
        close_memfd_if_any((int)memfd);
        return MAP_FAILED;
    }

    if (!real_mmap) resolve_all();
    if (!real_mmap) {
        close_memfd_if_any((int)memfd);
        return MAP_FAILED;
    }

    rw = real_mmap(NULL, len, PROT_READ | PROT_WRITE, MAP_SHARED, (int)memfd, 0);
    if (rw == MAP_FAILED) {
        close_memfd_if_any((int)memfd);
        return MAP_FAILED;
    }

    rx = real_mmap(NULL, len, PROT_READ | PROT_EXEC, MAP_SHARED, (int)memfd, 0);
    if (rx == MAP_FAILED) {
        raw_syscall6(SYS_munmap, (long)rw, (long)len, 0, 0, 0, 0);
        close_memfd_if_any((int)memfd);
        return MAP_FAILED;
    }

    track_region_dual((uintptr_t)rw, len, (uintptr_t)rx, (int)memfd);
    return rw;
}

/* File-backed mmap() requires the file offset to be a multiple of the real page
 * size. Guest code that lays out uncompressed resources (Godot noCompress) or
 * maps ELF segments assuming 4KB pages will pass a 4KB-aligned offset that the
 * kernel rejects with EINVAL on a 16KB-page device. Log it once so the failure
 * is attributable instead of a silent EINVAL. */
static void check_mmap_offset_alignment(int fd, off_t offset) {
    if (fd < 0 || offset == 0 || g_page_size <= 0) return;
    if (((uint64_t)offset & ((uint64_t)g_page_size - 1)) == 0) return;
    if (log_budget(&g_log_misaligned, 32)) {
        LOGW("mmap fd=%d offset=0x%llx not aligned to page size %ld "
             "(EINVAL on a 16KB-page device)",
             fd, (unsigned long long)offset, g_page_size);
    }
}

int mprotect(void *addr, size_t len, int prot) {
    if (!real_mprotect) resolve_all();
    if (!real_mprotect) {
        errno = ENOSYS;
        return -1;
    }

    if (prot & PROT_EXEC) {
        if (prot & PROT_WRITE) {
            /* RWX: the kernel would reject it under W^X, so downgrade to RW. */
            int rw = (prot & ~PROT_EXEC) | PROT_READ;
            int rc = real_mprotect(addr, len, rw);
            if (rc == 0) {
                track_region((uintptr_t)addr, len);
            }
            if (log_budget(&g_log_mmap_rwx, 16)) {
                LOGI("mprotect RWX->RW addr=%p len=%zu rc=%d", addr, len, rc);
            }
            return rc;
        }
        /* RX (or R+X without W): pass through unchanged but track it so a later
         * self-modifying write can be flipped back to RW. */
        int rc = real_mprotect(addr, len, prot);
        if (rc == 0) {
            track_region((uintptr_t)addr, len);
        }
        return rc;
    }

    return real_mprotect(addr, len, prot);
}

void *mmap(void *addr, size_t len, int prot, int flags, int fd, off_t offset) {
    if (!real_mmap) resolve_all();
    if (!real_mmap) {
        errno = ENOSYS;
        return MAP_FAILED;
    }

    check_mmap_offset_alignment(fd, offset);

    if (prot & PROT_EXEC) {
        if (prot & PROT_WRITE) {
            /* RWX: prefer a memfd dual-map (RW view returned to the JIT, RX
             * mirror for execution); fall back to downgrade-to-RW otherwise. */
            if (can_dual_map(addr, flags, fd)) {
                void *p = mmap_rwx_dual(len);
                if (p != MAP_FAILED) {
                    if (log_budget(&g_log_mmap_rwx, 16)) {
                        LOGI("mmap RWX->dual addr=%p len=%zu", p, len);
                    }
                    return p;
                }
            }
            int rw = (prot & ~PROT_EXEC) | PROT_READ;
            void *p = real_mmap(addr, len, rw, flags, fd, offset);
            if (p != MAP_FAILED) {
                track_region((uintptr_t)p, len);
            }
            if (log_budget(&g_log_mmap_rwx, 16)) {
                LOGI("mmap RWX->RW addr=%p len=%zu", p, len);
            }
            return p;
        }
        /* RX mapping: track it so a later self-modifying write can be flipped. */
        void *p = real_mmap(addr, len, prot, flags, fd, offset);
        if (p != MAP_FAILED) {
            track_region((uintptr_t)p, len);
        }
        return p;
    }

    return real_mmap(addr, len, prot, flags, fd, offset);
}

void *mmap64(void *addr, size_t len, int prot, int flags, int fd, off_t offset) {
    if (!real_mmap64) resolve_all();
    if (!real_mmap64) real_mmap64 = real_mmap;
    if (!real_mmap64) {
        errno = ENOSYS;
        return MAP_FAILED;
    }

    check_mmap_offset_alignment(fd, offset);

    if (prot & PROT_EXEC) {
        if (prot & PROT_WRITE) {
            /* RWX: prefer a memfd dual-map (RW view returned to the JIT, RX
             * mirror for execution); fall back to downgrade-to-RW otherwise. */
            if (can_dual_map(addr, flags, fd)) {
                void *p = mmap_rwx_dual(len);
                if (p != MAP_FAILED) {
                    if (log_budget(&g_log_mmap_rwx, 16)) {
                        LOGI("mmap64 RWX->dual addr=%p len=%zu", p, len);
                    }
                    return p;
                }
            }
            int rw = (prot & ~PROT_EXEC) | PROT_READ;
            void *p = real_mmap64(addr, len, rw, flags, fd, offset);
            if (p != MAP_FAILED) {
                track_region((uintptr_t)p, len);
            }
            if (log_budget(&g_log_mmap_rwx, 16)) {
                LOGI("mmap64 RWX->RW addr=%p len=%zu", p, len);
            }
            return p;
        }
        /* RX mapping: track it so a later self-modifying write can be flipped. */
        void *p = real_mmap64(addr, len, prot, flags, fd, offset);
        if (p != MAP_FAILED) {
            track_region((uintptr_t)p, len);
        }
        return p;
    }

    return real_mmap64(addr, len, prot, flags, fd, offset);
}

int munmap(void *addr, size_t len) {
    if (!real_munmap) resolve_all();
    untrack_range((uintptr_t)addr, len);
    return real_munmap ? real_munmap(addr, len) : -1;
}

int memfd_create(const char *name, unsigned int flags) {
    if (!real_memfd_create) resolve_all();
    if (!real_memfd_create) {
        errno = ENOSYS;
        return -1;
    }
    return real_memfd_create(name, flags);
}

int open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (!real_open) resolve_all();
    record_open(path, flags);

    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, mode_t);
        va_end(ap);
    }

    if (!real_open) {
        errno = ENOSYS;
        return -1;
    }
    return real_open(redirect_path(path), flags, mode);
}

int open64(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (!real_open64) resolve_all();
    record_open(path, flags);

    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, mode_t);
        va_end(ap);
    }

    if (!real_open64) real_open64 = real_open;
    if (!real_open64) {
        errno = ENOSYS;
        return -1;
    }
    return real_open64(redirect_path(path), flags, mode);
}

int openat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (!real_openat) resolve_all();
    record_open(path, flags);

    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, mode_t);
        va_end(ap);
    }

    if (!real_openat) {
        errno = ENOSYS;
        return -1;
    }
    if (dirfd == AT_FDCWD) {
        path = redirect_path(path);
    }
    return real_openat(dirfd, path, flags, mode);
}

int openat64(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (!real_openat64) resolve_all();
    record_open(path, flags);

    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, mode_t);
        va_end(ap);
    }

    if (!real_openat64) real_openat64 = real_openat;
    if (!real_openat64) {
        errno = ENOSYS;
        return -1;
    }
    if (dirfd == AT_FDCWD) {
        path = redirect_path(path);
    }
    return real_openat64(dirfd, path, flags, mode);
}

int access(const char *path, int mode) {
    if (!real_access) resolve_all();
    if (!real_access) {
        errno = ENOSYS;
        return -1;
    }
    return real_access(redirect_path(path), mode);
}

int faccessat(int dirfd, const char *path, int mode, int flags) {
    if (!real_faccessat) resolve_all();
    if (!real_faccessat) {
        errno = ENOSYS;
        return -1;
    }
    if (dirfd == AT_FDCWD) {
        path = redirect_path(path);
    }
    return real_faccessat(dirfd, path, mode, flags);
}

int stat(const char *path, struct stat *buf) {
    if (!real_stat) resolve_all();
    if (!real_stat) {
        errno = ENOSYS;
        return -1;
    }
    return real_stat(redirect_path(path), buf);
}

int lstat(const char *path, struct stat *buf) {
    if (!real_lstat) resolve_all();
    if (!real_lstat) {
        errno = ENOSYS;
        return -1;
    }
    return real_lstat(redirect_path(path), buf);
}

int fstatat(int dirfd, const char *path, struct stat *buf, int flags) {
    if (!real_fstatat) resolve_all();
    if (!real_fstatat) {
        errno = ENOSYS;
        return -1;
    }
    if (dirfd == AT_FDCWD) {
        path = redirect_path(path);
    }
    return real_fstatat(dirfd, path, buf, flags);
}

/* ----------------------------------------------------------------------------
 * Signal disposition interception.
 *
 * The guest (Wine/FEXCore) installs its own SIGSEGV handlers through
 * sigaction()/signal(). Without interception those calls would replace our
 * W^X handler, so JIT pages would fault forever (or the process would hang).
 * We keep our handler as the kernel's disposition and only record the guest's
 * handler, forwarding non-JIT faults to it from wx_sigsegv_handler().
 *
 * On LP64 bionic, sigaction() and signal() both funnel into the same internal
 * rt_sigaction path, so intercepting these two public entry points is enough.
 * -------------------------------------------------------------------------- */

int sigaction(int sig, const struct sigaction *act, struct sigaction *oldact) {
    if (!real_sigaction) resolve_all();
    if (sig == SIGSEGV) {
        if (log_budget(&g_log_sigaction, 16)) {
            LOGI("sigaction(SIGSEGV) intercepted, act=%p oldact=%p", (void *)act, (void *)oldact);
        }
        query_guest_sigsegv(oldact);
        save_guest_sigsegv_sigaction(act);
        return 0;
    }
    return real_sigaction ? real_sigaction(sig, act, oldact) : -1;
}

sighandler_t signal(int sig, sighandler_t handler) {
    if (!real_signal) resolve_all();
    if (sig == SIGSEGV) {
        struct sigaction prev;
        sighandler_t old;
        if (log_budget(&g_log_sigaction, 16)) {
            LOGI("signal(SIGSEGV) intercepted, handler=%p", (void *)handler);
        }
        query_guest_sigsegv(&prev);
        old = prev.sa_handler;
        save_guest_sigsegv_handler(handler);
        return old;
    }
    return real_signal ? real_signal(sig, handler) : SIG_ERR;
}

/* ----------------------------------------------------------------------------
 * Initialization.
 * -------------------------------------------------------------------------- */

__attribute__((constructor)) static void wx_init(void) {
    long ps = sysconf(_SC_PAGESIZE);
    g_page_size = ps > 0 ? ps : 4096;
    resolve_all();
    init_wine_redirect();
    install_sigsegv_handler();
    LOGI("W^X shim initialized (page_size=%ld)", g_page_size);
}