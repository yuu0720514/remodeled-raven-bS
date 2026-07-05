package keystrokesmod.module.impl.render;

import com.google.gson.JsonObject;
import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.Module.category;
import keystrokesmod.module.impl.combat.AntiKnockback;
import keystrokesmod.module.impl.combat.Velocity;
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
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.fml.client.config.GuiButtonExt;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;

public class HUD extends Module {
    private static final String[] COLOR_MODES = new String[]{"Static", "Gradient", "Rainbow"};
    private static final String[] WAVE_AXES = new String[]{"Vertical", "Horizontal"};
    private static final String[] VERTICAL_WAVE_DIRECTIONS = new String[]{"Down", "Up"};
    private static final String[] HORIZONTAL_WAVE_DIRECTIONS = new String[]{"Left", "Right"};
    private static final double HUD_WAVE_HORIZONTAL_X_SCALE = 0.35;
    private static final long HUD_RAINBOW_PERIOD_MS = 7500L;
    private static final double HUD_WAVE_ANGLE_SCALE = 0.12;
    private static final String MINECRAFT_COLOR_CODES = "0123456789abcdef";
    public static SliderSetting colorMode;
    public static ColorSetting hudColor;
    public static ColorSetting hudColor2;
    public static ButtonSetting useColorCodes;
    public static TextSetting hudColorCode;
    public static TextSetting hudColorCode2;
    public static SliderSetting waveAxis;
    public static SliderSetting verticalWaveDirection;
    public static SliderSetting horizontalWaveDirection;
    public static SliderSetting waveSpeed;
    public static SliderSetting waveLength;
    public static SliderSetting font;
    public static SliderSetting fontSize;
    public static SliderSetting backgroundRadius;
    public static SliderSetting animationSpeed;
    private static SliderSetting outline;
    public static ButtonSetting alphabeticalSort;
    private static ButtonSetting drawBackground;
    private static ButtonSetting roundedBackground;
    private static ButtonSetting textShadow;
    private static ButtonSetting alignRight;
    private static ButtonSetting lowercase;
    public static ButtonSetting showInfo;
    public static SliderSetting connectThreshold;
    private static final float DEFAULT_POS_X = 5.0F;
    private static final float DEFAULT_POS_Y = 70.0F;
    public static float posX = 5.0F;
    public static float posY = 70.0F;
    private static float relativePosX = Float.NaN;
    private static float relativePosY = Float.NaN;
    private static final String[] OUTLINE_MODES = new String[]{"None", "Full", "Side"};
    private static final String[] HUD_FONT_OPTIONS = FontManager.getHudFontOptions();
    private static final int BACKGROUND_COLOR = (new Color(0, 0, 0, 80)).getRGB();
    private boolean isAlphabeticalSort;
    private boolean canShowInfo;
    private String lastHudFontName = "";
    private float lastHudFontScale = -1.0F;

    public HUD() {
        super("HUD", category.render);
        this.registerSetting(colorMode = new SliderSetting("Color mode", 0, COLOR_MODES));
        this.registerSetting(hudColor = new ColorSetting("Color", 255, 255, 255));
        this.registerSetting(hudColor2 = new ColorSetting("Color 2", 85, 85, 255));
        this.registerSetting(useColorCodes = new ButtonSetting("Use color codes", false));
        this.registerSetting(hudColorCode = createColorCodeSetting("Color code", "f"));
        this.registerSetting(hudColorCode2 = createColorCodeSetting("Color code 2", "9"));
        this.registerSetting(waveAxis = new SliderSetting("Wave axis", 0, WAVE_AXES));
        this.registerSetting(verticalWaveDirection = new SliderSetting("Wave direction", 0, VERTICAL_WAVE_DIRECTIONS));
        this.registerSetting(horizontalWaveDirection = new SliderSetting("Wave direction", 0, HORIZONTAL_WAVE_DIRECTIONS));
        this.registerSetting(waveSpeed = new SliderSetting("Wave speed", (double)1.0F, 0.1, (double)5.0F, 0.1));
        this.registerSetting(waveLength = new SliderSetting("Wave length", (double)1.0F, (double)0.5F, (double)5.0F, 0.1));
        this.registerSetting(font = new SliderSetting("Font", 0, HUD_FONT_OPTIONS));
        this.registerSetting(fontSize = new SliderSetting("Scale", (double)1.0F, (double)0.5F, (double)2.0F, 0.1));
        this.registerSetting(backgroundRadius = new SliderSetting("Background radius", (double)8.0F, (double)0.0F, (double)30.0F, (double)0.5F));
        this.registerSetting(animationSpeed = new SliderSetting("Animation speed", 0.1, 0.01, (double)1.0F, 0.01));
        this.registerSetting(outline = new SliderSetting("Outline", 0, OUTLINE_MODES));
        this.registerSetting(new ButtonSetting("Edit position", () -> mc.displayGuiScreen(new EditScreen())));
        this.registerSetting(alignRight = new ButtonSetting("Align right", false));
        this.registerSetting(alphabeticalSort = new ButtonSetting("Alphabetical sort", false));
        this.registerSetting(drawBackground = new ButtonSetting("Draw background", true));
        this.registerSetting(roundedBackground = new ButtonSetting("Rounded background", false));
        this.registerSetting(textShadow = new ButtonSetting("Text shadow", true));
        this.registerSetting(lowercase = new ButtonSetting("Lowercase", false));
        this.registerSetting(showInfo = new ButtonSetting("Show module info", true));
        this.registerSetting(connectThreshold = new SliderSetting("Connect threshold", 0.0, 0.0, 30.0, 1.0));
    }

