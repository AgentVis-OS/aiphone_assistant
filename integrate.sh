#!/bin/bash
# Legacy wrapper - the canonical script is build_lmi.sh
exec bash "$(dirname "$(readlink -f "$0")")/build_lmi.sh" "$@"
