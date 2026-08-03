#include "pi_ssh.h"

#include <arpa/inet.h>
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

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
    int fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    struct sockaddr_in address;
    const char request[] =
        "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
    char response[4096];
    size_t received = 0;

    assert(fd >= 0);
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons(port);
    assert(connect(fd, (const struct sockaddr *)&address, sizeof(address)) == 0);
    assert(send(fd, request, sizeof(request) - 1, 0) ==
           (ssize_t)(sizeof(request) - 1));

    while (received + 1 < sizeof(response)) {
        ssize_t count = recv(fd,
                             response + received,
                             sizeof(response) - received - 1,
                             0);
        if (count > 0) {
            received += (size_t)count;
            continue;
        }
        if (count < 0 && errno == EINTR) {
            continue;
        }
        break;
    }
    response[received] = '\0';
    assert(strstr(response, "HTTP/1.") != NULL);
    assert(close(fd) == 0);
}

static void *verify_http_forward_thread(void *userdata)
{
    uint16_t port = *(const uint16_t *)userdata;
    verify_http_forward(port);
    return NULL;
}

int main(int argc, char **argv)
{
    pi_ssh_tunnel_config config;
    pi_ssh_error error;
    pi_ssh_tunnel *tunnel;
    char *private_key;
    char fingerprint[sizeof(error.host_key_sha256)];
    uint16_t local_port;
    pthread_t first_request;
    pthread_t second_request;

    if (argc != 7) {
        fprintf(stderr,
                "usage: %s ssh-host ssh-port username private-key remote-host remote-port\n",
                argv[0]);
        return 2;
    }
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
    assert(pthread_create(&first_request,
                          NULL,
                          verify_http_forward_thread,
                          &local_port) == 0);
    assert(pthread_create(&second_request,
                          NULL,
                          verify_http_forward_thread,
                          &local_port) == 0);
    assert(pthread_join(first_request, NULL) == 0);
    assert(pthread_join(second_request, NULL) == 0);

    pi_ssh_tunnel_free(tunnel);
    free(private_key);
    printf("pi_ssh tunnel integration test passed\n");
    return 0;
}
