import su.nightexpress.nightcore.NightPlugin;
import su.nightexpress.nightcore.config.PluginDetails;
public class LifecycleUnloadFailure extends NightPlugin {
    public int unloadCalls;
    public int shutdownCalls;
    protected PluginDetails getDefaultDetails() { return PluginDetails.create("LifecycleUnloadFailure", new String[]{"fixture"}); }
    public void enable() {}
    public void disable() {}
    protected void loadManagers() {}
    protected void unloadManagers() {
        unloadCalls++;
        throw new IllegalStateException("EXPECTED_FIXTURE_UNLOAD_FAILURE");
    }
    protected void onShutdown() {
        shutdownCalls++;

    }
}
