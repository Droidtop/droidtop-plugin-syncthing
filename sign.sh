#!/usr/bin/env bash
# Signs and packages an already-built payload (build.sh's build/: manifest.json, classes.jar and lib/) into
# droidtop.syncthing.droidplugin.tar.xz.
#
# The only script that touches the plugin key. CI runs it in its own job with the PLUGIN_SIGNING_KEY secret written
# to a 600 temp file (PLUGIN_SIGNING_KEY is that file's PATH) and PLUGIN_SIGNING_CERT, this key's leaf certificate
# from droidtop's signing root, packaged as origin.cert. The key is never committed.
set -euo pipefail
JWS_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$(dirname "$0")"

: "${PLUGIN_SIGNING_KEY:?set to the plugin's EC private key PEM}"
: "${BUNDLE_DIR:=build}"

for f in manifest.json classes.jar lib/arm64-v8a/libsyncthing.so lib/x86_64/libsyncthing.so; do
  test -f "$BUNDLE_DIR/$f" || { echo "missing $BUNDLE_DIR/$f: run build.sh first" >&2; exit 1; }
done

openssl dgst -sha256 -sign "$PLUGIN_SIGNING_KEY" "$BUNDLE_DIR/manifest.json" | base64 -w0 > "$BUNDLE_DIR/manifest.sig"

# Certificate-format signature (droidtop docs/SPEC.md 12a): a detached JWS over the exact manifest.json bytes, the
# leaf certificate in x5c. manifest.sig stays for older droidtop builds.
JWS_FILE=
if [ -n "${PLUGIN_SIGNING_CERT:-}" ]; then
  python3 -c 'import cryptography' 2>/dev/null || python3 -m pip install --quiet --user --break-system-packages 'cryptography>=42'
  python3 "$JWS_DIR/sign_jws.py" droidtop-plugin+jws "$BUNDLE_DIR/manifest.json" "$PLUGIN_SIGNING_KEY" "$PLUGIN_SIGNING_CERT" > "$BUNDLE_DIR/manifest.jws"
  JWS_FILE=manifest.jws
fi

rm -f "$BUNDLE_DIR/origin.cert"
CERT_FILE=
if [ -n "${PLUGIN_SIGNING_CERT:-}" ]; then
  test -f "$PLUGIN_SIGNING_CERT" || { echo "PLUGIN_SIGNING_CERT is not a file" >&2; exit 1; }
  cp "$PLUGIN_SIGNING_CERT" "$BUNDLE_DIR/origin.cert"
  CERT_FILE=origin.cert
fi

tar -C "$BUNDLE_DIR" --sort=name -cf - manifest.json manifest.sig $JWS_FILE $CERT_FILE classes.jar \
  lib/arm64-v8a/libsyncthing.so lib/x86_64/libsyncthing.so | xz -9e -T0 > droidtop.syncthing.droidplugin.tar.xz

echo "Signed droidtop.syncthing.droidplugin.tar.xz"
sha256sum droidtop.syncthing.droidplugin.tar.xz
ls -l droidtop.syncthing.droidplugin.tar.xz
