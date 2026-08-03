#include "pi_ssh.h"

#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <windows.h>
#include <process.h>
#else
#include <arpa/inet.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>
#endif

#if defined(_WIN32)
typedef SOCKET test_socket;
typedef HANDLE test_thread;
typedef unsigned(__stdcall *test_thread_function)(void *);

#define TEST_INVALID_SOCKET INVALID_SOCKET

static int test_socket_initialize(void)
{
    WSADATA data;
    return WSAStartup(MAKEWORD(2, 2), &data);
}

static int test_socket_last_error(void)
{
    return WSAGetLastError();
}

static int test_socket_error_is_interrupted(int error_code)
{
    return error_code == WSAEINTR;
}

static int test_socket_send(test_socket socket_value,
                            const void *buffer,
                            size_t length)
{
    return send(socket_value,
                (const char *)buffer,
                (int)length,
                0);
}

static int test_socket_receive(test_socket socket_value,
                               void *buffer,
                               size_t length)
{
    return recv(socket_value, (char *)buffer, (int)length, 0);
}

static int test_socket_close(test_socket socket_value)
{
    return closesocket(socket_value);
}

static int test_thread_start(test_thread *thread,
                             test_thread_function function,
                             void *userdata)
{
    uintptr_t handle = _beginthreadex(NULL, 0, function, userdata, 0, NULL);
    if (handle == 0) {
        return -1;
    }
    *thread = (HANDLE)handle;
    return 0;
}

static int test_thread_join(test_thread thread)
{
    DWORD result = WaitForSingleObject(thread, INFINITE);
    (void)CloseHandle(thread);
    return result == WAIT_OBJECT_0 ? 0 : -1;
}
#else
typedef int test_socket;
typedef pthread_t test_thread;
typedef void *(*test_thread_function)(void *);

#define TEST_INVALID_SOCKET (-1)

static int test_socket_initialize(void)
{
    return 0;
}

static int test_socket_last_error(void)
{
    return errno;
}

static int test_socket_error_is_interrupted(int error_code)
{
    return error_code == EINTR;
}

static int test_socket_send(test_socket socket_value,
                            const void *buffer,
                            size_t length)
{
    return (int)send(socket_value, buffer, length, 0);
}

static int test_socket_receive(test_socket socket_value,
                               void *buffer,
                               size_t length)
{
    return (int)recv(socket_value, buffer, length, 0);
}

static int test_socket_close(test_socket socket_value)
{
    return close(socket_value);
}

static int test_thread_start(test_thread *thread,
                             test_thread_function function,
                             void *userdata)
{
    return pthread_create(thread, NULL, function, userdata);
}

static int test_thread_join(test_thread thread)
{
    return pthread_join(thread, NULL);
}
#endif

static char *read_file(const char *path)
{
    FILE *file = fopen(path, "rb");
    long size;
    char *contents;

    assert(file != NULL);
    assert(fseek(file, 0, SEEK_END) == 0);
    size = ftell(file);
    assert(size > 0);
    assert(fseek(file, 0, SEEK_SET) == 0);
    contents = (char *)malloc((size_t)size + 1);
    assert(contents != NULL);
    assert(fread(contents, 1, (size_t)size, file) == (size_t)size);
    contents[size] = '\0';
    assert(fclose(file) == 0);
    return contents;
}

static void verify_http_forward(uint16_t port)
{
    test_socket socket_value = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    struct sockaddr_in address;
    const char request[] =
        "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
    char response[4096];
    size_t received = 0;

    assert(socket_value != TEST_INVALID_SOCKET);
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons(port);
    assert(connect(socket_value,
                   (const struct sockaddr *)&address,
                   (int)sizeof(address)) == 0);
    assert(test_socket_send(socket_value, request, sizeof(request) - 1) ==
           (int)(sizeof(request) - 1));

    while (received + 1 < sizeof(response)) {
        int count = test_socket_receive(socket_value,
                                        response + received,
                                        sizeof(response) - received - 1);
        if (count > 0) {
            received += (size_t)count;
            continue;
        }
        if (count < 0 &&
            test_socket_error_is_interrupted(test_socket_last_error())) {
            continue;
        }
        break;
    }
    response[received] = '\0';
    assert(strstr(response, "HTTP/1.") != NULL);
    assert(test_socket_close(socket_value) == 0);
}

#if defined(_WIN32)
static unsigned __stdcall verify_http_forward_thread(void *userdata)
{
    uint16_t port = *(const uint16_t *)userdata;
    verify_http_forward(port);
    return 0;
}
#else
static void *verify_http_forward_thread(void *userdata)
{
    uint16_t port = *(const uint16_t *)userdata;
    verify_http_forward(port);
    return NULL;
}
#endif

int main(int argc, char **argv)
{
    pi_ssh_tunnel_config config;
    pi_ssh_error error;
    pi_ssh_tunnel *tunnel;
    char *private_key;
    char fingerprint[sizeof(error.host_key_sha256)];
    uint16_t local_port;
    test_thread first_request;
    test_thread second_request;

    if (argc != 7) {
        fprintf(stderr,
                "usage: %s ssh-host ssh-port username private-key remote-host remote-port\n",
                argv[0]);
        return 2;
    }
    assert(test_socket_initialize() == 0);
    private_key = read_file(argv[4]);
    pi_ssh_tunnel_config_init(&config);
    config.ssh_host = argv[1];
    config.ssh_port = (uint16_t)strtoul(argv[2], NULL, 10);
    config.username = argv[3];
    config.auth_type = PI_SSH_AUTH_PRIVATE_KEY;
    config.private_key = private_key;
    config.remote_host = argv[5];
    config.remote_port = (uint16_t)strtoul(argv[6], NULL, 10);
    config.keepalive_interval_seconds = 1;

    tunnel = pi_ssh_tunnel_start(&config, &error);
    assert(tunnel == NULL);
    assert(error.code == PI_SSH_ERROR_HOST_KEY_UNKNOWN);
    assert(strncmp(error.host_key_sha256, "SHA256:", 7) == 0);
    (void)snprintf(fingerprint, sizeof(fingerprint), "%s", error.host_key_sha256);

    config.expected_host_key_sha256 = "SHA256:intentionally-wrong";
    tunnel = pi_ssh_tunnel_start(&config, &error);
    assert(tunnel == NULL);
    assert(error.code == PI_SSH_ERROR_HOST_KEY_MISMATCH);
    assert(strcmp(error.host_key_sha256, fingerprint) == 0);

    config.expected_host_key_sha256 = fingerprint;
    tunnel = pi_ssh_tunnel_start(&config, &error);
    if (tunnel == NULL) {
        fprintf(stderr, "tunnel start failed: %s\n", error.message);
        return 1;
    }
    assert(pi_ssh_tunnel_state(tunnel) == PI_SSH_STATE_RUNNING);
    assert(pi_ssh_tunnel_local_port(tunnel) != 0);

    /* Concurrent TCP streams exercise SSH channel multiplexing. */
    local_port = pi_ssh_tunnel_local_port(tunnel);
    assert(test_thread_start(&first_request,
                             verify_http_forward_thread,
                             &local_port) == 0);
    assert(test_thread_start(&second_request,
                             verify_http_forward_thread,
                             &local_port) == 0);
    assert(test_thread_join(first_request) == 0);
    assert(test_thread_join(second_request) == 0);

    pi_ssh_tunnel_free(tunnel);
    free(private_key);
    printf("pi_ssh tunnel integration test passed\n");
    return 0;
}
