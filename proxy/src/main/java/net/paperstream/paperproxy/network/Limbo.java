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

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import com.velocitypowered.proxy.protocol.StateRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Keeps players connected when their server restarts or crashes and no other server can take
 * them. They stay in the last world they saw, see a countdown and are sent back as soon as the
 * server (or another member of its group) is reachable again.
 */
public final class Limbo {

  private static final Logger logger = LogManager.getLogger(Limbo.class);

  private record Waiting(String server, long since, Component reason) {
  }

  private final VelocityServer server;
  private final Map<UUID, Waiting> waiting = new ConcurrentHashMap<>();

  /**
   * Creates the limbo.
   *
   * @param server the proxy
   */
  public Limbo(final VelocityServer server) {
    this.server = server;
  }

  /**
   * Starts the reconnect loop.
   */
  public void start() {
    server.getScheduler().buildTask(VelocityVirtualPlugin.INSTANCE, this::tick)
        .repeat(1, TimeUnit.SECONDS).schedule();
  }

  /**
   * Called by the proxy before it would disconnect a kicked player.
   *
   * @param player the player
   * @param event the kick event
   * @param reason the reason the player would be disconnected with
   * @return true if the player stays in the limbo instead
   */
  public boolean tryEnter(final ConnectedPlayer player, final KickedFromServerEvent event,
                          final Component reason) {
    final var settings = server.getPaperProxyConfig().values().limbo();
    if (!settings.enabled() || event.kickedDuringServerConnect()
        || player.getConnection().getState() != StateRegistry.PLAY
        || player.getConnectedServer() != null || !player.isActive()) {
      return false;
    }
    final boolean announced = server.getServerLinks()
        .announcedStop(event.getServer().getServerInfo().getName());
    if (!announced && event.getServerKickReason().isPresent()
        && !isShutdownMessage(event.getServerKickReason().get(), settings.kickMessages())) {
      // A real kick (ban, AFK, plugin): respect it.
      return false;
    }
    final String name = event.getServer().getServerInfo().getName();
    waiting.put(player.getUniqueId(), new Waiting(name, System.currentTimeMillis(), reason));
    logger.info("{} waits in the limbo for {}", player.getUsername(), name);
    player.sendMessage(Component.translatable("paperproxy.limbo.entered",
        Argument.string("server", name)));
    return true;
  }

  /**
   * Tells whether a player is waiting in the limbo.
   *
   * @param player the player's UUID
   * @return true while waiting
   */
  public boolean isWaiting(final UUID player) {
    return waiting.containsKey(player);
  }

  /**
   * Returns how many players wait in the limbo.
   *
   * @return the number of players
   */
  public int size() {
    return waiting.size();
  }

  static boolean isShutdownMessage(final Component message,
                                   final java.util.List<String> patterns) {
    if (message instanceof TranslatableComponent translatable
        && translatable.key().equals("multiplayer.disconnect.server_shutdown")) {
      return true;
    }
    final String plain = PlainTextComponentSerializer.plainText().serialize(message)
        .toLowerCase(Locale.ROOT);
    for (final String pattern : patterns) {
      if (!pattern.isEmpty() && plain.contains(pattern.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  private void tick() {
    if (waiting.isEmpty()) {
      return;
    }
    final var settings = server.getPaperProxyConfig().values().limbo();
    final long now = System.currentTimeMillis();
    for (final Map.Entry<UUID, Waiting> entry : waiting.entrySet()) {
      final Optional<ConnectedPlayer> player = server.getPlayer(entry.getKey())
          .map(ConnectedPlayer.class::cast);
      final Waiting state = entry.getValue();
      if (player.isEmpty() || !player.get().isActive()) {
        waiting.remove(entry.getKey());
        continue;
      }
      final ConnectedPlayer p = player.get();
      if (p.getConnectedServer() != null) {
        // Left the limbo through /server, /hub or a plugin.
        waiting.remove(entry.getKey());
        p.clearTitle();
        continue;
      }
      final long waited = (now - state.since()) / 1000;
      if (waited >= settings.maxWaitSeconds()) {
        waiting.remove(entry.getKey());
        p.disconnect(state.reason());
        continue;
      }
      // Keep the client from timing out while no backend sends keep alives.
      p.sendKeepAlive();
      final Optional<RegisteredServer> target = target(p, state.server());
      if (target.isPresent() && waited >= 2) {
        if (p.getConnectionInFlight() == null) {
          p.createConnectionRequest(target.get()).connect().thenAccept(result -> {
            if (result.isSuccessful()) {
              waiting.remove(entry.getKey());
              logger.info("{} left the limbo to {}", p.getUsername(),
                  target.get().getServerInfo().getName());
            }
          });
        }
        continue;
      }
      p.showTitle(Title.title(Component.translatable("paperproxy.limbo.title"),
          Component.translatable("paperproxy.limbo.subtitle",
              Argument.string("server", state.server()),
              Argument.string("seconds", String.valueOf(waited))),
          Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ZERO)));
    }
  }

  private Optional<RegisteredServer> target(final ConnectedPlayer player, final String name) {
    final HealthChecker health = server.getHealthChecker();
    final Optional<RegisteredServer> same = server.getServer(name);
    if (same.isPresent() && health.isOnline(name)
        && server.getNetworkRules().problem(player, same.get()) == null) {
      return same;
    }
    // Another member of the same group is fine too.
    if (server.getServerGroups().groupOf(name) != null) {
      return server.getServerGroups().best(player, name, null)
          .filter(candidate -> health.isOnline(candidate.getServerInfo().getName()));
    }
    return Optional.empty();
  }

  /**
   * Forgets players who leave.
   *
   * @param event the event
   */
  @Subscribe
  public void onDisconnect(final DisconnectEvent event) {
    waiting.remove(event.getPlayer().getUniqueId());
    server.getServerLinks().forget(event.getPlayer().getUniqueId());
  }
}
