/*
 * Copyright (C) 2026 PaperProxy Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.paperstream.paperproxy.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.proxy.VelocityServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.paperstream.paperproxy.bungee.BungeeLayerHandle;
import net.paperstream.paperproxy.plugin.PluginReloader;

/**
 * {@code /paperproxy plugin list|reload|unload|load}.
 */
final class PluginCommand {

  private PluginCommand() {
    throw new AssertionError();
  }

  static LiteralArgumentBuilder<CommandSource> create(final VelocityServer server) {
    final SuggestionProvider<CommandSource> plugins = (ctx, builder) -> {
      server.getPluginManager().getPlugins().forEach(p -> builder.suggest(
          p.getDescription().getId()));
      final BungeeLayerHandle bungee = server.getBungeeLayer();
      if (bungee != null) {
        bungee.pluginNames().forEach(builder::suggest);
      }
      return builder.buildFuture();
    };
    final SuggestionProvider<CommandSource> jars = (ctx, builder) -> {
      try (Stream<Path> files = Files.list(Path.of("plugins"))) {
        files.map(f -> f.getFileName().toString()).filter(f -> f.endsWith(".jar"))
            .forEach(builder::suggest);
      } catch (final IOException ignored) {
        // no suggestions
      }
      return builder.buildFuture();
    };
    final PluginReloader reloader = server.getPluginReloader();

    return BrigadierCommand.literalArgumentBuilder("plugin")
        .requires(source -> source.getPermissionValue("paperproxy.command.plugin")
            == Tristate.TRUE)
        .then(BrigadierCommand.literalArgumentBuilder("list")
            .executes(ctx -> list(server, ctx)))
        .then(action("reload", "plugin", plugins, server, reloader::reload))
        .then(action("unload", "plugin", plugins, server, reloader::unload))
        .then(action("load", "file", jars, server, reloader::load));
  }

  private static LiteralArgumentBuilder<CommandSource> action(
      final String name, final String argument, final SuggestionProvider<CommandSource> suggest,
      final VelocityServer server,
      final Function<String, CompletableFuture<PluginReloader.Outcome>> operation) {
    return BrigadierCommand.literalArgumentBuilder(name)
        .then(BrigadierCommand.requiredArgumentBuilder(argument, StringArgumentType.string())
            .suggests(suggest)
            .executes(ctx -> run(server, ctx, argument, operation, false))
            .then(BrigadierCommand.literalArgumentBuilder("confirm")
                .executes(ctx -> run(server, ctx, argument, operation, true))));
  }

  private static int run(final VelocityServer server, final CommandContext<CommandSource> ctx,
                         final String argument,
                         final Function<String, CompletableFuture<PluginReloader.Outcome>> operation,
                         final boolean confirmed) {
    final CommandSource source = ctx.getSource();
    final String target = StringArgumentType.getString(ctx, argument);
    if (!confirmed && server.getPaperProxyConfig().values().reloadRequiresConfirm()) {
      source.sendMessage(Component.translatable("paperproxy.plugin.warning",
          Argument.string("command", ctx.getInput() + " confirm")));
      return Command.SINGLE_SUCCESS;
    }
    source.sendMessage(Component.translatable("paperproxy.plugin.working",
        Argument.string("plugin", target)));
    operation.apply(target).whenComplete((outcome, error) -> {
      if (error != null) {
        source.sendMessage(Component.translatable("paperproxy.plugin.failed",
            Argument.string("plugin", target), Argument.string("detail", error.toString())));
        return;
      }
      source.sendMessage(Component.translatable(outcome.key(),
          Argument.string("plugin", outcome.plugin()),
          Argument.string("detail", outcome.detail() == null ? "" : outcome.detail())));
    });
    return Command.SINGLE_SUCCESS;
  }

  private static int list(final VelocityServer server, final CommandContext<CommandSource> ctx) {
    // Name to "Name version"; versions make bug reports useful.
    final java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
    server.getPluginManager().getPlugins().stream()
        .map(PluginContainer::getDescription)
        .filter(d -> !d.getId().equals("velocity") && !d.getId().equals("paperproxy-bungee"))
        .forEach(d -> {
          final String name = d.getName().orElse(d.getId());
          labels.put(name, d.getVersion().map(v -> name + " " + v).orElse(name));
        });
    final BungeeLayerHandle bungee = server.getBungeeLayer();
    final List<String> bungeePlugins = bungee == null ? List.of() : bungee.pluginNames();
    // Bungee plugins are registered as Velocity containers too; list them only once.
    final List<String> onlyVelocity = labels.keySet().stream()
        .filter(n -> !bungeePlugins.contains(n)).map(labels::get).toList();
    final List<String> bungeeLabels = bungeePlugins.stream()
        .map(n -> labels.getOrDefault(n, n)).toList();
    ctx.getSource().sendMessage(Component.translatable("paperproxy.plugin.list",
        Argument.string("velocity", onlyVelocity.isEmpty() ? "-" : String.join(", ", onlyVelocity)),
        Argument.string("bungee", bungeeLabels.isEmpty() ? "-"
            : String.join(", ", bungeeLabels)),
        Argument.string("changes", String.valueOf(server.getPluginReloader().changes()))));
    return Command.SINGLE_SUCCESS;
  }
}
