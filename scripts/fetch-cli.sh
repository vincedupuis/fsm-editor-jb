#!/usr/bin/env bash
# Copies the `fsm` code generator, with its templates, into plugin/cli/<platform>/
# so the plugin bundles it (plugin/cli is gitignored). <platform> is darwin-arm64,
# darwin-x64, linux-arm64, linux-x64 or windows-x64. Also copies the JSON Schema of
# the model file into plugin/cli/schemas (bundled: the text editor checks .fsm files
# against it) and fsm-cli's test cases into plugin/cli/cases (the core tests check
# the reader and validator against them; not bundled).
#
# fsm is the program of fsm-cli (github.com/vincedupuis/fsm-cli). It is taken from:
#   - a clone of fsm-cli (by default the sibling ../fsm-cli): its target/release/fsm
#     (under $CARGO_TARGET_DIR when set) and templates/, for this machine's platform;
#   - or a folder of unpacked fsm-cli release archives, fsm-<version>-<platform>/
#     each holding fsm (fsm.exe on Windows) and templates/: the newest version found,
#     with schemas/ and cases/ unpacked from fsm-schemas-<version>.tar.gz and
#     fsm-cases-<version>.tar.gz beside them.
#
# Usage: scripts/fetch-cli.sh [--all] [--build] [path to a clone of fsm-cli, or to a folder of release archives]
#   (default)  only the platform of this machine
#   --all      every platform of the release archives (a plugin for all systems)
#   --build    runs `cargo build --release` in the clone first
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
all=false
build=false
src="$here/../fsm-cli"
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
exe_of() { if [ "${1%%-*}" = windows ]; then echo fsm.exe; else echo fsm; fi; }

out="$here/plugin/cli"
if [ -f "$src/Cargo.toml" ]; then
  # A clone of fsm-cli: this machine's build.
  if $all; then
    echo "--all needs a folder of unpacked fsm-<version>-<platform> release archives, not a clone of fsm-cli." >&2
    exit 2
  fi
  if $build; then
    cargo="$(command -v cargo || echo "$HOME/.cargo/bin/cargo")"
    (cd "$src" && "$cargo" build --release --locked)
  fi
  exe="${CARGO_TARGET_DIR:-$src/target}/release/$(exe_of "$host")"
  if [ ! -f "$exe" ]; then
    echo "No $exe. Build fsm-cli there with: cargo build --release, or run this script with --build." >&2
    exit 1
  fi
  rm -rf "$out"
  mkdir -p "$out/$host"
  cp "$exe" "$out/$host/"
  cp -R "$src/templates" "$out/$host/templates"
  cp -R "$src/schemas" "$out/schemas"
  cp -R "$src/tests/cases" "$out/cases"
  echo "Copied $exe and its templates to plugin/cli/$host, the schemas and the test cases to plugin/cli"
  exit 0
fi

# Release archives: the newest version in the folder.
version="$(ls -d "$src"/fsm-*-*/ 2>/dev/null | sed -E 's|.*/fsm-([0-9][0-9.]*)-.*|\1|' | sort -V | tail -1 || true)"
if [ -z "$version" ]; then
  echo "No fsm-cli clone or unpacked fsm-<version>-<platform> release archive found in $src. Download them from https://github.com/vincedupuis/fsm-cli/releases." >&2
  exit 1
fi
if $all; then
  platforms="$(ls -d "$src"/fsm-"$version"-*/ | sed -E "s|.*/fsm-$version-||; s|/$||")"
else
  platforms="$host"
fi
rm -rf "$out"
for p in $platforms; do
  dist="$src/fsm-$version-$p"
  exe="$(exe_of "$p")"
  if [ ! -f "$dist/$exe" ]; then
    echo "No $exe in $dist." >&2
    exit 1
  fi
  mkdir -p "$out/$p"
  cp "$dist/$exe" "$out/$p/"
  cp -R "$dist/templates" "$out/$p/templates"
  echo "Copied fsm $version ($p) to plugin/cli/$p"
done
for part in schemas cases; do
  if [ -d "$src/$part" ]; then
    cp -R "$src/$part" "$out/$part"
    echo "Copied $part to plugin/cli/$part"
  else
    echo "No $part/ in $src: unpack fsm-$part-$version.tar.gz there." >&2
    exit 1
  fi
done
