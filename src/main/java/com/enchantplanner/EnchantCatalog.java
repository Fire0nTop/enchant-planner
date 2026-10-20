package com.enchantplanner;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The enchantments that can go on one target item, read from the client's synced registry so
 * datapack and modded enchantments are included with their real anvil costs.
 */
public final class EnchantCatalog {
	/** List sections, in display order. */
	public enum Type {
		ARMOR, MELEE, RANGED, TOOL, FISHING, OTHER, GENERAL, CURSE;

		public Component title() {
			return Component.translatable("enchant_planner.type." + name().toLowerCase(java.util.Locale.ROOT));
		}
	}

	/**
	 * @param type    list section
	 * @param cluster entries sharing a cluster are mutually exclusive (e.g. the protection family)
	 */
	public record Entry(Holder<Enchantment> holder, ResourceKey<Enchantment> key, String name, int maxLevel,
						int bookMultiplier, boolean curse, Type type, int cluster) {
		public boolean compatibleWith(Entry other) {
			return Enchantment.areCompatible(holder, other.holder);
		}

		public Component displayName(int level) {
			Component n = Component.literal(name);
			if (maxLevel > 1 || level > 1) {
				n = Component.literal(name + " ").append(Component.translatable("enchantment.level." + level));
			}
			return n;
		}
	}

	private final Item target;
	private final List<Entry> entries;

	private EnchantCatalog(Item target, List<Entry> entries) {
		this.target = target;
		this.entries = entries;
	}

	public static EnchantCatalog forItem(Item target) {
		List<Entry> list = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			boolean book = isBook(target);
			ItemStack stack = new ItemStack(target);
			Map<Type, List<ItemStack>> probes = probes();
			mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements().forEach(ref -> {
				Enchantment e = ref.value();
				if (book || e.canEnchant(stack)) {
					boolean curse = ref.is(EnchantmentTags.CURSE);
					list.add(new Entry(ref, ref.key(), e.description().getString(), e.getMaxLevel(),
							Math.max(1, e.getAnvilCost() / 2), curse, curse ? Type.CURSE : typeOf(e, probes), 0));
				}
			});
		}
		return new EnchantCatalog(target, sortByType(list));
	}

	/** Representative items per section; an enchantment goes to the first section it fits. */
	private static Map<Type, List<ItemStack>> probes() {
		Map<Type, List<ItemStack>> m = new EnumMap<>(Type.class);
		m.put(Type.ARMOR, stacks(Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS,
				Items.NETHERITE_BOOTS, Items.TURTLE_HELMET));
		m.put(Type.MELEE, stacks(Items.NETHERITE_SWORD, Items.MACE, Items.NETHERITE_SPEAR));
		m.put(Type.RANGED, stacks(Items.BOW, Items.CROSSBOW, Items.TRIDENT));
		m.put(Type.TOOL, stacks(Items.NETHERITE_PICKAXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE, Items.SHEARS));
		m.put(Type.FISHING, stacks(Items.FISHING_ROD));
		return m;
	}

	private static List<ItemStack> stacks(Item... items) {
		List<ItemStack> out = new ArrayList<>();
		for (Item i : items) {
			out.add(new ItemStack(i));
		}
		return out;
	}

	private static Type typeOf(Enchantment e, Map<Type, List<ItemStack>> probes) {
		Type first = null;
		int sections = 0;
		for (Map.Entry<Type, List<ItemStack>> p : probes.entrySet()) {
			if (p.getValue().stream().anyMatch(e::canEnchant)) {
				sections++;
				if (first == null) {
					first = p.getKey();
				}
			}
		}
		// Unbreaking, Mending and the like fit almost everything
		if (sections >= 3) {
			return Type.GENERAL;
		}
		return first == null ? Type.OTHER : first;
	}

	/**
	 * Orders by section, keeps mutually exclusive enchantments next to each other (their cluster
	 * sorted by its alphabetically first member), and sorts alphabetically inside a cluster.
	 */
	private static List<Entry> sortByType(List<Entry> list) {
		Comparator<Entry> byName = Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER);
		list.sort(byName);
		int[] parent = new int[list.size()];
		for (int i = 0; i < parent.length; i++) {
			parent[i] = i;
		}
		for (int i = 0; i < list.size(); i++) {
			for (int j = i + 1; j < list.size(); j++) {
				Entry a = list.get(i);
				Entry b = list.get(j);
				if (a.type() == b.type() && !a.compatibleWith(b)) {
					int ri = find(parent, i);
					int rj = find(parent, j);
					parent[Math.max(ri, rj)] = Math.min(ri, rj);
				}
			}
		}
		// roots are the lowest index, i.e. the alphabetically first member
		List<Entry> out = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			Entry e = list.get(i);
			out.add(new Entry(e.holder(), e.key(), e.name(), e.maxLevel(), e.bookMultiplier(), e.curse(), e.type(),
					find(parent, i)));
		}
		out.sort(Comparator.comparing(Entry::type).thenComparingInt(Entry::cluster).thenComparing(byName));
		return List.copyOf(out);
	}

	private static int find(int[] parent, int i) {
		while (parent[i] != i) {
			i = parent[i] = parent[parent[i]];
		}
		return i;
	}

	/** Can anything from the planner go on this item (used to auto-pick the item placed in the anvil)? */
	public static boolean isEnchantTarget(Item item) {
		if (isBook(item)) {
			return true;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return false;
		}
		ItemStack stack = new ItemStack(item);
		return mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements()
				.anyMatch(ref -> ref.value().canEnchant(stack));
	}

	public static boolean isBook(Item item) {
		return item == Items.BOOK || item == Items.ENCHANTED_BOOK;
	}

	public Item target() {
		return target;
	}

	public List<Entry> entries() {
		return entries;
	}

	public Entry find(ResourceKey<Enchantment> key) {
		for (Entry e : entries) {
			if (e.key().equals(key)) {
				return e;
			}
		}
		return null;
	}
}
