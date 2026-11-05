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
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.paperstream.paperproxy.config.PaperProxyConfig;
import net.paperstream.paperproxy.messages.MessageFormatter;

/**
 * Header and footer of the player list, with placeholders.
 */
public final class TabHeaderFooter {

  private final VelocityServer server;
  private final Map<UUID, Integer> sent = new ConcurrentHashMap<>();
  private int seconds;

  public TabHeaderFooter(final VelocityServer server) {
    this.server = server;
  }

  /**
   * Starts the updates.
   */
  public void start() {
    server.getScheduler().buildTask(VelocityVirtualPlugin.INSTANCE, this::update)
        .repeat(1, TimeUnit.SECONDS).schedule();
  }

  // nach einem Serverwechsel neu senden, das Backend kann die Zeilen ersetzt haben
  @Subscribe
  public void onServerConnected(final ServerPostConnectEvent event) {
    sent.remove(event.getPlayer().getUniqueId());
  }

  private void update() {
    final PaperProxyConfig.Values values = server.getPaperProxyConfig().values();
    final PaperProxyConfig.TabList settings = values.tabList();
    if (!settings.enabled()) {
      sent.clear();
      return;
    }
    if (++seconds < settings.updateSeconds()) {
      return;
    }
    seconds = 0;
    final String online = String.valueOf(server.getPlayerCount());
    final String max = String.valueOf(values.maxPlayers() > 0 ? values.maxPlayers()
        : server.getConfiguration().getShowMaxPlayers());
    final String network = String.valueOf(server.getNetworkSync().connected()
        ? server.getNetworkSync().networkPlayerCount() : server.getPlayerCount());
    sent.keySet().removeIf(id -> server.getPlayer(id).isEmpty());
    for (final Player player : server.getAllPlayers()) {
      final String header = fill(settings.header(), player, online, max, network);
      final String footer = fill(settings.footer(), player, online, max, network);
      final int hash = 31 * header.hashCode() + footer.hashCode();
      final Integer last = sent.put(player.getUniqueId(), hash);
      if (last != null && last == hash) {
        continue;
      }
      player.sendPlayerListHeaderAndFooter(parse(header), parse(footer));
    }
  }

  private static String fill(final String text, final Player player, final String online,
                             final String max, final String network) {
    return text.replace("{online}", online)
        .replace("{max}", max)
        .replace("{network_online}", network)
        .replace("{player}", player.getUsername())
        .replace("{server}", player.getCurrentServer()
            .map(connection -> connection.getServerInfo().getName()).orElse(""))
        .replace("{ping}", String.valueOf(player.getPing()));
  }

  private static Component parse(final String text) {
    return MiniMessage.miniMessage().deserialize(MessageFormatter.legacyToMiniMessage(text));
  }
}
