#!/usr/bin/env bash
# Runs inside the reactivecircus emulator step (on the runner, with adb access
# to the booted emulator). Pushes the android psiphon binary + server list into
# the emulator, runs it for 150s on open internet, and reports whether a tunnel
# was established. Always exits 0 so the timeout's exit code 124 does not fail
# the step before the analysis runs.
set +e

adb push psi_android /data/local/tmp/psi
adb push config.json /data/local/tmp/config.json
adb push app/src/main/assets/server_list /data/local/tmp/server_list
adb shell chmod 755 /data/local/tmp/psi

echo "=== running android psiphon binary for 150s ==="
adb shell "cd /data/local/tmp && mkdir -p d && timeout 150 ./psi -config config.json -serverList server_list -dataRootDirectory /data/local/tmp/d > psi.log 2>&1"

adb pull /data/local/tmp/psi.log psi.log 2>/dev/null

echo "--- last 45 lines ---"
tail -45 psi.log
echo "--- noticeType counts ---"
grep -oE '"noticeType":"[A-Za-z]+"' psi.log | sort | uniq -c | sort -rn | head -20
echo "--- errors / denied / EOF / failed ---"
grep -iE 'error|denied|EOF|failed|reject|refused|timeout' psi.log | tail -30

if grep -q '"count":1' psi.log || grep -q '"noticeType":"ActiveTunnel"' psi.log; then
  echo "RESULT=ANDROID_CONNECTED"
else
  echo "RESULT=ANDROID_NOT_CONNECTED"
fi
exit 0
