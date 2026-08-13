#ifndef PI_SSH_H
#define PI_SSH_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#if defined(PI_SSH_BUILDING_LIBRARY)
#define PI_SSH_API __declspec(dllexport)
#else
#define PI_SSH_API __declspec(dllimport)
#endif
#else
#define PI_SSH_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define PI_SSH_ABI_VERSION 1u
#define PI_SSH_DEFAULT_PORT 22u
#define PI_SSH_DEFAULT_CONNECT_TIMEOUT_MS 15000u
#define PI_SSH_DEFAULT_KEEPALIVE_INTERVAL_SECONDS 30u
#define PI_SSH_DEFAULT_COMMAND_TIMEOUT_MS 120000u
#define PI_SSH_DEFAULT_MAX_OUTPUT_BYTES (1024u * 1024u)

typedef struct pi_ssh_tunnel pi_ssh_tunnel;

typedef enum pi_ssh_auth_type {
    PI_SSH_AUTH_PASSWORD = 1,
    PI_SSH_AUTH_PRIVATE_KEY = 2,
} pi_ssh_auth_type;

typedef enum pi_ssh_state {
    PI_SSH_STATE_STARTING = 1,
    PI_SSH_STATE_RUNNING = 2,
    PI_SSH_STATE_STOPPING = 3,
    PI_SSH_STATE_STOPPED = 4,
    PI_SSH_STATE_FAILED = 5,
} pi_ssh_state;

typedef enum pi_ssh_error_code {
    PI_SSH_ERROR_NONE = 0,
    PI_SSH_ERROR_INVALID_ARGUMENT = 1,
    PI_SSH_ERROR_OUT_OF_MEMORY = 2,
    PI_SSH_ERROR_SSH_CONNECT = 3,
    PI_SSH_ERROR_HOST_KEY_UNAVAILABLE = 4,
    PI_SSH_ERROR_HOST_KEY_UNKNOWN = 5,
    PI_SSH_ERROR_HOST_KEY_MISMATCH = 6,
    PI_SSH_ERROR_PRIVATE_KEY = 7,
    PI_SSH_ERROR_AUTHENTICATION = 8,
    PI_SSH_ERROR_LOCAL_LISTENER = 9,
    PI_SSH_ERROR_THREAD = 10,
    PI_SSH_ERROR_SSH_DISCONNECTED = 11,
    PI_SSH_ERROR_REMOTE_FORWARD = 12,
    PI_SSH_ERROR_INTERNAL = 13,
    PI_SSH_ERROR_REMOTE_COMMAND = 14,
    PI_SSH_ERROR_COMMAND_TIMEOUT = 15,
    PI_SSH_ERROR_OUTPUT_LIMIT = 16,
    PI_SSH_ERROR_KEY_GENERATION = 17,
} pi_ssh_error_code;

typedef enum pi_ssh_command_stream {
    PI_SSH_COMMAND_STDOUT = 1,
    PI_SSH_COMMAND_STDERR = 2,
} pi_ssh_command_stream;

/*
 * Receives each command-output chunk on the thread calling
 * pi_ssh_command_execute_streaming(). The byte pointer is borrowed only for
 * the duration of the callback. Return zero to continue or non-zero to abort.
 */
typedef int (*pi_ssh_command_output_callback)(
    void *context,
    int32_t stream,
    const uint8_t *data,
    size_t size);

/*
 * All string pointers are borrowed for the duration of pi_ssh_tunnel_start().
 * The returned tunnel owns copies of values needed by its worker thread.
 */
typedef struct pi_ssh_tunnel_config {
    uint32_t struct_size;
    uint32_t abi_version;

    const char *ssh_host;
    uint16_t ssh_port;
    uint16_t reserved_port;
    const char *username;

    int32_t auth_type;
    const char *password;
    const char *private_key;
    const char *private_key_passphrase;

    /* Exact SHA-256 fingerprint in OpenSSH form, for example SHA256:abc... */
    const char *expected_host_key_sha256;

    /* Address resolved by the SSH server for direct-tcpip forwarding. */
    const char *remote_host;
    uint16_t remote_port;
    uint16_t reserved_remote_port;

    /* Also bounds opening each direct-tcpip forwarding channel. */
    uint32_t connect_timeout_ms;
    /* Requests best-effort ACK-based TCP keepalive tuning; zero disables it. */
    uint32_t keepalive_interval_seconds;
} pi_ssh_tunnel_config;

typedef struct pi_ssh_error {
    uint32_t struct_size;
    int32_t code;
    int32_t system_error;
    char message[256];
    char host_key_sha256[96];
} pi_ssh_error;

/*
 * Configuration for a single non-interactive command. String pointers and
 * stdin_data are borrowed only for the duration of pi_ssh_command_execute().
 */
