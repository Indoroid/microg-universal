#!/usr/bin/env bash
# Build app.morphe:morphe-patcher (pinned tag) from source into Maven local; the patch library,
# harness and store depend on it. Its build reads GitHub Packages, so GITHUB_ACTOR / GITHUB_TOKEN
# (read:packages) must be set. Publication signing is turned off (no GPG key in CI).
set -euo pipefail
VERSION=${MORPHE_PATCHER_VERSION:-2.5.0}
if [ -f "$HOME/.m2/repository/app/morphe/morphe-patcher/$VERSION/morphe-patcher-$VERSION.jar" ]; then
  echo "morphe-patcher $VERSION already in Maven local"; exit 0
fi
SRC=$(mktemp -d)
git clone -q --depth 1 --branch "v$VERSION" https://github.com/MorpheApp/morphe-patcher "$SRC"
sed -i 's|^\(\s*\)sign(publishing|\1// sign(publishing|' "$SRC/build.gradle.kts"
(cd "$SRC" && ./gradlew -q publishToMavenLocal -x test --no-daemon)
rm -rf "$SRC"
echo "morphe-patcher $VERSION published to Maven local"
