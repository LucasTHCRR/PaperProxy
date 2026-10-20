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
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Counters for the metrics endpoint: logins, refused logins, server switches with their
 * duration, and kicks from backends.
 */
public final class ProxyStats {

  /** Sum and count of server connect times for one server. */
  public static final class Timing {
    final LongAdder count = new LongAdder();
    final LongAdder millis = new LongAdder();
  }

  final LongAdder logins = new LongAdder();
  final LongAdder refusedPreLogin = new LongAdder();
  final LongAdder refusedLogin = new LongAdder();
  final Map<String, Timing> connects = new ConcurrentHashMap<>();
  final Map<String, LongAdder> kicks = new ConcurrentHashMap<>();
  private final Map<UUID, Long> pending = new ConcurrentHashMap<>();

  /**
   * Counts refused pre logins (bot protection, maintenance, versions).
   *
   * @param event the event
   */
  @Subscribe(order = PostOrder.LAST)
  public void onPreLogin(final PreLoginEvent event) {
    if (!event.getResult().isAllowed()) {
      refusedPreLogin.increment();
    }
  }

  /**
   * Counts logins and refused logins (bans, account limits).
   *
   * @param event the event
   */
  @Subscribe(order = PostOrder.LAST)
  public void onLogin(final LoginEvent event) {
    if (event.getResult().isAllowed()) {
      logins.increment();
    } else {
      refusedLogin.increment();
    }
  }

  /**
   * Starts timing a server connection.
   *
   * @param event the event
   */
  @Subscribe(order = PostOrder.LAST)
  public void onPreConnect(final ServerPreConnectEvent event) {
    if (event.getResult().isAllowed()) {
      pending.put(event.getPlayer().getUniqueId(), System.nanoTime());
    }
  }

  /**
   * Finishes timing a server connection.
   *
   * @param event the event
   */
  @Subscribe
  public void onConnected(final ServerConnectedEvent event) {
    final Long start = pending.remove(event.getPlayer().getUniqueId());
    if (start == null) {
      return;
    }
    final Timing timing = connects.computeIfAbsent(event.getServer().getServerInfo().getName(),
        k -> new Timing());
    timing.count.increment();
    timing.millis.add((System.nanoTime() - start) / 1_000_000);
  }

  /**
   * Counts kicks from backend servers.
   *
   * @param event the event
   */
  @Subscribe
  public void onKicked(final KickedFromServerEvent event) {
    pending.remove(event.getPlayer().getUniqueId());
    kicks.computeIfAbsent(event.getServer().getServerInfo().getName(), k -> new LongAdder())
        .increment();
    event.getServerKickReason().ifPresent(reason -> lastKickReason.put(
        event.getServer().getServerInfo().getName(),
        PlainTextComponentSerializer.plainText().serialize(reason)));
  }

  private final Map<String, String> lastKickReason = new ConcurrentHashMap<>();

  /**
   * Returns the text of the last kick from a backend server, used by /paperproxy migrate check.
   *
   * @param server the server name
   * @return the kick text, or null if nobody was kicked from it since the start
   */
  public String lastKickReason(final String server) {
    return lastKickReason.get(server);
  }

  /**
   * Forgets unfinished timings.
   *
   * @param event the event
   */
  @Subscribe
  public void onDisconnect(final DisconnectEvent event) {
    pending.remove(event.getPlayer().getUniqueId());
  }
}
