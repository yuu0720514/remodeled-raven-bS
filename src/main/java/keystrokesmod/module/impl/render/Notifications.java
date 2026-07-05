package keystrokesmod.module.impl.render;

import com.google.gson.JsonObject;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.ColorSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.module.setting.impl.TextSetting;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.Theme;
import keystrokesmod.utility.Utils;
import keystrokesmod.utility.font.FontManager;
import keystrokesmod.utility.font.RavenFontRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Notifications extends Module {
    private static final String[] COLOR_MODES = new String[]{"Static", "Gradient", "Rainbow"};
    private static final String[] WAVE_AXES = new String[]{"Vertical", "Horizontal"};
    private static final String[] VERTICAL_WAVE_DIRECTIONS = new String[]{"Down", "Up"};
    private static final String[] HORIZONTAL_WAVE_DIRECTIONS = new String[]{"Left", "Right"};
    private static final String[] FONT_OPTIONS = FontManager.getHudFontOptions();
    private static final long RAINBOW_PERIOD_MS = 7500L;
    private static final double WAVE_ANGLE_SCALE = 0.12;
    private static final int BACKGROUND_COLOR = new Color(0, 0, 0, 105).getRGB();

    private SliderSetting colorMode;
    private ColorSetting notifColor;
    private ColorSetting notifColor2;
    private ButtonSetting useColorCodes;
    private TextSetting notifColorCode;
    private TextSetting notifColorCode2;
    private SliderSetting waveAxis;
    private SliderSetting verticalWaveDirection;
    private SliderSetting horizontalWaveDirection;
    private SliderSetting waveSpeed;
    private SliderSetting waveLength;
    private SliderSetting font;
    private SliderSetting fontSize;
    private ButtonSetting textShadow;
    private ButtonSetting lowercase;
    private ColorSetting stateColor;
    private ButtonSetting drawBackground;
    private ButtonSetting roundedBackground;
    private SliderSetting backgroundRadius;
    private static final float TOP_OFFSET = 2.0f;

    private static final List<Notification> notifications = new ArrayList<>();

    private static final List<String> pendingBatch = new ArrayList<>();
    private static long batchStartMs = -1L;
    private static final long BATCH_WINDOW_MS = 100L;
    private static final int BATCH_THRESHOLD = 3;

    public Notifications() {
        super("Notifications", category.render);
        this.registerSetting(colorMode = new SliderSetting("Color mode", 0, COLOR_MODES));
        this.registerSetting(notifColor = new ColorSetting("Color", 255, 255, 255));
        this.registerSetting(notifColor2 = new ColorSetting("Color 2", 85, 85, 255));
        this.registerSetting(useColorCodes = new ButtonSetting("Use color codes", false));
        this.registerSetting(notifColorCode = createColorCodeSetting("Color code", "f"));
        this.registerSetting(notifColorCode2 = createColorCodeSetting("Color code 2", "9"));
        this.registerSetting(waveAxis = new SliderSetting("Wave axis", 0, WAVE_AXES));
        this.registerSetting(verticalWaveDirection = new SliderSetting("Wave direction", 0, VERTICAL_WAVE_DIRECTIONS));
        this.registerSetting(horizontalWaveDirection = new SliderSetting("Wave direction", 0, HORIZONTAL_WAVE_DIRECTIONS));
        this.registerSetting(waveSpeed = new SliderSetting("Wave speed", 1.0, 0.1, 5.0, 0.1));
        this.registerSetting(waveLength = new SliderSetting("Wave length", 1.0, 0.5, 5.0, 0.1));
        this.registerSetting(font = new SliderSetting("Font", 0, FONT_OPTIONS));
        this.registerSetting(fontSize = new SliderSetting("Scale", 1.0, 0.5, 2.0, 0.1));
        this.registerSetting(textShadow = new ButtonSetting("Text shadow", true));
        this.registerSetting(lowercase = new ButtonSetting("Lowercase", false));
        this.registerSetting(stateColor = new ColorSetting("State color", 170, 170, 170));
        this.registerSetting(drawBackground = new ButtonSetting("Draw background", true));
        this.registerSetting(roundedBackground = new ButtonSetting("Rounded background", false));
        this.registerSetting(backgroundRadius = new SliderSetting("Background radius", 8.0, 0.0, 30.0, 0.5));
    }

    @Override
    public void onEnable() {
        this.guiUpdate();
    }

    @Override
    public void guiUpdate() {
        int mode = colorMode == null ? 0 : (int) colorMode.getInput();
        boolean colorCodeInput = useColorCodes != null && useColorCodes.isToggled();

        if (notifColor != null) {
            notifColor.setVisible((mode == 0 || mode == 1) && !colorCodeInput, this);
        }
        if (notifColor2 != null) {
            notifColor2.setVisible(mode == 1 && !colorCodeInput, this);
        }
        if (useColorCodes != null) {
            useColorCodes.setVisible(mode == 0 || mode == 1, this);
        }
        if (notifColorCode != null) {
            notifColorCode.setVisible((mode == 0 || mode == 1) && colorCodeInput, this);
        }
        if (notifColorCode2 != null) {
            notifColorCode2.setVisible(mode == 1 && colorCodeInput, this);
        }

        boolean showWaveSettings = mode == 1 || mode == 2;
        boolean verticalAxis = waveIsVertical();
        if (waveAxis != null) {
            waveAxis.setVisible(showWaveSettings, this);
        }
        if (verticalWaveDirection != null) {
            verticalWaveDirection.setVisible(showWaveSettings && verticalAxis, this);
        }
        if (horizontalWaveDirection != null) {
            horizontalWaveDirection.setVisible(showWaveSettings && !verticalAxis, this);
        }
        if (waveSpeed != null) {
            waveSpeed.setVisible(showWaveSettings, this);
        }
        if (waveLength != null) {
            waveLength.setVisible(showWaveSettings, this);
        }
        if (backgroundRadius != null) {
            backgroundRadius.setVisible(roundedBackground != null && roundedBackground.isToggled(), this);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (batchStartMs >= 0 && System.currentTimeMillis() - batchStartMs >= BATCH_WINDOW_MS) {
            flushBatch();
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck() || !this.isEnabled()) {
            return;
        }
        if (mc.gameSettings.showDebugInfo || notifications.isEmpty()) {
            return;
        }

        RavenFontRenderer notifFont = getFontRenderer();
        int horizontalPadding = getScaledPixels(2.0f);
        int textTopPadding = getScaledPixels(1.0f);
        int textBottomPadding = getScaledPixels(1.0f);
        int rowHeight = Math.max(1, notifFont.getTextBottomOffset() - notifFont.getTextTopOffset()
                + textTopPadding + textBottomPadding);
        int stateGap = getScaledPixels(2.0f);
        int stateRgb = stateColor == null ? new Color(170, 170, 170).getRGB() : stateColor.getRGB();
        int rowGap = isRoundedBackgroundEnabled() ? getScaledPixels(2.0f) : 0;
        int edgePadding = isRoundedBackgroundEnabled() ? getScaledPixels(2.0f) : 0;
        float y = getTopOffsetPixels();

        Iterator<Notification> iterator = notifications.iterator();

        while (iterator.hasNext()) {
            Notification notification = iterator.next();
            long elapsed = System.currentTimeMillis() - notification.start;
            if (elapsed >= 3000L) {
                iterator.remove();
                continue;
            }

            float animation;
            if (elapsed < 180L) {
                animation = (float) elapsed / 180.0F;
            } else if (elapsed > 2600L) {
                animation = 1.0F - (float) (elapsed - 2600L) / 400.0F;
            } else {
                animation = 1.0F;
            }

            String moduleName;
            String stateText;
            if (notification.text.endsWith(" enabled")) {
                moduleName = notification.text.substring(0, notification.text.length() - " enabled".length());
                stateText = "enabled";
            } else if (notification.text.endsWith(" disabled")) {
                moduleName = notification.text.substring(0, notification.text.length() - " disabled".length());
                stateText = "disabled";
            } else {
                moduleName = notification.text;
                stateText = "";
            }

            if (shouldUseLowercase()) {
                moduleName = moduleName.toLowerCase();
                stateText = stateText.toLowerCase();
            }

            int moduleWidth = notifFont.getStringWidth(moduleName);
            int stateWidth = stateText.isEmpty() ? 0 : notifFont.getStringWidth(stateText);
            int boxWidth = moduleWidth + (stateText.isEmpty() ? 0 : stateGap + stateWidth) + horizontalPadding * 2;
            float x = edgePadding + (float) (-boxWidth) + (float) boxWidth * animation;
            float textY = getTextY(y, notifFont.getTextTopOffset(), textTopPadding);
            int nameColor = getNotificationColor(0.0);

            if (drawBackground != null && drawBackground.isToggled()) {
                drawNotificationBackground(x, y, x + boxWidth, y + rowHeight);
            }

            notifFont.drawString(moduleName, x + horizontalPadding, textY, nameColor, shouldDrawTextShadow());
            if (!stateText.isEmpty()) {
                notifFont.drawString(stateText, x + horizontalPadding + moduleWidth + stateGap, textY, stateRgb, shouldDrawTextShadow());
            }

            y += rowHeight + rowGap;
        }
    }

    private void drawNotificationBackground(float left, float top, float right, float bottom) {
        if (isRoundedBackgroundEnabled()) {
            List<double[]> rows = new ArrayList<>();
            rows.add(new double[]{left, right, top, bottom});
            float radius = backgroundRadius == null ? 8.0f : (float) backgroundRadius.getInput();
            drawSteppedScanlineBackground(rows, radius, BACKGROUND_COLOR);
        } else {
            RenderUtils.drawRect(left, top, right, bottom, BACKGROUND_COLOR);
        }
    }

    private void drawSteppedScanlineBackground(List<double[]> rows, float radius, int color) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        float baseAlpha = (color >> 24 & 255) / 255.0f;
        float red = (color >> 16 & 255) / 255.0f;
        float green = (color >> 8 & 255) / 255.0f;
        float blue = (color & 255) / 255.0f;
        int scale = new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableTexture2D();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer renderer = tessellator.getWorldRenderer();
        renderer.begin(7, DefaultVertexFormats.POSITION_COLOR);

        for (double[] row : rows) {
            double x1 = row[0] * scale;
            double x2 = row[1] * scale;
            double y1 = row[2] * scale;
            double y2 = row[3] * scale;
            double r = Math.max(0.0, Math.min(radius * scale, Math.min(x2 - x1, y2 - y1) / 2.0));
            double tlCX = x1 + r;
            double tlCY = y1 + r;
            double trCX = x2 - r;
            double trCY = y1 + r;
            double blCX = x1 + r;
            double blCY = y2 - r;
            double brCX = x2 - r;
            double brCY = y2 - r;
            int yStart = (int) Math.floor(y1);
            int yEnd = (int) Math.ceil(y2);
            int xStart = (int) Math.floor(x1);
            int xEnd = (int) Math.ceil(x2);

            for (int py = yStart; py < yEnd; ++py) {
                double rowTop = Math.max(py, y1);
                double rowBottom = Math.min(py + 1.0, y2);
                if (rowBottom <= rowTop) {
                    continue;
                }

                boolean inTopCorner = r > 0.5 && rowTop < y1 + r;
                boolean inBotCorner = r > 0.5 && rowBottom > y2 - r;
                double invS = 1.0 / scale;
                if (!inTopCorner && !inBotCorner) {
                    emitRoundedQuad(renderer, x1 * invS, rowTop * invS, x2 * invS, rowBottom * invS,
                            red, green, blue, baseAlpha * (float) (rowBottom - rowTop));
                } else {
                    for (int px = xStart; px < xEnd; ++px) {
                        double cov = screenPixelCoverage(px, py, rowTop, rowBottom, x1, y1, x2, y2, r,
                                tlCX, tlCY, trCX, trCY, blCX, blCY, brCX, brCY);
                        if (cov > 0.0) {
                            emitRoundedQuad(renderer, px * invS, rowTop * invS, (px + 1.0) * invS, rowBottom * invS,
                                    red, green, blue, baseAlpha * (float) cov);
                        }
                    }
                }
            }
        }

        tessellator.draw();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }

    private static double screenPixelCoverage(int px, int py, double rowTop, double rowBottom,
                                              double x1, double y1, double x2, double y2, double r,
                                              double tlCX, double tlCY, double trCX, double trCY,
                                              double blCX, double blCY, double brCX, double brCY) {
        int inside = 0;

        for (int sy = 0; sy < 16; ++sy) {
            double sampleY = rowTop + (rowBottom - rowTop) * (sy + 0.5) / 16.0;

            for (int sx = 0; sx < 16; ++sx) {
                double sampleX = px + (sx + 0.5) / 16.0;
                if (sampleX < x1 || sampleX > x2 || sampleY < y1 || sampleY > y2) {
                    continue;
                }

                boolean inShape = true;
                if (r > 0.5 && sampleX < tlCX && sampleY < tlCY) {
                    double dx = sampleX - tlCX;
                    double dy = sampleY - tlCY;
                    if (dx * dx + dy * dy > r * r) {
                        inShape = false;
                    }
                }
                if (inShape && r > 0.5 && sampleX > trCX && sampleY < trCY) {
                    double dx = sampleX - trCX;
                    double dy = sampleY - trCY;
                    if (dx * dx + dy * dy > r * r) {
                        inShape = false;
                    }
                }
                if (inShape && r > 0.5 && sampleX < blCX && sampleY > blCY) {
                    double dx = sampleX - blCX;
                    double dy = sampleY - blCY;
                    if (dx * dx + dy * dy > r * r) {
                        inShape = false;
                    }
                }
                if (inShape && r > 0.5 && sampleX > brCX && sampleY > brCY) {
                    double dx = sampleX - brCX;
                    double dy = sampleY - brCY;
                    if (dx * dx + dy * dy > r * r) {
                        inShape = false;
                    }
                }
                if (inShape) {
                    ++inside;
                }
            }
        }

        return inside / 256.0;
    }

    private static void emitRoundedQuad(WorldRenderer renderer, double x1, double y1, double x2, double y2,
                                        float red, float green, float blue, float alpha) {
        if (alpha <= 0.0f || x2 <= x1 || y2 <= y1) {
            return;
        }
        renderer.pos(x1, y2, 0.0D).color(red, green, blue, alpha).endVertex();
        renderer.pos(x2, y2, 0.0D).color(red, green, blue, alpha).endVertex();
        renderer.pos(x2, y1, 0.0D).color(red, green, blue, alpha).endVertex();
        renderer.pos(x1, y1, 0.0D).color(red, green, blue, alpha).endVertex();
    }

    private boolean isRoundedBackgroundEnabled() {
        return roundedBackground != null && roundedBackground.isToggled()
                && backgroundRadius != null && backgroundRadius.getInput() > 0.0;
    }

    private float getTopOffsetPixels() {
        return getScaledPixels(TOP_OFFSET);
    }

    public static void addNotification(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (ModuleManager.notifications == null || !ModuleManager.notifications.isEnabled()) {
            return;
        }
        pendingBatch.add(text);
        if (batchStartMs < 0) {
            batchStartMs = System.currentTimeMillis();
        }
    }

    private static void flushBatch() {
        if (pendingBatch.isEmpty()) {
            batchStartMs = -1L;
            return;
        }

        List<String> enabledList  = new ArrayList<>();
        List<String> disabledList = new ArrayList<>();
        List<String> otherList    = new ArrayList<>();

        for (String t : pendingBatch) {
            if (t.endsWith(" enabled"))       enabledList.add(t);
            else if (t.endsWith(" disabled")) disabledList.add(t);
            else                              otherList.add(t);
        }
        pendingBatch.clear();
        batchStartMs = -1L;

        if (enabledList.size() >= BATCH_THRESHOLD) {
            notifications.add(new Notification(enabledList.size() + " modules enabled"));
        } else {
            for (String t : enabledList) notifications.add(new Notification(t));
        }

        if (disabledList.size() >= BATCH_THRESHOLD) {
            notifications.add(new Notification(disabledList.size() + " modules disabled"));
        } else {
            for (String t : disabledList) notifications.add(new Notification(t));
        }

        for (String t : otherList) notifications.add(new Notification(t));
    }

    private RavenFontRenderer getFontRenderer() {
        return FontManager.getHudRenderer(getSelectedFontName(), getSelectedFontScale());
    }

    private String getSelectedFontName() {
        if (font == null) {
            return FONT_OPTIONS[0];
        }
        int index = (int) Math.max(0, Math.min(font.getOptions().length - 1, font.getInput()));
        return font.getOptions()[index];
    }

    private float getSelectedFontScale() {
        return fontSize == null ? 1.0f : (float) fontSize.getInput();
    }

    private int getScaledPixels(float basePixels) {
        return Math.max(1, Math.round(basePixels * getSelectedFontScale()));
    }

    private float getTextY(float rowTop, int textTopOffset, int textTopPadding) {
        return rowTop + textTopPadding - textTopOffset;
    }

    private boolean shouldDrawTextShadow() {
        return textShadow == null || textShadow.isToggled();
    }

    private boolean shouldUseLowercase() {
        return lowercase != null && lowercase.isToggled();
    }

    private int getNotificationColor(double gradientOffset) {
        if (colorMode == null || notifColor == null) {
            return 0xFFFFFF;
        }
        int mode = (int) colorMode.getInput();
        if (mode == 2) {
            return getRainbowWaveColor(gradientOffset);
        }
        if (mode == 1 && notifColor2 != null) {
            Color c1 = getPrimaryColor();
            Color c2 = getSecondaryColor();
            return getGradientWaveColor(c1, c2, gradientOffset);
        }
        return getPrimaryColor().getRGB();
    }

    private Color getPrimaryColor() {
        if (useColorCodes != null && useColorCodes.isToggled()) {
            return new Color(getColorCodeRgb(notifColorCode, notifColor));
        }
        return new Color(notifColor.getRed(), notifColor.getGreen(), notifColor.getBlue());
    }

    private Color getSecondaryColor() {
        if (useColorCodes != null && useColorCodes.isToggled()) {
            return new Color(getColorCodeRgb(notifColorCode2, notifColor2));
        }
        return notifColor2 == null ? getPrimaryColor() : new Color(notifColor2.getRed(), notifColor2.getGreen(), notifColor2.getBlue());
    }

    private int getColorCodeRgb(TextSetting colorCodeSetting, ColorSetting fallback) {
        char code = parseColorCodeChar(colorCodeSetting == null ? null : colorCodeSetting.getText());
        return code == 0 && fallback != null ? fallback.getRGB() : getMinecraftColorRgb(code == 0 ? 'f' : code);
    }

    private static char parseColorCodeChar(String input) {
        if (input == null) {
            return '\0';
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return '\0';
        }
        if (trimmed.length() >= 2 && (trimmed.charAt(0) == '&' || trimmed.charAt(0) == '\u00a7')) {
            char code = trimmed.charAt(1);
            return "0123456789abcdef".indexOf(Character.toLowerCase(code)) >= 0 ? Character.toLowerCase(code) : '\0';
        }
        char code = trimmed.charAt(0);
        return "0123456789abcdef".indexOf(Character.toLowerCase(code)) >= 0 ? Character.toLowerCase(code) : '\0';
    }

    private static int getMinecraftColorRgb(char code) {
        int index = "0123456789abcdef".indexOf(Character.toLowerCase(code));
        if (index < 0) {
            return 0xFFFFFF;
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

    private TextSetting createColorCodeSetting(String name, String defaultCode) {
        return new TextSetting(name, defaultCode, "&c / c", 2) {
            @Override
            public void loadProfile(JsonObject data) {
                if (data != null && data.has(this.getProfileKey()) && data.get(this.getProfileKey()).isJsonPrimitive()) {
                    String value = data.getAsJsonPrimitive(this.getProfileKey()).getAsString();
                    if (Notifications.parseColorCodeChar(value) != 0) {
                        this.setText(value);
                    }
                }
            }
        };
    }

    private int getGradientWaveColor(Color c1, Color c2, double gradientOffset) {
        double animationProgress = (Math.sin(getAnimatedWaveAngle(gradientOffset)) + 1.0) * 0.5;
        return Theme.convert(c1, c2, animationProgress).getRGB();
    }

    private int getRainbowWaveColor(double gradientOffset) {
        double hue = getAnimatedWaveAngle(gradientOffset) / (Math.PI * 2.0);
        hue -= Math.floor(hue);
        return Color.getHSBColor((float) hue, 1.0F, 1.0F).getRGB();
    }

    private double getAnimatedWaveAngle(double gradientOffset) {
        return System.currentTimeMillis() / (double) RAINBOW_PERIOD_MS * (Math.PI * 2.0) * getWaveSpeedMultiplier()
                + gradientOffset * WAVE_ANGLE_SCALE;
    }

    private double getWaveSpeedMultiplier() {
        return waveSpeed == null ? 1.0 : Math.max(0.1, waveSpeed.getInput());
    }

    private double getWaveLengthMultiplier() {
        return waveLength == null ? 1.0 : Math.max(0.5, waveLength.getInput());
    }

    private boolean waveIsVertical() {
        return waveAxis == null || (int) waveAxis.getInput() == 0;
    }

    private static final class Notification {
        private final String text;
        private final long start;

        private Notification(String text) {
            this.text = text;
            this.start = System.currentTimeMillis();
        }
    }
}
