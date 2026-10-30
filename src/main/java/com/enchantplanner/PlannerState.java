package com.enchantplanner;

import com.enchantplanner.solver.EnchantSolver;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Planner selection and the latest solver result. Static so it survives closing and reopening
 * the anvil (and GUI resizes, which rebuild the overlay).
 */
public final class PlannerState {
	/** Above this many books the exhaustive search gets too slow to be worth waiting for. */
	public static final int MAX_BOOKS = 15;

	public static boolean visible = false;
	public static EnchantSolver.Mode mode = EnchantSolver.Mode.LEVELS;

	private static ItemType target; // lazily the default type, once registries are bound
	private static final Map<ItemType, LinkedHashMap<ResourceKey<Enchantment>, Integer>> selections = new HashMap<>();
	private static EnchantCatalog catalog;
	private static ItemType catalogType;

	/** Ordered list of catalog entries passed to the solver; solver ids index into it. */
	private static List<EnchantCatalog.Entry> solvedEntries = List.of();
	private static List<Integer> solvedLevels = List.of();
	private static volatile EnchantSolver.Result result;
	private static volatile boolean computing;
	private static volatile boolean impossible;
	public static final Set<Integer> doneSteps = new HashSet<>();

	private static final AtomicInteger generation = new AtomicInteger();
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Enchant Planner solver");
		t.setDaemon(true);
		return t;
	});

	private PlannerState() {
	}

	/** The book button: show or hide the panels, remembered for {@link PlannerConfig.StartState#REMEMBER}. */
	public static void toggleVisible() {
		visible = !visible;
		PlannerConfig config = PlannerConfig.get();
		if (config.lastVisible != visible) {
			config.lastVisible = visible;
			PlannerConfig.save();
		}
	}

	public static ItemType target() {
		if (target == null) {
			target = ItemType.defaultType();
		}
		return target;
	}

	public static EnchantCatalog catalog() {
		if (catalog == null || !target().equals(catalogType)) {
			catalogType = target();
			catalog = EnchantCatalog.forItem(catalogType.probe());
			recompute();
		}
		return catalog;
	}

	/** Drop the cached catalog, e.g. after joining a world with different enchantments. */
	public static void invalidateCatalog() {
		catalog = null;
	}

	public static void setTarget(ItemType type) {
		if (!type.equals(target())) {
			target = type;
			startItem = null;
			catalog();
		}
	}

	/**
	 * The item the plan starts from: its enchantments cost nothing and its repair cost (from earlier
	 * anvil use) is added to every step. {@code null} means a fresh item.
	 *
	 * @param autoSelected enchantments that were ticked because the item has them
	 */
	public record StartItem(ItemType type, Item item, Map<ResourceKey<Enchantment>, Integer> enchants, int repairCost,
							Set<ResourceKey<Enchantment>> autoSelected) {
	}

	private static StartItem startItem;

	public static StartItem startItem() {
		return startItem;
	}

	/** Plan from an item with these enchantments and repair cost; ticks the enchantments it has. */
	public static void setStartItem(Item item, Map<ResourceKey<Enchantment>, Integer> enchants, int repairCost) {
		StartItem before = suspended;
		clearStartItem(false);
		Set<ResourceKey<Enchantment>> auto = new HashSet<>();
		for (Map.Entry<ResourceKey<Enchantment>, Integer> e : enchants.entrySet()) {
			Integer selected = selection().get(e.getKey());
			if (selected == null || selected < e.getValue()) {
				selection().put(e.getKey(), e.getValue());
				auto.add(e.getKey());
			}
		}
		if (before != null && before.item() == item) {
			// coming back after a re-check: still remember what was ticked only because of the item
			for (ResourceKey<Enchantment> key : before.autoSelected()) {
				if (enchants.containsKey(key)) {
					auto.add(key);
				}
			}
		}
		startItem = new StartItem(target(), item, Map.copyOf(enchants), repairCost, Set.copyOf(auto));
		recompute();
	}

	public static void clearStartItem() {
		clearStartItem(true);
	}

	/**
	 * Stop planning from the item (it was taken out of the anvil) but keep the selection: its
	 * enchantments stay ticked as ordinary choices that can be changed or unticked.
	 */
	/**
	 * Plan without the item for a moment, so it can be compared with the normal plan again; if it
	 * still doesn't fit, {@link #setStartItem} picks it up again with what it had ticked.
	 */
	public static void suspendStartItem() {
		if (startItem != null) {
			suspended = startItem;
			startItem = null;
			recompute();
		}
	}

	private static StartItem suspended;

	public static void releaseStartItem() {
		suspended = null;
		if (startItem != null) {
			startItem = null;
			recompute();
		}
	}

	private static void clearStartItem(boolean recompute) {
		suspended = null;
		StartItem old = startItem;
		if (old == null) {
			return;
		}
		startItem = null;
		// untick what was only ticked because the item had it
		for (ResourceKey<Enchantment> key : old.autoSelected()) {
			if (old.enchants().get(key).equals(selection().get(key))) {
				selection().remove(key);
			}
		}
		if (recompute) {
			recompute();
		}
	}

	/** Level of this enchantment already on the starting item, or 0. */
	public static int ownedLevel(EnchantCatalog.Entry e) {
		return startItem == null ? 0 : startItem.enchants().getOrDefault(e.key(), 0);
	}

	/** Enchantments on the starting item; empty for a fresh item. */
	public static Map<ResourceKey<Enchantment>, Integer> startEnchants() {
		return startItem == null ? Map.of() : startItem.enchants();
	}

	public static LinkedHashMap<ResourceKey<Enchantment>, Integer> selection() {
		return selections.computeIfAbsent(target(), k -> new LinkedHashMap<>());
	}

	public static int level(EnchantCatalog.Entry e) {
		return selection().getOrDefault(e.key(), 0);
	}

	public static void setLevel(EnchantCatalog.Entry e, int level) {
		level = Math.max(level, ownedLevel(e)); // the anvil can't remove what the item already has
		if (level <= 0) {
			if (selection().remove(e.key()) != null) {
				untickedForItem.add(e.key());
			}
		} else {
			selection().put(e.key(), Math.min(level, Math.max(1, e.maxLevel())));
			untickedForItem.remove(e.key());
		}
		selectionChanged = true;
		recompute();
	}

	/**
	 * Set when the player changes what to plan; the overlay then compares the item in the anvil
	 * with the normal plan again before falling back to planning from the item.
	 */
	public static boolean selectionChanged;

	/**
	 * Enchantments unticked while the current item was in the anvil: if the item has them they
	 * no longer count when comparing it with the plan. Reset when a different item goes in.
	 */
	public static final Set<ResourceKey<Enchantment>> untickedForItem = new HashSet<>();

	/** Untick everything except what the starting item already has. */
	public static void clear() {
		selection().clear();
		selection().putAll(startEnchants());
		selectionChanged = true;
		recompute();
	}

	public static void toggleMode() {
		mode = mode == EnchantSolver.Mode.LEVELS ? EnchantSolver.Mode.PRIOR_WORK : EnchantSolver.Mode.LEVELS;
		selectionChanged = true;
		recompute();
	}

	public static EnchantSolver.Result result() {
		return result;
	}

	public static boolean computing() {
		return computing;
	}

	public static boolean impossible() {
		return impossible;
	}

	public static List<EnchantCatalog.Entry> solvedEntries() {
		return solvedEntries;
	}

	public static List<Integer> solvedLevels() {
		return solvedLevels;
	}

	public static int selectedCount() {
		return selection().size();
	}

	/**
	 * Enchanted books in the open anvil screen (inventory included) that carry two or more of the
	 * enchantments still needed, each at exactly the selected level, as solver books.
	 */
	private static List<EnchantSolver.Book> ownedBooks(List<EnchantCatalog.Entry> entries, List<Integer> levels, int needed) {
		List<EnchantSolver.Book> out = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !(mc.player.containerMenu instanceof AnvilMenu menu)) {
			return out;
		}
		Set<Set<Integer>> seen = new HashSet<>();
		for (Slot slot : menu.slots) {
			ItemStack stack = slot.getItem();
			if (!stack.is(Items.ENCHANTED_BOOK)) {
				continue;
			}
			Map<ResourceKey<Enchantment>, Integer> onBook = PlanMatcher.enchantLevels(stack);
			if (onBook.size() < 2) {
				continue;
			}
			List<Integer> ids = new ArrayList<>();
			int value = 0;
			for (Map.Entry<ResourceKey<Enchantment>, Integer> e : onBook.entrySet()) {
				int id = -1;
				for (int i = 0; i < entries.size(); i++) {
					if (entries.get(i).key().equals(e.getKey()) && levels.get(i).equals(e.getValue())) {
						id = i;
					}
				}
				if (id < 0) {
					ids = null; // has something the plan doesn't want (or another level)
					break;
				}
				ids.add(id);
				value += e.getValue() * entries.get(id).bookMultiplier();
			}
			if (ids != null && seen.add(new HashSet<>(ids))) {
				int repairCost = stack.getOrDefault(DataComponents.REPAIR_COST, 0);
				out.add(new EnchantSolver.Book(ids.stream().mapToInt(Integer::intValue).toArray(), value,
						EnchantSolver.workFromRepairCost(repairCost)));
			}
		}
		// every combination is a full search, so keep the number of tries reasonable
		out.sort((a, b) -> b.ids().length - a.ids().length);
		int limit = needed <= 8 ? 8 : 3;
		return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
	}

	/** The single books with the chosen owned books swapped in, or {@code null} if those overlap. */
	private static List<EnchantSolver.Book> withOwnedBooks(List<EnchantSolver.Book> singles, List<EnchantSolver.Book> owned, int subset) {
		Set<Integer> covered = new HashSet<>();
		List<EnchantSolver.Book> out = new ArrayList<>();
		for (int i = 0; i < owned.size(); i++) {
			if ((subset & (1 << i)) != 0) {
				for (int id : owned.get(i).ids()) {
					if (!covered.add(id)) {
						return null;
					}
				}
				out.add(owned.get(i));
			}
		}
		for (EnchantSolver.Book b : singles) {
			if (!covered.contains(b.ids()[0])) {
				out.add(b);
			}
		}
		return out;
	}

	private static boolean cheaper(EnchantSolver.Result a, EnchantSolver.Result b, EnchantSolver.Mode mode) {
		if (a == null) {
			return false;
		}
		if (b == null) {
			return true;
		}
		double xpA = a.steps().stream().mapToDouble(EnchantSolver.Step::xp).sum();
		double xpB = b.steps().stream().mapToDouble(EnchantSolver.Step::xp).sum();
		if (mode == EnchantSolver.Mode.PRIOR_WORK && a.finalPriorWork() != b.finalPriorWork()) {
			return a.finalPriorWork() < b.finalPriorWork();
		}
		return xpA < xpB;
	}

	public static void recompute() {
		EnchantCatalog cat = catalog;
		if (cat == null) {
			return;
		}
		int gen = generation.incrementAndGet();
		doneSteps.clear();

		List<EnchantCatalog.Entry> entries = new ArrayList<>();
		List<Integer> levels = new ArrayList<>();
		List<EnchantSolver.Book> books = new ArrayList<>();
		for (Map.Entry<ResourceKey<Enchantment>, Integer> sel : selection().entrySet()) {
			EnchantCatalog.Entry e = cat.find(sel.getKey());
			if (e == null || ownedLevel(e) >= sel.getValue()) {
				continue; // already on the starting item
			}
			books.add(new EnchantSolver.Book(entries.size(), sel.getValue() * e.bookMultiplier()));
			entries.add(e);
			levels.add(sel.getValue());
		}
		solvedEntries = List.copyOf(entries);
		solvedLevels = List.copyOf(levels);
		result = null;
		impossible = false;

		boolean bookTarget = catalogType.isBook();
		int startWork = startItem == null || bookTarget ? 0 : EnchantSolver.workFromRepairCost(startItem.repairCost());
		computing = false;
		if (books.isEmpty()) {
			if (!selection().isEmpty()) {
				result = EnchantSolver.solve(books, bookTarget, mode, startWork, null); // nothing left to add
			}
			return;
		}
		if (books.size() > MAX_BOOKS) {
			return;
		}
		EnchantSolver.Mode m = mode;
		List<EnchantSolver.Book> owned = ownedBooks(entries, levels, books.size());
		computing = true;
		EXECUTOR.submit(() -> {
			try {
				Runnable cancel = () -> {
					if (generation.get() != gen) {
						throw new CancellationException();
					}
				};
				EnchantSolver.Result r = EnchantSolver.solve(books, bookTarget, m, startWork, cancel);
				// try the books you already have with several of the enchantments on them, alone and
				// together, and keep whatever is cheapest
				for (int subset = 1; subset < (1 << owned.size()); subset++) {
					List<EnchantSolver.Book> pieces = withOwnedBooks(books, owned, subset);
					if (pieces != null) {
						EnchantSolver.Result alt = EnchantSolver.solve(pieces, bookTarget, m, startWork, cancel);
						if (cheaper(alt, r, m)) {
							r = alt;
						}
					}
				}
				if (generation.get() == gen) {
					result = r;
					impossible = r == null;
					computing = false;
				}
			} catch (CancellationException ignored) {
				// a newer selection superseded this run
			} catch (Throwable t) {
				EnchantPlannerClient.LOGGER.error("Enchant order search failed", t);
				if (generation.get() == gen) {
					computing = false;
				}
			}
		});
	}
}
