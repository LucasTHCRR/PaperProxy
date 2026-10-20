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

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.proxy.server.PingOptions;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.PlayerInfoForwarding;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.translation.Argument;

/**
 * /paperproxy migrate check: after moving from Velocity or BungeeCord, shows per server what
 * still has to be set up. It pings every server and reads the last kick a server sent, because
 * a wrong forwarding setup shows up as exactly such a kick.
 */
final class MigrateCheck {

  private MigrateCheck() {
    throw new AssertionError();
  }

  static void run(final VelocityServer server, final CommandSource source) {
    final List<RegisteredServer> all = new ArrayList<>(server.getAllServers());
    final PingOptions options = PingOptions.builder()
        .timeout(Math.max(1000, server.getConfiguration().getConnectTimeout()),
            TimeUnit.MILLISECONDS)
        .build();
    final Map<String, Boolean> online = new ConcurrentHashMap<>();
    final CompletableFuture<?>[] pings = all.stream()
        .map(registered -> registered.ping(options).handle((ping, error) -> {
          online.put(registered.getServerInfo().getName(), error == null);
          return null;
        }))
        .toArray(CompletableFuture[]::new);
    CompletableFuture.allOf(pings)
        .whenComplete((ignored, error) -> report(server, source, all, online));
  }

  private static void report(final VelocityServer server, final CommandSource source,
                             final List<RegisteredServer> all, final Map<String, Boolean> online) {
    source.sendMessage(Component.translatable("paperproxy.migrate.header",
        Argument.string("count", String.valueOf(all.size()))));
    int problems = 0;
    final List<String> noForwarding = new ArrayList<>();
    for (final RegisteredServer registered : all) {
      final String name = registered.getServerInfo().getName();
      final boolean reachable = Boolean.TRUE.equals(online.get(name));
      final PlayerInfoForwarding mode = server.getForwarding().modeFor(name);
      source.sendMessage(Component.translatable("paperproxy.migrate.server",
          Argument.string("server", name),
          Argument.component("state", reachable
              ? Component.text("online", NamedTextColor.GREEN)
              : Component.text("offline", NamedTextColor.RED)),
          Argument.string("forwarding", mode.name().toLowerCase(Locale.ROOT))));
      if (mode == PlayerInfoForwarding.NONE) {
        noForwarding.add(name);
      }
      if (!reachable) {
        source.sendMessage(Component.translatable("paperproxy.migrate.offline"));
        ++problems;
      }
      final String kick = server.getProxyStats().lastKickReason(name);
      if (kick != null) {
        final String hint = hintFor(kick);
        if (hint != null) {
          source.sendMessage(Component.translatable(hint));
          ++problems;
        } else {
          source.sendMessage(Component.translatable("paperproxy.migrate.kick",
              Argument.string("reason", kick)));
        }
      }
    }
    if (!noForwarding.isEmpty()) {
      source.sendMessage(Component.translatable("paperproxy.migrate.forwarding-none",
          Argument.string("servers", String.join(", ", noForwarding))));
      ++problems;
    }
    final ProxyConfig config = server.getConfiguration();
    if (!config.isOnlineMode()) {
      source.sendMessage(Component.translatable("paperproxy.migrate.proxy-offline-mode"));
      ++problems;
    }
    if (problems == 0) {
      source.sendMessage(Component.translatable("paperproxy.migrate.ok"));
    }
  }

  /** Maps the kick texts of Paper, Spigot and Velocity's own checks to a hint. */
  private static String hintFor(final String kick) {
    final String k = kick.toLowerCase(Locale.ROOT);
    if (k.contains("unknown data in login hostname") || k.contains("enable bungeecord in spigot")) {
      return "paperproxy.migrate.legacy-off";
    }
    if (k.contains("enable it in your bungeecord config") || k.contains("ip forwarding")) {
      return "paperproxy.migrate.bungeecord";
    }
    if (k.contains("connect with velocity") || k.contains("requires you to connect")) {
      return "paperproxy.migrate.velocity-required";
    }
    if (k.contains("unable to verify player details") || k.contains("forwarding secret")) {
      return "paperproxy.migrate.secret";
    }
    if (k.contains("failed to verify username") || k.contains("invalid session")
        || k.contains("authentication servers")) {
      return "paperproxy.migrate.online-mode";
    }
    if (k.contains("outdated client") || k.contains("outdated server")
        || k.contains("incompatible client")) {
      return "paperproxy.migrate.outdated";
    }
    return null;
  }
}
