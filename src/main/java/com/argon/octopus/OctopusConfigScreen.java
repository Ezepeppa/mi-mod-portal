package com.argon.octopus;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class OctopusConfigScreen extends Screen {
    public OctopusConfigScreen() {
        super(Text.literal("Panel de Físicas Biomecánicas v2.0.0 — Pulpo Terrestre"));
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int startY = 42;
        int btnW = 260;
        int btnH = 20;

        this.addDrawableChild(ButtonWidget.builder(getTentacleCountText(), btn -> {
            int next = OctopusPhysicsConfig.tentacleCount + 2;
            if (next > 12) next = 4;
            OctopusPhysicsConfig.tentacleCount = next;
            btn.setMessage(getTentacleCountText());
            syncConfigToServer();
        }).dimensions(cx - btnW / 2, startY, btnW, btnH).build());

        this.addDrawableChild(ButtonWidget.builder(getLengthText(), btn -> {
            float next = OctopusPhysicsConfig.tentacleLength + 2.0f;
            if (next > 11.0f) next = 4.5f;
            OctopusPhysicsConfig.tentacleLength = next;
            btn.setMessage(getLengthText());
            syncConfigToServer();
        }).dimensions(cx - btnW / 2, startY + 24, btnW, btnH).build());

        this.addDrawableChild(ButtonWidget.builder(getPullStrengthText(), btn -> {
            float next = OctopusPhysicsConfig.suctionPullStrength + 0.4f;
            if (next > 2.3f) next = 0.65f;
            OctopusPhysicsConfig.suctionPullStrength = next;
            btn.setMessage(getPullStrengthText());
            syncConfigToServer();
        }).dimensions(cx - btnW / 2, startY + 48, btnW, btnH).build());

        this.addDrawableChild(ButtonWidget.builder(getGravityText(), btn -> {
            float next = OctopusPhysicsConfig.gravitySlumpFactor + 0.45f;
            if (next > 2.0f) next = 0.55f;
            OctopusPhysicsConfig.gravitySlumpFactor = next;
            btn.setMessage(getGravityText());
            syncConfigToServer();
        }).dimensions(cx - btnW / 2, startY + 72, btnW, btnH).build());

        this.addDrawableChild(ButtonWidget.builder(getStiffnessText(), btn -> {
            float next = OctopusPhysicsConfig.hydrostatStiffness + 0.4f;
            if (next > 1.85f) next = 0.4f;
            OctopusPhysicsConfig.hydrostatStiffness = next;
            btn.setMessage(getStiffnessText());
            syncConfigToServer();
        }).dimensions(cx - btnW / 2, startY + 96, btnW, btnH).build());

        this.addDrawableChild(ButtonWidget.builder(getClimbText(), btn -> {
            OctopusPhysicsConfig.wallClimbingEnabled = !OctopusPhysicsConfig.wallClimbingEnabled;
            btn.setMessage(getClimbText());
            syncConfigToServer();
        }).dimensions(cx - btnW / 2, startY + 120, btnW, btnH).build());

        this.addDrawableChild(ButtonWidget.builder(getDebugText(), btn -> {
            OctopusPhysicsConfig.showDebugPhysicsVectors = !OctopusPhysicsConfig.showDebugPhysicsVectors;
            btn.setMessage(getDebugText());
            syncConfigToServer();
        }).dimensions(cx - btnW / 2, startY + 144, btnW, btnH).build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("★ Iniciar Circuito Showcase / Demo Guiado ★").formatted(Formatting.GOLD, Formatting.BOLD),
                btn -> {
                    PacketByteBuf buf = PacketByteBufs.create();
                    buf.writeInt(1);
                    ClientPlayNetworking.send(OctopusMod.START_SHOWCASE_PACKET, buf);
                    this.close();
                }
        ).dimensions(cx - btnW / 2, startY + 172, btnW, 22).build());
    }

    private Text getTentacleCountText() {
        return Text.literal("Cantidad de Tentáculos: ").formatted(Formatting.GRAY)
                .append(Text.literal(OctopusPhysicsConfig.tentacleCount + " brazos radiales").formatted(Formatting.AQUA));
    }

    private Text getLengthText() {
        return Text.literal("Longitud de Tentáculos: ").formatted(Formatting.GRAY)
                .append(Text.literal(String.format("%.1f bloques", OctopusPhysicsConfig.tentacleLength)).formatted(Formatting.AQUA));
    }

    private Text getPullStrengthText() {
        return Text.literal("Fuerza de Ventosas y Tracción: ").formatted(Formatting.GRAY)
                .append(Text.literal(String.format("%.2fx", OctopusPhysicsConfig.suctionPullStrength)).formatted(Formatting.GREEN));
    }

    private Text getGravityText() {
        return Text.literal("Peso fuera del Agua (Gravedad): ").formatted(Formatting.GRAY)
                .append(Text.literal(String.format("%.2fx", OctopusPhysicsConfig.gravitySlumpFactor)).formatted(Formatting.YELLOW));
    }

    private Text getStiffnessText() {
        return Text.literal("Rigidez Muscular (Hidrostato): ").formatted(Formatting.GRAY)
                .append(Text.literal(String.format("%.2fx", OctopusPhysicsConfig.hydrostatStiffness)).formatted(Formatting.LIGHT_PURPLE));
    }

    private Text getClimbText() {
        return Text.literal("Trepar Paredes Verticales: ").formatted(Formatting.GRAY)
                .append(Text.literal(OctopusPhysicsConfig.wallClimbingEnabled ? "ACTIVADO" : "DESACTIVADO")
                        .formatted(OctopusPhysicsConfig.wallClimbingEnabled ? Formatting.GREEN : Formatting.RED));
    }

    private Text getDebugText() {
        return Text.literal("Rayos-X de Nodos y Ventosas (Debug): ").formatted(Formatting.GRAY)
                .append(Text.literal(OctopusPhysicsConfig.showDebugPhysicsVectors ? "VISIBLE" : "OCULTO")
                        .formatted(OctopusPhysicsConfig.showDebugPhysicsVectors ? Formatting.AQUA : Formatting.DARK_GRAY));
    }

    private void syncConfigToServer() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(OctopusPhysicsConfig.tentacleCount);
        buf.writeFloat(OctopusPhysicsConfig.tentacleLength);
        buf.writeFloat(OctopusPhysicsConfig.suctionPullStrength);
        buf.writeFloat(OctopusPhysicsConfig.gravitySlumpFactor);
        buf.writeFloat(OctopusPhysicsConfig.hydrostatStiffness);
        buf.writeBoolean(OctopusPhysicsConfig.wallClimbingEnabled);
        buf.writeBoolean(OctopusPhysicsConfig.showDebugPhysicsVectors);
        ClientPlayNetworking.send(OctopusMod.SYNC_CONFIG_PACKET, buf);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 16, 0xFFCC44);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("WASD = Arrastrarse/Nadar | R = Agarrar/Lanzar con Inercia | G = Latigazo | Espacio = Impulso | Shift+Clic = Quedarse/Seguir")
                        .formatted(Formatting.GRAY),
                this.width / 2, 28, 0xAAAAAA);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
