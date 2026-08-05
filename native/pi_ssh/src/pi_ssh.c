#include "pi_ssh.h"
#include "pi_ssh_platform.h"

#include <libssh/libssh.h>
#include <libssh/poll.h>

#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define PI_SSH_BUFFER_CAPACITY (64u * 1024u)
#define PI_SSH_MAX_CONNECTIONS 32u
#define PI_SSH_EVENT_TIMEOUT_MS 250

typedef struct pi_ssh_ring_buffer {
    unsigned char bytes[PI_SSH_BUFFER_CAPACITY];
    size_t head;
    size_t length;
} pi_ssh_ring_buffer;

typedef enum pi_ssh_connection_state {
    PI_SSH_CONNECTION_OPENING = 1,
    PI_SSH_CONNECTION_OPEN = 2,
} pi_ssh_connection_state;

typedef struct pi_ssh_connection {
    pi_ssh_socket socket_value;
    bool registered;
    short registered_events;
    short revents;
    ssh_channel channel;
    pi_ssh_connection_state state;
    bool local_read_eof;
    bool channel_eof_sent;
    bool channel_read_eof;
    bool local_write_shutdown;
    pi_ssh_ring_buffer to_ssh;
    pi_ssh_ring_buffer to_local;
    struct pi_ssh_connection *next;
} pi_ssh_connection;

struct pi_ssh_tunnel {
    pi_ssh_mutex mutex;
    pi_ssh_thread worker;
    bool worker_started;
    int32_t state;
    pi_ssh_error last_error;

    ssh_session session;
    pi_ssh_socket listener_socket;
    pi_ssh_socket wake_read_socket;
    pi_ssh_socket wake_write_socket;
    uint16_t local_port;
    char *remote_host;
    uint16_t remote_port;
    uint32_t keepalive_interval_seconds;

    short listener_revents;
    short wake_revents;
};

static void pi_ssh_secure_zero(void *value, size_t size)
{
    volatile unsigned char *bytes = (volatile unsigned char *)value;
    while (size > 0) {
        *bytes++ = 0;
        --size;
    }
}

static void pi_ssh_copy_string(char *destination,
                               size_t destination_size,
                               const char *source)
{
    if (destination == NULL || destination_size == 0) {
        return;
    }
    if (source == NULL) {
        destination[0] = '\0';
        return;
    }
    (void)snprintf(destination, destination_size, "%s", source);
}

void pi_ssh_error_init(pi_ssh_error *error)
{
    if (error == NULL) {
        return;
    }
    memset(error, 0, sizeof(*error));
    error->struct_size = (uint32_t)sizeof(*error);
}

void pi_ssh_tunnel_config_init(pi_ssh_tunnel_config *config)
{
    if (config == NULL) {
        return;
    }
    memset(config, 0, sizeof(*config));
    config->struct_size = (uint32_t)sizeof(*config);
    config->abi_version = PI_SSH_ABI_VERSION;
    config->ssh_port = PI_SSH_DEFAULT_PORT;
    config->connect_timeout_ms = PI_SSH_DEFAULT_CONNECT_TIMEOUT_MS;
    config->keepalive_interval_seconds =
        PI_SSH_DEFAULT_KEEPALIVE_INTERVAL_SECONDS;
}

static void pi_ssh_set_error_value(pi_ssh_error *error,
                                   pi_ssh_error_code code,
                                   int system_error,
                                   const char *fingerprint,
                                   const char *format,
                                   ...)
{
    va_list arguments;

    if (error == NULL) {
        return;
    }
    pi_ssh_error_init(error);
    error->code = (int32_t)code;
    error->system_error = system_error;
    pi_ssh_copy_string(error->host_key_sha256,
                       sizeof(error->host_key_sha256),
                       fingerprint);
    if (format == NULL) {
        return;
    }
    va_start(arguments, format);
    (void)vsnprintf(error->message, sizeof(error->message), format, arguments);
    va_end(arguments);
}

static void pi_ssh_set_tunnel_error(pi_ssh_tunnel *tunnel,
                                    pi_ssh_error_code code,
                                    int system_error,
                                    const char *format,
                                    ...)
{
    va_list arguments;
    pi_ssh_error next;

    pi_ssh_error_init(&next);
    next.code = (int32_t)code;
    next.system_error = system_error;
    if (format != NULL) {
        va_start(arguments, format);
        (void)vsnprintf(next.message, sizeof(next.message), format, arguments);
        va_end(arguments);
    }

    pi_ssh_mutex_lock(&tunnel->mutex);
    tunnel->last_error = next;
    pi_ssh_mutex_unlock(&tunnel->mutex);
}

static size_t pi_ssh_ring_free(const pi_ssh_ring_buffer *buffer)
{
    return PI_SSH_BUFFER_CAPACITY - buffer->length;
}

