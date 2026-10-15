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

public class SingularityOrbItem extends Item {
    public SingularityOrbItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient && user instanceof ServerPlayerEntity serverPlayer) {
            if (user.isSneaking()) {
                SingularityManager.quantumSwap(serverPlayer);
            } else {
                SingularityManager.spawnBlackHoleVortex(serverPlayer);
            }
            user.getItemCooldownManager().set(this, 15);
        }
        return TypedActionResult.success(stack, world.isClient());
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.literal("Clic Derecho: ").formatted(Formatting.GRAY)
                .append(Text.literal("Lanzar Agujero Negro Arcano 3D").formatted(Formatting.LIGHT_PURPLE)));
        tooltip.add(Text.literal("Clic Izquierdo / Shift+Derecho: ").formatted(Formatting.GRAY)
                .append(Text.literal("Intercambio Cuántico de Posición (Swap)").formatted(Formatting.AQUA)));
    }
}
