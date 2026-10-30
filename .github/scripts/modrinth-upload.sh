#!/usr/bin/env bash
# Uploads one jar as a new Modrinth version.
# Usage: modrinth-upload.sh <mod version> <minecraft version> <jar>
# Needs MODRINTH_TOKEN and MODRINTH_PROJECT (project id or slug) in the environment.
# Versions are marked client-only: the mod runs on the client and needs nothing on the server.
set -euo pipefail
mod_version="$1"
mc="$2"
jar="$3"
api=https://api.modrinth.com/v2
agent="Fire0nTop/enchant-planner (GitHub Actions)"

# MODRINTH_DRY_RUN=1 prints the request instead of sending it (used by CI on every push)
dry_run="${MODRINTH_DRY_RUN:-0}"

# request <out file> <curl args...>: prints Modrinth's error and fails on any HTTP error
request() {
	local out="$1"
	shift
	local status
	status=$(curl -sS -o "$out" -w '%{http_code}' -H "User-Agent: $agent" "$@")
	if [ "$status" -ge 400 ]; then
		echo "::error::Modrinth answered HTTP $status: $(cat "$out")"
		return 1
	fi
}

if [ "$dry_run" = 1 ]; then
	project_id=DRYRUN
elif [[ "$MODRINTH_PROJECT" =~ ^[A-Za-z0-9]{8}$ ]]; then
	project_id="$MODRINTH_PROJECT" # already an id
else
	# a slug: look up the id (with the token, since the project may not be public yet)
	if ! request project.json -H "Authorization: $MODRINTH_TOKEN" "$api/project/$MODRINTH_PROJECT"; then
		echo "::error::Could not find Modrinth project '$MODRINTH_PROJECT'. While the project is not public, set the MODRINTH_PROJECT variable to the project ID (project page → ⋯ → Copy ID) or give the token the 'Read projects' scope."
		exit 1
	fi
	project_id=$(jq -r .id project.json)
fi

jq -n \
	--arg project "$project_id" \
	--arg name "Enchant Planner $mod_version for $mc" \
	--arg number "$mod_version+$mc" \
	--arg mc "$mc" \
	--arg type "$(.github/scripts/release-type.sh)" \
	--rawfile changelog <(.github/scripts/release-notes.sh "$mod_version") \
	'{
		project_id: $project,
		name: $name,
		version_number: $number,
		changelog: $changelog,
		game_versions: [$mc],
		loaders: ["fabric"],
		environment: "client_only",
		version_type: $type,
		featured: true,
		dependencies: [{project_id: "P7dR8mSH", dependency_type: "required"}],
		file_parts: ["file"],
		primary_file: "file"
	}' > modrinth-version.json

if [ "$dry_run" = 1 ]; then
	jq . modrinth-version.json
	echo "Dry run: would upload $jar ($(wc -c < "$jar") bytes)"
	exit 0
fi

request version.json -X POST "$api/version" \
	-H "Authorization: $MODRINTH_TOKEN" \
	-F "data=<modrinth-version.json;type=application/json" \
	-F "file=@$jar;type=application/java-archive"
jq -r '"Published \(.version_number): https://modrinth.com/mod/\(.project_id)/version/\(.id)"' version.json