static size_t pi_ssh_ring_read_contiguous(const pi_ssh_ring_buffer *buffer)
{
    size_t until_end = PI_SSH_BUFFER_CAPACITY - buffer->head;
    return buffer->length < until_end ? buffer->length : until_end;
}

static size_t pi_ssh_ring_write_offset(const pi_ssh_ring_buffer *buffer)
{
    return (buffer->head + buffer->length) % PI_SSH_BUFFER_CAPACITY;
}

static size_t pi_ssh_ring_write_contiguous(const pi_ssh_ring_buffer *buffer)
{
    size_t free_space = pi_ssh_ring_free(buffer);
    size_t offset = pi_ssh_ring_write_offset(buffer);
    size_t until_end = PI_SSH_BUFFER_CAPACITY - offset;
    return free_space < until_end ? free_space : until_end;
}

static void pi_ssh_ring_did_read(pi_ssh_ring_buffer *buffer, size_t count)
{
    buffer->head = (buffer->head + count) % PI_SSH_BUFFER_CAPACITY;
    buffer->length -= count;
    if (buffer->length == 0) {
        buffer->head = 0;
    }
}

static void pi_ssh_ring_did_write(pi_ssh_ring_buffer *buffer, size_t count)
{
    buffer->length += count;
}

static int pi_ssh_event_fd_callback(socket_t fd, int revents, void *userdata)
{
    short *target = (short *)userdata;
    (void)fd;
    *target = (short)(*target | (short)revents);
    return 0;
}

static int pi_ssh_connection_fd_callback(socket_t fd,
                                         int revents,
                                         void *userdata)
{
    pi_ssh_connection *connection = (pi_ssh_connection *)userdata;
    (void)fd;
    connection->revents =
        (short)(connection->revents | (short)revents);
    return 0;
}

static void pi_ssh_connection_unregister(ssh_event event,
                                         pi_ssh_connection *connection)
{
    if (connection->registered) {
        (void)ssh_event_remove_fd(event, connection->socket_value);
        connection->registered = false;
        connection->registered_events = 0;
    }
}

static int pi_ssh_connection_update_events(ssh_event event,
                                           pi_ssh_connection *connection)
{
    short wanted = 0;
    int result;

    if (!connection->local_read_eof &&
        pi_ssh_ring_free(&connection->to_ssh) > 0) {
        wanted = (short)(wanted | POLLIN);
    }
    if (connection->to_local.length > 0) {
        wanted = (short)(wanted | POLLOUT);
    }
    if (connection->registered && connection->registered_events == wanted) {
        return SSH_OK;
    }

    pi_ssh_connection_unregister(event, connection);
    if (wanted == 0) {
        return SSH_OK;
    }
    result = ssh_event_add_fd(event,
                              connection->socket_value,
                              wanted,
                              pi_ssh_connection_fd_callback,
                              connection);
    if (result == SSH_OK) {
        connection->registered = true;
        connection->registered_events = wanted;
    }
    return result;
}

static void pi_ssh_connection_destroy(ssh_event event,
                                      pi_ssh_connection *connection)
{
    if (connection == NULL) {
        return;
    }
    pi_ssh_connection_unregister(event, connection);
    if (pi_ssh_socket_is_valid(connection->socket_value)) {
        pi_ssh_socket_close(connection->socket_value);
    }
    if (connection->channel != NULL) {
        ssh_channel_free(connection->channel);
    }
    pi_ssh_secure_zero(connection, sizeof(*connection));
    free(connection);
}

static void pi_ssh_destroy_connections(ssh_event event,
                                       pi_ssh_connection *connections)
{
    while (connections != NULL) {
        pi_ssh_connection *next = connections->next;
        pi_ssh_connection_destroy(event, connections);
        connections = next;
    }
}

static int pi_ssh_accept_connections(pi_ssh_tunnel *tunnel,
                                     ssh_event event,
                                     pi_ssh_connection **connections,
                                     size_t *connection_count)
{
    for (;;) {
        pi_ssh_socket socket_value =
            pi_ssh_socket_accept(tunnel->listener_socket);
        pi_ssh_connection *connection;

        if (!pi_ssh_socket_is_valid(socket_value)) {
            int error_code = pi_ssh_platform_last_error();
            if (pi_ssh_platform_error_is_would_block(error_code)) {
                return 0;
            }
            if (pi_ssh_platform_error_is_interrupted(error_code)) {
                continue;
            }
            return -1;
        }
        if (*connection_count >= PI_SSH_MAX_CONNECTIONS ||
            pi_ssh_socket_configure(socket_value) < 0) {
            pi_ssh_socket_close(socket_value);
            continue;
        }

        connection = (pi_ssh_connection *)calloc(1, sizeof(*connection));
        if (connection == NULL) {
            pi_ssh_socket_close(socket_value);
            return -1;
        }
        connection->socket_value = socket_value;
        connection->state = PI_SSH_CONNECTION_OPENING;
        connection->channel = ssh_channel_new(tunnel->session);
        if (connection->channel == NULL) {
            pi_ssh_connection_destroy(event, connection);
            return -1;
        }
        ssh_channel_set_blocking(connection->channel, 0);
        connection->next = *connections;
        *connections = connection;
        ++*connection_count;

        if (pi_ssh_connection_update_events(event, connection) != SSH_OK) {
            return -1;
        }
    }
}

