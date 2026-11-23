#!/usr/bin/env bash
# Add the named bundle patch "MicroG support (universal)" to a morphe-patches checkout.
# The library half (universal patch + microg extension) lives in this repo under patches/.
# usage: patches/bundle/apply.sh <morphe-patches dir>
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
git -C "$1" apply --index "$here/morphe-patches.patch"
echo "applied: bundle patch 'MicroG support (universal)'; build it against the library jar from patches/"
