#!/usr/bin/env bash
# Uploads one jar as a new Modrinth version.
# Usage: modrinth-upload.sh <mod version> <minecraft version> <jar>
# Needs MODRINTH_TOKEN and MODRINTH_PROJECT (project id or slug) in the environment.
set -euo pipefail
mod_version="$1"
mc="$2"
jar="$3"
api=https://api.modrinth.com/v2
agent="Fire0nTop/enchant-planner (GitHub Actions)"

# MODRINTH_DRY_RUN=1 prints the request instead of sending it (used by CI on every push)
dry_run="${MODRINTH_DRY_RUN:-0}"

if [ "$dry_run" = 1 ]; then
	project_id=DRYRUN
else
	project_id=$(curl -sS --fail-with-body -H "User-Agent: $agent" "$api/project/$MODRINTH_PROJECT" | jq -r .id)
fi

jq -n \
	--arg project "$project_id" \
	--arg name "Enchant Planner $mod_version for $mc" \
	--arg number "$mod_version+$mc" \
	--arg mc "$mc" \
	--rawfile changelog <(.github/scripts/release-notes.sh "$mod_version") \
	'{
		project_id: $project,
		name: $name,
		version_number: $number,
		changelog: $changelog,
		game_versions: [$mc],
		loaders: ["fabric"],
		version_type: "release",
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

curl -sS --fail-with-body -X POST "$api/version" \
	-H "Authorization: $MODRINTH_TOKEN" \
	-H "User-Agent: $agent" \
	-F "data=<modrinth-version.json;type=application/json" \
	-F "file=@$jar;type=application/java-archive" \
	| jq -r '"Published \(.version_number): https://modrinth.com/mod/\(.project_id)/version/\(.id)"'
