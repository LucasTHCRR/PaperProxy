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

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.velocitypowered.proxy.config.PlayerInfoForwarding;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.paperstream.paperproxy.network.DiscordWebhook;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Reads {@code paperproxy.toml}. Every invalid value is reported with its name and what is
 * allowed, and falls back to the default instead of stopping the proxy.
 */
public final class PaperProxyConfig {

  private static final Logger logger = LogManager.getLogger(PaperProxyConfig.class);
  private static final String FILE = "paperproxy.toml";

  private final Path file;
  private volatile Values values = Values.defaults();

  /**
   * The parsed settings.
   *
   * @param forwarding forwarding mode per server (lower case names)
   * @param paperGuardMaxAgeSeconds signature lifetime
   * @param versions allowed client versions per server
   * @param healthCheck whether backends are pinged
   * @param healthCheckIntervalSeconds ping interval
   * @param healthNotifyPermission permission for status messages
   * @param maintenance global maintenance
   * @param maintenanceServers servers in maintenance (lower case)
   * @param maintenanceWhitelist names or UUIDs allowed during maintenance (lower case)
   * @param pingCacheSeconds ping cache lifetime
   * @param watchdogMillis watchdog threshold, 0 = off
   * @param reloadRequiresConfirm whether reload needs "confirm"
   * @param reloadBlocked plugin ids that are never reloaded
   * @param updateCheck whether updates are checked
   * @param updateChannel auto, release, beta or alpha
   * @param autoUpdate whether verified updates are downloaded
   * @param autoUpdateAllowMajor whether major updates are installed automatically
   * @param groups server groups, group name to member names (all lower case)
   * @param queue queue settings
   * @param hubTarget server or group for /hub, empty if disabled
   * @param hubAliases command names for the hub command
   * @param antiBot bot protection settings
   * @param metrics Prometheus endpoint settings
   * @param transferOnShutdown host:port to send players to when the proxy stops, or empty
   * @param sync network sync settings
   * @param hiddenCommands commands hidden from tab completion (lower case)
   * @param queryServer backend whose query answer the proxy passes on, empty if disabled
   * @param queryPort query port of that backend, 0 = its game port
   * @param discord Discord webhook settings
   * @param restart planned restart settings
   * @param party party settings
   * @param punish whether the ban, mute and kick commands are registered
   * @param limbo limbo settings
   * @param paperStreamLink whether PaperStream backends may pair and link
   */
  public record Values(Map<String, PlayerInfoForwarding> forwarding, int paperGuardMaxAgeSeconds,
                       Map<String, VersionRange> versions, boolean healthCheck,
                       int healthCheckIntervalSeconds, String healthNotifyPermission,
                       boolean maintenance, Set<String> maintenanceServers,
                       Set<String> maintenanceWhitelist, int pingCacheSeconds, int watchdogMillis,
                       boolean reloadRequiresConfirm, Set<String> reloadBlocked,
                       boolean updateCheck, String updateChannel, boolean autoUpdate,
                       boolean autoUpdateAllowMajor, Map<String, List<String>> groups,
                       Queue queue, String hubTarget, List<String> hubAliases,
                       AntiBot antiBot, Metrics metrics, String transferOnShutdown,
                       Sync sync, Set<String> hiddenCommands, String queryServer,
                       int queryPort, Discord discord, Restart restart, Party party,
                       boolean punish, Limbo limbo, boolean paperStreamLink) {

    static Values defaults() {
      return new Values(Map.of(), 10, Map.of(), true, 10, "paperproxy.notify.health", false,
          Set.of(), Set.of(), 5, 500, true,
          Set.of("viaversion", "viabackwards", "viarewind", "luckperms", "geyser", "floodgate"),
          true, "auto", false, false, Map.of(), Queue.defaults(), "",
          List.of("hub", "lobby"), AntiBot.defaults(), Metrics.defaults(), "",
          Sync.defaults(), Set.of(), "", 0, Discord.defaults(), Restart.defaults(),
          Party.defaults(), false, Limbo.defaults(), false);
    }
  }

  /**
   * Bot protection settings.
   *
   * @param enabled whether the protection is active
   * @param attackThreshold new connections per second that start attack mode
   * @param attackDurationSeconds how long attack mode lasts after the last burst
   * @param attackKnownOnly whether only known players may join during an attack
   * @param maxAccountsPerIp players online from one IP, 0 = unlimited
   * @param blockedNamePattern regular expression for refused names, empty = none
   * @param requirePing "off", "attack" or "always": new players must ping the server list first
   */
  public record AntiBot(boolean enabled, int attackThreshold, int attackDurationSeconds,
                        boolean attackKnownOnly, int maxAccountsPerIp,
                        String blockedNamePattern, String requirePing) {

    static AntiBot defaults() {
      return new AntiBot(true, 30, 60, true, 0, "", "attack");
    }
  }

