#!/usr/bin/env bash
# Prints the CHANGELOG.md section for one version: release-notes.sh 1.0.1
set -euo pipefail
version="$1"
notes=$(awk -v v="## $version" '$0 == v {found=1; next} found && /^## / {exit} found {print}' CHANGELOG.md)
if [ -z "$(echo "$notes" | tr -d '[:space:]')" ]; then
	echo "CHANGELOG.md has no '## $version' section" >&2
	exit 1
fi
echo "$notes" | sed -e '/./,$!d'
