#!/usr/bin/env bash
# Lance CaDesk sous Linux.
# Ce dossier est autonome : il contient le programme, ses bibliothèques et Java (jdk/linux-x64), et peut être
# déplacé ou copié où l'on veut. Les données sont dans data/, à côté de ce script.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
JAVA_HOME="$PWD/jdk/linux-x64"

if [ -f "$JAVA_HOME/bin/java" ] && [ ! -x "$JAVA_HOME/bin/java" ]; then
  # une copie passée par un disque ou une archive qui ne garde pas les droits d'exécution les a retirés
  chmod +x "$JAVA_HOME"/bin/* "$JAVA_HOME/lib/jexec" "$JAVA_HOME/lib/jspawnhelper" 2>/dev/null || true
fi
if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "Java est introuvable ou ne peut pas être exécuté : $JAVA_HOME/bin/java" >&2
  echo "Ce dossier doit contenir cadesk.jar, lib/ et jdk/linux-x64/ : est-il complet ?" >&2
  exit 1
fi

exec "$JAVA_HOME/bin/java" \
  --module-path "cadesk.jar:lib:lib/linux" \
  --add-modules copilot.sdk.runtime \
  --enable-native-access=javafx.graphics,org.xerial.sqlitejdbc \
  -Dcadesk.home="$PWD" \
  -m cadesk/io.github.pierre3119.cadesk.ui.Main "$@"
