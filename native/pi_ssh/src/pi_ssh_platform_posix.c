#include "pi_ssh_platform.h"

#include <libssh/libssh.h>

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

typedef struct pi_ssh_thread_context {
    pi_ssh_thread_function function;
    void *userdata;
} pi_ssh_thread_context;

static pthread_once_t pi_ssh_initialize_once = PTHREAD_ONCE_INIT;
static int pi_ssh_initialize_result = SSH_ERROR;

static void pi_ssh_initialize_once_callback(void)
{
    pi_ssh_initialize_result = ssh_init();
}

int pi_ssh_platform_initialize(void)
{
    int result = pthread_once(&pi_ssh_initialize_once,
                              pi_ssh_initialize_once_callback);
    if (result != 0) {
        return result;
    }
    return pi_ssh_initialize_result == SSH_OK ? 0 : EIO;
}

int pi_ssh_platform_last_error(void)
{
    return errno;
}

bool pi_ssh_platform_error_is_interrupted(int error_code)
{
    return error_code == EINTR;
}

bool pi_ssh_platform_error_is_would_block(int error_code)
{
    return error_code == EAGAIN || error_code == EWOULDBLOCK;
}

bool pi_ssh_platform_error_is_not_connected(int error_code)
{
    return error_code == ENOTCONN;
}

char *pi_ssh_platform_duplicate_string(const char *value)
{
    return strdup(value);
}

uint64_t pi_ssh_platform_monotonic_seconds(void)
{
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) {
        return 0;
    }
    return (uint64_t)value.tv_sec;
}

int pi_ssh_mutex_initialize(pi_ssh_mutex *mutex)
{
    return pthread_mutex_init(mutex, NULL);
}

void pi_ssh_mutex_lock(pi_ssh_mutex *mutex)
{
    (void)pthread_mutex_lock(mutex);
}

void pi_ssh_mutex_unlock(pi_ssh_mutex *mutex)
{
    (void)pthread_mutex_unlock(mutex);
}

void pi_ssh_mutex_destroy(pi_ssh_mutex *mutex)
{
    (void)pthread_mutex_destroy(mutex);
}

static void *pi_ssh_thread_trampoline(void *userdata)
{
    pi_ssh_thread_context *context = (pi_ssh_thread_context *)userdata;
    pi_ssh_thread_function function = context->function;
    void *function_userdata = context->userdata;

    free(context);
    function(function_userdata);
    return NULL;
}

int pi_ssh_thread_start(pi_ssh_thread *thread,
                        pi_ssh_thread_function function,
                        void *userdata)
{
    pi_ssh_thread_context *context =
        (pi_ssh_thread_context *)malloc(sizeof(*context));
    int result;

    if (context == NULL) {
        return ENOMEM;
    }
    context->function = function;
    context->userdata = userdata;
    result = pthread_create(thread, NULL, pi_ssh_thread_trampoline, context);
    if (result != 0) {
        free(context);
    }
    return result;
}

int pi_ssh_thread_join(pi_ssh_thread thread)
{
    return pthread_join(thread, NULL);
}

bool pi_ssh_socket_is_valid(pi_ssh_socket socket_value)
{
    return socket_value >= 0;
}

static int pi_ssh_descriptor_configure(int descriptor)
{
    int flags = fcntl(descriptor, F_GETFL, 0);

    if (flags < 0 ||
        fcntl(descriptor, F_SETFL, flags | O_NONBLOCK) < 0) {
        return -1;
    }
    flags = fcntl(descriptor, F_GETFD, 0);
    if (flags < 0 ||
        fcntl(descriptor, F_SETFD, flags | FD_CLOEXEC) < 0) {
        return -1;
    }
    return 0;
}

int pi_ssh_socket_configure(pi_ssh_socket socket_value)
{
    if (pi_ssh_descriptor_configure(socket_value) < 0) {
        return -1;
    }
#if defined(SO_NOSIGPIPE)
    {
        int enabled = 1;
        if (setsockopt(socket_value,
                       SOL_SOCKET,
                       SO_NOSIGPIPE,
                       &enabled,
                       sizeof(enabled)) < 0) {
            return -1;
        }
    }
#endif
    return 0;
}

