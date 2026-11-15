#!/usr/bin/env bash
# Signs the freshly built mod jar and uploads it to your relay so players auto-update.
#   ./gradlew build
#   deploy/publish-update.sh <server-ip>
# Needs your release key at ~/.vcraft/release-key.json and the vcraft_relay SSH key.
set -euo pipefail
cd "$(dirname "$0")/.."

SERVER="${1:?usage: deploy/publish-update.sh <server-ip>}"
KEY="${VCRAFT_RELEASE_KEY:-$HOME/.vcraft/release-key.json}"
SSH_KEY="${VCRAFT_SSH_KEY:-$HOME/.ssh/vcraft_relay}"
VERSION=$(grep '^mod_version=' gradle.properties | cut -d= -f2)
JAR="build/libs/vcraft-$VERSION.jar"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

[ -f "$JAR" ] || { echo "Build first: $JAR is missing"; exit 1; }
[ -f "$KEY" ] || { echo "Release key not found at $KEY"; exit 1; }

OUT=$(mktemp -d)
"$JAVA" -cp "$(ls relay/build/libs/vcraft-relay-*.jar | head -1)" dev.vcraft.relay.ReleaseTool sign "$KEY" "$JAR" "$VERSION" "$OUT"
scp -i "$SSH_KEY" "$OUT/vcraft-$VERSION.jar" "$OUT/update.json" "root@$SERVER:/opt/vcraft/www/vcraft/"
ssh -i "$SSH_KEY" "root@$SERVER" "chown -R vcraft: /opt/vcraft/www"
echo "Published VCraft $VERSION. Players get it the next time they connect."
