#!/bin/sh
set -eu
dbx_source=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
: "${DBX_INSTALL_DIR:=$HOME/.local/share/dbx}"
: "${DBX_BIN_DIR:=$HOME/.local/bin}"
case "$DBX_INSTALL_DIR$DBX_BIN_DIR" in *"'"*|*'
'*) echo 'Install paths must not contain quotes or newlines.' >&2; exit 1;; esac
mkdir -p "$DBX_INSTALL_DIR" "$DBX_BIN_DIR"
dbx_target="$DBX_INSTALL_DIR/0.1.0"
[ ! -e "$dbx_target" ] || { echo "Already installed: $dbx_target" >&2; exit 1; }
cp -R "$dbx_source" "$dbx_target"
dbx_target=$(CDPATH='' cd -- "$dbx_target" && pwd)
dbx_launcher_tmp=$(mktemp "$DBX_BIN_DIR/.dbx.XXXXXX")
trap 'rm -f "$dbx_launcher_tmp"' EXIT HUP INT TERM
printf '#!/bin/sh\nexec '\''%s/bin/dbx'\'' "$@"\n' "$dbx_target" > "$dbx_launcher_tmp"
chmod +x "$dbx_launcher_tmp"
mv "$dbx_launcher_tmp" "$DBX_BIN_DIR/dbx"
printf 'Installed %s/dbx\nAdd %s to PATH if needed.\n' "$DBX_BIN_DIR" "$DBX_BIN_DIR"
