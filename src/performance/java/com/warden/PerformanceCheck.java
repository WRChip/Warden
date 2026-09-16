package com.warden;

import com.google.gson.JsonParser;
import com.warden.config.WardenConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.component.ComponentChanges;
import net.minecraft.item.Items;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public final class PerformanceCheck {
    private static Method weapons;
    private static Method enchantments;

    public static void main(String[] args) throws Exception {
        var loader = FabricLoader.getInstance();
        var configDir = loader.getClass().getDeclaredField("configDir");
        configDir.setAccessible(true);
        configDir.set(loader, Files.createTempDirectory(Path.of("build"), "warden-check-"));
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        weapons = WardenMod.class.getDeclaredMethod("enforceWeaponComponentsRecursive", ItemStack.class);
        enchantments = WardenMod.class.getDeclaredMethod("enforceEnchantmentLimitsRecursive", ItemStack.class);
        weapons.setAccessible(true);
        enchantments.setAccessible(true);
        WardenMod.CONFIG = new WardenConfig();
        if (args.length == 0) {
            checkWeapons();
            checkNestedContents();
            WardenConfig cfg = WardenConfig.fromJson(JsonParser.parseString(
                    "{\"item_limits\":{\"check_interval_ticks\":0}}").getAsJsonObject());
            require(cfg.checkIntervalTicks == 1, "zero check interval");
            System.out.println("Warden performance checks passed");
        }
        benchmark();
    }

    private static void checkWeapons() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.setDamage(10);
        require(!WardenMod.enforceWeaponComponents(sword), "ordinary damage must not require normalization");
        WardenConfig.WeaponLimitConfig limit = new WardenConfig.WeaponLimitConfig();
        limit.attackDamage = 3.0;
        limit.attackSpeed = 2.0;
        limit.reach = 2.5f;
        WardenMod.CONFIG.weaponLimits.put("minecraft:diamond_sword", limit);
        var entry = Items.DIAMOND_SWORD.getRegistryEntry();
        for (ItemStack created : List.of(new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.DIAMOND_SWORD, 1),
                new ItemStack(entry), new ItemStack(entry, 1), new ItemStack(entry, 1, ComponentChanges.EMPTY))) {
            require(damage(created) == 3.0, "all constructor routes enforce weapon stats");
        }
        require(WardenMod.enforceWeaponComponents(sword), "configured stats must apply");
        require(damage(sword) == 3.0, "attack damage includes player base");
        require(sword.get(DataComponentTypes.ATTACK_RANGE).maxRange() == 2.5f, "reach cap");
        require(!WardenMod.enforceWeaponComponents(sword), "configured weapon must be stable");
        require(WardenMod.getVanillaAttackDamage(Items.DIAMOND_SWORD) == 7.0, "vanilla baseline");
        WardenMod.CONFIG.weaponLimits.clear();
        require(WardenMod.enforceWeaponComponents(sword), "removing limit must restore defaults");
        require(damage(sword) == 7.0 && sword.getDamage() == 10, "restore damage, preserve durability");
    }

    private static void checkNestedContents() throws Exception {
        var sharpness = BuiltinRegistries.createWrapperLookup().getOrThrow(RegistryKeys.ENCHANTMENT)
                .getOrThrow(Enchantments.SHARPNESS);
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        ItemEnchantmentsComponent.Builder enchants = new ItemEnchantmentsComponent.Builder(ItemEnchantmentsComponent.DEFAULT);
        enchants.set(sharpness, 5);
        sword.set(DataComponentTypes.ENCHANTMENTS, enchants.build());
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < 54; i++) slots.add(ItemStack.EMPTY);
        slots.set(40, sword);
        slots.set(53, new ItemStack(Items.DIAMOND, 8));
        ContainerComponent original = ContainerComponent.fromStacks(slots);
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponentTypes.CONTAINER, original);
        WardenMod.CONFIG.weaponLimits.put("minecraft:diamond_sword",
                new WardenConfig.WeaponLimitConfig(3.0, null, null, null, null, null));
        require((boolean) weapons.invoke(null, box), "nested weapon must be capped");
        ContainerComponent capped = box.get(DataComponentTypes.CONTAINER);
        require(capped.stream().toList().size() == 54, "preserve container slots beyond 27");
        require(damage(capped.stream().toList().get(40)) == 3.0, "cap slot 40");
        require(damage(original.stream().toList().get(40)) == 7.0, "do not mutate shared container");
        require(!(boolean) weapons.invoke(null, box), "clean nested scan");
        require(box.get(DataComponentTypes.CONTAINER) == capped, "reuse clean container component");

        WardenMod.CONFIG.enchantmentLimits.put("minecraft:sharpness", 2);
        require((boolean) enchantments.invoke(null, box), "nested enchantment must be capped");
        require(box.get(DataComponentTypes.CONTAINER).stream().toList().get(40)
                .get(DataComponentTypes.ENCHANTMENTS).getLevel(sharpness) == 2, "enchantment cap");
        require(original.stream().toList().get(40).get(DataComponentTypes.ENCHANTMENTS).getLevel(sharpness) == 5,
                "preserve shared enchantments");

        BundleContentsComponent bundle = new BundleContentsComponent(List.of(sword));
        ItemStack bag = new ItemStack(Items.BUNDLE);
        bag.set(DataComponentTypes.BUNDLE_CONTENTS, bundle);
        require((boolean) weapons.invoke(null, bag), "bundle weapon cap");
        require(damage(bundle.iterate().iterator().next()) == 7.0, "do not mutate shared bundle");
        require((boolean) enchantments.invoke(null, bag), "bundle enchantment cap");
        require(bundle.iterate().iterator().next().get(DataComponentTypes.ENCHANTMENTS).getLevel(sharpness) == 5,
                "preserve shared bundle enchantments");
        WardenMod.CONFIG.itemEnchantmentOverrides.put("minecraft:diamond_sword", java.util.Map.of("minecraft:sharpness", -1));
        require(WardenMod.capEnchantments(sword, sword.get(DataComponentTypes.ENCHANTMENTS)) == null,
                "item exemption overrides global enchantment cap");
        WardenMod.CONFIG.itemEnchantmentOverrides.clear();

        WardenMod.CONFIG.itemLimits.put("minecraft:diamond", 0);
        WardenMod.CONFIG.deleteOverflowItem = true;
        var remove = WardenMod.class.getDeclaredMethod("removeItemsRecursive", ServerPlayerEntity.class,
                ItemStack.class, String.class, int.class, Runnable.class);
        remove.setAccessible(true);
        require((int) remove.invoke(null, null, box, "minecraft:diamond", 8, (Runnable) () -> {}) == 0,
                "remove banned items beyond slot 27");
        require(original.stream().toList().get(53).getCount() == 8, "preserve source container on removal");
        BundleContentsComponent diamonds = new BundleContentsComponent(List.of(new ItemStack(Items.DIAMOND, 8)));
        bag.set(DataComponentTypes.BUNDLE_CONTENTS, diamonds);
        require((int) remove.invoke(null, null, bag, "minecraft:diamond", 8, (Runnable) () -> {}) == 0,
                "remove banned bundle contents");
        require(diamonds.iterate().iterator().next().getCount() == 8, "preserve source bundle on removal");
        HashMap<String, Integer> counts = new HashMap<>();
        WardenMod.countItemsRecursive(box, counts);
        require(!counts.containsKey("minecraft:diamond"), "no banned diamonds remain");
        Class.forName("net.minecraft.entity.ItemEntity");
    }

    private static void benchmark() throws Exception {
        WardenMod.CONFIG = new WardenConfig();
        List<ItemStack> contents = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
            stack.setDamage(10);
            contents.add(stack);
        }
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(contents));
        for (int i = 0; i < 2_000; i++) weapons.invoke(null, box);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();
        long allocated = bean.getThreadAllocatedBytes(id);
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) weapons.invoke(null, box);
        System.out.printf("10,000 clean shulker scans: %.3f ms, %.1f bytes/scan%n",
                (System.nanoTime() - start) / 1_000_000.0,
                (bean.getThreadAllocatedBytes(id) - allocated) / 10_000.0);
    }

    private static double damage(ItemStack stack) {
        AttributeModifiersComponent attrs = stack.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        for (var entry : attrs.modifiers()) {
            if (entry.attribute().equals(EntityAttributes.ATTACK_DAMAGE)
                    && entry.modifier().idMatches(Item.BASE_ATTACK_DAMAGE_MODIFIER_ID)) return entry.modifier().value() + 1;
        }
        return 1;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
