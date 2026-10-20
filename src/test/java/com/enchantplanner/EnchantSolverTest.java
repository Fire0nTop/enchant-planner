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
}
