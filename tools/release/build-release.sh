#!/usr/bin/env bash
# Builds a FarmPulse release locally (AP-11.2).
#
#   tools/release/build-release.sh [--skip-tests]
#
# Output in release/ (git-ignored):
#   rpsim-backend-<v>.jar         backend (serves the frontend when started with --rpsim.web.static-dir=web)
#   farmpulse-web-<v>.zip         built Angular frontend (dist/frontend/browser)
#   FS25_RPSim.zip                FS25 mod (modDesc.xml in the ZIP root)
#   FarmPulse-<v>/ + .zip         ready-to-play bundle: jar + web/ + mod ZIP + start scripts + docs
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/release"
SKIP_TESTS=false
[[ "${1:-}" == "--skip-tests" ]] && SKIP_TESTS=true

log() { printf '\n==> %s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || { echo "missing tool: $1" >&2; exit 1; }; }
for t in java mvn node npm zip; do need "$t"; done

# ---------------------------------------------------------------- version (one project version, SemVer)
VERSION="$(awk '/<artifactId>rpsim-backend<\/artifactId>/{f=1} f && /<version>/{gsub(/.*<version>|<\/version>.*/,""); print; exit}' "$ROOT/backend/pom.xml")"
MOD_VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$ROOT/mod/FS25_RPSim/modDesc.xml" | head -1)"
FRONTEND_VERSION="$(node -p "require('$ROOT/frontend/package.json').version")"
[[ "$MOD_VERSION" == "$VERSION.0" ]] || { echo "modDesc.xml version $MOD_VERSION != $VERSION.0" >&2; exit 1; }
[[ "$FRONTEND_VERSION" == "$VERSION" ]] || { echo "frontend/package.json version $FRONTEND_VERSION != $VERSION" >&2; exit 1; }
grep -q "## \[$VERSION\]" "$ROOT/CHANGELOG.md" || { echo "CHANGELOG.md has no section [$VERSION]" >&2; exit 1; }
log "FarmPulse $VERSION"

rm -rf "$OUT" && mkdir -p "$OUT"

# ---------------------------------------------------------------- backend
log "backend (mvn package, tests: $([[ $SKIP_TESTS == true ]] && echo skipped || echo run))"
MVN_ARGS=(-B -q package)
[[ $SKIP_TESTS == true ]] && MVN_ARGS+=(-DskipTests)
(cd "$ROOT/backend" && rm -f target/rpsim-backend-*.jar && mvn "${MVN_ARGS[@]}")
cp "$ROOT/backend/target/rpsim-backend-$VERSION.jar" "$OUT/"

# ---------------------------------------------------------------- frontend
log "frontend (npm ci, build)"
(cd "$ROOT/frontend" && npm ci --no-audit --no-fund && npm run build)
WEB="$ROOT/frontend/dist/frontend/browser"
[[ -f "$WEB/index.html" ]] || { echo "frontend build missing $WEB/index.html" >&2; exit 1; }
(cd "$WEB" && zip -q -r "$OUT/farmpulse-web-$VERSION.zip" .)

# ---------------------------------------------------------------- mod
log "mod (FS25_RPSim.zip)"
[[ -f "$ROOT/mod/FS25_RPSim/icon_RPSim.dds" ]] || { echo "mod icon missing" >&2; exit 1; }
(cd "$ROOT/mod/FS25_RPSim" && zip -q -r -X "$OUT/FS25_RPSim.zip" modDesc.xml icon_RPSim.dds src)
unzip -l "$OUT/FS25_RPSim.zip" | grep -q " modDesc.xml$" || { echo "modDesc.xml not in the ZIP root" >&2; exit 1; }

# ---------------------------------------------------------------- bundle
log "bundle FarmPulse-$VERSION"
B="$OUT/FarmPulse-$VERSION"
mkdir -p "$B/web"
cp "$OUT/rpsim-backend-$VERSION.jar" "$B/rpsim-backend.jar"
cp -r "$WEB/." "$B/web/"
cp "$OUT/FS25_RPSim.zip" "$B/"
cp "$ROOT/backend/application-local.yml.example" "$ROOT/LICENSE" "$ROOT/CHANGELOG.md" "$B/"
cp -r "$ROOT/docs/user-guide" "$B/anleitung"
mkdir -p "$B/screenshots" && cp "$ROOT"/docs/screenshots/*.png "$B/screenshots/" 2>/dev/null || true

cat > "$B/start.sh" <<'SH'
#!/usr/bin/env sh
# Starts FarmPulse (backend + web UI) - then open http://localhost:8080
cd "$(dirname "$0")" || exit 1
exec java -jar rpsim-backend.jar --spring.profiles.active=prod --rpsim.web.static-dir=web "$@"
SH
chmod +x "$B/start.sh"

printf '%s\r\n' \
  '@echo off' \
  'rem Startet FarmPulse (Backend + Oberflaeche) - danach http://localhost:8080 im Browser oeffnen' \
  'cd /d "%~dp0"' \
  'echo FarmPulse startet ... danach im Browser http://localhost:8080 oeffnen. Fenster offen lassen.' \
  'java -jar rpsim-backend.jar --spring.profiles.active=prod --rpsim.web.static-dir=web %*' \
  'pause' > "$B/start.bat"

cat > "$B/LIESMICH.txt" <<TXT
FarmPulse $VERSION - KI-Rollenspiel fuer Farming Simulator 25

1. FS25_RPSim.zip (ungeoeffnet) nach  Dokumente\\My Games\\FarmingSimulator2025\\mods\\  kopieren
   und im Spielstand aktivieren.
2. Java 21 installieren (https://adoptium.net), dann start.bat (Windows) bzw. start.sh starten.
3. Im Browser http://localhost:8080 oeffnen und das Onboarding durchlaufen.

Ausfuehrliche Anleitung: Ordner "anleitung" (installation.md, erster-spielstand.md, funktionen.md,
fehlerbehebung.md). Eigene Einstellungen: application-local.yml.example -> application-local.yml.
Fan-Projekt, kein offizielles GIANTS-Software-/Farming-Simulator-Produkt. Lizenz: MIT (LICENSE).
TXT

(cd "$OUT" && zip -q -r "FarmPulse-$VERSION.zip" "FarmPulse-$VERSION")

log "done"
ls -lh "$OUT" | sed 1d
