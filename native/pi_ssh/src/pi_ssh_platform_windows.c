#include "pi_ssh_platform.h"

#include <libssh/libssh.h>

#include <errno.h>
#include <limits.h>
#include <process.h>
#include <stdlib.h>
#include <string.h>

typedef struct pi_ssh_thread_context {
    pi_ssh_thread_function function;
    void *userdata;
} pi_ssh_thread_context;

static INIT_ONCE pi_ssh_initialize_once = INIT_ONCE_STATIC_INIT;
static int pi_ssh_initialize_result = WSASYSNOTREADY;

static BOOL CALLBACK pi_ssh_initialize_once_callback(
    PINIT_ONCE init_once,
    PVOID parameter,
    PVOID *context)
{
    WSADATA winsock_data;
    int result;

    (void)init_once;
    (void)parameter;
    (void)context;

    result = WSAStartup(MAKEWORD(2, 2), &winsock_data);
    if (result != 0) {
        pi_ssh_initialize_result = result;
        return TRUE;
    }
    if (LOBYTE(winsock_data.wVersion) != 2 ||
        HIBYTE(winsock_data.wVersion) != 2) {
        (void)WSACleanup();
        pi_ssh_initialize_result = WSAVERNOTSUPPORTED;
        return TRUE;
    }
    if (ssh_init() != SSH_OK) {
        (void)WSACleanup();
        pi_ssh_initialize_result = WSASYSCALLFAILURE;
        return TRUE;
    }
    pi_ssh_initialize_result = 0;
    return TRUE;
}

int pi_ssh_platform_initialize(void)
{
    if (!InitOnceExecuteOnce(&pi_ssh_initialize_once,
                             pi_ssh_initialize_once_callback,
                             NULL,
                             NULL)) {
        return (int)GetLastError();
    }
    return pi_ssh_initialize_result;
}

int pi_ssh_platform_last_error(void)
{
    return WSAGetLastError();
}

bool pi_ssh_platform_error_is_interrupted(int error_code)
{
    return error_code == WSAEINTR;
}

bool pi_ssh_platform_error_is_would_block(int error_code)
{
    return error_code == WSAEWOULDBLOCK;
}

bool pi_ssh_platform_error_is_not_connected(int error_code)
{
    return error_code == WSAENOTCONN || error_code == WSAESHUTDOWN;
}

char *pi_ssh_platform_duplicate_string(const char *value)
{
    return _strdup(value);
}

uint64_t pi_ssh_platform_monotonic_seconds(void)
{
    return (uint64_t)(GetTickCount64() / 1000u);
}

uint64_t pi_ssh_platform_monotonic_millis(void)
{
    return (uint64_t)GetTickCount64();
}

int pi_ssh_mutex_initialize(pi_ssh_mutex *mutex)
{
    InitializeSRWLock(mutex);
    return 0;
}

void pi_ssh_mutex_lock(pi_ssh_mutex *mutex)
{
    AcquireSRWLockExclusive(mutex);
}

void pi_ssh_mutex_unlock(pi_ssh_mutex *mutex)
{
    ReleaseSRWLockExclusive(mutex);
}

void pi_ssh_mutex_destroy(pi_ssh_mutex *mutex)
{
    (void)mutex;
}

static unsigned __stdcall pi_ssh_thread_trampoline(void *userdata)
{
    pi_ssh_thread_context *context = (pi_ssh_thread_context *)userdata;
    pi_ssh_thread_function function = context->function;
    void *function_userdata = context->userdata;

    free(context);
    function(function_userdata);
    return 0;
}

int pi_ssh_thread_start(pi_ssh_thread *thread,
                        pi_ssh_thread_function function,
                        void *userdata)
{
    pi_ssh_thread_context *context =
        (pi_ssh_thread_context *)malloc(sizeof(*context));
    uintptr_t handle;

    if (context == NULL) {
        return ENOMEM;
    }
    context->function = function;
    context->userdata = userdata;
    handle = _beginthreadex(NULL,
                            0,
                            pi_ssh_thread_trampoline,
                            context,
                            0,
                            NULL);
    if (handle == 0) {
        int error_code = errno != 0 ? errno : (int)GetLastError();
        free(context);
        return error_code;
    }
    *thread = (HANDLE)handle;
    return 0;
}

int pi_ssh_thread_join(pi_ssh_thread thread)
{
    DWORD wait_result = WaitForSingleObject(thread, INFINITE);
    int result = wait_result == WAIT_OBJECT_0 ? 0 : (int)GetLastError();

    (void)CloseHandle(thread);
    return result;
}

bool pi_ssh_socket_is_valid(pi_ssh_socket socket_value)
{
    return socket_value != INVALID_SOCKET;
}

static pi_ssh_socket pi_ssh_socket_create(int type, int protocol)
{
    return WSASocketW(AF_INET,
                      type,
                      protocol,
                      NULL,
                      0,
                      WSA_FLAG_NO_HANDLE_INHERIT);
}

