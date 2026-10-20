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

package net.paperstream.paperproxy.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.io.IOException;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * First start in a BungeeCord or Waterfall folder: builds velocity.toml from its config.yml, so
 * the network comes up with the same servers, ports, MOTD and forwarding. The config.yml itself
 * is left untouched, going back to BungeeCord stays possible.
 */
public final class BungeeImport {

  private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
      .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

  private BungeeImport() {
    throw new AssertionError();
  }

  /**
   * Imports config.yml if velocity.toml does not exist yet and config.yml is a BungeeCord config.
   *
   * @param velocityToml where velocity.toml belongs
   * @param logger the logger for the report
   * @return true if velocity.toml was written
   */
  public static boolean importIfPresent(final Path velocityToml, final Logger logger) {
    final Path bungee = velocityToml.resolveSibling("config.yml");
    if (Files.exists(velocityToml) || !Files.isRegularFile(bungee)) {
      return false;
    }
    final Map<String, Object> yaml;
    try (Reader reader = Files.newBufferedReader(bungee, StandardCharsets.UTF_8)) {
      final Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
      if (!(loaded instanceof Map<?, ?> map) || !map.containsKey("listeners")
          || !map.containsKey("servers")) {
        return false;
      }
      yaml = cast(map);
    } catch (final Exception e) {
      logger.warn("Found config.yml but could not read it as a BungeeCord config, "
          + "starting with a fresh velocity.toml", e);
      return false;
    }
    try {
      final List<String> report = write(velocityToml, yaml);
      logger.info("------------------------------------------------------------");
      logger.info("BungeeCord/Waterfall config.yml found, velocity.toml was built from it:");
      for (final String line : report) {
        logger.info("  {}", line);
      }
      logger.info("config.yml was not changed. Check velocity.toml before players join.");
      logger.info("------------------------------------------------------------");
      return true;
    } catch (final IOException | RuntimeException e) {
      logger.error("Could not build velocity.toml from config.yml, starting with the defaults", e);
      try {
        Files.deleteIfExists(velocityToml);
      } catch (final IOException ignored) {
        // the default config is written on top of it anyway
      }
      return false;
    }
  }

