package su.nightexpress.nightcore.util.blocktracker;

import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class PlayerBlockTrackerTest {

    private static final NamespacedKey DATA_KEY = PlayerBlockTracker.TRACKED_DATA_KEY;

    @Test
    public void movedMarksReplaceSourcesAcrossChunksAndSurviveReload() {
        Fixture fixture = new Fixture(0, 1);
        Block first = fixture.block(15);
        Block middle = fixture.block(16);
        Block last = fixture.block(17);
        Block end = fixture.block(18);
        fixture.store(0, first);
        fixture.store(1, last);

        PlayerBlockTracker.initWorld(fixture.world);
        try {
            PlayerBlockTracker.shift(BlockFace.EAST, List.of(first, middle, last));
            assertMovedMarks(first, middle, last, end);

            PlayerBlockTracker.terminateWorld(fixture.world);
            assertFalse(fixture.dataAt(0).containsKey(DATA_KEY));
            fixture.assertStored(1, middle, end);

            PlayerBlockTracker.initWorld(fixture.world);
            assertMovedMarks(first, middle, last, end);
        }
        finally {
            PlayerBlockTracker.terminateWorld(fixture.world);
        }
    }

    @Test
    public void brokenBlockOnlyLosesItsOwnMark() {
        for (boolean sourceTracked : new boolean[]{false, true}) {
            Fixture fixture = new Fixture(0);
            Block broken = fixture.block(0);
            Block neighbor = fixture.block(1);
            fixture.setBreak(0);
            if (sourceTracked) {
                fixture.store(0, broken, neighbor);
            }
            else {
                fixture.store(0, neighbor);
            }

            PlayerBlockTracker.initWorld(fixture.world);
            try {
                PlayerBlockTracker.shift(BlockFace.EAST, List.of(broken));
                assertFalse(PlayerBlockTracker.isTracked(broken));
                assertTrue(PlayerBlockTracker.isTracked(neighbor));

                PlayerBlockTracker.terminateWorld(fixture.world);
                fixture.assertStored(0, neighbor);

                PlayerBlockTracker.initWorld(fixture.world);
                assertFalse(PlayerBlockTracker.isTracked(broken));
                assertTrue(PlayerBlockTracker.isTracked(neighbor));
            }
            finally {
                PlayerBlockTracker.terminateWorld(fixture.world);
            }
        }
    }

    @Test
    public void shiftUsesRetractDirection() {
        Fixture fixture = new Fixture(0);
        Block source = fixture.block(2);
        Block target = fixture.block(1);
        fixture.store(0, source);

        PlayerBlockTracker.initWorld(fixture.world);
        try {
            PlayerBlockTracker.shift(BlockFace.WEST, List.of(source));
            assertFalse(PlayerBlockTracker.isTracked(source));
            assertTrue(PlayerBlockTracker.isTracked(target));
            PlayerBlockTracker.terminateWorld(fixture.world);
            fixture.assertStored(0, target);
        }
        finally {
            PlayerBlockTracker.terminateWorld(fixture.world);
        }
    }

    private static void assertMovedMarks(Block first, Block middle, Block last, Block end) {
        assertFalse(PlayerBlockTracker.isTracked(first));
        assertTrue(PlayerBlockTracker.isTracked(middle));
        assertFalse(PlayerBlockTracker.isTracked(last));
        assertTrue(PlayerBlockTracker.isTracked(end));
    }

    private record Position(int x, int y, int z) { }

    private static final class Fixture {

        private final UUID uuid = UUID.randomUUID();
        private final Map<Integer, Map<NamespacedKey, Object>> chunkData = new HashMap<>();
        private final Map<Position, Block> blocks = new HashMap<>();
        private final Map<Position, PistonMoveReaction> reactions = new HashMap<>();
        private final World world;
        private Chunk[] chunks;

        private Fixture(int... chunkXs) {
            this.world = proxy(World.class, (self, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(self, method, args);
                return switch (method.getName()) {
                    case "getUID" -> this.uuid;
                    case "getLoadedChunks" -> this.chunks;
                    default -> throw unexpected(method);
                };
            });
            this.chunks = Arrays.stream(chunkXs).mapToObj(this::chunk).toArray(Chunk[]::new);
        }

        private Chunk chunk(int chunkX) {
            Map<NamespacedKey, Object> data = dataAt(chunkX);
            PersistentDataContainer container = proxy(PersistentDataContainer.class, (self, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(self, method, args);
                return switch (method.getName()) {
                    case "get" -> data.get(args[0]);
                    case "set" -> { data.put((NamespacedKey) args[0], args[2]); yield null; }
                    case "remove" -> { data.remove(args[0]); yield null; }
                    default -> throw unexpected(method);
                };
            });
            return proxy(Chunk.class, (self, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(self, method, args);
                return switch (method.getName()) {
                    case "getX" -> chunkX;
                    case "getZ" -> 0;
                    case "getWorld" -> this.world;
                    case "getPersistentDataContainer" -> container;
                    default -> throw unexpected(method);
                };
            });
        }

        private Block block(int x) {
            return blockAt(new Position(x, 64, 0));
        }

        private Block blockAt(Position position) {
            return this.blocks.computeIfAbsent(position, pos -> proxy(Block.class, (self, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(self, method, args);
                return switch (method.getName()) {
                    case "getWorld" -> this.world;
                    case "getX" -> pos.x();
                    case "getY" -> pos.y();
                    case "getZ" -> pos.z();
                    case "getPistonMoveReaction" -> this.reactions.getOrDefault(pos, PistonMoveReaction.MOVE);
                    case "getRelative" -> {
                        BlockFace face = (BlockFace) args[0];
                        yield blockAt(new Position(pos.x() + face.getModX(), pos.y() + face.getModY(), pos.z() + face.getModZ()));
                    }
                    default -> throw unexpected(method);
                };
            }));
        }

        private void setBreak(int x) {
            this.reactions.put(new Position(x, 64, 0), PistonMoveReaction.BREAK);
        }

        private Map<NamespacedKey, Object> dataAt(int chunkX) {
            return this.chunkData.computeIfAbsent(chunkX, ignored -> new HashMap<>());
        }

        private void store(int chunkX, Block... storedBlocks) {
            dataAt(chunkX).put(DATA_KEY, Arrays.stream(storedBlocks).mapToInt(TrackUtil::getRelativeChunkPosition).toArray());
        }

        private void assertStored(int chunkX, Block... expectedBlocks) {
            int[] stored = (int[]) dataAt(chunkX).get(DATA_KEY);
            assertNotNull(stored);
            assertEquals(expectedBlocks.length, stored.length);
            Set<Integer> expected = Arrays.stream(expectedBlocks).map(TrackUtil::getRelativeChunkPosition).collect(Collectors.toSet());
            Set<Integer> actual = Arrays.stream(stored).boxed().collect(Collectors.toSet());
            assertEquals(expected, actual);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object objectMethod(Object self, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> self == args[0];
            case "hashCode" -> System.identityHashCode(self);
            case "toString" -> method.getDeclaringClass().getSimpleName() + "@" + System.identityHashCode(self);
            default -> throw unexpected(method);
        };
    }

    private static AssertionError unexpected(Method method) {
        return new AssertionError("Unexpected API call: " + method);
    }
}
