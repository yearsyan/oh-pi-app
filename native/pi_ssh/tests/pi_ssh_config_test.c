#include "pi_ssh.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

int main(void)
{
    pi_ssh_tunnel_config config;
    pi_ssh_error error;
    pi_ssh_tunnel *tunnel;

    pi_ssh_tunnel_config_init(&config);
    assert(config.struct_size == sizeof(config));
    assert(config.abi_version == PI_SSH_ABI_VERSION);
    assert(config.ssh_port == PI_SSH_DEFAULT_PORT);

    pi_ssh_error_init(&error);
    tunnel = pi_ssh_tunnel_start(&config, &error);
    assert(tunnel == NULL);
    assert(error.code == PI_SSH_ERROR_INVALID_ARGUMENT);
    assert(strlen(error.message) > 0);

    printf("pi_ssh config test passed (libssh %s)\n",
           pi_ssh_library_version());
    return 0;
}
