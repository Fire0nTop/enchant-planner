package com.enchantplanner;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * What the planner plans for: a kind of item such as "Helmet" that covers every material through
 * its item tag, so an iron and a netherite helmet share one entry and one selection. Items that fit
 * no built-in type (e.g. from other mods) get a type of their own.
 */
public final class ItemType {
	private final String id;
	private final Component name;
	private final Item icon;
	private final Predicate<ItemStack> contains;

	private ItemType(String id, Component name, Item icon, Predicate<ItemStack> contains) {
		this.id = id;
		this.name = name;
		this.icon = icon;
		this.contains = contains;
	}

	private static List<ItemType> builtIn;
	/** Types made for items from the anvil that fit no built-in type; kept for the session. */
	private static final List<ItemType> extra = new ArrayList<>();

	private static ItemType tag(String id, TagKey<Item> tag, Item icon) {
		return new ItemType(id, Component.translatable("enchant_planner.item_type." + id), icon, s -> s.is(tag));
	}

	private static ItemType item(String id, Item item) {
		return new ItemType(id, item.getName(new ItemStack(item)), item, s -> s.is(item));
	}

	/** Built on first use, not in a static initializer, so registries and tags are bound. */
	public static List<ItemType> builtIn() {
		if (builtIn == null) {
			builtIn = List.of(
					tag("helmet", ItemTags.HEAD_ARMOR, Items.NETHERITE_HELMET),
					tag("chestplate", ItemTags.CHEST_ARMOR, Items.NETHERITE_CHESTPLATE),
					tag("leggings", ItemTags.LEG_ARMOR, Items.NETHERITE_LEGGINGS),
					tag("boots", ItemTags.FOOT_ARMOR, Items.NETHERITE_BOOTS),
					item("elytra", Items.ELYTRA),
					tag("sword", ItemTags.SWORDS, Items.NETHERITE_SWORD),
					tag("axe", ItemTags.AXES, Items.NETHERITE_AXE),
					item("mace", Items.MACE),
					tag("spear", ItemTags.SPEARS, Items.NETHERITE_SPEAR),
					item("trident", Items.TRIDENT),
					item("bow", Items.BOW),
					item("crossbow", Items.CROSSBOW),
					tag("pickaxe", ItemTags.PICKAXES, Items.NETHERITE_PICKAXE),
					tag("shovel", ItemTags.SHOVELS, Items.NETHERITE_SHOVEL),
					tag("hoe", ItemTags.HOES, Items.NETHERITE_HOE),
					item("shield", Items.SHIELD),
					item("brush", Items.BRUSH),
					item("fishing_rod", Items.FISHING_ROD),
					item("shears", Items.SHEARS),
					item("flint_and_steel", Items.FLINT_AND_STEEL),
					item("carrot_on_a_stick", Items.CARROT_ON_A_STICK),
					item("warped_fungus_on_a_stick", Items.WARPED_FUNGUS_ON_A_STICK),
					item("carved_pumpkin", Items.CARVED_PUMPKIN),
					new ItemType("book", Items.BOOK.getName(new ItemStack(Items.BOOK)), Items.BOOK,
							s -> s.is(Items.BOOK) || s.is(Items.ENCHANTED_BOOK)));
		}
		return builtIn;
	}

	/** Built-in types plus any extra ones made this session, in display order. */
	public static List<ItemType> all() {
		List<ItemType> list = new ArrayList<>(builtIn());
		list.addAll(extra);
		return list;
	}

	public static ItemType defaultType() {
		return builtIn().get(5); // sword
	}

	/** The type an item belongs to, creating an extra type for items no built-in type covers. */
	public static ItemType of(ItemStack stack) {
		for (ItemType t : all()) {
			if (t.contains(stack)) {
				return t;
			}
		}
		Item item = stack.getItem();
		ItemType t = new ItemType("item:" + BuiltInRegistries.ITEM.getKey(item), item.getName(new ItemStack(item)), item,
				s -> s.is(item));
		extra.add(t);
		return t;
	}

	public String id() {
		return id;
	}

	public Component name() {
		return name;
	}

	public ItemStack icon() {
		return new ItemStack(icon);
	}

	/** A representative item, used to ask which enchantments fit this type. */
	public Item probe() {
		return icon;
	}

	public boolean isBook() {
		return icon == Items.BOOK;
	}

	public boolean contains(ItemStack stack) {
		return !stack.isEmpty() && contains.test(stack);
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof ItemType t && t.id.equals(id);
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}

	@Override
	public String toString() {
		return id;
	}
}
