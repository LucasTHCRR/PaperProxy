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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.VelocityServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import net.paperstream.paperproxy.config.PaperProxyConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Serves proxy statistics in the Prometheus text format at {@code /metrics}.
 */
public final class MetricsEndpoint {

  private static final Logger logger = LogManager.getLogger(MetricsEndpoint.class);

  private final VelocityServer server;
  private @Nullable HttpServer http;
  private PaperProxyConfig.@Nullable Metrics running;

  /**
   * Creates the endpoint.
   *
   * @param server the proxy
   */
  public MetricsEndpoint(final VelocityServer server) {
    this.server = server;
  }

  /**
   * Starts, restarts or stops the endpoint to match the current settings.
   */
  public synchronized void apply() {
    final PaperProxyConfig.Metrics settings = server.getPaperProxyConfig().values().metrics();
    if (settings.equals(running) && (http != null) == settings.enabled()) {
      return;
    }
    stop();
    if (!settings.enabled()) {
      return;
    }
    try {
      final HttpServer created = HttpServer.create(
          new InetSocketAddress(settings.bind(), settings.port()), 0);
      created.createContext("/metrics", exchange -> {
        final byte[] body = render().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
          out.write(body);
        }
      });
      created.createContext("/status", exchange -> {
        final byte[] body = status().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        // Statusseiten auf Webseiten duerfen das direkt abfragen
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
          out.write(body);
        }
      });
      created.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      created.start();
      http = created;
      running = settings;
      logger.info("Metrics available at http://{}:{}/metrics, status as JSON at /status",
          settings.bind(), settings.port());
    } catch (final IOException e) {
      logger.error("Unable to start the metrics endpoint on {}:{}: {}", settings.bind(),
          settings.port(), e.getMessage());
    }
  }

  /**
   * Stops the endpoint.
   */
  public synchronized void stop() {
    if (http != null) {
      http.stop(0);
      http = null;
    }
    running = null;
  }

  String render() {
    final StringBuilder out = new StringBuilder(1024);
    gauge(out, "paperproxy_players_online", "Players connected to the proxy",
        server.getPlayerCount());
    out.append("# HELP paperproxy_server_players Players on a backend server\n")
        .append("# TYPE paperproxy_server_players gauge\n");
    for (final RegisteredServer registered : server.getAllServers()) {
      out.append("paperproxy_server_players{server=\"").append(label(registered)).append("\"} ")
          .append(registered.getPlayersConnected().size()).append('\n');
    }
    out.append("# HELP paperproxy_server_up Whether the last health check reached the server\n")
        .append("# TYPE paperproxy_server_up gauge\n");
    for (final RegisteredServer registered : server.getAllServers()) {
      out.append("paperproxy_server_up{server=\"").append(label(registered)).append("\"} ")
          .append(server.getHealthChecker().isOnline(registered.getServerInfo().getName())
              ? 1 : 0).append('\n');
    }
    out.append("# HELP paperproxy_server_queue Players waiting for a server\n")
        .append("# TYPE paperproxy_server_queue gauge\n");
    for (final RegisteredServer registered : server.getAllServers()) {
      out.append("paperproxy_server_queue{server=\"").append(label(registered)).append("\"} ")
          .append(server.getServerQueue().size(registered.getServerInfo().getName()))
          .append('\n');
    }
    final ProxyStats stats = server.getProxyStats();
    counter(out, "paperproxy_logins_total", "Successful logins", stats.logins.sum());
    counter(out, "paperproxy_logins_refused_total", "Logins refused by the proxy",
        stats.refusedPreLogin.sum() + stats.refusedLogin.sum());
    out.append("# HELP paperproxy_server_connects_total Finished connections to a server\n")
        .append("# TYPE paperproxy_server_connects_total counter\n");
    stats.connects.forEach((name, timing) -> out.append("paperproxy_server_connects_total{server=\"")
        .append(label(name)).append("\"} ").append(timing.count.sum()).append('\n'));
    out.append("# HELP paperproxy_server_connect_seconds_total Time spent connecting to a server\n")
        .append("# TYPE paperproxy_server_connect_seconds_total counter\n");
    stats.connects.forEach((name, timing) -> out.append(
            "paperproxy_server_connect_seconds_total{server=\"").append(label(name)).append("\"} ")
        .append(timing.millis.sum() / 1000.0).append('\n'));
    out.append("# HELP paperproxy_server_kicks_total Players kicked by a backend server\n")
        .append("# TYPE paperproxy_server_kicks_total counter\n");
    stats.kicks.forEach((name, count) -> out.append("paperproxy_server_kicks_total{server=\"")
        .append(label(name)).append("\"} ").append(count.sum()).append('\n'));
    out.append("# HELP paperproxy_players_by_version Players by client version\n")
        .append("# TYPE paperproxy_players_by_version gauge\n");
    final Map<String, Long> versions = new TreeMap<>();
    for (final Player player : server.getAllPlayers()) {
      versions.merge(player.getProtocolVersion().getMostRecentSupportedVersion(), 1L, Long::sum);
    }
    versions.forEach((version, count) -> out.append("paperproxy_players_by_version{version=\"")
        .append(label(version)).append("\"} ").append(count).append('\n'));
    if (server.getNetworkSync().connected()) {
      gauge(out, "paperproxy_network_players", "Players on all synced proxies",
          server.getNetworkSync().networkPlayerCount());
    }
    gauge(out, "paperproxy_antibot_attack", "1 while bot attack mode is on",
        server.getAntiBot().underAttack() ? 1 : 0);
    final MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    gauge(out, "paperproxy_jvm_heap_used_bytes", "Used heap memory", heap.getUsed());
    gauge(out, "paperproxy_jvm_heap_max_bytes", "Maximum heap memory", heap.getMax());
    gauge(out, "paperproxy_jvm_threads", "Live threads",
        ManagementFactory.getThreadMXBean().getThreadCount());
    gauge(out, "paperproxy_uptime_seconds", "Seconds since the proxy started",
        ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
    return out.toString();
  }

  String status() {
    final PaperProxyConfig.Values values = server.getPaperProxyConfig().values();
    final JsonObject root = new JsonObject();
    root.addProperty("online", server.getPlayerCount());
    root.addProperty("max", values.maxPlayers() > 0 ? values.maxPlayers()
        : server.getConfiguration().getShowMaxPlayers());
    if (server.getNetworkSync().connected()) {
      root.addProperty("network_online", server.getNetworkSync().networkPlayerCount());
    }
    root.addProperty("maintenance", values.maintenance());
    final JsonArray servers = new JsonArray();
    for (final RegisteredServer registered : server.getAllServers()) {
      final String name = registered.getServerInfo().getName();
      final JsonObject entry = new JsonObject();
      entry.addProperty("name", name);
      entry.addProperty("online", server.getHealthChecker().isOnline(name));
      entry.addProperty("players", registered.getPlayersConnected().size());
      entry.addProperty("queue", server.getServerQueue().size(name));
      entry.addProperty("maintenance",
          values.maintenanceServers().contains(name.toLowerCase(Locale.ROOT)));
      servers.add(entry);
    }
    root.add("servers", servers);
    root.addProperty("version", server.getVersion().getVersion());
    root.addProperty("uptime_seconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
    return root.toString();
  }

  private static void counter(final StringBuilder out, final String name, final String help,
                              final long value) {
    out.append("# HELP ").append(name).append(' ').append(help).append('\n')
        .append("# TYPE ").append(name).append(" counter\n")
        .append(name).append(' ').append(value).append('\n');
  }

  private static void gauge(final StringBuilder out, final String name, final String help,
                            final long value) {
    out.append("# HELP ").append(name).append(' ').append(help).append('\n')
        .append("# TYPE ").append(name).append(" gauge\n")
        .append(name).append(' ').append(value).append('\n');
  }

  private static String label(final RegisteredServer registered) {
    return label(registered.getServerInfo().getName());
  }

  private static String label(final String text) {
    return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }
}
