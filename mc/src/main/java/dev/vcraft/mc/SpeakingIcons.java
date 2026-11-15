package dev.vcraft.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vcraft.core.AddonHost;
import dev.vcraft.core.VCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
//#if v26
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.state.level.CameraRenderState;
//#else
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.renderer.state.CameraRenderState;
//#endif

/** The speaker icon addons can show above players' heads, drawn like a nametag: above it, facing the camera. */
final class SpeakingIcons {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(VCraft.MOD_ID, "textures/icon/speaking.png");
    private static final double MAX_DISTANCE = 64;
    private static final float SIZE = 0.35f;
    private static final int FULL_BRIGHT = 0xF000F0;

    static void register() {
        //#if v26
        LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> draw(ctx.poseStack(), ctx.submitNodeCollector(), ctx.levelState().cameraRenderState));
        //#else
        WorldRenderEvents.BEFORE_ENTITIES.register(ctx -> draw(ctx.matrices(), ctx.commandQueue(), ctx.worldState().cameraRenderState));
        //#endif
    }

    private static void draw(PoseStack poses, SubmitNodeCollector out, CameraRenderState camera) {
        if (!AddonHost.INSTANCE.hasSpeakingIcons() || Mc.hudHidden()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player me = mc.player;
        if (level == null || me == null || camera == null || camera.pos == null) return;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 cam = camera.pos;
        Quaternionf facing = camera.orientation;
        RenderType type = RenderTypes.text(TEXTURE);
        for (Player p : level.players()) {
            if (p == me || p == mc.getCameraEntity()) continue;
            // Same cases where vanilla hides or dims the nametag.
            if (p.isInvisibleTo(me) || p.isDiscrete()) continue;
            Vec3 pos = p.getPosition(partial);
            if (pos.distanceToSqr(cam) > MAX_DISTANCE * MAX_DISTANCE) continue;
            if (!AddonHost.INSTANCE.showSpeakingIcon(p.getUUID().toString())) continue;

            poses.pushPose();
            // Nametags sit about half a block above the head; the icon goes just above that.
            poses.translate(pos.x - cam.x, pos.y + p.getBbHeight() + 0.95 - cam.y, pos.z - cam.z);
            //#if v26
            poses.rotate(facing);
            //#else
            poses.mulPose(facing);
            //#endif
            out.submitCustomGeometry(poses, type, SpeakingIcons::quad);
            poses.popPose();
        }
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer v) {
        float s = SIZE / 2;
        // Both windings, so it shows whichever way the render type culls.
        vertex(v, pose, -s, s, 0, 0);
        vertex(v, pose, -s, -s, 0, 1);
        vertex(v, pose, s, -s, 1, 1);
        vertex(v, pose, s, s, 1, 0);
        vertex(v, pose, s, s, 1, 0);
        vertex(v, pose, s, -s, 1, 1);
        vertex(v, pose, -s, -s, 0, 1);
        vertex(v, pose, -s, s, 0, 0);
    }

    private static void vertex(VertexConsumer v, PoseStack.Pose pose, float x, float y, float u, float w) {
        v.addVertex(pose, x, y, 0).setColor(0xFFFFFFFF).setUv(u, w).setLight(FULL_BRIGHT);
    }

    private SpeakingIcons() {
    }
}
