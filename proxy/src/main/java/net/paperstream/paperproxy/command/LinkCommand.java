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
import com.velocitypowered.proxy.VelocityServer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.paperstream.paperproxy.link.ServerLinks;

/**
 * {@code /paperproxy link list|pair|remove|exec|restart} for PaperStream backends.
 */
final class LinkCommand {

  private static final org.apache.logging.log4j.Logger LOGGER =
      org.apache.logging.log4j.LogManager.getLogger(LinkCommand.class);

  private LinkCommand() {
    throw new AssertionError();
  }

  static LiteralArgumentBuilder<CommandSource> create(final VelocityServer server) {
    final ServerLinks links = server.getServerLinks();
    final SuggestionProvider<CommandSource> linked = (ctx, builder) -> {
      links.all().forEach(l -> builder.suggest(l.name()));
      return builder.buildFuture();
    };
    final SuggestionProvider<CommandSource> targets = (ctx, builder) -> {
      links.all().forEach(l -> builder.suggest(l.name()));
      server.getPaperProxyConfig().values().groups().keySet().forEach(builder::suggest);
      links.groups().keySet().forEach(builder::suggest);
      return builder.buildFuture();
    };
    return BrigadierCommand.literalArgumentBuilder("link")
        .requires(source -> source.getPermissionValue("paperproxy.command.link")
            == Tristate.TRUE)
        .executes(ctx -> list(server, ctx))
        .then(BrigadierCommand.literalArgumentBuilder("list")
            .executes(ctx -> list(server, ctx)))
        .then(BrigadierCommand.literalArgumentBuilder("pair")
            .executes(ctx -> usage(ctx, "/paperproxy link pair <server> [group]"))
            .then(BrigadierCommand.requiredArgumentBuilder("server", StringArgumentType.word())
                .executes(ctx -> pair(server, ctx, null))
                .then(BrigadierCommand.requiredArgumentBuilder("group", StringArgumentType.word())
                    .executes(ctx -> pair(server, ctx,
                        StringArgumentType.getString(ctx, "group"))))))
        .then(BrigadierCommand.literalArgumentBuilder("remove")
            .executes(ctx -> usage(ctx, "/paperproxy link remove <server>"))
            .then(BrigadierCommand.requiredArgumentBuilder("server", StringArgumentType.word())
                .suggests(linked)
                .executes(ctx -> remove(server, ctx))))
        .then(BrigadierCommand.literalArgumentBuilder("exec")
            .requires(source -> source.getPermissionValue("paperproxy.command.link.exec")
                == Tristate.TRUE)
            .executes(ctx -> usage(ctx, "/paperproxy link exec <server> <command>"))
            .then(BrigadierCommand.requiredArgumentBuilder("server", StringArgumentType.word())
                .suggests(linked)
                .then(BrigadierCommand.requiredArgumentBuilder("command",
                        StringArgumentType.greedyString())
                    .executes(ctx -> exec(server, ctx)))))
        .then(BrigadierCommand.literalArgumentBuilder("restart")
            .executes(ctx -> usage(ctx, "/paperproxy link restart <server|group>"))
            .then(BrigadierCommand.requiredArgumentBuilder("target", StringArgumentType.word())
                .suggests(targets)
                .executes(ctx -> restart(server, ctx))));
  }

  private static int usage(final CommandContext<CommandSource> ctx, final String usage) {
    ctx.getSource().sendMessage(Component.translatable("paperproxy.usage",
        Argument.string("usage", usage)));
    return Command.SINGLE_SUCCESS;
  }

