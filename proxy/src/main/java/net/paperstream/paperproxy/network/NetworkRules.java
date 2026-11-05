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

package net.paperstream.paperproxy.network;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import net.paperstream.paperproxy.api.event.ClientVersionDeniedEvent;
import net.paperstream.paperproxy.config.PaperProxyConfig;
import net.paperstream.paperproxy.config.VersionRange;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Applies maintenance mode, per-server version rules and health checks to logins, server
 * switches and the server list.
 */
public final class NetworkRules {

  /** Permission to join and switch during maintenance. */
  public static final String MAINTENANCE_BYPASS = "paperproxy.maintenance.bypass";
  /** Permission to join when the network is full. */
  public static final String LIMIT_BYPASS = "paperproxy.limit.bypass";

  private final VelocityServer server;

  /**
   * Creates the rules.
   *
   * @param server the proxy
   */
  public NetworkRules(final VelocityServer server) {
    this.server = server;
  }

  private PaperProxyConfig.Values values() {
    return server.getPaperProxyConfig().values();
  }

  /**
   * Global maintenance and the player limit.
   *
   * @param event the event
   */
  @Subscribe(order = PostOrder.EARLY)
  public void onLogin(final LoginEvent event) {
    if (!event.getResult().isAllowed()) {
      return;
    }
    final Player player = event.getPlayer();
    if (values().maintenance() && !mayBypass(player)) {
      event.setResult(ResultedEvent.ComponentResult.denied(
          Component.translatable("paperproxy.maintenance.kick")));
      return;
    }
    // der Spieler zaehlt hier noch nicht mit
    final int max = values().maxPlayers();
    if (max > 0 && server.getPlayerCount() >= max && !player.hasPermission(LIMIT_BYPASS)) {
      event.setResult(ResultedEvent.ComponentResult.denied(Component.translatable(
          "paperproxy.limit.full", Argument.string("max", String.valueOf(max)))));
    }
  }

  /**
   * Picks a usable first server when the configured one is offline, in maintenance or does not
   * support the player's version.
   *
   * @param event the event
   */
  @Subscribe(order = PostOrder.LATE)
  public void onChooseInitialServer(final PlayerChooseInitialServerEvent event) {
    final Player player = event.getPlayer();
    final RegisteredServer chosen = event.getInitialServer().orElse(null);
    if (chosen != null) {
      // A group member stands for its whole group: use the emptiest usable member.
      final Optional<RegisteredServer> best = server.getServerGroups()
          .best(player, chosen.getServerInfo().getName(), null);
      if (best.isPresent()) {
        event.setInitialServer(best.get());
        return;
      }
      if (hardProblem(player, chosen) == null && values().queue().enabled()) {
        // Full or offline: the player waits on another server and is queued for this one.
        final RegisteredServer fallback = fallback(player, chosen).orElse(null);
        if (fallback != null) {
          event.setInitialServer(fallback);
          queueLater(player, chosen);
          return;
        }
      }
    }
    fallback(player, chosen).ifPresent(event::setInitialServer);
    // Nothing better found: keep the original choice and let the connect check explain why.
  }

  private Optional<RegisteredServer> fallback(final Player player,
                                              final @Nullable RegisteredServer exclude) {
    for (final String name : server.getConfiguration().getAttemptConnectionOrder()) {
      final Optional<RegisteredServer> candidate = server.getServerGroups()
          .best(player, name, exclude);
      if (candidate.isPresent()) {
        return candidate;
      }
    }
    return Optional.empty();
  }

  private void queueLater(final Player player, final RegisteredServer target) {
    // Messages sent before the player is on a server are lost, so wait a moment.
    server.getScheduler().buildTask(VelocityVirtualPlugin.INSTANCE, () -> {
      if (player.isActive()) {
        server.getServerQueue().enqueue(player, target);
      }
    }).delay(2, TimeUnit.SECONDS).schedule();
  }

