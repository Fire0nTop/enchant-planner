# Changelog

Download the jar that matches your Minecraft version:
`enchant-planner-<version>+26.2.jar` for Minecraft 26.2, `enchant-planner-<version>+26.3.jar`
for Minecraft 26.3. Both need Fabric Loader 0.19.5+ and Fabric API.

## 1.0.0

First release, for Minecraft 26.2 and 26.3 (Fabric).

- Planner panels beside the anvil: choose the item and enchantments, see the cheapest order.
- Solver ported from iamcal/enchant-order, checked against 397 reference cases.
- Optimize for least XP or lowest repair penalty.
- Enchantments grouped by type, exclusive ones kept together.
- The item placed in the anvil is selected automatically; the current step is highlighted.
- Enchantments read from the game registry (datapack and modded enchantments work).
- Hidden by default; toggle with the book button above the anvil.
- English and German translations.
