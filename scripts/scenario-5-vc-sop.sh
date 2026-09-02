#!/usr/bin/env bash

set -euo pipefail
source "$(dirname "$0")/lib/scenario-common.sh"

launch_scenario 5 http http http none none vc http