static bool pi_ssh_connection_open_forward(pi_ssh_tunnel *tunnel,
                                           pi_ssh_connection *connection)
{
    int result = ssh_channel_open_forward(connection->channel,
                                          tunnel->remote_host,
                                          (int)tunnel->remote_port,
                                          "127.0.0.1",
                                          0);
    if (result == SSH_OK) {
        connection->state = PI_SSH_CONNECTION_OPEN;
        return true;
    }
    if (result == SSH_AGAIN) {
        return true;
    }
    pi_ssh_set_tunnel_error(tunnel,
                            PI_SSH_ERROR_REMOTE_FORWARD,
                            0,
                            "SSH server could not connect to %s:%u: %s",
                            tunnel->remote_host,
                            (unsigned int)tunnel->remote_port,
                            ssh_get_error(tunnel->session));
    return false;
}

static bool pi_ssh_connection_read_local(pi_ssh_connection *connection)
{
    if ((connection->revents & (POLLERR | POLLNVAL)) != 0) {
        return false;
    }
    if ((connection->revents & (POLLIN | POLLHUP)) == 0 ||
        connection->local_read_eof) {
        return true;
    }

    while (pi_ssh_ring_free(&connection->to_ssh) > 0) {
        size_t count = pi_ssh_ring_write_contiguous(&connection->to_ssh);
        size_t offset = pi_ssh_ring_write_offset(&connection->to_ssh);
        int read_count = pi_ssh_socket_receive(
            connection->socket_value,
            connection->to_ssh.bytes + offset,
            count);
        if (read_count > 0) {
            pi_ssh_ring_did_write(&connection->to_ssh, (size_t)read_count);
            continue;
        }
        if (read_count == 0) {
            connection->local_read_eof = true;
            break;
        }
        {
            int error_code = pi_ssh_platform_last_error();
            if (pi_ssh_platform_error_is_interrupted(error_code)) {
                continue;
            }
            if (pi_ssh_platform_error_is_would_block(error_code)) {
                break;
            }
        }
        return false;
    }
    return true;
}

static bool pi_ssh_connection_write_channel(pi_ssh_connection *connection)
{
    while (connection->to_ssh.length > 0) {
        size_t count = pi_ssh_ring_read_contiguous(&connection->to_ssh);
        uint32_t window = ssh_channel_window_size(connection->channel);
        int written;

        if (window == 0) {
            break;
        }
        if (count > window) {
            count = window;
        }
        written = ssh_channel_write(connection->channel,
                                    connection->to_ssh.bytes +
                                        connection->to_ssh.head,
                                    (uint32_t)count);
        if (written == SSH_ERROR) {
            return false;
        }
        if (written <= 0) {
            break;
        }
        pi_ssh_ring_did_read(&connection->to_ssh, (size_t)written);
    }

    if (connection->local_read_eof && connection->to_ssh.length == 0 &&
        !connection->channel_eof_sent) {
        int result = ssh_channel_send_eof(connection->channel);
        if (result == SSH_OK || result == SSH_EOF) {
            connection->channel_eof_sent = true;
        } else if (result != SSH_AGAIN) {
            return false;
        }
    }
    return true;
}

static bool pi_ssh_connection_read_channel(pi_ssh_connection *connection)
{
    while (pi_ssh_ring_free(&connection->to_local) > 0) {
        size_t count = pi_ssh_ring_write_contiguous(&connection->to_local);
        size_t offset = pi_ssh_ring_write_offset(&connection->to_local);
        int read_count = ssh_channel_read_nonblocking(
            connection->channel,
            connection->to_local.bytes + offset,
            (uint32_t)count,
            0);
        if (read_count > 0) {
            pi_ssh_ring_did_write(&connection->to_local, (size_t)read_count);
            continue;
        }
        if (read_count == SSH_ERROR) {
            return false;
        }
        break;
    }
    if (ssh_channel_is_eof(connection->channel)) {
        connection->channel_read_eof = true;
    }
    return true;
}

