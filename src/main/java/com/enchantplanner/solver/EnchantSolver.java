package com.enchantplanner.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Finds the cheapest anvil combining order for a set of enchantment books.
 * <p>
 * This is a faithful Java port of the search in iamcal/enchant-order (work.js, MIT licensed):
 * the item is first combined with the most expensive book, then every split of the remaining
 * pieces is explored with memoization, keeping the cheapest candidate per prior-work level.
 * <p>
 * Pure Java on purpose (no Minecraft classes) so it can be unit tested and run off-thread.
 */
public final class EnchantSolver {
	/** Survival anvils refuse merges that would cost 40 levels or more ("Too Expensive!"). */
	public static final int MAXIMUM_MERGE_LEVELS = 39;

	public enum Mode {
		/** Minimise the total experience spent. */
		LEVELS,
		/** Minimise the final prior-work penalty (keeps the item cheaper to repair later). */
		PRIOR_WORK
	}

	/** One enchantment book to apply. {@code value} = level × book anvil-cost multiplier. */
	public record Book(int id, int value) {
	}

	/** One side of an anvil operation: the target item, or a book holding {@code enchants}. */
	public record Piece(boolean isTarget, int[] enchants, int priorWork) {
		@Override
		public String toString() {
			return (isTarget ? "Item" : "Book") + Arrays.toString(enchants) + "/w" + priorWork;
		}
	}

	/** A single anvil operation: put {@code left} in the first slot and {@code right} in the second. */
	public record Step(Piece left, Piece right, int levels, double xp, int priorWorkPenaltyAfter) {
	}

	public record Result(List<Step> steps, int totalLevels, double totalXp, int maxStepLevels, int finalPriorWork) {
	}

	private static final class TooExpensive extends RuntimeException {
		TooExpensive() {
			super(null, null, false, false);
		}
	}

	/** An intermediate combined object, mirroring item_obj / MergeEnchants of the original. */
	private static final class Node {
		final boolean isTarget; // the item (always kept in the left slot)
		final boolean isBookBase; // in book mode: the book chosen as the base
		final int[] enchants;
		final long mask; // bit per enchant id
		final int work;
		final int value;
		final double xp;
		final Node left;
		final Node right;
		final int mergeCost;

		Node(boolean isTarget, boolean isBookBase, int[] enchants, int value) {
			this(isTarget, isBookBase, enchants, value, 0);
		}

		Node(boolean isTarget, boolean isBookBase, int[] enchants, int value, int work) {
			this.isTarget = isTarget;
			this.isBookBase = isBookBase;
			this.enchants = enchants;
			long m = 0;
			for (int id : enchants) {
				m |= 1L << id;
			}
			this.mask = m;
			this.work = work;
			this.value = value;
			this.xp = 0;
			this.left = null;
			this.right = null;
			this.mergeCost = 0;
		}

		Node(Node left, Node right) {
			int cost = right.value + penalty(left.work) + penalty(right.work);
			if (cost > MAXIMUM_MERGE_LEVELS) {
				throw new TooExpensive();
			}
			this.isTarget = left.isTarget;
			this.isBookBase = left.isBookBase;
			this.enchants = concat(left.enchants, right.enchants);
			this.mask = left.mask | right.mask;
			this.work = Math.max(left.work, right.work) + 1;
			this.value = left.value + right.value;
			this.xp = left.xp + right.xp + experience(cost);
			this.left = left;
			this.right = right;
			this.mergeCost = cost;
		}

		boolean isLeaf() {
			return left == null;
		}
	}

	/**
	 * Results per sub-list of the starting pieces. Every list searched is a subsequence of the
	 * same starting list and those pieces hold disjoint enchantments, so the union of their
	 * enchantment bits identifies a list exactly.
	 */
	private final Map<Long, TreeMap<Integer, Node>> memo = new HashMap<>();
	private final Runnable cancelCheck;

	private EnchantSolver(Runnable cancelCheck) {
		this.cancelCheck = cancelCheck;
	}

	/**
	 * @param books       the books to apply, in display order
	 * @param bookTarget  true when the goal is a single combined book instead of an item
	 * @param cancelCheck called regularly; may throw to abort a long search
	 * @return the best order, or {@code null} if every order hits "Too Expensive!"
	 */
	public static Result solve(List<Book> books, boolean bookTarget, Mode mode, Runnable cancelCheck) {
		return solve(books, bookTarget, mode, 0, cancelCheck);
	}

