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

package net.paperstream.paperproxy.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.proxy.VelocityServer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Receives what PaperGuard (formerly PaperProxy-Bridge) reports about each backend and warns about known problems,
 * such as ViaVersion running on the proxy and on a backend at the same time.
 */
public final class BridgeReports {

  /** The channel PaperGuard reports on. The name is kept from PaperProxy-Bridge. */
  public static final MinecraftChannelIdentifier CHANNEL =
      MinecraftChannelIdentifier.from("paperproxy:bridge");
  /** Where backend owners get PaperGuard. */
  public static final String PAPERGUARD_URL = "https://github.com/LucasTHCRR/PaperGuard";
  private static final List<String> VIA = List.of("viaversion", "viabackwards", "viarewind");
  private static final Logger logger = LogManager.getLogger(BridgeReports.class);

  private final VelocityServer server;
  private final Map<String, Report> reports = new ConcurrentHashMap<>();

  /**
   * What a backend reported.
   *
   * @param bridgeVersion the PaperGuard version (or old PaperProxy-Bridge version)
   * @param software the server software
   * @param plugins the plugin names
   */
  public record Report(String bridgeVersion, String software, List<String> plugins) {
  }

  /**
   * Creates the receiver.
   *
   * @param server the proxy
   */
  public BridgeReports(final VelocityServer server) {
    this.server = server;
  }

  /**
   * Returns the latest report per server name.
   *
   * @return the reports
   */
  public Map<String, Report> reports() {
    return Map.copyOf(reports);
  }

  /**
   * Handles reports. They are never forwarded to the player.
   *
   * @param event the event
   */
  @Subscribe
  public void onMessage(final PluginMessageEvent event) {
    if (!event.getIdentifier().equals(CHANNEL)) {
      return;
    }
    event.setResult(PluginMessageEvent.ForwardResult.handled());
    if (!(event.getSource() instanceof ServerConnection connection)) {
      return;
    }
    final String serverName = connection.getServerInfo().getName();
    try {
      final JsonObject json = JsonParser.parseString(
          new String(event.getData(), StandardCharsets.UTF_8)).getAsJsonObject();
      final List<String> plugins = new ArrayList<>();
      final JsonArray array = json.getAsJsonArray("plugins");
      if (array != null) {
        for (final JsonElement element : array) {
          plugins.add(element.getAsString());
        }
      }
      final boolean oldBridge = !json.has("paperguard");
      final Report report = new Report(json.get(oldBridge ? "bridge" : "paperguard").getAsString(),
          json.has("software") ? json.get("software").getAsString() : "?", List.copyOf(plugins));
      reports.put(serverName, report);
      check(serverName, report, oldBridge);
    } catch (final RuntimeException e) {
      logger.warn("Ignoring an invalid PaperGuard report from {}", serverName);
    }
  }

  private void check(final String serverName, final Report report, final boolean oldBridge) {
    for (final String plugin : report.plugins()) {
      final String lower = plugin.toLowerCase(Locale.ROOT);
      if (VIA.contains(lower) && server.getPluginManager().isLoaded(lower)) {
        logger.warn("{} runs on the proxy AND on '{}'. Remove it from the backend, otherwise "
            + "packets are translated twice and players get kicked.", plugin, serverName);
      }
    }
    if (oldBridge) {
      logger.warn("'{}' runs PaperProxy-Bridge {}, which is now called PaperGuard. Replace it "
          + "with PaperGuard from {}, your settings are taken over.", serverName,
          report.bridgeVersion(), PAPERGUARD_URL);
    }
  }
}
