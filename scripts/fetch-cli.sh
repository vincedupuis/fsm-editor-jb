#!/usr/bin/env bash
# Copies the `fsm` code generation CLI, with its templates, into plugin/cli/<platform>/
# so the plugin bundles it (plugin/cli is gitignored).
#
# The CLI is the one of FSM Editor for VS Code (github.com/vincedupuis/fsm-editor-vscode),
# built there with `npm run build:bin`. By default it is taken from a sibling clone:
#   ../fsm-editor-vscode/dist/fsm-<version>-<platform>/
# where <platform> is darwin-arm64, darwin-x64, linux-arm64, linux-x64 or windows-x64.
#
# Usage: scripts/fetch-cli.sh [--all] [--build] [path to fsm-editor-vscode]
#   (default)  only the platform of this machine
#   --all      every platform found in dist/ (a plugin for all systems, ~400 MB)
#   --build    runs `npm run build:bin` there first (with --all: every target, else `-- --target current`)
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
all=false
build=false
src="$here/../fsm-editor-vscode"
for arg in "$@"; do
  case "$arg" in
    --all) all=true ;;
    --build) build=true ;;
    *) src="$arg" ;;
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
host="$os-$arch"

if $build; then
  if $all; then (cd "$src" && npm run build:bin); else (cd "$src" && npm run build:bin -- --target current); fi
fi

# The newest version in dist/.
version="$(ls -d "$src"/dist/fsm-*-* 2>/dev/null | sed -E 's|.*/fsm-([0-9][0-9.]*)-.*|\1|' | sort -V | tail -1 || true)"
if [ -z "$version" ]; then
  echo "No fsm build found under $src/dist. Build it there with: npm run build:bin, or run this script with --build." >&2
  exit 1
fi
if $all; then
  platforms="$(ls -d "$src"/dist/fsm-"$version"-* | sed -E "s|.*/fsm-$version-||")"
else
  platforms="$host"
fi

out="$here/plugin/cli"
rm -rf "$out"
for p in $platforms; do
  dist="$src/dist/fsm-$version-$p"
  exe=fsm
  [ "${p%%-*}" = windows ] && exe=fsm.exe
  if [ ! -f "$dist/$exe" ]; then
    echo "No $exe in $dist. Build it with: npm run build:bin -- --target bun-$p" >&2
    exit 1
  fi
  mkdir -p "$out/$p"
  cp "$dist/$exe" "$out/$p/"
  cp -R "$dist/templates" "$out/$p/templates"
  echo "Copied fsm $version ($p) to plugin/cli/$p"
done