	/**
	 * @param startWork how often the item has already been through an anvil (its repair cost is
	 *                  {@code 2^startWork - 1}); ignored for a book target
	 */
	public static Result solve(List<Book> books, boolean bookTarget, Mode mode, int startWork, Runnable cancelCheck) {
		if (books.isEmpty() || (bookTarget && books.size() < 2)) {
			return new Result(List.of(), 0, 0, 0, penalty(bookTarget ? 0 : startWork));
		}
		return new EnchantSolver(cancelCheck == null ? () -> {} : cancelCheck).run(books, bookTarget, mode, startWork);
	}

	/** The anvil-use count for a repair cost: 0 → 0, 1 → 1, 3 → 2, 7 → 3, … (rounded up otherwise). */
	public static int workFromRepairCost(int repairCost) {
		return repairCost <= 0 ? 0 : 32 - Integer.numberOfLeadingZeros(repairCost);
	}

	private Result run(List<Book> books, boolean bookTarget, Mode mode, int startWork) {
		List<Node> pieces = new ArrayList<>();
		for (Book b : books) {
			pieces.add(new Node(false, false, new int[]{b.id()}, b.value()));
		}

		Node base;
		if (bookTarget) {
			Node top = pieces.remove(mostExpensive(pieces));
			base = new Node(false, true, top.enchants, top.value);
		} else {
			base = new Node(true, false, new int[0], 0, startWork);
		}
		Node first;
		try {
			first = new Node(base, pieces.remove(mostExpensive(pieces)));
		} catch (TooExpensive e) {
			return null;
		}
		pieces.add(first);

		Map<Integer, Node> candidates;
		try {
			candidates = cheapestFromList(pieces);
		} catch (TooExpensive e) {
			return null;
		}

		Node best = null;
		double bestCost = Double.POSITIVE_INFINITY;
		for (Node n : candidates.values()) {
			double cost = mode == Mode.LEVELS ? n.xp : n.work;
			if (cost < bestCost) {
				bestCost = cost;
				best = n;
			}
		}
		if (best == null) {
			return null;
		}

		List<Step> steps = new ArrayList<>();
		collectSteps(best, steps);
		int total = 0;
		int max = 0;
		for (Step s : steps) {
			total += s.levels();
			max = Math.max(max, s.levels());
		}
		return new Result(steps, total, experience(total), max, penalty(best.work));
	}

	private static int mostExpensive(List<Node> nodes) {
		int idx = 0;
		for (int i = 1; i < nodes.size(); i++) {
			if (nodes.get(i).value > nodes.get(idx).value) {
				idx = i;
			}
		}
		return idx;
	}

	private void collectSteps(Node n, List<Step> out) {
		if (n.isLeaf()) {
			return;
		}
		collectSteps(n.left, out);
		collectSteps(n.right, out);
		out.add(new Step(toPiece(n.left), toPiece(n.right), n.mergeCost, experience(n.mergeCost), penalty(n.work)));
	}

	private static Piece toPiece(Node n) {
		return new Piece(n.isTarget, n.enchants.clone(), n.work);
	}

	private TreeMap<Integer, Node> cheapestFromList(List<Node> items) {
		long k = 0;
		for (Node n : items) {
			k |= n.mask;
		}
		TreeMap<Integer, Node> cached = memo.get(k);
		if (cached != null) {
			return cached;
		}
		cancelCheck.run();

		TreeMap<Integer, Node> result;
		switch (items.size()) {
			case 1 -> {
				result = new TreeMap<>();
				result.put(items.getFirst().work, items.getFirst());
			}
			case 2 -> {
				Node n = cheapestOfPair(items.get(0), items.get(1));
				result = new TreeMap<>();
				result.put(n.work, n);
			}
			default -> result = cheapestFromListN(items, items.size() / 2);
		}
		memo.put(k, result);
		return result;
	}

	private static Node cheapestOfPair(Node left, Node right) {
		if (right.isTarget) {
			return new Node(right, left);
		} else if (left.isTarget) {
			return new Node(left, right);
		}

		Node normal;
		try {
			normal = new Node(left, right);
		} catch (TooExpensive e) {
			return new Node(right, left);
		}
		Node reversed;
		try {
			reversed = new Node(right, left);
		} catch (TooExpensive e) {
			return normal;
		}
		return compareCheapest(normal, reversed).firstEntry().getValue();
	}

