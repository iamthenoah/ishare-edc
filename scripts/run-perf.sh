#!/usr/bin/env bash

set -euo pipefail
cd "$(dirname "$0")/.."

scenario="${1:?Usage: run-perf.sh <scenario-number 1-4> [modes] [concurrency] [duration-s] [repeats]}"
modes="${2:-ping,catalog,run}"
concurrency="${3:-1,4,16}"
duration="${4:-30}"
repeats="${5:-3}"

./gradlew --console=plain :perf:perfRun \
  -Dperf.scenario="$scenario" \
  -Dperf.mode="$modes" \
  -Dperf.concurrency="$concurrency" \
  -Dperf.duration-seconds="$duration" \
  -Dperf.repeats="$repeats"

echo
echo "Done. Raw samples + summary.csv in results/perf/scenario-$scenario/."
echo "Cross-scenario table: ./gradlew :perf:perfCompare  ->  results/perf/comparison.md"
