# pi_ssh native tunnel

`pi_ssh` is the shared SSH transport used by the Android, iOS, and desktop
apps. It exposes a small C ABI and keeps all SSH protocol, host-key checking,
authentication, local listener, channel multiplexing, buffering, and shutdown
behavior in one implementation.

The same ABI can execute one non-interactive remote command with binary-safe
stdin, separate bounded stdout/stderr buffers, a remote exit status, and a
deadline. The managed installer uses this path for OS probing, service control,
and direct binary upload without adding SFTP or shell download dependencies.

The ABI also generates passphrase-protected Ed25519 key pairs entirely in
memory. It returns an OpenSSH private key and an authorized_keys-form public
key; callers own the result until `pi_ssh_key_pair_free()` securely clears it.

The app packages this worker for Android, iOS, macOS (arm64/x86_64), Linux
x86_64, and Windows x86_64. POSIX targets use file descriptors and a pipe for
event wakeups. Windows uses WinSock sockets plus a loopback UDP socket pair so
libssh's socket-based Windows poller can wake the worker without polling.

The tunnel binds only `127.0.0.1` on an OS-selected port. Each local TCP
connection becomes one SSH `direct-tcpip` channel to `remote_host:remote_port`.
Channel opening is bounded by the configured connect timeout, so a half-open
SSH session transitions to failed instead of retaining loopback clients forever.
The SSH socket requests ACK-based operating-system TCP keepalive with
best-effort platform tuning; channel and application watchdogs remain the
fallback when tuning is unavailable. The WebSocket and HTTP clients can
therefore use the same rewritten loopback gateway URL while stale sessions
remain detectable.

Host verification is deliberately fail-closed. The first attempt returns
`PI_SSH_ERROR_HOST_KEY_UNKNOWN` and the observed SHA-256 fingerprint. A caller
must show it to the user and persist explicit trust before retrying. A changed
fingerprint returns `PI_SSH_ERROR_HOST_KEY_MISMATCH` before credentials are
sent.

Portable builds fetch checksum-pinned libssh 0.12.1 and Mbed TLS 3.6.6 source
archives. Developer builds can use an installed libssh:

```bash
cmake -S native/pi_ssh -B build/pi_ssh \
  -DPI_SSH_USE_SYSTEM_LIBSSH=ON \
  -DPI_SSH_BUILD_TESTS=ON
cmake --build build/pi_ssh
ctest --test-dir build/pi_ssh --output-on-failure
```

On Windows, configure from an x64 Visual Studio developer environment. The
portable build links the MSVC runtime, libssh, and Mbed TLS into `pi_ssh.dll`;
only Windows system DLLs are required at runtime.

libssh is licensed under LGPL-2.1-or-later. Mbed TLS is licensed under
Apache-2.0 OR GPL-2.0-or-later. Release packaging must retain their applicable
license and source/relinking notices. Complete texts, source checksums, applied
patches, and relinking information are recorded in
[`licenses/THIRD_PARTY_NOTICES.md`](licenses/THIRD_PARTY_NOTICES.md).