    public void guiUpdate() {
        int mode = colorMode == null ? 0 : (int)colorMode.getInput();
        boolean colorCodeInput = useColorCodes != null && useColorCodes.isToggled();
        if (hudColor != null) {
            hudColor.setVisible((mode == 0 || mode == 1) && !colorCodeInput, this);
        }

        if (hudColor2 != null) {
            hudColor2.setVisible(mode == 1 && !colorCodeInput, this);
        }

        if (useColorCodes != null) {
            useColorCodes.setVisible(mode == 0 || mode == 1, this);
        }

        if (hudColorCode != null) {
            hudColorCode.setVisible((mode == 0 || mode == 1) && colorCodeInput, this);
        }

        if (hudColorCode2 != null) {
            hudColorCode2.setVisible(mode == 1 && colorCodeInput, this);
        }

        boolean showWaveSettings = mode == 1 || mode == 2;
        boolean verticalAxis = hudWaveIsVertical();
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

    }

    public void onEnable() {
        this.guiUpdate();
        ModuleManager.sort();
    }

    public void guiButtonToggled(ButtonSetting buttonSetting) {
        if (buttonSetting == alphabeticalSort || buttonSetting == showInfo) {
            ModuleManager.sort();
        }

    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == Phase.END && Utils.nullCheck()) {
            if (this.isAlphabeticalSort != alphabeticalSort.isToggled()) {
                this.isAlphabeticalSort = alphabeticalSort.isToggled();
                ModuleManager.sort();
            }

            if (this.canShowInfo != showInfo.isToggled()) {
                this.canShowInfo = showInfo.isToggled();
                ModuleManager.sort();
            }

            String currentFontName = getSelectedFontName();
            float currentFontScale = getSelectedFontScale();
            if (!currentFontName.equals(this.lastHudFontName) || Float.compare(currentFontScale, this.lastHudFontScale) != 0) {
                this.lastHudFontName = currentFontName;
                this.lastHudFontScale = currentFontScale;
                ModuleManager.sort();
            }

            if (!mc.gameSettings.showDebugInfo) {
                syncPositionToResolution();

                for(Module module : ModuleManager.organizedModules) {
                    module.getInfoUpdate();
                    if (Module.sort) {
                        break;
                    }
                }

                if (Module.sort) {
                    ModuleManager.sort();
                }

                Module.sort = false;
                RavenFontRenderer hudFont = getHudFontRenderer();
                int textTopOffset = hudFont.getTextTopOffset();
                int textBottomOffset = hudFont.getTextBottomOffset();
                int horizontalTextPadding = getHudHorizontalTextPadding();
                int textTopPadding = getHudTextTopPadding();
                int textBottomPadding = getHudTextBottomPadding();
                int outlineThickness = getHudOutlineThickness();
                int rowHeight = getHudRowHeight(textTopOffset, textBottomOffset, textTopPadding, textBottomPadding);
                float yPos = posY;
                double verticalWaveAccum = (double)0.0F;
                boolean firstVisibleRow = true;
                String previousModule = "";
                double lastOutlineLeft = (double)0.0F;
                double lastOutlineRight = (double)0.0F;
                double lastBackgroundBottom = (double)0.0F;
                boolean removeVelocity = ModuleManager.antiKnockback.isEnabled();
                List<Module> modulesToRemove = new ArrayList();
                Set<Module> seenModules = new HashSet();
                if (drawBackground.isToggled()) {
                    List<double[]> rows = new ArrayList();
                    float scanY = posY;
                    double alignedRight = Double.NEGATIVE_INFINITY;

                    for(Module module : ModuleManager.organizedModules) {
                        if (module != this && !shouldSkipModule(module, removeVelocity) && (module.isEnabled() || !(module.hudAnimation <= 0.02F)) && (!(module.hudAnimation < 0.03F) || module.isEnabled())) {
                            String mn = getHudRenderText(module);
                            int ow = hudFont.getStringWidth(mn);
                            int mw = (int)((float)ow * Math.max(0.05F, module.hudAnimation));
                            float xp = posX;
                            if (alignRight.isToggled()) {
                                xp -= (float)mw;
                            }

                            double pad = (double)Math.max(1, horizontalTextPadding);
                            double left = Math.floor((double)xp - pad);
                            double right = Math.ceil((double)(xp + (float)mw) + pad);
                            double top = Math.floor((double)scanY);
                            double bottom = Math.floor((double)(scanY + (float)rowHeight));
                            rows.add(new double[]{left, right, top, bottom});
                            alignedRight = Math.max(alignedRight, right);
                            scanY += (float)rowHeight * module.hudAnimation;
                        }
                    }

                    if (!rows.isEmpty()) {
                        for(double[] row : rows) {
                            double width = row[1] - row[0];
                            row[1] = alignedRight;
                            row[0] = row[1] - width;
                        }

                        double threshold = connectThreshold == null ? 0.0 : connectThreshold.getInput();
                        if (threshold > 0.0) {
                            int gi = 0;
                            while (gi < rows.size()) {
                                double groupMinLeft = rows.get(gi)[0];
                                int gj = gi + 1;
                                while (gj < rows.size() && Math.abs(rows.get(gj)[0] - rows.get(gj - 1)[0]) <= threshold) {
                                    groupMinLeft = Math.min(groupMinLeft, rows.get(gj)[0]);
                                    gj++;
                                }
                                for (int gk = gi; gk < gj; gk++) {
                                    rows.get(gk)[0] = groupMinLeft;
                                }
                                gi = gj;
                            }
                        }

                        float rad = backgroundRadius == null ? 3.0F : (float)backgroundRadius.getInput();
                        drawSteppedScanlineBackground(rows, rad, BACKGROUND_COLOR);
                    }
                }

                try {
                    for(Module module : ModuleManager.organizedModules) {
                        if (module != this && !shouldSkipModule(module, removeVelocity)) {
                            if (!seenModules.add(module)) {
                                modulesToRemove.add(module);
                            } else if (!module.isEnabled() && module.hudAnimation <= 0.02F) {
                                modulesToRemove.add(module);
                            } else {
                                String moduleName = getHudRenderText(module);
                                int originalModuleWidth = hudFont.getStringWidth(moduleName);
                                float targetAnimation = module.isEnabled() ? 1.0F : 0.0F;
                                float speed = (float)animationSpeed.getInput();
                                module.hudAnimation += (targetAnimation - module.hudAnimation) * speed;
                                if (Math.abs(targetAnimation - module.hudAnimation) < 0.01F) {
                                    module.hudAnimation = targetAnimation;
                                }

                                if (!(module.hudAnimation < 0.03F) || module.isEnabled()) {
                                    int moduleWidth = (int)((float)originalModuleWidth * Math.max(0.05F, module.hudAnimation));
                                    float xPos = posX;
                                    float textY = getHudTextY(yPos, textTopOffset, textTopPadding);
                                    double backgroundLeft = (double)(xPos - (float)horizontalTextPadding);
                                    double backgroundRight = (double)(xPos + (float)moduleWidth + (float)horizontalTextPadding);
                                    double backgroundTop = (double)yPos;
                                    double backgroundBottom = (double)(yPos + (float)rowHeight);
                                    double outlineLeft = backgroundLeft - (double)outlineThickness;
                                    double outlineRight = backgroundRight + (double)outlineThickness;
                                    double outlineTop = backgroundTop - (double)outlineThickness;
                                    if (alignRight.isToggled()) {
                                        xPos -= (float)moduleWidth;
                                        backgroundLeft = (double)(xPos - (float)horizontalTextPadding);
                                        backgroundRight = (double)(xPos + (float)moduleWidth + (float)horizontalTextPadding);
                                        outlineLeft = backgroundLeft - (double)outlineThickness;
                                        outlineRight = backgroundRight + (double)outlineThickness;
                                    }

                                    double rowCenterX = (backgroundLeft + backgroundRight) * (double)0.5F;
                                    double wavePhase = hudWavePhase(verticalWaveAccum, rowCenterX);
                                    int color = getHudColor(wavePhase);
                                    if (outline.getInput() == (double)1.0F && firstVisibleRow) {
                                        RenderUtils.drawRect(outlineLeft, outlineTop, outlineRight, backgroundTop, color);
                                    }

                                    if (hudWaveIsVertical()) {
                                        verticalWaveAccum += getVerticalWaveStep();
                                    }

                                    firstVisibleRow = false;
                                if (outline.getInput() == (double)1.0F && !previousModule.isEmpty()) {
                                    double difference = (double)(hudFont.getStringWidth(previousModule) - moduleWidth);
                                    if (alphabeticalSort.isToggled() && difference < (double)0.0F) {
                                        RenderUtils.drawRect(outlineLeft, outlineTop, (double)xPos - difference + (double)horizontalTextPadding + (double)outlineThickness, backgroundTop, color);
                                    } else if (alignRight.isToggled()) {
                                        double stepEdge = (double)xPos - difference - (double)horizontalTextPadding - (double)outlineThickness;
                                        RenderUtils.drawRect(Math.min(stepEdge, backgroundLeft), outlineTop, Math.max(stepEdge, backgroundLeft), backgroundTop, color);
                                    } else {
                                        double stepEdge = (double)xPos + difference + (double)moduleWidth + (double)horizontalTextPadding + (double)outlineThickness;
                                        RenderUtils.drawRect(Math.min(backgroundRight, stepEdge), outlineTop, Math.max(backgroundRight, stepEdge), backgroundTop, color);
                                    }
                                }

                                    if (outline.getInput() > (double)0.0F) {
                                        if (alignRight.isToggled()) {
                                            RenderUtils.drawRect(backgroundRight, backgroundTop, outlineRight, backgroundBottom, color);
                                        } else {
                                            RenderUtils.drawRect(outlineLeft, backgroundTop, backgroundLeft, backgroundBottom, color);
                                        }
                                    }

                                    if (outline.getInput() == (double)1.0F) {
                                        if (alignRight.isToggled()) {
                                            RenderUtils.drawRect(outlineLeft, backgroundTop, backgroundLeft, backgroundBottom, color);
                                        } else {
                                            RenderUtils.drawRect(backgroundRight, backgroundTop, outlineRight, backgroundBottom, color);
                                        }
                                    }

                                    drawHudText(hudFont, moduleName, xPos, textY, color);
                                    previousModule = moduleName;
                                    lastOutlineLeft = outlineLeft;
                                    lastOutlineRight = outlineRight;
                                    lastBackgroundBottom = backgroundBottom;
                                    yPos += (float)rowHeight * module.hudAnimation;
                                }
                            }
                        }
                    }

                    if (!modulesToRemove.isEmpty()) {
                        ModuleManager.organizedModules.removeAll(modulesToRemove);
                    }
                } catch (Exception exception) {
                    Utils.sendMessage("&cAn error occurred rendering HUD. check your logs");
                    exception.printStackTrace();
                }

                if (outline.getInput() == (double)1.0F && !previousModule.isEmpty()) {
                    double bottomCenterX = (lastOutlineLeft + lastOutlineRight) * (double)0.5F;
                    double bottomPhase = hudWavePhase(verticalWaveAccum, bottomCenterX);
                    RenderUtils.drawRect(lastOutlineLeft, lastBackgroundBottom, lastOutlineRight, lastBackgroundBottom + (double)outlineThickness, getHudColor(bottomPhase));
                }

            }
        }
    }

    public static int getLongestModule() {
        RavenFontRenderer hudFont = getHudFontRenderer();
        int length = 0;

        for(Module module : ModuleManager.organizedModules) {
            if (module.isEnabled()) {
                length = Math.max(length, hudFont.getStringWidth(getHudRenderText(module)));
            }
        }

        return length;
    }

    private static boolean shouldSkipModule(Module module, boolean removeVelocity) {
        if (module.isHidden()) {
            return true;
        } else if (module == ModuleManager.commandLine) {
            return true;
        } else {
            return module instanceof Velocity && removeVelocity;
        }
    }

    private static boolean isLastVisibleModule(Module currentModule, boolean removeVelocity) {
        boolean foundCurrent = false;

        for(Module module : ModuleManager.organizedModules) {
            if (!foundCurrent) {
                if (module == currentModule) {
                    foundCurrent = true;
                }
            } else if (module.isEnabled() && !(module instanceof HUD) && !shouldSkipModule(module, removeVelocity)) {
                return false;
            }
        }

        return true;
    }

    public static RavenFontRenderer getHudFontRenderer() {
        return FontManager.getHudRenderer(getSelectedFontName(), getSelectedFontScale());
    }

    public static String getHudText(Module module) {
        String moduleName = module instanceof AntiKnockback ? "Velocity" : module.getNameInHud();
        if (!moduleName.equalsIgnoreCase("KB Displacement")) {
            moduleName = moduleName.replace(" ", "");
        }

        if (lowercase != null && lowercase.isToggled()) {
            moduleName = moduleName.toLowerCase();
        }

        return moduleName;
    }

    public static String getHudRenderText(Module module) {
        String moduleName = getHudText(module);
        if (showInfo != null && showInfo.isToggled() && !module.getInfo().isEmpty()) {
            moduleName = moduleName + " §7" + module.getInfo();
        }

        if (lowercase != null && lowercase.isToggled()) {
            moduleName = moduleName.toLowerCase();
        }

        return moduleName;
    }

    public static String getSelectedFontName() {
        if (font == null) {
            return HUD_FONT_OPTIONS[0];
        } else {
            int index = (int)Math.max((double)0.0F, Math.min((double)(font.getOptions().length - 1), font.getInput()));
            return font.getOptions()[index];
        }
    }

    public static float getSelectedFontScale() {
        return fontSize == null ? 1.0F : (float)fontSize.getInput();
    }

    public static float getRelativePosX() {
        syncPositionToResolution();
        return relativePosX;
    }

    public static float getRelativePosY() {
        syncPositionToResolution();
        return relativePosY;
    }

    public static void setRelativePosition(float normalizedX, float normalizedY) {
        relativePosX = normalizedX;
        relativePosY = normalizedY;
        syncPositionToResolution();
    }

    public static void setAbsolutePosition(float absoluteX, float absoluteY) {
        setAbsolutePosition(absoluteX, absoluteY, new ScaledResolution(mc));
    }

    public static void resetPosition() {
        resetPosition(new ScaledResolution(mc));
    }

    private static void syncPositionToResolution() {
        syncPositionToResolution(new ScaledResolution(mc));
    }

    private static void syncPositionToResolution(ScaledResolution resolution) {
        int scaledWidth = Math.max(1, resolution.getScaledWidth());
        int scaledHeight = Math.max(1, resolution.getScaledHeight());
        if (Float.isNaN(relativePosX) || Float.isNaN(relativePosY)) {
            relativePosX = posX / (float)scaledWidth;
            relativePosY = posY / (float)scaledHeight;
        }

        posX = relativePosX * (float)scaledWidth;
        posY = relativePosY * (float)scaledHeight;
    }

    private static void setAbsolutePosition(float absoluteX, float absoluteY, ScaledResolution resolution) {
        posX = absoluteX;
        posY = absoluteY;
        int scaledWidth = Math.max(1, resolution.getScaledWidth());
        int scaledHeight = Math.max(1, resolution.getScaledHeight());
        relativePosX = absoluteX / (float)scaledWidth;
        relativePosY = absoluteY / (float)scaledHeight;
    }

    private static void resetPosition(ScaledResolution resolution) {
        setAbsolutePosition(5.0F, 70.0F, resolution);
    }

    private static int getHudHorizontalTextPadding() {
        return getScaledHudPixels(2.0F);
    }

    private static int getHudTextTopPadding() {
        return getScaledHudPixels(2.0F);
    }

    private static int getHudTextBottomPadding() {
        return 0;
    }

    private static int getHudOutlineThickness() {
        return getScaledHudPixels(1.0F);
    }

    private static int getHudRowHeight(int textTopOffset, int textBottomOffset, int textTopPadding, int textBottomPadding) {
        int textBoxHeight = Math.max(1, textBottomOffset - textTopOffset);
        return Math.max(1, textBoxHeight + textTopPadding + textBottomPadding);
    }

    private static float getHudTextY(float rowTop, int textTopOffset, int textTopPadding) {
        return rowTop + (float)textTopPadding - (float)textTopOffset;
    }

    private static int getScaledHudPixels(float basePixels) {
        return Math.max(1, Math.round(basePixels * getSelectedFontScale()));
    }

    private static boolean shouldDrawTextShadow() {
        return textShadow == null || textShadow.isToggled();
    }

    private static boolean hudWaveIsVertical() {
        return waveAxis == null || (int)waveAxis.getInput() == 0;
    }

    private static double hudWavePhase(double verticalAccum, double rowCenterX) {
        return hudWaveIsVertical() ? verticalAccum : rowCenterX * (0.35 / getWaveLengthMultiplier()) * (double)getHorizontalWaveDirectionSign();
    }

    private static void drawHudText(RavenFontRenderer hudFont, String moduleName, float xPos, float textY, int fallbackColor) {
        if (!shouldUseHorizontalWaveText()) {
            hudFont.drawString(moduleName, xPos, textY, fallbackColor, shouldDrawTextShadow());
        } else {
            hudFont.drawGlyphString(moduleName, xPos, textY, (character, xOffset, width, formattingColor) -> formattingColor != null ? formattingColor : getHudColor(hudWavePhase((double)0.0F, (double)(xPos + xOffset + width * 0.5F))), shouldDrawTextShadow());
        }
    }

    private static boolean shouldUseHorizontalWaveText() {
        return colorMode != null && (int)colorMode.getInput() != 0 && !hudWaveIsVertical();
    }

    private static double getVerticalWaveStep() {
        return (double)12.0F / getWaveLengthMultiplier() * (double)getVerticalWaveDirectionSign();
    }

    private static int getVerticalWaveDirectionSign() {
        return verticalWaveDirection != null && (int)verticalWaveDirection.getInput() != 0 ? 1 : -1;
    }

    private static int getHorizontalWaveDirectionSign() {
        return horizontalWaveDirection != null && (int)horizontalWaveDirection.getInput() != 0 ? 1 : -1;
    }

    public static int getHudColor(double gradientOffset) {
        if (colorMode != null && hudColor != null) {
            int mode = (int)colorMode.getInput();
            if (mode == 2) {
                return getRainbowWaveColor(gradientOffset);
            } else if (mode == 1 && hudColor2 != null) {
                Color c1 = getHudPrimaryColor();
                Color c2 = getHudSecondaryColor();
                return getGradientWaveColor(c1, c2, gradientOffset);
            } else {
                return getHudPrimaryColor().getRGB();
            }
        } else {
            return 16777215;
        }
    }

    private static Color getHudPrimaryColor() {
        return useColorCodes != null && useColorCodes.isToggled() ? new Color(getColorCodeRgb(hudColorCode, hudColor)) : new Color(hudColor.getRed(), hudColor.getGreen(), hudColor.getBlue());
    }

    private static Color getHudSecondaryColor() {
        if (useColorCodes != null && useColorCodes.isToggled()) {
            return new Color(getColorCodeRgb(hudColorCode2, hudColor2));
        } else {
            return hudColor2 == null ? getHudPrimaryColor() : new Color(hudColor2.getRed(), hudColor2.getGreen(), hudColor2.getBlue());
        }
    }

    private static int getColorCodeRgb(TextSetting colorCodeSetting, ColorSetting fallback) {
        char code = parseColorCodeChar(colorCodeSetting == null ? null : colorCodeSetting.getText());
        return code == 0 && fallback != null ? fallback.getRGB() : getMinecraftColorRgb(code == 0 ? 'f' : code);
    }

    private static char parseColorCodeChar(String input) {
        if (input == null) {
            return '\u0000';
        } else {
            String trimmed = input.trim();
            if (trimmed.isEmpty()) {
                return '\u0000';
            } else if (trimmed.length() < 2 || trimmed.charAt(0) != '&' && trimmed.charAt(0) != 167) {
                char code = trimmed.charAt(0);
                return "0123456789abcdef".indexOf(Character.toLowerCase(code)) >= 0 ? Character.toLowerCase(code) : '\u0000';
            } else {
                char code = trimmed.charAt(1);
                return "0123456789abcdef".indexOf(Character.toLowerCase(code)) >= 0 ? Character.toLowerCase(code) : '\u0000';
            }
        }
    }

    private static int getMinecraftColorRgb(char code) {
        int index = "0123456789abcdef".indexOf(Character.toLowerCase(code));
        if (index < 0) {
            return 16777215;
        } else {
            int offset = (index >> 3 & 1) * 85;
            int red = (index >> 2 & 1) * 170 + offset;
            int green = (index >> 1 & 1) * 170 + offset;
            int blue = (index & 1) * 170 + offset;
            if (index == 6) {
                red += 85;
            }

            return red << 16 | green << 8 | blue;
        }
    }

    private static TextSetting createColorCodeSetting(String name, String defaultCode) {
        return new TextSetting(name, defaultCode, "&c / c", 2) {
            public void loadProfile(JsonObject data) {
                if (data != null && data.has(this.getProfileKey()) && data.get(this.getProfileKey()).isJsonPrimitive()) {
                    String value = data.getAsJsonPrimitive(this.getProfileKey()).getAsString();
                    if (HUD.parseColorCodeChar(value) != 0) {
                        this.setText(value);
                    }

                }
            }
        };
    }

    private static int getGradientWaveColor(Color c1, Color c2, double gradientOffset) {
        double animationProgress = (Math.sin(getAnimatedWaveAngle(gradientOffset)) + (double)1.0F) * (double)0.5F;
        return Theme.convert(c1, c2, animationProgress).getRGB();
    }

    private static int getRainbowWaveColor(double gradientOffset) {
        double hue = getAnimatedWaveAngle(gradientOffset) / (Math.PI * 2D);
        hue -= Math.floor(hue);
        return Color.getHSBColor((float)hue, 1.0F, 1.0F).getRGB();
    }

    private static double getAnimatedWaveAngle(double gradientOffset) {
        return (double)System.currentTimeMillis() / (double)7500.0F * (Math.PI * 2D) * getWaveSpeedMultiplier() + gradientOffset * 0.12;
    }

    private static double getWaveSpeedMultiplier() {
        return waveSpeed == null ? (double)1.0F : Math.max(0.1, waveSpeed.getInput());
    }

    private static double getWaveLengthMultiplier() {
        return waveLength == null ? (double)1.0F : Math.max((double)0.5F, waveLength.getInput());
    }

    private static void drawModuleBackground(double x1, double y1, double x2, double y2, int color) {
        if (roundedBackground != null && roundedBackground.isToggled()) {
            List<double[]> one = new ArrayList();
            one.add(new double[]{x1, x2, y1, y2});
            float radius = backgroundRadius == null ? 3.0F : (float)backgroundRadius.getInput();
            drawSteppedScanlineBackground(one, radius, color);
        } else {
            RenderUtils.drawRect(x1, y1, x2, y2, color);
        }
    }

    private static void drawRowBackground(double left, double top, double right, double bottom, float radius, boolean isFirst, boolean isLast, int color) {
        List<double[]> one = new ArrayList();
        one.add(new double[]{left, right, top, bottom});
        drawSteppedScanlineBackground(one, radius, color);
    }

    private static void drawSteppedScanlineBackground(List<double[]> rows, float radius, int color) {
        if (rows != null && !rows.isEmpty()) {
            if (roundedBackground != null && roundedBackground.isToggled()) {
                float baseAlpha = (float)(color >> 24 & 255) / 255.0F;
                float red = (float)(color >> 16 & 255) / 255.0F;
                float green = (float)(color >> 8 & 255) / 255.0F;
                float blue = (float)(color & 255) / 255.0F;
                int scale = new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor();
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                GlStateManager.disableTexture2D();
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                Tessellator tessellator = Tessellator.getInstance();
                WorldRenderer renderer = tessellator.getWorldRenderer();
                renderer.begin(7, DefaultVertexFormats.POSITION_COLOR);

                for(int i = 0; i < rows.size(); ++i) {
                    double[] row = (double[])rows.get(i);
                    double x1 = row[0] * (double)scale;
                    double x2 = row[1] * (double)scale;
                    double y1 = row[2] * (double)scale;
                    double y2 = row[3] * (double)scale;
                    double r = Math.max((double)0.0F, Math.min((double)(radius * (float)scale), Math.min(x2 - x1, y2 - y1) / (double)2.0F));
                boolean isFirst = i == 0;
                boolean isLast = i == rows.size() - 1;
                boolean connectedToNext = i < rows.size() - 1 && Math.abs(row[0] - rows.get(i + 1)[0]) < 0.5;
                boolean connectedToPrev = i > 0 && Math.abs(row[0] - rows.get(i - 1)[0]) < 0.5;
                boolean isLastInGroup = !connectedToNext;
                boolean isFirstInGroup = !connectedToPrev;
                boolean roundTL;
                boolean roundTR;
                boolean roundBL;
                boolean roundBR;
                if (alignRight != null && alignRight.isToggled()) {
                        roundTL = isFirst && isFirstInGroup;
                        roundTR = isFirst;
                        roundBL = isLastInGroup;
                        roundBR = isLast;
                    } else {
                        roundTL = isFirst;
                        roundTR = isFirstInGroup && isFirst;
                        roundBL = isLast;
                        roundBR = isLastInGroup;
                    }
                    double tlCX = x1 + r;
                    double tlCY = y1 + r;
                    double trCX = x2 - r;
                    double trCY = y1 + r;
                    double blCX = x1 + r;
                    double blCY = y2 - r;
                    double brCX = x2 - r;
                    double brCY = y2 - r;
                    int yStart = (int)Math.floor(y1);
                    int yEnd = (int)Math.ceil(y2);
                    int xStart = (int)Math.floor(x1);
                    int xEnd = (int)Math.ceil(x2);

                    for(int py = yStart; py < yEnd; ++py) {
                        double rowTop = Math.max((double)py, y1);
                        double rowBottom = Math.min((double)py + (double)1.0F, y2);
                        if (!(rowBottom <= rowTop)) {
                            boolean inTopCorner = r > (double)0.5F && rowTop < y1 + r && (roundTL || roundTR);
                            boolean inBotCorner = r > (double)0.5F && rowBottom > y2 - r && (roundBL || roundBR);
                            if (!inTopCorner && !inBotCorner) {
                                double invS = (double)1.0F / (double)scale;
                                emitHudQuad(renderer, x1 * invS, rowTop * invS, x2 * invS, rowBottom * invS, red, green, blue, baseAlpha * (float)(rowBottom - rowTop));
                            } else {
                                double invS = (double)1.0F / (double)scale;

                                for(int px = xStart; px < xEnd; ++px) {
                                    double cov = screenPixelCoverage(px, py, rowTop, rowBottom, x1, y1, x2, y2, r,
                                            roundTL, roundTR, roundBL, roundBR,
                                            tlCX, tlCY, trCX, trCY, blCX, blCY, brCX, brCY);
                                    if (cov > (double)0.0F) {
                                        emitHudQuad(renderer, (double)px * invS, rowTop * invS, ((double)px + (double)1.0F) * invS, rowBottom * invS, red, green, blue, baseAlpha * (float)cov);
                                    }
                                }
                            }
                        }
                    }
                }

                tessellator.draw();
                GlStateManager.enableTexture2D();
                GlStateManager.disableBlend();
            } else {
                for(double[] row : rows) {
                    RenderUtils.drawRect(row[0], row[2], row[1], row[3], color);
                }

            }
        }
    }

    private static double screenPixelCoverage(int px, int py, double rowTop, double rowBottom, double x1, double y1, double x2, double y2, double r,
                                              boolean roundTL, boolean roundTR, boolean roundBL, boolean roundBR,
                                              double tlCX, double tlCY, double trCX, double trCY, double blCX, double blCY, double brCX, double brCY) {
        int inside = 0;

        for(int sy = 0; sy < 16; ++sy) {
            double sampleY = rowTop + (rowBottom - rowTop) * ((double)sy + (double)0.5F) / (double)16.0F;

            for(int sx = 0; sx < 16; ++sx) {
                double sampleX = (double)px + ((double)sx + (double)0.5F) / (double)16.0F;
                if (!(sampleX < x1) && !(sampleX > x2) && !(sampleY < y1) && !(sampleY > y2)) {
                    boolean inShape = true;
                    if (roundTL && r > (double)0.5F && sampleX < tlCX && sampleY < tlCY) {
                        double dx = sampleX - tlCX;
                        double dy = sampleY - tlCY;
                        if (dx * dx + dy * dy > r * r) {
                            inShape = false;
                        }
                    }

                    if (inShape && roundTR && r > (double)0.5F && sampleX > trCX && sampleY < trCY) {
                        double dx = sampleX - trCX;
                        double dy = sampleY - trCY;
                        if (dx * dx + dy * dy > r * r) {
                            inShape = false;
                        }
                    }

                    if (inShape && roundBL && r > (double)0.5F && sampleX < blCX && sampleY > blCY) {
                        double dx = sampleX - blCX;
                        double dy = sampleY - blCY;
                        if (dx * dx + dy * dy > r * r) {
                            inShape = false;
                        }
                    }

                    if (inShape && roundBR && r > (double)0.5F && sampleX > brCX && sampleY > brCY) {
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
        }

        return (double)inside / (double)256.0F;
    }

    private static void emitHudQuad(WorldRenderer renderer, double x1, double y1, double x2, double y2, float red, float green, float blue, float alpha) {
        if (!(alpha <= 0.0F) && !(x2 <= x1) && !(y2 <= y1)) {
            renderer.pos(x1, y2, 0.0D).color(red, green, blue, alpha).endVertex();
            renderer.pos(x2, y2, 0.0D).color(red, green, blue, alpha).endVertex();
            renderer.pos(x2, y1, 0.0D).color(red, green, blue, alpha).endVertex();
            renderer.pos(x1, y1, 0.0D).color(red, green, blue, alpha).endVertex();
        }
    }

    static class EditScreen extends GuiScreen {
        private static final String EXAMPLE = "This is an-Example-HUD";
        private GuiButtonExt resetPosition;
        private boolean dragging = false;
        private float minX = 0.0F;
        private float minY = 0.0F;
        private float maxX = 0.0F;
        private float maxY = 0.0F;
        private float actualX = 5.0F;
        private float actualY = 70.0F;
        private float lastActualX = 0.0F;
        private float lastActualY = 0.0F;
        private int lastMouseX = 0;
        private int lastMouseY = 0;
        private float clickMinX = 0.0F;

        @Override
        public void initGui() {
            super.initGui();
            this.buttonList.add(this.resetPosition = new GuiButtonExt(1, this.width - 90, this.height - 25, 85, 20, "Reset position"));
            HUD.syncPositionToResolution(new ScaledResolution(this.mc));
            this.actualX = HUD.posX;
            this.actualY = HUD.posY;
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            ScaledResolution resolution = new ScaledResolution(this.mc);
            if (!this.dragging) {
                HUD.syncPositionToResolution(resolution);
                this.actualX = HUD.posX;
                this.actualY = HUD.posY;
            }

            drawDefaultBackground();
            float previewX = this.actualX;
            float previewY = this.actualY;
            float previewMaxX = previewX + 50.0F;
            float previewMaxY = previewY + 32.0F;
            float[] clickPos = this.getPreviewBounds("This is an-Example-HUD");
            this.minX = previewX;
            this.minY = previewY;
            if (clickPos == null) {
                this.maxX = previewMaxX;
                this.maxY = previewMaxY;
                this.clickMinX = previewX;
            } else {
                this.maxX = clickPos[0];
                this.maxY = clickPos[1];
                this.clickMinX = clickPos[2];
            }

            HUD.setAbsolutePosition(previewX, previewY, resolution);
            int textX = resolution.getScaledWidth() / 2 - 84;
            int textY = resolution.getScaledHeight() / 2 - 20;
            RenderUtils.drawColoredString("Edit the HUD position by dragging.", '-', textX, textY, 2L, 0L, true, this.mc.fontRendererObj);

            try {
                this.handleInput();
            } catch (IOException var13) {
            }

            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        private float[] getPreviewBounds(String text) {
            RavenFontRenderer hudFont = HUD.getHudFontRenderer();
            if (this.empty()) {
                float x = this.minX;
                float y = this.minY;
                String[] lines = text.split("-");
                int localTextTopPadding = HUD.getHudTextTopPadding();
                int localTextBottomPadding = HUD.getHudTextBottomPadding();
                int localRowHeight = HUD.getHudRowHeight(hudFont.getTextTopOffset(), hudFont.getTextBottomOffset(), localTextTopPadding, localTextBottomPadding);

                for(String line : lines) {
                    if (HUD.alignRight.isToggled()) {
                        x += (float)(hudFont.getStringWidth(lines[0]) - hudFont.getStringWidth(line));
                    }

                    float textY = HUD.getHudTextY(y, hudFont.getTextTopOffset(), localTextTopPadding);
                    HUD.drawHudText(hudFont, line, x, textY, Color.white.getRGB());
                    y += (float)localRowHeight;
                }

                return null;
            } else {
                int longestModule = HUD.getLongestModule();
                float y = this.minY;
                double verticalWaveAccum = (double)0.0F;
                boolean firstVisibleRow = true;
                String previousModule = "";
                double lastOutlineLeft = (double)0.0F;
                double lastOutlineRight = (double)0.0F;
                double lastBackgroundBottom = (double)0.0F;
                boolean removeVelocity = ModuleManager.antiKnockback.isEnabled();
                new ArrayList();
                new HashSet();
                int textTopOffset = hudFont.getTextTopOffset();
                int textBottomOffset = hudFont.getTextBottomOffset();
                int horizontalTextPadding = HUD.getHudHorizontalTextPadding();
                int textTopPadding = HUD.getHudTextTopPadding();
                int textBottomPadding = HUD.getHudTextBottomPadding();
                int outlineThickness = HUD.getHudOutlineThickness();
                int rowHeight = HUD.getHudRowHeight(textTopOffset, textBottomOffset, textTopPadding, textBottomPadding);

                try {
                    for(Module module : ModuleManager.organizedModules) {
                        if (!(module instanceof HUD) && !HUD.shouldSkipModule(module, removeVelocity)) {
                            float targetAnimation = module.isEnabled() ? 1.0F : 0.0F;
                            float speed = HUD.animationSpeed == null ? 0.01F : (float)HUD.animationSpeed.getInput();
                            module.hudAnimation += (targetAnimation - module.hudAnimation) * speed;
                            if (Math.abs(targetAnimation - module.hudAnimation) < 0.01F) {
                                module.hudAnimation = targetAnimation;
                            }

                            if (!(module.hudAnimation < 0.03F) || module.isEnabled()) {
                                String moduleName = HUD.getHudRenderText(module);
                                int originalModuleWidth = hudFont.getStringWidth(moduleName);
                                int moduleWidth = (int)((float)originalModuleWidth * Math.max(0.05F, module.hudAnimation));
                                float xPos = HUD.posX;
                                float textY = HUD.getHudTextY(y, textTopOffset, textTopPadding);
                                double backgroundLeft = (double)(xPos - (float)horizontalTextPadding);
                                double backgroundRight = (double)(xPos + (float)moduleWidth + (float)horizontalTextPadding);
                                double backgroundTop = (double)y;
                                double backgroundBottom = (double)(y + (float)rowHeight);
                                double outlineLeft = backgroundLeft - (double)outlineThickness;
                                double outlineRight = backgroundRight + (double)outlineThickness;
                                double outlineTop = backgroundTop - (double)outlineThickness;
                                if (HUD.alignRight.isToggled()) {
                                    xPos -= (float)moduleWidth;
                                    backgroundLeft = (double)(xPos - (float)horizontalTextPadding);
                                    backgroundRight = (double)(xPos + (float)moduleWidth + (float)horizontalTextPadding);
                                    outlineLeft = backgroundLeft - (double)outlineThickness;
                                    outlineRight = backgroundRight + (double)outlineThickness;
                                }

                                double rowCenterX = (backgroundLeft + backgroundRight) * (double)0.5F;
                                double wavePhase = HUD.hudWavePhase(verticalWaveAccum, rowCenterX);
                                int color = HUD.getHudColor(wavePhase);
                                if (HUD.outline.getInput() == (double)1.0F && firstVisibleRow) {
                                    RenderUtils.drawRect(outlineLeft, outlineTop, outlineRight, backgroundTop, color);
                                }

                                if (HUD.hudWaveIsVertical()) {
                                    verticalWaveAccum += HUD.getVerticalWaveStep();
                                }

                                firstVisibleRow = false;
                                if (HUD.drawBackground.isToggled()) {
                                    HUD.drawModuleBackground(backgroundLeft, backgroundTop, backgroundRight, backgroundBottom, HUD.BACKGROUND_COLOR);
                                }

                                if (HUD.outline.getInput() == (double)1.0F && !previousModule.isEmpty()) {
                                    double difference = (double)(hudFont.getStringWidth(previousModule) - moduleWidth);
                                    if (HUD.alphabeticalSort.isToggled() && difference < (double)0.0F) {
                                        RenderUtils.drawRect(outlineLeft, outlineTop, (double)xPos - difference + (double)horizontalTextPadding + (double)outlineThickness, backgroundTop, color);
                                    } else if (HUD.alignRight.isToggled()) {
                                        double stepEdge = (double)xPos - difference - (double)horizontalTextPadding - (double)outlineThickness;
                                        RenderUtils.drawRect(Math.min(stepEdge, backgroundLeft), outlineTop, Math.max(stepEdge, backgroundLeft), backgroundTop, color);
                                    } else {
                                        double stepEdge = (double)xPos + difference + (double)moduleWidth + (double)horizontalTextPadding + (double)outlineThickness;
                                        RenderUtils.drawRect(Math.min(backgroundRight, stepEdge), outlineTop, Math.max(backgroundRight, stepEdge), backgroundTop, color);
                                    }
                                }

                                if (HUD.outline.getInput() > (double)0.0F) {
                                    if (HUD.alignRight.isToggled()) {
                                        RenderUtils.drawRect(backgroundRight, backgroundTop, outlineRight, backgroundBottom, color);
                                    } else {
                                        RenderUtils.drawRect(outlineLeft, backgroundTop, backgroundLeft, backgroundBottom, color);
                                    }
                                }

                                if (HUD.outline.getInput() == (double)1.0F) {
                                    if (HUD.alignRight.isToggled()) {
                                        RenderUtils.drawRect(outlineLeft, backgroundTop, backgroundLeft, backgroundBottom, color);
                                    } else {
                                        RenderUtils.drawRect(backgroundRight, backgroundTop, outlineRight, backgroundBottom, color);
                                    }
                                }

                                HUD.drawHudText(hudFont, moduleName, xPos, textY, color);
                                previousModule = moduleName;
                                lastOutlineLeft = outlineLeft;
                                lastOutlineRight = outlineRight;
                                lastBackgroundBottom = backgroundBottom;
                                y += (float)rowHeight * module.hudAnimation;
                            }
                        }
                    }
                } catch (Exception exception) {
                    Utils.sendMessage("&cAn error occurred rendering HUD. check your logs");
                    exception.printStackTrace();
                }

                if (HUD.outline.getInput() == (double)1.0F && !previousModule.isEmpty()) {
                    double bottomCenterX = (lastOutlineLeft + lastOutlineRight) * (double)0.5F;
                    double bottomPhase = HUD.hudWavePhase(verticalWaveAccum, bottomCenterX);
                    RenderUtils.drawRect(lastOutlineLeft, lastBackgroundBottom, lastOutlineRight, lastBackgroundBottom + (double)outlineThickness, HUD.getHudColor(bottomPhase));
                }

                return new float[]{this.minX + (float)longestModule, (float)Math.ceil(Math.max((double)y, lastBackgroundBottom)), this.minX - (float)longestModule};
            }
        }

        @Override
        protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
            super.mouseClickMove(mouseX, mouseY, button, timeSinceLastClick);
            if (button == 0) {
                if (this.dragging) {
                    this.actualX = this.lastActualX + (float)(mouseX - this.lastMouseX);
                    this.actualY = this.lastActualY + (float)(mouseY - this.lastMouseY);
                } else if ((float)mouseX > this.clickMinX && (float)mouseX < this.maxX && (float)mouseY > this.minY && (float)mouseY < this.maxY) {
                    this.dragging = true;
                    this.lastMouseX = mouseX;
                    this.lastMouseY = mouseY;
                    this.lastActualX = this.actualX;
                    this.lastActualY = this.actualY;
                }

            }
        }

        @Override
        protected void mouseReleased(int mouseX, int mouseY, int state) {
            super.mouseReleased(mouseX, mouseY, state);
            if (state == 0) {
                this.dragging = false;
            }

        }

        @Override
        public void actionPerformed(GuiButton button) {
            if (button == this.resetPosition) {
                HUD.resetPosition(new ScaledResolution(this.mc));
                this.actualX = HUD.posX;
                this.actualY = HUD.posY;
            }

        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }

        private boolean empty() {
            for(Module module : ModuleManager.organizedModules) {
                if (module.isEnabled() && !module.getName().equals("HUD") && !module.isHidden() && module != ModuleManager.commandLine) {
                    return false;
                }
            }

            return true;
        }
    }
}