#!/usr/bin/env bash
# Runs inside the reactivecircus emulator step. Pushes the FULL engine
# (libbrainfuck injector/rotator + libpsiphon.so core) into the emulator and
# runs it exactly as the app does — FRONTED-MEEK forced through the injector to
# the Viettel bug IP 125.235.36.177 — to see whether the bug path establishes a
# tunnel in a real Android environment on open internet. Always exits 0.
set +e

adb push libbrainfuck /data/local/tmp/libbrainfuck
adb push libpsiphon.so /data/local/tmp/libpsiphon.so
adb push app/src/main/assets/server_list /data/local/tmp/server_list
adb shell chmod 755 /data/local/tmp/libbrainfuck /data/local/tmp/libpsiphon.so

echo "=== running full engine (injector + bug + FRONTED-MEEK) for 150s ==="
adb shell "cd /data/local/tmp && HOME=/data/local/tmp BF_CONFIG_HOME=/data/local/tmp BF_SERVERLIST=/data/local/tmp/server_list BF_CORE_NAME=libpsiphon.so BF_CORES=1 timeout 150 ./libbrainfuck > bug.log 2>&1"

adb pull /data/local/tmp/bug.log bug.log 2>/dev/null
# strip ANSI colour codes for readability
sed -i 's/\x1b\[[0-9;]*[A-Za-z]//g' bug.log 2>/dev/null

echo "--- injector activity (bug path exercised) ---"
grep -a "Connecting to 125.235.36.177" bug.log | head -6
echo "--- last 40 lines ---"
tail -40 bug.log
echo "--- errors / EOF / failed ---"
grep -aiE 'error|EOF|failed|denied|refused|blocked' bug.log | tail -25

if grep -aq "Connected" bug.log; then
  echo "RESULT=ANDROID_BUG_WORKS"
else
  echo "RESULT=ANDROID_BUG_FAILED"
fi
exit 0
