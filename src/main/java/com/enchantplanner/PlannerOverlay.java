package com.enchantplanner;

import com.enchantplanner.solver.EnchantSolver;
import com.mojang.blaze3d.platform.InputConstants;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws the planner beside the vanilla anvil screen and handles its clicks. Rebuilt on every
 * screen init; everything worth keeping lives in {@link PlannerState}.
 * <p>
 * Wide screens get a setup panel left of the anvil and the result panel right of it. When the
 * sides are too narrow (large GUI scale) both panels are drawn over the anvil instead and the
 * toggle button hides them again.
 */
public final class PlannerOverlay {
	private static final int ANVIL_W = 176;
	private static final int ANVIL_H = 166;
	private static final int GAP = 4;
	private static final int PAD = 5;
	private static final int ROW_H = 12;
	private static final int HEADER_H = 11;
	private static final int CELL = 18;

	private static final int TEXT_DARK = 0xFF404040;
	private static final int TEXT_LIGHT = 0xFFFFFFFF;
	private static final int TEXT_DIM = 0xFF5A5A5A;
	private static final int TEXT_GOLD = 0xFFFFE070;
	private static final int TEXT_CURSE = 0xFFFF7070;
	private static final int COST_OK = 0xFF80FF20;
	private static final int COST_BAD = 0xFFFF6060;
	private static final int COLOR_ON_ITEM = 0xFF8FD0FF;

	private static final Identifier BUTTON = Identifier.withDefaultNamespace("widget/button");
	private static final Identifier BUTTON_HOVER = Identifier.withDefaultNamespace("widget/button_highlighted");


	private record Rect(int x, int y, int w, int h) {
		int right() {
			return x + w;
		}

		int bottom() {
			return y + h;
		}

		boolean contains(double px, double py) {
			return px >= x && px < x + w && py >= y && py < y + h;
		}
	}

	private record Hotspot(Rect rect, Runnable action) {
	}

	private final AnvilScreen screen;
	private final Minecraft mc;
	private final Font font;
	private final ItemStack bookIcon;

	private Rect toggle;
	private Rect setup;
	private Rect order;
	private Rect setupList;
	private Rect stepList;
	private boolean compact;
	private int anvilX;
	private int anvilY;

	private final List<Hotspot> hotspots = new ArrayList<>();
	private static boolean pickingItem;
	private static int enchantScroll;
	private static int gridScroll;
	private static int stepScroll;
	private ItemStack lastAnvilStack;
	private ItemStack lastBookStack = ItemStack.EMPTY;
	private ItemStack lastResultStack = ItemStack.EMPTY;
	/** The plan step that was in the anvil last frame (taking its result ticks it off). */
	private int lastStepInAnvil = -1;
	private EnchantSolver.Result lastPlan;

	/** Ticks until the plan is redone for a newly opened anvil (once the inventory has arrived), or -1. */
	private int replanIn;

	public PlannerOverlay(AnvilScreen screen, boolean newAnvil) {
		this.replanIn = newAnvil ? 5 : -1;
		this.screen = screen;
		this.mc = Minecraft.getInstance();
		this.font = mc.font;
		this.bookIcon = glint(new ItemStack(Items.ENCHANTED_BOOK));
		this.lastAnvilStack = anvilItem(0).copy();
		PlannerState.catalog();
	}

	// ---------------------------------------------------------------- layout

	private void layout() {
		int w = screen.width;
		int h = screen.height;
		int ax = (w - ANVIL_W) / 2;
		int ay = (h - ANVIL_H) / 2;
		anvilX = ax;
		anvilY = ay;
		int side = ax - 2 * GAP;
		int ph = Math.min(h - 8, 230);
		int py = (h - ph) / 2;

		if (side >= 112) {
			int pw = Math.min(side, 170);
			compact = false;
			setup = new Rect(ax - GAP - pw, py, pw, ph);
			order = new Rect(ax + ANVIL_W + GAP, py, pw, ph);
		} else {
			int pw = Math.min(w - 8, 340);
			int px = (w - pw) / 2;
			int half = pw / 2;
			compact = true;
			setup = new Rect(px, py, half - 1, ph);
			order = new Rect(px + half + 1, py, pw - half - 1, ph);
		}
		// above the anvil's top-right corner, or tucked into that corner when there is no room
		toggle = ay >= 22
				? new Rect(ax + ANVIL_W - 20, ay - 21, 20, 20)
				: new Rect(ax + ANVIL_W - 19, ay + 3, 15, 15);
	}

	// ---------------------------------------------------------------- frame

	/** Every client tick: follow what is in the anvil (independent of how often frames are drawn). */
	public void tick() {
		try {
			if (replanIn > 0 && --replanIn == 0) {
				PlannerState.recompute(); // so books you have now (e.g. with several enchantments) count
			}
			syncAnvilItem();
		} catch (RuntimeException e) {
			EnchantPlannerClient.LOGGER.error("Enchant Planner failed to read the anvil", e);
		}
	}