static bool pi_ssh_connection_write_local(pi_ssh_connection *connection)
{
    if ((connection->revents & (POLLERR | POLLNVAL)) != 0) {
        return false;
    }
    if ((connection->revents & POLLOUT) != 0) {
        while (connection->to_local.length > 0) {
            size_t count = pi_ssh_ring_read_contiguous(&connection->to_local);
            int written = pi_ssh_socket_send(
                connection->socket_value,
                connection->to_local.bytes + connection->to_local.head,
                count);
            if (written > 0) {
                pi_ssh_ring_did_read(&connection->to_local, (size_t)written);
                continue;
            }
            if (written < 0) {
                int error_code = pi_ssh_platform_last_error();
                if (pi_ssh_platform_error_is_interrupted(error_code)) {
                    continue;
                }
                if (pi_ssh_platform_error_is_would_block(error_code)) {
                    break;
                }
            }
            return false;
        }
    }

    if (connection->channel_read_eof && connection->to_local.length == 0 &&
        !connection->local_write_shutdown) {
        if (pi_ssh_socket_shutdown_write(connection->socket_value) < 0) {
            int error_code = pi_ssh_platform_last_error();
            if (!pi_ssh_platform_error_is_not_connected(error_code)) {
                return false;
            }
        }
        connection->local_write_shutdown = true;
    }
    return true;
}

static bool pi_ssh_connection_should_close(
    const pi_ssh_connection *connection)
{
    if (connection->state == PI_SSH_CONNECTION_OPEN &&
        ssh_channel_is_closed(connection->channel)) {
        return true;
    }
    return connection->local_read_eof && connection->channel_read_eof &&
           connection->to_ssh.length == 0 && connection->to_local.length == 0;
}

static bool pi_ssh_process_connection(pi_ssh_tunnel *tunnel,
                                      ssh_event event,
                                      pi_ssh_connection *connection)
{
    bool healthy = true;

    if (connection->state == PI_SSH_CONNECTION_OPENING) {
        healthy = pi_ssh_connection_open_forward(tunnel, connection);
    }
    if (healthy) {
        healthy = pi_ssh_connection_read_local(connection);
    }
    if (healthy && connection->state == PI_SSH_CONNECTION_OPEN) {
        healthy = pi_ssh_connection_write_channel(connection) &&
                  pi_ssh_connection_read_channel(connection) &&
                  pi_ssh_connection_write_local(connection);
    }
    connection->revents = 0;
    if (!healthy || pi_ssh_connection_should_close(connection)) {
        return false;
    }
    return pi_ssh_connection_update_events(event, connection) == SSH_OK;
}

static void pi_ssh_remove_connection(ssh_event event,
                                     pi_ssh_connection **cursor,
                                     size_t *connection_count)
{
    pi_ssh_connection *connection = *cursor;
    *cursor = connection->next;
    pi_ssh_connection_destroy(event, connection);
    --*connection_count;
}

static bool pi_ssh_tunnel_stop_requested(pi_ssh_tunnel *tunnel)
{
    bool result;
    pi_ssh_mutex_lock(&tunnel->mutex);
    result = tunnel->state == PI_SSH_STATE_STOPPING;
    pi_ssh_mutex_unlock(&tunnel->mutex);
    return result;
}

