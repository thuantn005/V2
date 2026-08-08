# Third-party components & attribution

This project is an integration of existing open-source software. Full credit
goes to the original authors.

## SocksDroid — the Android app base (VpnService + tun2socks)
- Upstream: https://github.com/bndeff/socksdroid (fork of https://github.com/PeterCxy/SocksDroid)
- License: GPL-3.0
- Reused: the Android application (`net.typeblog.socks`), the prebuilt native
  binaries `libtun2socks.so`, `libpdnsd.so`, `libsystem.so` under
  `app/src/main/jniLibs/`, and the overall VpnService → tun2socks → SOCKS5 flow.

## Brainfuck Psiphon Pro Go — the tunnel engine
- Upstream: https://github.com/aztecrabbit/brainfuck-psiphon-pro-go
- Author: Aztec Rabbit
- Reused (vendored under `engine/third_party/`): `libpsiphon`, and the helper
  libraries `libinject`, `libproxyrotator`, `libutils`, `liblog`, `libredsocks`.
  `engine/main.go` is an Android-specific launcher adapted from the upstream
  `main.go`; `libpsiphon` and `libutils` carry small patches (see
  `engine/third_party/*/`), noted in comments.

## Psiphon — the tunnel core
- Upstream: https://github.com/Psiphon-Labs/psiphon-tunnel-core
- License: GPL-3.0
- Built by CI into `libpsiphon.so` (the `ConsoleClient`); not vendored.

## Other Go modules (vendored under `engine/vendor/`)
- github.com/armon/go-socks5 (MIT)
- golang.org/x/net, golang.org/x/sys (BSD-3-Clause)
- github.com/buger/goterm (MIT)

The combined work is distributed under GPL-3.0 (see `LICENSE`).
