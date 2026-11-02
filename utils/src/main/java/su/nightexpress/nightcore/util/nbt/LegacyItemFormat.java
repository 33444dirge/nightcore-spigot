package su.nightexpress.nightcore.util.nbt;

import com.google.gson.*;

final class LegacyItemFormat {

    private LegacyItemFormat() {}

    static boolean hasNativeText(JsonElement item) {
        if (!item.isJsonObject()) return false;
        JsonElement components = item.getAsJsonObject().get("components");
        if (components == null || !components.isJsonObject()) return false;
        JsonObject values = components.getAsJsonObject();
        if (nativeText(values.get("minecraft:custom_name")) || nativeText(values.get("minecraft:item_name"))) return true;
        JsonElement lore = values.get("minecraft:lore");
        if (lore != null && lore.isJsonArray()) {
            for (JsonElement line : lore.getAsJsonArray()) if (nativeText(line)) return true;
        }
        JsonElement book = values.get("minecraft:written_book_content");
        if (book != null && book.isJsonObject()) {
            JsonElement pages = book.getAsJsonObject().get("pages");
            if (pages != null && pages.isJsonArray()) {
                for (JsonElement page : pages.getAsJsonArray()) {
                    if (page.isJsonObject() && (nativeText(page.getAsJsonObject().get("raw")) ||
                        nativeText(page.getAsJsonObject().get("filtered")))) return true;
                }
            }
        }
        // Follow actual item-containing components, never similarly named custom data.
        for (String key : new String[]{"minecraft:container", "minecraft:bundle_contents", "minecraft:charged_projectiles"}) {
            JsonElement contents = values.get(key);
            if (contents == null || !contents.isJsonArray()) continue;
            for (JsonElement entry : contents.getAsJsonArray()) {
                JsonElement child = key.equals("minecraft:container") && entry.isJsonObject()
                    ? entry.getAsJsonObject().get("item") : entry;
                if (child != null && hasNativeText(child)) return true;
            }
        }
        JsonElement remainder = values.get("minecraft:use_remainder");
        return remainder != null && hasNativeText(remainder);
    }

    private static boolean nativeText(JsonElement text) {
        if (text == null || text.isJsonNull()) return false;
        if (text.isJsonObject() || text.isJsonArray()) return true;
        if (!text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString()) return false;
        try {
            JsonParser.parseString(text.getAsString());
            return false;
        }
        catch (JsonParseException exception) {
            return true;
        }
    }
}
