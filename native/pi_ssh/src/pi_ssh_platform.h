#ifndef PI_SSH_PLATFORM_H
#define PI_SSH_PLATFORM_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <windows.h>

typedef SOCKET pi_ssh_socket;
typedef SRWLOCK pi_ssh_mutex;
typedef HANDLE pi_ssh_thread;

#define PI_SSH_INVALID_SOCKET INVALID_SOCKET
#else
#include <pthread.h>

typedef int pi_ssh_socket;
typedef pthread_mutex_t pi_ssh_mutex;
typedef pthread_t pi_ssh_thread;

#define PI_SSH_INVALID_SOCKET (-1)
#endif

typedef void (*pi_ssh_thread_function)(void *userdata);

/* Initializes the process-wide socket and libssh runtime exactly once. */
int pi_ssh_platform_initialize(void);

int pi_ssh_platform_last_error(void);
bool pi_ssh_platform_error_is_interrupted(int error_code);
bool pi_ssh_platform_error_is_would_block(int error_code);
bool pi_ssh_platform_error_is_not_connected(int error_code);

char *pi_ssh_platform_duplicate_string(const char *value);
uint64_t pi_ssh_platform_monotonic_seconds(void);
uint64_t pi_ssh_platform_monotonic_millis(void);

int pi_ssh_mutex_initialize(pi_ssh_mutex *mutex);
void pi_ssh_mutex_lock(pi_ssh_mutex *mutex);
void pi_ssh_mutex_unlock(pi_ssh_mutex *mutex);
void pi_ssh_mutex_destroy(pi_ssh_mutex *mutex);

int pi_ssh_thread_start(pi_ssh_thread *thread,
                        pi_ssh_thread_function function,
                        void *userdata);
int pi_ssh_thread_join(pi_ssh_thread thread);

bool pi_ssh_socket_is_valid(pi_ssh_socket socket_value);
pi_ssh_socket pi_ssh_socket_create_listener(uint16_t *local_port);
pi_ssh_socket pi_ssh_socket_accept(pi_ssh_socket listener);
int pi_ssh_socket_configure(pi_ssh_socket socket_value);
int pi_ssh_socket_receive(pi_ssh_socket socket_value,
                          void *buffer,
                          size_t length);
int pi_ssh_socket_send(pi_ssh_socket socket_value,
                       const void *buffer,
                       size_t length);
int pi_ssh_socket_shutdown_write(pi_ssh_socket socket_value);
void pi_ssh_socket_close(pi_ssh_socket socket_value);

/* Creates a pollable wake pair used to interrupt the SSH worker event loop. */
int pi_ssh_wake_pair_create(pi_ssh_socket *read_socket,
                            pi_ssh_socket *write_socket);
void pi_ssh_wake_pair_drain(pi_ssh_socket read_socket);
void pi_ssh_wake_pair_signal(pi_ssh_socket write_socket);

#endif
