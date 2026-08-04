package keystrokesmod.script;

import keystrokesmod.Raven;
import keystrokesmod.event.*;
import keystrokesmod.mixin.impl.accessor.IAccessorEntityRenderer;
import keystrokesmod.mixin.impl.accessor.IAccessorMinecraft;
import keystrokesmod.module.Module;
import keystrokesmod.script.model.Entity;
import keystrokesmod.script.model.MovementInput;
import keystrokesmod.script.model.PlayerState;
import keystrokesmod.script.model.Vec3;
import keystrokesmod.script.packet.clientbound.SPacket;
import keystrokesmod.script.packet.serverbound.CPacket;
import keystrokesmod.script.packet.serverbound.PacketHandler;
import keystrokesmod.utility.Utils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.lwjgl.opengl.GL11;

public class ScriptEvents {
    public Module module;

    public ScriptEvents(Module module) {
        this.module = module;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onChat(ClientChatReceivedEvent e) {
        if (!Utils.nullCheck()) {
            return;
        }
        if (Utils.stripColor(e.message.getUnformattedText()).isEmpty()) {
            return;
        }
        if (Raven.scriptManager.invokeBoolean("onChat", module, e.message.getUnformattedText(), e.type) == 0) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onSendPacket(SendPacketEvent e) {
        if (e.isCanceled() || e.getPacket() == null) {
            return;
        }
        if (e.getPacket().getClass().getSimpleName().startsWith("S")) {
            return;
        }
        CPacket packet = PacketHandler.convertServerBound(e.getPacket());
        if (packet != null && Raven.scriptManager.invokeBoolean("onPacketSent", module, packet) == 0) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDispatchPacket(DispatchPacketEvent e) {
        if (e.getPacket() == null) {
            return;
        }
        if (e.getPacket().getClass().getSimpleName().startsWith("S")) {
            return;
        }
        CPacket packet = PacketHandler.convertServerBound(e.getPacket());
        Raven.scriptManager.invoke("onDispatchPacket", module, packet);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onReceivePacket(ReceivePacketEvent e) {
        if (e.isCanceled() || e.getPacket() == null) {
            return;
        }
        SPacket packet = PacketHandler.convertClientBound(e.getPacket());
        if (packet != null && Raven.scriptManager.invokeBoolean("onPacketReceived", module, packet) == 0) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPreAttack(PreAttackEvent e) {
        if (e.isCanceled()) {
            return;
        }
        if (Raven.scriptManager.invokeBoolean("onPreAttack", module) == 0) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAttack(AttackEvent e) {
        if (e.isCanceled()) {
            return;
        }
        Entity target = Entity.convert(e.target);
        Entity attacker = Entity.convert(e.attacker);
        if (Raven.scriptManager.invokeBoolean("onAttackEntity", module, target, attacker) == 0) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onClientRotations(ClientRotationEvent e) {
        Float[] rotations = Raven.scriptManager.invokeFloatArray("getRotations", module);
        if (rotations == null || rotations.length == 0 || rotations.length > 2) {
            return;
        }
        if (rotations[0] != null) {
            e.yaw = rotations[0];
            e.scriptRotations = true;
        }
        if (rotations.length == 2 && rotations[1] != null) {
            e.pitch = rotations[1];
            e.scriptRotations = true;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPrePlayerMovementInput(PrePlayerInputEvent e) {
        MovementInput input = new MovementInput(e, (byte) 0);
        Raven.scriptManager.invoke("onPrePlayerInput", module, input);
        if (e.isEquals(input)) {
            return;
        }
        e.setForward(input.forward);
        e.setSneak(input.sneak);
        e.setJump(input.jump);
        e.setStrafe(input.strafe);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onKeyTyped(KeyPressEvent e) {
        if (e.isCanceled()) {
            return;
        }
        if (Raven.scriptManager.invokeBoolean("onKeyPress", module, e.typedChar, e.keyCode) == 0) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onKeyTyped(KeyEvent e) {
        if (e.isCanceled()) {
            return;
        }
        Raven.scriptManager.invoke("onKey", module, e.keyName, e.keyCode, e.state, e.inGui);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderWorldLast(RenderWorldLastEvent e) {
        if (!Utils.nullCheck()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        ((IAccessorEntityRenderer) mc.entityRenderer).callSetupCameraTransform(((IAccessorMinecraft) mc).getTimer().renderPartialTicks, 0);
        try {
            Raven.scriptManager.invoke("onRenderWorld", module, e.partialTicks);
        } finally {
            restoreWorldRenderState(mc, e.partialTicks);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPreUpdate(PreUpdateEvent e) {
        Raven.scriptManager.invoke("onPreUpdate", module);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPostUpdate(PostUpdateEvent e) {
        Raven.scriptManager.invoke("onPostUpdate", module);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !Utils.nullCheck()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        mc.entityRenderer.setupOverlayRendering();
        try {
            Raven.scriptManager.invoke("onRenderTick", module, e.renderTickTime);
        } finally {
            restoreOverlayRenderState(mc);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAntiCheatFlag(AntiCheatFlagEvent e) {
        Raven.scriptManager.invoke("onAntiCheatFlag", module, e.flag, Entity.convert(e.entity));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onGuiUpdate(GuiUpdateEvent e) {
        if (e.guiScreen == null) {
            return;
        }
        Raven.scriptManager.invoke("onGuiUpdate", module, e.guiScreen.getClass().getSimpleName(), e.opened);
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
        Raven.scriptManager.invoke("onDisconnect", module);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPreMotion(PreMotionEvent e) {
        PlayerState playerState = new PlayerState(e, (byte) 0);
        Raven.scriptManager.invoke("onPreMotion", module, playerState);
        if (e.isEquals(playerState)) {
            return;
        }
        if (e.getYaw() != playerState.yaw) {
            e.setYaw(playerState.yaw);
        }
        e.setPitch(playerState.pitch);
        e.setPosX(playerState.x);
        e.setPosY(playerState.y);
        e.setPosZ(playerState.z);
        e.setOnGround(playerState.onGround);
        e.setSprinting(playerState.isSprinting);
        e.setSneaking(playerState.isSneaking);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPrePlayerInteract(PrePlayerInteractEvent e) {
        Raven.scriptManager.invoke("onPrePlayerInteract", module);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onWorldJoin(EntityJoinWorldEvent e) {
        if (e.entity == null) {
            return;
        }
        Raven.scriptManager.invoke("onWorldJoin", module, Entity.convert(e.entity));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPostInput(PostPlayerInputEvent e) {
        Raven.scriptManager.invoke("onPostPlayerInput", module);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPostMotion(PostMotionEvent e) {
        Raven.scriptManager.invoke("onPostMotion", module);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMouse(MouseEvent e) {
        if (e.button == -1 && e.dwheel == 0) {
            return;
        }
        boolean state = e.buttonstate;
        if (e.button == -1) {
            if (e.dwheel > 0) {
                state = true;
            } else {
                state = false;
            }
        }
        if (Raven.scriptManager.invokeBoolean("onMouse", module, e.button, state, e.x, e.y) == 0) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPreRenderModel(RenderLivingEvent.Pre e) {
        if (!Utils.nullCheck()) {
            return;
        }
        if (Raven.scriptManager.invokeBoolean("onPreRenderModel", module, Entity.convert(e.entity)) == 0) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPostRenderModel(RenderLivingEvent.Post e) {
        if (!Utils.nullCheck()) {
            return;
        }
        Raven.scriptManager.invoke("onPostRenderModel", module, Entity.convert(e.entity));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent e) {
        Raven.scriptManager.invoke("onPlayerMove", module, new Vec3(e.x, e.y, e.z));
    }

    private void restoreWorldRenderState(Minecraft mc, float partialTicks) {
        ((IAccessorEntityRenderer) mc.entityRenderer).callSetupCameraTransform(((IAccessorMinecraft) mc).getTimer().renderPartialTicks, 0);
        restoreCommonRenderState();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.disableFog();
        RenderHelper.disableStandardItemLighting();
    }

    private void restoreOverlayRenderState(Minecraft mc) {
        mc.entityRenderer.setupOverlayRendering();
        restoreCommonRenderState();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.disableLighting();
        GlStateManager.disableBlend();
        RenderHelper.disableStandardItemLighting();
    }

    private void restoreCommonRenderState() {
        GlStateManager.resetColor();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glLineWidth(1.0F);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }
}