# Publishing to Modrinth

Releases are published by GitHub Actions (`.github/workflows/build.yml`). Pushing a tag such as
`v1.0.1` builds both jars and then:

- creates a GitHub release with both jars, and
- uploads one Modrinth version per Minecraft version (`1.0.1+26.2` for 26.2, `1.0.1+26.3` for
  26.3), marked client-only and as needing Fabric API, with that version's `CHANGELOG.md` section as its changelog.

The Modrinth step is skipped (with a notice) until the setup below is done.

## One-time setup

1. **Create the project** on [modrinth.com](https://modrinth.com/dashboard/projects): type *Mod*,
   name *Enchant Planner*, slug e.g. `enchant-planner`. Fill in the page using the text below,
   upload `docs/icon.png` as the icon, and set the license to **MIT**, client side **Required**,
   server side **Unsupported**. Modrinth reviews new projects before they go public; you can submit for review once the
   first version is uploaded.
2. **Create a personal access token** at
   [modrinth.com/settings/pats](https://modrinth.com/settings/pats) with only the
   **Create versions** scope. Copy it; Modrinth shows it once.
3. **Add it to GitHub:** in the repository go to *Settings → Secrets and variables → Actions*.
   - *Secrets* tab → **New repository secret**: name `MODRINTH_TOKEN`, value = the token.
   - *Variables* tab → **New repository variable**: name `MODRINTH_PROJECT`, value = the
     project ID (project page → ⋯ → **Copy ID**). The slug (e.g. `enchant-planner`) works too
     once the project is public, or earlier if the token also has the **Read projects** scope.

## Releasing a new version

1. Bump `mod_version` in `gradle.properties`. `release_type` in the same file sets the channel:
   `alpha` (current), `beta` or `release`. Alpha and beta versions are uploaded to that Modrinth
   channel and marked as *Pre-release* on GitHub.
2. Add a `## <version>` section at the top of `CHANGELOG.md` (the release fails without one).
3. Commit, push, then tag and push the tag. The tag must match `mod_version`:

```bash
git tag v1.0.1
```

```bash
git push origin v1.0.1
```

If a Modrinth upload fails (for example the token expired), the job log shows Modrinth's error.
Fix the cause, then open *Actions → Publish to Modrinth → Run workflow* and enter the tag (e.g.
`v1.0.1`). It uploads that GitHub release's jars using the current scripts.

## Suggested project description

> **Enchant Planner** shows the cheapest order to combine enchantment books, right next to the
> anvil.
>
> Pick the item and the enchantments you want; the mod works out which book goes on what and in
> which order, so you spend as few levels as possible and never hit *Too Expensive!*.
>
> - Panels beside the vanilla anvil, hidden until you click the book button
> - Picks up the item you put in the anvil and highlights the step you're on
> - Optimize for least XP, or for the lowest repair penalty
> - Reads enchantments from the game, so datapack and modded enchantments work
> - Client-side only: works on any server
> - English and German
>
> The combining algorithm is ported from
> [iamcal/enchant-order](https://github.com/iamcal/enchant-order) by Cal Henderson (MIT) and gives
> the same results as that web tool.
>
> Source code: https://github.com/Fire0nTop/enchant-planner
