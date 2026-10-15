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

public class PortalGunItem extends Item {
    public PortalGunItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient && user instanceof ServerPlayerEntity serverPlayer) {
            if (user.isSneaking()) {
                PortalManager.clearPlayerPortals(serverPlayer);
            } else {
                PortalManager.shootPortal(serverPlayer, true); // Portal Naranja
            }
            user.getItemCooldownManager().set(this, 4);
        }
        return TypedActionResult.success(stack, world.isClient());
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.literal("Clic Izquierdo: ").formatted(Formatting.GRAY)
                .append(Text.literal("Disparar Portal Azul").formatted(Formatting.AQUA)));
        tooltip.add(Text.literal("Clic Derecho: ").formatted(Formatting.GRAY)
                .append(Text.literal("Disparar Portal Naranja").formatted(Formatting.GOLD)));
        tooltip.add(Text.literal("Shift + Clic Izquierdo: ").formatted(Formatting.GRAY)
                .append(Text.literal("Cambiar Modo (Normal / Colosal / Impulso)").formatted(Formatting.LIGHT_PURPLE)));
        tooltip.add(Text.literal("Shift + Clic Derecho: ").formatted(Formatting.GRAY)
                .append(Text.literal("Cerrar ambos portales").formatted(Formatting.RED)));
    }
}
