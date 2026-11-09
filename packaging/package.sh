#!/usr/bin/env bash
# Gera o app do Overlook com runtime Java embutido (jlink + jpackage) para o sistema atual.
#
#   packaging/package.sh <versão> <plataforma>
#   plataforma: windows-x64 | linux-x64 | macos-x64 | macos-arm64
#
# Saída em dist/: overlook-<plataforma>.zip (ou .tar.gz no Linux) e o .sha256 correspondente.
# Quem baixa não precisa ter Java instalado.
set -euo pipefail

VERSION="${1:?versão, ex.: 0.1.0}"
PLATFORM="${2:?plataforma, ex.: linux-x64}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

rm -rf build/jpackage dist
mkdir -p build/jpackage/input dist

mvn -B -q -Drevision="$VERSION" verify
cp target/overlook.jar build/jpackage/input/

# Módulos do JDK que o app realmente usa, mais jdk.unsupported (Jetty/Kotlin) e
# jdk.localedata para os formatos em pt-BR.
MODULES="$(jdeps --ignore-missing-deps --print-module-deps --multi-release 21 target/overlook.jar)"
MODULES="$MODULES,jdk.unsupported,jdk.localedata"

# O macOS não aceita versão de app começando com 0; o número de verdade continua no
# nome do arquivo, no manifesto e em "overlook --version".
APP_VERSION="$VERSION"
if [[ "$PLATFORM" == macos-* && "$VERSION" == 0.* ]]; then
  APP_VERSION="1.${VERSION#0.}"
fi

COMMON=(
  --type app-image
  --name Overlook
  --app-version "$APP_VERSION"
  --vendor "Gabriel Serignolli"
  --description "Painel local de Web Analytics da Vercel"
  --input build/jpackage/input
  --main-jar overlook.jar
  --main-class dev.overlook.App
  --add-modules "$MODULES"
  --jlink-options "--strip-debug --no-man-pages --no-header-files --compress=zip-6"
  --java-options "-Dfile.encoding=UTF-8"
  --java-options "-Xmx256m"
  --dest build/jpackage/out
)

case "$PLATFORM" in
  windows-*)
    jpackage "${COMMON[@]}" --icon packaging/icons/overlook.ico
    (cd build/jpackage/out && powershell -NoProfile -Command \
      "Compress-Archive -Path Overlook -DestinationPath '../../../dist/overlook-$PLATFORM.zip' -Force")
    ARCHIVE="overlook-$PLATFORM.zip"
    ;;
  macos-*)
    jpackage "${COMMON[@]}" --icon packaging/icons/overlook.icns --mac-package-identifier dev.overlook.app
    (cd build/jpackage/out && ditto -c -k --keepParent Overlook.app "$ROOT/dist/overlook-$PLATFORM.zip")
    ARCHIVE="overlook-$PLATFORM.zip"
    ;;
  linux-*)
    jpackage "${COMMON[@]}" --icon packaging/icons/overlook.png
    tar -C build/jpackage/out -czf "dist/overlook-$PLATFORM.tar.gz" Overlook
    ARCHIVE="overlook-$PLATFORM.tar.gz"
    ;;
  *)
    echo "plataforma desconhecida: $PLATFORM" >&2
    exit 2
    ;;
esac

cd dist
if command -v sha256sum >/dev/null; then
  sha256sum "$ARCHIVE" > "$ARCHIVE.sha256"
else
  shasum -a 256 "$ARCHIVE" > "$ARCHIVE.sha256"
fi
ls -la
