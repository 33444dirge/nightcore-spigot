package su.nightexpress.nightcore.util.nbt;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import su.nightexpress.nightcore.util.Reflex;
import su.nightexpress.nightcore.util.Version;

import java.lang.reflect.Method;

public class NbtUtil {

    private static final String CRAFTBUKKIT_PACKAGE = Bukkit.getServer().getClass().getPackage().getName();

    private static final Class<?> CLS_CRAFT_REGISTRY   = Reflex.safeClass(CRAFTBUKKIT_PACKAGE, "CraftRegistry");
    private static final Class<?> CLS_CRAFT_ITEM_STACK = Reflex.safeClass(CRAFTBUKKIT_PACKAGE + ".inventory",
        "CraftItemStack");

    public static final Class<?> CLS_NMS_ITEM_STACK = Reflex.safeClass("net.minecraft.world.item", "ItemStack");

    public static final Class<?> CLS_TAG                    = Reflex.safeClass("net.minecraft.nbt", "Tag", "NBTBase");
    public static final Class<?> CLS_TAG_PARSER             = Reflex.safeClass("net.minecraft.nbt", "TagParser",
        "MojangsonParser");
    public static final Class<?> CLS_HOLDER_LOOKUP_PROVIDER = Reflex.safeClass("net.minecraft.core",
        "HolderLookup$Provider", "HolderLookup$a");

    private static final Method GET_MC_REGISTRY = Reflex.safeMethod(CLS_CRAFT_REGISTRY, "getMinecraftRegistry");
    private static final Method PARSE_TAG       = Reflex.safeMethod(CLS_TAG_PARSER, "parseCompoundFully", "a",
        String.class);

    public static final Method AS_NMS_COPY    = Reflex.safeMethod(CLS_CRAFT_ITEM_STACK, "asNMSCopy", ItemStack.class);
    public static final Method AS_BUKKIT_COPY = Reflex.safeMethod(CLS_CRAFT_ITEM_STACK, "asBukkitCopy",
        CLS_NMS_ITEM_STACK);

    public static final Object REGISTRY_ACCESS = Reflex.invokeMethod(GET_MC_REGISTRY, null);

    @Nullable
    public static Object tagFromString(@NonNull String json) {
        return Reflex.invokeMethod(PARSE_TAG, null, json);
    }

    @NonNull
    public static JsonElement tagToJson(@NonNull Object compoundTag) {
        return NbtOps.convertTag(JsonOps.INSTANCE, compoundTag);
    }

    @Nullable
    public static ItemStack tagToItemStack(@NonNull String tagString, int sourceVersion) {
        Object tag = tagFromString(tagString);
        return tag == null ? null : tagToItemStack(tag, sourceVersion);
    }

    @Nullable
    public static ItemStack tagToItemStack(@NonNull String tagString) {
        if (tagString.isBlank() || tagString.equals("{}")) return null;
        Object tag = tagFromString(tagString);
        return tag == null ? null : tagToItemStack(tag);
    }

    @Nullable
    public static ItemStack tagToItemStack(@NonNull Object tag) {
        int storedVersion = dynamic(tag).get("DataVersion").asInt(-1);
        if (storedVersion > 0) return tagToItemStack(tag, storedVersion);

        // Unversioned records can contain either old JSON text or native NBT text.
        // Only accept native text when the complete current codec roundtrip is lossless.
        if (LegacyItemFormat.hasNativeText(tagToJson(tag))) {
            Object itemStack = NbtSerializer.decodeItemStackExact(tag);
            if (itemStack != null && tag.equals(NbtSerializer.encodeItemStack(itemStack))) {
                return (ItemStack) Reflex.invokeMethod(AS_BUKKIT_COPY, null, itemStack);
            }
        }
        return tagToItemStack(tag, Version.MC_1_21.getDataVersion());
    }

    @Nullable
    public static ItemStack tagToItemStack(@NonNull Object tag, int sourceVersion) {
        Dynamic<Object> data = dynamic(tag);
        int storedVersion = data.get("DataVersion").asInt(-1);
        if (storedVersion > 0) sourceVersion = storedVersion;
        Object compoundTag = DataFixerUtil.updateItemStack(data.remove("DataVersion").getValue(), sourceVersion);
        Object itemStack = NbtSerializer.decodeItemStack(compoundTag);

        return itemStack == null ? null : (ItemStack) Reflex.invokeMethod(AS_BUKKIT_COPY, null, itemStack);
    }

    @NonNull
    public static Object tagFromItemStack(@NonNull ItemStack bukkitStack) {
        Object nmsStack = Reflex.invokeMethod(AS_NMS_COPY, null, bukkitStack);
        if (nmsStack == null) throw new IllegalStateException("Could not convert bukkit ItemStack to NMS copy");

        Dynamic<Object> data = dynamic(NbtSerializer.encodeItemStack(nmsStack));
        return data.set("DataVersion", data.createInt(Bukkit.getUnsafe().getDataVersion())).getValue();
    }

    @SuppressWarnings("unchecked")
    private static Dynamic<Object> dynamic(@NonNull Object tag) {
        return new Dynamic<>((DynamicOps<Object>) NbtOps.INSTANCE, tag);
    }
}
