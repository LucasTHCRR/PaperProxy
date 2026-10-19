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

package net.paperstream.paperproxy.link;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The PaperStream backends paired with this proxy: their keys, open links, pending pairing
 * codes, announced restarts and rolling restarts.
 */
public final class ServerLinks {

  /** Wrong codes in a row after which every pending code is cancelled. */
  static final int MAX_FAILED_ATTEMPTS = 3;
  private static final String FILE = "linked-servers.json";
  private static final long ANNOUNCED_MILLIS = 60_000L;
  private static final Logger logger = LogManager.getLogger(ServerLinks.class);
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private final VelocityServer server;
  private final Path file;
  private final Map<String, Linked> links = new ConcurrentHashMap<>();
  private final Map<String, Pending> pending = new ConcurrentHashMap<>();
  private final Map<String, LinkHandler> sessions = new ConcurrentHashMap<>();
  private final Map<String, Long> announced = new ConcurrentHashMap<>();
  private final Map<UUID, Move> moved = new ConcurrentHashMap<>();
  private final Map<Integer, Command> commands = new ConcurrentHashMap<>();
  private final java.util.concurrent.atomic.AtomicBoolean restarting =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final AtomicInteger failedAttempts = new AtomicInteger();
  private final AtomicInteger nextCommand = new AtomicInteger();

  /**
   * A paired backend.
   *
   * @param name the server name on the proxy
   * @param host the address the backend linked from
   * @param port the game port of the backend
   * @param group the group the server was paired into, or null
   * @param key the link key
   * @param registered whether the proxy registered the server itself (not in velocity.toml)
   */
  public record Linked(String name, String host, int port, @Nullable String group, byte[] key,
                       boolean registered) {
  }

  /**
   * A pairing code waiting to be used.
   *
   * @param name the server name
   * @param group the group, or null
   * @param code the code
   * @param expires when it stops working
   */
  record Pending(String name, @Nullable String group, String code, long expires) {
  }

  private record Move(String from, String to) {
  }

  private record Command(String server, CompletableFuture<List<String>> future) {
  }

  /**
   * Creates the registry.
   *
   * @param server the proxy
   * @param directory the proxy directory
   */
  public ServerLinks(final VelocityServer server, final Path directory) {
    this.server = server;
    this.file = directory.resolve(FILE);
  }

  /**
   * Loads the paired servers and registers those not in velocity.toml.
   */
  public void load() {
    if (!Files.exists(file)) {
      return;
    }
    try {
      final JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
      for (final Map.Entry<String, JsonElement> entry
          : root.getAsJsonObject("servers").entrySet()) {
        final JsonObject json = entry.getValue().getAsJsonObject();
        final Linked linked = new Linked(entry.getKey(), json.get("host").getAsString(),
            json.get("port").getAsInt(),
            json.has("group") ? json.get("group").getAsString() : null,
            LinkProtocol.unb64(json.get("key").getAsString()),
            json.get("registered").getAsBoolean());
        links.put(key(linked.name()), linked);
        register(linked);
      }
      logger.info("Loaded {} linked PaperStream server(s)", links.size());
    } catch (IOException | RuntimeException e) {
      logger.error("Could not read {}, linked servers are not available", FILE, e);
    }
  }

  private void register(final Linked linked) {
    if (linked.registered() && server.getServer(linked.name()).isEmpty()) {
      server.registerServer(new ServerInfo(linked.name(),
          new InetSocketAddress(linked.host(), linked.port())));
    }
  }

  private synchronized void save() {
    final JsonObject servers = new JsonObject();
    for (final Linked linked : links.values()) {
      final JsonObject json = new JsonObject();
      json.addProperty("host", linked.host());
      json.addProperty("port", linked.port());
      if (linked.group() != null) {
        json.addProperty("group", linked.group());
      }
      json.addProperty("key", LinkProtocol.b64(linked.key()));
      json.addProperty("registered", linked.registered());
      servers.add(linked.name(), json);
    }
    final JsonObject root = new JsonObject();
    root.add("servers", servers);
    final Path temp = file.resolveSibling(FILE + ".tmp");
    try {
      Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
      try {
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
        // Not a POSIX file system.
      }
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      logger.error("Could not save {}", FILE, e);
    }
  }

  private static String key(final String name) {
    return name.toLowerCase(Locale.ROOT);
  }

  /**
   * Starts a pairing.
   *
   * @param name the server name the backend gets
   * @param group the group to add it to, or null
   * @return the code to type on the backend
   */
  public String startPairing(final String name, final @Nullable String group) {
    final String code = LinkProtocol.newCode();
    pending.put(key(name), new Pending(name,
        group == null ? null : group.toLowerCase(Locale.ROOT), code,
        System.currentTimeMillis() + LinkProtocol.CODE_TTL_MILLIS));
    failedAttempts.set(0);
    return code;
  }

