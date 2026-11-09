import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;
import com.google.gson.Gson;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Independent diagnostic observer; no dependency on, or cached classes from, ExcellentCrates. */
public class HotAudit extends JavaPlugin implements Listener, CommandExecutor {
    private final List<WeakReference<Plugin>> oldPlugins=new ArrayList<>();
    private final List<WeakReference<ClassLoader>> oldLoaders=new ArrayList<>();
    private final Gson json=new Gson();
    private Player player;
    private int shifts,allowed,openingShifts,openingAllowed,nativeWindowShifts;
    private int helpInvocations;
    private su.nightexpress.nightcore.commands.command.NightCommand helpRoute;
    public void onEnable(){getCommand("hotcheck").setExecutor(this);getServer().getPluginManager().registerEvents(this,this);getServer().getMessenger().registerOutgoingPluginChannel(this,"ce:audit");}
    private void row(String action,String tag,Map<String,Object> data){
        data.put("action",action);data.put("tag",tag);
        String line=json.toJson(data);getLogger().info("HOTRESULT "+line);
        try{getDataFolder().mkdirs();Files.writeString(getDataFolder().toPath().resolve("results.jsonl"),line+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){throw new RuntimeException(e);}
    }
    private Object call(Object target,String name,Object...args)throws Exception{
        for(Method m:target.getClass().getMethods()){
            if(!m.getName().equals(name)||m.getParameterCount()!=args.length)continue;
            Class<?>[] types=m.getParameterTypes();boolean ok=true;
            for(int i=0;i<args.length;i++){Class<?> t=types[i];if(t==int.class)t=Integer.class;if(t==boolean.class)t=Boolean.class;if(args[i]!=null&&!t.isInstance(args[i]))ok=false;}
            if(ok)return m.invoke(target,args);
        }throw new NoSuchMethodException(target.getClass()+"."+name);
    }
    private Plugin target(){return getServer().getPluginManager().getPlugin("ExcellentCrates");}
    private Map<String,Object> inventory(){Map<String,Object> m=new TreeMap<>();for(ItemStack i:player.getInventory().getContents())if(i!=null&&!i.getType().isAir())m.merge(i.getType().name(),i.getAmount(),(a,b)->(Integer)a+(Integer)b);if(!player.getItemOnCursor().getType().isAir())m.put("cursor",player.getItemOnCursor().toString());return m;}
    private void snapshot(String action,String tag)throws Exception{
        Plugin current=target();Map<String,Object> data=new LinkedHashMap<>();
        data.put("present",current!=null);data.put("enabled",current!=null&&current.isEnabled());data.put("instance",current==null?0:System.identityHashCode(current));
        if(current!=null)data.put("listeners",HandlerList.getRegisteredListeners(current).size());
        Plugin nc=getServer().getPluginManager().getPlugin("nightcore");
        Class<?> ncType=nc.getClass().getClassLoader().loadClass("su.nightexpress.nightcore.NightCore");
        Set<?> children=(Set<?>)ncType.getField("CHILDRENS").get(null);
        List<Object> retained=new ArrayList<>();
        for(Object obj:children)if(obj instanceof Plugin p&&p.getName().equals("ExcellentCrates"))retained.add(Map.of("instance",System.identityHashCode(p),"enabled",p.isEnabled(),"loader",System.identityHashCode(p.getClass().getClassLoader())));
        data.put("nightcoreChildren",retained);
        data.put("oldPluginsAlive",oldPlugins.stream().filter(r->r.get()!=null).count());data.put("oldLoadersAlive",oldLoaders.stream().filter(r->r.get()!=null).count());
        row(action,tag,data);
    }
    public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        String action=args.length>0?args[0]:"snapshot",tag=args.length>1?args[1]:action;
        try{
            if(action.equals("fixture-reload")||action.equals("fixture-disable")) {
                Plugin fixture=getServer().getPluginManager().getPlugin(args[2]);
                Plugin core=getServer().getPluginManager().getPlugin("nightcore");
                Set<?> children=(Set<?>)core.getClass().getField("CHILDRENS").get(null);
                boolean before=children.stream().anyMatch(child->child==fixture);
                if(action.equals("fixture-reload"))call(fixture,"reload");
                else getServer().getPluginManager().disablePlugin(fixture);
                Map<String,Object> data=new LinkedHashMap<>();
                data.put("fixture",fixture.getName());data.put("registeredBefore",before);
                data.put("registeredAfter",children.stream().anyMatch(child->child==fixture));
                data.put("enabled",fixture.isEnabled());
                data.put("unloadCalls",fixture.getClass().getField("unloadCalls").get(fixture));
                data.put("shutdownCalls",fixture.getClass().getField("shutdownCalls").get(fixture));
                row(action,tag,data);
            }
            else if(action.equals("remember")){Plugin p=target();oldPlugins.add(new WeakReference<>(p));oldLoaders.add(new WeakReference<>(p.getClass().getClassLoader()));snapshot(action,tag);}
            else if(action.equals("gc")){getServer().getAsyncScheduler().runNow(this,t->{System.gc();getServer().getGlobalRegionScheduler().runDelayed(this,next->{try{snapshot(action,tag);}catch(Exception e){error(action,tag,e);}},2L);});}
            else if(action.equals("snapshot"))snapshot(action,tag);
            else{
                player=getServer().getPlayerExact("CEShiftAudit");if(player==null)throw new IllegalStateException("test player absent");
                player.getScheduler().run(this,t->{try{owner(action,tag);}catch(Throwable e){error(action,tag,e);}},null);
            }
        }catch(Throwable e){error(action,tag,e);}return true;
    }
    private void error(String action,String tag,Throwable e){row(action,tag,new LinkedHashMap<>(Map.of("error",e.toString())));e.printStackTrace();}
    private void owner(String action,String tag)throws Exception{
        Map<String,Object> data=new LinkedHashMap<>();data.put("owner",Bukkit.isOwnedByCurrentRegion(player));
        if(action.equals("help")){
            var fixture=(su.nightexpress.nightcore.NightPlugin)getServer().getPluginManager().getPlugin("LifecycleNormal");
            var route=su.nightexpress.nightcore.commands.command.NightCommand.literal(fixture,
                new String[]{"lifecycle-help","lifecycle-help-alias"},builder->builder.description("Lifecycle help regression")
                .permission("lifecycle.audit.help").executes((context,arguments)->{helpInvocations++;return true;}));
            route.register();helpRoute=route;
            var topic=Bukkit.getHelpMap().getHelpTopic("/lifecycle-help");
            var expected=new org.bukkit.help.GenericCommandHelpTopic(route);
            if(topic==null){Bukkit.getHelpMap().addTopic(expected);topic=expected;}
            data.put("textMatches",topic!=null&&topic.getShortText().equals(expected.getShortText())&&topic.getFullText(Bukkit.getConsoleSender()).equals(expected.getFullText(Bukkit.getConsoleSender())));
            boolean op=player.isOp();player.setOp(false);
            var deny=player.addAttachment(this,"lifecycle.audit.help",false);
            data.put("hiddenWhenDenied",topic!=null&&!topic.canSee(player)&&!route.testPermissionSilent(player));
            player.removeAttachment(deny);
            var allow=player.addAttachment(this,"lifecycle.audit.help",true);
            data.put("visibleWhenAllowed",topic!=null&&topic.canSee(player)&&route.testPermissionSilent(player));
            Bukkit.dispatchCommand(player,"lifecycle-help");
            Bukkit.dispatchCommand(player,"lifecycle-help-alias");
            Bukkit.dispatchCommand(player,"lifecyclenormal:lifecycle-help");
            data.put("realRoutePreserved",Bukkit.getCommandMap().getCommand("lifecycle-help")==route);
            topic.amendTopic("Amended short text","Amended full text");
            String amendedText=topic.getFullText(Bukkit.getConsoleSender());
            route.unregister();data.put("hiddenAfterUnregister",topic!=null&&!topic.canSee(player));
            data.put("topicIdentityPreserved",topic==Bukkit.getHelpMap().getHelpTopic("/lifecycle-help"));
            data.put("amendedTextPreserved",amendedText.equals(topic.getFullText(Bukkit.getConsoleSender())));
            route.register();data.put("visibleAfterRegister",topic!=null&&topic.canSee(player));
            player.removeAttachment(allow);player.setOp(op);
        }else if(action.equals("help-read")){
            data.put("invocations",helpInvocations);helpRoute.unregister();helpRoute=null;
        }else if(action.equals("help-factory")){
            var fixture=(su.nightexpress.nightcore.NightPlugin)getServer().getPluginManager().getPlugin("LifecycleNormal");
            var route=su.nightexpress.nightcore.commands.command.NightCommand.literal(fixture,
                new String[]{"lifecycle-factory"},builder->builder.description("Factory regression"));
            Bukkit.getHelpMap().registerHelpTopicFactory(route.getClass(),cmd->new org.bukkit.craftbukkit.help.CustomHelpTopic(
                "/"+cmd.getLabel(),"Factory short text","Factory full text",""));
            route.register();
            data.put("nativeFactoryNotPreempted",Bukkit.getHelpMap().getHelpTopic("/lifecycle-factory")==null);
            Bukkit.getHelpMap().getClass().getMethod("initializeCommands").invoke(Bukkit.getHelpMap());
            var topic=Bukkit.getHelpMap().getHelpTopic("/lifecycle-factory");
            data.put("customFactorySelected",topic instanceof org.bukkit.craftbukkit.help.CustomHelpTopic&&topic.getShortText().equals("Factory short text"));
            route.unregister();
            data.put("customTopicPreserved",topic==Bukkit.getHelpMap().getHelpTopic("/lifecycle-factory"));
        }else if(action.equals("opening")){
            traceOpening(data);
        }else if(action.equals("open")||action.equals("instant")){
            Plugin ec=target();Object manager=call(ec,"getCrateManager"),crate=call(manager,"getCrateById","audit_item");
            call(crate,"setOpeningEnabled",action.equals("open"));call(crate,"setOpeningId","csgo");
            ClassLoader loader=ec.getClass().getClassLoader();Class<?> sourceType=loader.loadClass("su.nightexpress.excellentcrates.crate.impl.CrateSource");
            Object source=sourceType.getConstructor(crate.getClass()).newInstance(crate),options=loader.loadClass("su.nightexpress.excellentcrates.crate.impl.OpenOptions").getMethod("empty").invoke(null);
            player.setInvulnerable(true);player.setGameMode(GameMode.SURVIVAL);player.getInventory().clear();player.setItemOnCursor(null);shifts=allowed=openingShifts=openingAllowed=nativeWindowShifts=0;
            data.put("opened",call(manager,"openCrate",player,source,options,null));
            if(action.equals("open"))player.sendPluginMessage(this,"ce:audit","{\"phase\":\"trial\",\"slots\":[9,10,11,12,13,14,15,16,17]}".getBytes(StandardCharsets.UTF_8));
        }else if(action.equals("close")){
            player.sendPluginMessage(this,"ce:audit","{\"phase\":\"idle\"}".getBytes(StandardCharsets.UTF_8));player.closeInventory();
        }else if(action.equals("seed")||action.equals("read")){
            Object user=call(call(target(),"getUserManager"),"getOrFetch",player);
            if(action.equals("seed"))call(user,"setKeys","audit_virtual",7);
            data.put("keys",call(user,"getKeys","audit_virtual"));
        }
        data.put("inventory",inventory());data.put("viewType",player.getOpenInventory().getType().name());data.put("topSize",player.getOpenInventory().getTopInventory().getSize());data.put("shiftEvents",shifts);data.put("allowed",allowed);data.put("openingShifts",openingShifts);data.put("openingAllowed",openingAllowed);data.put("nativeWindowShifts",nativeWindowShifts);
        row(action,tag,data);
    }
    private void traceOpening(Map<String,Object> data)throws Exception{
        Plugin ec=target();data.put("targetPresent",ec!=null);data.put("targetEnabled",ec!=null&&ec.isEnabled());
        if(ec==null)return;
        Object opening=call(call(ec,"getOpeningManager"),"getOpening",player);
        data.put("openingPresent",opening!=null);
        if(opening==null)return;
        data.put("openingClass",opening.getClass().getName());
        data.put("openingIdentity",System.identityHashCode(opening));
        data.put("openingRunning",call(opening,"isRunning"));
        data.put("openingCompleted",call(opening,"isCompleted"));
        data.put("openingTick",call(opening,"getTickCount"));
        data.put("openingViewIdentity",System.identityHashCode(call(opening,"getView")));
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void click(InventoryClickEvent event){
        if(!event.getWhoClicked().getName().equals("CEShiftAudit")||!event.isShiftClick())return;
        shifts++;if(!event.isCancelled())allowed++;
        if(event.getView().getTopInventory().getType()==InventoryType.CRAFTING)nativeWindowShifts++;
        else{openingShifts++;if(!event.isCancelled())openingAllowed++;}
        if(!event.isCancelled()){
            String tag="uncancelled-"+System.nanoTime();
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("rawSlot",event.getRawSlot());data.put("inventoryAction",event.getAction().name());
            data.put("viewIdentity",System.identityHashCode(event.getView()));
            data.put("playerViewIdentity",System.identityHashCode(player.getOpenInventory()));
            data.put("currentItem",event.getCurrentItem()==null?"null":event.getCurrentItem().getType().name());
            data.put("viewType",event.getView().getType().name());
            try{traceOpening(data);}catch(Exception error){data.put("traceError",error.toString());}
            row("uncancelled",tag,data);
            player.getScheduler().run(this,task->row("after-click",tag+"-after",new LinkedHashMap<>(Map.of("inventory",inventory()))),null);
        }
    }
}
