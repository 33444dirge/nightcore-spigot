import su.nightexpress.nightcore.NightPlugin;
import su.nightexpress.nightcore.config.PluginDetails;
public class LifecycleNormal extends NightPlugin {
    public int unloadCalls;
    public int shutdownCalls;
    protected PluginDetails getDefaultDetails() { return PluginDetails.create("LifecycleNormal", new String[]{"fixture"}); }
    public void enable() {}
    public void disable() {}
    protected void loadManagers() {}
    protected void unloadManagers() {
        unloadCalls++;

    }
    protected void onShutdown() {
        shutdownCalls++;

    }
}
