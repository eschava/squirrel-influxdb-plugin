#!/usr/bin/env bash
# One-time setup: installs the SQuirreL SQL Client's core jar into the local Maven
# repository so it can be used as a `provided`-scope dependency when building this
# plugin. squirrel-sql.jar is not published on Maven Central, so it must be sourced
# from a local SQuirreL installation.
set -euo pipefail

SQUIRREL_APP="${SQUIRREL_APP:-/Applications/SQuirreLSQL.app}"
CORE_JAR="$SQUIRREL_APP/Contents/Resources/Java/squirrel-sql.jar"
GROUP_ID="net.sourceforge.squirrel-sql"
ARTIFACT_ID="squirrel-sql-core"
VERSION="5.1.0"

if [ ! -f "$CORE_JAR" ]; then
  echo "error: squirrel-sql.jar not found at $CORE_JAR" >&2
  echo "Set SQUIRREL_APP to your SQuirreL SQL Client.app location and retry." >&2
  exit 1
fi

mvn install:install-file \
  -Dfile="$CORE_JAR" \
  -DgroupId="$GROUP_ID" \
  -DartifactId="$ARTIFACT_ID" \
  -Dversion="$VERSION" \
  -Dpackaging=jar
