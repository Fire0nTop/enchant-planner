package com.enchantplanner;

import com.enchantplanner.solver.EnchantSolver;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.HashMap;
import java.util.Map;

/** Compares real items and books with the pieces of the current plan. */
public final class PlanMatcher {
	private PlanMatcher() {
	}

	/** Does this stack hold exactly this piece of the plan (same item kind and the same enchantments)? */
	public static boolean matches(EnchantSolver.Piece p, ItemStack stack) {
		return matches(p, stack, true);
	}

	/** @param checkCost also require the plan's repair cost (not needed to know the item is finished) */
	public static boolean matches(EnchantSolver.Piece p, ItemStack stack, boolean checkCost) {
		if (stack.isEmpty()) {
			return false;
		}
		if (p.isTarget() ? !PlannerState.target().contains(stack) : !stack.is(Items.ENCHANTED_BOOK)) {
			return false;
		}
		Map<ResourceKey<Enchantment>, Integer> levels = enchantLevels(stack);
		if (p.isTarget()) {
			// same stage of the plan only with the plan's repair cost (an item enchanted another way
			// can have the same enchantments but cost more)
			if (checkCost && stack.getOrDefault(DataComponents.REPAIR_COST, 0) != EnchantSolver.penalty(p.priorWork())) {
				return false;
			}
			// enchantments you unticked while this item was in the anvil no longer count, unless they
			// clash with the plan
			for (ResourceKey<Enchantment> unticked : PlannerState.untickedForItem) {
				if (levels.containsKey(unticked) && !PlannerState.selection().containsKey(unticked)
						&& !clashesWithSelection(unticked)) {
					levels.remove(unticked);
				}
			}
		}
		return levels.equals(expectedLevels(p));
	}

	private static boolean clashesWithSelection(ResourceKey<Enchantment> key) {
		EnchantCatalog.Entry extra = PlannerState.catalog().find(key);
		if (extra == null) {
			return false;
		}
		for (ResourceKey<Enchantment> selected : PlannerState.selection().keySet()) {
			EnchantCatalog.Entry e = PlannerState.catalog().find(selected);
			if (e != null && !extra.compatibleWith(e)) {
				return true;
			}
		}
		return false;
	}

	/** The enchantments a piece of the plan has: for the item, what it started with plus what the step added. */
	public static Map<ResourceKey<Enchantment>, Integer> expectedLevels(EnchantSolver.Piece p) {
		Map<ResourceKey<Enchantment>, Integer> out = new HashMap<>();
		if (p.isTarget()) {
			out.putAll(PlannerState.startEnchants());
		}
		for (int id : p.enchants()) {
			out.put(PlannerState.solvedEntries().get(id).key(), PlannerState.solvedLevels().get(id));
		}
		return out;
	}

	public static Map<ResourceKey<Enchantment>, Integer> enchantLevels(ItemStack stack) {
		Map<ResourceKey<Enchantment>, Integer> out = new HashMap<>();
		for (Object2IntMap.Entry<Holder<Enchantment>> e : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()) {
			e.getKey().unwrapKey().ifPresent(k -> out.put(k, e.getIntValue()));
		}
		return out;
	}
}
