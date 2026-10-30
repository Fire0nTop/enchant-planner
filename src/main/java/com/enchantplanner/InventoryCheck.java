package com.enchantplanner;

import com.enchantplanner.solver.EnchantSolver;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What of the current plan is in the player's inventory (or already in the anvil). */
public final class InventoryCheck {
	/** Slots 0-2 of the anvil menu are the anvil itself; the player's inventory follows. */
	private static final int FIRST_INVENTORY_SLOT = 3;

	private InventoryCheck() {
	}

	/** The books the plan starts from (the ones you need to bring), single or combined ones you have. */
	public static List<EnchantSolver.Piece> neededBooks(EnchantSolver.Result plan) {
		List<EnchantSolver.Piece> out = new ArrayList<>();
		if (plan == null) {
			return out;
		}
		for (EnchantSolver.Step s : plan.steps()) {
			for (EnchantSolver.Piece p : List.of(s.left(), s.right())) {
				if (isStartingBook(plan, p)) {
					out.add(p);
				}
			}
		}
		return out;
	}

	/** A book that no step of the plan makes, i.e. one you bring rather than combine on the way. */
	public static boolean isStartingBook(EnchantSolver.Result plan, EnchantSolver.Piece p) {
		if (p.isTarget()) {
			return false;
		}
		Set<Integer> ids = ids(p.enchants());
		for (EnchantSolver.Step s : plan.steps()) {
			Set<Integer> made = ids(s.left().enchants());
			made.addAll(ids(s.right().enchants()));
			if (!s.left().isTarget() && made.equals(ids)) {
				return false;
			}
		}
		return true;
	}

	private static Set<Integer> ids(int[] ids) {
		Set<Integer> out = new HashSet<>();
		for (int id : ids) {
			out.add(id);
		}
		return out;
	}

	/** Is this piece anywhere in the anvil or the inventory? */
	public static boolean has(AbstractContainerMenu menu, EnchantSolver.Piece piece) {
		for (Slot slot : menu.slots) {
			if (PlanMatcher.matches(piece, slot.getItem())) {
				return true;
			}
		}
		return false;
	}

	/** Books the plan needs that are neither in the anvil nor in the inventory. */
	public static List<EnchantSolver.Piece> missingBooks(AbstractContainerMenu menu, EnchantSolver.Result plan) {
		List<EnchantSolver.Piece> out = new ArrayList<>();
		for (EnchantSolver.Piece p : neededBooks(plan)) {
			if (!has(menu, p)) {
				out.add(p);
			}
		}
		return out;
	}

	/** The inventory slot holding this piece, or {@code null}. */
	public static Slot findInInventory(AbstractContainerMenu menu, EnchantSolver.Piece piece) {
		for (int i = FIRST_INVENTORY_SLOT; i < menu.slots.size(); i++) {
			Slot slot = menu.slots.get(i);
			if (PlanMatcher.matches(piece, slot.getItem())) {
				return slot;
			}
		}
		return null;
	}

	/** The first step of the plan that isn't ticked yet, or -1 when everything is done. */
	public static int nextStep(EnchantSolver.Result plan) {
		if (plan == null) {
			return -1;
		}
		for (int i = 0; i < plan.steps().size(); i++) {
			if (!PlannerState.doneSteps.contains(i)) {
				return i;
			}
		}
		return -1;
	}
}
