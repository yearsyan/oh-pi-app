#include "pi_ssh_platform.h"

#include <libssh/libssh.h>
#include <libssh/poll.h>

#include <assert.h>
#include <stdio.h>

typedef struct platform_thread_test_context {
    pi_ssh_mutex *mutex;
    int *value;
} platform_thread_test_context;

static void increment_value(void *userdata)
{
    platform_thread_test_context *context =
        (platform_thread_test_context *)userdata;

    pi_ssh_mutex_lock(context->mutex);
    *context->value += 1;
    pi_ssh_mutex_unlock(context->mutex);
}

static int wake_callback(socket_t socket_value,
                         int revents,
                         void *userdata)
{
    short *observed_events = (short *)userdata;

    (void)socket_value;
    *observed_events = (short)revents;
    return 0;
}

int main(void)
{
    pi_ssh_mutex mutex;
    pi_ssh_thread thread;
    platform_thread_test_context thread_context;
    pi_ssh_socket listener = PI_SSH_INVALID_SOCKET;
    pi_ssh_socket wake_reader = PI_SSH_INVALID_SOCKET;
    pi_ssh_socket wake_writer = PI_SSH_INVALID_SOCKET;
    ssh_event event = NULL;
    uint16_t local_port = 0;
    short observed_events = 0;
    int value = 0;

    assert(pi_ssh_platform_initialize() == 0);
    assert(pi_ssh_platform_monotonic_seconds() > 0);

    assert(pi_ssh_mutex_initialize(&mutex) == 0);
    thread_context.mutex = &mutex;
    thread_context.value = &value;
    assert(pi_ssh_thread_start(&thread, increment_value, &thread_context) == 0);
    assert(pi_ssh_thread_join(thread) == 0);
    assert(value == 1);
    pi_ssh_mutex_destroy(&mutex);

    listener = pi_ssh_socket_create_listener(&local_port);
    assert(pi_ssh_socket_is_valid(listener));
    assert(local_port != 0);
    pi_ssh_socket_close(listener);

    assert(pi_ssh_wake_pair_create(&wake_reader, &wake_writer) == 0);
    event = ssh_event_new();
    assert(event != NULL);
    assert(ssh_event_add_fd(event,
                            wake_reader,
                            POLLIN,
                            wake_callback,
                            &observed_events) == SSH_OK);
    pi_ssh_wake_pair_signal(wake_writer);
    assert(ssh_event_dopoll(event, 1000) == SSH_OK);
    assert((observed_events & POLLIN) != 0);
    pi_ssh_wake_pair_drain(wake_reader);

    assert(ssh_event_remove_fd(event, wake_reader) == SSH_OK);
    ssh_event_free(event);
    pi_ssh_socket_close(wake_reader);
    pi_ssh_socket_close(wake_writer);

    printf("pi_ssh platform test passed\n");
    return 0;
}
