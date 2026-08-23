#!/usr/bin/env bash
# Dev loop: builds the plugin and installs it into a local SQuirreL SQL Client's
# plugins directory. Quit SQuirreL before running this if it's currently open.
set -euo pipefail

cd "$(dirname "$0")/.."

SQUIRREL_APP="${SQUIRREL_APP:-/Applications/SQuirreLSQL.app}"
PLUGINS_DIR="$SQUIRREL_APP/Contents/MacOS/plugins"

if [ ! -d "$PLUGINS_DIR" ]; then
  echo "error: plugins directory not found at $PLUGINS_DIR" >&2
  echo "Set SQUIRREL_APP to your SQuirreL SQL Client.app location and retry." >&2
  exit 1
fi

mvn -q package

rm -rf "$PLUGINS_DIR/influxdb"
cp target/plugin-dist/influxdb.jar "$PLUGINS_DIR/influxdb.jar"
cp -R target/plugin-dist/influxdb "$PLUGINS_DIR/influxdb"

echo "Installed influxdb plugin into $PLUGINS_DIR"
echo "Restart SQuirreL SQL Client to load it."
