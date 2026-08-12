#ifndef PI_SSH_DEADLINE_H
#define PI_SSH_DEADLINE_H

#include <stdbool.h>
#include <stdint.h>

/* A platform clock that remains unavailable and returns zero for every read
 * cannot provide observable elapsed time; normal clocks may validly start at zero. */
static inline bool pi_ssh_deadline_expired(uint64_t started_at_ms,
                                           uint64_t now_ms,
                                           uint32_t timeout_ms)
{
    if (timeout_ms == 0) {
        return false;
    }
    if (now_ms < started_at_ms) {
        return true;
    }
    return now_ms - started_at_ms >= timeout_ms;
}

#endif
