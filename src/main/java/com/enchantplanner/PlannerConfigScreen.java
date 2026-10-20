package com.enchantplanner;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Settings screen, opened from Mod Menu. */
public final class PlannerConfigScreen extends Screen {
	private final Screen parent;

	public PlannerConfigScreen(Screen parent) {
		super(Component.translatable("enchant_planner.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		PlannerConfig config = PlannerConfig.get();
		int x = width / 2 - 100;
		int y = height / 2 - 24;

		addRenderableWidget(CycleButton.builder(PlannerConfig.StartState::label, config.startState)
				.withValues(PlannerConfig.StartState.values())
				.withTooltip(state -> Tooltip.create(state.tooltip()))
				.create(x, y, 200, 20, Component.translatable("enchant_planner.config.start_state"), (button, value) -> {
					config.startState = value;
					PlannerConfig.save();
				}));

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
				.bounds(x, y + 32, 200, 20)
				.build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.centeredText(font, title, width / 2, height / 2 - 52, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
