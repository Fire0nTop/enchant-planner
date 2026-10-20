#!/usr/bin/env bash
# Prints the release channel from gradle.properties: alpha, beta or release.
set -euo pipefail
type=$(grep '^release_type=' gradle.properties | cut -d= -f2 | tr -d '[:space:]')
case "$type" in
	alpha | beta | release) echo "$type" ;;
	*)
		echo "release_type in gradle.properties must be alpha, beta or release (got '$type')" >&2
		exit 1
		;;
esac
