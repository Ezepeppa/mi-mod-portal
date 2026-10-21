package com.argon.octopus;

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

public class OctopusControllerItem extends Item {
    public OctopusControllerItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (user.isSneaking()) {
            if (!world.isClient && user instanceof ServerPlayerEntity sp) {
                OctopusShowcaseManager.startOrAdvanceShowcase(sp, 1);
            }
            return TypedActionResult.success(stack);
        }
        if (world.isClient) {
            OctopusClient.openConfigScreenOnClient();
        }
        return TypedActionResult.success(stack);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.literal("Clic Derecho: ").formatted(Formatting.GRAY)
                .append(Text.literal("Abrir Menú Interactivo de Físicas de Tentáculos").formatted(Formatting.AQUA)));
        tooltip.add(Text.literal("Shift + Clic Derecho: ").formatted(Formatting.GRAY)
                .append(Text.literal("Construir y Lanzar Circuito Showcase Guiado").formatted(Formatting.GOLD)));
    }
}
