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
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.paperstream.paperproxy.config.PaperProxyConfig;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Queues for full or offline servers. Players wait on their current server, see their position
 * in the action bar and are connected as soon as there is room. Players moved away because
 * their server restarted are sent back once it is online again.
 */
public final class ServerQueue {

  /** Prefix of the priority permission, followed by a number from 0 to 100. */
  public static final String PRIORITY_PREFIX = "paperproxy.queue.priority.";
  /** A player kicked by a restart must see the server go offline within this time. */
  private static final long RESTART_DETECT_MILLIS = 60_000;
  /** Players are not sent back after this time. */
  private static final long REJOIN_MAX_MILLIS = 5 * 60_000;

  private record Entry(UUID player, String server, int priority, long joined, long order) {
  }

  private record Rejoin(String server, long kickedAt) {
  }

  private final VelocityServer server;
  /** Server name (lower case) to its waiting players, best first. Guarded by this. */
  private final Map<String, List<Entry>> queues = new HashMap<>();
  private final Map<UUID, Rejoin> rejoins = new ConcurrentHashMap<>();
  // Fehlversuche pro Spieler: {Anzahl Ablehnungen durch den Server, naechster Versuch in ms}
  private final Map<UUID, long[]> retries = new ConcurrentHashMap<>();
  private static final int MAX_REFUSALS = 3;
  private static final long RETRY_MILLIS = 5_000;
  private long counter;
  private volatile @Nullable ScheduledTask task;
  private volatile int interval;

  /**
   * Creates the queue.
   *
   * @param server the proxy
   */
  public ServerQueue(final VelocityServer server) {
    this.server = server;
  }

  private PaperProxyConfig.Queue settings() {
    return server.getPaperProxyConfig().values().queue();
  }

  /**
   * Starts or restarts the queue ticks with the current settings.
   */
  public synchronized void start() {
    final PaperProxyConfig.Queue settings = settings();
    final ScheduledTask current = task;
    if (current != null && interval == settings.intervalSeconds()) {
      return;
    }
    if (current != null) {
      current.cancel();
    }
    interval = settings.intervalSeconds();
    task = server.getScheduler().buildTask(VelocityVirtualPlugin.INSTANCE, this::tick)
        .repeat(interval, TimeUnit.SECONDS).schedule();
  }

  /**
   * Puts a player into the queue of a server, or tells them their position if they already
   * wait there.
   *
   * @param player the player
   * @param target the server
   */
  public void enqueue(final Player player, final RegisteredServer target) {
    enqueue(player, target, true);
  }

  private void enqueue(final Player player, final RegisteredServer target,
                       final boolean announce) {
    final String name = target.getServerInfo().getName().toLowerCase(Locale.ROOT);
    final int position;
    final int total;
    synchronized (this) {
      final Entry existing = find(player.getUniqueId());
      if (existing == null || !existing.server().equals(name)) {
        if (existing != null) {
          queues.get(existing.server()).remove(existing);
        }
        final List<Entry> queue = queues.computeIfAbsent(name, k -> new ArrayList<>());
        queue.add(new Entry(player.getUniqueId(), name, priority(player),
            System.currentTimeMillis(), counter++));
        queue.sort(Comparator.comparingInt(Entry::priority).reversed()
            .thenComparingLong(Entry::order));
      }
      final List<Entry> queue = queues.get(name);
      position = indexOf(queue, player.getUniqueId()) + 1;
      total = queue.size();
    }
    if (!announce) {
      return;
    }
    player.sendMessage(Component.translatable("paperproxy.queue.joined",
        Argument.string("server", target.getServerInfo().getName()),
        Argument.string("position", String.valueOf(position)),
        Argument.string("total", String.valueOf(total))));
  }

  /**
   * Removes a player from any queue.
   *
   * @param player the player
   * @return the server they waited for, or null
   */
  public synchronized @Nullable String leave(final UUID player) {
    final Entry entry = find(player);
    if (entry == null) {
      return null;
    }
    final List<Entry> queue = queues.get(entry.server());
    queue.remove(entry);
    if (queue.isEmpty()) {
      queues.remove(entry.server());
    }
    return entry.server();
  }

