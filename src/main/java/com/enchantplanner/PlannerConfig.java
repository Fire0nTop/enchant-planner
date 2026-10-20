package com.enchantplanner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Settings stored in config/enchant_planner.json. */
public final class PlannerConfig {
	/** Whether the planner panels show when an anvil is opened. */
	public enum StartState {
		CLOSED, OPEN, REMEMBER;

		public Component label() {
			return Component.translatable("enchant_planner.config.start_state." + name().toLowerCase(Locale.ROOT));
		}

		public Component tooltip() {
			return Component.translatable("enchant_planner.config.start_state." + name().toLowerCase(Locale.ROOT) + ".tip");
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("enchant_planner.json");

	private static PlannerConfig instance = new PlannerConfig();

	public StartState startState = StartState.CLOSED;
	/** Last toggle state, used by {@link StartState#REMEMBER}. */
	public boolean lastVisible = false;

	public static PlannerConfig get() {
		return instance;
	}

	public static void load() {
		if (!Files.exists(FILE)) {
			return;
		}
		try (Reader r = Files.newBufferedReader(FILE)) {
			PlannerConfig loaded = GSON.fromJson(r, PlannerConfig.class);
			if (loaded != null) {
				if (loaded.startState == null) {
					loaded.startState = StartState.CLOSED;
				}
				instance = loaded;
			}
		} catch (IOException | JsonParseException e) {
			EnchantPlannerClient.LOGGER.warn("Could not read {}, using defaults", FILE, e);
		}
	}

	public static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer w = Files.newBufferedWriter(FILE)) {
				GSON.toJson(instance, w);
			}
		} catch (IOException e) {
			EnchantPlannerClient.LOGGER.warn("Could not write {}", FILE, e);
		}
	}

	/** Visibility for a freshly opened anvil. */
	public boolean visibleOnOpen() {
		return switch (startState) {
			case CLOSED -> false;
			case OPEN -> true;
			case REMEMBER -> lastVisible;
		};
	}
}
