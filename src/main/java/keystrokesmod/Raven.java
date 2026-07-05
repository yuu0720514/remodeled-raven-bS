package keystrokesmod;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import keystrokesmod.clickgui.ClickGui;
import keystrokesmod.command.CommandManager;
import keystrokesmod.event.PostProfileLoadEvent;
import keystrokesmod.event.PostSetSliderEvent;
import keystrokesmod.helper.DebugHelper;
import keystrokesmod.helper.MouseHelper;
import keystrokesmod.helper.PingHelper;
import keystrokesmod.helper.RotationHelper;
import keystrokesmod.keystroke.KeyStrokeCommand;
import keystrokesmod.keystroke.KeyStrokeConfigGui;
import keystrokesmod.keystroke.KeyStrokeRenderer;
import keystrokesmod.lag.handler.UnifiedLagHandler;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.client.Gui;
import keystrokesmod.module.impl.movement.Timer;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.script.ScriptDefaults;
import keystrokesmod.script.ScriptManager;
import keystrokesmod.script.model.Entity;
import keystrokesmod.script.model.NetworkPlayer;
import keystrokesmod.utility.BlockHighlightSharedHandler;
import keystrokesmod.utility.FrozenEntitySync;
import keystrokesmod.utility.ModuleUtils;
import keystrokesmod.utility.PacketsHandler;
import keystrokesmod.utility.PlayerRelationsManager;
import keystrokesmod.utility.ReflectionUtils;
import keystrokesmod.utility.Utils;
import keystrokesmod.utility.profile.Profile;
import keystrokesmod.utility.profile.ProfileManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.command.ICommand;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

@Mod(modid="keystrokes", name="KeystrokesMod", version="KMV5", acceptedMinecraftVersions="[1.8.9]")
public class Raven {
    public static boolean DEBUG = false;
    public static Minecraft mc = Minecraft.getMinecraft();
    private static KeyStrokeRenderer keyStrokeRenderer;
    private static boolean isKeyStrokeConfigGuiToggled;
    private static final ScheduledExecutorService scheduledExecutor;
    private static final ExecutorService cachedExecutor;
    public static ModuleManager moduleManager;
    public static ClickGui clickGui;
    public static ProfileManager profileManager;
    public static ScriptManager scriptManager;
    public static CommandManager commandManager;
    public static PlayerRelationsManager playerRelationsManager;
    public static Profile currentProfile;
    public static PacketsHandler packetsHandler;
    public static UnifiedLagHandler lagHandler;
    public static boolean authenticated;
    private static boolean firstLoad;

    public Raven() {
        moduleManager = new ModuleManager();
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        authenticated = true;
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        Runtime.getRuntime().addShutdownHook(new Thread(scheduledExecutor::shutdown));
        Runtime.getRuntime().addShutdownHook(new Thread(cachedExecutor::shutdown));
        ClientCommandHandler.instance.registerCommand((ICommand)new KeyStrokeCommand());
        MinecraftForge.EVENT_BUS.register((Object)this);
        MinecraftForge.EVENT_BUS.register((Object)new DebugHelper());
        MinecraftForge.EVENT_BUS.register((Object)new MouseHelper());
        MinecraftForge.EVENT_BUS.register((Object)RotationHelper.get());
        MinecraftForge.EVENT_BUS.register((Object)new KeyStrokeRenderer());
        MinecraftForge.EVENT_BUS.register((Object)new PingHelper());
        packetsHandler = new PacketsHandler();
        MinecraftForge.EVENT_BUS.register((Object)packetsHandler);
        MinecraftForge.EVENT_BUS.register((Object)new ModuleUtils());
        lagHandler = new UnifiedLagHandler();
        MinecraftForge.EVENT_BUS.register((Object)lagHandler);
        ReflectionUtils.setupFields();
        playerRelationsManager = new PlayerRelationsManager();
        playerRelationsManager.load();
        moduleManager.register();
        MinecraftForge.EVENT_BUS.register((Object)new BlockHighlightSharedHandler());
        scriptManager = new ScriptManager();
        keyStrokeRenderer = new KeyStrokeRenderer();
        clickGui = new ClickGui();
        profileManager = new ProfileManager();
        ScriptDefaults.reloadModules();
        scriptManager.loadScripts();
        profileManager.loadProfiles();
        ReflectionUtils.setKeyBindings();
        commandManager = new CommandManager();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        block25: {
            block24: {
                if (e.phase != TickEvent.Phase.END) break block24;
                if (Utils.nullCheck()) {
                    if (Raven.mc.thePlayer.ticksExisted % 6000 == 0) {
                        Entity.clearCache();
                        NetworkPlayer.clearCache();
                        if (DebugHelper.BACKGROUND) {
                            Utils.sendMessage("&aticks % 6000 == 0 &7reached, clearing script caches. (&dEntity&7, &dNetworkPlayer&7)");
                        }
                    }
                    if (ReflectionUtils.ERROR) {
                        Utils.sendMessage("&cThere was an error, relaunch the game.");
                        ReflectionUtils.ERROR = false;
                    }
                    MouseHelper.updateWheelCache();
                    boolean frozenKeybinds = Raven.shouldUseFrozenKeybinds();
                    for (Module module : Raven.getModuleManager().getModules()) {
                        if (module == ModuleManager.timer) {
                            Raven.handleTimerKeybind(frozenKeybinds);
                            if (!module.isEnabled()) continue;
                            module.onUpdate();
                            continue;
                        }
                        if (Raven.mc.currentScreen == null && module.canBeEnabled()) {
                            if (!frozenKeybinds && !Raven.shouldSkipKeybindForGuiOpen(module)) {
                                module.onKeyBind();
                            }
                        } else if (Raven.mc.currentScreen instanceof ClickGui) {
                            if (!frozenKeybinds) {
                                module.guiUpdate();
                            }
                            module.syncKeyBindState();
                        } else {
                            module.syncKeyBindState();
                        }
                        if (!module.isEnabled()) continue;
                        module.onUpdate();
                    }
                    if (Raven.mc.currentScreen == null) {
                        for (Module module : Raven.scriptManager.scripts.values()) {
                            if (frozenKeybinds) continue;
                            module.onKeyBind();
                        }
                    } else {
                        for (Module module : Raven.scriptManager.scripts.values()) {
                            module.syncKeyBindState();
                        }
                        if (Raven.mc.currentScreen instanceof ClickGui) {
                            if (this.applyKillAuraRangeConstraints()) {
                                clickGui.onSliderChange();
                            }
                            if (Raven.mc.thePlayer.getHealth() <= 0.0f) {
                                mc.displayGuiScreen(null);
                            }
                        }
                    }
                }
                if (!isKeyStrokeConfigGuiToggled) break block25;
                isKeyStrokeConfigGuiToggled = false;
                mc.displayGuiScreen((GuiScreen)new KeyStrokeConfigGui());
                break block25;
            }
            MouseHelper.clearWheelCache();
            if (Raven.mc.currentScreen == null && Utils.nullCheck()) {
                for (Profile profile : Raven.profileManager.profiles) {
                    profile.getModule().onKeyBind();
                }
            } else if (Utils.nullCheck()) {
                for (Profile profile : Raven.profileManager.profiles) {
                    profile.getModule().syncKeyBindState();
                }
            }
        }
    }

