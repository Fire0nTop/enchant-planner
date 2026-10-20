# Enchant Planner

A client-side Fabric mod for Minecraft **26.2** that shows the cheapest order to combine
enchantment books, right next to the vanilla anvil.

Pick the item and the enchantments you want; the mod works out which book goes on what, in which
order, so you spend as few experience levels as possible and never hit *Too Expensive!*.
The search is a Java port of the well-known web tool
[iamcal/enchant-order](https://github.com/iamcal/enchant-order) and gives the same results.

- Works in singleplayer and on any server: nothing is installed server-side.
- Reads enchantments from the game itself, so datapack and modded enchantments are supported.
- English and German translations.

## Requirements

| | Version |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.5 or newer |
| Fabric API | 0.161.0+26.2 or newer |
| Java | 25 |

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.2.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) for 26.2.
3. Put `enchant-planner-<version>.jar` (from the releases page, or built yourself, see below)
   and Fabric API into your `.minecraft/mods` folder.

## Usage

Open any anvil. The planner starts hidden; click the **enchanted book button** above the anvil to
show or hide it.

### Left panel: what you want

- **Item:** cycle with `<` / `>`, or click the item to choose from a grid. Putting an item into
  the anvil's first slot selects it automatically. Choose the **Book** to plan one combined book.
- **Enchantments:** grouped by type (Armor, Melee Weapons, Ranged & Trident, Tools, Fishing,
  General, Curses). Enchantments that exclude each other, such as the four kinds of Protection,
  sit together. Tick the ones you want and set the level with `-` / `+`. Enchantments that clash
  with your selection are greyed out; hover them to see why.
- **Optimize: XP** spends the least experience. **Optimize: Repair** keeps the final prior-work
  penalty as low as possible instead, so the item stays cheap to repair later.
- **Clear** removes the selection for the current item.

### Right panel: the best order

- The total cost in levels and XP, and the most expensive single step.
- One entry per anvil operation: **left slot + right slot** and its cost. Hover a step for details
  (both slots, cost, repair penalty afterwards).
- The step that matches what is in the anvil right now is outlined **green**.
- Costs turn **red** when you don't have enough levels (never in creative).
- Click a step to mark it as done.

On small windows or large GUI scales there is no room beside the anvil, so the panels are drawn
over it; use the book button to hide them while you work.

See [docs/how-it-works.md](docs/how-it-works.md) for the anvil maths and the search.

## Limitations

- The plan assumes a **fresh item and fresh books**: enchantments or anvil uses the item already
  has are not taken into account.
- At most **15 enchantments** at once (that takes about two seconds; the search runs in the
  background, so the game doesn't freeze).
- Renaming in the same step costs one extra level, which the plan doesn't include.

## Building from source

```bash
./gradlew build
```

The jar ends up in `build/libs/`. The build also runs the solver tests, which compare the Java
solver with 397 cases produced by the original `work.js`.

```bash
./gradlew runClient
```

starts a development client with the mod installed.

## Credits and license

- Enchant Planner is released under the [MIT License](LICENSE).
- The combining algorithm is ported from
  [iamcal/enchant-order](https://github.com/iamcal/enchant-order) by Cal Henderson, also MIT
  ([LICENSE-enchant-order.txt](LICENSE-enchant-order.txt)).
