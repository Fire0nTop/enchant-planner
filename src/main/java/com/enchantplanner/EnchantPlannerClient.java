package com.enchantplanner;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;

public final class EnchantPlannerClient implements ClientModInitializer {
	public static final String MOD_ID = "enchant_planner";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** The anvil screen the planner was last attached to, to tell a new anvil from a resize. */
	private static WeakReference<Screen> lastAnvil = new WeakReference<>(null);

	@Override
	public void onInitializeClient() {
		PlannerConfig.load();
		// Screen-specific events are recreated on every init, so a resize simply builds a new overlay.
		ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
			if (!(screen instanceof AnvilScreen anvil)) {
				return;
			}
			boolean newAnvil = lastAnvil.get() != screen;
			if (newAnvil) {
				lastAnvil = new WeakReference<>(screen);
				PlannerState.visible = PlannerConfig.get().visibleOnOpen();
			}
			PlannerOverlay overlay = new PlannerOverlay(anvil, newAnvil);
			ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> overlay.extract(g, mouseX, mouseY));
			ScreenEvents.afterTick(screen).register(s -> overlay.tick());
			ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !overlay.mouseClicked(event.x(), event.y(), event.button()));
			ScreenMouseEvents.allowMouseScroll(screen).register((s, mx, my, h, v) -> !overlay.mouseScrolled(mx, my, v));
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> PlannerState.invalidateCatalog());
	}
}