  /**
   * Returns a player's place in a queue.
   *
   * @param player the player
   * @return server name, position and queue size, or empty
   */
  public synchronized Optional<Object[]> position(final UUID player) {
    final Entry entry = find(player);
    if (entry == null) {
      return Optional.empty();
    }
    final List<Entry> queue = queues.get(entry.server());
    return Optional.of(new Object[] {entry.server(), indexOf(queue, player) + 1, queue.size()});
  }

  /**
   * Returns how many players wait for a server.
   *
   * @param serverName the server
   * @return the queue length
   */
  public synchronized int size(final String serverName) {
    final List<Entry> queue = queues.get(serverName.toLowerCase(Locale.ROOT));
    return queue == null ? 0 : queue.size();
  }

  private @Nullable Entry find(final UUID player) {
    for (final List<Entry> queue : queues.values()) {
      for (final Entry entry : queue) {
        if (entry.player().equals(player)) {
          return entry;
        }
      }
    }
    return null;
  }

  private static int indexOf(final List<Entry> queue, final UUID player) {
    for (int i = 0; i < queue.size(); i++) {
      if (queue.get(i).player().equals(player)) {
        return i;
      }
    }
    return -1;
  }

  private static int priority(final Player player) {
    for (int i = 100; i > 0; i--) {
      if (player.hasPermission(PRIORITY_PREFIX + i)) {
        return i;
      }
    }
    return 0;
  }

  void tick() {
    final long now = System.currentTimeMillis();
    final long timeout = TimeUnit.MINUTES.toMillis(settings().timeoutMinutes());
    final List<Runnable> actions = new ArrayList<>();
    synchronized (this) {
      for (final Iterator<Map.Entry<String, List<Entry>>> it = queues.entrySet().iterator();
           it.hasNext(); ) {
        final Map.Entry<String, List<Entry>> mapEntry = it.next();
        final List<Entry> queue = mapEntry.getValue();
        queue.removeIf(entry -> {
          if (now - entry.joined() < timeout) {
            return false;
          }
          server.getPlayer(entry.player()).ifPresent(player -> actions.add(() ->
              player.sendMessage(Component.translatable("paperproxy.queue.timeout",
                  Argument.string("server", entry.server())))));
          return true;
        });
        queue.removeIf(entry -> server.getPlayer(entry.player()).isEmpty());
        final Optional<RegisteredServer> target = server.getServer(mapEntry.getKey());
        if (target.isEmpty()) {
          queue.clear();
        } else {
          admit(target.get(), queue, actions);
        }
        if (queue.isEmpty()) {
          it.remove();
        }
      }
    }
    actions.forEach(Runnable::run);
    rejoins(now);
  }

  /** Connects waiting players while the server has room. Called with the lock held. */
  private void admit(final RegisteredServer target, final List<Entry> queue,
                     final List<Runnable> actions) {
    final String name = target.getServerInfo().getName();
    final boolean online = server.getHealthChecker().isOnline(name);
    final int max = server.getHealthChecker().maxPlayers(name);
    int free = !online ? 0 : max <= 0 ? Integer.MAX_VALUE
        : max - target.getPlayersConnected().size();
    for (final Iterator<Entry> it = queue.iterator(); it.hasNext(); ) {
      final Entry entry = it.next();
      final Player player = server.getPlayer(entry.player()).orElse(null);
      if (player == null) {
        it.remove();
        continue;
      }
      final long[] retry = retries.get(entry.player());
      if (retry != null && retry[1] > System.currentTimeMillis()) {
        continue;
      }
      if (free > 0 && server.getNetworkRules().hardProblem(player, target) == null) {
        free--;
        it.remove();
        actions.add(() -> connect(player, target));
      }
    }
    int position = 1;
    for (final Entry entry : queue) {
      final int pos = position++;
      server.getPlayer(entry.player()).ifPresent(player -> actions.add(() ->
          player.sendActionBar(Component.translatable("paperproxy.queue.position",
              Argument.string("server", name),
              Argument.string("position", String.valueOf(pos)),
              Argument.string("total", String.valueOf(queue.size()))))));
    }
  }