  /**
   * Discord webhook settings.
   *
   * @param webhookUrl the webhook URL, empty = off
   * @param username name shown in Discord
   * @param events which events are posted
   */
  public record Discord(String webhookUrl, String username, Set<DiscordWebhook.Kind> events) {

    static Discord defaults() {
      return new Discord("", "PaperProxy", Set.of(DiscordWebhook.Kind.values()));
    }
  }

  /**
   * Planned restart settings.
   *
   * @param times daily restart times
   * @param warnings seconds before a restart at which players are warned
   */
  public record Restart(List<LocalTime> times, List<Integer> warnings) {

    static Restart defaults() {
      return new Restart(List.of(), List.of(600, 300, 60, 30, 10, 5, 4, 3, 2, 1));
    }
  }

  /**
   * Limbo settings.
   *
   * @param enabled whether players wait in the limbo when their server restarts
   * @param maxWaitSeconds how long they wait at most
   * @param kickMessages kick messages (parts, any case) that mean the server is restarting
   */
  public record Limbo(boolean enabled, int maxWaitSeconds, List<String> kickMessages) {

    static Limbo defaults() {
      return new Limbo(true, 300, List.of("server closed", "restarting", "server is restarting",
          "server stopped"));
    }
  }

  /**
   * Party settings.
   *
   * @param enabled whether /party is registered
   * @param maxSize the most members a party can have
   * @param follow whether members follow the leader to other servers
   */
  public record Party(boolean enabled, int maxSize, boolean follow) {

    static Party defaults() {
      return new Party(true, 8, true);
    }
  }

  /**
   * Network sync settings.
   *
   * @param enabled whether proxies are connected through Redis
   * @param proxyId this proxy's name, empty for host:port
   * @param host the Redis host
   * @param port the Redis port
   * @param password the Redis password, empty for none
   * @param database the Redis database number
   * @param networkPlayerCount whether the server list shows all proxies' players
   * @param username the Redis ACL user, empty for the default user
   * @param ssl whether to connect with TLS
   * @param secret shared secret that signs messages between proxies, empty = unsigned
   */
  public record Sync(boolean enabled, String proxyId, String host, int port, String password,
                     int database, boolean networkPlayerCount, String username, boolean ssl,
                     String secret) {

    static Sync defaults() {
      return new Sync(false, "", "127.0.0.1", 6379, "", 0, true, "", false, "");
    }
  }

  /**
   * Prometheus endpoint settings.
   *
   * @param enabled whether the endpoint runs
   * @param bind address to listen on
   * @param port port to listen on
   */
  public record Metrics(boolean enabled, String bind, int port) {

    static Metrics defaults() {
      return new Metrics(false, "127.0.0.1", 9225);
    }
  }

  /**
   * Queue settings.
   *
   * @param enabled whether full or offline servers get a queue
   * @param intervalSeconds seconds between queue updates
   * @param timeoutMinutes minutes after which a player leaves the queue
   * @param rejoinAfterRestart whether players are sent back after a server restart
   */
  public record Queue(boolean enabled, int intervalSeconds, int timeoutMinutes,
                      boolean rejoinAfterRestart) {

    static Queue defaults() {
      return new Queue(true, 2, 10, true);
    }
  }

  /**
   * Creates the config.
   *
   * @param directory the proxy root
   */
  public PaperProxyConfig(final Path directory) {
    this.file = directory.resolve(FILE);
  }

  /**
   * Returns the current settings.
   *
   * @return the settings
   */
  public Values values() {
    return values;
  }

  /**
   * Loads the file, creating it from the bundled default if needed.
   *
   * @return true if the file was read without errors
   */
  public boolean load() {
    try {
      if (!Files.exists(file)) {
        try (InputStream in = PaperProxyConfig.class.getResourceAsStream("/paperproxy/" + FILE)) {
          if (in == null) {
            throw new IOException("bundled " + FILE + " is missing");
          }
          Files.copy(in, file);
        }
      }
      try (CommentedFileConfig config = CommentedFileConfig.builder(file).preserveInsertionOrder().sync()
          .build()) {
        config.load();
        final List<String> errors = new ArrayList<>();
        final Values parsed = parse(config, errors);
        for (final String error : errors) {
          logger.error("{}: {}", FILE, error);
        }
        this.values = parsed;
        return errors.isEmpty();
      }
    } catch (final Exception e) {
      logger.error("Unable to read {} ({}), keeping the previous settings", FILE, e.getMessage());
      return false;
    }
  }

