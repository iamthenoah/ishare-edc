#!/usr/bin/env bash

set -euo pipefail
source "$(dirname "$0")/lib/scenario-common.sh"

launch_scenario 6 http akka akka provider consumer vc akka