  /**
   * The pairing codes that still work.
   *
   * @return the pending pairings
   */
  List<Pending> pendingPairings() {
    final long now = System.currentTimeMillis();
    pending.values().removeIf(p -> p.expires() < now);
    return List.copyOf(pending.values());
  }

  /**
   * Counts a wrong code. Too many cancel every pending code, so it cannot be guessed.
   */
  void failedPairing() {
    if (failedAttempts.incrementAndGet() >= MAX_FAILED_ATTEMPTS) {
      pending.clear();
      failedAttempts.set(0);
      logger.warn("PaperStream link: {} wrong pairing codes, all pending codes were cancelled",
          MAX_FAILED_ATTEMPTS);
    }
  }

  /**
   * Uses up a pairing code once it was proven.
   *
   * @param pairing the pairing
   * @return the name the server gets on this proxy, or null if someone else used it first
   */
  @Nullable String claim(final Pending pairing) {
    if (!pending.remove(key(pairing.name()), pairing)) {
      return null;
    }
    return server.getServer(pairing.name()).map(s -> s.getServerInfo().getName())
        .orElse(pairing.name());
  }

  /**
   * Finishes a pairing after the code was proven and the backend saved it.
   *
   * @param pairing the pairing
   * @param host the backend address
   * @param port the backend game port
   * @param linkKey the new link key
   * @return the server
   */
  Linked completePairing(final Pending pairing, final String host, final int port,
                         final byte[] linkKey) {
    pending.remove(key(pairing.name()));
    final Optional<RegisteredServer> existing = server.getServer(pairing.name());
    final Linked old = links.get(key(pairing.name()));
    final boolean registered = existing.isEmpty() || (old != null && old.registered());
    final String name = existing.map(s -> s.getServerInfo().getName()).orElse(pairing.name());
    if (registered && existing.isPresent() && !existing.get().getServerInfo().getAddress()
        .equals(new InetSocketAddress(host, port))) {
      server.unregisterServer(existing.get().getServerInfo());
    }
    final Linked linked = new Linked(name, host, port, pairing.group(), linkKey, registered);
    links.put(key(name), linked);
    register(linked);
    save();
    final LinkHandler session = sessions.remove(key(name));
    if (session != null) {
      session.close();
    }
    logger.info("PaperStream server {} paired from {}:{}{}", name, host, port,
        pairing.group() == null ? "" : " into group " + pairing.group());
    return linked;
  }

  /**
   * Removes a pairing.
   *
   * @param name the server
   * @return whether it was paired
   */
  public boolean unlink(final String name) {
    final Linked linked = links.remove(key(name));
    if (linked == null) {
      return false;
    }
    save();
    final LinkHandler session = sessions.remove(key(name));
    if (session != null) {
      final JsonObject message = new JsonObject();
      message.addProperty("op", "unlinked");
      session.send(message);
      session.close();
    }
    if (linked.registered()) {
      server.getServer(linked.name()).ifPresent(s -> server.unregisterServer(s.getServerInfo()));
    }
    logger.info("PaperStream server {} unlinked", linked.name());
    return true;
  }

  /**
   * Returns a paired server.
   *
   * @param name the server name
   * @return the server, or null
   */
  public @Nullable Linked linked(final String name) {
    return links.get(key(name));
  }

  /**
   * All paired servers.
   *
   * @return the servers
   */
  public List<Linked> all() {
    return List.copyOf(links.values());
  }

  /**
   * Whether a server is paired, so the proxy forwards to it with modern forwarding.
   *
   * @param name the server name
   * @return whether it is paired
   */
  public boolean isLinked(final String name) {
    return links.containsKey(key(name));
  }

  /**
   * Whether the link of a server is open.
   *
   * @param name the server name
   * @return whether it is connected
   */
  public boolean isConnected(final String name) {
    return sessions.containsKey(key(name));
  }

  /**
   * The groups that paired servers joined, in addition to those in paperproxy.toml.
   *
   * @return group name to members, all lower case
   */
  public Map<String, List<String>> groups() {
    final Map<String, List<String>> groups = new LinkedHashMap<>();
    for (final Linked linked : links.values()) {
      if (linked.group() != null) {
        groups.computeIfAbsent(linked.group(), g -> new ArrayList<>()).add(key(linked.name()));
      }
    }
    return groups;
  }