  /**
   * Turns global or per-server maintenance on or off and saves the file.
   *
   * @param server the server, or null for global maintenance
   * @param enabled the new state
   * @throws IOException if the file cannot be written
   */
  public synchronized void setMaintenance(final @Nullable String server, final boolean enabled)
      throws IOException {
    try (CommentedFileConfig config = CommentedFileConfig.builder(file).preserveInsertionOrder().sync()
        .build()) {
      config.load();
      if (server == null) {
        config.set("maintenance.enabled", enabled);
      } else {
        final Set<String> servers = new LinkedHashSet<>(stringList(config.get("maintenance.servers")));
        servers.removeIf(s -> s.equalsIgnoreCase(server));
        if (enabled) {
          servers.add(server);
        }
        config.set("maintenance.servers", new ArrayList<>(servers));
      }
      config.save();
    }
    load();
  }

  static Values parse(final Config config, final List<String> errors) {
    final Values d = Values.defaults();

    final Map<String, PlayerInfoForwarding> forwarding = new LinkedHashMap<>();
    final Object forwardingServers = config.get("forwarding.servers");
    if (forwardingServers instanceof Config servers) {
      for (final Config.Entry entry : servers.entrySet()) {
        final Object raw = entry.getValue();
        final String value = String.valueOf(raw).toUpperCase(Locale.ROOT);
        try {
          forwarding.put(entry.getKey().toLowerCase(Locale.ROOT),
              PlayerInfoForwarding.valueOf(value));
        } catch (final IllegalArgumentException e) {
          errors.add("forwarding.servers." + entry.getKey() + ": '" + raw
              + "' is not a forwarding mode (modern, paperguard, bungeeguard, legacy, none)");
        }
      }
    }

    final Map<String, VersionRange> versions = new LinkedHashMap<>();
    final Object versionSection = config.get("versions");
    if (versionSection instanceof Config section) {
      for (final Config.Entry entry : section.entrySet()) {
        final Object raw = entry.getValue();
        try {
          versions.put(entry.getKey().toLowerCase(Locale.ROOT),
              VersionRange.parse(String.valueOf(raw)));
        } catch (final IllegalArgumentException e) {
          errors.add("versions." + entry.getKey() + ": " + e.getMessage());
        }
      }
    }

    final Map<String, List<String>> groups = new LinkedHashMap<>();
    final Object groupSection = config.get("groups");
    if (groupSection instanceof Config section) {
      for (final Config.Entry entry : section.entrySet()) {
        if (!(entry.getValue() instanceof List<?>)) {
          errors.add("groups." + entry.getKey() + ": must be a list of server names, e.g. "
              + "[\"lobby-1\", \"lobby-2\"]");
          continue;
        }
        final List<String> members = new ArrayList<>(lowerSet(entry.getValue()));
        if (members.isEmpty()) {
          errors.add("groups." + entry.getKey() + ": has no servers");
          continue;
        }
        groups.put(entry.getKey().toLowerCase(Locale.ROOT), List.copyOf(members));
      }
    }
    final Queue q = Queue.defaults();
    final Queue queue = new Queue(
        bool(config, "queue.enabled", q.enabled(), errors),
        integer(config, "queue.interval-seconds", q.intervalSeconds(), 1, 60, errors),
        integer(config, "queue.timeout-minutes", q.timeoutMinutes(), 1, 1440, errors),
        bool(config, "queue.rejoin-after-restart", q.rejoinAfterRestart(), errors));
    final AntiBot a = AntiBot.defaults();
    String namePattern = string(config, "antibot.blocked-name-pattern", a.blockedNamePattern(),
        errors);
    try {
      java.util.regex.Pattern.compile(namePattern);
    } catch (final java.util.regex.PatternSyntaxException e) {
      errors.add("antibot.blocked-name-pattern: not a valid regular expression ("
          + e.getDescription() + ")");
      namePattern = "";
    }
    final AntiBot antiBot = new AntiBot(
        bool(config, "antibot.enabled", a.enabled(), errors),
        integer(config, "antibot.attack-threshold", a.attackThreshold(), 1, 100_000, errors),
        integer(config, "antibot.attack-duration-seconds", a.attackDurationSeconds(), 1, 3600,
            errors),
        bool(config, "antibot.attack-known-only", a.attackKnownOnly(), errors),
        integer(config, "antibot.max-accounts-per-ip", a.maxAccountsPerIp(), 0, 1000, errors),
        namePattern, requirePing(config, a.requirePing(), errors));
    final Metrics m = Metrics.defaults();
    final Metrics metrics = new Metrics(
        bool(config, "metrics.enabled", m.enabled(), errors),
        string(config, "metrics.bind", m.bind(), errors),
        integer(config, "metrics.port", m.port(), 1, 65535, errors));
    final Sync y = Sync.defaults();
    final Sync sync = new Sync(
        bool(config, "sync.enabled", y.enabled(), errors),
        string(config, "sync.proxy-id", y.proxyId(), errors).trim(),
        string(config, "sync.redis-host", y.host(), errors).trim(),
        integer(config, "sync.redis-port", y.port(), 1, 65535, errors),
        secret(string(config, "sync.redis-password", y.password(), errors)),
        integer(config, "sync.redis-database", y.database(), 0, 1000, errors),
        bool(config, "sync.network-player-count", y.networkPlayerCount(), errors),
        secret(string(config, "sync.redis-username", y.username(), errors).trim()),
        bool(config, "sync.redis-ssl", y.ssl(), errors),
        secret(string(config, "sync.secret", y.secret(), errors).trim()));
    final List<String> hubAliases = config.get("hub-command.aliases") == null
        ? d.hubAliases() : List.copyOf(lowerSet(config.get("hub-command.aliases")));

    final String channel = string(config, "updates.channel", d.updateChannel(), errors)
        .toLowerCase(Locale.ROOT);
    if (!List.of("auto", "release", "beta", "alpha").contains(channel)) {
      errors.add("updates.channel: '" + channel + "' must be auto, release, beta or alpha");
    }

    return new Values(Map.copyOf(forwarding),
        integer(config, "paperguard.max-age-seconds", d.paperGuardMaxAgeSeconds(), 1, 300, errors),
        Map.copyOf(versions),
        bool(config, "health-check.enabled", d.healthCheck(), errors),
        integer(config, "health-check.interval-seconds", d.healthCheckIntervalSeconds(), 1, 3600,
            errors),
        string(config, "health-check.notify-permission", d.healthNotifyPermission(), errors),
        bool(config, "maintenance.enabled", d.maintenance(), errors),
        lowerSet(config.get("maintenance.servers")),
        lowerSet(config.get("maintenance.whitelist")),
        integer(config, "ping-cache.seconds", d.pingCacheSeconds(), 0, 300, errors),
        integer(config, "watchdog.warn-after-ms", d.watchdogMillis(), 0, 600_000, errors),
        bool(config, "plugin-reload.require-confirm", d.reloadRequiresConfirm(), errors),
        config.get("plugin-reload.blocked") == null ? d.reloadBlocked()
            : lowerSet(config.get("plugin-reload.blocked")),
        bool(config, "updates.check", d.updateCheck(), errors),
        List.of("auto", "release", "beta", "alpha").contains(channel) ? channel
            : d.updateChannel(),
        bool(config, "auto-update.enabled", d.autoUpdate(), errors),
        bool(config, "auto-update.allow-major", d.autoUpdateAllowMajor(), errors),
        Map.copyOf(groups), queue,
        string(config, "hub-command.target", d.hubTarget(), errors).toLowerCase(Locale.ROOT)
            .trim(),
        hubAliases, antiBot, metrics,
        string(config, "shutdown.transfer-to", d.transferOnShutdown(), errors).trim(), sync,
        hiddenCommands(config.get("tab-complete.hidden-commands")),
        string(config, "query.passthrough-server", d.queryServer(), errors)
            .toLowerCase(Locale.ROOT).trim(),
        integer(config, "query.passthrough-port", d.queryPort(), 0, 65535, errors),
        discord(config, errors), restart(config, errors),
        new Party(bool(config, "party.enabled", d.party().enabled(), errors),
            integer(config, "party.max-size", d.party().maxSize(), 2, 100, errors),
            bool(config, "party.follow-leader", d.party().follow(), errors)),
        bool(config, "punish.enabled", d.punish(), errors),
        new Limbo(bool(config, "limbo.enabled", d.limbo().enabled(), errors),
            integer(config, "limbo.max-wait-seconds", d.limbo().maxWaitSeconds(), 10, 3600,
                errors),
            config.get("limbo.kick-messages") == null ? d.limbo().kickMessages()
                : stringList(config.get("limbo.kick-messages"))),
        bool(config, "paperstream-link.enabled", d.paperStreamLink(), errors));
  }

