#include "pi_ssh_deadline.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>

int main(void)
{
    assert(!pi_ssh_deadline_expired(1000u, 1999u, 1000u));
    assert(pi_ssh_deadline_expired(1000u, 2000u, 1000u));
    assert(pi_ssh_deadline_expired(1000u, 2500u, 1000u));
    assert(!pi_ssh_deadline_expired(0u, 999u, 1000u));
    assert(pi_ssh_deadline_expired(0u, 1000u, 1000u));
    assert(!pi_ssh_deadline_expired(1000u, 5000u, 0u));
    assert(pi_ssh_deadline_expired(2000u, 1000u, 1000u));

    printf("pi_ssh deadline test passed\n");
    return 0;
}
