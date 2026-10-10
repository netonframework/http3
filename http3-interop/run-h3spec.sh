#!/bin/bash
# h3spec (SPEC §6) against the neton HTTP/3 server (h3interop.kexe server, default configuration, GREASE on), with
# every case run. Usage: run-h3spec.sh <h3interop.kexe> <h3spec binary> [port]; expects certs/ from gen-certs.sh.
# Fails on any h3spec failure, and also when a case was skipped: h3spec reports "0-RTT is not possible. Skipping this
# test" yet marks `CRYPTO in 0-RTT` as passed when the server does not do 0-RTT, so a pass alone is not evidence. The
# server's log must show the 0-RTT packet was actually received and refused.
# Only the server started here is stopped, by its recorded PID.
set -u
K=$1
H3SPEC=$2
P=${3:-24940}
C=$(pwd)/certs
mkdir -p logs
"$K" server 127.0.0.1:$P "$C/server.pem" "$C/server.key" > logs/h3spec-server.log 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null; wait $SERVER 2>/dev/null' EXIT
sleep 2
# -n: h3spec does not verify the certificate (it has no option to name a CA); -t: timeout in milliseconds.
"$H3SPEC" 127.0.0.1 $P -n -t 3000 2>&1 | tee logs/h3spec.txt
R=${PIPESTATUS[0]}
FAILS=0
check() { if [ "$1" = 0 ]; then echo "== PASS $2"; else echo "== FAIL $2"; FAILS=$((FAILS + 1)); fi; }
check "$R" "h3spec exit status ($R)"
grep -q "examples, 0 failures" logs/h3spec.txt; check $? "h3spec: 0 failures"
! grep -qi "not possible\|skipping" logs/h3spec.txt; check $? "h3spec: no case skipped"
grep -q "in 0-RTT" logs/h3spec-server.log; check $? "the 0-RTT case reached the server (its 0-RTT packet was decrypted and refused)"
grep -h "in 0-RTT" logs/h3spec-server.log | head -1
echo "== $FAILS failure(s)"
[ $FAILS = 0 ]
