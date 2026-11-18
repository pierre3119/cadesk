#!/usr/bin/env bash
# Construit l'application avec le JDK embarqué : compilation, tests, puis distribution dans target/app.
#   ./build.sh               construction complète (tests compris)
#   ./build.sh -DskipTests   tout argument est transmis à Maven
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
. scripts/jdk.sh
exec ./mvnw --batch-mode --no-transfer-progress clean package "$@"
