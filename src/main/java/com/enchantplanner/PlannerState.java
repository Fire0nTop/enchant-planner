package com.enchantplanner;

import com.enchantplanner.solver.EnchantSolver;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
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

	private static Item target = Items.NETHERITE_SWORD;
	private static final Map<Item, LinkedHashMap<ResourceKey<Enchantment>, Integer>> selections = new HashMap<>();
	private static EnchantCatalog catalog;

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

	public static Item target() {
		return target;
	}

	public static EnchantCatalog catalog() {
		if (catalog == null || catalog.target() != target) {
			catalog = EnchantCatalog.forItem(target);
			recompute();
		}
		return catalog;
	}

	/** Drop the cached catalog, e.g. after joining a world with different enchantments. */
	public static void invalidateCatalog() {
		catalog = null;
	}

	public static void setTarget(Item item) {
		if (item == Items.ENCHANTED_BOOK) {
			item = Items.BOOK;
		}
		if (item != target) {
			target = item;
			catalog();
		}
	}

	public static LinkedHashMap<ResourceKey<Enchantment>, Integer> selection() {
		return selections.computeIfAbsent(target, k -> new LinkedHashMap<>());
	}

	public static int level(EnchantCatalog.Entry e) {
		return selection().getOrDefault(e.key(), 0);
	}

	public static void setLevel(EnchantCatalog.Entry e, int level) {
		if (level <= 0) {
			selection().remove(e.key());
		} else {
			selection().put(e.key(), Math.min(level, Math.max(1, e.maxLevel())));
		}
		recompute();
	}

	public static void clear() {
		selection().clear();
		recompute();
	}

	public static void toggleMode() {
		mode = mode == EnchantSolver.Mode.LEVELS ? EnchantSolver.Mode.PRIOR_WORK : EnchantSolver.Mode.LEVELS;
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
			if (e == null) {
				continue;
			}
			books.add(new EnchantSolver.Book(entries.size(), sel.getValue() * e.bookMultiplier()));
			entries.add(e);
			levels.add(sel.getValue());
		}
		solvedEntries = List.copyOf(entries);
		solvedLevels = List.copyOf(levels);
		result = null;
		impossible = false;

		if (books.isEmpty() || books.size() > MAX_BOOKS) {
			computing = false;
			return;
		}
		boolean bookTarget = EnchantCatalog.isBook(cat.target());
		EnchantSolver.Mode m = mode;
		computing = true;
		EXECUTOR.submit(() -> {
			try {
				EnchantSolver.Result r = EnchantSolver.solve(books, bookTarget, m, () -> {
					if (generation.get() != gen) {
						throw new CancellationException();
					}
				});
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
