package keystrokesmod.module.impl.render;

import java.awt.Color;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.ColorSetting;
import keystrokesmod.module.setting.impl.GroupSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.StairsUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDeadBush;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.BlockFlower;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockTallGrass;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

public class BlockOverlay
extends Module {
    private static final String[] RENDER_MODES = new String[]{"Hidden", "Vanilla", "Side", "Full"};
    private static final String[] COLOR_MODES = new String[]{"Static", "Gradient", "Fade", "Chroma"};
    private static final double PADDING = 0.002;
    private final SliderSetting renderMode = new SliderSetting("Mode", 2, RENDER_MODES);
    private final GroupSetting overlayGroup;
    private final ButtonSetting overlayVisible;
    private final SliderSetting overlayColorMode;
    private final ColorSetting overlayColor;
    private final ColorSetting overlayColor2;
    private final SliderSetting overlayFadeSpeed;
    private final SliderSetting overlayChromaSpeed;
    private final GroupSetting outlineGroup;
    private final ButtonSetting outlineVisible;
    private final SliderSetting outlineColorMode;
    private final ColorSetting outlineColor;
    private final ColorSetting outlineColor2;
    private final SliderSetting outlineFadeSpeed;
    private final SliderSetting outlineChromaSpeed;
    private final GroupSetting optionsGroup;
    private final SliderSetting thickness;
    private final ButtonSetting persistence;
    private final ButtonSetting depthless;
    private final ButtonSetting barriers;
    private final ButtonSetting hidePlants;

    public BlockOverlay() {
        super("Block Overlay", Module.category.render);
        this.registerSetting(this.renderMode);
        this.overlayGroup = new GroupSetting("Overlay");
        this.registerSetting(this.overlayGroup);
        this.overlayVisible = new ButtonSetting(this.overlayGroup, "Visible", true);
        this.registerSetting(this.overlayVisible);
        this.overlayColorMode = new SliderSetting(this.overlayGroup, "Color mode", 0, COLOR_MODES);
        this.registerSetting(this.overlayColorMode);
        this.overlayColor = new ColorSetting(this.overlayGroup, "Color", 0, 0, 0, 100);
        this.registerSetting(this.overlayColor);
        this.overlayColor2 = new ColorSetting(this.overlayGroup, "Color 2", 255, 255, 255, 100);
        this.registerSetting(this.overlayColor2);
        this.overlayFadeSpeed = new SliderSetting(this.overlayGroup, "Fade speed", 5.5, 1.0, 10.0, 0.5);
        this.registerSetting(this.overlayFadeSpeed);
        this.overlayChromaSpeed = new SliderSetting(this.overlayGroup, "Chroma speed", 5.5, 1.0, 10.0, 0.5);
        this.registerSetting(this.overlayChromaSpeed);
        this.outlineGroup = new GroupSetting("Outline");
        this.registerSetting(this.outlineGroup);
        this.outlineVisible = new ButtonSetting(this.outlineGroup, "Visible", true);
        this.registerSetting(this.outlineVisible);
        this.outlineColorMode = new SliderSetting(this.outlineGroup, "Color mode", 0, COLOR_MODES);
        this.registerSetting(this.outlineColorMode);
        this.outlineColor = new ColorSetting(this.outlineGroup, "Color", 0, 0, 0, 255);
        this.registerSetting(this.outlineColor);
        this.outlineColor2 = new ColorSetting(this.outlineGroup, "Color 2", 255, 255, 255, 255);
        this.registerSetting(this.outlineColor2);
        this.outlineFadeSpeed = new SliderSetting(this.outlineGroup, "Fade speed", 5.5, 1.0, 10.0, 0.5);
        this.registerSetting(this.outlineFadeSpeed);
        this.outlineChromaSpeed = new SliderSetting(this.outlineGroup, "Chroma speed", 5.5, 1.0, 10.0, 0.5);
        this.registerSetting(this.outlineChromaSpeed);
        this.optionsGroup = new GroupSetting("Options");
        this.registerSetting(this.optionsGroup);
        this.thickness = new SliderSetting(this.optionsGroup, "Thickness", 2.0, 1.0, 10.0, 0.5);
        this.registerSetting(this.thickness);
        this.persistence = new ButtonSetting(this.optionsGroup, "Persistence", false);
        this.registerSetting(this.persistence);
        this.depthless = new ButtonSetting(this.optionsGroup, "Depthless", false);
        this.registerSetting(this.depthless);
        this.barriers = new ButtonSetting(this.optionsGroup, "Barriers", false);
        this.registerSetting(this.barriers);
        this.hidePlants = new ButtonSetting(this.optionsGroup, "Hide plants", false);
        this.registerSetting(this.hidePlants);
    }

    @Override
    public void guiUpdate() {
        int oMode = (int)this.overlayColorMode.getInput();
        this.overlayColor2.setVisible(oMode == 1 || oMode == 2, this);
        this.overlayFadeSpeed.setVisible(oMode == 2, this);
        this.overlayChromaSpeed.setVisible(oMode == 3, this);
        int olMode = (int)this.outlineColorMode.getInput();
        this.outlineColor2.setVisible(olMode == 1 || olMode == 2, this);
        this.outlineFadeSpeed.setVisible(olMode == 2, this);
        this.outlineChromaSpeed.setVisible(olMode == 3, this);
    }

    @Override
    public String getInfo() {
        return RENDER_MODES[(int)this.renderMode.getInput()];
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public void onDrawBlockHighlight(DrawBlockHighlightEvent e) {
        int mode = (int)this.renderMode.getInput();
        if (mode == 0) {
            e.setCanceled(true);
            return;
        }
        if (mode == 1) {
            return;
        }
        e.setCanceled(true);
        if (!Utils.nullCheck()) {
            return;
        }
        if (!this.persistence.isToggled() && BlockOverlay.mc.thePlayer.isSpectator()) {
            return;
        }
        BlockPos pos = this.getFocusedBlock();
        if (pos == null) {
            return;
        }
        boolean showOverlay = this.overlayVisible.isToggled();
        boolean showOutline = this.outlineVisible.isToggled();
        if (!showOverlay && !showOutline) {
            return;
        }
        EnumFacing side = mode == 2 ? BlockOverlay.mc.objectMouseOver.sideHit : null;
        this.renderCustomBlockOverlay(pos, side, showOverlay, showOutline);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void renderCustomBlockOverlay(BlockPos pos, EnumFacing side, boolean showOverlay, boolean showOutline) {
        AxisAlignedBB box = BlockUtils.getBlockSelectionBox(pos);
        if (box == null) {
            return;
        }
        box = box.expand(0.002, 0.002, 0.002);
        double vx = BlockOverlay.mc.getRenderManager().viewerPosX;
        double vy = BlockOverlay.mc.getRenderManager().viewerPosY;
        double vz = BlockOverlay.mc.getRenderManager().viewerPosZ;
        int overlayStart = 0;
        int overlayEnd = 0;
        int outlineStart = 0;
        int outlineEnd = 0;
        if (showOverlay) {
            overlayStart = BlockOverlay.computeStart((int)this.overlayColorMode.getInput(), this.overlayColor, this.overlayColor2, this.overlayFadeSpeed.getInput(), this.overlayChromaSpeed.getInput());
            overlayEnd = BlockOverlay.computeEnd((int)this.overlayColorMode.getInput(), this.overlayColor, this.overlayColor2, this.overlayFadeSpeed.getInput(), this.overlayChromaSpeed.getInput());
        }
        if (showOutline) {
            outlineStart = BlockOverlay.computeStart((int)this.outlineColorMode.getInput(), this.outlineColor, this.outlineColor2, this.outlineFadeSpeed.getInput(), this.outlineChromaSpeed.getInput());
            outlineEnd = BlockOverlay.computeEnd((int)this.outlineColorMode.getInput(), this.outlineColor, this.outlineColor2, this.outlineFadeSpeed.getInput(), this.outlineChromaSpeed.getInput());
        }
        GL11.glPushMatrix();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate((int)770, (int)771, (int)1, (int)0);
        GlStateManager.disableTexture2D();
        GlStateManager.depthMask((boolean)false);
        boolean depthDisabled = this.depthless.isToggled();
        if (depthDisabled) {
            GlStateManager.disableDepth();
        }
        GL11.glEnable((int)2848);
        GL11.glHint((int)3154, (int)4354);
        if (showOutline) {
            GL11.glLineWidth((float)((float)this.thickness.getInput()));
        }
        GL11.glShadeModel((int)7425);
        try {
            BlockOverlay.drawOverlayGeometry(mc, pos, box, side, vx, vy, vz, overlayStart, overlayEnd, outlineStart, outlineEnd, showOverlay, showOutline);
        }
        finally {
            GL11.glShadeModel((int)7424);
            GL11.glLineWidth((float)2.0f);
            GL11.glDisable((int)2848);
            if (depthDisabled) {
                GlStateManager.enableDepth();
            }
            GlStateManager.depthMask((boolean)true);
            GlStateManager.enableTexture2D();
            GlStateManager.enableCull();
            GlStateManager.disableBlend();
            GL11.glPopMatrix();
        }
    }

    public static void renderBlockOutline(BlockPos pos, int outlineArgbStart, int outlineArgbEnd, float lineWidth, boolean depthless) {
        BlockOverlay.renderBlockHighlight(pos, 0, outlineArgbStart, outlineArgbEnd, lineWidth, depthless, false, true);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void renderBlockHighlight(BlockPos pos, int overlayArgb, int outlineArgbStart, int outlineArgbEnd, float lineWidth, boolean depthless, boolean showOverlay, boolean showOutline) {
        if (!showOverlay && !showOutline) {
            return;
        }
        Minecraft m = Minecraft.getMinecraft();
        if (m.theWorld == null || pos == null) {
            return;
        }
        AxisAlignedBB box = BlockUtils.getBlockSelectionBox(pos);
        if (box == null) {
            return;
        }
        box = box.expand(0.002, 0.002, 0.002);
        double vx = m.getRenderManager().viewerPosX;
        double vy = m.getRenderManager().viewerPosY;
        double vz = m.getRenderManager().viewerPosZ;
        GL11.glPushMatrix();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate((int)770, (int)771, (int)1, (int)0);
        GlStateManager.disableTexture2D();
        GlStateManager.depthMask((boolean)false);
        if (depthless) {
            GlStateManager.disableDepth();
        }
        GL11.glEnable((int)2848);
        GL11.glHint((int)3154, (int)4354);
        GL11.glLineWidth((float)lineWidth);
        GL11.glShadeModel((int)7425);
        try {
            BlockOverlay.drawOverlayGeometry(m, pos, box, null, vx, vy, vz, overlayArgb, overlayArgb, outlineArgbStart, outlineArgbEnd, showOverlay, showOutline);
        }
        finally {
            GL11.glShadeModel((int)7424);
            GL11.glLineWidth((float)2.0f);
            GL11.glDisable((int)2848);
            if (depthless) {
                GlStateManager.enableDepth();
            }
            GlStateManager.depthMask((boolean)true);
            GlStateManager.enableTexture2D();
            GlStateManager.enableCull();
            GlStateManager.disableBlend();
            GL11.glPopMatrix();
        }
    }

    private static void drawOverlayGeometry(Minecraft mc, BlockPos pos, AxisAlignedBB paddedWorldBox, EnumFacing side, double vx, double vy, double vz, int overlayStart, int overlayEnd, int outlineStart, int outlineEnd, boolean showOverlay, boolean showOutline) {
        AxisAlignedBB renderBox = paddedWorldBox.offset(-vx, -vy, -vz);
        IBlockState state = mc.theWorld.getBlockState(pos);
        if (state.getBlock() instanceof BlockStairs) {
            StairsUtils.drawStairs(pos, state, paddedWorldBox, side, vx, vy, vz, overlayStart, overlayEnd, outlineStart, outlineEnd, showOverlay, showOutline, BlockOverlay::drawFace);
        } else if (side != null) {
            BlockOverlay.drawFace(renderBox, side, overlayStart, overlayEnd, outlineStart, outlineEnd, showOverlay, showOutline);
        } else {
            for (EnumFacing face : EnumFacing.values()) {
                BlockOverlay.drawFace(renderBox, face, overlayStart, overlayEnd, outlineStart, outlineEnd, showOverlay, showOutline);
            }
        }
    }

    private BlockPos getFocusedBlock() {
        if (BlockOverlay.mc.objectMouseOver == null || BlockOverlay.mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return null;
        }
        BlockPos pos = BlockOverlay.mc.objectMouseOver.getBlockPos();
        if (pos == null) {
            return null;
        }
        Block block = BlockOverlay.mc.theWorld.getBlockState(pos).getBlock();
        if (block == Blocks.air) {
            return null;
        }
        if (block == Blocks.barrier && !this.barriers.isToggled()) {
            return null;
        }
        if (this.hidePlants.isToggled() && (block instanceof BlockTallGrass || block instanceof BlockFlower || block instanceof BlockDeadBush || block instanceof BlockDoublePlant)) {
            return null;
        }
        return pos;
    }

    private static int interpolate(int c1, int c2, double pct) {
        Color a = new Color(c1, true);
        Color b = new Color(c2, true);
        double inv = 1.0 - pct;
        return new Color((int)((double)a.getRed() * pct + (double)b.getRed() * inv), (int)((double)a.getGreen() * pct + (double)b.getGreen() * inv), (int)((double)a.getBlue() * pct + (double)b.getBlue() * inv), (int)((double)a.getAlpha() * pct + (double)b.getAlpha() * inv)).getRGB();
    }

    private static int computeStart(int colorMode, ColorSetting color1, ColorSetting color2, double fadeSpeed, double chromaSpeed) {
        switch (colorMode) {
            case 1: {
                return color1.getColor();
            }
            case 2: {
                double pct = Math.sin((double)System.currentTimeMillis() / (1100.0 - fadeSpeed * 100.0)) * 0.5 + 0.5;
                return BlockOverlay.interpolate(color1.getColor(), color2.getColor(), pct);
            }
            case 3: {
                int alpha = color1.getColor() >> 24 & 0xFF;
                return Utils.mergeAlpha(Utils.getChroma((long)chromaSpeed, new long[0]), alpha);
            }
        }
        return color1.getColor();
    }

    private static int computeEnd(int colorMode, ColorSetting color1, ColorSetting color2, double fadeSpeed, double chromaSpeed) {
        switch (colorMode) {
            case 1: {
                return color2.getColor();
            }
            case 2: {
                double pct = Math.sin((double)(System.currentTimeMillis() + 500L) / (1100.0 - fadeSpeed * 100.0)) * 0.5 + 0.5;
                return BlockOverlay.interpolate(color1.getColor(), color2.getColor(), pct);
            }
        }
        return BlockOverlay.computeStart(colorMode, color1, color2, fadeSpeed, chromaSpeed);
    }

    private static void drawFace(AxisAlignedBB box, EnumFacing face, int os, int oe, int ls, int le, boolean overlay, boolean outline) {
        Tessellator ts = Tessellator.getInstance();
        WorldRenderer wr = ts.getWorldRenderer();
        if (overlay) {
            wr.begin(7, DefaultVertexFormats.POSITION_COLOR);
            BlockOverlay.addFaceVertices(wr, face, box, os, oe);
            ts.draw();
        }
        if (outline) {
            wr.begin(2, DefaultVertexFormats.POSITION_COLOR);
            BlockOverlay.addFaceVertices(wr, face, box, ls, le);
            ts.draw();
        }
    }

    private static void v(WorldRenderer wr, double x, double y, double z, int color) {
        wr.pos(x, y, z).color(color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF, color >> 24 & 0xFF).endVertex();
    }

    private static void addFaceVertices(WorldRenderer wr, EnumFacing face, AxisAlignedBB box, int start, int end) {
        switch (face) {
            case UP: {
                BlockOverlay.v(wr, box.minX, box.maxY, box.maxZ, start);
                BlockOverlay.v(wr, box.maxX, box.maxY, box.maxZ, end);
                BlockOverlay.v(wr, box.maxX, box.maxY, box.minZ, start);
                BlockOverlay.v(wr, box.minX, box.maxY, box.minZ, end);
                break;
            }
            case DOWN: {
                BlockOverlay.v(wr, box.maxX, box.minY, box.maxZ, start);
                BlockOverlay.v(wr, box.minX, box.minY, box.maxZ, end);
                BlockOverlay.v(wr, box.minX, box.minY, box.minZ, start);
                BlockOverlay.v(wr, box.maxX, box.minY, box.minZ, end);
                break;
            }
            case NORTH: {
                BlockOverlay.v(wr, box.maxX, box.maxY, box.minZ, start);
                BlockOverlay.v(wr, box.maxX, box.minY, box.minZ, end);
                BlockOverlay.v(wr, box.minX, box.minY, box.minZ, start);
                BlockOverlay.v(wr, box.minX, box.maxY, box.minZ, end);
                break;
            }
            case SOUTH: {
                BlockOverlay.v(wr, box.minX, box.maxY, box.maxZ, start);
                BlockOverlay.v(wr, box.minX, box.minY, box.maxZ, end);
                BlockOverlay.v(wr, box.maxX, box.minY, box.maxZ, start);
                BlockOverlay.v(wr, box.maxX, box.maxY, box.maxZ, end);
                break;
            }
            case EAST: {
                BlockOverlay.v(wr, box.maxX, box.maxY, box.minZ, start);
                BlockOverlay.v(wr, box.maxX, box.maxY, box.maxZ, end);
                BlockOverlay.v(wr, box.maxX, box.minY, box.maxZ, start);
                BlockOverlay.v(wr, box.maxX, box.minY, box.minZ, end);
                break;
            }
            case WEST: {
                BlockOverlay.v(wr, box.minX, box.maxY, box.maxZ, start);
                BlockOverlay.v(wr, box.minX, box.maxY, box.minZ, end);
                BlockOverlay.v(wr, box.minX, box.minY, box.minZ, start);
                BlockOverlay.v(wr, box.minX, box.minY, box.maxZ, end);
            }
        }
    }
}

