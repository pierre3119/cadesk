#!/usr/bin/env bash
# Construit l'application, puis assemble app_clean/ : un dossier autonome, à déplacer ou à copier où l'on veut,
# qui contient tout ce qu'il faut pour lancer CaDesk sous Linux (start.sh) comme sous Windows (start.bat) —
# le programme, ses bibliothèques, Java pour les deux systèmes et le guide d'utilisation.
#   ./package.sh               construction complète (tests compris), puis assemblage
#   ./package.sh -DskipTests   tout argument est transmis à Maven
# Les données d'un app_clean déjà utilisé (app_clean/data) sont conservées : seul le programme est remplacé.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
./build.sh "$@"
. scripts/jdk.sh
cadesk_ensure_jdk windows

APP=target/app
OUT=app_clean
if [ ! -f "$APP/cadesk.jar" ] || [ ! -d "$APP/lib/linux" ] || [ ! -d "$APP/lib/windows" ]; then
  echo "La distribution est incomplète dans $APP : rien n'a été assemblé." >&2
  exit 1
fi

echo "Assemblage de $OUT/…"
mkdir -p "$OUT"
rm -rf "$OUT/cadesk.jar" "$OUT/lib" "$OUT/jdk" "$OUT/docs" "$OUT/start.sh" "$OUT/start.bat"

cp "$APP/cadesk.jar" "$OUT/cadesk.jar"
cp -R "$APP/lib" "$OUT/lib"

# Java, pour chaque système : le JDK embarqué du projet, sans ce qui ne sert qu'à développer (sources, en-têtes C,
# pages de manuel, table des symboles du compilateur). Les liens symboliques sont remplacés par leur cible, pour
# que le dossier survive à une copie par un disque ou une archive qui ne les connaît pas.
mkdir "$OUT/jdk"
for os in linux-x64 windows-x64; do
  cp -RLp "jdk/$os" "$OUT/jdk/$os"
  rm -rf "$OUT/jdk/$os/include" "$OUT/jdk/$os/man" "$OUT/jdk/$os/jmods" \
    "$OUT/jdk/$os/lib/src.zip" "$OUT/jdk/$os/lib/ct.sym" "$OUT/jdk/$os/.version"
done

# Les lanceurs du dossier autonome, avec les fins de ligne de leur système quel que soit celui sur lequel le projet
# a été récupéré : un script Linux n'en supporte pas d'autres, un fichier .bat non plus.
sed -e 's/\r$//' scripts/standalone/start.sh > "$OUT/start.sh"
chmod +x "$OUT/start.sh"
sed -e 's/\r$//' -e 's/$/\r/' scripts/standalone/start.bat > "$OUT/start.bat"

cp -R docs "$OUT/docs"

echo "Prêt : $OUT/ ($(du -sh "$OUT" | cut -f1))."
echo "  Linux   : $OUT/start.sh"
echo "  Windows : $OUT\\start.bat"
