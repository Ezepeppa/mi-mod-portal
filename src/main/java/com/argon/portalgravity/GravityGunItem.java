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

public class GravityGunItem extends Item {
    public GravityGunItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient && user instanceof ServerPlayerEntity serverPlayer) {
            if (user.isSneaking()) {
                GravityGunManager.grabOrbitalShield(serverPlayer);
            } else {
                GravityGunManager.toggleGrabOrRelease(serverPlayer);
            }
            user.getItemCooldownManager().set(this, 4);
        }
        return TypedActionResult.success(stack, world.isClient());
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.literal("Clic Derecho: ").formatted(Formatting.GRAY)
                .append(Text.literal("Agarrar / Soltar con colisiones reales").formatted(Formatting.GOLD)));
        tooltip.add(Text.literal("Ruedita del Mouse (al sostener): ").formatted(Formatting.GRAY)
                .append(Text.literal("Acercar / Alejar objeto (sin cambiar slot)").formatted(Formatting.GREEN)));
        tooltip.add(Text.literal("Clic Izquierdo: ").formatted(Formatting.GRAY)
                .append(Text.literal("Lanzar con alto daño de impacto").formatted(Formatting.YELLOW)));
        tooltip.add(Text.literal("Shift + Clic Derecho: ").formatted(Formatting.GRAY)
                .append(Text.literal("Escudo Orbital (5 objetos en órbita)").formatted(Formatting.AQUA)));
    }
}
