#!/usr/bin/env bash
#
# Test harness for deploy/install-site/verify-published.sh. Serves the two entry points from a local
# loopback stub -- no network, no Vercel -- and drives the verifier against bodies that are correct,
# stale on one side, stale on the other, and missing.
#
# The two one-sided cases are the point of the harness. A verifier that checks only the CLI entry
# point passes case 2 and fails nothing, which is exactly the hole that let the quickstart entry point
# sit a release behind while the one that was watched looked fine. Each case here fails a verifier
# that drops the half it names.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
VERIFY="$HERE/verify-published.sh"

PASS=0; FAIL=0
ok()  { printf '  PASS  %s\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf '  FAIL  %s\n' "$1"; FAIL=$((FAIL + 1)); }

command -v python3 >/dev/null 2>&1 || { printf 'python3 is required for the loopback stub\n' >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"; [ -n "${STUB_PID:-}" ] && kill "$STUB_PID" 2>/dev/null' EXIT

# The tree side: what a deployment would upload.
EXPECTED="$TMP/expected"
mkdir -p "$EXPECTED"
printf 'CLI_VERSION="9.9.9"\n# quickstart\n'    > "$EXPECTED/quickstart.sh"
printf 'PINNED_VERSION="9.9.9"\n# installer\n'  > "$EXPECTED/install.sh"

# The served side: swapped per case by rewriting these two files.
SERVED="$TMP/served"
mkdir -p "$SERVED"

cat > "$TMP/stub.py" <<'PY'
import http.server, socketserver, sys, os

root, portfile = sys.argv[1], sys.argv[2]
ROUTES = {"/": "quickstart.sh", "/cli": "install.sh"}

class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        name = ROUTES.get(self.path)
        path = os.path.join(root, name) if name else None
        if not path or not os.path.exists(path):
            self.send_response(404); self.end_headers(); return
        body = open(path, "rb").read()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *a): pass

srv = socketserver.TCPServer(("127.0.0.1", 0), H)
open(portfile, "w").write(str(srv.server_address[1]))
srv.serve_forever()
PY

python3 "$TMP/stub.py" "$SERVED" "$TMP/port" &
STUB_PID=$!
for _ in $(seq 1 50); do [ -s "$TMP/port" ] && break; sleep 0.1; done
[ -s "$TMP/port" ] || { printf 'the loopback stub never came up\n' >&2; exit 1; }
BASE="http://127.0.0.1:$(cat "$TMP/port")"

serve_fresh_quickstart() { cp "$EXPECTED/quickstart.sh" "$SERVED/quickstart.sh"; }
serve_fresh_installer()  { cp "$EXPECTED/install.sh"    "$SERVED/install.sh"; }
serve_stale_quickstart() { printf 'CLI_VERSION="9.9.8"\n# quickstart\n'   > "$SERVED/quickstart.sh"; }
serve_stale_installer()  { printf 'PINNED_VERSION="9.9.8"\n# installer\n' > "$SERVED/install.sh"; }

run_verify() { sh "$VERIFY" "$BASE" "$EXPECTED" 2>&1; }

# --- 1. both entry points match -----------------------------------------------------------------
serve_fresh_quickstart; serve_fresh_installer
out="$(run_verify)"; rc=$?
if [ "$rc" -eq 0 ]; then ok "matching bodies on both entry points verify clean"
else bad "matching bodies were rejected (rc=$rc): $out"; fi

# --- 2. the quickstart entry point is stale -------------------------------------------------------
# Fails a verifier that watches only /cli -- the shape that actually shipped.
serve_stale_quickstart; serve_fresh_installer
out="$(run_verify)"; rc=$?
if [ "$rc" -ne 0 ] && grep -q "$BASE/ is not what this tree would deploy" <<<"$out"; then
  ok "a stale quickstart entry point is caught and named"
else bad "a stale quickstart entry point was not caught (rc=$rc): $out"; fi

# --- 3. the CLI entry point is stale --------------------------------------------------------------
serve_fresh_quickstart; serve_stale_installer
out="$(run_verify)"; rc=$?
if [ "$rc" -ne 0 ] && grep -q "$BASE/cli is not what this tree would deploy" <<<"$out"; then
  ok "a stale CLI entry point is caught and named"
else bad "a stale CLI entry point was not caught (rc=$rc): $out"; fi

# --- 4. the failure message names the two versions ------------------------------------------------
# A digest mismatch alone does not tell anyone what to do; the pins are what identify the skew.
if grep -q 'served  pin 9.9.8' <<<"$out" && grep -q 'tree    pin 9.9.9' <<<"$out"; then
  ok "the failure names the served pin and the tree's pin"
else bad "the failure does not name both pins: $out"; fi

# --- 5. a missing entry point is a failure, not a pass --------------------------------------------
serve_fresh_quickstart; rm -f "$SERVED/install.sh"
out="$(run_verify)"; rc=$?
if [ "$rc" -ne 0 ] && grep -q 'could not be fetched' <<<"$out"; then
  ok "an entry point that does not answer fails loudly"
else bad "a missing entry point did not fail (rc=$rc): $out"; fi

# --- 6. same pin, different body ------------------------------------------------------------------
# The case that separates this verifier from a version comparison. An installer fix that ships without
# a version bump leaves both sides reading the same pin while the served script is the old one, and a
# check built on the version number calls that healthy.
serve_fresh_quickstart
printf 'PINNED_VERSION="9.9.9"\n# installer, but an older body\n' > "$SERVED/install.sh"
out="$(run_verify)"; rc=$?
if [ "$rc" -ne 0 ]; then ok "a stale body carrying the current pin is still caught"
else bad "a stale body with a matching pin passed (rc=$rc): $out"; fi

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