static void pi_ssh_worker_main(void *userdata)
{
    pi_ssh_tunnel *tunnel = (pi_ssh_tunnel *)userdata;
    ssh_event event = NULL;
    pi_ssh_connection *connections = NULL;
    size_t connection_count = 0;
    bool failed = false;
    uint64_t last_keepalive = pi_ssh_platform_monotonic_seconds();

    event = ssh_event_new();
    if (event == NULL ||
        ssh_event_add_session(event, tunnel->session) != SSH_OK ||
        ssh_event_add_fd(event,
                         tunnel->listener_socket,
                         POLLIN,
                         pi_ssh_event_fd_callback,
                         &tunnel->listener_revents) != SSH_OK ||
        ssh_event_add_fd(event,
                         tunnel->wake_read_socket,
                         POLLIN,
                         pi_ssh_event_fd_callback,
                         &tunnel->wake_revents) != SSH_OK) {
        pi_ssh_set_tunnel_error(tunnel,
                                PI_SSH_ERROR_INTERNAL,
                                pi_ssh_platform_last_error(),
                                "Could not initialize SSH forwarding event loop");
        failed = true;
        goto cleanup;
    }

    while (!pi_ssh_tunnel_stop_requested(tunnel)) {
        int poll_result = ssh_event_dopoll(event, PI_SSH_EVENT_TIMEOUT_MS);
        pi_ssh_connection **cursor;
        uint64_t now;

        if (poll_result == SSH_ERROR) {
            int error_code = pi_ssh_platform_last_error();
            if (pi_ssh_platform_error_is_interrupted(error_code)) {
                continue;
            }
            pi_ssh_set_tunnel_error(tunnel,
                                    PI_SSH_ERROR_SSH_DISCONNECTED,
                                    error_code,
                                    "SSH event loop failed: %s",
                                    ssh_get_error(tunnel->session));
            failed = true;
            break;
        }
        if (tunnel->wake_revents != 0) {
            pi_ssh_wake_pair_drain(tunnel->wake_read_socket);
            tunnel->wake_revents = 0;
        }
        if (pi_ssh_tunnel_stop_requested(tunnel)) {
            break;
        }
        if ((tunnel->listener_revents & (POLLERR | POLLNVAL)) != 0) {
            pi_ssh_set_tunnel_error(tunnel,
                                    PI_SSH_ERROR_LOCAL_LISTENER,
                                    0,
                                    "Local SSH tunnel listener failed");
            failed = true;
            break;
        }
        if ((tunnel->listener_revents & POLLIN) != 0 &&
            pi_ssh_accept_connections(tunnel,
                                      event,
                                      &connections,
                                      &connection_count) < 0) {
            pi_ssh_set_tunnel_error(tunnel,
                                    PI_SSH_ERROR_INTERNAL,
                                    pi_ssh_platform_last_error(),
                                    "Could not accept a local tunnel connection");
            failed = true;
            break;
        }
        tunnel->listener_revents = 0;

        cursor = &connections;
        while (*cursor != NULL) {
            if (!pi_ssh_process_connection(tunnel, event, *cursor)) {
                pi_ssh_remove_connection(event, cursor, &connection_count);
            } else {
                cursor = &(*cursor)->next;
            }
        }

        if ((ssh_get_status(tunnel->session) &
             (SSH_CLOSED | SSH_CLOSED_ERROR)) != 0 ||
            !ssh_is_connected(tunnel->session)) {
            pi_ssh_set_tunnel_error(tunnel,
                                    PI_SSH_ERROR_SSH_DISCONNECTED,
                                    0,
                                    "SSH connection closed: %s",
                                    ssh_get_error(tunnel->session));
            failed = true;
            break;
        }

        now = pi_ssh_platform_monotonic_seconds();
        if (tunnel->keepalive_interval_seconds > 0 && now > 0 &&
            now - last_keepalive >= tunnel->keepalive_interval_seconds) {
            int keepalive_result = ssh_send_ignore(tunnel->session, "ohpi");
            if (keepalive_result == SSH_ERROR) {
                pi_ssh_set_tunnel_error(tunnel,
                                        PI_SSH_ERROR_SSH_DISCONNECTED,
                                        0,
                                        "SSH keepalive failed: %s",
                                        ssh_get_error(tunnel->session));
                failed = true;
                break;
            }
            if (keepalive_result == SSH_OK) {
                last_keepalive = now;
            }
        }
    }

cleanup:
    pi_ssh_destroy_connections(event, connections);
    if (event != NULL) {
        (void)ssh_event_remove_fd(event, tunnel->listener_socket);
        (void)ssh_event_remove_fd(event, tunnel->wake_read_socket);
        (void)ssh_event_remove_session(event, tunnel->session);
        ssh_event_free(event);
    }

    pi_ssh_mutex_lock(&tunnel->mutex);
    if (failed && tunnel->state != PI_SSH_STATE_STOPPING) {
        tunnel->state = PI_SSH_STATE_FAILED;
    } else {
        tunnel->state = PI_SSH_STATE_STOPPED;
    }
    pi_ssh_mutex_unlock(&tunnel->mutex);
}

static bool pi_ssh_string_present(const char *value)
{
    return value != NULL && value[0] != '\0';
}

static bool pi_ssh_validate_config(const pi_ssh_tunnel_config *config,
                                   pi_ssh_error *error)
{
    if (config == NULL || config->struct_size < sizeof(*config) ||
        config->abi_version != PI_SSH_ABI_VERSION) {
        pi_ssh_set_error_value(error,
                               PI_SSH_ERROR_INVALID_ARGUMENT,
                               0,
                               NULL,
                               "Unsupported or incomplete SSH tunnel config");
        return false;
    }
    if (!pi_ssh_string_present(config->ssh_host) ||
        !pi_ssh_string_present(config->username) ||
        !pi_ssh_string_present(config->remote_host) ||
        config->remote_port == 0) {
        pi_ssh_set_error_value(error,
                               PI_SSH_ERROR_INVALID_ARGUMENT,
                               0,
                               NULL,
                               "SSH host, username, remote host, and remote port are required");
        return false;
    }
    if (config->auth_type == PI_SSH_AUTH_PASSWORD &&
        !pi_ssh_string_present(config->password)) {
        pi_ssh_set_error_value(error,
                               PI_SSH_ERROR_INVALID_ARGUMENT,
                               0,
                               NULL,
                               "SSH password is required");
        return false;
    }
    if (config->auth_type == PI_SSH_AUTH_PRIVATE_KEY &&
        !pi_ssh_string_present(config->private_key)) {
        pi_ssh_set_error_value(error,
                               PI_SSH_ERROR_INVALID_ARGUMENT,
                               0,
                               NULL,
                               "SSH private key is required");
        return false;
    }
    if (config->auth_type != PI_SSH_AUTH_PASSWORD &&
        config->auth_type != PI_SSH_AUTH_PRIVATE_KEY) {
        pi_ssh_set_error_value(error,
                               PI_SSH_ERROR_INVALID_ARGUMENT,
                               0,
                               NULL,
                               "Unsupported SSH authentication type");
        return false;
    }
    return true;
}

