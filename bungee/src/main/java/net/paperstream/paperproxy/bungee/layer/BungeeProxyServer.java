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

package net.paperstream.paperproxy.bungee.layer;

import com.velocitypowered.api.network.ProtocolVersion;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.Format;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.md_5.bungee.BungeeCord;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyConfig;
import net.md_5.bungee.api.ReconnectHandler;
import net.md_5.bungee.api.Title;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.config.ConfigurationAdapter;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.PluginManager;
import net.paperstream.paperproxy.PaperProxyBranding;

/**
 * PaperProxy as {@code net.md_5.bungee.api.ProxyServer}.
 */
final class BungeeProxyServer extends BungeeCord {

  private final BungeeLayer layer;
  private final Logger logger = JulBridge.createProxyLogger();
  private final Set<String> channels = ConcurrentHashMap.newKeySet();
  private volatile ConfigurationAdapter configurationAdapter;
  private volatile ReconnectHandler reconnectHandler;
  private final net.md_5.bungee.scheduler.BungeeScheduler scheduler;
  /**
   * Same name and type as in BungeeCord, because plugins such as BungeeMessages replace the
   * proxy's texts by writing this field through reflection.
   */
  @SuppressWarnings("FieldMayBeFinal")
  private Map<String, Format> messageFormats = loadMessageFormats();

  BungeeProxyServer(final BungeeLayer layer) {
    this.layer = layer;
    this.scheduler = new net.md_5.bungee.scheduler.BungeeScheduler(layer.scheduler());
    this.configurationAdapter = new BungeeConfig.Adapter(layer);
  }

  @Override
  public String getName() {
    return PaperProxyBranding.NAME;
  }

  @Override
  public String getVersion() {
    return layer.velocity().getVersion().getVersion();
  }

  @Override
  public String getTranslation(final String name, final Object... args) {
    final Format format = messageFormats.get(name);
    return format == null ? "<translation '" + name + "' missing>" : format.format(args);
  }