  /**
   * Refuses switches to servers the player cannot use, with a message instead of an error.
   *
   * @param event the event
   */
  @Subscribe(order = PostOrder.EARLY)
  public void onServerPreConnect(final ServerPreConnectEvent event) {
    final RegisteredServer target = event.getResult().getServer().orElse(null);
    if (target == null) {
      return;
    }
    final Player player = event.getPlayer();
    final boolean switching = player.getCurrentServer().isPresent();
    final Component hard = hardProblem(player, target);
    if (hard != null) {
      deny(event, hard);
      return;
    }
    final String name = target.getServerInfo().getName();
    final boolean offline = !server.getHealthChecker().isOnline(name);
    final boolean full = server.getServerGroups().isFull(player, target);
    if (!offline && !full) {
      return;
    }
    if (server.getServerGroups().groupOf(name) != null) {
      final Optional<RegisteredServer> other = server.getServerGroups().best(player, name, target);
      if (other.isPresent()) {
        event.setResult(ServerPreConnectEvent.ServerResult.allowed(other.get()));
        return;
      }
    }
    final Component reason = offline
        ? Component.translatable("paperproxy.health.offline", Argument.string("server", name))
        : Component.translatable("paperproxy.queue.full", Argument.string("server", name));
    if (!values().queue().enabled()) {
      deny(event, reason);
      return;
    }
    if (switching) {
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      server.getServerQueue().enqueue(player, target);
      return;
    }
    final Optional<RegisteredServer> fallback = fallback(player, target);
    if (fallback.isPresent()) {
      event.setResult(ServerPreConnectEvent.ServerResult.allowed(fallback.get()));
      queueLater(player, target);
    } else {
      deny(event, reason);
    }
  }

  private static void deny(final ServerPreConnectEvent event, final Component reason) {
    event.setResult(ServerPreConnectEvent.ServerResult.denied());
    if (event.getPlayer().getCurrentServer().isPresent()) {
      event.getPlayer().sendMessage(reason);
    } else {
      event.getPlayer().disconnect(reason);
    }
  }

  /**
   * Shows maintenance and the player limit in the server list.
   *
   * @param event the event
   */
  @Subscribe(order = PostOrder.LATE)
  public void onPing(final ProxyPingEvent event) {
    final PaperProxyConfig.Values values = values();
    if (values.maxPlayers() > 0 && values.maxPlayersInPing()) {
      event.setPing(event.getPing().asBuilder().maximumPlayers(values.maxPlayers()).build());
    }
    if (!values.maintenance()) {
      return;
    }
    final ServerPing ping = event.getPing();
    final String label = LegacyComponentSerializer.legacySection().serialize(GlobalTranslator
        .render(Component.translatable("paperproxy.maintenance.version-label"), Locale.US));
    event.setPing(ping.asBuilder()
        // Server list responses are not translated by the proxy, so render them here.
        .description(GlobalTranslator.render(
            Component.translatable("paperproxy.maintenance.motd"), Locale.US))
        // An impossible protocol makes clients show the label in red instead of the player count.
        .version(new ServerPing.Version(-1, label))
        .build());
  }

  /**
   * Explains why a player cannot use a server.
   *
   * @param player the player
   * @param target the server
   * @return the message, or null if the player may connect
   */
  public @Nullable Component problem(final Player player, final RegisteredServer target) {
    final Component hard = hardProblem(player, target);
    if (hard != null) {
      return hard;
    }
    final String name = target.getServerInfo().getName();
    if (!server.getHealthChecker().isOnline(name)) {
      return Component.translatable("paperproxy.health.offline", Argument.string("server", name));
    }
    return null;
  }

  /**
   * Explains why a player may never use a server right now, ignoring whether it is online:
   * maintenance and version rules.
   *
   * @param player the player
   * @param target the server
   * @return the message, or null
   */
  public @Nullable Component hardProblem(final Player player, final RegisteredServer target) {
    final String name = target.getServerInfo().getName();
    final PaperProxyConfig.Values values = values();
    if (values.maintenanceServers().contains(name.toLowerCase(Locale.ROOT))
        && !mayBypass(player)) {
      return Component.translatable("paperproxy.maintenance.server",
          Argument.string("server", name));
    }
    final VersionRange range = values.versions().get(name.toLowerCase(Locale.ROOT));
    final ProtocolVersion version = player.getProtocolVersion();
    if (range != null && !range.contains(version)) {
      server.getEventManager().fireAndForget(new ClientVersionDeniedEvent(player, name,
          range.text()));
      return Component.translatable("paperproxy.versions.denied",
          Argument.string("server", name),
          Argument.string("versions", range.text()),
          Argument.string("client_version", version.getMostRecentSupportedVersion()));
    }
    return null;
  }

  private boolean mayBypass(final Player player) {
    final java.util.Set<String> whitelist = values().maintenanceWhitelist();
    return player.hasPermission(MAINTENANCE_BYPASS)
        || whitelist.contains(player.getUsername().toLowerCase(Locale.ROOT))
        || whitelist.contains(player.getUniqueId().toString());
  }
}
