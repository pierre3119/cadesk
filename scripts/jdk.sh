#!/usr/bin/env bash
# JDK embarqué : garantit la présence du JDK du projet (jdk/<os>-x64) et pointe JAVA_HOME dessus.
#
#   . scripts/jdk.sh                 (depuis un autre script) -> prépare le JDK Linux et exporte JAVA_HOME
#   scripts/jdk.sh provision [linux|windows|all]             -> télécharge le ou les JDK dans le projet
#
# Le téléchargement n'a lieu que si le JDK est absent ou d'une autre version que celle de jdk.env ;
# l'archive est vérifiée par SHA-256 avant d'être décompressée.

CADESK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../jdk.env
. "$CADESK_ROOT/jdk.env"

cadesk_ensure_jdk() {
  local os="$1" url sha dir archive tmp inner
  case "$os" in
    linux)   url="$JDK_LINUX_URL";   sha="$JDK_LINUX_SHA256" ;;
    windows) url="$JDK_WINDOWS_URL"; sha="$JDK_WINDOWS_SHA256" ;;
    *) echo "Système inconnu : $os (attendu : linux ou windows)" >&2; return 2 ;;
  esac
  dir="$CADESK_ROOT/jdk/$os-x64"
  # (le fichier .version peut avoir été écrit sous Windows, avec un retour chariot)
  if [ "$(tr -d '\r\n' 2>/dev/null < "$dir/.version")" = "$JDK_VERSION" ]; then
    return 0
  fi
  if [ "$(uname -m)" != "x86_64" ]; then
    echo "Seule l'architecture x86_64 est prévue (détecté : $(uname -m))." >&2; return 1
  fi
  command -v curl >/dev/null || { echo "curl est requis pour télécharger le JDK." >&2; return 1; }

  mkdir -p "$CADESK_ROOT/jdk/.download"
  archive="$CADESK_ROOT/jdk/.download/${url##*/}"
  echo "JDK $JDK_VERSION ($os) absent du projet : téléchargement…" >&2
  curl -fL --retry 3 -sS -o "$archive" "$url" || { echo "Téléchargement impossible : $url" >&2; return 1; }
  if ! echo "$sha  $archive" | sha256sum --check --status; then
    echo "Empreinte SHA-256 inattendue pour $archive : archive supprimée, rien n'a été installé." >&2
    rm -f "$archive"; return 1
  fi

  tmp="$CADESK_ROOT/jdk/.download/extract-$os"
  rm -rf "$tmp"; mkdir -p "$tmp"
  case "$archive" in
    *.tar.gz) tar -xzf "$archive" -C "$tmp" --strip-components=1 || return 1 ;;
    *.zip)
      command -v unzip >/dev/null || { echo "unzip est requis pour décompresser le JDK Windows." >&2; return 1; }
      unzip -q "$archive" -d "$tmp" || return 1
      # l'archive contient un unique dossier jdk-<version> : on remonte son contenu d'un niveau
      inner="$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
      [ -n "$inner" ] || { echo "Archive inattendue : $archive" >&2; return 1; }
      mv "$inner" "$tmp.inner" && rmdir "$tmp" && mv "$tmp.inner" "$tmp" || return 1 ;;
  esac
  rm -rf "$dir"
  mv "$tmp" "$dir" && echo "$JDK_VERSION" > "$dir/.version" && rm -f "$archive"
  rmdir "$CADESK_ROOT/jdk/.download" 2>/dev/null || true
  echo "JDK $JDK_VERSION ($os) installé dans jdk/$os-x64" >&2
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  # exécution directe
  set -u
  [ "${1:-}" = "provision" ] || { echo "Usage : scripts/jdk.sh provision [linux|windows|all]" >&2; exit 2; }
  case "${2:-linux}" in
    all) cadesk_ensure_jdk linux && cadesk_ensure_jdk windows ;;
    *)   cadesk_ensure_jdk "$2" ;;
  esac
else
  # script « sourcé » : JDK Linux prêt + JAVA_HOME
  cadesk_ensure_jdk linux || return 1
  export JAVA_HOME="$CADESK_ROOT/jdk/linux-x64"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
