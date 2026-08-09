#!/usr/bin/env bash
# Brainfuck-Psiphon engine launcher for Termux.
cd "$(dirname "$0")" || exit 1
chmod +x brainfuck psiphon-tunnel-core 2>/dev/null

export HOME="$PWD"
export BF_CONFIG_HOME="$PWD"
export BF_SERVERLIST="$PWD/server_list"
export BF_CORE_NAME="psiphon-tunnel-core"
export BF_CORES="${BF_CORES:-2}"

# Viettel bug defaults are baked into the engine:
#   akamai.net -> 125.235.36.177 on ports 80 & 443, egress region auto.
# Override before running, e.g.:
#   export BF_FRONT=1.2.3.4     # different bug IP
#   export BF_WHITELIST=host    # different fronted host
#   export BF_REGION=sg         # pin egress region
echo "Starting engine..."
echo "A SOCKS5 proxy will appear on 127.0.0.1:3080 once a tunnel connects."
echo "Watch for the 'Connected' line (can take ~1-2 minutes)."
exec ./brainfuck
