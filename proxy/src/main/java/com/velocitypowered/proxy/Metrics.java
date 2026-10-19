/*
 * Copyright (C) 2019-2023 Velocity Contributors
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

package com.velocitypowered.proxy;

import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bstats.MetricsBase;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.CustomChart;
import org.bstats.charts.DrilldownPie;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bstats.config.MetricsConfig;
import org.bstats.json.JsonObjectBuilder;

/**
 * Initializes bStats.
 */
public class Metrics {

  private MetricsBase metricsBase;

  private Metrics(Logger logger, int serviceId, boolean defaultEnabled) {
    File configFile = Path.of("plugins", "bStats", "config.txt").toFile();
    MetricsConfig config;
    try {
      config = new MetricsConfig(configFile, defaultEnabled);
    } catch (IOException e) {
      logger.error("Failed to create bStats config", e);
      return;
    }

    metricsBase = new MetricsBase(
        "server-implementation",
        config.getServerUUID(),
        serviceId,
        config.isEnabled(),
        this::appendPlatformData,
        jsonObjectBuilder -> { /* NOP */ },
        null,
        () -> true,
        logger::warn,
        logger::info,
        config.isLogErrorsEnabled(),
        config.isLogSentDataEnabled(),
        config.isLogResponseStatusTextEnabled(),
        false
    );

    if (!config.didExistBefore()) {
      // Send an info message when the bStats config file gets created for the first time
      logger.info("PaperProxy and some of its plugins collect metrics"
          + " and send them to bStats (https://bStats.org).");
      logger.info("bStats collects some basic information for plugin"
          + " authors, like how many people use");
      logger.info("their plugin and their total player count."
          + " It's recommended to keep bStats enabled, but");
      logger.info("if you're not comfortable with this, you can opt-out"
          + " by editing the config.txt file in");
      logger.info("the '/plugins/bStats/' folder and setting enabled to false.");
    }
  }

  /**
   * Adds a custom chart.
   *
   * @param chart The chart to add.
   */
  public void addCustomChart(CustomChart chart) {
    metricsBase.addCustomChart(chart);
  }

  private void appendPlatformData(JsonObjectBuilder builder) {
    builder.appendField("osName", System.getProperty("os.name"));
    builder.appendField("osArch", System.getProperty("os.arch"));
    builder.appendField("osVersion", System.getProperty("os.version"));
    builder.appendField("coreCount", Runtime.getRuntime().availableProcessors());
  }

  static class VelocityMetrics {

    private static final Logger logger = LogManager.getLogger(Metrics.class);

    static void startMetrics(VelocityServer server, VelocityConfiguration.Metrics metricsConfig) {
      // PaperProxy reports to its own "server implementation" page, never to Velocity's (4752):
      // https://bstats.org/plugin/server-implementation/PaperProxy/34471
      Metrics metrics = new Metrics(logger, 34471, metricsConfig.isEnabled());

      metrics.addCustomChart(
          new SingleLineChart("players", server::getPlayerCount)
      );
      metrics.addCustomChart(
          new SingleLineChart("managed_servers", () -> server.getAllServers().size())
      );
      metrics.addCustomChart(
          new SimplePie("online_mode",
              () -> server.getConfiguration().isOnlineMode() ? "online" : "offline")
      );
      metrics.addCustomChart(new SimplePie("paperproxy_version",
          net.paperstream.paperproxy.PaperProxyBranding::version));
      metrics.addCustomChart(new AdvancedPie("plugin_types", () -> {
        final List<String> bungee = server.getBungeeLayer() == null ? List.of()
            : server.getBungeeLayer().pluginNames();
        // Not real plugins: the proxy itself, the Bungee layer, and Bungee plugins (which are
        // registered as Velocity containers too and counted separately).
        final long velocityPlugins = server.getPluginManager().getPlugins().stream()
            .map(plugin -> plugin.getDescription())
            .filter(d -> !d.getId().equals("velocity") && !d.getId().equals("paperproxy-bungee"))
            .filter(d -> !bungee.contains(d.getName().orElse(d.getId())))
            .count();
        return Map.of("Velocity", (int) velocityPlugins, "BungeeCord", bungee.size());
      }));
      metrics.addCustomChart(new AdvancedPie("bungee_plugins", () -> {
        final Map<String, Integer> plugins = new HashMap<>();
        if (server.getBungeeLayer() != null) {
          for (final String name : server.getBungeeLayer().pluginNames()) {
            plugins.put(name.length() > 32 ? name.substring(0, 32) : name, 1);
          }
        }
        return plugins;
      }));
      metrics.addCustomChart(new AdvancedPie("forwarding_modes", () -> {
        final Map<String, Integer> modes = new HashMap<>();
        server.getAllServers().forEach(registered -> modes.merge(server.getForwarding()
            .modeFor(registered.getServerInfo().getName()).name().toLowerCase(Locale.ROOT), 1,
            Integer::sum));
        return modes;
      }));
      metrics.addCustomChart(new SimplePie("auto_updater",
          () -> server.getPaperProxyConfig().values().autoUpdate() ? "enabled" : "disabled"));
      metrics.addCustomChart(new SimplePie("plugin_reload_used",
          () -> server.getPluginReloader().changes() > 0 ? "yes" : "no"));
      // Counts every join since the last submission, so the chart is not empty when nobody
      // happens to be online at the moment bStats sends its data.
      final Map<String, Integer> joins = new ConcurrentHashMap<>();
      server.getEventManager().register(VelocityVirtualPlugin.INSTANCE, PostLoginEvent.class,
          event -> joins.merge(
              event.getPlayer().getProtocolVersion().getMostRecentSupportedVersion(), 1,
              Integer::sum));
      metrics.addCustomChart(new AdvancedPie("client_versions", () -> {
        final Map<String, Integer> versions = new HashMap<>();
        for (final String version : List.copyOf(joins.keySet())) {
          versions.put(version, joins.remove(version));
        }
        // Players who stay online for longer than one interval still count.
        server.getAllPlayers().forEach(player -> versions.putIfAbsent(
            player.getProtocolVersion().getMostRecentSupportedVersion(), 1));
        return versions;
      }));
      metrics.addCustomChart(new SimplePie("via_on_proxy",
          () -> server.getPluginManager().isLoaded("viaversion") ? "yes" : "no"));

      metrics.addCustomChart(new DrilldownPie("java_version", () -> {
        Runtime.Version version = Runtime.version();

        return Map.of(
            "Java " + version.feature(),
            Map.of(javaVersion(version), 1));
      }));
    }
  }

  /**
   * Recreates the exact {@code java.version} system property value from a {@link Runtime.Version}.
   *
   * <p>Per <a href="https://openjdk.org/jeps/223">JEP 223</a>, {@code java.version} is
   * {@code $VNUM(-$PRE)?}; the build and optional segments only appear in {@code java.runtime.version}.
   *
   * @param v the runtime version
   * @return the value {@code java.version} would hold on this JVM
   */
  private static String javaVersion(Runtime.Version v) {
    return v.version().stream()
        .map(Object::toString)
        .collect(Collectors.joining("."))
        + v.pre().map(p -> "-" + p).orElse("");
  }
}
