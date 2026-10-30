package com.enchantplanner;

import com.enchantplanner.solver.EnchantSolver;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Java solver against answers produced by the original enchant-order work.js
 * (src/test/resources/reference-cases.txt: "levels xpSum steps item mode name:level:value,...").
 */
class EnchantSolverTest {
	@Test
	void matchesReferenceImplementation() throws Exception {
		int checked = 0;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(
				getClass().getResourceAsStream("/reference-cases.txt"), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				String[] f = line.split(" ");
				boolean impossible = f[0].equals("IMPOSSIBLE");
				int o = impossible ? 1 : 3;
				String item = f[o];
				EnchantSolver.Mode mode = f[o + 1].equals("levels") ? EnchantSolver.Mode.LEVELS : EnchantSolver.Mode.PRIOR_WORK;
				List<EnchantSolver.Book> books = new ArrayList<>();
				Map<String, Integer> ids = new HashMap<>();
				for (String e : f[o + 2].split(",")) {
					String[] p = e.split(":");
					books.add(new EnchantSolver.Book(ids.computeIfAbsent(p[0], k -> ids.size()), Integer.parseInt(p[2])));
				}

				EnchantSolver.Result res = EnchantSolver.solve(books, item.equals("book"), mode, null);
				if (impossible) {
					assertEquals(null, res, line);
				} else {
					assertNotNull(res, line);
					double xpSum = res.steps().stream().mapToDouble(EnchantSolver.Step::xp).sum();
					assertEquals(Integer.parseInt(f[0]), res.totalLevels(), "levels: " + line);
					assertEquals(Double.parseDouble(f[1]), xpSum, 1e-6, "xp: " + line);
					assertEquals(Integer.parseInt(f[2]), res.steps().size(), "steps: " + line);
					for (EnchantSolver.Step s : res.steps()) {
						assertTrue(s.levels() <= EnchantSolver.MAXIMUM_MERGE_LEVELS, line);
					}
				}
				checked++;
			}
		}
		assertTrue(checked > 300, "only " + checked + " cases");
	}

	@Test
	void largeBookFinishesQuickly() {
		List<EnchantSolver.Book> books = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			books.add(new EnchantSolver.Book(i, 1 + i % 4));
		}
		long start = System.nanoTime();
		EnchantSolver.solve(books, false, EnchantSolver.Mode.LEVELS, null);
		long ms = (System.nanoTime() - start) / 1_000_000;
		assertTrue(ms < 10_000, "took " + ms + " ms");
	}

	@Test
	void startingItemPaysItsPriorWork() {
		// one Sharpness V book (value 5) on an item that went through the anvil twice (repair cost 3)
		List<EnchantSolver.Book> books = List.of(new EnchantSolver.Book(0, 5));
		EnchantSolver.Result fresh = EnchantSolver.solve(books, false, EnchantSolver.Mode.LEVELS, 0, null);
		EnchantSolver.Result used = EnchantSolver.solve(books, false, EnchantSolver.Mode.LEVELS, 2, null);
		assertEquals(5, fresh.totalLevels());
		assertEquals(8, used.totalLevels());
		assertEquals(7, used.finalPriorWork());

		// a heavily used item (repair cost 31) makes 9-level books too expensive
		assertEquals(null, EnchantSolver.solve(List.of(new EnchantSolver.Book(0, 9)), false, EnchantSolver.Mode.LEVELS, 5, null));
	}

	@Test
	void repairCostToWork() {
		assertEquals(0, EnchantSolver.workFromRepairCost(0));
		assertEquals(1, EnchantSolver.workFromRepairCost(1));
		assertEquals(2, EnchantSolver.workFromRepairCost(3));
		assertEquals(3, EnchantSolver.workFromRepairCost(7));
		assertEquals(5, EnchantSolver.workFromRepairCost(31));
	}

	@Test
	void multiEnchantmentBookIsOneStep() {
		// item + Sharpness V (5), Unbreaking III (3) and Mending (2): with Unbreaking + Mending on one
		// book the plan needs two anvil steps instead of three
		List<EnchantSolver.Book> singles = List.of(new EnchantSolver.Book(0, 5), new EnchantSolver.Book(1, 3), new EnchantSolver.Book(2, 2));
		List<EnchantSolver.Book> combined = List.of(new EnchantSolver.Book(0, 5), new EnchantSolver.Book(new int[]{1, 2}, 5, 0));
		EnchantSolver.Result a = EnchantSolver.solve(singles, false, EnchantSolver.Mode.LEVELS, null);
		EnchantSolver.Result b = EnchantSolver.solve(combined, false, EnchantSolver.Mode.LEVELS, null);
		assertEquals(3, a.steps().size());
		assertEquals(2, b.steps().size());
		assertTrue(b.totalLevels() < a.totalLevels(), b.totalLevels() + " vs " + a.totalLevels());

		// a combined book that went through the anvil once costs its penalty too
		EnchantSolver.Result used = EnchantSolver.solve(List.of(new EnchantSolver.Book(0, 5), new EnchantSolver.Book(new int[]{1, 2}, 5, 1)),
				false, EnchantSolver.Mode.LEVELS, null);
		assertEquals(b.totalLevels() + 1, used.totalLevels());
	}
}