static int pi_ssh_configure_session(ssh_session session,
                                    const pi_ssh_tunnel_config *config)
{
    unsigned int port = config->ssh_port == 0 ? PI_SSH_DEFAULT_PORT
                                               : config->ssh_port;
    uint32_t timeout_ms = config->connect_timeout_ms == 0
                              ? PI_SSH_DEFAULT_CONNECT_TIMEOUT_MS
                              : config->connect_timeout_ms;
    long timeout_seconds = (long)(timeout_ms / 1000u);
    long timeout_microseconds = (long)((timeout_ms % 1000u) * 1000u);
    int disabled = 0;
    int enabled = 1;
    int verbosity = SSH_LOG_NONE;

    if (ssh_options_set(session, SSH_OPTIONS_HOST, config->ssh_host) != SSH_OK ||
        ssh_options_set(session, SSH_OPTIONS_PORT, &port) != SSH_OK ||
        ssh_options_set(session, SSH_OPTIONS_USER, config->username) != SSH_OK ||
        ssh_options_set(session, SSH_OPTIONS_TIMEOUT, &timeout_seconds) != SSH_OK ||
        ssh_options_set(session,
                        SSH_OPTIONS_TIMEOUT_USEC,
                        &timeout_microseconds) != SSH_OK ||
        ssh_options_set(session, SSH_OPTIONS_PROCESS_CONFIG, &disabled) != SSH_OK ||
        ssh_options_set(session, SSH_OPTIONS_NODELAY, &enabled) != SSH_OK ||
        ssh_options_set(session, SSH_OPTIONS_LOG_VERBOSITY, &verbosity) != SSH_OK) {
        return SSH_ERROR;
    }
    return SSH_OK;
}

static char *pi_ssh_server_fingerprint(ssh_session session)
{
    ssh_key key = NULL;
    unsigned char *hash = NULL;
    size_t hash_length = 0;
    char *fingerprint = NULL;

    if (ssh_get_server_publickey(session, &key) != SSH_OK || key == NULL) {
        goto cleanup;
    }
    if (ssh_get_publickey_hash(key,
                               SSH_PUBLICKEY_HASH_SHA256,
                               &hash,
                               &hash_length) != SSH_OK) {
        goto cleanup;
    }
    fingerprint = ssh_get_fingerprint_hash(SSH_PUBLICKEY_HASH_SHA256,
                                           hash,
                                           hash_length);

cleanup:
    if (hash != NULL) {
        ssh_clean_pubkey_hash(&hash);
    }
    if (key != NULL) {
        ssh_key_free(key);
    }
    return fingerprint;
}

static int pi_ssh_authenticate(ssh_session session,
                               const pi_ssh_tunnel_config *config,
                               pi_ssh_error *error)
{
    int result;

    if (config->auth_type == PI_SSH_AUTH_PASSWORD) {
        result = ssh_userauth_password(session, NULL, config->password);
        if (result != SSH_AUTH_SUCCESS) {
            pi_ssh_set_error_value(error,
                                   PI_SSH_ERROR_AUTHENTICATION,
                                   0,
                                   NULL,
                                   "SSH password authentication failed: %s",
                                   ssh_get_error(session));
            return SSH_ERROR;
        }
        return SSH_OK;
    }

    {
        ssh_key private_key = NULL;
        const char *passphrase =
            pi_ssh_string_present(config->private_key_passphrase)
                ? config->private_key_passphrase
                : NULL;
        result = ssh_pki_import_privkey_base64(config->private_key,
                                               passphrase,
                                               NULL,
                                               NULL,
                                               &private_key);
        if (result != SSH_OK || private_key == NULL) {
            pi_ssh_set_error_value(error,
                                   PI_SSH_ERROR_PRIVATE_KEY,
                                   0,
                                   NULL,
                                   "Could not parse SSH private key: %s",
                                   ssh_get_error(session));
            return SSH_ERROR;
        }
        result = ssh_userauth_publickey(session, NULL, private_key);
        ssh_key_free(private_key);
        if (result != SSH_AUTH_SUCCESS) {
            pi_ssh_set_error_value(error,
                                   PI_SSH_ERROR_AUTHENTICATION,
                                   0,
                                   NULL,
                                   "SSH public-key authentication failed: %s",
                                   ssh_get_error(session));
            return SSH_ERROR;
        }
    }
    return SSH_OK;
}