  private static int list(final VelocityServer server, final CommandContext<CommandSource> ctx) {
    final ServerLinks links = server.getServerLinks();
    final List<ServerLinks.Linked> all = links.all();
    if (all.isEmpty()) {
      ctx.getSource().sendMessage(Component.translatable("paperproxy.link.none"));
      return Command.SINGLE_SUCCESS;
    }
    ctx.getSource().sendMessage(Component.translatable("paperproxy.link.list-header"));
    for (final ServerLinks.Linked linked : all) {
      ctx.getSource().sendMessage(Component.translatable("paperproxy.link.list-entry",
          Argument.string("server", linked.name()),
          Argument.component("state", Component.translatable(links.isConnected(linked.name())
              ? "paperproxy.link.connected" : "paperproxy.link.disconnected")),
          Argument.string("address", linked.host() + ":" + linked.port()),
          Argument.string("group", linked.group() == null ? "-" : linked.group())));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int pair(final VelocityServer server, final CommandContext<CommandSource> ctx,
                          final String group) {
    final String name = StringArgumentType.getString(ctx, "server");
    final String code = server.getServerLinks().startPairing(name, group);
    final InetSocketAddress bind = server.getConfiguration().getBind();
    final String host = bind.getAddress() == null || bind.getAddress().isAnyLocalAddress()
        ? "<proxy-address>" : bind.getAddress().getHostAddress();
    ctx.getSource().sendMessage(Component.translatable("paperproxy.link.pair",
        Argument.string("server", name),
        Argument.string("code", code),
        Argument.string("address", host + ":" + bind.getPort())));
    return Command.SINGLE_SUCCESS;
  }

  private static int remove(final VelocityServer server,
                            final CommandContext<CommandSource> ctx) {
    final String name = StringArgumentType.getString(ctx, "server");
    ctx.getSource().sendMessage(Component.translatable(server.getServerLinks().unlink(name)
        ? "paperproxy.link.removed" : "paperproxy.link.not-linked",
        Argument.string("server", name)));
    return Command.SINGLE_SUCCESS;
  }

  private static int exec(final VelocityServer server, final CommandContext<CommandSource> ctx) {
    final String name = StringArgumentType.getString(ctx, "server");
    final String command = StringArgumentType.getString(ctx, "command");
    final CommandSource source = ctx.getSource();
    if (server.getServerLinks().linked(name) == null) {
      source.sendMessage(Component.translatable("paperproxy.link.not-linked",
          Argument.string("server", name)));
      return Command.SINGLE_SUCCESS;
    }
    final String line = command.startsWith("/") ? command.substring(1) : command;
    LOGGER.info("{} runs /{} on {}", source instanceof com.velocitypowered.api.proxy.Player p
        ? p.getUsername() : "Console", line, name);
    final var running = server.getServerLinks().execute(name, line);
    if (running.isEmpty()) {
      source.sendMessage(Component.translatable("paperproxy.link.offline",
          Argument.string("server", name)));
      return Command.SINGLE_SUCCESS;
    }
    running.get().whenComplete((lines, error) -> {
      if (error != null) {
        source.sendMessage(Component.translatable("paperproxy.link.exec-failed",
            Argument.string("server", name)));
      } else if (lines.isEmpty()) {
        source.sendMessage(Component.translatable("paperproxy.link.exec-empty",
            Argument.string("server", name)));
      } else {
        for (final String output : lines) {
          source.sendMessage(Component.translatable("paperproxy.link.exec-line",
              Argument.string("server", name), Argument.string("line", output)));
        }
      }
    });
    return Command.SINGLE_SUCCESS;
  }

  private static int restart(final VelocityServer server,
                             final CommandContext<CommandSource> ctx) {
    final String target = StringArgumentType.getString(ctx, "target").toLowerCase(Locale.ROOT);
    final ServerLinks links = server.getServerLinks();
    final List<String> names = new ArrayList<>();
    if (links.linked(target) != null) {
      names.add(links.linked(target).name());
    } else {
      for (final String member : server.getServerGroups().members(target)) {
        final ServerLinks.Linked linked = links.linked(member);
        if (linked != null) {
          names.add(linked.name());
        }
      }
    }
    final CommandSource source = ctx.getSource();
    if (names.isEmpty()) {
      source.sendMessage(Component.translatable("paperproxy.link.not-linked",
          Argument.string("server", target)));
      return Command.SINGLE_SUCCESS;
    }
    links.rollingRestart(names, (step, name) -> source.sendMessage(Component.translatable(
        "paperproxy.link.restart-" + step, Argument.string("server", name))));
    return Command.SINGLE_SUCCESS;
  }
}
