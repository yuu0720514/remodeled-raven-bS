package keystrokesmod.module.impl.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import keystrokesmod.mixin.impl.accessor.IAccessorEntityRenderer;
import keystrokesmod.module.Module;
import keystrokesmod.module.impl.world.AntiBot;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.ColorSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.Utils;
import keystrokesmod.utility.font.FontManager;
import keystrokesmod.utility.font.RavenFontRenderer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

public class Nametags
extends Module {
    private static final float AUTO_SCALE_THRESHOLD = 5.0f;
    private static final Comparator<NametagRenderState> FAR_TO_NEAR = (a, b) -> Double.compare(((NametagRenderState)b).distanceSq, ((NametagRenderState)a).distanceSq);
    private static final String[] HEALTH_DISPLAY_MODES = new String[]{"Hearts", "Health"};
    private static final String[] FONT_OPTIONS = FontManager.getHudFontOptions();
    private static final int ITEM_SPACING = 14;
    private static final int ENCHANT_LINE_HEIGHT = 8;
    private static final int ENCHANT_Y_OFFSET = 24;
    private SliderSetting scale;
    private SliderSetting font;
    private ButtonSetting autoScale;
    private ButtonSetting showRect;
    private ButtonSetting onlyRenderName;
    private SliderSetting bgOpacity;
    private ButtonSetting bgBorder;
    private ButtonSetting showHealth;
    private SliderSetting healthDisplayMode;
    private ButtonSetting showHeartSymbol;
    private ButtonSetting textShadow;
    private ButtonSetting showDistance;
    private ButtonSetting showInvis;
    private ButtonSetting showArmor;
    private ButtonSetting showEnchants;
    private ButtonSetting showDurability;
    private ButtonSetting showYourself;
    private ButtonSetting hideVanilla;
    private ColorSetting friendColor;
    private ColorSetting enemyColor;
    private final SliderSetting maxDistance;
    private final List<NametagRenderState> renderStates = new ArrayList<NametagRenderState>();
    private int renderStateCount = 0;
    // armorType=0: Helmet
    private static final int[] HELMET_ENCHANT_IDS = new int[]{0, 1, 3, 4, 5, 6, 7, 34};
    private static final String[] HELMET_ENCHANT_ABBR = new String[]{"P", "FP", "BP", "PP", "R", "AA", "T", "U"};
    // armorType=1: Chestplate, armorType=2: Leggings
    private static final int[] TORSO_ENCHANT_IDS = new int[]{0, 1, 3, 4, 7, 34};
    private static final String[] TORSO_ENCHANT_ABBR = new String[]{"P", "FP", "BP", "PP", "T", "U"};
    // armorType=3: Boots
    private static final int[] BOOTS_ENCHANT_IDS = new int[]{0, 1, 2, 4, 7, 8, 34};
    private static final String[] BOOTS_ENCHANT_ABBR = new String[]{"P", "FP", "FF", "PP", "T", "DS", "U"};
    // fallback for unknown armor type
    private static final int[] ARMOR_ENCHANT_IDS = new int[]{0, 7, 34};
    private static final String[] ARMOR_ENCHANT_ABBR = new String[]{"P", "T", "U"};
    private static final int[] SWORD_ENCHANT_IDS = new int[]{16, 17, 18, 20, 19, 21};
    private static final String[] SWORD_ENCHANT_ABBR = new String[]{"S", "Sm", "BA", "F", "K", "L"};
    private static final int[] BOW_ENCHANT_IDS = new int[]{48, 49, 50, 51};
    private static final String[] BOW_ENCHANT_ABBR = new String[]{"Pw", "Pu", "Fl", "In"};
    private static final int[] TOOL_ENCHANT_IDS = new int[]{32, 33, 35, 34};
    private static final String[] TOOL_ENCHANT_ABBR = new String[]{"E", "ST", "Fo", "U"};
    private static final int[] MISC_ENCHANT_IDS = new int[]{19};
    private static final String[] MISC_ENCHANT_ABBR = new String[]{"K"};

    public Nametags() {
        super("Nametags", Module.category.render, 0);
        this.scale = new SliderSetting("Scale", 1.0, 0.1, 2.0, 0.1);
        this.registerSetting(this.scale);
        this.font = new SliderSetting("Font", 0, FONT_OPTIONS);
        this.registerSetting(this.font);
        this.autoScale = new ButtonSetting("Auto Scale", false);
        this.registerSetting(this.autoScale);
        this.showRect = new ButtonSetting("Background", true);
        this.registerSetting(this.showRect);
        this.onlyRenderName = new ButtonSetting("Only render name", false);
        this.registerSetting(this.onlyRenderName);
        this.bgOpacity = new SliderSetting("Background Opacity", 0.5, 0.0, 1.0, 0.05);
        this.registerSetting(this.bgOpacity);
        this.bgBorder = new ButtonSetting("Background Border", false);
        this.registerSetting(this.bgBorder);
        this.showHealth = new ButtonSetting("Show Health", false);
        this.registerSetting(this.showHealth);
        this.healthDisplayMode = new SliderSetting("Health display", 0, HEALTH_DISPLAY_MODES);
        this.registerSetting(this.healthDisplayMode);
        this.showHeartSymbol = new ButtonSetting("Show Heart Symbol", true);
        this.registerSetting(this.showHeartSymbol);
        this.textShadow = new ButtonSetting("Text Shadow", false);
        this.registerSetting(this.textShadow);
        this.showDistance = new ButtonSetting("Show Distance", false);
        this.registerSetting(this.showDistance);
        this.showInvis = new ButtonSetting("Show Invis", true);
        this.registerSetting(this.showInvis);
        this.showArmor = new ButtonSetting("Show Armor", false);
        this.registerSetting(this.showArmor);
        this.showEnchants = new ButtonSetting("Show Enchantments", false);
        this.registerSetting(this.showEnchants);
        this.showDurability = new ButtonSetting("Show Durability", false);
        this.registerSetting(this.showDurability);
        this.showYourself = new ButtonSetting("Show Yourself", false);
        this.registerSetting(this.showYourself);
        this.hideVanilla = new ButtonSetting("Hide Vanilla", true);
        this.registerSetting(this.hideVanilla);
        this.friendColor = new ColorSetting("Friend color", 85, 255, 255);
        this.registerSetting(this.friendColor);
        this.enemyColor = new ColorSetting("Enemy color", 255, 85, 85);
        this.registerSetting(this.enemyColor);
        this.maxDistance = new SliderSetting("Max distance", 512.0, 32.0, 512.0, 8.0);
        this.registerSetting(this.maxDistance);
    }

    @Override
    public void guiUpdate() {
        boolean healthOn = this.showHealth.isToggled();
        this.healthDisplayMode.setVisible(healthOn, this);
        this.showHeartSymbol.setVisible(healthOn && (int)this.healthDisplayMode.getInput() == 0, this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!Utils.nullCheck() || Nametags.mc.theWorld == null) {
            this.renderStateCount = 0;
            return;
        }
        this.updateRenderStates();
    }

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }
        this.renderNametags(event.partialTicks);
    }

    @SubscribeEvent
    public void onRenderLiving(RenderLivingEvent.Specials.Pre event) {
        EntityPlayer player;
        if (!this.hideVanilla.isToggled()) {
            return;
        }
        if (event.entity instanceof EntityPlayer && this.shouldRenderNametag(player = (EntityPlayer)event.entity)) {
            event.setCanceled(true);
        }
    }

    private void updateRenderStates() {
        RavenFontRenderer fontRenderer = this.getNametagFontRenderer();
        Entity viewer = mc.getRenderViewEntity();
        if (viewer == null) {
            this.renderStateCount = 0;
            return;
        }
        boolean renderDistance = this.showDistance.isToggled();
        boolean renderArmor = this.showArmor.isToggled();
        float baseScale = this.computeBaseScaleValue();
        double maxDistSq = this.maxDistance.getInput() * this.maxDistance.getInput();
        this.renderStateCount = 0;
        for (EntityPlayer player : Nametags.mc.theWorld.playerEntities) {
            double dz;
            double dy;
            double dx;
            double distanceSq;
            if (!this.shouldRenderNametag(player) || (distanceSq = (dx = player.posX - viewer.posX) * dx + (dy = player.posY - viewer.posY) * dy + (dz = player.posZ - viewer.posZ) * dz) > maxDistSq) continue;
            float distance = (float)Math.sqrt(distanceSq);
            String displayName = this.buildDisplayName(player, renderDistance, distance);
            int stringHalfWidth = fontRenderer.getStringWidth(displayName) / 2;
            int relationshipColor = this.resolveRelationshipColor(player);
            int[] playerNameRange = this.findVisiblePlayerNameRange(displayName, player.getName());
            ItemStack heldItem = null;
            ItemStack boots = null;
            ItemStack leggings = null;
            ItemStack chestplate = null;
            ItemStack helmet = null;
            int totalItems = 0;
            if (renderArmor) {
                heldItem = player.getEquipmentInSlot(0);
                if (heldItem != null) {
                    ++totalItems;
                }
                if ((boots = player.getEquipmentInSlot(1)) != null) {
                    ++totalItems;
                }
                if ((leggings = player.getEquipmentInSlot(2)) != null) {
                    ++totalItems;
                }
                if ((chestplate = player.getEquipmentInSlot(3)) != null) {
                    ++totalItems;
                }
                if ((helmet = player.getEquipmentInSlot(4)) != null) {
                    ++totalItems;
                }
            }
            if (this.renderStateCount >= this.renderStates.size()) {
                this.renderStates.add(new NametagRenderState());
            }
            this.renderStates.get(this.renderStateCount++).set(player, displayName, stringHalfWidth, Utils.getColorFromEntity((Entity)player), relationshipColor, playerNameRange[0], playerNameRange[1], distanceSq, baseScale, (player.isSneaking() ? player.height - 0.3f : player.height) + 0.3f, heldItem, boots, leggings, chestplate, helmet, totalItems);
        }
        if (this.renderStateCount > 1) {
            this.renderStates.subList(0, this.renderStateCount).sort(FAR_TO_NEAR);
        }
    }

    private void renderNametags(float partialTicks) {
        RenderManager renderManager = mc.getRenderManager();
        FontRenderer itemFontRenderer = Nametags.mc.fontRendererObj;
        RavenFontRenderer textRenderer = this.getNametagFontRenderer();
        if (renderManager == null || itemFontRenderer == null || this.renderStateCount == 0) {
            return;
        }
        ((IAccessorEntityRenderer)Nametags.mc.entityRenderer).callSetupCameraTransform(partialTicks, 0);
        for (int i = 0; i < this.renderStateCount; ++i) {
            NametagRenderState renderState = this.renderStates.get(i);
            if (renderState.player == null || !RenderUtils.isInViewFrustum((Entity)renderState.player)) continue;
            this.renderCustomName(renderState, partialTicks, renderManager, textRenderer, itemFontRenderer);
        }
        GlStateManager.enableDepth();
        GlStateManager.depthMask((boolean)true);
        GlStateManager.disableLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.tryBlendFuncSeparate((int)770, (int)771, (int)1, (int)0);
        GlStateManager.color((float)1.0f, (float)1.0f, (float)1.0f, (float)1.0f);
    }

    private boolean shouldRenderNametag(EntityPlayer player) {
        if (player == null) {
            return false;
        }
        if (player == Nametags.mc.thePlayer) {
            return this.showYourself.isToggled() && Nametags.mc.gameSettings.thirdPersonView != 0;
        }
        if (player.isDead || player.deathTime > 0) {
            return false;
        }
        if (!this.showInvis.isToggled() && player.isInvisible()) {
            return false;
        }
        return !AntiBot.isBot((Entity)player);
    }

    private String buildDisplayName(EntityPlayer entity, boolean showDist, float distance) {
        String name;
        if (this.onlyRenderName.isToggled()) {
            String formatted = Utils.getFirstColorCode(entity.getDisplayName().getFormattedText());
            String color = formatted.length() >= 2 && formatted.charAt(0) == '\u00a7' ? formatted : "";
            name = color + entity.getName();
        } else {
            name = entity.getDisplayName().getFormattedText();
        }
        if (this.showHealth.isToggled()) {
            name = this.appendHealth(name, entity);
        }
        if (showDist) {
            int dist = (int)distance;
            String distColor = dist <= 8 ? "\u00a7c" : (dist <= 15 ? "\u00a76" : (dist <= 25 ? "\u00a7e" : "\u00a77"));
            name = distColor + dist + "m\u00a7r " + name;
        }
        return name;
    }

    private int resolveRelationshipColor(EntityPlayer entity) {
        if (Utils.isFriended(entity)) {
            return this.friendColor.getColor();
        }
        if (Utils.isEnemy(entity)) {
            return this.enemyColor.getColor();
        }
        return -1;
    }

    private float computeBaseScaleValue() {
        return (float)this.scale.getInput() * 0.02f;
    }

    private float computeScaleValue(float distance, boolean scaleByDistance) {
        float scaleValue = this.computeBaseScaleValue();
        if (!scaleByDistance) {
            return scaleValue;
        }
        float effectiveDistance = Math.max(1.0f, distance);
        float scaledValue = scaleValue * (effectiveDistance / 5.0f);
        return Math.max(scaleValue, scaledValue);
    }

    private void renderCustomName(NametagRenderState state, float partialTicks, RenderManager renderManager, RavenFontRenderer textRenderer, FontRenderer itemFontRenderer) {
        EntityPlayer entity = state.player;
        if (entity == null || entity.isDead || entity.deathTime > 0) {
            return;
        }
        double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * (double)partialTicks - renderManager.viewerPosX;
        double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * (double)partialTicks - renderManager.viewerPosY;
        double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * (double)partialTicks - renderManager.viewerPosZ;
        float renderScale = state.baseScale;
        if (this.autoScale.isToggled()) {
            renderScale = this.computeScaleValue((float)Math.sqrt(x * x + y * y + z * z), true);
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate((float)((float)x), (float)((float)y + state.yOffset), (float)((float)z));
        GlStateManager.rotate((float)(-renderManager.playerViewY), (float)0.0f, (float)1.0f, (float)0.0f);
        GlStateManager.rotate((float)renderManager.playerViewX, (float)1.0f, (float)0.0f, (float)0.0f);
        GlStateManager.scale((float)(-renderScale), (float)(-renderScale), (float)renderScale);
        GlStateManager.disableLighting();
        GlStateManager.depthMask((boolean)false);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate((int)770, (int)771, (int)1, (int)0);
        GlStateManager.translate((float)0.0f, (float)-10.0f, (float)0.0f);
        if (this.showRect.isToggled() && this.bgOpacity.getInput() > 0.01 || this.bgBorder.isToggled() || state.relationshipColor != -1) {
            this.renderBackground(state.stringHalfWidth, 0.0f, state.teamColor, state.relationshipColor, textRenderer);
            this.applyNametagTextState();
        }
        this.drawDisplayName(state, textRenderer);
        this.applyNametagTextState();
        if (state.totalItems > 0) {
            int iconX = -(state.totalItems * 14) / 2;
            int iconY = -20;
            if (state.heldItem != null) {
                this.renderItemStack(state.heldItem, iconX, iconY, itemFontRenderer);
                iconX += 14;
            }
            if (state.helmet != null) {
                this.renderItemStack(state.helmet, iconX, iconY, itemFontRenderer);
                iconX += 14;
            }
            if (state.chestplate != null) {
                this.renderItemStack(state.chestplate, iconX, iconY, itemFontRenderer);
                iconX += 14;
            }
            if (state.leggings != null) {
                this.renderItemStack(state.leggings, iconX, iconY, itemFontRenderer);
                iconX += 14;
            }
            if (state.boots != null) {
                this.renderItemStack(state.boots, iconX, iconY, itemFontRenderer);
            }
        }
        GlStateManager.enableDepth();
        GlStateManager.depthMask((boolean)true);
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.tryBlendFuncSeparate((int)770, (int)771, (int)1, (int)0);
        GlStateManager.color((float)1.0f, (float)1.0f, (float)1.0f, (float)1.0f);
        GlStateManager.popMatrix();
    }

    private void applyNametagTextState() {
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.depthMask((boolean)false);
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc((int)516, (float)0.1f);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate((int)770, (int)771, (int)1, (int)0);
        GL11.glTexEnvi((int)8960, (int)8704, (int)8448);
        GlStateManager.color((float)1.0f, (float)1.0f, (float)1.0f, (float)1.0f);
    }

    private void drawDisplayName(NametagRenderState state, RavenFontRenderer textRenderer) {
        if (state.relationshipColor == -1 || state.playerNameStart < 0 || state.playerNameEnd <= state.playerNameStart) {
            textRenderer.drawString(state.displayName, -state.stringHalfWidth, 0.0f, -1, this.textShadow.isToggled());
            return;
        }
        int[] visibleIndex = new int[]{0};
        textRenderer.drawGlyphString(state.displayName, -state.stringHalfWidth, 0.0f, (character, xOffset, width, formattingColor) -> {
            int n = visibleIndex[0];
            visibleIndex[0] = n + 1;
            int glyphIndex = n;
            if (glyphIndex >= state.playerNameStart && glyphIndex < state.playerNameEnd) {
                return state.relationshipColor;
            }
            return formattingColor != null ? formattingColor : -1;
        }, this.textShadow.isToggled());
    }

    private void renderBackground(int stringWidth, float textY, int teamColor, int relationshipColor, RavenFontRenderer fontRenderer) {
        int borderColor;
        boolean renderBaseFill;
        GlStateManager.disableTexture2D();
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldRenderer = tessellator.getWorldRenderer();
        float alpha = (float)this.bgOpacity.getInput();
        float innerLeft = (float)(-stringWidth) - 3.0f;
        float innerRight = (float)stringWidth + 3.0f;
        float innerTop = textY + (float)fontRenderer.getTextTopOffset() - 3.0f;
        float innerBottom = textY + (float)fontRenderer.getTextBottomOffset() + 2.0f;
        boolean bl = renderBaseFill = this.showRect.isToggled() && alpha > 0.01f;
        if (renderBaseFill) {
            worldRenderer.begin(7, DefaultVertexFormats.POSITION_COLOR);
            worldRenderer.pos((double)innerLeft, (double)innerTop, 0.0).color(0.0f, 0.0f, 0.0f, alpha).endVertex();
            worldRenderer.pos((double)innerLeft, (double)innerBottom, 0.0).color(0.0f, 0.0f, 0.0f, alpha).endVertex();
            worldRenderer.pos((double)innerRight, (double)innerBottom, 0.0).color(0.0f, 0.0f, 0.0f, alpha).endVertex();
            worldRenderer.pos((double)innerRight, (double)innerTop, 0.0).color(0.0f, 0.0f, 0.0f, alpha).endVertex();
            tessellator.draw();
        }
        int n = borderColor = relationshipColor != -1 ? relationshipColor : teamColor;
        if (this.bgBorder.isToggled() || relationshipColor != -1) {
            float blue;
            float green;
            float red;
            if (borderColor != -1) {
                red = (float)(borderColor >> 16 & 0xFF) / 255.0f;
                green = (float)(borderColor >> 8 & 0xFF) / 255.0f;
                blue = (float)(borderColor & 0xFF) / 255.0f;
            } else {
                red = 0.6f;
                green = 0.6f;
                blue = 0.6f;
            }
            float borderThickness = 1.0f;
            float borderAlpha = relationshipColor != -1 ? alpha : 1.0f;
            float left = innerLeft - borderThickness;
            float right = innerRight + borderThickness;
            float top = innerTop - borderThickness;
            float bottom = innerBottom + borderThickness;
            float borderZ = -0.001f;
            worldRenderer.begin(7, DefaultVertexFormats.POSITION_COLOR);
            worldRenderer.pos((double)left, (double)top, (double)borderZ).color(red, green, blue, borderAlpha).endVertex();
            worldRenderer.pos((double)left, (double)innerTop, (double)borderZ).color(red, green, blue, borderAlpha).endVertex();
            worldRenderer.pos((double)right, (double)innerTop, (double)borderZ).color(red, green, blue, borderAlpha).endVertex();
            worldRenderer.pos((double)right, (double)top, (double)borderZ).color(red, green, blue, borderAlpha).endVertex();
            worldRenderer.pos((double)left, (double)innerBottom, (double)borderZ).color(red, green, blue, borderAlpha).endVertex();
            worldRenderer.pos((double)left, (double)bottom, (double)borderZ).color(red, green, blue, borderAlpha).endVertex();
            worldRenderer.pos((double)right, (double)bottom, (double)borderZ).color(red, green, blue, borderAlpha).endVertex();
            worldRenderer.pos((double)right, (double)innerBottom, (double)borderZ).color(red, green, blue, borderAlpha).endVertex();
            worldRenderer.pos((double)left, (double)innerTop, (double)borderZ).color(red, green, blue, borderAlpha).endVertex();
            worldRenderer.pos((double)left, (double)innerBottom, (double)borderZ).color(red, green, blue, borderAlpha).endVertex();
            worldRenderer.pos((double)innerLeft, (double)innerBottom, (double)borderZ).color(red, green, blue, borderAlpha).endVertex();
            worldRenderer.pos((double)innerLeft, (double)innerTop, (double)borderZ).color(red, green, blue, borderAlpha).endVertex();
            worldRenderer.pos((double)innerRight, (double)innerTop, (double)borderZ).color(red, green, blue, borderAlpha).endVertex();
            worldRenderer.pos((double)innerRight, (double)innerBottom, (double)borderZ).color(red, green, blue, borderAlpha).endVertex();
            worldRenderer.pos((double)right, (double)innerBottom, (double)borderZ).color(red, green, blue, borderAlpha).endVertex();
            worldRenderer.pos((double)right, (double)innerTop, (double)borderZ).color(red, green, blue, borderAlpha).endVertex();
            tessellator.draw();
        }
        GlStateManager.enableTexture2D();
    }

    private int[] findVisiblePlayerNameRange(String formattedText, String playerName) {
        String strippedText = this.stripFormattingCodes(formattedText);
        int nameStart = strippedText.indexOf(playerName);
        if (nameStart < 0) {
            return new int[]{-1, -1};
        }
        return new int[]{nameStart, nameStart + playerName.length()};
    }

    private String stripFormattingCodes(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ++i) {
            char character = text.charAt(i);
            if (character == '\u00a7' && i + 1 < text.length()) {
                ++i;
                continue;
            }
            builder.append(character);
        }
        return builder.toString();
    }

    private String getSelectedFontName() {
        if (this.font == null) {
            return FONT_OPTIONS[0];
        }
        int index = (int)Math.max(0.0, Math.min((double)(this.font.getOptions().length - 1), this.font.getInput()));
        return this.font.getOptions()[index];
    }

    private RavenFontRenderer getNametagFontRenderer() {
        return FontManager.getNametagRenderer(this.getSelectedFontName());
    }

    private String appendHealth(String name, EntityPlayer entity) {
        float health = Utils.getPlayerHealth(entity);
        float maxHealth = Math.max(entity.getMaxHealth(), 20.0f);
        if (maxHealth <= 0.0f) {
            maxHealth = 20.0f;
        }
        boolean heartsMode = (int)this.healthDisplayMode.getInput() == 0;
        double ratio = health / maxHealth;
        String color = ratio < 0.3 ? "\u00a7c" : (ratio < 0.5 ? "\u00a76" : (ratio < 0.7 ? "\u00a7e" : "\u00a7a"));
        float displayValue = heartsMode ? health / 2.0f : health;
        String valueStr = this.fastOneDecimal(displayValue);
        String heartSuffix = heartsMode && this.showHeartSymbol.isToggled() ? " \u2764" : "";
        name = name + " " + color + valueStr + heartSuffix;
        float absorption = entity.getAbsorptionAmount();
        if (absorption > 0.0f) {
            float absDisplay = heartsMode ? absorption / 2.0f : absorption;
            String absStr = this.fastOneDecimal(absDisplay);
            String absSuffix = heartsMode && this.showHeartSymbol.isToggled() ? " \u2764" : "";
            name = name + " \u00a76+" + absStr + absSuffix;
        }
        name = name + "\u00a7r";
        return name;
    }

    private String fastOneDecimal(float value) {
        int whole = (int)value;
        if (value == (float)whole) {
            return String.valueOf(whole);
        }
        int tenths = Math.round(value * 10.0f);
        int intPart = tenths / 10;
        int fracPart = Math.abs(tenths % 10);
        return intPart + "." + fracPart;
    }

    private void renderItemStack(ItemStack stack, int xPos, int yPos, FontRenderer fontRenderer) {
        if (stack == null) {
            return;
        }
        RenderUtils.renderItemAndEffectIntoGui3D(stack, xPos, yPos);
        if (this.showEnchants.isToggled()) {
            // アイテム描画後のGL状態（深度テスト・ライティング有効）をテキスト描画用にリセット
            GlStateManager.disableLighting();
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            GlStateManager.pushMatrix();
            GlStateManager.scale(0.5, 0.5, 0.5);
            GlStateManager.translate(0.0f, -10.0f, 0.0f);
            this.renderEnchantText(stack, xPos, yPos, fontRenderer);
            GlStateManager.popMatrix();
        }
        GlStateManager.disableDepth();
        if (stack.stackSize > 1) {
            String countStr = String.valueOf(stack.stackSize);
            fontRenderer.drawStringWithShadow(countStr, (float)(xPos + 17 - fontRenderer.getStringWidth(countStr)), (float)(yPos + 9), 0xFFFFFF);
        }
        if (this.showDurability.isToggled() && stack.isItemStackDamageable() && stack.getItemDamage() > 0) {
            int maxDamage = stack.getMaxDamage();
            int currentDamage = stack.getItemDamage();
            float durabilityRatio = 1.0f - (float)currentDamage / (float)maxDamage;
            RenderUtils.drawDurabilityBar(xPos, yPos, durabilityRatio);
        }
        GlStateManager.enableDepth();
    }

    private void renderEnchantText(ItemStack stack, int xPos, int yPos, FontRenderer fontRenderer) {
        String[] abbreviations;
        int[] ids;
        Item item = stack.getItem();
        if (item instanceof ItemArmor) {
            int armorType = ((ItemArmor) item).armorType;
            switch (armorType) {
                case 0:
                    ids = HELMET_ENCHANT_IDS;
                    abbreviations = HELMET_ENCHANT_ABBR;
                    break;
                case 1:
                case 2:
                    ids = TORSO_ENCHANT_IDS;
                    abbreviations = TORSO_ENCHANT_ABBR;
                    break;
                case 3:
                    ids = BOOTS_ENCHANT_IDS;
                    abbreviations = BOOTS_ENCHANT_ABBR;
                    break;
                default:
                    ids = ARMOR_ENCHANT_IDS;
                    abbreviations = ARMOR_ENCHANT_ABBR;
                    break;
            }
        } else if (item instanceof ItemSword) {
            ids = SWORD_ENCHANT_IDS;
            abbreviations = SWORD_ENCHANT_ABBR;
        } else if (item instanceof ItemBow) {
            ids = BOW_ENCHANT_IDS;
            abbreviations = BOW_ENCHANT_ABBR;
        } else if (item instanceof ItemTool) {
            ids = TOOL_ENCHANT_IDS;
            abbreviations = TOOL_ENCHANT_ABBR;
        } else {
            ids = MISC_ENCHANT_IDS;
            abbreviations = MISC_ENCHANT_ABBR;
        }
        int drawX = xPos * 2;
        int drawY = yPos - 24;
        for (int i = 0; i < ids.length; ++i) {
            int level = this.getEnchantLevel(stack, ids[i]);
            if (level <= 0) continue;
            this.drawEnchantLine(fontRenderer, abbreviations[i], level, drawX, drawY);
            drawY += 8;
        }
    }

    private int getEnchantLevel(ItemStack stack, int enchantId) {
        int level = EnchantmentHelper.getEnchantmentLevel(enchantId, stack);
        if (level > 0) return level;

        if (stack.hasTagCompound()) {
            NBTTagCompound tag = stack.getTagCompound();

            for (String key : new String[]{"ench", "StoredEnchantments"}) {
                if (!tag.hasKey(key, 9)) continue;
                NBTTagList list = tag.getTagList(key, 10);
                for (int i = 0; i < list.tagCount(); i++) {
                    NBTTagCompound e = list.getCompoundTagAt(i);
                    int eId = readNbtInt(e, "id");
                    if (eId == enchantId) {
                        return Math.max(readNbtInt(e, "lvl"), 1);
                    }
                }
            }

            if (tag.hasKey("ExtraAttributes", 10)) {
                NBTTagCompound extra = tag.getCompoundTag("ExtraAttributes");
                if (extra.hasKey("enchantments", 10)) {
                    NBTTagCompound enchants = extra.getCompoundTag("enchantments");
                    String skyblockName = ENCHANT_SKYBLOCK_NAMES.get(enchantId);
                    if (skyblockName != null && enchants.hasKey(skyblockName)) {
                        return readNbtInt(enchants, skyblockName);
                    }
                }
            }

            if (tag.hasKey("display", 10)) {
                NBTTagCompound display = tag.getCompoundTag("display");
                if (display.hasKey("Lore", 9)) {
                    NBTTagList lore = display.getTagList("Lore", 8);
                    for (int i = 0; i < lore.tagCount(); i++) {
                        int lvl = matchEnchantLine(lore.getStringTagAt(i), enchantId);
                        if (lvl > 0) return lvl;
                    }
                }
            }
        }

        return this.getEnchantLevelFromTooltip(stack, enchantId);
    }

    private static int readNbtInt(NBTTagCompound tag, String key) {
        if (tag.hasKey(key, 3)) return tag.getInteger(key);       // TAG_Int
        if (tag.hasKey(key, 2)) return tag.getShort(key) & 0xFFFF; // TAG_Short
        if (tag.hasKey(key, 1)) return tag.getByte(key) & 0xFF;    // TAG_Byte
        if (tag.hasKey(key, 4)) return (int) tag.getLong(key);     // TAG_Long
        return 0;
    }

    private int matchEnchantLine(String rawLine, int enchantId) {
        String[] names = ENCHANT_TOOLTIP_NAMES.get(enchantId);
        if (names == null) return 0;
        String line = net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(rawLine).trim();
        for (String name : names) {
            if (!line.startsWith(name)) continue;
            String rest = line.substring(name.length()).trim();
            if (rest.isEmpty()) return 1;
            for (int lvl = ROMAN_NUMERALS.length - 1; lvl >= 1; lvl--) {
                if (rest.equals(ROMAN_NUMERALS[lvl])) return lvl;
            }
            try { return Integer.parseInt(rest); } catch (NumberFormatException ignored) {}
            return 1;
        }
        return 0;
    }

    private static final Map<Integer, String> ENCHANT_SKYBLOCK_NAMES;
    static {
        ENCHANT_SKYBLOCK_NAMES = new HashMap<Integer, String>();
        ENCHANT_SKYBLOCK_NAMES.put(0,  "protection");
        ENCHANT_SKYBLOCK_NAMES.put(1,  "fire_protection");
        ENCHANT_SKYBLOCK_NAMES.put(2,  "feather_falling");
        ENCHANT_SKYBLOCK_NAMES.put(3,  "blast_protection");
        ENCHANT_SKYBLOCK_NAMES.put(4,  "projectile_protection");
        ENCHANT_SKYBLOCK_NAMES.put(5,  "respiration");
        ENCHANT_SKYBLOCK_NAMES.put(6,  "aqua_affinity");
        ENCHANT_SKYBLOCK_NAMES.put(7,  "thorns");
        ENCHANT_SKYBLOCK_NAMES.put(8,  "depth_strider");
        ENCHANT_SKYBLOCK_NAMES.put(16, "sharpness");
        ENCHANT_SKYBLOCK_NAMES.put(17, "smite");
        ENCHANT_SKYBLOCK_NAMES.put(18, "bane_of_arthropods");
        ENCHANT_SKYBLOCK_NAMES.put(19, "knockback");
        ENCHANT_SKYBLOCK_NAMES.put(20, "fire_aspect");
        ENCHANT_SKYBLOCK_NAMES.put(21, "looting");
        ENCHANT_SKYBLOCK_NAMES.put(32, "efficiency");
        ENCHANT_SKYBLOCK_NAMES.put(33, "silk_touch");
        ENCHANT_SKYBLOCK_NAMES.put(34, "unbreaking");
        ENCHANT_SKYBLOCK_NAMES.put(35, "fortune");
        ENCHANT_SKYBLOCK_NAMES.put(48, "power");
        ENCHANT_SKYBLOCK_NAMES.put(49, "punch");
        ENCHANT_SKYBLOCK_NAMES.put(50, "flame");
        ENCHANT_SKYBLOCK_NAMES.put(51, "infinity");
    }

    private static final Map<Integer, String[]> ENCHANT_TOOLTIP_NAMES;
    static {
        ENCHANT_TOOLTIP_NAMES = new HashMap<Integer, String[]>();
        ENCHANT_TOOLTIP_NAMES.put(0,  new String[]{"Protection"});
        ENCHANT_TOOLTIP_NAMES.put(1,  new String[]{"Fire Protection"});
        ENCHANT_TOOLTIP_NAMES.put(2,  new String[]{"Feather Falling"});
        ENCHANT_TOOLTIP_NAMES.put(3,  new String[]{"Blast Protection"});
        ENCHANT_TOOLTIP_NAMES.put(4,  new String[]{"Projectile Protection"});
        ENCHANT_TOOLTIP_NAMES.put(5,  new String[]{"Respiration"});
        ENCHANT_TOOLTIP_NAMES.put(6,  new String[]{"Aqua Affinity"});
        ENCHANT_TOOLTIP_NAMES.put(7,  new String[]{"Thorns"});
        ENCHANT_TOOLTIP_NAMES.put(8,  new String[]{"Depth Strider"});
        ENCHANT_TOOLTIP_NAMES.put(16, new String[]{"Sharpness"});
        ENCHANT_TOOLTIP_NAMES.put(17, new String[]{"Smite"});
        ENCHANT_TOOLTIP_NAMES.put(18, new String[]{"Bane of Arthropods"});
        ENCHANT_TOOLTIP_NAMES.put(19, new String[]{"Knockback"});
        ENCHANT_TOOLTIP_NAMES.put(20, new String[]{"Fire Aspect"});
        ENCHANT_TOOLTIP_NAMES.put(21, new String[]{"Looting"});
        ENCHANT_TOOLTIP_NAMES.put(32, new String[]{"Efficiency"});
        ENCHANT_TOOLTIP_NAMES.put(33, new String[]{"Silk Touch"});
        ENCHANT_TOOLTIP_NAMES.put(34, new String[]{"Unbreaking"});
        ENCHANT_TOOLTIP_NAMES.put(35, new String[]{"Fortune"});
        ENCHANT_TOOLTIP_NAMES.put(48, new String[]{"Power"});
        ENCHANT_TOOLTIP_NAMES.put(49, new String[]{"Punch"});
        ENCHANT_TOOLTIP_NAMES.put(50, new String[]{"Flame"});
        ENCHANT_TOOLTIP_NAMES.put(51, new String[]{"Infinity"});
    }

    private static final String[] ROMAN_NUMERALS = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private int getEnchantLevelFromTooltip(ItemStack stack, int enchantId) {
        List<String> tooltip;
        try {
            tooltip = stack.getTooltip(Nametags.mc.thePlayer, false);
        } catch (Exception e) {
            return 0;
        }
        for (String line : tooltip) {
            int lvl = matchEnchantLine(line, enchantId);
            if (lvl > 0) return lvl;
        }
        return 0;
    }

    private void drawEnchantLine(FontRenderer fontRenderer, String abbreviation, int level, int x, int y) {
        fontRenderer.drawStringWithShadow(abbreviation, (float)x, (float)y, 0xFFFFFF);
        int advance = fontRenderer.getStringWidth(abbreviation);
        fontRenderer.drawStringWithShadow(String.valueOf(level), (float)(x + advance), (float)y, this.colorForEnchantLevel(level));
    }

    private int colorForEnchantLevel(int level) {
        if (level <= 5) {
            if (level == 1) {
                return 0xFFFFFF;
            }
            if (level == 2) {
                return 0x55FFFF;
            }
            if (level == 3) {
                return 43690;
            }
            if (level == 4) {
                return 0xAA00AA;
            }
            if (level == 5) {
                return 0xFFAA00;
            }
        }
        return 0xFF55FF;
    }

    private static class NametagRenderState {
        private EntityPlayer player;
        private String displayName;
        private int stringHalfWidth;
        private int teamColor;
        private int relationshipColor;
        private int playerNameStart;
        private int playerNameEnd;
        private double distanceSq;
        private float baseScale;
        private float yOffset;
        private ItemStack heldItem;
        private ItemStack boots;
        private ItemStack leggings;
        private ItemStack chestplate;
        private ItemStack helmet;
        private int totalItems;

        private NametagRenderState() {
        }

        private void set(EntityPlayer player, String displayName, int stringHalfWidth, int teamColor, int relationshipColor, int playerNameStart, int playerNameEnd, double distanceSq, float baseScale, float yOffset, ItemStack heldItem, ItemStack boots, ItemStack leggings, ItemStack chestplate, ItemStack helmet, int totalItems) {
            this.player = player;
            this.displayName = displayName;
            this.stringHalfWidth = stringHalfWidth;
            this.teamColor = teamColor;
            this.relationshipColor = relationshipColor;
            this.playerNameStart = playerNameStart;
            this.playerNameEnd = playerNameEnd;
            this.distanceSq = distanceSq;
            this.baseScale = baseScale;
            this.yOffset = yOffset;
            this.heldItem = heldItem;
            this.boots = boots;
            this.leggings = leggings;
            this.chestplate = chestplate;
            this.helmet = helmet;
            this.totalItems = totalItems;
        }
    }
}