    @SubscribeEvent
    public void onPostProfileLoad(PostProfileLoadEvent e) {
        this.applyKillAuraRangeConstraints();
        clickGui.onSliderChange();
    }

    @SubscribeEvent
    public void onPostSetSlider(PostSetSliderEvent e) {
        this.applyKillAuraRangeConstraints();
        clickGui.onSliderChange();
    }

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent e) {
        if (e.entity == Raven.mc.thePlayer) {
            if (!firstLoad) {
                firstLoad = true;
                scriptManager.loadScripts();
            }
            Entity.clearCache();
            NetworkPlayer.clearCache();
            FrozenEntitySync.get().clearAll();
            if (DebugHelper.BACKGROUND) {
                Utils.sendMessage("&enew world&7, clearing script caches. (&dEntity&7, &dNetworkPlayer&7)");
            }
        }
    }

    public static ModuleManager getModuleManager() {
        return moduleManager;
    }

    public static ScheduledExecutorService getScheduledExecutor() {
        return scheduledExecutor;
    }

    public static ExecutorService getCachedExecutor() {
        return cachedExecutor;
    }

    public static KeyStrokeRenderer getKeyStrokeRenderer() {
        return keyStrokeRenderer;
    }

    public static void toggleKeyStrokeConfigGui() {
        isKeyStrokeConfigGuiToggled = true;
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !Utils.nullCheck()) {
            return;
        }
        if (!Raven.shouldUseFrozenKeybinds()) {
            return;
        }
        Raven.handleFrozenKeybinds();
    }

    private static void handleTimerKeybind(boolean frozenKeybinds) {
        Timer timer = ModuleManager.timer;
        if (timer == null) {
            return;
        }
        if (frozenKeybinds) {
            if (timer.shouldBlockKeybindToggle()) {
                timer.syncKeyBindState();
            }
            return;
        }
        if (timer.shouldBlockKeybindToggle()) {
            timer.syncKeyBindState();
            return;
        }
        if (timer.canBeEnabled()) {
            timer.onKeyBind();
        } else {
            timer.syncKeyBindState();
        }
    }

    private static boolean shouldBlockTimerKeybind() {
        Timer timer = ModuleManager.timer;
        return timer != null && timer.shouldBlockKeybindToggle();
    }

    private static boolean shouldUseFrozenKeybinds() {
        Timer timer = ModuleManager.timer;
        if (timer == null || !timer.isEnabled()) {
            return false;
        }
        if (Raven.mc.currentScreen instanceof ClickGui || Raven.mc.currentScreen instanceof KeyStrokeConfigGui) {
            return false;
        }
        return timer.getConfiguredSpeed() != 1.0f;
    }

    public static boolean isRavenUiActive() {
        if (Raven.mc.currentScreen instanceof ClickGui || Raven.mc.currentScreen instanceof KeyStrokeConfigGui) {
            return true;
        }
        if (ClickGui.shouldGuardModuleToggle()) {
            return true;
        }
        return Raven.isGuiOpenKeyDown();
    }

    private static void handleTimerKeybindInFrozenPath() {
        Timer timer = ModuleManager.timer;
        if (timer == null || !timer.canBeEnabled()) {
            return;
        }
        if (Raven.shouldBlockTimerKeybind()) {
            timer.syncKeyBindState();
            return;
        }
        if (timer.canBeEnabled()) {
            timer.onKeyBind();
        } else {
            timer.syncKeyBindState();
        }
    }

    public static void handleFrozenKeybinds() {
        if (!Utils.nullCheck()) {
            return;
        }
        MouseHelper.updateWheelCache();
        if (Raven.mc.currentScreen == null) {
            if (Raven.isGuiOpenKeyDown()) {
                Timer timer;
                Module guiModule = ModuleManager.getModule(Gui.class);
                if (guiModule != null && guiModule.canBeEnabled()) {
                    guiModule.onKeyBind();
                }
                if ((timer = ModuleManager.timer) != null) {
                    timer.syncKeyBindState();
                }
            } else if (!ClickGui.shouldGuardModuleToggle()) {
                for (Module module : Raven.getModuleManager().getModules()) {
                    if (module == ModuleManager.timer || !module.canBeEnabled() || Raven.shouldSkipKeybindForGuiOpen(module)) continue;
                    module.onKeyBind();
                }
                Raven.handleTimerKeybindInFrozenPath();
            } else {
                Timer timer = ModuleManager.timer;
                if (timer != null) {
                    timer.syncKeyBindState();
                }
            }
            if (!Raven.isGuiOpenKeyDown()) {
                for (Module module : Raven.scriptManager.scripts.values()) {
                    module.onKeyBind();
                }
            }
        } else if (Raven.mc.currentScreen instanceof ClickGui) {
            for (Module module : Raven.getModuleManager().getModules()) {
                if (module == ModuleManager.timer) {
                    module.syncKeyBindState();
                    continue;
                }
                module.guiUpdate();
                module.syncKeyBindState();
            }
            for (Module module : Raven.scriptManager.scripts.values()) {
                module.syncKeyBindState();
            }
        } else {
            for (Module module : Raven.getModuleManager().getModules()) {
                module.syncKeyBindState();
            }
            for (Module module : Raven.scriptManager.scripts.values()) {
                module.syncKeyBindState();
            }
        }
        if (isKeyStrokeConfigGuiToggled) {
            isKeyStrokeConfigGuiToggled = false;
            mc.displayGuiScreen((GuiScreen)new KeyStrokeConfigGui());
        }
    }

    public static boolean isModuleBindDown(int keycode) {
        if (keycode == 0) {
            return false;
        }
        try {
            if (keycode >= 1000) {
                if (keycode == 1069 || keycode == 1070) {
                    return MouseHelper.isScrollDown(keycode);
                }
                return Mouse.isButtonDown((int)(keycode - 1000));
            }
            return Keyboard.isKeyDown((int)keycode);
        }
        catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isGuiOpenKeyDown() {
        Module guiModule = ModuleManager.getModule(Gui.class);
        return guiModule != null && Raven.isModuleBindDown(guiModule.getKeycode());
    }

    private static boolean shouldSkipKeybindForGuiOpen(Module module) {
        if (module instanceof Gui) {
            return false;
        }
        if (Raven.isGuiOpenKeyDown()) {
            return true;
        }
        Module guiModule = ModuleManager.getModule(Gui.class);
        if (guiModule == null) {
            return false;
        }
        int guiKey = guiModule.getKeycode();
        return guiKey != 0 && module.getKeycode() == guiKey;
    }

    private boolean applyKillAuraRangeConstraints() {
        if (ModuleManager.killAura == null) {
            return false;
        }
        SliderSetting attackRange = ModuleManager.killAura.getAttackRangeSetting();
        SliderSetting swingRange = ModuleManager.killAura.getSwingRangeSetting();
        SliderSetting aimRange = ModuleManager.killAura.getAimRangeSetting();
        if (attackRange == null || swingRange == null || aimRange == null) {
            return false;
        }
        boolean changed = false;
        double attack = attackRange.getInput();
        double swing = swingRange.getInput();
        double aim = aimRange.getInput();
        if (swing < attack) {
            swingRange.setValue(attack);
            swing = swingRange.getInput();
            changed = true;
        }
        if (aim < swing) {
            aimRange.setValue(swing);
            changed = true;
        }
        return changed;
    }

    static {
        scheduledExecutor = Executors.newScheduledThreadPool(2);
        cachedExecutor = Executors.newCachedThreadPool();
        authenticated = true;
    }
}

