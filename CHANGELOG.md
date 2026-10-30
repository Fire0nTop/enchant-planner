# Changelog

## 1.2.0

- Plans from the item you put in the anvil: enchantments it already has are kept for free (shown
  in blue) and its repair cost from earlier anvil use is added to every step, so the levels match
  what the anvil charges. "From your <item>" under the item type shows this; the x goes back to a
  fresh item. Taking the item out by hand turns its enchantments into normal choices you can untick;
  taking a finished result or putting back a half-finished item from the plan keeps the plan going.

## 1.1.1

- Items are grouped by type through item tags: every helmet, sword, pickaxe and so on shares one
  entry (Helmet, Sword, ...) and one enchantment selection, whatever its material.
- Fixed: putting an item that wasn't in the list (e.g. an iron helmet) into the anvil added a
  separate entry that went away again when the item was taken out.

## 1.1.0

- Settings screen in Mod Menu (optional): choose whether the planner starts closed, open, or
  the way you left it when you open an anvil. Stored in `config/enchant_planner.json`.

## 1.0.1

- The license texts (this mod's and iamcal/enchant-order's) are now included in the jar.
- Releases are also published to Modrinth automatically.
- New pixel-art mod icon.

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
