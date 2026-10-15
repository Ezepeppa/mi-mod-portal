package com.argon.portalgravity;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ArchitectGunItem extends Item {
    public ArchitectGunItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient && user instanceof ServerPlayerEntity serverPlayer) {
            if (user.isSneaking()) {
                ArchitectManager.cycleAxisOrMode(serverPlayer);
            } else {
                ArchitectManager.handleRightClickBuildOrMove(serverPlayer);
            }
            user.getItemCooldownManager().set(this, 4);
        }
        return TypedActionResult.success(stack, world.isClient());
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.literal("Shift + Clic Izquierdo: ").formatted(Formatting.GRAY)
                .append(Text.literal("Escanear y copiar bloque apuntado con láser").formatted(Formatting.GREEN)));
        tooltip.add(Text.literal("Shift + Clic Derecho: ").formatted(Formatting.GRAY)
                .append(Text.literal("Cambiar Modo (Suelo XZ / Pared / Cubo 3D / Mover Estructura)").formatted(Formatting.AQUA)));
        tooltip.add(Text.literal("Clic Derecho (1º y 2º clic): ").formatted(Formatting.GRAY)
                .append(Text.literal("Anclar inicio, estirar holograma 3D y construir/mover").formatted(Formatting.GOLD)));
        tooltip.add(Text.literal("Ruedita del Mouse (en previsualización): ").formatted(Formatting.GRAY)
                .append(Text.literal("Cambiar altura/pisos o acercar/alejar estructura").formatted(Formatting.YELLOW)));
        tooltip.add(Text.literal("Clic Izquierdo: ").formatted(Formatting.GRAY)
                .append(Text.literal("Cancelar previsualización actual").formatted(Formatting.RED)));
    }
}
