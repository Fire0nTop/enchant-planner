package com.enchantplanner.test;

import com.enchantplanner.EnchantCatalog;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

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
				PlannerState.setTarget(net.minecraft.world.item.Items.NETHERITE_SWORD);
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
