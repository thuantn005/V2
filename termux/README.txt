Brainfuck-Psiphon Termux bundle
===============================
In Termux:
    pkg install unzip
    unzip brainfuck-termux-*.zip
    cd bundle-*
    ./run.sh

Wait ~1-2 minutes for the "Connected" line. The tunnel then serves a
SOCKS5 proxy on 127.0.0.1:3080 (an HTTP injector is on :8989).

To route all app traffic you still need tun2socks / sing-box pointed at
127.0.0.1:3080, as in your existing setup.

Change the bug host:  export BF_FRONT=<new-ip>   before ./run.sh
Pin egress region:    export BF_REGION=sg        before ./run.sh
