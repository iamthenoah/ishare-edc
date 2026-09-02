#!/usr/bin/env bash

set -euo pipefail
source "$(dirname "$0")/lib/scenario-common.sh"

launch_scenario 3 akka http http none none
