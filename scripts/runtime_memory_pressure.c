/* Bounded, shell-only Android memory pressure fixture. Never shipped in the APK. */
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

static long available_kb(void) {
    FILE *f = fopen("/proc/meminfo", "r");
    if (!f) return -1;
    char line[256]; long value = -1;
    while (fgets(line, sizeof(line), f)) if (sscanf(line, "MemAvailable: %ld kB", &value) == 1) break;
    fclose(f); return value;
}

int main(int argc, char **argv) {
    int target_mb = argc > 1 ? atoi(argv[1]) : 1536;
    int maximum_mb = argc > 2 ? atoi(argv[2]) : 3072;
    int seconds = argc > 3 ? atoi(argv[3]) : 120;
    if (target_mb < 1024 || maximum_mb < 64 || maximum_mb > 4096 || seconds < 10 || seconds > 180) return 2;
    /* The fixture, rather than another app, must be the preferred LMK victim. */
    FILE *adj = fopen("/proc/self/oom_score_adj", "w");
    if (!adj || fprintf(adj, "1000") < 0 || fclose(adj) != 0) return 3;
    adj = fopen("/proc/self/oom_score_adj", "r"); int priority = 0;
    if (!adj || fscanf(adj, "%d", &priority) != 1 || priority != 1000) return 4;
    fclose(adj);
    const size_t chunk = 64UL * 1024 * 1024;
    void *allocations[64] = {0}; int count = 0;
    time_t start = time(NULL);
    setbuf(stdout, NULL);
    while (time(NULL) - start < seconds && access("/data/local/tmp/rikka-pressure.stop", F_OK) != 0) {
        long available = available_kb();
        if (available < 512L * 1024) break;
        if (available > target_mb * 1024L && (count + 1) * 64 <= maximum_mb) {
            void *p = mmap(NULL, chunk, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (p == MAP_FAILED) break;
            allocations[count++] = p;
            uint64_t random = 0x9e3779b97f4a7c15ULL ^ (uint64_t)count;
            for (size_t n = 0; n < chunk / sizeof(uint64_t); ++n) {
                random ^= random << 13; random ^= random >> 7; random ^= random << 17;
                ((volatile uint64_t *)p)[n] = random;
            }
        }
        printf("{\"elapsed_s\":%ld,\"allocated_mb\":%d,\"available_kb\":%ld,\"oom_score_adj\":%d}\n",
            time(NULL) - start, count * 64, available_kb(), priority);
        usleep(250000);
    }
    for (int n = 0; n < count; ++n) munmap(allocations[n], chunk);
    puts("released");
    return 0;
}
