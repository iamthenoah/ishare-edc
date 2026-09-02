#!/usr/bin/env bash

set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v lizard >/dev/null 2>&1; then
  echo "lizard not found, installing..."
  pip3 install lizard --break-system-packages -q
fi

OUT="docs/metrics-report.md"
python3 scripts/lizard_report.py | tee "$OUT"

echo
echo "Report written to $OUT"
