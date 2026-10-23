package su.nightexpress.nightcore.dialog;

import org.bukkit.entity.Player;
import org.junit.After;
import org.junit.Test;
import su.nightexpress.nightcore.menu.api.Menu;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class DialogTimeoutTest {

    private final UUID playerId = UUID.randomUUID();
    private final AtomicInteger titles = new AtomicInteger();
    private final AtomicInteger opens = new AtomicInteger();
    private final List<Runnable> tasks = new ArrayList<>();
    private final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
        (self, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> this.playerId;
            case "sendTitle" -> { this.titles.incrementAndGet(); yield null; }
            default -> throw new AssertionError("Unexpected player call: " + method);
        });

    @After
    public void cleanup() {
        Dialog.shutdown();
    }

    @Test
    public void timeoutOnlyReopensMenuWhenPlayerTaskRuns() throws Exception {
        this.registerExpired();
        this.queueTimeout();
        assertEquals(1, this.tasks.size());
        assertEquals(0, this.opens.get());
        assertEquals(0, this.titles.get());

        this.tasks.get(0).run();
        assertEquals(1, this.opens.get());
        assertEquals(1, this.titles.get());
        assertNull(Dialog.get(this.player));
    }

    @Test
    public void queuedTimeoutDoesNotCloseReplacement() throws Exception {
        this.registerExpired();
        this.queueTimeout();
        Dialog replacement = new Dialog(this.player, (dialog, input) -> false);
        this.dialogs().put(this.playerId, replacement);
        this.tasks.get(0).run();
        assertSame(replacement, Dialog.get(this.player));
        assertEquals(0, this.opens.get());
        assertEquals(0, this.titles.get());
    }

    @Test
    public void queuedTimeoutHonorsRenewedDeadline() throws Exception {
        Dialog dialog = this.registerExpired();
        this.queueTimeout();
        dialog.setTimeout(60);
        this.tasks.get(0).run();
        assertSame(dialog, Dialog.get(this.player));
        assertEquals(0, this.opens.get());
        assertEquals(0, this.titles.get());
    }

    @Test
    public void duplicateTasksOnlyStopOnce() throws Exception {
        this.registerExpired();
        this.queueTimeout();
        this.queueTimeout();
        this.tasks.forEach(Runnable::run);
        assertEquals(1, this.opens.get());
        assertEquals(1, this.titles.get());
    }

    @Test
    public void queuedTimeoutIsIgnoredAfterShutdown() throws Exception {
        this.registerExpired();
        this.queueTimeout();
        Dialog.shutdown();
        this.tasks.get(0).run();
        assertEquals(0, this.opens.get());
        assertEquals(0, this.titles.get());
    }

    private Dialog registerExpired() throws Exception {
        Menu menu = (Menu) Proxy.newProxyInstance(Menu.class.getClassLoader(), new Class<?>[]{Menu.class},
            (self, method, args) -> {
                if (!method.getName().equals("open")) throw new AssertionError("Unexpected menu call: " + method);
                assertSame(this.player, args[0]);
                this.opens.incrementAndGet();
                return true;
            });
        Dialog dialog = new Dialog(this.player, (current, input) -> false).setLastMenu(menu);
        dialog.setTimeout(-1);
        this.dialogs().put(this.playerId, dialog);
        return dialog;
    }

    private void queueTimeout() {
        Dialog.checkTimeOut((owner, task) -> {
            assertSame(this.player, owner);
            this.tasks.add(task);
        });
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Dialog> dialogs() throws Exception {
        Field field = Dialog.class.getDeclaredField("DIALOG_MAP");
        field.setAccessible(true);
        return (Map<UUID, Dialog>) field.get(null);
    }
}
