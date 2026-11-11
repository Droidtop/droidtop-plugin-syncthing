#!/usr/bin/env bash
# Run by droidtop-platforms' plugin bundle workflow before build.sh (its prepare-script input): installs the Go
# toolchain syncthing.pin names, checked against its published SHA-256, and puts it on PATH for build.sh.
set -euo pipefail
cd "$(dirname "$0")"
# shellcheck source=syncthing.pin
. ./syncthing.pin

: "${RUNNER_TEMP:?prepare.sh runs in GitHub Actions}"
archive="$RUNNER_TEMP/go${GO_VERSION}.linux-amd64.tar.gz"
curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz" -o "$archive"
echo "${GO_SHA256}  $archive" | sha256sum -c -
mkdir -p "$RUNNER_TEMP/go-toolchain"
tar -C "$RUNNER_TEMP/go-toolchain" -xzf "$archive"
echo "$RUNNER_TEMP/go-toolchain/go/bin" >> "$GITHUB_PATH"
{
  echo "GOTOOLCHAIN=local"
  echo "GOPATH=$RUNNER_TEMP/gopath"
  echo "GOCACHE=$RUNNER_TEMP/gocache"
} >> "$GITHUB_ENV"
"$RUNNER_TEMP/go-toolchain/go/bin/go" version