  private void connect(final Player player, final RegisteredServer target) {
    final UUID id = player.getUniqueId();
    if (!retries.containsKey(id)) {
      // Only announced on the first try, retries stay quiet.
      player.sendMessage(Component.translatable("paperproxy.queue.connecting",
          Argument.string("server", target.getServerInfo().getName())));
    }
    player.createConnectionRequest(target).connect().whenComplete((result, error) -> {
      if (error == null && result != null && result.isSuccessful()) {
        retries.remove(id);
        return;
      }
      final boolean already = result != null
          && result.getStatus() == ConnectionRequestBuilder.Status.ALREADY_CONNECTED;
      if (already || !player.isActive()) {
        retries.remove(id);
        return;
      }
      // The server answered and turned the player away (whitelist, ban, full): a few spaced
      // retries, then give up instead of trying again every second.
      final boolean refused = result != null
          && result.getStatus() == ConnectionRequestBuilder.Status.SERVER_DISCONNECTED;
      final long[] retry = retries.computeIfAbsent(id, k -> new long[2]);
      if (refused && ++retry[0] >= MAX_REFUSALS) {
        retries.remove(id);
        player.sendMessage(Component.translatable("paperproxy.queue.refused",
            Argument.string("server", target.getServerInfo().getName())));
        return;
      }
      retry[1] = System.currentTimeMillis() + RETRY_MILLIS * Math.max(1, retry[0]);
      enqueue(player, target, false);
    });
  }

  private void rejoins(final long now) {
    if (!settings().rejoinAfterRestart()) {
      rejoins.clear();
      return;
    }
    for (final Iterator<Map.Entry<UUID, Rejoin>> it = rejoins.entrySet().iterator();
         it.hasNext(); ) {
      final Map.Entry<UUID, Rejoin> entry = it.next();
      final Rejoin rejoin = entry.getValue();
      final Player player = server.getPlayer(entry.getKey()).orElse(null);
      final Optional<RegisteredServer> target = server.getServer(rejoin.server());
      final long offlineAt = server.getHealthChecker().lastOffline(rejoin.server());
      final boolean restarted = offlineAt >= rejoin.kickedAt() - 5_000;
      if (player == null || target.isEmpty() || now - rejoin.kickedAt() > REJOIN_MAX_MILLIS
          || (!restarted && now - rejoin.kickedAt() > RESTART_DETECT_MILLIS)) {
        it.remove();
        continue;
      }
      if (restarted && server.getHealthChecker().isOnline(rejoin.server())
          && server.getHealthChecker().status(rejoin.server()) == HealthChecker.Status.ONLINE) {
        it.remove();
        player.sendMessage(Component.translatable("paperproxy.queue.rejoin",
            Argument.string("server", target.get().getServerInfo().getName())));
        enqueue(player, target.get(), false);
      }
    }
  }

  /**
   * Forgets players that leave the network.
   *
   * @param event the event
   */
  @Subscribe
  public void onDisconnect(final DisconnectEvent event) {
    leave(event.getPlayer().getUniqueId());
    rejoins.remove(event.getPlayer().getUniqueId());
    retries.remove(event.getPlayer().getUniqueId());
  }

  /**
   * Removes a player from the queue once they reached the server some other way.
   *
   * @param event the event
   */
  @Subscribe
  public void onConnected(final ServerConnectedEvent event) {
    final Entry entry;
    synchronized (this) {
      entry = find(event.getPlayer().getUniqueId());
    }
    if (entry != null && entry.server().equalsIgnoreCase(
        event.getServer().getServerInfo().getName())) {
      leave(event.getPlayer().getUniqueId());
    }
  }

  /**
   * Remembers players kicked from a running server; if the server turns out to restart, they
   * are sent back afterwards.
   *
   * @param event the event
   */
  @Subscribe
  public void onKicked(final KickedFromServerEvent event) {
    if (event.kickedDuringServerConnect() || !settings().rejoinAfterRestart()) {
      return;
    }
    rejoins.put(event.getPlayer().getUniqueId(),
        new Rejoin(event.getServer().getServerInfo().getName(), System.currentTimeMillis()));
  }
}
