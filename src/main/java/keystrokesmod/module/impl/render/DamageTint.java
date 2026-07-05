package keystrokesmod.module.impl.render;

import com.google.gson.JsonObject;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.ColorSetting;
import keystrokesmod.module.setting.impl.TextSetting;
import net.minecraft.entity.EntityLivingBase;

public class DamageTint
extends Module {
    public static DamageTint instance;
    private static final String MINECRAFT_COLOR_CODES = "0123456789abcdef";
    public final ColorSetting color = new ColorSetting("Tint color", 255, 0, 0, 76);
    public final ButtonSetting useColorCodes;
    public final TextSetting tintColorCode;
    public final ButtonSetting fade;

    public DamageTint() {
        super("Damage Tint", Module.category.render, 0);
        this.registerSetting(this.color);
        this.useColorCodes = new ButtonSetting("Use color codes", false);
        this.registerSetting(this.useColorCodes);
        this.tintColorCode = DamageTint.createColorCodeSetting("Color code", "c");
        this.registerSetting(this.tintColorCode);
        this.fade = new ButtonSetting("Fade out", false);
        this.registerSetting(this.fade);
    }

    @Override
    public void guiUpdate() {
        boolean colorCodeInput = this.useColorCodes.isToggled();
        this.color.setVisible(!colorCodeInput, this);
        this.tintColorCode.setVisible(colorCodeInput, this);
    }

    @Override
    public void onEnable() {
        instance = this;
        this.guiUpdate();
    }

    @Override
    public void onDisable() {
        instance = null;
    }

    public float getTintRed() {
        return (float)(this.getTintRgb() >> 16 & 0xFF) / 255.0f;
    }

    public float getTintGreen() {
        return (float)(this.getTintRgb() >> 8 & 0xFF) / 255.0f;
    }

    public float getTintBlue() {
        return (float)(this.getTintRgb() & 0xFF) / 255.0f;
    }

    public static float computeAlpha(EntityLivingBase entity) {
        if (instance == null) {
            return 1.0f;
        }
        float baseAlpha = (float)DamageTint.instance.color.getAlpha() / 255.0f;
        if (!DamageTint.instance.fade.isToggled()) {
            return baseAlpha;
        }
        float maxHurt = entity.maxHurtTime;
        if (maxHurt <= 0.0f) {
            return baseAlpha;
        }
        float percent = 1.0f - (float)entity.hurtTime / maxHurt;
        percent = percent < 0.5f ? percent / 0.5f : (1.0f - percent) / 0.5f;
        return baseAlpha * percent;
    }

    private int getTintRgb() {
        if (this.useColorCodes.isToggled()) {
            return DamageTint.getColorCodeRgb(this.tintColorCode.getText());
        }
        return this.color.getRGB();
    }

    private static int getColorCodeRgb(String input) {
        char code = DamageTint.parseColorCodeChar(input);
        if (code == '\u0000') {
            code = 'c';
        }
        return DamageTint.getMinecraftColorRgb(code);
    }

    private static char parseColorCodeChar(String input) {
        if (input == null) {
            return '\u0000';
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return '\u0000';
        }
        if (trimmed.length() >= 2 && (trimmed.charAt(0) == '&' || trimmed.charAt(0) == '\u00a7')) {
            char code = trimmed.charAt(1);
            if (MINECRAFT_COLOR_CODES.indexOf(Character.toLowerCase(code)) >= 0) {
                return Character.toLowerCase(code);
            }
            return '\u0000';
        }
        char code = trimmed.charAt(0);
        if (MINECRAFT_COLOR_CODES.indexOf(Character.toLowerCase(code)) >= 0) {
            return Character.toLowerCase(code);
        }
        return '\u0000';
    }

    private static int getMinecraftColorRgb(char code) {
        if (mc != null && DamageTint.mc.fontRendererObj != null) {
            return DamageTint.mc.fontRendererObj.getColorCode(code);
        }
        int index = MINECRAFT_COLOR_CODES.indexOf(Character.toLowerCase(code));
        if (index < 0) {
            return 0xFF0000;
        }
        int offset = (index >> 3 & 1) * 85;
        int red = (index >> 2 & 1) * 170 + offset;
        int green = (index >> 1 & 1) * 170 + offset;
        int blue = (index & 1) * 170 + offset;
        if (index == 6) {
            red += 85;
        }
        return red << 16 | green << 8 | blue;
    }

    private static TextSetting createColorCodeSetting(String name, String defaultCode) {
        return new TextSetting(name, defaultCode, "&c / c", 2){

            @Override
            public void loadProfile(JsonObject data) {
                if (data == null || !data.has(this.getProfileKey()) || !data.get(this.getProfileKey()).isJsonPrimitive()) {
                    return;
                }
                String value = data.getAsJsonPrimitive(this.getProfileKey()).getAsString();
                if (DamageTint.parseColorCodeChar(value) != '\u0000') {
                    this.setText(value);
                }
            }
        };
    }
}