	private TreeMap<Integer, Node> cheapestFromListN(List<Node> items, int maxSubcount) {
		TreeMap<Integer, Node> cheapest = new TreeMap<>();
		int n = items.size();
		for (int subcount = 1; subcount <= maxSubcount; subcount++) {
			int[] idx = new int[subcount];
			for (int i = 0; i < subcount; i++) {
				idx[i] = i;
			}
			while (true) {
				List<Node> left = new ArrayList<>(subcount);
				List<Node> right = new ArrayList<>(n - subcount);
				int p = 0;
				for (int i = 0; i < n; i++) {
					if (p < subcount && idx[p] == i) {
						left.add(items.get(i));
						p++;
					} else {
						right.add(items.get(i));
					}
				}

				cancelCheck.run();
				TreeMap<Integer, Node> merged = cheapestFromDictionaries(cheapestFromList(left), cheapestFromList(right));
				mergeInto(cheapest, merged);

				// next combination in lexicographic order
				int i = subcount - 1;
				while (i >= 0 && idx[i] == n - subcount + i) {
					i--;
				}
				if (i < 0) {
					break;
				}
				idx[i]++;
				for (int j = i + 1; j < subcount; j++) {
					idx[j] = idx[j - 1] + 1;
				}
			}
		}
		return cheapest;
	}

	private TreeMap<Integer, Node> cheapestFromDictionaries(TreeMap<Integer, Node> leftMap, TreeMap<Integer, Node> rightMap) {
		TreeMap<Integer, Node> cheapest = new TreeMap<>();
		for (Node l : leftMap.values()) {
			for (Node r : rightMap.values()) {
				// the original memoizes these pairs too, but a given pair of candidates never comes up twice
				Node merged;
				try {
					merged = cheapestOfPair(l, r);
				} catch (TooExpensive e) {
					continue;
				}
				Node existing = cheapest.get(merged.work);
				cheapest.put(merged.work, existing == null ? merged : compareCheapest(existing, merged).get(merged.work));
			}
		}
		return removeExpensiveCandidates(cheapest);
	}

	private static void mergeInto(TreeMap<Integer, Node> into, Map<Integer, Node> from) {
		for (Map.Entry<Integer, Node> e : from.entrySet()) {
			Node existing = into.get(e.getKey());
			if (existing == null) {
				into.put(e.getKey(), e.getValue());
			} else {
				into.put(e.getKey(), compareCheapest(existing, e.getValue()).get(e.getKey()));
			}
		}
	}

	private static TreeMap<Integer, Node> compareCheapest(Node a, Node b) {
		TreeMap<Integer, Node> out = new TreeMap<>();
		if (a.work == b.work) {
			if (a.value == b.value) {
				out.put(a.work, a.xp <= b.xp ? a : b);
			} else {
				out.put(a.work, a.value < b.value ? a : b);
			}
		} else {
			out.put(a.work, a);
			out.put(b.work, b);
		}
		return out;
	}

	private static TreeMap<Integer, Node> removeExpensiveCandidates(TreeMap<Integer, Node> byWork) {
		TreeMap<Integer, Node> out = new TreeMap<>();
		Integer cheapestValue = null;
		for (Map.Entry<Integer, Node> e : byWork.entrySet()) {
			int value = e.getValue().value;
			if (cheapestValue == null || value < cheapestValue) {
				out.put(e.getKey(), e.getValue());
				cheapestValue = value;
			}
		}
		return out;
	}

	private static int[] concat(int[] a, int[] b) {
		int[] out = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	/** Prior-work penalty in levels for an item that has been through the anvil {@code work} times. */
	public static int penalty(int work) {
		return (1 << work) - 1;
	}

	/** Experience points needed to go from level 0 to {@code level}. */
	public static double experience(int level) {
		if (level == 0) {
			return 0;
		} else if (level <= 16) {
			return level * level + 6 * level;
		} else if (level <= 31) {
			return 2.5 * level * level - 40.5 * level + 360;
		} else {
			return 4.5 * level * level - 162.5 * level + 2220;
		}
	}
}