int pi_ssh_socket_configure(pi_ssh_socket socket_value)
{
    u_long nonblocking = 1;

    if (ioctlsocket(socket_value, FIONBIO, &nonblocking) != 0) {
        return -1;
    }
    if (!SetHandleInformation((HANDLE)socket_value,
                              HANDLE_FLAG_INHERIT,
                              0)) {
        WSASetLastError((int)GetLastError());
        return -1;
    }
    return 0;
}

pi_ssh_socket pi_ssh_socket_create_listener(uint16_t *local_port)
{
    pi_ssh_socket socket_value = PI_SSH_INVALID_SOCKET;
    BOOL exclusive = TRUE;
    struct sockaddr_in address;
    int address_length = (int)sizeof(address);

    socket_value = pi_ssh_socket_create(SOCK_STREAM, IPPROTO_TCP);
    if (!pi_ssh_socket_is_valid(socket_value)) {
        return PI_SSH_INVALID_SOCKET;
    }
    if (setsockopt(socket_value,
                   SOL_SOCKET,
                   SO_EXCLUSIVEADDRUSE,
                   (const char *)&exclusive,
                   (int)sizeof(exclusive)) != 0 ||
        pi_ssh_socket_configure(socket_value) != 0) {
        pi_ssh_socket_close(socket_value);
        return PI_SSH_INVALID_SOCKET;
    }
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = 0;
    if (bind(socket_value,
             (const struct sockaddr *)&address,
             (int)sizeof(address)) != 0 ||
        listen(socket_value, 16) != 0 ||
        getsockname(socket_value,
                    (struct sockaddr *)&address,
                    &address_length) != 0) {
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
    int requested = length > INT_MAX ? INT_MAX : (int)length;
    return recv(socket_value, (char *)buffer, requested, 0);
}

int pi_ssh_socket_send(pi_ssh_socket socket_value,
                       const void *buffer,
                       size_t length)
{
    int requested = length > INT_MAX ? INT_MAX : (int)length;
    return send(socket_value, (const char *)buffer, requested, 0);
}

int pi_ssh_socket_shutdown_write(pi_ssh_socket socket_value)
{
    return shutdown(socket_value, SD_SEND);
}

void pi_ssh_socket_close(pi_ssh_socket socket_value)
{
    if (pi_ssh_socket_is_valid(socket_value)) {
        (void)closesocket(socket_value);
    }
}

static int pi_ssh_wake_socket_bind(pi_ssh_socket socket_value,
                                   struct sockaddr_in *address)
{
    int address_length = (int)sizeof(*address);

    memset(address, 0, sizeof(*address));
    address->sin_family = AF_INET;
    address->sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address->sin_port = 0;
    if (bind(socket_value,
             (const struct sockaddr *)address,
             (int)sizeof(*address)) != 0 ||
        getsockname(socket_value,
                    (struct sockaddr *)address,
                    &address_length) != 0) {
        return -1;
    }
    return 0;
}

int pi_ssh_wake_pair_create(pi_ssh_socket *read_socket,
                            pi_ssh_socket *write_socket)
{
    pi_ssh_socket reader = PI_SSH_INVALID_SOCKET;
    pi_ssh_socket writer = PI_SSH_INVALID_SOCKET;
    struct sockaddr_in reader_address;
    struct sockaddr_in writer_address;

    reader = pi_ssh_socket_create(SOCK_DGRAM, IPPROTO_UDP);
    writer = pi_ssh_socket_create(SOCK_DGRAM, IPPROTO_UDP);
    if (!pi_ssh_socket_is_valid(reader) ||
        !pi_ssh_socket_is_valid(writer) ||
        pi_ssh_wake_socket_bind(reader, &reader_address) != 0 ||
        pi_ssh_wake_socket_bind(writer, &writer_address) != 0 ||
        connect(reader,
                (const struct sockaddr *)&writer_address,
                (int)sizeof(writer_address)) != 0 ||
        connect(writer,
                (const struct sockaddr *)&reader_address,
                (int)sizeof(reader_address)) != 0 ||
        pi_ssh_socket_configure(reader) != 0 ||
        pi_ssh_socket_configure(writer) != 0) {
        pi_ssh_socket_close(reader);
        pi_ssh_socket_close(writer);
        return -1;
    }
    *read_socket = reader;
    *write_socket = writer;
    return 0;
}

void pi_ssh_wake_pair_drain(pi_ssh_socket read_socket)
{
    unsigned char buffer[64];

    for (;;) {
        int count = recv(read_socket,
                         (char *)buffer,
                         (int)sizeof(buffer),
                         0);
        int error_code;

        if (count > 0) {
            continue;
        }
        if (count == 0) {
            return;
        }
        error_code = WSAGetLastError();
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
        int result = send(write_socket,
                          (const char *)&wake,
                          (int)sizeof(wake),
                          0);
        int error_code;

        if (result >= 0) {
            return;
        }
        error_code = WSAGetLastError();
        if (pi_ssh_platform_error_is_interrupted(error_code)) {
            continue;
        }
        return;
    }
}
