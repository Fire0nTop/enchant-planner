# Changelog

## 1.2.0

- The checklist follows the item in the anvil: an item at some stage of the plan ticks the steps
  that lead there and unticks the later ones, so swapping items updates it. Book + book steps tick
  when you take their result. An item that matches no stage of the plan becomes the starting item
  (its enchantments in blue) and only the missing enchantments are planned. Changing the
  selection re-checks the item; an enchantment you untick while the item is in the anvil stops
  counting for that item instead of being ticked again. After any change the item is compared
  with the normal plan first, so unticking what you just added returns to the checked plan. A
  half-finished item only counts as a stage of the plan with the plan's repair cost.
- Fixed: a book in the anvil (a book + book step) switched the item type to Book and lost the plan.
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