  private static Map<String, Format> loadMessageFormats() {
    final Properties properties = new Properties();
    try (InputStream in = BungeeProxyServer.class
        .getResourceAsStream("/paperproxy/bungee-messages.properties")) {
      if (in != null) {
        properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    final Map<String, Format> formats = new HashMap<>();
    for (final String key : properties.stringPropertyNames()) {
      formats.put(key, new MessageFormat(properties.getProperty(key).replace("'", "''")));
    }
    return formats;
  }

  @Override
  public Logger getLogger() {
    return logger;
  }

  @Override
  public Collection<ProxiedPlayer> getPlayers() {
    final List<ProxiedPlayer> players = new java.util.ArrayList<>();
    layer.velocity().getAllPlayers().forEach(p -> players.add(layer.player(p)));
    // In BungeeCord a player still counts while PlayerDisconnectEvent runs.
    for (final com.velocitypowered.api.proxy.Player leaving : layer.leaving()) {
      if (layer.velocity().getPlayer(leaving.getUniqueId()).isEmpty()) {
        players.add(layer.player(leaving));
      }
    }
    return Collections.unmodifiableList(players);
  }

  @Override
  public ProxiedPlayer getPlayer(final String name) {
    return layer.velocity().getPlayer(name).or(() -> layer.leaving().stream()
        .filter(p -> p.getUsername().equalsIgnoreCase(name)).findFirst())
        .map(layer::player).orElse(null);
  }

  @Override
  public ProxiedPlayer getPlayer(final UUID uuid) {
    return layer.velocity().getPlayer(uuid).or(() -> layer.leaving().stream()
        .filter(p -> p.getUniqueId().equals(uuid)).findFirst())
        .map(layer::player).orElse(null);
  }

  @Override
  @Deprecated
  public Map<String, ServerInfo> getServers() {
    return layer.serverMap();
  }

  @Override
  public ServerInfo getServerInfo(final String name) {
    return layer.serverMap().get(name);
  }

  @Override
  public PluginManager getPluginManager() {
    return layer.pluginManager();
  }

  @Override
  public ConfigurationAdapter getConfigurationAdapter() {
    return configurationAdapter;
  }

  @Override
  public void setConfigurationAdapter(final ConfigurationAdapter adapter) {
    this.configurationAdapter = adapter;
  }

  @Override
  public ReconnectHandler getReconnectHandler() {
    return reconnectHandler;
  }

  @Override
  public void setReconnectHandler(final ReconnectHandler handler) {
    Unsupported.ignored("ProxyServer.setReconnectHandler",
        "PaperProxy chooses servers through its own try list and events");
    this.reconnectHandler = handler;
  }

  @Override
  public void stop() {
    layer.velocity().shutdown(true);
  }

  @Override
  public void stop(final String reason) {
    layer.velocity().shutdown(true, Chat.fromLegacy(reason));
  }

  @Override
  public void registerChannel(final String channel) {
    if (channels.add(channel)) {
      layer.velocity().getChannelRegistrar().register(Channels.identifier(channel));
    }
  }

  @Override
  public void unregisterChannel(final String channel) {
    if (channels.remove(channel)) {
      layer.velocity().getChannelRegistrar().unregister(Channels.identifier(channel));
    }
  }

  @Override
  public Collection<String> getChannels() {
    return Collections.unmodifiableSet(channels);
  }

  @Override
  @Deprecated
  public String getGameVersion() {
    return ProtocolVersion.MAXIMUM_VERSION.getMostRecentSupportedVersion();
  }

  @Override
  @Deprecated
  public int getProtocolVersion() {
    return ProtocolVersion.MAXIMUM_VERSION.getProtocol();
  }

  @Override
  public ServerInfo constructServerInfo(final String name, final InetSocketAddress address,
                                        final String motd, final boolean restricted) {
    return new BungeeServerInfo(layer, name, address, motd, restricted);
  }

  @Override
  public ServerInfo constructServerInfo(final String name, final SocketAddress address,
                                        final String motd, final boolean restricted) {
    if (!(address instanceof InetSocketAddress inet)) {
      throw new IllegalArgumentException("PaperProxy only supports network addresses: " + address);
    }
    return constructServerInfo(name, inet, motd, restricted);
  }

  @Override
  public CommandSender getConsole() {
    return layer.console();
  }

  @Override
  public File getPluginsFolder() {
    return layer.pluginsDirectory().toFile();
  }

  @Override
  public net.md_5.bungee.scheduler.BungeeScheduler getScheduler() {
    return scheduler;
  }

  @Override
  public int getOnlineCount() {
    int count = layer.velocity().getPlayerCount();
    for (final com.velocitypowered.api.proxy.Player leaving : layer.leaving()) {
      if (layer.velocity().getPlayer(leaving.getUniqueId()).isEmpty()) {
        count++;
      }
    }
    return count;
  }

  @Override
  @Deprecated
  public void broadcast(final String message) {
    layer.velocity().sendMessage(Chat.fromLegacy(message));
  }

  @Override
  public void broadcast(final BaseComponent... message) {
    layer.velocity().sendMessage(Chat.toAdventure(message));
  }

  @Override
  public void broadcast(final BaseComponent message) {
    layer.velocity().sendMessage(Chat.toAdventure(message));
  }

  @Override
  @Deprecated
  public Collection<String> getDisabledCommands() {
    return layer.config().getDisabledCommands();
  }

  @Override
  public ProxyConfig getConfig() {
    return layer.config();
  }

  @Override
  public Collection<ProxiedPlayer> matchPlayer(final String match) {
    final ProxiedPlayer exact = getPlayer(match);
    if (exact != null) {
      return List.of(exact);
    }
    final String lower = match.toLowerCase(Locale.ROOT);
    return layer.velocity().getAllPlayers().stream()
        .filter(player -> player.getUsername().toLowerCase(Locale.ROOT).startsWith(lower))
        .map(layer::player)
        .map(ProxiedPlayer.class::cast)
        .toList();
  }

  @Override
  public Title createTitle() {
    return new BungeeTitle();
  }

  @Override
  public Unsafe unsafe() {
    throw Unsupported.fail("ProxyServer.unsafe", "replacing BungeeCord's network pipeline");
  }
}