  /**
   * Called once a backend proved its link key.
   *
   * @param linked the server
   * @param handler its connection
   * @param address the address it connected from
   */
  void attach(final Linked linked, final LinkHandler handler, final String address) {
    final LinkHandler old = sessions.put(key(linked.name()), handler);
    if (old != null && old != handler) {
      old.close();
    }
    if (linked.registered() && !linked.host().equals(address)) {
      // The backend moved, for example a container that got a new address.
      final Linked updated = new Linked(linked.name(), address, linked.port(), linked.group(),
          linked.key(), true);
      links.put(key(linked.name()), updated);
      server.getServer(linked.name()).ifPresent(s -> server.unregisterServer(s.getServerInfo()));
      register(updated);
      save();
      logger.info("PaperStream server {} now links from {}", linked.name(), address);
    }
    logger.info("PaperStream server {} linked", linked.name());
    // The forwarding secret may have changed since the pairing.
    final JsonObject secret = new JsonObject();
    secret.addProperty("op", "secret");
    secret.addProperty("value", forwardingSecret());
    handler.send(secret);
  }

  /**
   * Called when a link closes.
   *
   * @param name the server name
   * @param handler the connection
   */
  void detach(final String name, final LinkHandler handler) {
    if (sessions.remove(key(name), handler)) {
      logger.info("PaperStream server {} unlinked until it reconnects", name);
    }
  }

  /**
   * Handles a message from a linked backend.
   *
   * @param name the server name
   * @param handler the connection
   * @param message the message
   */
  void receive(final String name, final LinkHandler handler, final JsonObject message) {
    final String op = message.has("op") ? message.get("op").getAsString() : "";
    switch (op) {
      case "ready" -> returnPlayers(name);
      case "shutdown" -> shutdown(name, handler);
      case "result" -> {
        final int id = message.get("id").getAsInt();
        final Command command = commands.get(id);
        // Only the server that got the command may answer it.
        if (command != null && command.server().equals(key(name))) {
          commands.remove(id);
          final CompletableFuture<List<String>> future = command.future();
          final List<String> lines = new ArrayList<>();
          final JsonArray array = message.getAsJsonArray("lines");
          if (array != null) {
            array.forEach(line -> lines.add(line.getAsString()));
          }
          future.complete(lines);
        }
      }
      default -> {
        // ping and future messages: nothing to do
      }
    }
  }

  /**
   * Whether a server announced that it stops, so the limbo takes its players whatever the
   * kick message says.
   *
   * @param name the server name
   * @return whether it announced a stop in the last minute
   */
  public boolean announcedStop(final String name) {
    final Long since = announced.get(key(name));
    return since != null && System.currentTimeMillis() - since < ANNOUNCED_MILLIS;
  }

  private void shutdown(final String name, final LinkHandler handler) {
    announced.put(key(name), System.currentTimeMillis());
    final Optional<RegisteredServer> stopping = server.getServer(name);
    final List<CompletableFuture<?>> moves = new ArrayList<>();
    if (stopping.isPresent()) {
      for (final Player player : stopping.get().getPlayersConnected()) {
        final Optional<RegisteredServer> target = server.getServerGroups()
            .best(player, name, stopping.get())
            .filter(s -> reachable(s.getServerInfo().getName()));
        if (target.isEmpty()) {
          continue; // the limbo takes them when the server kicks them
        }
        moves.add(player.createConnectionRequest(target.get()).connect().thenAccept(result -> {
          if (result.isSuccessful()) {
            moved.put(player.getUniqueId(),
                new Move(key(name), target.get().getServerInfo().getName()));
          }
        }));
      }
    }
    logger.info("PaperStream server {} is stopping, moving {} player(s) to other servers", name,
        moves.size());
    CompletableFuture.allOf(moves.toArray(CompletableFuture[]::new))
        .completeOnTimeout(null, 5, TimeUnit.SECONDS)
        .whenComplete((ignored, error) -> {
          final JsonObject done = new JsonObject();
          done.addProperty("op", "moved");
          handler.send(done);
        });
  }

  /**
   * Whether a server takes players: the health check saw it, or its link is open, which the
   * health check may not have noticed yet right after a restart.
   *
   * @param name the server name
   * @return whether players can be sent there
   */
  private boolean reachable(final String name) {
    return isConnected(name) || server.getHealthChecker().isOnline(name);
  }

