#!/usr/bin/env bash
# Apply the universal microG patch to Morphe checkouts in one go.
# usage: morphe/apply.sh <morphe-patches-library dir> <morphe-patches dir>
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
git -C "$1" apply --index "$here/morphe-patches-library.patch"
git -C "$2" apply --index "$here/morphe-patches.patch"
# The extension's contract must be the shared one (tools/e2e.sh check verifies this too).
cmp "$here/../shared/src/main/java/app/morphe/extension/microg/contract/MicroGContract.java" \
    "$1/extensions/microg/src/main/java/app/morphe/extension/microg/contract/MicroGContract.java"
echo "applied: library (patch + microg extension) and bundle (named patch 'MicroG support (universal)')"
echo "note: morphe-patches must depend on the morphe-patches-library version built from $1"
