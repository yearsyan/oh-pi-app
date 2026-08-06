#include "pi_ssh.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

int main(void)
{
    pi_ssh_tunnel_config config;
    pi_ssh_error error;
    pi_ssh_tunnel *tunnel;
    pi_ssh_command_config command_config;
    pi_ssh_command_result command_result;

    pi_ssh_tunnel_config_init(&config);
    assert(config.struct_size == sizeof(config));
    assert(config.abi_version == PI_SSH_ABI_VERSION);
    assert(config.ssh_port == PI_SSH_DEFAULT_PORT);

    pi_ssh_error_init(&error);
    tunnel = pi_ssh_tunnel_start(&config, &error);
    assert(tunnel == NULL);
    assert(error.code == PI_SSH_ERROR_INVALID_ARGUMENT);
    assert(strlen(error.message) > 0);

    pi_ssh_command_config_init(&command_config);
    assert(command_config.struct_size == sizeof(command_config));
    assert(command_config.abi_version == PI_SSH_ABI_VERSION);
    assert(command_config.ssh_port == PI_SSH_DEFAULT_PORT);
    assert(command_config.command_timeout_ms ==
           PI_SSH_DEFAULT_COMMAND_TIMEOUT_MS);
    assert(command_config.max_output_bytes ==
           PI_SSH_DEFAULT_MAX_OUTPUT_BYTES);

    pi_ssh_command_result_init(&command_result);
    assert(command_result.struct_size == sizeof(command_result));
    assert(command_result.exit_status == -1);
    assert(pi_ssh_command_execute(&command_config,
                                  &command_result,
                                  &error) == -1);
    assert(error.code == PI_SSH_ERROR_INVALID_ARGUMENT);
    assert(strlen(error.message) > 0);
    pi_ssh_command_result_free(&command_result);

    printf("pi_ssh config test passed (libssh %s)\n",
           pi_ssh_library_version());
    return 0;
}
