package su.nightexpress.nightcore.core;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.After;
import org.junit.Test;
import su.nightexpress.nightcore.menu.MenuViewer;
import su.nightexpress.nightcore.menu.api.Menu;
import su.nightexpress.nightcore.menu.impl.AbstractMenu;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;

public class CoreManagerMenuRefreshTest {

    private final Fixture fixture = new Fixture();

    @After
    public void clearMenu() {
        AbstractMenu.PLAYER_MENUS.remove(this.fixture.playerId);
    }

    @Test
    public void refreshesCurrentSession() {
        MenuViewer viewer = this.fixture.openMenu();

        CoreManager.refreshMenuIfCurrent(this.fixture.menu, viewer);

        assertEquals(1, this.fixture.refreshes.get());
    }

    @Test
    public void ignoresRefreshQueuedBeforeClose() {
        MenuViewer viewer = this.fixture.openMenu();
        AbstractMenu.PLAYER_MENUS.remove(this.fixture.playerId);
        this.fixture.currentViewer.set(null);

        CoreManager.refreshMenuIfCurrent(this.fixture.menu, viewer);

        assertEquals(0, this.fixture.refreshes.get());
    }

    @Test
    public void ignoresRefreshAfterDifferentMenuOpens() {
        MenuViewer viewer = this.fixture.openMenu();
        AbstractMenu.PLAYER_MENUS.put(this.fixture.playerId, this.fixture.newMenu());

        CoreManager.refreshMenuIfCurrent(this.fixture.menu, viewer);

        assertEquals(0, this.fixture.refreshes.get());
    }

    @Test
    public void oldRefreshCannotAffectReopenedSessionOfSameMenu() {
        MenuViewer oldViewer = this.fixture.openMenu();
        MenuViewer newViewer = this.fixture.openMenu();

        CoreManager.refreshMenuIfCurrent(this.fixture.menu, oldViewer);
        assertEquals(0, this.fixture.refreshes.get());

        CoreManager.refreshMenuIfCurrent(this.fixture.menu, newViewer);
        assertEquals(1, this.fixture.refreshes.get());
    }

    @Test
    public void ignoresRefreshWhenTopInventoryChanged() {
        MenuViewer viewer = this.fixture.openMenu();
        this.fixture.topInventory.set(this.fixture.newInventory());

        CoreManager.refreshMenuIfCurrent(this.fixture.menu, viewer);

        assertEquals(0, this.fixture.refreshes.get());
    }

    @Test
    public void ignoresViewerWithoutInventory() {
        MenuViewer viewer = new MenuViewer(this.fixture.player);
        this.fixture.currentViewer.set(viewer);
        AbstractMenu.PLAYER_MENUS.put(this.fixture.playerId, this.fixture.menu);

        CoreManager.refreshMenuIfCurrent(this.fixture.menu, viewer);

        assertEquals(0, this.fixture.refreshes.get());
    }

    private static final class Fixture {

        private final UUID playerId = UUID.randomUUID();
        private final AtomicReference<Inventory> topInventory = new AtomicReference<>();
        private final AtomicReference<MenuViewer> currentViewer = new AtomicReference<>();
        private final AtomicInteger refreshes = new AtomicInteger();
        private final InventoryView openView = proxy(InventoryView.class, (self, method, args) -> {
            if (method.getName().equals("getTopInventory")) return this.topInventory.get();
            throw new AssertionError("Unexpected inventory view call: " + method);
        });
        private final Player player = proxy(Player.class, (self, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> this.playerId;
            case "getOpenInventory", "openInventory" -> this.openView;
            default -> throw new AssertionError("Unexpected player call: " + method);
        });
        private final Menu menu = this.newMenu();

        private MenuViewer openMenu() {
            Inventory inventory = this.newInventory();
            MenuViewer viewer = new MenuViewer(this.player);
            viewer.openInventory(inventory);
            this.topInventory.set(inventory);
            this.currentViewer.set(viewer);
            AbstractMenu.PLAYER_MENUS.put(this.playerId, this.menu);
            return viewer;
        }

        private Menu newMenu() {
            return proxy(Menu.class, (self, method, args) -> switch (method.getName()) {
                case "getViewer" -> this.currentViewer.get();
                case "flush" -> {
                    this.refreshes.incrementAndGet();
                    yield null;
                }
                default -> throw new AssertionError("Unexpected menu call: " + method);
            });
        }

        private Inventory newInventory() {
            return proxy(Inventory.class, (self, method, args) -> {
                throw new AssertionError("Unexpected inventory call: " + method);
            });
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    case "toString" -> type.getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(self));
                    default -> throw new AssertionError("Unexpected object call: " + method);
                };
            }
            return handler.invoke(self, method, args);
        }));
    }
}
