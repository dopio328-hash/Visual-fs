package com.example.aimassist;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** Весь мод в одном файле: Fabric, Minecraft 1.21.4. Правый Shift открывает меню. */
public class AimAssistMod implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("aimassist");

    // ===================== Модель настроек =====================

    public static class Slider {
        final String name;
        final double min, max;
        double value;

        Slider(String name, double min, double max, double value) {
            this.name = name;
            this.min = min;
            this.max = max;
            this.value = value;
        }
    }

    public static class Feature {
        final String tab, name, desc;
        final Slider[] sliders;
        boolean enabled = false;
        boolean expanded = false;

        Feature(String tab, String name, String desc, Slider... sliders) {
            this.tab = tab;
            this.name = name;
            this.desc = desc;
            this.sliders = sliders;
        }

        double get(int i) {
            return sliders[i].value;
        }
    }

    static final String TAB_COMBAT = "Бой";
    static final String TAB_VISUAL = "Визуал";
    static final String[] TABS = {TAB_COMBAT, TAB_VISUAL};

    static final Feature AIM = new Feature(TAB_COMBAT, "Aim Assist",
            "Плавно наводит прицел на ближайшего игрока",
            new Slider("Дальность", 1, 6, 4.5),
            new Slider("Плавность", 0.05, 1.0, 0.3),
            new Slider("FOV", 10, 180, 90));

    static final Feature HITBOX = new Feature(TAB_COMBAT, "Hitbox",
            "Увеличивает хитбоксы только у игроков",
            new Slider("Размер", 0.0, 1.0, 0.3));

    static final Feature ESP = new Feature(TAB_VISUAL, "ESP",
            "Рамка, ник и дистанция игроков сквозь стены",
            new Slider("Дальность", 8, 128, 64));

    static final Feature[] FEATURES = {AIM, HITBOX, ESP};

    private static KeyBinding menuKey;
    private static boolean hitboxApplied = false;

    // ===================== Инициализация =====================

    @Override
    public void onInitializeClient() {
        LOGGER.info("[AimAssist] onInitializeClient: start");

        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aimassist.menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                "category.aimassist"
        ));
        LOGGER.info("[AimAssist] keybind registered: key.aimassist.menu (Right Shift)");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (menuKey.wasPressed()) {
                if (client.currentScreen == null) client.setScreen(new MenuScreen());
            }
            if (client.player == null || client.world == null) return;

            if (AIM.enabled && client.currentScreen == null) aim(client);
            tickHitbox(client);
        });

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> renderEsp(ctx, tickCounter.getTickDelta(true)));
    }

    // ===================== Aim Assist =====================

    private static void aim(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        Vec3d eye = player.getEyePos();

        double range = AIM.get(0);
        float smooth = (float) AIM.get(1);
        double bestAngle = AIM.get(2) / 2.0;
        boolean found = false;
        float bestYaw = 0, bestPitch = 0;

        for (PlayerEntity e : client.world.getPlayers()) {
            if (e == player || !e.isAlive()) continue;
            if (player.distanceTo(e) > range || !player.canSee(e)) continue;

            Vec3d t = e.getPos().add(0, e.getHeight() * 0.85, 0);
            double dx = t.x - eye.x, dy = t.y - eye.y, dz = t.z - eye.z;
            double horiz = Math.sqrt(dx * dx + dz * dz);

            float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
            float dYaw = MathHelper.wrapDegrees(yaw - player.getYaw());
            float dPitch = pitch - player.getPitch();
            double angle = Math.hypot(dYaw, dPitch);

            if (angle < bestAngle) {
                bestAngle = angle;
                bestYaw = dYaw;
                bestPitch = dPitch;
                found = true;
            }
        }
        if (!found) return;

        player.setYaw(player.getYaw() + bestYaw * smooth);
        player.setPitch(MathHelper.clamp(player.getPitch() + bestPitch * smooth, -90f, 90f));
    }

    // ===================== Hitbox (только игроки, только на клиенте) =====================

    private static void tickHitbox(MinecraftClient client) {
        boolean on = HITBOX.enabled;
        if (!on && !hitboxApplied) return;

        double grow = on ? HITBOX.get(0) : 0;
        for (PlayerEntity e : client.world.getPlayers()) {
            if (e == client.player) continue;
            Box base = e.getDimensions(e.getPose()).getBoxAt(e.getPos());
            e.setBoundingBox(grow > 0 ? base.expand(grow) : base);
        }
        hitboxApplied = on;
    }

    // ===================== ESP (2D рамки на HUD) =====================

    private static void renderEsp(DrawContext ctx, float delta) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (!ESP.enabled || c.player == null || c.world == null) return;

        Camera cam = c.gameRenderer.getCamera();
        Vec3d cp = cam.getPos();
        double yr = Math.toRadians(cam.getYaw());
        double pr = Math.toRadians(cam.getPitch());
        Vec3d fwd = new Vec3d(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
        Vec3d right = new Vec3d(-Math.cos(yr), 0, -Math.sin(yr));
        Vec3d up = right.crossProduct(fwd);

        int sw = c.getWindow().getScaledWidth();
        int sh = c.getWindow().getScaledHeight();
        double fovY = Math.toRadians(c.options.getFov().getValue());
        double scale = (sh / 2.0) / Math.tan(fovY / 2.0);
        double range = ESP.get(0);

        for (PlayerEntity e : c.world.getPlayers()) {
            if (e == c.player || !e.isAlive()) continue;
            double dist = c.player.distanceTo(e);
            if (dist > range) continue;

            Box b = e.getDimensions(e.getPose()).getBoxAt(e.getLerpedPos(delta));
            double minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
            boolean ok = true;

            for (int i = 0; i < 8; i++) {
                Vec3d p = new Vec3d(
                        (i & 1) == 0 ? b.minX : b.maxX,
                        (i & 2) == 0 ? b.minY : b.maxY,
                        (i & 4) == 0 ? b.minZ : b.maxZ).subtract(cp);
                double d = p.dotProduct(fwd);
                if (d < 0.05) { ok = false; break; }
                double sx = sw / 2.0 + p.dotProduct(right) / d * scale;
                double sy = sh / 2.0 - p.dotProduct(up) / d * scale;
                minX = Math.min(minX, sx);
                maxX = Math.max(maxX, sx);
                minY = Math.min(minY, sy);
                maxY = Math.max(maxY, sy);
            }
            if (!ok) continue;

            int x1 = (int) minX, y1 = (int) minY, x2 = (int) maxX, y2 = (int) maxY;
            if (x2 - x1 < 3 || y2 - y1 < 3) continue;

            int col = 0xFFFF7A1A;
            ctx.fill(x1, y1, x2, y1 + 1, col);
            ctx.fill(x1, y2 - 1, x2, y2, col);
            ctx.fill(x1, y1, x1 + 1, y2, col);
            ctx.fill(x2 - 1, y1, x2, y2, col);

            String label = e.getName().getString() + " " + (int) dist + "m";
            ctx.drawText(c.textRenderer, label, (x1 + x2) / 2 - c.textRenderer.getWidth(label) / 2,
                    y1 - 10, 0xFFFFFFFF, true);
        }
    }

    // ===================== Меню =====================

    public static class MenuScreen extends Screen {
        private static final int W = 440, H = 280, SIDE = 100;
        private static final int ORANGE = 0xFFFF7A1A;
        private static final int WHITE = 0xFFFFFFFF;
        private static final int GRAY = 0xFF8A8F99;
        private static final int BG = 0xFF14171E;
        private static final int SIDEBAR = 0xFF0F1116;
        private static final int CARD = 0xFF1B1F27;
        private static final int BORDER = 0xFF2A2F3A;
        private static final int OFF = 0xFF3A3F4B;

        private static class Region {
            final int x1, y1, x2, y2;
            Runnable action;
            Slider slider;
            int bx, bw;

            Region(int x1, int y1, int x2, int y2) {
                this.x1 = x1;
                this.y1 = y1;
                this.x2 = x2;
                this.y2 = y2;
            }
        }

        private String tab = TABS[0];
        private final List<Region> regions = new ArrayList<>();
        private Region dragging;

        public MenuScreen() {
            super(Text.literal("Aim Assist"));
        }

        private void addAction(int x1, int y1, int x2, int y2, Runnable action) {
            Region r = new Region(x1, y1, x2, y2);
            r.action = action;
            regions.add(r);
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            super.render(ctx, mouseX, mouseY, delta);
            regions.clear();

            int x0 = (width - W) / 2;
            int y0 = (height - H) / 2;

            ctx.fill(x0 - 1, y0 - 1, x0 + W + 1, y0 + H + 1, BORDER);
            ctx.fill(x0, y0, x0 + W, y0 + H, BG);
            ctx.fill(x0, y0, x0 + SIDE, y0 + H, SIDEBAR);
            ctx.drawText(textRenderer, "AimAssist", x0 + 14, y0 + 14, ORANGE, true);

            // вкладки
            int ty = y0 + 40;
            for (String t : TABS) {
                boolean sel = t.equals(tab);
                if (sel) ctx.fill(x0 + 8, ty, x0 + SIDE - 8, ty + 22, ORANGE);
                ctx.drawText(textRenderer, t, x0 + 18, ty + 7, sel ? 0xFF000000 : WHITE, false);
                final String tt = t;
                addAction(x0 + 8, ty, x0 + SIDE - 8, ty + 22, () -> tab = tt);
                ty += 26;
            }

            ctx.drawText(textRenderer, "Right Shift - закрыть", x0 + 10, y0 + H - 16, GRAY, false);

            // содержимое вкладки
            int cx1 = x0 + SIDE + 10;
            int cx2 = x0 + W - 10;
            int cy = y0 + 14;
            ctx.drawText(textRenderer, tab, cx1, cy, WHITE, true);
            cy += 18;

            for (Feature f : FEATURES) {
                if (!f.tab.equals(tab)) continue;

                int h = 28 + (f.expanded ? f.sliders.length * 26 + 6 : 0);
                ctx.fill(cx1, cy, cx2, cy + h, CARD);

                ctx.drawText(textRenderer, f.name, cx1 + 8, cy + 5, WHITE, true);
                ctx.drawText(textRenderer, f.desc, cx1 + 8, cy + 16, GRAY, false);

                // клик по шапке раскрывает настройки
                if (f.sliders.length > 0) {
                    final Feature ff = f;
                    addAction(cx1, cy, cx2, cy + 28, () -> ff.expanded = !ff.expanded);
                    ctx.drawText(textRenderer, f.expanded ? "^" : "v", cx2 - 52, cy + 10, GRAY, false);
                }

                // переключатель
                int sx = cx2 - 38;
                int sy = cy + 8;
                ctx.fill(sx, sy, sx + 28, sy + 12, f.enabled ? ORANGE : OFF);
                int kx = f.enabled ? sx + 16 : sx + 2;
                ctx.fill(kx, sy + 2, kx + 10, sy + 10, WHITE);
                final Feature fe = f;
                addAction(sx - 2, sy - 2, sx + 30, sy + 14, () -> fe.enabled = !fe.enabled);

                // ползунки
                if (f.expanded) {
                    int sy2 = cy + 34;
                    for (Slider s : f.sliders) {
                        ctx.drawText(textRenderer, s.name, cx1 + 8, sy2, GRAY, false);
                        String val = String.format("%.2f", s.value);
                        ctx.drawText(textRenderer, val, cx2 - 8 - textRenderer.getWidth(val), sy2, WHITE, false);

                        int bx = cx1 + 8;
                        int bw = cx2 - cx1 - 16;
                        int by = sy2 + 13;
                        ctx.fill(bx, by, bx + bw, by + 3, OFF);
                        int fx = bx + (int) (bw * (s.value - s.min) / (s.max - s.min));
                        ctx.fill(bx, by, fx, by + 3, ORANGE);
                        ctx.fill(fx - 2, by - 3, fx + 2, by + 6, WHITE);

                        Region r = new Region(bx - 4, by - 7, bx + bw + 4, by + 10);
                        r.slider = s;
                        r.bx = bx;
                        r.bw = bw;
                        regions.add(r);

                        sy2 += 26;
                    }
                }
                cy += h + 6;
            }
        }

        private void updateSlider(Region r, double mouseX) {
            double t = MathHelper.clamp((mouseX - r.bx) / r.bw, 0.0, 1.0);
            r.slider.value = r.slider.min + (r.slider.max - r.slider.min) * t;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                for (int i = regions.size() - 1; i >= 0; i--) {
                    Region r = regions.get(i);
                    if (mouseX >= r.x1 && mouseX <= r.x2 && mouseY >= r.y1 && mouseY <= r.y2) {
                        if (r.slider != null) {
                            dragging = r;
                            updateSlider(r, mouseX);
                        } else if (r.action != null) {
                            r.action.run();
                        }
                        return true;
                    }
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            if (dragging != null) {
                updateSlider(dragging, mouseX);
                return true;
            }
            return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            dragging = null;
            return super.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) {
                close();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean shouldPause() {
            return false;
        }
    }
}