  /**
   * Lets secrets stay out of the config file: {@code ${env:NAME}} reads an environment variable,
   * {@code file:path} the first line of a file.
   *
   * @param value the configured value
   * @return the secret
   */
  static String secret(final String value) {
    if (value.startsWith("${env:") && value.endsWith("}")) {
      final String env = System.getenv(value.substring(6, value.length() - 1));
      return env == null ? "" : env.trim();
    }
    if (value.startsWith("file:")) {
      try {
        final List<String> lines = Files.readAllLines(Path.of(value.substring(5)));
        return lines.isEmpty() ? "" : lines.get(0).trim();
      } catch (final IOException e) {
        return "";
      }
    }
    return value;
  }

  private static String requirePing(final Config config, final String def,
                                    final List<String> errors) {
    final String value = string(config, "antibot.require-ping", def, errors)
        .toLowerCase(Locale.ROOT);
    if (!List.of("off", "attack", "always").contains(value)) {
      errors.add("antibot.require-ping: '" + value + "' must be off, attack or always");
      return def;
    }
    return value;
  }

  private static Discord discord(final Config config, final List<String> errors) {
    final Discord d = Discord.defaults();
    final String url = secret(string(config, "discord.webhook-url", d.webhookUrl(), errors).trim());
    final boolean web = url.startsWith("https://") || url.startsWith("http://");
    if (!url.isEmpty() && !web) {
      errors.add("discord.webhook-url: must start with https://");
    }
    Set<DiscordWebhook.Kind> events = d.events();
    if (config.get("discord.events") != null) {
      events = new LinkedHashSet<>();
      for (final String name : lowerSet(config.get("discord.events"))) {
        try {
          events.add(DiscordWebhook.Kind.valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (final IllegalArgumentException e) {
          errors.add("discord.events: unknown event '" + name + "' (proxy, health, antibot, "
              + "maintenance, update, restart, punish)");
        }
      }
      events = Set.copyOf(events);
    }
    return new Discord(web ? url : "",
        string(config, "discord.username", d.username(), errors), events);
  }

  private static Restart restart(final Config config, final List<String> errors) {
    final Restart d = Restart.defaults();
    final List<LocalTime> times = new ArrayList<>();
    for (final String time : stringList(config.get("restart.times"))) {
      try {
        times.add(LocalTime.parse(time.trim()));
      } catch (final java.time.format.DateTimeParseException e) {
        errors.add("restart.times: '" + time + "' is not a time like \"04:00\"");
      }
    }
    List<Integer> warnings = d.warnings();
    if (config.get("restart.warn-seconds") instanceof List<?> list) {
      warnings = new ArrayList<>();
      for (final Object o : list) {
        if (o instanceof Number n && n.intValue() > 0) {
          warnings.add(n.intValue());
        } else {
          errors.add("restart.warn-seconds: '" + o + "' must be a positive number");
        }
      }
      warnings = List.copyOf(warnings);
    }
    return new Restart(List.copyOf(times), warnings);
  }

  private static Set<String> hiddenCommands(final @Nullable Object value) {
    final Set<String> out = new LinkedHashSet<>();
    for (final String name : lowerSet(value)) {
      out.add(name.startsWith("/") ? name.substring(1) : name);
    }
    return Set.copyOf(out);
  }

  private static int integer(final Config config, final String path, final int def,
                             final int min, final int max, final List<String> errors) {
    final Object value = config.get(path);
    if (value == null) {
      return def;
    }
    if (!(value instanceof Number number)) {
      errors.add(path + ": '" + value + "' must be a whole number");
      return def;
    }
    final long v = number.longValue();
    if (v < min || v > max) {
      errors.add(path + ": " + v + " must be between " + min + " and " + max);
      return def;
    }
    return (int) v;
  }

  private static boolean bool(final Config config, final String path, final boolean def,
                              final List<String> errors) {
    final Object value = config.get(path);
    if (value == null) {
      return def;
    }
    if (!(value instanceof Boolean b)) {
      errors.add(path + ": '" + value + "' must be true or false");
      return def;
    }
    return b;
  }

  private static String string(final Config config, final String path, final String def,
                               final List<String> errors) {
    final Object value = config.get(path);
    if (value == null) {
      return def;
    }
    if (!(value instanceof String s)) {
      errors.add(path + ": '" + value + "' must be text in quotes");
      return def;
    }
    return s;
  }

  private static List<String> stringList(final @Nullable Object value) {
    final List<String> out = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (final Object o : list) {
        out.add(String.valueOf(o));
      }
    }
    return out;
  }

  private static Set<String> lowerSet(final @Nullable Object value) {
    final Set<String> out = new LinkedHashSet<>();
    for (final String s : stringList(value)) {
      out.add(s.toLowerCase(Locale.ROOT));
    }
    // Keep the order: group members are tried in the configured order on ties.
    return Collections.unmodifiableSet(out);
  }
}
