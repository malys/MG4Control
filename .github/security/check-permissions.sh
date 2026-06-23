#!/usr/bin/env bash
# Permission-drift gate.
# Échoue si un manifeste déclare une uses-permission absente de l'allowlist.
# Sécurité : empêche l'ajout silencieux d'une permission (réseau / véhicule)
# sur une app qui tourne sur un véhicule en mouvement.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ALLOWLIST="$ROOT/.github/security/permission-allowlist.txt"

# Permissions autorisées (nettoyées des commentaires/vides)
mapfile -t allowed < <(grep -vE '^\s*(#|$)' "$ALLOWLIST" | tr -d '\r' | sed 's/[[:space:]]*$//')

declare -A allow_set=()
for p in "${allowed[@]}"; do allow_set["$p"]=1; done

fail=0
while IFS= read -r -d '' manifest; do
  # Extrait les noms de permission déclarés
  while read -r perm; do
    [ -z "$perm" ] && continue
    if [ -z "${allow_set[$perm]:-}" ]; then
      echo "::error file=$manifest::Permission non autorisée: $perm (ajoutez-la à permission-allowlist.txt après revue sécurité)"
      fail=1
    fi
  done < <(grep -oE 'android:name="[^"]+"' "$manifest" \
            | sed -E 's/.*"(.*)"/\1/' \
            | grep -E '^(android|com)\.' \
            | grep -iE 'permission')
done < <(find "$ROOT/app/src" -name AndroidManifest.xml -print0)

if [ "$fail" -ne 0 ]; then
  echo "❌ Permission-drift gate: au moins une permission non autorisée."
  exit 1
fi
echo "✅ Permission-drift gate: toutes les permissions sont autorisées."