static void pi_ssh_tunnel_cleanup_unstarted(pi_ssh_tunnel *tunnel)
{
    if (tunnel == NULL) {
        return;
    }
    if (tunnel->session != NULL) {
        if (ssh_is_connected(tunnel->session)) {
            ssh_set_blocking(tunnel->session, 1);
            ssh_disconnect(tunnel->session);
        }
        ssh_free(tunnel->session);
    }
    pi_ssh_socket_close(tunnel->listener_socket);
    pi_ssh_socket_close(tunnel->wake_read_socket);
    pi_ssh_socket_close(tunnel->wake_write_socket);
    free(tunnel->remote_host);
    pi_ssh_mutex_destroy(&tunnel->mutex);
    pi_ssh_secure_zero(tunnel, sizeof(*tunnel));
    free(tunnel);
}

pi_ssh_tunnel *pi_ssh_tunnel_start(const pi_ssh_tunnel_config *config,
                                   pi_ssh_error *error)
{
    pi_ssh_tunnel *tunnel = NULL;
    char *fingerprint = NULL;
    int platform_result;
    int thread_result;

    pi_ssh_error_init(error);
    if (!pi_ssh_validate_config(config, error)) {
        return NULL;
    }
    platform_result = pi_ssh_platform_initialize();
    if (platform_result != 0) {
        pi_ssh_set_error_value(error,
                               PI_SSH_ERROR_INTERNAL,
                               platform_result,
                               NULL,
                               "Could not initialize the SSH socket runtime");
        return NULL;
    }

    tunnel = (pi_ssh_tunnel *)calloc(1, sizeof(*tunnel));
    if (tunnel == NULL) {
        pi_ssh_set_error_value(error,
                               PI_SSH_ERROR_OUT_OF_MEMORY,
                               0,
                               NULL,
                               "Could not allocate SSH tunnel");
        return NULL;
    }
    tunnel->listener_socket = PI_SSH_INVALID_SOCKET;
    tunnel->wake_read_socket = PI_SSH_INVALID_SOCKET;
    tunnel->wake_write_socket = PI_SSH_INVALID_SOCKET;
    tunnel->state = PI_SSH_STATE_STARTING;
    pi_ssh_error_init(&tunnel->last_error);
    platform_result = pi_ssh_mutex_initialize(&tunnel->mutex);
    if (platform_result != 0) {
        pi_ssh_set_error_value(error,
                               PI_SSH_ERROR_INTERNAL,
                               platform_result,
                               NULL,
                               "Could not initialize SSH tunnel mutex");
        free(tunnel);
        return NULL;
    }

    tunnel->session = ssh_new();
    tunnel->remote_host = pi_ssh_platform_duplicate_string(config->remote_host);
    tunnel->remote_port = config->remote_port;
    tunnel->keepalive_interval_seconds = config->keepalive_interval_seconds;
    if (tunnel->session == NULL || tunnel->remote_host == NULL) {
        pi_ssh_set_error_value(error,
                               PI_SSH_ERROR_OUT_OF_MEMORY,
                               0,
                               NULL,
                               "Could not allocate SSH session");
        pi_ssh_tunnel_cleanup_unstarted(tunnel);
        return NULL;
    }
    if (pi_ssh_configure_session(tunnel->session, config) != SSH_OK ||
        ssh_connect(tunnel->session) != SSH_OK) {
        pi_ssh_set_error_value(error,
                               PI_SSH_ERROR_SSH_CONNECT,
                               pi_ssh_platform_last_error(),
                               NULL,
                               "Could not connect to SSH server: %s",
                               ssh_get_error(tunnel->session));
        pi_ssh_tunnel_cleanup_unstarted(tunnel);
        return NULL;
    }

    fingerprint = pi_ssh_server_fingerprint(tunnel->session);
    if (fingerprint == NULL) {
        pi_ssh_set_error_value(error,
                               PI_SSH_ERROR_HOST_KEY_UNAVAILABLE,
                               0,
                               NULL,
                               "Could not read SSH server host key: %s",
                               ssh_get_error(tunnel->session));
        pi_ssh_tunnel_cleanup_unstarted(tunnel);
        return NULL;
    }
    if (!pi_ssh_string_present(config->expected_host_key_sha256)) {
        pi_ssh_set_error_value(error,
                               PI_SSH_ERROR_HOST_KEY_UNKNOWN,
                               0,
                               fingerprint,
                               "SSH server host key must be trusted explicitly");
        ssh_string_free_char(fingerprint);
        pi_ssh_tunnel_cleanup_unstarted(tunnel);
        return NULL;
    }
    if (strcmp(config->expected_host_key_sha256, fingerprint) != 0) {
        pi_ssh_set_error_value(error,
                               PI_SSH_ERROR_HOST_KEY_MISMATCH,
                               0,
                               fingerprint,
                               "SSH server host key changed");
        ssh_string_free_char(fingerprint);
        pi_ssh_tunnel_cleanup_unstarted(tunnel);
        return NULL;
    }
    ssh_string_free_char(fingerprint);

    if (pi_ssh_authenticate(tunnel->session, config, error) != SSH_OK) {
        pi_ssh_tunnel_cleanup_unstarted(tunnel);
        return NULL;
    }
    tunnel->listener_socket =
        pi_ssh_socket_create_listener(&tunnel->local_port);
    if (!pi_ssh_socket_is_valid(tunnel->listener_socket) ||
        pi_ssh_wake_pair_create(&tunnel->wake_read_socket,
                                &tunnel->wake_write_socket) < 0) {
        pi_ssh_set_error_value(error,
                               PI_SSH_ERROR_LOCAL_LISTENER,
                               pi_ssh_platform_last_error(),
                               NULL,
                               "Could not create loopback SSH tunnel listener");
        pi_ssh_tunnel_cleanup_unstarted(tunnel);
        return NULL;
    }

    ssh_set_blocking(tunnel->session, 0);
    tunnel->state = PI_SSH_STATE_RUNNING;
    thread_result = pi_ssh_thread_start(&tunnel->worker,
                                        pi_ssh_worker_main,
                                        tunnel);
    if (thread_result != 0) {
        tunnel->state = PI_SSH_STATE_STOPPED;
        pi_ssh_set_error_value(error,
                               PI_SSH_ERROR_THREAD,
                               thread_result,
                               NULL,
                               "Could not start SSH tunnel worker");
        pi_ssh_tunnel_cleanup_unstarted(tunnel);
        return NULL;
    }
    tunnel->worker_started = true;
    return tunnel;
}