pi_ssh_socket pi_ssh_socket_create_listener(uint16_t *local_port)
{
    pi_ssh_socket socket_value = PI_SSH_INVALID_SOCKET;
    int enabled = 1;
    struct sockaddr_in address;
    socklen_t address_length = (socklen_t)sizeof(address);

    socket_value = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (!pi_ssh_socket_is_valid(socket_value)) {
        return PI_SSH_INVALID_SOCKET;
    }
    if (setsockopt(socket_value,
                   SOL_SOCKET,
                   SO_REUSEADDR,
                   &enabled,
                   sizeof(enabled)) < 0 ||
        pi_ssh_socket_configure(socket_value) < 0) {
        pi_ssh_socket_close(socket_value);
        return PI_SSH_INVALID_SOCKET;
    }
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = 0;
    if (bind(socket_value,
             (const struct sockaddr *)&address,
             sizeof(address)) < 0 ||
        listen(socket_value, 16) < 0 ||
        getsockname(socket_value,
                    (struct sockaddr *)&address,
                    &address_length) < 0) {
        pi_ssh_socket_close(socket_value);
        return PI_SSH_INVALID_SOCKET;
    }
    *local_port = ntohs(address.sin_port);
    return socket_value;
}

pi_ssh_socket pi_ssh_socket_accept(pi_ssh_socket listener)
{
    return accept(listener, NULL, NULL);
}

int pi_ssh_socket_receive(pi_ssh_socket socket_value,
                          void *buffer,
                          size_t length)
{
    ssize_t result = recv(socket_value, buffer, length, 0);
    return result > INT_MAX ? INT_MAX : (int)result;
}

int pi_ssh_socket_send(pi_ssh_socket socket_value,
                       const void *buffer,
                       size_t length)
{
    ssize_t result;
#if defined(MSG_NOSIGNAL)
    result = send(socket_value, buffer, length, MSG_NOSIGNAL);
#else
    result = send(socket_value, buffer, length, 0);
#endif
    return result > INT_MAX ? INT_MAX : (int)result;
}

int pi_ssh_socket_shutdown_write(pi_ssh_socket socket_value)
{
    return shutdown(socket_value, SHUT_WR);
}

void pi_ssh_socket_close(pi_ssh_socket socket_value)
{
    if (pi_ssh_socket_is_valid(socket_value)) {
        (void)close(socket_value);
    }
}

int pi_ssh_wake_pair_create(pi_ssh_socket *read_socket,
                            pi_ssh_socket *write_socket)
{
    int descriptors[2];

    if (pipe(descriptors) < 0) {
        return -1;
    }
    if (pi_ssh_descriptor_configure(descriptors[0]) < 0 ||
        pi_ssh_descriptor_configure(descriptors[1]) < 0) {
        pi_ssh_socket_close(descriptors[0]);
        pi_ssh_socket_close(descriptors[1]);
        return -1;
    }
    *read_socket = descriptors[0];
    *write_socket = descriptors[1];
    return 0;
}

void pi_ssh_wake_pair_drain(pi_ssh_socket read_socket)
{
    unsigned char buffer[64];

    for (;;) {
        ssize_t count = read(read_socket, buffer, sizeof(buffer));
        int error_code;

        if (count > 0) {
            continue;
        }
        if (count == 0) {
            return;
        }
        error_code = errno;
        if (pi_ssh_platform_error_is_interrupted(error_code)) {
            continue;
        }
        return;
    }
}

void pi_ssh_wake_pair_signal(pi_ssh_socket write_socket)
{
    const unsigned char wake = 1;

    for (;;) {
        ssize_t result = write(write_socket, &wake, sizeof(wake));
        int error_code;

        if (result >= 0) {
            return;
        }
        error_code = errno;
        if (pi_ssh_platform_error_is_interrupted(error_code)) {
            continue;
        }
        return;
    }
}
