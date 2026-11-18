#!/usr/bin/env bash
# Lance CaDesk sous Linux avec le JDK embarqué dans le projet.
# Au premier lancement, l'application est construite automatiquement (./build.sh pour reconstruire ensuite).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
. scripts/jdk.sh

APP=target/app
if [ ! -f "$APP/cadesk.jar" ]; then
  echo "Première utilisation : construction de l'application…"
  ./build.sh -DskipTests
fi

exec "$JAVA_HOME/bin/java" \
  --module-path "$APP/cadesk.jar:$APP/lib:$APP/lib/linux" \
  --add-modules copilot.sdk.runtime \
  --enable-native-access=javafx.graphics,org.xerial.sqlitejdbc \
  -Dcadesk.home="$PWD" \
  -m cadesk/io.github.pierre3119.cadesk.ui.Main "$@"
