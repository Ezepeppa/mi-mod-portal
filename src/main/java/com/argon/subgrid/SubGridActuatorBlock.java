package com.argon.subgrid;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.BlockView;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class SubGridActuatorBlock extends Block {
    public SubGridActuatorBlock(Settings settings) {
        super(settings);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable BlockView world, List<Text> tooltip, TooltipContext options) {
        tooltip.add(Text.literal("Al recibir Redstone: ").formatted(Formatting.GRAY)
                .append(Text.literal("Desliza los bloques de Sub-Grid apoyados encima de a fracciones de bloque").formatted(Formatting.AQUA)));
    }
}
