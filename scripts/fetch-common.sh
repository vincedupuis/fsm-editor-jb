#!/usr/bin/env bash
# Copies fsm-editor-common's sidecar into plugin/cli/<platform>/ so the plugin bundles it (plugin/cli is gitignored),
# with fsm-cli's templates (plugin/cli/templates), the files' JSON Schemas (plugin/cli/schemas, bundled: the text
# editor checks the files against them) and the interface's cases (plugin/cli/api-cases, which the core tests replay;
# not bundled). <platform> is darwin-arm64, darwin-x64, linux-arm64, linux-x64 or windows-x64.
#
# It is taken from:
#   - clones of fsm-editor-common and fsm-cli side by side (by default the siblings ../fsm-editor-common and
#     ../fsm-cli): the sidecar from target/release (under $CARGO_TARGET_DIR when set), for this machine's platform;
#   - or a folder of an fsm-editor-common release's archives, unpacked: fsm-editor-common-<version>-<platform>/ each
#     holding the sidecar (every platform found), with templates/, schemas/ and api-cases/ beside them.
#
# Usage: scripts/fetch-common.sh [--build] [clone of fsm-editor-common, or folder of release archives]
#   --build    runs `cargo build --release` in the clone first
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
build=false
src="$here/../fsm-editor-common"
for arg in "$@"; do
  case "$arg" in
    --build) build=true ;;
    *) src="$(cd "$arg" && pwd)" ;;
  esac
done

case "$(uname -s)" in
  Darwin) os=darwin ;;
  Linux) os=linux ;;
  MINGW*|MSYS*|CYGWIN*) os=windows ;;
  *) echo "Unsupported system $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  arm64|aarch64) arch=arm64 ;;
  *) arch=x64 ;;
esac
exe=fsm-editor-common
[ "$os" = windows ] && exe=fsm-editor-common.exe

out="$here/plugin/cli"
rm -rf "$out"
mkdir -p "$out/schemas"
if [ -f "$src/Cargo.toml" ]; then
  cli="$src/../fsm-cli"
  if $build; then
    cargo="cargo"
    command -v cargo > /dev/null || cargo="$HOME/.cargo/bin/cargo"
    (cd "$src" && "$cargo" build --release --locked)
  fi
  release="${CARGO_TARGET_DIR:-$src/target}/release"
  if [ -f "$release/$exe" ]; then
    mkdir -p "$out/$os-$arch"
    cp "$release/$exe" "$out/$os-$arch/"
  fi
  cp -R "$cli/templates" "$out/templates"
  cp "$cli/schemas/"*.json "$src/schemas/"*.json "$out/schemas/"
  cp -R "$src/api-cases" "$out/api-cases"
else
  for dir in "$src"/fsm-editor-common-*-*-*/; do
    [ -d "$dir" ] || continue
    platform="$(basename "$dir" | sed -E 's/^fsm-editor-common-[^-]+-//')"
    mkdir -p "$out/$platform"
    cp "$dir"/fsm-editor-common* "$out/$platform/"
  done
  cp -R "$src/templates" "$out/templates"
  cp "$src/schemas/"*.json "$out/schemas/"
  cp -R "$src/api-cases" "$out/api-cases"
fi
chmod +x "$out"/*/fsm-editor-common 2>/dev/null || true
ls -d "$out"/*-*/ > /dev/null 2>&1 || echo "No fsm-editor-common sidecar found in $src: build it (cargo build --release, or --build), or pass a folder of unpacked release archives (https://github.com/vincedupuis/fsm-editor-common/releases)." >&2
echo "Copied to plugin/cli: $(cd "$out" && ls | tr '\n' ' ')"