  private void returnPlayers(final String name) {
    announced.remove(key(name));
    if (moved.values().stream().noneMatch(m -> m.from().equals(key(name)))) {
      return;
    }
    // Groups send players to members the health check knows as online, so wait for it.
    server.getHealthChecker().checkAll();
    final long until = System.currentTimeMillis() + 30_000L;
    final ScheduledTask[] task =
        new ScheduledTask[1];
    task[0] = server.getScheduler().buildTask(
        VelocityVirtualPlugin.INSTANCE, () -> {
          if (server.getHealthChecker().isOnline(name)) {
            task[0].cancel();
            sendBack(name);
          } else if (System.currentTimeMillis() > until) {
            task[0].cancel();
          }
        }).repeat(1, TimeUnit.SECONDS).schedule();
  }

  private void sendBack(final String name) {
    final Optional<RegisteredServer> back = server.getServer(name);
    if (back.isEmpty()) {
      return;
    }
    for (final Map.Entry<UUID, Move> entry : moved.entrySet()) {
      if (!entry.getValue().from().equals(key(name))) {
        continue;
      }
      moved.remove(entry.getKey());
      server.getPlayer(entry.getKey()).map(ConnectedPlayer.class::cast).ifPresent(player -> {
        final boolean stillThere = player.getCurrentServer()
            .map(c -> c.getServerInfo().getName().equalsIgnoreCase(entry.getValue().to()))
            .orElse(false);
        if (stillThere && player.getConnectionInFlight() == null) {
          logger.info("Sending {} back to {}", player.getUsername(), name);
          player.createConnectionRequest(back.get()).fireAndForget();
        }
      });
    }
  }

  /**
   * Forgets a player who left the network.
   *
   * @param player the player
   */
  public void forget(final UUID player) {
    moved.remove(player);
  }

  /**
   * Runs a console command on a linked backend.
   *
   * @param name the server name
   * @param command the command, without slash
   * @return the output lines, or an empty optional if the server is not linked
   */
  public Optional<CompletableFuture<List<String>>> execute(final String name,
                                                           final String command) {
    final LinkHandler session = sessions.get(key(name));
    if (session == null) {
      return Optional.empty();
    }
    final int id = nextCommand.incrementAndGet();
    final CompletableFuture<List<String>> future = new CompletableFuture<>();
    commands.put(id, new Command(key(name), future));
    final JsonObject message = new JsonObject();
    message.addProperty("op", "exec");
    message.addProperty("id", id);
    message.addProperty("command", command);
    session.send(message);
    return Optional.of(future.orTimeout(15, TimeUnit.SECONDS)
        .whenComplete((lines, error) -> commands.remove(id)));
  }

  /**
   * Restarts linked servers one after another. Each one has to link again before the next
   * one stops, so players always have somewhere to go.
   *
   * @param names the servers
   * @param progress receives a message key and the server name for each step
   * @return completes when done
   */
  public CompletableFuture<Void> rollingRestart(final List<String> names,
                                                final RestartProgress progress) {
    if (!restarting.compareAndSet(false, true)) {
      progress.step("running", "");
      return CompletableFuture.completedFuture(null);
    }
    return CompletableFuture.runAsync(() -> {
      try {
        restartAll(names, progress);
      } finally {
        restarting.set(false);
      }
    }, runnable -> Thread.ofVirtual().name("paperproxy-rolling-restart").start(runnable));
  }

  private void restartAll(final List<String> names, final RestartProgress progress) {
    for (final String name : names) {
      final LinkHandler session = sessions.get(key(name));
      if (session == null) {
        progress.step("skipped", name);
        continue;
      }
      progress.step("restarting", name);
      final JsonObject message = new JsonObject();
      message.addProperty("op", "restart");
      session.send(message);
      if (!waitFor(() -> sessions.get(key(name)) != session, 120)) {
        progress.step("timeout", name);
        return;
      }
      if (!waitFor(() -> sessions.containsKey(key(name)), 600)) {
        progress.step("timeout", name);
        return;
      }
      // Players of the next server can only move here once the health check agrees.
      server.getHealthChecker().checkAll();
      waitFor(() -> server.getHealthChecker().isOnline(name), 30);
      progress.step("back", name);
    }
    progress.step("done", "");
  }

  /**
   * Progress of a rolling restart.
   */
  @FunctionalInterface
  public interface RestartProgress {

    /**
     * One step.
     *
     * @param step skipped, restarting, back, timeout, running or done
     * @param server the server
     */
    void step(String step, String server);
  }

  private static boolean waitFor(final java.util.function.BooleanSupplier condition,
                                 final int seconds) {
    final long end = System.currentTimeMillis() + seconds * 1000L;
    while (System.currentTimeMillis() < end) {
      if (condition.getAsBoolean()) {
        return true;
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }

  /**
   * The forwarding secret a paired backend gets.
   *
   * @return the secret
   */
  String forwardingSecret() {
    return new String(server.getConfiguration().getForwardingSecret(), StandardCharsets.UTF_8);
  }
}
