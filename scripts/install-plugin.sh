#!/bin/sh
# Installs the Azure DevOps PR Review plugin into Rider (macOS and Linux).
#
#   scripts/install-plugin.sh                  latest GitHub release (needs curl)
#   scripts/install-plugin.sh --version v0.1.1 a specific release
#   scripts/install-plugin.sh --zip FILE       a downloaded zip
#   scripts/install-plugin.sh --build          build from this checkout first
#
# Options:
#   --rider PATH        Rider installation (default: /Applications/Rider.app, then ~/Applications/Rider.app)
#   --plugins-dir DIR   install here instead of the directory found from the Rider installation
#                       (also RIDER_PLUGINS_DIR)
#
# Restart Rider afterwards. Installing again replaces the installed version.
set -eu

REPO=antomys/rider-azdo-pr-review
PLUGIN=rider-azdo-pr-review
# The plugin is built for Rider 2026.2 (build 262) only.
DATA_DIR=Rider2026.2

ROOT=$(cd "$(dirname "$0")/.." && pwd)
version=""
zip=""
build=false
rider=""
plugins_dir=${RIDER_PLUGINS_DIR:-}

fail() { echo "error: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --version) version=${2:?--version needs a tag}; shift 2 ;;
        --zip) zip=${2:?--zip needs a file}; shift 2 ;;
        --build) build=true; shift ;;
        --rider) rider=${2:?--rider needs a path}; shift 2 ;;
        --plugins-dir) plugins_dir=${2:?--plugins-dir needs a directory}; shift 2 ;;
        -h|--help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) fail "unknown option: $1 (see --help)" ;;
    esac
done

if [ -z "$rider" ]; then
    for candidate in /Applications/Rider.app "$HOME/Applications/Rider.app"; do
        [ -d "$candidate" ] && { rider=$candidate; break; }
    done
fi

# Where Rider looks for plugins: <config>/<dataDirectoryName>/plugins on macOS, <data>/<dataDirectoryName> on Linux.
if [ -z "$plugins_dir" ]; then
    case "$(uname -s)" in
        Darwin)
            info="$rider/Contents/Resources/product-info.json"
            [ -f "$info" ] || fail "Rider not found; pass --rider PATH or --plugins-dir DIR"
            name=$(sed -n 's/.*"dataDirectoryName": *"\([^"]*\)".*/\1/p' "$info" | head -n 1)
            [ "$name" = "$DATA_DIR" ] || fail "$rider is $name; this plugin needs Rider 2026.2 ($DATA_DIR)"
            plugins_dir="$HOME/Library/Application Support/JetBrains/$name/plugins"
            ;;
        Linux)
            plugins_dir="${XDG_DATA_HOME:-$HOME/.local/share}/JetBrains/$DATA_DIR"
            ;;
        *)
            fail "unsupported OS; on Windows use Settings | Plugins | Install Plugin from Disk"
            ;;
    esac
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

if $build; then
    [ -n "$zip" ] && fail "--build and --zip can't be combined"
    echo "Building the plugin..."
    if [ -d "$rider" ]; then
        (cd "$ROOT" && ./gradlew -q buildPlugin "-PplatformLocalPath=$rider")
    else
        (cd "$ROOT" && ./gradlew -q buildPlugin)
    fi
    zip=$(ls -t "$ROOT"/build/distributions/$PLUGIN-*.zip | head -n 1)
elif [ -z "$zip" ]; then
    command -v curl >/dev/null || fail "curl not found; install it or pass --zip FILE"
    echo "Downloading ${version:-the latest release} of $REPO..."
    if [ -n "$version" ]; then
        api="https://api.github.com/repos/$REPO/releases/tags/$version"
    else
        api="https://api.github.com/repos/$REPO/releases/latest"
    fi
    url=$(curl -fsSL "$api" | sed -n 's/.*"browser_download_url": *"\([^"]*\/'"$PLUGIN"'-[^"\/]*\.zip\)".*/\1/p' | head -n 1)
    [ -n "$url" ] || fail "no $PLUGIN zip found in ${version:-the latest release} of $REPO"
    zip="$work/${url##*/}"
    curl -fsSL -o "$zip" "$url"
fi

[ -f "$zip" ] || fail "zip not found: $zip"
unzip -q "$zip" -d "$work/unpacked"
ls "$work/unpacked/$PLUGIN/lib/$PLUGIN-"*.jar >/dev/null 2>&1 || fail "$zip doesn't contain the $PLUGIN plugin"

mkdir -p "$plugins_dir"
rm -rf "${plugins_dir:?}/$PLUGIN"
mv "$work/unpacked/$PLUGIN" "$plugins_dir/$PLUGIN"
installed=$(basename "$(ls "$plugins_dir/$PLUGIN/lib/$PLUGIN-"*.jar | grep -v searchableOptions | head -n 1)" .jar)
echo "Installed ${installed#"$PLUGIN-"} into $plugins_dir/$PLUGIN"

if pgrep -f "$DATA_DIR|Rider.app/Contents/MacOS/rider" >/dev/null 2>&1; then
    echo "Rider is running: restart it to load the plugin."
else
    echo "Start Rider, then open Settings | Tools | Azure DevOps PR Review to sign in."
fi
