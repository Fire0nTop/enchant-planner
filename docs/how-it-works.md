# How it works

## Anvil cost

Combining two things in an anvil costs, in levels:

```
cost = value of the right-hand item + penalty(left) + penalty(right)
penalty(n) = 2^n - 1        (n = how many times the item has been through an anvil)
value = sum over its enchantments of  level × multiplier
```

The multiplier is the enchantment's anvil cost, halved (at least 1) when it comes from a book.
For example Mending has anvil cost 4, so a Mending book adds 2 levels; Sharpness V adds 5.
The mod reads the anvil cost and max level of every enchantment from the game's registry, so the
numbers always match the world you are in.

After a merge the result's anvil-use count is `max(left, right) + 1`. In survival a single merge
that costs 40 levels or more is refused ("Too Expensive!"), so the planner never uses a step above
39 levels.

Total XP is shown with the vanilla level-to-XP formula, assuming you start the plan at level 0.

## The search

The order matters because every merge raises the prior-work penalty of everything that is merged
later. The solver (`src/main/java/com/enchantplanner/solver/EnchantSolver.java`) is a direct port
of the search in [iamcal/enchant-order](https://github.com/iamcal/enchant-order)'s `work.js`:

1. The item is combined with the most valuable book first (for a book target, the most valuable
   book becomes the base).
2. For the remaining pieces, every split into two groups is tried recursively; each group is solved
   the same way and the two halves are merged.
3. For each group it keeps the cheapest result per anvil-use count, because a slightly more
   expensive result with a lower penalty can win later.
4. Results are memoized per group, so the search is exponential but fast up to about 15 books.

The result with the least total XP (or, in *Optimize: Repair* mode, the lowest anvil-use count)
is shown. `EnchantSolverTest` checks the port against 397 random cases produced by running the
original JavaScript.

## Code map

| File | What it does |
|---|---|
| `EnchantPlannerClient` | Client entrypoint; attaches the overlay to every anvil screen via Fabric's screen events (no mixins). |
| `PlannerOverlay` | Draws both panels and handles clicks and scrolling. |
| `PlannerState` | Selection, mode and the latest result; runs the solver on a background thread. |
| `EnchantCatalog` | Enchantments that fit the chosen item, grouped by type. |
| `solver/EnchantSolver` | The pure-Java search (no Minecraft classes, unit tested). |