typedef struct pi_ssh_command_config {
    uint32_t struct_size;
    uint32_t abi_version;

    const char *ssh_host;
    uint16_t ssh_port;
    uint16_t reserved_port;
    const char *username;

    int32_t auth_type;
    const char *password;
    const char *private_key;
    const char *private_key_passphrase;

    /* Exact SHA-256 fingerprint in OpenSSH form, for example SHA256:abc... */
    const char *expected_host_key_sha256;

    const char *command;
    const uint8_t *stdin_data;
    size_t stdin_size;

    uint32_t connect_timeout_ms;
    uint32_t command_timeout_ms;
    size_t max_output_bytes;
} pi_ssh_command_config;

/* Output buffers are owned by this value and released by result_free(). */
typedef struct pi_ssh_command_result {
    uint32_t struct_size;
    int32_t exit_status;
    uint8_t *stdout_data;
    size_t stdout_size;
    uint8_t *stderr_data;
    size_t stderr_size;
} pi_ssh_command_result;

/* In-memory OpenSSH key material returned by the generation function below. */
typedef struct pi_ssh_key_pair {
    uint32_t struct_size;
    char *private_key;
    char *public_key;
} pi_ssh_key_pair;

/* Initializes a config with ABI-safe defaults. */
PI_SSH_API void pi_ssh_tunnel_config_init(pi_ssh_tunnel_config *config);

/* Initializes an empty error value. */
PI_SSH_API void pi_ssh_error_init(pi_ssh_error *error);

/* Initializes a command config with ABI-safe defaults. */
PI_SSH_API void pi_ssh_command_config_init(pi_ssh_command_config *config);

/* Initializes an empty command result. */
PI_SSH_API void pi_ssh_command_result_init(pi_ssh_command_result *result);

/* Securely clears and releases buffers returned by command_execute(). */
PI_SSH_API void pi_ssh_command_result_free(pi_ssh_command_result *result);

/* Initializes an empty key-pair result. */
PI_SSH_API void pi_ssh_key_pair_init(pi_ssh_key_pair *key_pair);

/* Securely clears and releases buffers returned by key-pair generation. */
PI_SSH_API void pi_ssh_key_pair_free(pi_ssh_key_pair *key_pair);

/*
 * Generates an Ed25519 key pair entirely in memory. The private key is
 * exported in OpenSSH format and encrypted when passphrase is non-empty. The
 * public key is returned in authorized_keys form ("ssh-ed25519 AAAA...").
 */
PI_SSH_API int pi_ssh_key_pair_generate_ed25519(
    const char *passphrase,
    pi_ssh_key_pair *key_pair,
    pi_ssh_error *error);

/*
 * Executes one command through an authenticated SSH session. A successfully
 * executed remote command returns 0 even when its exit_status is non-zero.
 * Transport/protocol failures return -1 and populate error.
 */
PI_SSH_API int pi_ssh_command_execute(
    const pi_ssh_command_config *config,
    pi_ssh_command_result *result,
    pi_ssh_error *error);

/* Executes one command while forwarding stdout/stderr chunks as they arrive. */
PI_SSH_API int pi_ssh_command_execute_streaming(
    const pi_ssh_command_config *config,
    pi_ssh_command_result *result,
    pi_ssh_command_output_callback output_callback,
    void *output_context,
    pi_ssh_error *error);

/*
 * Connects and authenticates synchronously, then starts the forwarding worker.
 *
 * Host verification is mandatory. If expected_host_key_sha256 is empty, this
 * returns NULL with PI_SSH_ERROR_HOST_KEY_UNKNOWN and the observed fingerprint
 * in error->host_key_sha256. The caller must explicitly trust that fingerprint
 * and call start again.
 */
PI_SSH_API pi_ssh_tunnel *pi_ssh_tunnel_start(
    const pi_ssh_tunnel_config *config,
    pi_ssh_error *error);

/* Returns the loopback TCP port selected for this tunnel, or 0 if unavailable. */
PI_SSH_API uint16_t pi_ssh_tunnel_local_port(const pi_ssh_tunnel *tunnel);

/* Returns a pi_ssh_state value. */
PI_SSH_API int32_t pi_ssh_tunnel_state(const pi_ssh_tunnel *tunnel);

/* Copies the latest tunnel-level or forwarding error into error. */
PI_SSH_API void pi_ssh_tunnel_copy_last_error(
    const pi_ssh_tunnel *tunnel,
    pi_ssh_error *error);

/* Stops the worker and releases every resource; call once for each handle. */
PI_SSH_API void pi_ssh_tunnel_free(pi_ssh_tunnel *tunnel);

/* Exposed for diagnostics and About screens. */
PI_SSH_API const char *pi_ssh_library_version(void);

#ifdef __cplusplus
}
#endif

#endif
