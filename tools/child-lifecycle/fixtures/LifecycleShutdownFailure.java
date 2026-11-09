import su.nightexpress.nightcore.NightPlugin;
import su.nightexpress.nightcore.config.PluginDetails;
public class LifecycleShutdownFailure extends NightPlugin {
    public int unloadCalls;
    public int shutdownCalls;
    protected PluginDetails getDefaultDetails() { return PluginDetails.create("LifecycleShutdownFailure", new String[]{"fixture"}); }
    public void enable() {}
    public void disable() {}
    protected void loadManagers() {}
    protected void unloadManagers() {
        unloadCalls++;

    }
    protected void onShutdown() {
        shutdownCalls++;
        throw new IllegalStateException("EXPECTED_FIXTURE_SHUTDOWN_FAILURE");
    }
}
