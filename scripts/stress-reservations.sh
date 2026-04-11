#!/usr/bin/env bash
# Re-runs the reservation concurrency suite N times, each in a fresh JVM against a fresh Postgres
# container. Every run already repeats the 7-of-10 race 50 times, so N=10 means 500 races.
# Usage: scripts/stress-reservations.sh [runs]
set -euo pipefail
runs=${1:-10}
cd "$(dirname "$0")/../backend"
pass=0
for i in $(seq 1 "$runs"); do
  if ./mvnw -q test -Dtest=ReservationConcurrencyTest -Dsurefire.failIfNoSpecifiedTests=false >/tmp/stress-run.log 2>&1; then
    pass=$((pass + 1))
    echo "run $i/$runs: $(grep -h 'Tests run' target/surefire-reports/*ReservationConcurrencyTest.txt)"
  else
    echo "run $i/$runs: FAILED"
    grep -E 'Tests run|FAIL|Exception' /tmp/stress-run.log | head -20
    exit 1
  fi
done
echo "all $pass/$runs runs passed"
