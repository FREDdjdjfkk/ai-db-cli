#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
[ "$(uname -s)" = Darwin ] || { echo 'Build macOS packages on macOS.' >&2; exit 1; }
: "${JAVA_HOME:?Set JAVA_HOME to a redistributable JDK 17+ (e.g. Temurin)}"
version=0.1.0
arch=$(uname -m)
case "$arch" in arm64|x86_64) ;; *) echo 'Unsupported architecture' >&2; exit 1;; esac
mvn -B -s settings.xml "${@}" package
bundle="dist/dbx-$version-macos-$arch"
[ ! -e "$bundle" ] || { echo "Build output already exists: $bundle (remove it before rebuilding)" >&2; exit 1; }
mkdir -p "$bundle/lib" "$bundle/bin"
cp target/dbx.jar "$bundle/lib/"
cp README.md LICENSE THIRD_PARTY_NOTICES.md "$bundle/"
cp -R third_party "$bundle/third_party"
cp scripts/install.sh "$bundle/install.sh"
cp scripts/dbx "$bundle/bin/dbx"
"$JAVA_HOME/bin/jlink" --add-modules java.se,jdk.crypto.ec,jdk.unsupported --strip-debug --no-header-files --no-man-pages --output "$bundle/runtime"
chmod +x "$bundle/bin/dbx" "$bundle/install.sh"
"$bundle/bin/dbx" capabilities
tar -czf "$bundle.tar.gz" -C dist "$(basename "$bundle")"
(cd dist && shasum -a 256 "$(basename "$bundle").tar.gz") > "$bundle.tar.gz.sha256"
echo "Built $bundle.tar.gz"
