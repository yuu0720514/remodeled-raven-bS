package keystrokesmod.module.impl.client;

import keystrokesmod.Raven;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.Utils;
import keystrokesmod.utility.font.FontManager;
import keystrokesmod.utility.font.RavenFontRenderer;
import net.minecraft.client.gui.GuiScreen;

public class Gui
extends Module {
    private static final String[] GUI_FONT_OPTIONS = FontManager.getHudFontOptions();
    public static SliderSetting guiScale;
    public static SliderSetting font;
    public static SliderSetting backgroundBlur;
    public static SliderSetting scrollSpeed;
    public static ButtonSetting removePlayerModel;
    public static ButtonSetting darkBackground;
    public static ButtonSetting removeWatermark;
    public static ButtonSetting rainBowOutlines;
    public static ButtonSetting slinkyStyle;

    public Gui() {
        super("Gui", Module.category.client, 54);
        guiScale = new SliderSetting("Gui scale", "x", 1.0, 0.5, 2.0, 0.01);
        this.registerSetting(guiScale);
        font = new SliderSetting("Font", 0, GUI_FONT_OPTIONS);
        this.registerSetting(font);
        backgroundBlur = new SliderSetting("Background blur", "%", 0.0, 0.0, 100.0, 1.0);
        this.registerSetting(backgroundBlur);
        scrollSpeed = new SliderSetting("Scroll speed", 20.0, 2.0, 90.0, 1.0);
        this.registerSetting(scrollSpeed);
        darkBackground = new ButtonSetting("Dark background", true);
        this.registerSetting(darkBackground);
        rainBowOutlines = new ButtonSetting("Rainbow outlines", true);
        this.registerSetting(rainBowOutlines);
        removePlayerModel = new ButtonSetting("Remove player model", false);
        this.registerSetting(removePlayerModel);
        removeWatermark = new ButtonSetting("Remove watermark", false);
        this.registerSetting(removeWatermark);
        slinkyStyle = new ButtonSetting("Slinky style", false);
        this.registerSetting(slinkyStyle);
    }

    @Override
    public void onEnable() {
        if (Utils.nullCheck() && Gui.mc.currentScreen != Raven.clickGui) {
            Raven.clickGui.markModuleToggleGuard();
            mc.displayGuiScreen((GuiScreen)Raven.clickGui);
            Raven.clickGui.initMain();
        }
        this.disable();
    }

    public static String getSelectedFontName() {
        if (font == null) {
            return GUI_FONT_OPTIONS[0];
        }
        int index = (int)Math.max(0.0, Math.min((double)(font.getOptions().length - 1), font.getInput()));
        return font.getOptions()[index];
    }

    public static boolean isSlinkyStyle() {
        return slinkyStyle != null && slinkyStyle.isToggled();
    }

    public static RavenFontRenderer getClickGuiHeaderFontRenderer() {
        return FontManager.getClickGuiHeaderRenderer(Gui.getSelectedFontName());
    }

    public static RavenFontRenderer getClickGuiSettingFontRenderer() {
        return FontManager.getClickGuiSettingRenderer(Gui.getSelectedFontName());
    }

    public static float getClickGuiScale() {
        if (guiScale == null) {
            return 1.0f;
        }
        return (float)Math.max(0.5, Math.min(2.0, guiScale.getInput()));
    }
}

