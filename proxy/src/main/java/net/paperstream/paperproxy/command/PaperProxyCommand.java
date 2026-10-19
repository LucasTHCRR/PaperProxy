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
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.util.ProxyVersion;
import com.velocitypowered.proxy.VelocityServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.paperstream.paperproxy.PaperProxyBranding;
import net.paperstream.paperproxy.network.DiscordWebhook;
import net.paperstream.paperproxy.via.ViaInstaller;

/**
 * Implements {@code /paperproxy} (alias {@code /pp}).
 */
public final class PaperProxyCommand {

  private static final TextColor BRAND_COLOR = TextColor.color(0x3ad9cd);

  private PaperProxyCommand() {
    throw new AssertionError();
  }

  /**
   * Creates the command.
   *
   * @param server the proxy
   * @return the command
   */
  public static BrigadierCommand create(final VelocityServer server) {
    return new BrigadierCommand(BrigadierCommand.literalArgumentBuilder("paperproxy")
        // Everyone may see the version and the notice unless explicitly denied.
        .requires(source -> source.getPermissionValue("paperproxy.command.info") != Tristate.FALSE)
        .executes(ctx -> info(server, ctx))
        .then(BrigadierCommand.literalArgumentBuilder("info")
            .executes(ctx -> info(server, ctx)))
        .then(BrigadierCommand.literalArgumentBuilder("reload")
            .requires(source -> source.getPermissionValue("paperproxy.command.reload")
                == Tristate.TRUE)
            .executes(ctx -> reload(server, ctx)))
        .then(BrigadierCommand.literalArgumentBuilder("maintenance")
            .requires(source -> source.getPermissionValue("paperproxy.command.maintenance")
                == Tristate.TRUE)
            .executes(ctx -> maintenanceStatus(server, ctx))
            .then(BrigadierCommand.literalArgumentBuilder("on")
                .executes(ctx -> maintenance(server, ctx, null, true))
                .then(BrigadierCommand.requiredArgumentBuilder("server", StringArgumentType.word())
                    .suggests(PaperProxyCommand.serverSuggestions(server))
                    .executes(ctx -> maintenance(server, ctx,
                        StringArgumentType.getString(ctx, "server"), true))))
            .then(BrigadierCommand.literalArgumentBuilder("off")
                .executes(ctx -> maintenance(server, ctx, null, false))
                .then(BrigadierCommand.requiredArgumentBuilder("server", StringArgumentType.word())
                    .suggests(PaperProxyCommand.serverSuggestions(server))
                    .executes(ctx -> maintenance(server, ctx,
                        StringArgumentType.getString(ctx, "server"), false)))))
        .then(PluginCommand.create(server))
        .then(LinkCommand.create(server))
        .then(BrigadierCommand.literalArgumentBuilder("via")
            .executes(ctx -> usage(ctx, "/paperproxy via install|update"))
            .requires(source -> source.getPermissionValue("paperproxy.command.via")
                == Tristate.TRUE)
            .then(BrigadierCommand.literalArgumentBuilder("install")
                .executes(ctx -> via(server, ctx, false, false))
                .then(BrigadierCommand.literalArgumentBuilder("with-rewind")
                    .executes(ctx -> via(server, ctx, false, true))))
            .then(BrigadierCommand.literalArgumentBuilder("update")
                .executes(ctx -> via(server, ctx, true, false))))
        .then(BrigadierCommand.literalArgumentBuilder("update")
            .requires(source -> source.getPermissionValue("paperproxy.update") == Tristate.TRUE)
            .executes(ctx -> {
              final var checker = server.getUpdateChecker();
              final var release = checker.available();
              if (release == null) {
                ctx.getSource().sendMessage(Component.translatable("paperproxy.update.none"));
              } else if (checker.downloaded() != null) {
                ctx.getSource().sendMessage(Component.translatable(
                    "paperproxy.update.downloaded"));
              } else {
                final var source = ctx.getSource();
                source.sendMessage(checker.notice(release));
                source.sendMessage(Component.translatable("paperproxy.update.installing"));
                checker.install().thenAccept(problem -> source.sendMessage(problem == null
                    ? Component.translatable("paperproxy.update.downloaded")
                    : Component.translatable("paperproxy.update.failed",
                        Argument.string("reason", problem))));
              }
              return Command.SINGLE_SUCCESS;
            }))
        .then(BrigadierCommand.literalArgumentBuilder("servers")
            .requires(source -> source.getPermissionValue("paperproxy.command.servers")
                == Tristate.TRUE)
            .executes(ctx -> servers(server, ctx)))
        .then(BrigadierCommand.literalArgumentBuilder("restart")
            .executes(ctx -> usage(ctx, "/paperproxy restart <seconds> [reason] | cancel"))
            .requires(source -> source.getPermissionValue("paperproxy.command.restart")
                == Tristate.TRUE)
            .then(BrigadierCommand.literalArgumentBuilder("cancel").executes(ctx -> {
              final boolean cancelled = server.getRestartScheduler().cancel();
              ctx.getSource().sendMessage(Component.translatable(cancelled
                  ? "paperproxy.restart.cancelled" : "paperproxy.restart.none"));
              if (cancelled) {
                server.getDiscordWebhook().send(DiscordWebhook.Kind.RESTART,
                    "paperproxy.discord.restart-cancelled");
              }
              return Command.SINGLE_SUCCESS;
            }))
            .then(BrigadierCommand.requiredArgumentBuilder("seconds",
                    IntegerArgumentType.integer(1, 86_400))
                .executes(ctx -> restart(server, ctx, ""))
                .then(BrigadierCommand.requiredArgumentBuilder("reason",
                        StringArgumentType.greedyString())
                    .executes(ctx -> restart(server, ctx,
                        StringArgumentType.getString(ctx, "reason"))))))
        .then(BrigadierCommand.literalArgumentBuilder("network")
            .requires(source -> source.getPermissionValue("paperproxy.command.servers")
                == Tristate.TRUE)
            .executes(ctx -> network(server, ctx)))
        .then(BrigadierCommand.literalArgumentBuilder("paperguard")
            .executes(ctx -> usage(ctx, "paperproxy paperguard key <server> | rotate"))
            // Keys are secrets: console only, never shown in game.
            .requires(source -> source instanceof ConsoleCommandSource)
            .then(BrigadierCommand.literalArgumentBuilder("key")
            .executes(ctx -> usage(ctx, "paperproxy paperguard key <server>"))
                .then(BrigadierCommand.requiredArgumentBuilder("server",
                        StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                      server.getAllServers().forEach(s ->
                          builder.suggest(s.getServerInfo().getName()));
                      return builder.buildFuture();
                    })
                    .executes(ctx -> paperGuardKey(server, ctx))))
            .then(BrigadierCommand.literalArgumentBuilder("rotate")
            .executes(ctx -> usage(ctx, "paperproxy paperguard rotate confirm"))
                .then(BrigadierCommand.literalArgumentBuilder("confirm")
                    .executes(ctx -> paperGuardRotate(server, ctx)))
                .executes(ctx -> {
                  ctx.getSource().sendMessage(Component.translatable(
                      "paperproxy.paperguard.rotate-confirm"));
                  return Command.SINGLE_SUCCESS;
                })))
        .build());
  }

  private static int info(final VelocityServer server, final CommandContext<CommandSource> ctx) {
    final CommandSource source = ctx.getSource();
    final ProxyVersion version = server.getVersion();

    source.sendMessage(Component.text()
        .content(version.getName() + " ")
        .decoration(TextDecoration.BOLD, true)
        .color(BRAND_COLOR)
        .append(Component.text(PaperProxyBranding.version()).decoration(TextDecoration.BOLD, false))
        .hoverEvent(Component.translatable("velocity.command.version-offer-copy-version"))
        .clickEvent(ClickEvent.copyToClipboard(version.getName() + " " + PaperProxyBranding.version()))
        .build());
    source.sendMessage(Component.translatable("paperproxy.command.disclaimer"));
    source.sendMessage(Component.translatable("paperproxy.command.based-on",
        Argument.string("velocity_version", PaperProxyBranding.velocityVersion())));
    source.sendMessage(Component.text()
        .append(link("GitHub", PaperProxyBranding.REPOSITORY_URL))
        .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
        .append(link("Discord", PaperProxyBranding.DISCORD_URL))
        .build());
    return Command.SINGLE_SUCCESS;
  }

  static com.mojang.brigadier.suggestion.SuggestionProvider<CommandSource> serverSuggestions(
      final VelocityServer server) {
    return (ctx, builder) -> {
      server.getAllServers().forEach(s -> builder.suggest(s.getServerInfo().getName()));
      return builder.buildFuture();
    };
  }

  private static int via(final VelocityServer server, final CommandContext<CommandSource> ctx,
                         final boolean update, final boolean rewind) {
    final CommandSource source = ctx.getSource();
    source.sendMessage(Component.translatable("paperproxy.via.working"));
    new ViaInstaller(server).run(update, rewind).thenAccept(lines -> {
      boolean changed = false;
      for (final ViaInstaller.Line line : lines) {
        source.sendMessage(Component.translatable(line.key(),
            Argument.string("plugin", line.project()),
            Argument.string("detail", String.valueOf(line.detail()))));
        changed |= line.key().endsWith("installed") || line.key().endsWith("updated");
      }
      if (changed) {
        source.sendMessage(Component.translatable("paperproxy.via.restart"));
      }
    });
    return Command.SINGLE_SUCCESS;
  }

  private static int maintenanceStatus(final VelocityServer server,
                                       final CommandContext<CommandSource> ctx) {
    final var values = server.getPaperProxyConfig().values();
    ctx.getSource().sendMessage(Component.translatable("paperproxy.maintenance.status",
        Argument.string("state", values.maintenance() ? "on" : "off"),
        Argument.string("servers", values.maintenanceServers().isEmpty() ? "-"
            : String.join(", ", values.maintenanceServers()))));
    return Command.SINGLE_SUCCESS;
  }

  private static int maintenance(final VelocityServer server,
                                 final CommandContext<CommandSource> ctx,
                                 final String target, final boolean enabled) {
    if (target != null && server.getServer(target).isEmpty()) {
      ctx.getSource().sendMessage(Component.translatable("velocity.command.server-does-not-exist",
          Component.text(target)));
      return 0;
    }
    try {
      server.getPaperProxyConfig().setMaintenance(target, enabled);
      server.getDiscordWebhook().send(DiscordWebhook.Kind.MAINTENANCE,
          "paperproxy.discord.maintenance-" + (enabled ? "on" : "off")
              + (target == null ? "" : "-server"), "server", target == null ? "" : target);
    } catch (final java.io.IOException e) {
      ctx.getSource().sendMessage(Component.text("Unable to save paperproxy.toml: "
          + e.getMessage(), NamedTextColor.RED));
      return 0;
    }
    server.getPingCache().clear();
    ctx.getSource().sendMessage(Component.translatable(enabled
            ? "paperproxy.maintenance.enabled" : "paperproxy.maintenance.disabled",
        Argument.string("target", target == null ? "network" : target)));
    return Command.SINGLE_SUCCESS;
  }

  private static int servers(final VelocityServer server,
                             final CommandContext<CommandSource> ctx) {
    final var values = server.getPaperProxyConfig().values();
    for (final var registered : server.getAllServers()) {
      final String name = registered.getServerInfo().getName();
      final String lower = name.toLowerCase(java.util.Locale.ROOT);
      final String state = values.maintenanceServers().contains(lower) ? "maintenance"
          : server.getHealthChecker().status(name).name().toLowerCase(java.util.Locale.ROOT);
      ctx.getSource().sendMessage(Component.translatable("paperproxy.servers.line",
          Argument.string("server", name),
          Argument.string("state", state),
          Argument.string("forwarding", server.getForwarding().modeFor(name).name()
              .toLowerCase(java.util.Locale.ROOT)),
          Argument.string("players", String.valueOf(registered.getPlayersConnected().size()))));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int network(final VelocityServer server,
                             final CommandContext<CommandSource> ctx) {
    final var sync = server.getNetworkSync();
    if (!sync.connected()) {
      ctx.getSource().sendMessage(Component.translatable("paperproxy.sync.disabled"));
      return 0;
    }
    ctx.getSource().sendMessage(Component.translatable("paperproxy.sync.total",
        Argument.string("players", String.valueOf(sync.networkPlayerCount())),
        Argument.string("proxies", String.valueOf(Math.max(1, sync.proxies().size())))));
    for (final var entry : sync.proxies().entrySet()) {
      final boolean self = entry.getKey().equals(sync.proxyId());
      ctx.getSource().sendMessage(Component.translatable("paperproxy.sync.line",
          Argument.string("proxy", entry.getKey() + (self ? " *" : "")),
          Argument.string("players", String.valueOf(self ? server.getPlayerCount()
              : entry.getValue()))));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int paperGuardKey(final VelocityServer server,
                                   final CommandContext<CommandSource> ctx) {
    final String name = StringArgumentType.getString(ctx, "server");
    if (server.getServer(name).isEmpty()) {
      ctx.getSource().sendMessage(Component.translatable("velocity.command.server-does-not-exist",
          Component.text(name)));
      return 0;
    }
    try {
      server.getForwarding().loadSecret();
    } catch (final java.io.IOException e) {
      ctx.getSource().sendMessage(Component.text("Unable to load paperguard.secret: "
          + e.getMessage(), NamedTextColor.RED));
      return 0;
    }
    ctx.getSource().sendMessage(Component.translatable("paperproxy.paperguard.key",
        Argument.string("server", name),
        Argument.string("key", server.getForwarding().serverKey(name))));
    return Command.SINGLE_SUCCESS;
  }

  private static int paperGuardRotate(final VelocityServer server,
                                      final CommandContext<CommandSource> ctx) {
    try {
      server.getForwarding().rotateSecret();
    } catch (final java.io.IOException e) {
      ctx.getSource().sendMessage(Component.text("Unable to write paperguard.secret: "
          + e.getMessage(), NamedTextColor.RED));
      return 0;
    }
    ctx.getSource().sendMessage(Component.translatable("paperproxy.paperguard.rotated"));
    return Command.SINGLE_SUCCESS;
  }

  private static int reload(final VelocityServer server, final CommandContext<CommandSource> ctx) {
    server.getPaperProxyConfig().load();
    server.getMotd().load();
    server.getPingCache().clear();
    server.getHealthChecker().start();
    java.util.concurrent.CompletableFuture.runAsync(server.getUpdateChecker()::check);
    final boolean success = server.getMessages().load();
    ctx.getSource().sendMessage(Component.translatable(success
        ? "paperproxy.command.reload-success" : "paperproxy.command.reload-failure"));
    return success ? Command.SINGLE_SUCCESS : 0;
  }

  private static Component link(final String label, final String url) {
    return Component.text()
        .content(label)
        .color(NamedTextColor.GREEN)
        .decoration(TextDecoration.UNDERLINED, true)
        .clickEvent(ClickEvent.openUrl(url))
        .build();
  }

  private static int restart(final VelocityServer server, final CommandContext<CommandSource> ctx,
                             final String reason) {
    final int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
    server.getRestartScheduler().schedule(seconds, reason);
    ctx.getSource().sendMessage(Component.translatable("paperproxy.restart.planned",
        Argument.string("seconds", String.valueOf(seconds))));
    return Command.SINGLE_SUCCESS;
  }

  private static int usage(final CommandContext<CommandSource> ctx, final String usage) {
    ctx.getSource().sendMessage(Component.translatable("paperproxy.usage",
        Argument.string("usage", usage)));
    return Command.SINGLE_SUCCESS;
  }
}
