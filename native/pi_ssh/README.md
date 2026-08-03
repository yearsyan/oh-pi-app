# pi_ssh native tunnel

`pi_ssh` is the shared SSH transport used by the Android, iOS, and desktop
apps. It exposes a small C ABI and keeps all SSH protocol, host-key checking,
authentication, local listener, channel multiplexing, buffering, and shutdown
behavior in one implementation.

The app currently packages this POSIX worker for Android, iOS, macOS
(arm64/x86_64), and Linux x86_64. A Windows Desktop package will require a
WinSock event/wakeup backend before SSH tunneling can be enabled there.

The tunnel binds only `127.0.0.1` on an OS-selected port. Each local TCP
connection becomes one SSH `direct-tcpip` channel to `remote_host:remote_port`.
The WebSocket and HTTP clients can therefore use the same rewritten loopback
gateway URL.

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

libssh is licensed under LGPL-2.1-or-later. Mbed TLS is licensed under
Apache-2.0 OR GPL-2.0-or-later. Release packaging must retain their applicable
license and source/relinking notices. Complete texts, source checksums, applied
patches, and relinking information are recorded in
[`licenses/THIRD_PARTY_NOTICES.md`](licenses/THIRD_PARTY_NOTICES.md).
