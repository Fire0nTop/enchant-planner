package com.enchantplanner.test;

import com.enchantplanner.EnchantCatalog;
import com.enchantplanner.ItemType;
import com.enchantplanner.PlannerConfig;
import com.enchantplanner.PlannerConfigScreen;
import com.enchantplanner.PlannerState;
import com.enchantplanner.solver.EnchantSolver;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Opens an anvil, then for several window sizes and GUI scales clicks the planner's book button
 * like a player would and checks the panels open. Screenshots land in build/run/clientGameTest.
 */
public class AnvilPlannerGameTest implements FabricClientGameTest {
	private static final int[][] WINDOWS = {{854, 480}, {1280, 720}, {1920, 1080}, {2560, 1440}};
	private static final int[] GUI_SCALES = {0, 2, 3, 4}; // 0 = auto

	private static void openAnvil(TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			BlockPos pos = player.blockPosition().north(2);
			BlockState anvil = Blocks.ANVIL.defaultBlockState();
			player.level().setBlockAndUpdate(pos, anvil);
			player.openMenu(anvil.getMenuProvider(player.level(), pos));
		});
	}

	private static void putInAnvil(TestSingleplayerContext world, Item item) {
		putInAnvil(world, item, 0, Map.of());
	}

	private static void putInAnvil(TestSingleplayerContext world, Item item, int repairCost,
			Map<ResourceKey<Enchantment>, Integer> enchants) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			ItemStack stack = item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
			var lookup = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
			enchants.forEach((key, level) -> stack.enchant(lookup.getOrThrow(key), level));
			if (repairCost > 0) {
				stack.set(DataComponents.REPAIR_COST, repairCost);
			}
			player.containerMenu.getSlot(0).set(stack);
			player.containerMenu.broadcastChanges();
		});
	}

	/** {isItem, enchantment levels, repair cost} for a piece of the current plan. */
	private static Object[] pieceSpec(EnchantSolver.Piece piece) {
		Map<ResourceKey<Enchantment>, Integer> levels = new java.util.HashMap<>();
		if (piece.isTarget()) {
			levels.putAll(PlannerState.startEnchants());
		}
		for (int id : piece.enchants()) {
			levels.put(PlannerState.solvedEntries().get(id).key(), PlannerState.solvedLevels().get(id));
		}
		return new Object[]{piece.isTarget(), levels, EnchantSolver.penalty(piece.priorWork())};
	}

	/** Done steps must be exactly those whose enchantments are all part of this stage of the item. */
	private static void expectChecklist(ClientGameTestContext ctx, String when, Set<Integer> stage) {
		String state = ctx.computeOnClient(mc -> {
			List<EnchantSolver.Step> steps = PlannerState.result().steps();
			java.util.TreeSet<Integer> expected = new java.util.TreeSet<>();
			for (int i = 0; i < steps.size(); i++) {
				Set<Integer> made = new java.util.HashSet<>();
				for (int id : steps.get(i).left().enchants()) {
					made.add(id);
				}
				for (int id : steps.get(i).right().enchants()) {
					made.add(id);
				}
				// book + book steps were all done earlier in this test and stay ticked
				if (stage.containsAll(made) || !steps.get(i).left().isTarget()) {
					expected.add(i);
				}
			}
			java.util.TreeSet<Integer> actual = new java.util.TreeSet<>(PlannerState.doneSteps);
			return expected.equals(actual) ? "ok" : "expected done " + expected + ", got " + actual;
		});
		if (!state.equals("ok")) {
			throw new AssertionError(when + ": " + state);
		}
	}

	private static void setLevelOnClient(ClientGameTestContext ctx, ResourceKey<Enchantment> key, int level) {
		ctx.runOnClient(mc -> {
			for (EnchantCatalog.Entry e : PlannerState.catalog().entries()) {
				if (e.key().equals(key)) {
					PlannerState.setLevel(e, level);
				}
			}
		});
		ctx.waitFor(mc -> !PlannerState.computing(), 200);
		ctx.waitTicks(3);
	}

	private static void expectAllDone(ClientGameTestContext ctx, String when) {
		String state = ctx.computeOnClient(mc -> describePlan());
		boolean ok = ctx.computeOnClient(mc -> PlannerState.startItem() == null && PlannerState.result() != null
				&& PlannerState.doneSteps.size() == PlannerState.result().steps().size());
		if (!ok) {
			throw new AssertionError(when + ": expected no starting item and every step done, got " + state);
		}
	}

	private static String describePlan() {
		StringBuilder b = new StringBuilder();
		b.append(PlannerState.startItem() == null ? "no start" : "start " + PlannerState.startItem().enchants().keySet()).append(" | selected ")
				.append(PlannerState.selection().keySet()).append(" | steps ");
		List<EnchantSolver.Step> steps = PlannerState.result() == null ? List.of() : PlannerState.result().steps();
		for (int i = 0; i < steps.size(); i++) {
			b.append(PlannerState.doneSteps.contains(i) ? "[x]" : "[ ]").append(steps.get(i).left().isTarget() ? "item" : "book")
					.append(java.util.Arrays.toString(steps.get(i).right().enchants())).append(' ');
		}
		return b.toString();
	}

	private static void expectHelmet(ClientGameTestContext ctx, String when, int typeCount) {
		String state = ctx.computeOnClient(mc -> PlannerState.target().id() + ", " + ItemType.all().size()
				+ " types, " + PlannerState.selectedCount() + " selected");
		if (!state.equals("helmet, " + typeCount + " types, 1 selected")) {
			throw new AssertionError(when + ": expected the Helmet type with its selection, got " + state);
		}
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try {
			run(ctx);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private void run(ClientGameTestContext ctx) throws IOException {
		try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			openAnvil(world);
			ctx.waitForScreen(AnvilScreen.class);
			ctx.waitTicks(5);
			if (ctx.computeOnClient(mc -> PlannerState.visible)) {
				throw new AssertionError("planner should start hidden");
			}

			List<String> failures = new ArrayList<>();
			for (int[] size : WINDOWS) {
				ctx.getInput().resizeWindow(size[0], size[1]);
				for (int scale : GUI_SCALES) {
					ctx.runOnClient(mc -> {
						mc.options.guiScale().set(scale);
						PlannerState.visible = false;
					});
					ctx.waitTicks(3);
					String name = size[0] + "x" + size[1] + "_gui" + scale;

					double[] cursor = ctx.computeOnClient(mc -> {
						// the book button: above the anvil's top-right corner, or inside it (see PlannerOverlay#layout)
						Window w = mc.getWindow();
						int gw = w.getGuiScaledWidth();
						int gh = w.getGuiScaledHeight();
						int ax = (gw - 176) / 2;
						int ay = (gh - 166) / 2;
						double gx = ay >= 22 ? ax + 176 - 10 : ax + 176 - 12;
						double gy = ay >= 22 ? ay - 11 : ay + 10;
						double f = (double) w.getScreenWidth() / gw;
						return new double[]{gx * f, gy * f, gw, gh, w.getGuiScale()};
					});
					ctx.getInput().setCursorPos(cursor[0], cursor[1]);
					ctx.waitTick();
					ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
					ctx.waitTicks(3);
					boolean open = ctx.computeOnClient(mc -> PlannerState.visible);
					ctx.takeScreenshot("planner_" + name);
					System.out.printf("[planner-test] %s gui=%dx%d scale=%d -> %s%n", name,
							(int) cursor[2], (int) cursor[3], (int) cursor[4], open ? "OPEN" : "STILL CLOSED");
					if (!open) {
						failures.add(name);
					}
				}
			}
			if (!failures.isEmpty()) {
				throw new AssertionError("clicking the book button did not open the planner at: " + failures);
			}

			// pick a sword with a few compatible enchantments and check an order gets computed
			ctx.getInput().resizeWindow(1920, 1080);
			ctx.runOnClient(mc -> {
				mc.options.guiScale().set(0);
				PlannerState.visible = true;
				PlannerState.setTarget(ItemType.of(new ItemStack(Items.NETHERITE_SWORD)));
				PlannerState.clear();
				List<EnchantCatalog.Entry> picked = new ArrayList<>();
				for (EnchantCatalog.Entry e : PlannerState.catalog().entries()) {
					if (!e.curse() && picked.stream().allMatch(e::compatibleWith)) {
						picked.add(e);
						PlannerState.setLevel(e, e.maxLevel());
					}
				}
			});
			ctx.waitFor(mc -> !PlannerState.computing(), 200);
			ctx.waitTicks(3);
			ctx.takeScreenshot("planner_sword_result");
			EnchantSolver.Result result = ctx.computeOnClient(mc -> PlannerState.result());
			if (result == null || result.steps().isEmpty()) {
				throw new AssertionError("no enchanting order was computed");
			}
			System.out.printf("[planner-test] sword: %d enchantments, %d steps, %d levels%n",
					PlannerState.selectedCount(), result.steps().size(), result.totalLevels());

			// item types: every helmet material selects the one "Helmet" entry, and taking the
			// helmet out of the anvil neither adds nor removes entries
			int typeCount = ctx.computeOnClient(mc -> ItemType.all().size());
			putInAnvil(world, Items.IRON_HELMET);
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> PlannerState.setLevel(PlannerState.catalog().entries().getFirst(), 1));
			ctx.waitTicks(3);
			ctx.takeScreenshot("planner_iron_helmet");
			expectHelmet(ctx, "iron helmet in the anvil", typeCount);
			putInAnvil(world, Items.AIR);
			ctx.waitTicks(5);
			expectHelmet(ctx, "helmet taken out", typeCount);
			putInAnvil(world, Items.DIAMOND_HELMET);
			ctx.waitTicks(5);
			expectHelmet(ctx, "diamond helmet in the anvil", typeCount);
			putInAnvil(world, Items.AIR);
			System.out.println("[planner-test] helmets: one Helmet entry, selection kept across materials");

			// starting item: a used iron helmet with Protection IV (repair cost 3); adding Unbreaking III
			// costs the book (3) plus the repair cost (3)
			ctx.runOnClient(mc -> PlannerState.clear());
			putInAnvil(world, Items.IRON_HELMET, 3, Map.of(Enchantments.PROTECTION, 4));
			ctx.waitTicks(5);
			String captured = ctx.computeOnClient(mc -> {
				PlannerState.StartItem start = PlannerState.startItem();
				return start == null ? "none" : start.repairCost() + " " + start.enchants();
			});
			if (!captured.startsWith("3 ") || !captured.contains("protection")) {
				throw new AssertionError("used helmet not taken as the starting item: " + captured);
			}
			ctx.runOnClient(mc -> {
				for (EnchantCatalog.Entry e : PlannerState.catalog().entries()) {
					if (e.key().equals(Enchantments.UNBREAKING)) {
						PlannerState.setLevel(e, 3);
					}
				}
			});
			ctx.waitFor(mc -> !PlannerState.computing(), 200);
			ctx.waitTicks(3);
			ctx.takeScreenshot("planner_start_item");
			int levels = ctx.computeOnClient(mc -> PlannerState.result() == null ? -1 : PlannerState.result().totalLevels());
			if (levels != 6) {
				throw new AssertionError("expected Unbreaking III on the used helmet to cost 6 levels, got " + levels);
			}
			// the finished helmet (Protection IV + Unbreaking III, repair cost 7) is part of the plan: no restart
			putInAnvil(world, Items.IRON_HELMET, 7, Map.of(Enchantments.PROTECTION, 4, Enchantments.UNBREAKING, 3));
			ctx.waitTicks(5);
			int stillFrom = ctx.computeOnClient(mc -> PlannerState.startItem() == null ? -1 : PlannerState.startItem().repairCost());
			if (stillFrom != 3) {
				throw new AssertionError("putting back the finished helmet restarted the plan (repair cost now " + stillFrom + ")");
			}
			// the x button: back to a fresh item, Protection no longer ticked
			ctx.runOnClient(mc -> PlannerState.clearStartItem());
			ctx.waitFor(mc -> !PlannerState.computing(), 200);
			String fresh = ctx.computeOnClient(mc -> PlannerState.selectedCount() + " selected, "
					+ (PlannerState.result() == null ? -1 : PlannerState.result().totalLevels()) + " levels");
			if (!fresh.equals("1 selected, 3 levels")) {
				throw new AssertionError("clearing the starting item: expected 1 selected, 3 levels, got " + fresh);
			}
			putInAnvil(world, Items.AIR);
			System.out.println("[planner-test] starting item: used helmet costs 6, finished helmet keeps the plan, x resets");

			// taking the item out by hand: Protection stays ticked but is an ordinary choice again,
			// so it can be unticked; Unbreaking is untouched
			putInAnvil(world, Items.IRON_HELMET, 3, Map.of(Enchantments.PROTECTION, 4));
			ctx.waitTicks(5);
			putInAnvil(world, Items.AIR);
			ctx.waitTicks(5);
			String removed = ctx.computeOnClient(mc -> {
				String state = (PlannerState.startItem() == null ? "released" : "still from item")
						+ ", " + PlannerState.selectedCount() + " selected";
				for (EnchantCatalog.Entry e : PlannerState.catalog().entries()) {
					if (e.key().equals(Enchantments.PROTECTION)) {
						PlannerState.setLevel(e, 0);
					}
				}
				return state + ", after unticking Protection " + PlannerState.selectedCount();
			});
			if (!removed.equals("released, 2 selected, after unticking Protection 1")) {
				throw new AssertionError("taking the helmet out: expected 'released, 2 selected, after unticking Protection 1', got " + removed);
			}

			// taking a finished result (item + book used up together) keeps the plan going
			putInAnvil(world, Items.IRON_HELMET, 3, Map.of(Enchantments.PROTECTION, 4));
			ctx.waitTicks(5);
			world.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.giveExperienceLevels(30);
				var unbreaking = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.UNBREAKING);
				player.containerMenu.getSlot(1).set(EnchantmentHelper.createBook(new EnchantmentInstance(unbreaking, 3)));
				player.containerMenu.broadcastChanges();
			});
			ctx.waitTicks(5);
			double[] resultSlot = ctx.computeOnClient(mc -> {
				Window w = mc.getWindow();
				double f = (double) w.getScreenWidth() / w.getGuiScaledWidth();
				int left = (w.getGuiScaledWidth() - 176) / 2;
				int top = (w.getGuiScaledHeight() - 166) / 2;
				return new double[]{(left + 134 + 8) * f, (top + 47 + 8) * f};
			});
			ctx.getInput().setCursorPos(resultSlot[0], resultSlot[1]);
			ctx.waitTick();
			ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			ctx.waitTicks(5);
			String taken = ctx.computeOnClient(mc -> mc.player.containerMenu.getCarried().getItem() + ", "
					+ (PlannerState.startItem() == null ? "fresh" : "from item, repair " + PlannerState.startItem().repairCost()));
			if (!taken.endsWith("from item, repair 3") || !taken.contains("iron_helmet")) {
				throw new AssertionError("taking the anvil result: expected the plan to go on, got " + taken);
			}
			// put the result away so the next checks start clean
			world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().containerMenu.setCarried(ItemStack.EMPTY));
			ctx.runOnClient(mc -> PlannerState.clearStartItem());
			ctx.waitTicks(3);
			System.out.println("[planner-test] removal: by hand releases the item (selection kept, deselectable), taking a result keeps the plan");

			// follow a whole plan in the anvil: every taken result ticks its step, book + book steps
			// don't switch the item type away from Helmet
			ctx.runOnClient(mc -> {
				PlannerState.clear();
				for (EnchantCatalog.Entry e : PlannerState.catalog().entries()) {
					if (e.key().equals(Enchantments.PROTECTION) || e.key().equals(Enchantments.UNBREAKING)
							|| e.key().equals(Enchantments.MENDING) || e.key().equals(Enchantments.RESPIRATION)) {
						PlannerState.setLevel(e, e.maxLevel());
					}
				}
			});
			ctx.waitFor(mc -> !PlannerState.computing(), 200);
			int stepCount = ctx.computeOnClient(mc -> PlannerState.result().steps().size());
			world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().giveExperienceLevels(200));
			for (int i = 0; i < stepCount; i++) {
				int step = i;
				List<Object[]> pieces = ctx.computeOnClient(mc -> {
					EnchantSolver.Step st = PlannerState.result().steps().get(step);
					return List.of(pieceSpec(st.left()), pieceSpec(st.right()));
				});
				world.getServer().runOnServer(server -> {
					ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
					var lookup = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
					for (int slot = 0; slot < 2; slot++) {
						Object[] spec = pieces.get(slot);
						ItemStack stack = new ItemStack((Boolean) spec[0] ? Items.IRON_HELMET : Items.ENCHANTED_BOOK);
						@SuppressWarnings("unchecked")
						Map<ResourceKey<Enchantment>, Integer> pieceLevels = (Map<ResourceKey<Enchantment>, Integer>) spec[1];
						pieceLevels.forEach((key, level) -> stack.enchant(lookup.getOrThrow(key), level));
						if ((Integer) spec[2] > 0) {
							stack.set(DataComponents.REPAIR_COST, (Integer) spec[2]);
						}
						player.containerMenu.getSlot(slot).set(stack);
					}
					player.containerMenu.broadcastChanges();
				});
				ctx.waitTicks(5);
				if (step == 0) {
					ctx.takeScreenshot("planner_follow_step1");
				}
				ctx.getInput().setCursorPos(resultSlot[0], resultSlot[1]);
				ctx.waitTick();
				ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
				ctx.waitTicks(5);
				world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().containerMenu.setCarried(ItemStack.EMPTY));
				ctx.waitTicks(2);
				String state = ctx.computeOnClient(mc -> PlannerState.target().id() + ", done " + new java.util.TreeSet<>(PlannerState.doneSteps));
				if (!state.startsWith("helmet, ") || !ctx.computeOnClient(mc -> PlannerState.doneSteps.contains(step))) {
					throw new AssertionError("step " + (step + 1) + " of " + stepCount + " was not ticked after taking its result: " + state);
				}
			}
			ctx.takeScreenshot("planner_follow_done");

			// swapping items: the checklist follows the item in the anvil
			putInAnvil(world, Items.IRON_HELMET);
			ctx.waitTicks(5);
			expectChecklist(ctx, "fresh helmet", Set.of());
			int lastItemStep = ctx.computeOnClient(mc -> {
				List<EnchantSolver.Step> steps = PlannerState.result().steps();
				for (int i = steps.size() - 1; i >= 0; i--) {
					if (steps.get(i).left().isTarget()) {
						return i;
					}
				}
				return -1;
			});
			Object[] halfway = ctx.computeOnClient(mc -> pieceSpec(PlannerState.result().steps().get(lastItemStep).left()));
			@SuppressWarnings("unchecked")
			Map<ResourceKey<Enchantment>, Integer> halfwayLevels = (Map<ResourceKey<Enchantment>, Integer>) halfway[1];
			putInAnvil(world, Items.IRON_HELMET, (Integer) halfway[2], halfwayLevels);
			ctx.waitTicks(5);
			Set<Integer> halfwayIds = ctx.computeOnClient(mc -> {
				Set<Integer> ids = new java.util.HashSet<>();
				for (int id : PlannerState.result().steps().get(lastItemStep).left().enchants()) {
					ids.add(id);
				}
				return ids;
			});
			expectChecklist(ctx, "helmet before the last step", halfwayIds);
			ctx.takeScreenshot("planner_follow_halfway");

			// unticking on the left re-checks the item against the new plan: Protection (not on the
			// helmet yet) leaves nothing to do; Respiration (on the helmet) just stops counting
			setLevelOnClient(ctx, Enchantments.PROTECTION, 0);
			expectAllDone(ctx, "unticked Protection");
			setLevelOnClient(ctx, Enchantments.RESPIRATION, 0);
			expectAllDone(ctx, "unticked Respiration, which the helmet has");
			if (ctx.computeOnClient(mc -> PlannerState.selection().containsKey(Enchantments.RESPIRATION))) {
				throw new AssertionError("unticked Respiration was ticked again");
			}
			ctx.takeScreenshot("planner_untick_on_item");
			setLevelOnClient(ctx, Enchantments.PROTECTION, 4);
			setLevelOnClient(ctx, Enchantments.RESPIRATION, 3);
			String reticked = ctx.computeOnClient(mc -> describePlan());
			if (!ctx.computeOnClient(mc -> PlannerState.selection().containsKey(Enchantments.RESPIRATION)
					&& PlannerState.selection().containsKey(Enchantments.PROTECTION))) {
				throw new AssertionError("ticking Protection and Respiration again didn't stick: " + reticked);
			}

			// a helmet that matches no stage of the plan (it has Thorns): planned from, only missing enchantments
			putInAnvil(world, Items.IRON_HELMET, 1, Map.of(Enchantments.THORNS, 1, Enchantments.MENDING, 1));
			ctx.waitTicks(5);
			ctx.waitFor(mc -> !PlannerState.computing(), 200);
			ctx.waitTicks(3);
			String other = ctx.computeOnClient(mc -> (PlannerState.startItem() == null ? "no start item" : "from item")
					+ ", " + PlannerState.solvedEntries().size() + " books planned");
			ctx.takeScreenshot("planner_follow_other_item");
			if (!other.equals("from item, 3 books planned")) {
				throw new AssertionError("helmet with Thorns + Mending: expected 'from item, 3 books planned', got " + other + " / " + ctx.computeOnClient(mc -> describePlan()));
			}
			putInAnvil(world, Items.AIR);
			ctx.waitTicks(5); // let the client see the empty anvil before switching to another type
			ctx.runOnClient(mc -> PlannerState.clearStartItem());
			System.out.println("[planner-test] swapping items: checklist follows fresh and halfway helmets, other helmet plans only what's missing");
			System.out.println("[planner-test] following a " + stepCount + "-step plan: every step ticked itself, type stayed Helmet");

			// pickaxe: plan Mending + Unbreaking, put in the finished pickaxe (all done), tick Efficiency
			// (doesn't fit the new plan: planned from the item) and untick it again (back to all done)
			ctx.runOnClient(mc -> {
				PlannerState.setTarget(ItemType.of(new ItemStack(Items.IRON_PICKAXE)));
				PlannerState.clear();
			});
			setLevelOnClient(ctx, Enchantments.MENDING, 1);
			setLevelOnClient(ctx, Enchantments.UNBREAKING, 3);
			int finishedCost = ctx.computeOnClient(mc -> PlannerState.result().finalPriorWork());
			putInAnvil(world, Items.IRON_PICKAXE, finishedCost, Map.of(Enchantments.MENDING, 1, Enchantments.UNBREAKING, 3));
			ctx.waitTicks(5);
			expectAllDone(ctx, "finished pickaxe in the anvil");
			setLevelOnClient(ctx, Enchantments.EFFICIENCY, 5);
			ctx.waitFor(mc -> !PlannerState.computing(), 200);
			ctx.waitTicks(5);
			String withEfficiency = ctx.computeOnClient(mc -> describePlan());
			if (!ctx.computeOnClient(mc -> PlannerState.startItem() != null && PlannerState.solvedEntries().size() == 1)) {
				throw new AssertionError("ticked Efficiency: expected planning from the pickaxe with 1 book, got " + withEfficiency);
			}
			ctx.takeScreenshot("planner_pickaxe_efficiency");
			setLevelOnClient(ctx, Enchantments.EFFICIENCY, 0);
			ctx.waitFor(mc -> !PlannerState.computing(), 200);
			ctx.waitTicks(5);
			expectAllDone(ctx, "unticked Efficiency again");
			ctx.takeScreenshot("planner_pickaxe_back");
			putInAnvil(world, Items.AIR);
			ctx.waitTicks(3);
			System.out.println("[planner-test] pickaxe: ticking Efficiency plans from the item, unticking it returns to the checked plan");

			// settings: Mod Menu offers our screen; clicking the option once switches Closed -> Open
			boolean hasModMenuEntry = FabricLoader.getInstance().getEntrypointContainers("modmenu", ModMenuApi.class)
					.stream().anyMatch(c -> c.getProvider().getMetadata().getId().equals("enchant_planner"));
			if (!hasModMenuEntry) {
				throw new AssertionError("no Mod Menu entrypoint registered");
			}
			ctx.setScreen(() -> new PlannerConfigScreen(null));
			ctx.waitTicks(3);
			ctx.takeScreenshot("planner_settings");
			double[] option = ctx.computeOnClient(mc -> {
				Window w = mc.getWindow();
				double f = (double) w.getScreenWidth() / w.getGuiScaledWidth();
				return new double[]{(w.getGuiScaledWidth() / 2.0) * f, (w.getGuiScaledHeight() / 2 - 24 + 10) * f};
			});
			ctx.getInput().setCursorPos(option[0], option[1]);
			ctx.waitTick();
			ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			ctx.waitTicks(2);
			PlannerConfig.StartState state = ctx.computeOnClient(mc -> PlannerConfig.get().startState);
			String saved = Files.readString(FabricLoader.getInstance().getConfigDir().resolve("enchant_planner.json"));
			System.out.printf("[planner-test] settings: start state %s, saved %s%n", state, saved.replaceAll("\\s+", " "));
			if (state != PlannerConfig.StartState.OPEN || !saved.contains("OPEN")) {
				throw new AssertionError("clicking the setting did not switch it to Open");
			}

			// a freshly opened anvil now starts with the planner shown
			ctx.runOnClient(mc -> PlannerState.visible = false);
			ctx.setScreen(() -> null);
			openAnvil(world);
			ctx.waitForScreen(AnvilScreen.class);
			ctx.waitTicks(3);
			ctx.takeScreenshot("planner_start_open");
			if (!ctx.computeOnClient(mc -> PlannerState.visible)) {
				throw new AssertionError("start state Open did not show the planner on a new anvil");
			}
			System.out.println("[planner-test] start state Open: planner shown on a new anvil");
		}
	}
}
