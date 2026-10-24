package su.nightexpress.nightcore.util.blocktracker;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import su.nightexpress.nightcore.Engine;

import java.util.*;
import java.util.function.Predicate;

public final class PlayerBlockTracker {

    public static final Set<Predicate<Block>>    BLOCK_FILTERS     = new HashSet<>();
    public static final NamespacedKey            TRACKED_DATA_KEY  = NamespacedKey.minecraft("tracked_chunk_data");
    private static final Map<UUID, TrackedWorld> TRACKED_WORLD_MAP = new Object2ObjectOpenHashMap<>();

    private static TrackListener<?> listener;

    public static void initialize() {
        if (listener == null) {
            initCurrentlyLoadedWorlds();
            (listener = new TrackListener<>(Engine.core())).registerListeners();
        }
    }

    public static void shutdown() {
        if (listener != null) {
            terminateCurrentlyLoadedWorlds();
            listener.unregisterListeners();
            listener = null;
            BLOCK_FILTERS.clear();
        }
    }

    public static void initWorld(@NonNull World world) {
        TrackedWorld trackedWorld = new TrackedWorld();
        for (Chunk loadedChunk : world.getLoadedChunks()) {
            trackedWorld.initChunk(loadedChunk);
        }
        TRACKED_WORLD_MAP.put(world.getUID(), trackedWorld);
    }

    public static void terminateWorld(@NonNull World world) {
        TrackedWorld trackedWorld = TRACKED_WORLD_MAP.remove(world.getUID());
        if (trackedWorld == null) {
            return;
        }
        for (Chunk loadedChunk : world.getLoadedChunks()) {
            trackedWorld.terminateChunk(loadedChunk);
        }
    }

    public static void initChunk(@NonNull Chunk chunk) {
        TrackedWorld trackedWorld = getTrackedWorldOf(chunk);
        if (trackedWorld == null) {
            return;
        }
        trackedWorld.initChunk(chunk);
    }

    public static void terminateChunk(@NonNull Chunk chunk) {
        TrackedWorld trackedWorld = getTrackedWorldOf(chunk);
        if (trackedWorld == null) {
            return;
        }
        trackedWorld.terminateChunk(chunk);
    }

    public static void initCurrentlyLoadedWorlds() {
        Bukkit.getWorlds().forEach(PlayerBlockTracker::initWorld);
    }

    public static void terminateCurrentlyLoadedWorlds() {
        Bukkit.getWorlds().forEach(PlayerBlockTracker::terminateWorld);
    }

    public static boolean isTracked(@NonNull Block block) {
        TrackedWorld trackedWorld = getTrackedWorldOf(block);
        if (trackedWorld == null) {
            return false;
        }
        return trackedWorld.isTracked(block);
    }

    public static void track(@NonNull Block block) {
        if (BLOCK_FILTERS.stream().noneMatch(filter -> filter.test(block))) return;
        trackForce(block);
    }

    public static void trackForce(@NonNull Block block) {
        TrackedWorld trackedWorld = getTrackedWorldOf(block);
        if (trackedWorld == null) {
            return;
        }
        trackedWorld.add(block);
    }

    public static void unTrack(@NonNull Block block) {
        TrackedWorld trackedWorld = getTrackedWorldOf(block);
        if (trackedWorld == null) {
            return;
        }
        trackedWorld.remove(block);
    }

    public static void track(@NonNull Collection<Block> trackedBlocks) {
        trackedBlocks.forEach(PlayerBlockTracker::trackForce);
    }

    public static void unTrack(@NonNull Collection<Block> trackedBlocks) {
        trackedBlocks.forEach(PlayerBlockTracker::unTrack);
    }

    public static void shift(@NonNull BlockFace direction, @NonNull List<Block> blocks) {
        // Snapshot tracked state before any change, so adjacent blocks in the chain don't leak marks into each other.
        Set<Block> toClear = new HashSet<>();
        Set<Block> toTrack = new HashSet<>();

        blocks.forEach(block -> {
            toClear.add(block);

            // A block broken by the piston has no destination to clear.
            if (block.getPistonMoveReaction() == PistonMoveReaction.BREAK) {
                return;
            }

            Block target = block.getRelative(direction);
            toClear.add(target);

            if (isTracked(block)) {
                toTrack.add(target);
            }
        });

        // Clear sources and targets first, then restore marks at the new positions.
        // trackForce: the block was already accepted by filters when placed, and the target still holds the old block at this point.
        toClear.forEach(PlayerBlockTracker::unTrack);
        toTrack.forEach(PlayerBlockTracker::trackForce);
    }

    public static void move(@NonNull Block from, @NonNull Block to) {
        unTrack(from);
        track(to);
    }

    @Nullable
    private static TrackedWorld getTrackedWorldOf(@NonNull Block block) {
        return TRACKED_WORLD_MAP.get(block.getWorld().getUID());
    }

    @Nullable
    private static TrackedWorld getTrackedWorldOf(@NonNull Chunk chunk) {
        return TRACKED_WORLD_MAP.get(chunk.getWorld().getUID());
    }
}
