#!/usr/bin/env bash
# Siren prototype helper.  ./siren.sh up | down | reset | logs | url | apk | smoke
set -euo pipefail
cd "$(dirname "$0")"

lan_ip() { ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1)}'; }

case "${1:-up}" in
  up)
    docker compose up -d --build
    echo "Waiting for the stack…"
    for _ in $(seq 90); do curl -sf http://localhost:8080/api/public/info >/dev/null && break; sleep 2; done
    echo
    echo "  Admin panel:  http://localhost:8080   (admin / siren)"
    echo "  Phones:       http://$(lan_ip):8080   (same Wi-Fi)"
    ;;
  down)  docker compose down ;;
  reset) docker compose down -v && "$0" up ;;   # wipes the database: fresh demo data and keys
  logs)  docker compose logs -f backend ;;
  url)   echo "http://$(lan_ip):8080" ;;
  apk)
    export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
    (cd mobile && ./gradlew assembleDebug)
    mkdir -p dist && cp mobile/app/build/outputs/apk/debug/app-debug.apk dist/siren.apk
    echo "APK: dist/siren.apk   (install: adb install -r dist/siren.apk)"
    ;;
  *) echo "usage: $0 up|down|reset|logs|url|apk|smoke"; exit 1 ;;
esac