  private static List<String> write(final Path path, final Map<String, Object> yaml)
      throws IOException {
    final URL defaults = BungeeImport.class.getClassLoader().getResource("default-velocity.toml");
    if (defaults == null) {
      throw new IOException("default-velocity.toml is missing");
    }
    final List<String> report = new ArrayList<>();
    final List<Map<String, Object>> listeners = new ArrayList<>();
    if (yaml.get("listeners") instanceof List<?> list) {
      for (final Object o : list) {
        if (o instanceof Map<?, ?> m) {
          listeners.add(cast(m));
        }
      }
    }
    final Map<String, Object> listener = listeners.isEmpty() ? Map.of() : listeners.get(0);
    if (listeners.size() > 1) {
      report.add("Only the first of " + listeners.size()
          + " listeners was taken over, PaperProxy listens on one address.");
    }
    final Map<String, String> servers = new LinkedHashMap<>();
    if (yaml.get("servers") instanceof Map<?, ?> m) {
      for (final Map.Entry<?, ?> e : m.entrySet()) {
        if (e.getValue() instanceof Map<?, ?> server && server.get("address") != null) {
          servers.put(String.valueOf(e.getKey()), String.valueOf(server.get("address")));
          if (Boolean.TRUE.equals(server.get("restricted"))) {
            report.add("Server " + e.getKey() + " was restricted in BungeeCord. "
                + "Velocity has no such switch, use a permission plugin.");
          }
        }
      }
    }

    try (CommentedFileConfig config = CommentedFileConfig.builder(path)
        .defaultData(defaults).preserveInsertionOrder().sync().build()) {
      config.load();
      final Object host = listener.get("host");
      if (host != null) {
        config.set("bind", String.valueOf(host));
        report.add("bind = " + host);
      }
      final Object motd = listener.get("motd");
      if (motd != null) {
        final String mini = MiniMessage.miniMessage()
            .serialize(LEGACY.deserialize(String.valueOf(motd).replace('\u00a7', '&')));
        config.set("motd", mini);
        report.add("motd = " + mini);
      }
      if (listener.get("max_players") instanceof Number n) {
        config.set("show-max-players", n.intValue());
      }
      final boolean onlineMode = !Boolean.FALSE.equals(yaml.get("online_mode"));
      config.set("online-mode", onlineMode);
      config.set("force-key-authentication",
          Boolean.TRUE.equals(yaml.get("enforce_secure_profile")));
      config.set("prevent-client-proxy-connections",
          Boolean.TRUE.equals(yaml.get("prevent_proxy_connections")));
      final boolean ipForward = Boolean.TRUE.equals(yaml.get("ip_forward"));
      config.set("player-info-forwarding-mode", ipForward ? "LEGACY" : "NONE");
      report.add("online-mode = " + onlineMode + ", forwarding = " + (ipForward
          ? "LEGACY (the BungeeCord forwarding, keep bungeecord: true in the backends' spigot.yml)"
          : "NONE (ip_forward was off)"));
      if (ipForward) {
        report.add("Tip: modern forwarding is safer. Switch to MODERN and set velocity.enabled "
            + "in each backend's paper-global.yml when you are ready.");
      }

      config.remove("servers");
      for (final Map.Entry<String, String> e : servers.entrySet()) {
        config.set(List.of("servers", e.getKey()), e.getValue());
      }
      final List<String> tryList = new ArrayList<>();
      if (listener.get("priorities") instanceof List<?> prio) {
        for (final Object o : prio) {
          if (servers.containsKey(String.valueOf(o))) {
            tryList.add(String.valueOf(o));
          }
        }
      }
      if (tryList.isEmpty() && !servers.isEmpty()) {
        tryList.add(servers.keySet().iterator().next());
      }
      config.set(List.of("servers", "try"), tryList);
      report.add(servers.size() + " servers " + servers.keySet() + ", players join " + tryList);

      config.remove("forced-hosts");
      int hosts = 0;
      if (listener.get("forced_hosts") instanceof Map<?, ?> forced) {
        for (final Map.Entry<?, ?> e : forced.entrySet()) {
          final String target = String.valueOf(e.getValue());
          if (servers.containsKey(target)) {
            config.set(List.of("forced-hosts", String.valueOf(e.getKey())), List.of(target));
            ++hosts;
          }
        }
      }
      if (hosts == 0) {
        config.set("forced-hosts", com.electronwill.nightconfig.core.Config.inMemory());
      }
      report.add(hosts + " forced hosts");

      if (Boolean.TRUE.equals(listener.get("ping_passthrough"))) {
        for (final String key : List.of("version", "players", "description", "favicon")) {
          config.set(List.of("ping-passthrough", key), true);
        }
        report.add("ping passthrough on");
      }
      if (Boolean.TRUE.equals(listener.get("query_enabled"))) {
        config.set(List.of("query", "enabled"), true);
        if (listener.get("query_port") instanceof Number port) {
          config.set(List.of("query", "port"), port.intValue());
        }
      }
      if (Boolean.TRUE.equals(listener.get("proxy_protocol"))) {
        config.set(List.of("advanced", "haproxy-protocol"), true);
        report.add("HAProxy protocol on");
      }
      if (yaml.get("connection_throttle") instanceof Number n) {
        config.set(List.of("advanced", "login-ratelimit"), n.intValue());
      }
      if (yaml.get("timeout") instanceof Number n) {
        config.set(List.of("advanced", "read-timeout"), n.intValue());
      }
      if (yaml.get("log_commands") instanceof Boolean b) {
        config.set(List.of("advanced", "log-command-executions"), b);
      }
      config.save();
    }

    if (yaml.get("groups") instanceof Map<?, ?> groups && !groups.isEmpty()) {
      report.add("BungeeCord's built in groups/permissions were not taken over, "
          + "use LuckPerms (works for Velocity and Bungee plugins).");
    }
    if (yaml.get("disabled_commands") instanceof List<?> disabled && !disabled.isEmpty()) {
      report.add("disabled_commands " + disabled + " were not taken over.");
    }
    if (yaml.get("player_limit") instanceof Number n && n.intValue() > 0) {
      report.add("player_limit " + n + " was not taken over, Velocity has no global hard limit.");
    }

    final Path secret = path.resolveSibling("forwarding.secret");
    if (Files.notExists(secret)) {
      final String chars = "AaBbCcDdEeFfGgHhIiJjKkLlMmNnOoPpQqRrSsTtUuVvWwXxYyZz1234567890";
      final SecureRandom random = new SecureRandom();
      final StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 12; i++) {
        sb.append(chars.charAt(random.nextInt(chars.length())));
      }
      Files.writeString(secret, sb.toString());
    }
    return report;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(final Map<?, ?> map) {
    return (Map<String, Object>) map;
  }
}
