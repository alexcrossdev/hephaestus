package me.alexcrossdev.hephaestus.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import me.alexcrossdev.hephaestus.HephaestusInfo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class ServerCommand {
    public ServerCommand() {
    }

    @SuppressWarnings("all")
    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)
                        Commands.literal("server").requires(
                            Commands.hasPermission(Commands.LEVEL_ALL)
                        )).executes(
                            (c) -> server((CommandSourceStack)c.getSource())
                        )));
    }

    private static int server(final CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                    """
                    Type: %s

                    Version: %s
                    Build: %s
                    """
                    .formatted(
                        HephaestusInfo.BRAND,
                        HephaestusInfo.VERSION,
                        HephaestusInfo.BUILD
                    )
                ),
                false);
        return 1;
    }
}
