package audit;

import com.google.gson.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import net.minecraft.nbt.*;
import org.bukkit.*;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import su.nightexpress.nightcore.util.*;
import su.nightexpress.nightcore.util.nbt.*;
import su.nightexpress.combatpets.PetAPI;
import su.nightexpress.combatpets.data.DataHandler;
import su.nightexpress.combatpets.data.impl.PetData;

public final class BookVersionProbe extends JavaPlugin {
    private final JsonArray results = new JsonArray();
    private int failed;
    interface Check { void run() throws Exception; }
    void check(String name, Check check) {
        JsonObject row = new JsonObject(); row.addProperty("name", name);
        try { check.run(); row.addProperty("pass", true); getLogger().info("CHECK_PASS " + name); }
        catch (Throwable error) {
            failed++; row.addProperty("pass", false); row.addProperty("error", error.toString());
            getLogger().warning("CHECK_FAIL " + name + " " + error);
        }
        results.add(row);
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static CompoundTag tag(ItemStack item) { return (CompoundTag) NbtUtil.tagFromItemStack(item); }
    static CompoundTag unversioned(ItemStack item) {
        CompoundTag tag = tag(item); tag.remove("DataVersion"); return tag;
    }
    static String compressTag(CompoundTag tag) throws IOException {
        var bytes = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(bytes));
        return new BigInteger(1, bytes.toByteArray()).toString(32);
    }
    static void same(ItemStack expected, ItemStack actual) {
        require(actual != null, "decoded item is null");
        require(unversioned(expected).equals(unversioned(actual)), "full item NBT changed");
    }
    static ItemStack book() {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();
        meta.setTitle("往返测试"); meta.setAuthor("NightCore regression");
        meta.pages(List.of(Component.text("中文正文\n第二行，不是 JSON"),
            Component.text("123"), Component.text("null"), Component.text("{\"text\":\"literal JSON\"}"),
            Component.text("彩色样式").color(NamedTextColor.RED).decoration(TextDecoration.BOLD, true)));
        item.setItemMeta(meta); return item;
    }
    static ItemStack simpleBook(String page) {
        ItemStack item = book(); BookMeta meta = (BookMeta) item.getItemMeta();
        meta.pages(List.of(Component.text(page))); item.setItemMeta(meta); return item;
    }
    @Override public void onEnable() {
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> runChecks(), 2);
    }
    void runChecks() {
        check("actual_core_version", () -> require(Bukkit.getUnsafe().getDataVersion() == 4903, "wrong core"));
        check("public_version_constant", () -> require(Version.getCurrent().getDataVersion() == 4903, "predicted version"));
        check("new_data_has_runtime_version", () -> {
            JsonObject json = NbtUtil.tagToJson(tag(book())).getAsJsonObject();
            require(json.has("DataVersion") && json.get("DataVersion").getAsInt() == 4903, "missing actual DataVersion");
        });
        check("new_book_roundtrip_20_times", () -> {
            ItemStack expected = book(), actual = expected;
            for (int i = 0; i < 20; i++) { actual = ItemNbt.decompress(ItemNbt.compress(actual)); same(expected, actual); }
        });
        check("new_json_literal_pages_remain_literal", () -> {
            for (String page : List.of("123", "null", "true", "{}", "[]", "\"quoted\"", "{\"text\":\"literal\"}")) {
                ItemStack expected = simpleBook(page); same(expected, ItemNbt.decompress(ItemNbt.compress(expected)));
            }
        });
        check("embedded_version_overrides_incorrect_fallback", () -> {
            ItemStack expected = book(); same(expected, new ItemTag(tag(expected).toString(), 3955).getItemStack());
        });
        check("legacy_unversioned_current_book", () -> {
            ItemStack expected = book(); same(expected, ItemNbt.decompress(compressTag(unversioned(expected))));
        });
        check("legacy_unversioned_plain_book", () -> {
            ItemStack expected = simpleBook("正文\n又一行文字"); same(expected, ItemNbt.decompress(compressTag(unversioned(expected))));
        });
        check("tag_string_roundtrip", () -> {
            ItemStack expected = book(); same(expected, ItemNbt.fromTagString(ItemNbt.getTagString(expected)));
        });
        check("empty_tag_strings_remain_empty", () -> {
            require(ItemNbt.fromTagString("") == null && ItemNbt.fromTagString("   ") == null &&
                ItemNbt.fromTagString("{}") == null, "empty SNBT behavior changed");
        });
        check("ordinary_enchanted_custom_item", () -> {
            ItemStack expected = new ItemStack(Material.DIAMOND_SWORD);
            ItemMeta meta = expected.getItemMeta();
            meta.displayName(Component.text("测试之剑").color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("说明\n换行")));
            meta.addEnchant(Enchantment.SHARPNESS, 5, true);
            meta.getPersistentDataContainer().set(new NamespacedKey("bookprobe", "value"), PersistentDataType.STRING, "保留");
            expected.setItemMeta(meta); same(expected, ItemNbt.decompress(ItemNbt.compress(expected)));
        });
        check("new_container_nested_book", () -> {
            ItemStack expected = container(book()); same(expected, ItemNbt.decompress(ItemNbt.compress(expected)));
        });
        check("legacy_container_nested_book", () -> {
            ItemStack expected = container(simpleBook("容器里的书\n第二行"));
            same(expected, ItemNbt.decompress(compressTag(unversioned(expected))));
        });
        check("legacy_bundle_nested_book", () -> {
            ItemStack expected = new ItemStack(Material.BUNDLE);
            BundleMeta meta = (BundleMeta) expected.getItemMeta();
            meta.setItems(List.of(simpleBook("收纳袋里的书\n第二行"))); expected.setItemMeta(meta);
            same(expected, ItemNbt.decompress(compressTag(unversioned(expected))));
        });
        check("legacy_charged_projectile_name", () -> {
            ItemStack arrow = new ItemStack(Material.ARROW); ItemMeta name = arrow.getItemMeta();
            name.displayName(Component.text("箭矢\n第二行").color(NamedTextColor.AQUA)); arrow.setItemMeta(name);
            ItemStack expected = new ItemStack(Material.CROSSBOW); CrossbowMeta meta = (CrossbowMeta) expected.getItemMeta();
            meta.setChargedProjectiles(List.of(arrow)); expected.setItemMeta(meta);
            same(expected, ItemNbt.decompress(compressTag(unversioned(expected))));
        });
        check("custom_data_does_not_imply_native_item_format", () -> {
            var detector = Class.forName("su.nightexpress.nightcore.util.nbt.LegacyItemFormat").getDeclaredMethod("hasNativeText", JsonElement.class);
            detector.setAccessible(true);
            JsonElement fake = JsonParser.parseString("{\"components\":{\"minecraft:custom_data\":{\"components\":{\"minecraft:custom_name\":{\"text\":\"fake\"}}}}}");
            require(Boolean.FALSE.equals(detector.invoke(null, fake)), "arbitrary custom data was treated as item schema evidence");
        });
        check("lossless_guard_rejects_unknown_root_field", () -> {
            ItemStack item = book(); BookMeta meta = (BookMeta) item.getItemMeta();
            meta.pages(List.of(Component.text("styled").color(NamedTextColor.RED),
                Component.text("{\"text\":\"legacy fallback\",\"color\":\"gold\"}"))); item.setItemMeta(meta);
            CompoundTag raw = unversioned(item); raw.putString("UnknownRootField", "must not silently discard");
            ItemStack expectedFallback = NbtUtil.tagToItemStack(raw, 3955);
            ItemStack actual = ItemNbt.decompress(compressTag(raw));
            same(expectedFallback, actual);
            require(((BookMeta) actual.getItemMeta()).page(2).equals(Component.text("legacy fallback").color(NamedTextColor.GOLD)),
                "lossy shortcut bypassed the original JSON migration");
        });
        check("legacy_1_21_1_styled_json_book", () -> {
            String json = "{id:'minecraft:written_book',count:1,components:{'minecraft:written_book_content':{title:{raw:'旧书'},author:'Legacy',pages:[{raw:'{\"text\":\"旧版红字\",\"color\":\"red\",\"bold\":true}'}]}}}";
            ItemStack actual = ItemNbt.decompress(compressTag(TagParser.parseCompoundFully(json)));
            require(actual != null && actual.getItemMeta() instanceof BookMeta, "legacy book lost");
            BookMeta meta = (BookMeta) actual.getItemMeta();
            require(meta.getTitle().equals("旧书") && meta.getAuthor().equals("Legacy"), "legacy title or author changed");
            Component expected = Component.text("旧版红字").color(NamedTextColor.RED).decoration(TextDecoration.BOLD, true);
            require(meta.page(1).equals(expected), "legacy style lost: " + meta.page(1));
        });
        check("explicit_legacy_source_still_migrates", () -> {
            String json = "{id:'minecraft:stone',Count:2b,tag:{display:{Name:'{\"text\":\"旧石头\",\"color\":\"gold\"}'}}}";
            ItemStack actual = new ItemTag(json, 3700).getItemStack();
            require(actual != null && actual.getType() == Material.STONE && actual.getAmount() == 2, "legacy pre-components item lost");
            require(actual.getItemMeta().displayName().equals(Component.text("旧石头").color(NamedTextColor.GOLD)), "old display name not migrated");
        });
        check("serialized_book_survives_disk_and_restart", () -> {
            Path saved = getDataFolder().toPath().resolve("saved-book.txt"); getDataFolder().mkdirs();
            if (Files.exists(saved)) {
                same(book(), ItemNbt.decompress(Files.readString(saved)));
                getLogger().info("PERSISTED_BOOK_READ_FROM_PREVIOUS_JVM");
            }
            Files.writeString(saved, ItemNbt.compress(book()));
            same(book(), ItemNbt.decompress(Files.readString(saved)));
        });
        if (Bukkit.getPluginManager().isPluginEnabled("CombatPets")) {
            check("combatpets_actual_serializer_inventory_and_equipment", () -> {
                PetData data = petData();
                String json = DataHandler.GSON.toJson(data, PetData.class);
                samePetBooks(DataHandler.GSON.fromJson(json, PetData.class));
                Path saved = getDataFolder().toPath().resolve("saved-pet.json");
                if (Files.exists(saved)) {
                    samePetBooks(DataHandler.GSON.fromJson(Files.readString(saved), PetData.class));
                    getLogger().info("PERSISTED_PET_READ_FROM_PREVIOUS_JVM");
                }
                Files.writeString(saved, json);
            });
            check("combatpets_actual_serializer_legacy_records", () -> {
                JsonObject json = DataHandler.GSON.toJsonTree(petData(), PetData.class).getAsJsonObject();
                String legacy = compressTag(unversioned(book()));
                JsonArray inventory = new JsonArray(); inventory.add(legacy); json.add("inventory", inventory);
                json.getAsJsonObject("equipmentSlots").addProperty("HAND", legacy);
                samePetBooks(DataHandler.GSON.fromJson(json, PetData.class));
            });
        }
        try {
            JsonObject report = new JsonObject(); report.addProperty("total", results.size()); report.addProperty("failed", failed);
            report.add("checks", results); getDataFolder().mkdirs();
            Files.writeString(getDataFolder().toPath().resolve("results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
            getLogger().info("PROBE_COMPLETE total=" + results.size() + " failed=" + failed);
        } catch (IOException error) { throw new RuntimeException(error); }
        Bukkit.shutdown();
    }
    static ItemStack container(ItemStack item) {
        ItemStack result = new ItemStack(Material.SHULKER_BOX);
        BlockStateMeta meta = (BlockStateMeta) result.getItemMeta();
        ShulkerBox box = (ShulkerBox) meta.getBlockState();
        box.getInventory().setItem(7, item); meta.setBlockState(box); result.setItemMeta(meta); return result;
    }
    static PetData petData() {
        PetData data = PetData.create(PetAPI.getPetManager().getTemplate("pig"), PetAPI.getPetManager().getTier("common"));
        data.getInventory().clear(); data.getInventory().add(book());
        data.getEquipment().clear(); data.getEquipment().put(EquipmentSlot.HAND, book()); return data;
    }
    static void samePetBooks(PetData data) {
        require(data != null && data.getInventory().size() == 1, "pet inventory changed");
        same(book(), data.getInventory().getFirst()); same(book(), data.getEquipment().get(EquipmentSlot.HAND));
    }
}