uint16_t pi_ssh_tunnel_local_port(const pi_ssh_tunnel *tunnel)
{
    return tunnel == NULL ? 0 : tunnel->local_port;
}

int32_t pi_ssh_tunnel_state(const pi_ssh_tunnel *tunnel)
{
    int32_t state;
    if (tunnel == NULL) {
        return PI_SSH_STATE_STOPPED;
    }
    pi_ssh_mutex_lock((pi_ssh_mutex *)&tunnel->mutex);
    state = tunnel->state;
    pi_ssh_mutex_unlock((pi_ssh_mutex *)&tunnel->mutex);
    return state;
}

void pi_ssh_tunnel_copy_last_error(const pi_ssh_tunnel *tunnel,
                                   pi_ssh_error *error)
{
    if (error == NULL) {
        return;
    }
    pi_ssh_error_init(error);
    if (tunnel == NULL) {
        return;
    }
    pi_ssh_mutex_lock((pi_ssh_mutex *)&tunnel->mutex);
    *error = tunnel->last_error;
    pi_ssh_mutex_unlock((pi_ssh_mutex *)&tunnel->mutex);
}

void pi_ssh_tunnel_free(pi_ssh_tunnel *tunnel)
{
    if (tunnel == NULL) {
        return;
    }
    pi_ssh_mutex_lock(&tunnel->mutex);
    if (tunnel->state == PI_SSH_STATE_RUNNING ||
        tunnel->state == PI_SSH_STATE_STARTING) {
        tunnel->state = PI_SSH_STATE_STOPPING;
    }
    pi_ssh_mutex_unlock(&tunnel->mutex);

    if (pi_ssh_socket_is_valid(tunnel->wake_write_socket)) {
        pi_ssh_wake_pair_signal(tunnel->wake_write_socket);
    }
    if (tunnel->worker_started) {
        (void)pi_ssh_thread_join(tunnel->worker);
        tunnel->worker_started = false;
    }

    if (tunnel->session != NULL) {
        if (ssh_is_connected(tunnel->session)) {
            ssh_set_blocking(tunnel->session, 1);
            ssh_disconnect(tunnel->session);
        }
        ssh_free(tunnel->session);
        tunnel->session = NULL;
    }
    pi_ssh_socket_close(tunnel->listener_socket);
    pi_ssh_socket_close(tunnel->wake_read_socket);
    pi_ssh_socket_close(tunnel->wake_write_socket);
    free(tunnel->remote_host);
    pi_ssh_mutex_destroy(&tunnel->mutex);
    pi_ssh_secure_zero(tunnel, sizeof(*tunnel));
    free(tunnel);
}

const char *pi_ssh_library_version(void)
{
    return ssh_version(0);
}