	public void extract(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		try {
			layout();
			hotspots.clear();
			g.nextStratum();

			if (PlannerState.visible) {
				if (!compact) {
					extractNextStepSlots(g);
				}
				extractSetup(g, mouseX, mouseY);
				extractOrder(g, mouseX, mouseY);
			}
			extractToggle(g, mouseX, mouseY);
		} catch (RuntimeException e) {
			// never take the anvil screen down with us
			EnchantPlannerClient.LOGGER.error("Enchant Planner failed to draw", e);
			PlannerState.visible = false;
		}
	}

	/** Outline the inventory slots holding the item and book for the next step of the plan. */
	private void extractNextStepSlots(GuiGraphicsExtractor g) {
		EnchantSolver.Result plan = PlannerState.result();
		int next = InventoryCheck.nextStep(plan);
		if (next < 0 || PlannerState.computing()) {
			return;
		}
		EnchantSolver.Step step = plan.steps().get(next);
		// a slow pulse so it catches the eye without flashing
		int alpha = 0x50 + (int) (0x40 * (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 250.0)));
		for (EnchantSolver.Piece piece : List.of(step.left(), step.right())) {
			if (PlanMatcher.matches(piece, anvilItem(0)) || PlanMatcher.matches(piece, anvilItem(1))) {
				continue; // already in the anvil
			}
			Slot slot = InventoryCheck.findInInventory(screen.getMenu(), piece);
			if (slot != null) {
				int x = anvilX + slot.x;
				int y = anvilY + slot.y;
				g.fill(x, y, x + 16, y + 16, (alpha << 24) | 0xFFD040);
				g.outline(x - 1, y - 1, 18, 18, 0xFFFFD040);
			}
		}
	}

	private void extractToggle(GuiGraphicsExtractor g, int mx, int my) {
		boolean hover = toggle.contains(mx, my);
		button(g, toggle, hover);
		int ix = toggle.x() + (toggle.w() - 16) / 2;
		int iy = toggle.y() + (toggle.h() - 16) / 2;
		if (toggle.w() < 18) {
			g.pose().pushMatrix();
			g.pose().translate(toggle.x() + 1.5f, toggle.y() + 1.5f);
			g.pose().scale(0.75f, 0.75f);
			g.item(bookIcon, 0, 0);
			g.pose().popMatrix();
		} else {
			g.item(bookIcon, ix, iy);
		}
		hotspots.add(new Hotspot(toggle, PlannerState::toggleVisible));
		if (hover) {
			g.setTooltipForNextFrame(font, Component.translatable("enchant_planner.toggle"), mx, my);
		}
	}

	// ---------------------------------------------------------------- setup panel

	private void extractSetup(GuiGraphicsExtractor g, int mx, int my) {
		Rect r = setup;
		panel(g, r);
		hotspots.add(new Hotspot(r, () -> {}));
		g.text(font, Component.translatable("enchant_planner.title"), r.x() + PAD + 1, r.y() + 6, TEXT_DARK, false);

		// item type selector: < [icon name] >
		List<ItemType> types = ItemType.all();
		int idx = Math.max(0, types.indexOf(PlannerState.target()));
		int y = r.y() + 17;
		Rect prev = new Rect(r.x() + PAD, y + 2, 11, 16);
		Rect next = new Rect(r.right() - PAD - 11, y + 2, 11, 16);
		Rect item = new Rect(prev.right() + 2, y, next.x() - prev.right() - 4, 20);
		textButton(g, prev, "<", mx, my, () -> selectTarget(types.get(Math.floorMod(idx - 1, types.size()))));
		textButton(g, next, ">", mx, my, () -> selectTarget(types.get(Math.floorMod(idx + 1, types.size()))));
		inset(g, item);
		if (item.contains(mx, my)) {
			g.fill(item.x() + 1, item.y() + 1, item.right() - 1, item.bottom() - 1, 0x30FFFFFF);
			g.setTooltipForNextFrame(font, Component.translatable("enchant_planner.choose_item"), mx, my);
		}
		ItemType current = types.get(idx);
		g.item(targetIcon(), item.x() + 2, item.y() + 2);
		g.text(font, ellipsize(current.name().getString(), item.w() - 22), item.x() + 20, item.y() + 6, TEXT_LIGHT, true);
		hotspots.add(new Hotspot(item, () -> pickingItem = !pickingItem));

		// bottom buttons
		int by = r.bottom() - PAD - 14;
		int bw = r.w() - 2 * PAD;
		Rect mode = new Rect(r.x() + PAD, by, bw * 2 / 3 - 1, 14);
		Rect clear = new Rect(mode.right() + 2, by, r.right() - PAD - mode.right() - 2, 14);
		boolean levels = PlannerState.mode == EnchantSolver.Mode.LEVELS;
		textButton(g, mode, Component.translatable(levels ? "enchant_planner.mode.levels" : "enchant_planner.mode.prior_work").getString(),
				mx, my, PlannerState::toggleMode);
		if (mode.contains(mx, my)) {
			g.setTooltipForNextFrame(font, Component.translatable(levels ? "enchant_planner.mode.levels.tip" : "enchant_planner.mode.prior_work.tip"), mx, my);
		}
		textButton(g, clear, Component.translatable("enchant_planner.clear").getString(), mx, my, PlannerState::clear);

		// "From your Iron Helmet [x]" when the plan starts from the item in the anvil
		int listTop = item.bottom() + 4;
		PlannerState.StartItem start = PlannerState.startItem();
		if (start != null) {
			Rect line = new Rect(r.x() + PAD, item.bottom() + 3, bw, 11);
			Rect close = new Rect(line.right() - 10, line.y() + 1, 9, 9);
			String name = start.item().getName(new ItemStack(start.item())).getString();
			String text = Component.translatable("enchant_planner.start_item", name).getString();
			g.text(font, ellipsize(text, line.w() - 13), line.x() + 1, line.y() + 1, 0xFF1F5F9F, false);
			miniButton(g, close, "x", mx, my, true);
			hotspots.add(new Hotspot(close, PlannerState::clearStartItem));
			if (line.contains(mx, my)) {
				g.setTooltipForNextFrame(font, Component.translatable(close.contains(mx, my) ? "enchant_planner.start_item.clear"
						: "enchant_planner.start_item.tip", name, start.repairCost()), mx, my);
			}
			listTop = line.bottom() + 2;
		}

		setupList = new Rect(r.x() + PAD, listTop, bw, by - 3 - listTop);
		inset(g, setupList);
		if (pickingItem) {
			extractItemGrid(g, types, mx, my);
		} else {
			extractEnchantList(g, mx, my);
		}
	}

	private void extractItemGrid(GuiGraphicsExtractor g, List<ItemType> types, int mx, int my) {
		Rect area = inner(setupList);
		int cols = Math.max(1, area.w() / CELL);
		int rows = (types.size() + cols - 1) / cols;
		int x0 = area.x() + (area.w() - cols * CELL) / 2;
		gridScroll = Mth.clamp(gridScroll, 0, Math.max(0, rows * CELL - area.h()));

		g.enableScissor(area.x(), area.y(), area.right(), area.bottom());
		for (int i = 0; i < types.size(); i++) {
			ItemType t = types.get(i);
			Rect cell = new Rect(x0 + (i % cols) * CELL, area.y() + (i / cols) * CELL - gridScroll, CELL, CELL);
			if (cell.bottom() < area.y() || cell.y() > area.bottom()) {
				continue;
			}
			boolean hover = area.contains(mx, my) && cell.contains(mx, my);
			if (t.equals(PlannerState.target())) {
				g.fill(cell.x(), cell.y(), cell.right(), cell.bottom(), 0x80FFE070);
			} else if (hover) {
				g.fill(cell.x(), cell.y(), cell.right(), cell.bottom(), 0x50FFFFFF);
			}
			g.item(t.icon(), cell.x() + 1, cell.y() + 1);
			if (hover) {
				g.setTooltipForNextFrame(font, t.name(), mx, my);
				hotspots.add(new Hotspot(cell, () -> {
					selectTarget(t);
					pickingItem = false;
				}));
			}
		}
		g.disableScissor();
		scrollbar(g, area, rows * CELL, gridScroll);
	}

	private void extractEnchantList(GuiGraphicsExtractor g, int mx, int my) {
		Rect area = inner(setupList);
		List<EnchantCatalog.Entry> entries = PlannerState.catalog().entries();
		if (entries.isEmpty()) {
			wrapped(g, Component.translatable("enchant_planner.none_available"), area.x() + 3, area.y() + 3, area.w() - 6, TEXT_LIGHT);
			return;
		}
		// lay out rows: a header before each type section, a thin line around exclusive groups
		Map<Integer, Integer> clusterSize = new HashMap<>();
		for (EnchantCatalog.Entry e : entries) {
			clusterSize.merge(e.cluster(), 1, Integer::sum);
		}
		int[] rowY = new int[entries.size()];
		List<int[]> headers = new ArrayList<>(); // {y, entry index}
		List<Integer> separators = new ArrayList<>();
		int cursor = 0;
		for (int i = 0; i < entries.size(); i++) {
			EnchantCatalog.Entry e = entries.get(i);
			EnchantCatalog.Entry prev = i > 0 ? entries.get(i - 1) : null;
			if (prev == null || prev.type() != e.type()) {
				if (prev != null) {
					cursor += 2;
				}
				headers.add(new int[]{cursor, i});
				cursor += HEADER_H;
			} else if (prev.cluster() != e.cluster() && (clusterSize.get(prev.cluster()) > 1 || clusterSize.get(e.cluster()) > 1)) {
				separators.add(cursor);
			}
			rowY[i] = cursor;
			cursor += ROW_H;
		}
		int contentH = cursor;
		enchantScroll = Mth.clamp(enchantScroll, 0, Math.max(0, contentH - area.h()));

		List<EnchantCatalog.Entry> selected = new ArrayList<>();
		for (EnchantCatalog.Entry e : entries) {
			if (PlannerState.level(e) > 0) {
				selected.add(e);
			}
		}

		g.enableScissor(area.x(), area.y(), area.right(), area.bottom());
		int top = area.y() - enchantScroll;
		for (int[] h : headers) {
			int hy = top + h[0];
			if (hy + HEADER_H < area.y() || hy > area.bottom()) {
				continue;
			}
			g.fill(area.x(), hy, area.right() - 3, hy + HEADER_H - 1, 0x40000000);
			int color = entries.get(h[1]).type() == EnchantCatalog.Type.CURSE ? TEXT_CURSE : 0xFFFFE070;
			g.text(font, ellipsize(entries.get(h[1]).type().title().getString(), area.w() - 8), area.x() + 3, hy + 1, color, true);
		}
		for (int sy : separators) {
			g.horizontalLine(area.x() + 2, area.right() - 5, top + sy, 0x50FFFFFF);
		}
		boolean inArea = area.contains(mx, my);
		for (int i = 0; i < entries.size(); i++) {
			EnchantCatalog.Entry e = entries.get(i);
			int ry = top + rowY[i];
			if (ry + ROW_H < area.y() || ry > area.bottom()) {
				continue;
			}
			Rect row = new Rect(area.x(), ry, area.w() - 3, ROW_H);
			int level = PlannerState.level(e);
			boolean on = level > 0;
			List<String> conflicts = new ArrayList<>();
			if (!on) {
				for (EnchantCatalog.Entry s : selected) {
					if (!e.compatibleWith(s)) {
						conflicts.add(s.name());
					}
				}
			}
			boolean blocked = !conflicts.isEmpty();
			boolean hover = inArea && row.contains(mx, my);
			int owned = PlannerState.ownedLevel(e);
			boolean onItem = owned > 0 && owned >= level; // already there: no book needed

			if (hover && !blocked) {
				// registered before the level buttons so those win (hotspots are tested newest first)
				hotspots.add(new Hotspot(row, () -> PlannerState.setLevel(e, on ? 0 : e.maxLevel())));
			}

			if (on) {
				g.fill(row.x(), row.y(), row.right(), row.bottom(), 0x40FFE070);
			} else if (hover && !blocked) {
				g.fill(row.x(), row.y(), row.right(), row.bottom(), 0x30FFFFFF);
			}

			// checkbox
			int cx = row.x() + 2;
			int cy = row.y() + 2;
			g.fill(cx, cy, cx + 8, cy + 8, blocked ? 0xFF6A6A6A : 0xFF202020);
			g.fill(cx + 1, cy + 1, cx + 7, cy + 7, blocked ? 0xFF7E7E7E : 0xFFB0B0B0);
			if (on) {
				g.fill(cx + 2, cy + 2, cx + 6, cy + 6, onItem ? COLOR_ON_ITEM : 0xFF3FB43F);
			}

			boolean levelControls = on && e.maxLevel() > 1;
			int nameW = row.w() - 14 - (levelControls ? 34 : 2);
			int color = blocked ? TEXT_DIM : e.curse() ? TEXT_CURSE : onItem ? COLOR_ON_ITEM : on ? TEXT_GOLD : TEXT_LIGHT;
			String label = on && !levelControls && e.maxLevel() > 1 ? e.displayName(level).getString() : e.name();
			g.text(font, ellipsize(label, nameW), row.x() + 13, row.y() + 2, color, !blocked);

			if (levelControls) {
				Rect minus = new Rect(row.right() - 34, row.y() + 1, 9, 10);
				Rect plus = new Rect(row.right() - 10, row.y() + 1, 9, 10);
				miniButton(g, minus, "-", mx, my, inArea);
				miniButton(g, plus, "+", mx, my, inArea);
				String roman = Component.translatable("enchantment.level." + level).getString();
				g.centeredText(font, Component.literal(roman), row.right() - 17, row.y() + 2, TEXT_GOLD);
				if (inArea) {
					hotspots.add(new Hotspot(minus, () -> PlannerState.setLevel(e, Math.max(1, level - 1))));
					hotspots.add(new Hotspot(plus, () -> PlannerState.setLevel(e, level + 1)));
				}
			}

			if (hover) {
				if (blocked) {
					g.setTooltipForNextFrame(font, Component.translatable("enchant_planner.incompatible", String.join(", ", conflicts)), mx, my);
				} else if (owned > 0) {
					g.setTooltipForNextFrame(font, Component.translatable(onItem ? "enchant_planner.on_item" : "enchant_planner.on_item.upgrade",
							e.displayName(owned)), mx, my);
				} else if (font.width(label) > nameW) {
					g.setTooltipForNextFrame(font, Component.literal(label), mx, my);
				}
			}
		}
		g.disableScissor();
		scrollbar(g, area, contentH, enchantScroll);
	}

	// ---------------------------------------------------------------- order panel

	private void extractOrder(GuiGraphicsExtractor g, int mx, int my) {
		Rect r = order;
		panel(g, r);
		hotspots.add(new Hotspot(r, () -> {}));
		g.text(font, Component.translatable("enchant_planner.order"), r.x() + PAD + 1, r.y() + 6, TEXT_DARK, false);

		int tx = r.x() + PAD + 1;
		int tw = r.w() - 2 * PAD - 2;
		int y = r.y() + 18;
		EnchantSolver.Result res = PlannerState.result();
		int count = PlannerState.selectedCount();
		stepList = null;

		if (count == 0) {
			wrapped(g, Component.translatable("enchant_planner.select_hint"), tx, y, tw, TEXT_DARK);
			return;
		}
		if (PlannerState.solvedEntries().size() > PlannerState.MAX_BOOKS) {
			wrapped(g, Component.translatable("enchant_planner.too_many", PlannerState.MAX_BOOKS), tx, y, tw, 0xFFAA2020);
			return;
		}
		if (PlannerState.computing()) {
			String dots = ".".repeat((int) (System.currentTimeMillis() / 300 % 4));
			g.text(font, Component.translatable("enchant_planner.computing").append(dots), tx, y, TEXT_DARK, false);
			return;
		}
		if (PlannerState.impossible()) {
			wrapped(g, Component.translatable(PlannerState.startItem() != null && PlannerState.startItem().repairCost() > 0
					? "enchant_planner.impossible.used" : "enchant_planner.impossible"), tx, y, tw, 0xFFAA2020);
			return;
		}
		if (res == null) {
			return;
		}
		if (res.steps().isEmpty()) {
			wrapped(g, Component.translatable(PlannerState.startItem() != null ? "enchant_planner.all_owned" : "enchant_planner.nothing_to_do"),
					tx, y, tw, TEXT_DARK);
			return;
		}

		boolean canAfford = canAfford(res.totalLevels());
		g.text(font, Component.translatable("enchant_planner.total", res.totalLevels()), tx, y, canAfford ? 0xFF207020 : 0xFFAA2020, false);
		y += 10;
		g.text(font, ellipsize(Component.translatable("enchant_planner.total_xp", (int) res.totalXp(), res.maxStepLevels()).getString(), tw),
				tx, y, TEXT_DARK, false);
		y += 10;
		List<EnchantSolver.Piece> needed = InventoryCheck.neededBooks(res);
		List<EnchantSolver.Piece> missing = InventoryCheck.missingBooks(screen.getMenu(), res);
		if (!needed.isEmpty()) {
			Component books = Component.translatable("enchant_planner.books", needed.size() - missing.size(), needed.size());
			g.text(font, ellipsize(books.getString(), tw), tx, y, missing.isEmpty() ? 0xFF207020 : 0xFFAA2020, false);
			Rect booksLine = new Rect(tx, y - 1, Math.min(tw, font.width(books)), 10);
			if (booksLine.contains(mx, my)) {
				List<Component> tip = new ArrayList<>();
				if (missing.isEmpty()) {
					tip.add(Component.translatable("enchant_planner.books.all").withColor(0xFF80FF80));
				} else {
					tip.add(Component.translatable("enchant_planner.books.missing").withColor(0xFFFF8080));
					for (EnchantSolver.Piece p : missing) {
						tip.add(Component.literal("- ").append(describe(p)));
					}
				}
				g.setComponentTooltipForNextFrame(font, tip, mx, my);
			}
			y += 10;
		}
		y += 2;

		int hintY = r.bottom() - PAD - 9;
		stepList = new Rect(r.x() + PAD, y, r.w() - 2 * PAD, hintY - 2 - y);
		inset(g, stepList);
		g.text(font, ellipsize(Component.translatable("enchant_planner.step_hint").getString(), tw), tx, hintY, 0xFF606060, false);

		Rect area = inner(stepList);
		int textW = area.w() - 8;
		List<List<FormattedCharSequence>> texts = new ArrayList<>();
		int contentH = 0;
		for (EnchantSolver.Step s : res.steps()) {
			MutableComponent line = describe(s.left()).copy().append(Component.literal(" + ").withColor(0xFFC0C0C0)).append(describe(s.right()));
			List<FormattedCharSequence> lines = font.split(line, textW);
			texts.add(lines);
			contentH += stepHeight(lines);
		}
		stepScroll = Mth.clamp(stepScroll, 0, Math.max(0, contentH - area.h()));

		ItemStack slot0 = anvilItem(0);
		ItemStack slot1 = anvilItem(1);
		boolean inArea = area.contains(mx, my);
		g.enableScissor(area.x(), area.y(), area.right(), area.bottom());
		int sy = area.y() - stepScroll;
		for (int i = 0; i < res.steps().size(); i++) {
			EnchantSolver.Step s = res.steps().get(i);
			List<FormattedCharSequence> lines = texts.get(i);
			int h = stepHeight(lines);
			Rect block = new Rect(area.x(), sy, area.w() - 3, h);
			sy += h;
			if (block.bottom() < area.y() || block.y() > area.bottom()) {
				continue;
			}
			boolean done = PlannerState.doneSteps.contains(i);
			boolean current = !done && PlanMatcher.matches(s.left(), slot0) && PlanMatcher.matches(s.right(), slot1);
			boolean hover = inArea && block.contains(mx, my);

			if (current) {
				g.fill(block.x(), block.y(), block.right(), block.bottom() - 1, 0x6030C030);
				g.outline(block.x(), block.y(), block.w(), block.h() - 1, 0xFF60FF60);
			} else if (hover) {
				g.fill(block.x(), block.y(), block.right(), block.bottom() - 1, 0x30FFFFFF);
			}
			if (i > 0) {
				g.horizontalLine(block.x() + 2, block.right() - 3, block.y(), 0x60FFFFFF);
			}

			int bx = block.x() + 3;
			int by = block.y() + 2;
			g.text(font, done ? "✔" : (i + 1) + ".", bx, by + 4, done ? 0xFF80FF80 : TEXT_LIGHT, true);
			g.item(icon(s.left()), bx + 13, by);
			bookMarker(g, s.left(), bx + 13, by);
			g.text(font, "+", bx + 31, by + 4, TEXT_LIGHT, true);
			g.item(icon(s.right()), bx + 38, by);
			bookMarker(g, s.right(), bx + 38, by);
			String cost = Component.translatable("enchant_planner.levels", s.levels()).getString();
			g.text(font, cost, block.right() - 3 - font.width(cost), by + 4, canAfford(s.levels()) ? COST_OK : COST_BAD, true);

			int ly = by + 18;
			for (FormattedCharSequence l : lines) {
				g.text(font, l, bx + 1, ly, done ? 0xFFB8B8B8 : TEXT_LIGHT, true);
				ly += 9;
			}
			if (done) {
				g.fill(block.x(), block.y(), block.right(), block.bottom() - 1, 0x60404040);
			}

			if (hover) {
				int index = i;
				hotspots.add(new Hotspot(block, () -> {
					if (!PlannerState.doneSteps.remove(index)) {
						PlannerState.doneSteps.add(index);
					}
				}));
				List<Component> tip = new ArrayList<>();
				tip.add(Component.translatable("enchant_planner.step", i + 1).withColor(0xFFFFE070));
				tip.add(Component.translatable("enchant_planner.slot1", describe(s.left())));
				tip.add(Component.translatable("enchant_planner.slot2", describe(s.right())));
				tip.add(Component.translatable("enchant_planner.cost", s.levels(), (int) s.xp()).withColor(0xFF80FF20));
				tip.add(Component.translatable("enchant_planner.prior_work", s.priorWorkPenaltyAfter()).withColor(0xFFA0A0A0));
				if (current) {
					tip.add(Component.translatable("enchant_planner.current").withColor(0xFF60FF60));
				}
				g.setComponentTooltipForNextFrame(font, tip, mx, my);
			}
		}
		g.disableScissor();
		scrollbar(g, area, contentH, stepScroll);
	}

	/** A small dot on a book the plan starts from: green if you have it, red if you still need it. */
	private void bookMarker(GuiGraphicsExtractor g, EnchantSolver.Piece p, int x, int y) {
		if (PlannerState.result() == null || !InventoryCheck.isStartingBook(PlannerState.result(), p)) {
			return;
		}
		boolean have = InventoryCheck.has(screen.getMenu(), p);
		g.fill(x + 11, y + 11, x + 16, y + 16, 0xFF000000);
		g.fill(x + 12, y + 12, x + 15, y + 15, have ? 0xFF50E050 : 0xFFE04040);
	}

	private static int stepHeight(List<FormattedCharSequence> lines) {
		return 21 + lines.size() * 9 + 2;
	}

	private Component describe(EnchantSolver.Piece p) {
		if (p.isTarget()) {
			ItemStack s = targetIcon();
			return PlannerState.target().contains(anvilItem(0)) ? s.getItem().getName(s) : PlannerState.target().name();
		}
		List<EnchantCatalog.Entry> entries = PlannerState.solvedEntries();
		List<Integer> levels = PlannerState.solvedLevels();
		MutableComponent out = Component.empty();
		for (int i = 0; i < p.enchants().length; i++) {
			int id = p.enchants()[i];
			if (i > 0) {
				out.append(", ");
			}
			EnchantCatalog.Entry e = entries.get(id);
			out.append(Component.literal(e.displayName(levels.get(id)).getString()).withColor(e.curse() ? TEXT_CURSE : 0xFFFFFFFF));
		}
		return out;
	}

	private ItemStack icon(EnchantSolver.Piece p) {
		if (!p.isTarget()) {
			return bookIcon;
		}
		ItemStack s = targetIcon();
		return p.enchants().length > 0 || !PlannerState.startEnchants().isEmpty() ? glint(s) : s;
	}

	/** The item in the anvil if it is of the planned type (e.g. an iron helmet), else the type's icon. */
	private ItemStack targetIcon() {
		ItemStack in = anvilItem(0);
		return PlannerState.target().contains(in) ? new ItemStack(in.getItem()) : PlannerState.target().icon();
	}

	private boolean canAfford(int levels) {
		return mc.player == null || mc.player.hasInfiniteMaterials() || mc.player.experienceLevel >= levels;
	}

	// ---------------------------------------------------------------- input

	public boolean mouseClicked(double mx, double my, int button) {
		if (toggle == null || button != InputConstants.MOUSE_BUTTON_LEFT && !(PlannerState.visible && (setup.contains(mx, my) || order.contains(mx, my)))) {
			return false;
		}
		for (int i = hotspots.size() - 1; i >= 0; i--) {
			Hotspot h = hotspots.get(i);
			if (h.rect().contains(mx, my)) {
				if (button == InputConstants.MOUSE_BUTTON_LEFT) { // 0 on 26.2 (GLFW), 1 on 26.3 (SDL)
					h.action().run();
					if (!isPanelBackground(h)) {
						click();
					}
				}
				return true;
			}
		}
		return false;
	}

	private boolean isPanelBackground(Hotspot h) {
		return h.rect().equals(setup) || h.rect().equals(order);
	}

	public boolean mouseScrolled(double mx, double my, double amount) {
		if (!PlannerState.visible || setup == null) {
			return false;
		}
		int delta = (int) Math.signum(amount) * -ROW_H * 2;
		if (setupList != null && setupList.contains(mx, my)) {
			if (pickingItem) {
				gridScroll += (int) Math.signum(amount) * -CELL;
			} else {
				enchantScroll += delta;
			}
			return true;
		}
		if (stepList != null && stepList.contains(mx, my)) {
			stepScroll += delta * 2;
			return true;
		}
		return setup.contains(mx, my) || order.contains(mx, my);
	}

	// ---------------------------------------------------------------- helpers

	private void syncAnvilItem() {
		ItemStack in = anvilItem(0);
		ItemStack book = anvilItem(1);
		boolean hadResult = !lastResultStack.isEmpty();
		boolean bookUsed = !lastBookStack.isEmpty() && !ItemStack.matches(book, lastBookStack);
		int stepInAnvil = lastStepInAnvil;
		lastBookStack = book.copy();
		lastResultStack = anvilItem(2).copy();
		lastStepInAnvil = stepInAnvil(in, book);
		if (PlannerState.selectionChanged) {
			// what to plan changed: compare the item with the normal plan first, not with a plan
			// that starts from the item (that comes back below if the item still doesn't fit)
			PlannerState.selectionChanged = false;
			if (PlannerState.startItem() != null && PlannerState.target().contains(in)) {
				PlannerState.suspendStartItem();
			}
		}
		// a new plan (e.g. the selection changed) is compared with the item again
		boolean newPlan = PlannerState.result() != lastPlan && !PlannerState.computing();
		if ((ItemStack.matches(in, lastAnvilStack) && !newPlan) || (PlannerState.computing() && !in.isEmpty())) {
			return; // unchanged, or wait for the plan to compare the item with
		}
		lastPlan = PlannerState.result();
		boolean hadItem = !lastAnvilStack.isEmpty();
		if (!ItemStack.matches(in, lastAnvilStack)) {
			PlannerState.untickedForItem.clear();
		}
		lastAnvilStack = in.copy();
		if (in.isEmpty()) {
			if (hadItem && hadResult && bookUsed) {
				// a finished result was taken: the plan goes on, and the step that was in the anvil is done
				if (stepInAnvil >= 0) {
					PlannerState.doneSteps.add(stepInAnvil);
				}
			} else if (hadItem) {
				// taken out by hand: stop planning from it, but keep the selection as it is
				PlannerState.releaseStartItem();
			}
			return;
		}
		// select the item's type (an iron helmet selects "Helmet"); books are left alone, since a
		// book in the first slot is usually a book + book step of the plan
		ItemType type = ItemType.all().stream().filter(t -> t.contains(in)).findFirst().orElse(null);
		boolean book1 = type != null && type.isBook();
		if (!book1 && !PlannerState.target().contains(in) && (type != null || EnchantCatalog.isEnchantTarget(in.getItem()))) {
			selectTarget(ItemType.of(in));
			pickingItem = false;
		}
		if (PlannerState.target().contains(in) && !PlannerState.target().isBook()) {
			// an item at some stage of the plan sets the checklist; any other item becomes the new start
			Set<Integer> stage = plannedStage(in);
			if (stage != null) {
				applyStage(stage);
			} else {
				captureStartItem(in);
			}
		}
	}

	/** Index of the not-yet-done step whose two inputs are in the anvil right now, or -1. */
	private int stepInAnvil(ItemStack left, ItemStack right) {
		EnchantSolver.Result res = PlannerState.result();
		if (res == null || left.isEmpty() || right.isEmpty()) {
			return -1;
		}
		for (int i = 0; i < res.steps().size(); i++) {
			EnchantSolver.Step s = res.steps().get(i);
			if (!PlannerState.doneSteps.contains(i) && PlanMatcher.matches(s.left(), left) && PlanMatcher.matches(s.right(), right)) {
				return i;
			}
		}
		return -1;
	}

	/** Plan from this item: keep its enchantments and pay its repair cost. A fresh item resets to a fresh plan. */
	private void captureStartItem(ItemStack in) {
		Map<ResourceKey<Enchantment>, Integer> enchants = new HashMap<>(PlanMatcher.enchantLevels(in));
		enchants.keySet().removeIf(k -> PlannerState.catalog().find(k) == null);
		enchants.keySet().removeAll(PlannerState.untickedForItem); // you unticked these for this item
		int repairCost = in.getOrDefault(DataComponents.REPAIR_COST, 0);
		PlannerState.StartItem current = PlannerState.startItem();
		if (enchants.isEmpty() && repairCost == 0) {
			PlannerState.clearStartItem();
		} else if (current == null || current.item() != in.getItem() || !current.enchants().equals(enchants)
				|| current.repairCost() != repairCost) {
			PlannerState.setStartItem(in.getItem(), enchants, repairCost);
		}
	}

	/**
	 * The stage of the current plan this item is at, as the planned enchantments it already has
	 * (empty: not started, all: finished), or {@code null} if it matches no stage of the plan.
	 */
	private Set<Integer> plannedStage(ItemStack in) {
		EnchantSolver.Result res = PlannerState.result();
		if (res == null) {
			return null;
		}
		int[] all = new int[PlannerState.solvedEntries().size()];
		for (int i = 0; i < all.length; i++) {
			all[i] = i;
		}
		int finalWork = EnchantSolver.workFromRepairCost(res.finalPriorWork());
		if (PlanMatcher.matches(new EnchantSolver.Piece(true, all, finalWork), in, false)) { // finished, whatever it cost
			return ids(all);
		}
		for (EnchantSolver.Step s : res.steps()) {
			if (s.left().isTarget() && PlanMatcher.matches(s.left(), in)) {
				return ids(s.left().enchants());
			}
		}
		return null;
	}

	/**
	 * Tick the steps that lead to this stage of the item and untick later steps on the item. Book +
	 * book steps are ticked once their book is part of the item, otherwise left as they are.
	 */
	private static void applyStage(Set<Integer> stage) {
		EnchantSolver.Result res = PlannerState.result();
		for (int i = 0; i < res.steps().size(); i++) {
			EnchantSolver.Step s = res.steps().get(i);
			Set<Integer> made = ids(s.left().enchants());
			made.addAll(ids(s.right().enchants()));
			if (stage.containsAll(made)) {
				PlannerState.doneSteps.add(i);
			} else if (s.left().isTarget()) {
				PlannerState.doneSteps.remove(i);
			}
		}
	}

	private static Set<Integer> ids(int[] ids) {
		Set<Integer> out = new HashSet<>();
		for (int id : ids) {
			out.add(id);
		}
		return out;
	}

	private ItemStack anvilItem(int slot) {
		return screen.getMenu().getSlot(slot).getItem();
	}

	private void selectTarget(ItemType type) {
		if (!type.equals(PlannerState.target())) {
			enchantScroll = 0;
		}
		PlannerState.setTarget(type);
	}

	private static ItemStack glint(ItemStack s) {
		s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		return s;
	}

	private void click() {
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
	}

	private String ellipsize(String s, int width) {
		if (font.width(s) <= width) {
			return s;
		}
		return font.plainSubstrByWidth(s, Math.max(0, width - font.width("..."))) + "...";
	}

	private void wrapped(GuiGraphicsExtractor g, Component text, int x, int y, int width, int color) {
		for (FormattedCharSequence line : font.split(text, width)) {
			g.text(font, line, x, y, color, false);
			y += 10;
		}
	}

	private static Rect inner(Rect r) {
		return new Rect(r.x() + 1, r.y() + 1, r.w() - 2, r.h() - 2);
	}

	/** Vanilla container look: black rim, white top-left bevel, grey bottom-right bevel. */
	private static void panel(GuiGraphicsExtractor g, Rect r) {
		g.fill(r.x() + 1, r.y(), r.right() - 1, r.bottom(), 0xFF000000);
		g.fill(r.x(), r.y() + 1, r.right(), r.bottom() - 1, 0xFF000000);
		g.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, 0xFFFFFFFF);
		g.fill(r.x() + 2, r.y() + 2, r.right() - 1, r.bottom() - 1, 0xFF555555);
		g.fill(r.x() + 2, r.y() + 2, r.right() - 2, r.bottom() - 2, 0xFFC6C6C6);
	}

	/** Slot-style sunken area. */
	private static void inset(GuiGraphicsExtractor g, Rect r) {
		g.fill(r.x(), r.y(), r.right(), r.bottom(), 0xFF373737);
		g.fill(r.x() + 1, r.y() + 1, r.right(), r.bottom(), 0xFFFFFFFF);
		g.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, 0xFF8B8B8B);
	}

	private static void button(GuiGraphicsExtractor g, Rect r, boolean hover) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, hover ? BUTTON_HOVER : BUTTON, r.x(), r.y(), r.w(), r.h());
	}

	private void textButton(GuiGraphicsExtractor g, Rect r, String label, int mx, int my, Runnable action) {
		button(g, r, r.contains(mx, my));
		g.centeredText(font, Component.literal(ellipsize(label, r.w() - 4)), r.x() + r.w() / 2, r.y() + (r.h() - 8) / 2, TEXT_LIGHT);
		hotspots.add(new Hotspot(r, action));
	}

	private void miniButton(GuiGraphicsExtractor g, Rect r, String label, int mx, int my, boolean inArea) {
		boolean hover = inArea && r.contains(mx, my);
		g.fill(r.x(), r.y(), r.right(), r.bottom(), hover ? 0xFFE0E0E0 : 0xFF505050);
		g.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, hover ? 0xFF7090D0 : 0xFF6E6E6E);
		g.centeredText(font, Component.literal(label), r.x() + r.w() / 2 + 1, r.y() + 1, TEXT_LIGHT);
	}

	private static void scrollbar(GuiGraphicsExtractor g, Rect area, int contentH, int scroll) {
		if (contentH <= area.h()) {
			return;
		}
		int trackX = area.right() - 2;
		int barH = Math.max(8, area.h() * area.h() / contentH);
		int barY = area.y() + (area.h() - barH) * scroll / (contentH - area.h());
		g.fill(trackX, area.y(), trackX + 2, area.bottom(), 0x40000000);
		g.fill(trackX, barY, trackX + 2, barY + barH, 0xFFE0E0E0);
	}
}
