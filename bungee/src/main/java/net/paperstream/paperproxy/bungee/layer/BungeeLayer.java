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

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.plugin.VelocityPluginManager;
import com.velocitypowered.proxy.plugin.loader.VelocityPluginContainer;
import com.velocitypowered.proxy.plugin.loader.VelocityPluginDescription;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ListenerInfo;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.PluginDescription;
import net.md_5.bungee.api.plugin.PluginManager;
import net.paperstream.paperproxy.bungee.BungeeLayerHandle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.PropertyUtils;

/**
 * Entry point of the BungeeCord compatibility layer. Created by
 * {@link net.paperstream.paperproxy.bungee.BungeeLayerBootstrap} through reflection inside the
 * layer's own class loader.
 */
public final class BungeeLayer implements BungeeLayerHandle {

  private static final Logger logger = LogManager.getLogger(BungeeLayer.class);
  private static final String LAYER_PLUGIN_ID = "paperproxy-bungee";

  private final VelocityServer velocity;
  private final Path pluginsDirectory;
  private final List<Path> jars;
  private final BungeeProxyServer proxy;
  private final PluginManager pluginManager;
  private final BungeeScheduler scheduler = new BungeeScheduler();
  private final BungeeConsole console;
  private final BungeeConfig config;
  private final ServerMap serverMap;
  private final Cookies cookies = new Cookies();
  private final CommandBridge commands;
  private final Map<UUID, BungeePlayer> players = new ConcurrentHashMap<>();
  private final Set<Player> leaving = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final Map<String, BungeeServerInfo> serverInfos = new ConcurrentHashMap<>();
  private volatile ListenerInfo listenerInfo;
  private volatile ScheduledTask commandSync;

  /**
   * Creates the layer. Called reflectively by the bootstrap.
   *
   * @param velocity the proxy
   * @param pluginsDirectory the plugins directory
   * @param jars the BungeeCord plugin jars found there
   */
  public BungeeLayer(final VelocityServer velocity, final Path pluginsDirectory,
                     final List<Path> jars) {
    this.velocity = velocity;
    this.pluginsDirectory = pluginsDirectory;
    this.jars = jars;
    this.console = new BungeeConsole(this);
    this.config = new BungeeConfig(this);
    this.serverMap = new ServerMap(this);
    this.commands = new CommandBridge(this);
    this.proxy = new BungeeProxyServer(this);
    ProxyServer.setInstance(proxy);
    this.pluginManager = new PluginManager(proxy);
  }

  @Override
  public void enable() {
    // Libraries such as SnakeYAML live in the proxy's class loader and look classes up through
    // the context class loader; without this they cannot see BungeeCord classes.
    final Thread thread = Thread.currentThread();
    final ClassLoader previous = thread.getContextClassLoader();
    thread.setContextClassLoader(BungeeLayer.class.getClassLoader());
    try {
      enable0();
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  private void enable0() {
    // The layer itself is a plugin to Velocity so it can own listeners, commands and tasks.
    pluginManagerV().registerPlugin(container(LAYER_PLUGIN_ID, "PaperProxy Bungee Layer",
        velocity.getVersion().getVersion(), "BungeeCord plugin support", this));
    velocity.getEventManager().register(this, new EventBridge(this));
    this.listenerInfo = BungeeConfig.listener(this);

    queuePlugins();
    pluginManager.loadPlugins();
    for (final Plugin plugin : pluginManager.getPlugins()) {
      registerContainer(plugin);
    }
    pluginManager.enablePlugins();

    commands.sync();
    this.commandSync = velocity.getScheduler().buildTask(this, commands::sync)
        .repeat(2, TimeUnit.SECONDS).schedule();
    logger.info("BungeeCord layer enabled with {} plugin(s): {}", pluginManager.getPlugins().size(),
        String.join(", ", pluginNames()));
  }

  @Override
  public void shutdown() {
    if (commandSync != null) {
      commandSync.cancel();
    }
    final List<Plugin> plugins = new ArrayList<>(pluginManager.getPlugins());
    java.util.Collections.reverse(plugins);
    for (final Plugin plugin : plugins) {
      try {
        plugin.onDisable();
      } catch (final Throwable t) {
        logger.error("Error disabling BungeeCord plugin {}", plugin.getDescription().getName(), t);
      }
      scheduler.cancel(plugin);
      pluginManager.unregisterListeners(plugin);
      pluginManager.unregisterCommands(plugin);
    }
    scheduler.shutdown();
    commands.clear();
  }

  @Override
  public List<String> pluginNames() {
    return pluginManager.getPlugins().stream().map(p -> p.getDescription().getName()).toList();
  }

  @Override
  public java.util.Optional<String> findPlugin(final String name) {
    return pluginManager.getPlugins().stream()
        .map(plugin -> plugin.getDescription().getName())
        .filter(pluginName -> pluginName.equalsIgnoreCase(name))
        .findFirst();
  }

  @Override
  public Path pluginFile(final String name) {
    return pluginManager.getPlugin(name).getDescription().getFile().toPath();
  }

  @Override
  @SuppressWarnings("unchecked")
  public ClassLoader unloadPlugin(final String name) {
    final Plugin plugin = pluginManager.getPlugin(name);
    if (plugin == null) {
      throw new IllegalArgumentException("BungeeCord plugin " + name + " is not loaded");
    }
    final ClassLoader loader = plugin.getClass().getClassLoader();
    try {
      plugin.onDisable();
    } catch (final Throwable t) {
      logger.error("Error disabling BungeeCord plugin {}", name, t);
    }
    scheduler.cancel(plugin);
    pluginManager.unregisterListeners(plugin);
    pluginManager.unregisterCommands(plugin);
    commands.sync();
    try {
      ((Map<String, Plugin>) field(PluginManager.class, "plugins").get(pluginManager))
          .remove(name);
      final Object service = field(Plugin.class, "service").get(plugin);
      if (service instanceof java.util.concurrent.ExecutorService executor) {
        executor.shutdownNow();
      }
      final Class<?> loaderClass = Class.forName("net.md_5.bungee.api.plugin.PluginClassloader");
      ((java.util.Set<?>) field(loaderClass, "allLoaders").get(null)).remove(loader);
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException("Incompatible BungeeCord API version", e);
    }
    velocity.getPluginManager().fromInstance(plugin).ifPresent(pluginManagerV()::unregisterPlugin);
    if (loader instanceof java.io.Closeable closeable) {
      try {
        closeable.close();
      } catch (final java.io.IOException e) {
        logger.warn("Could not close the class loader of {}", name, e);
      }
    }
    return loader;
  }

  @Override
  @SuppressWarnings("unchecked")
  public String loadPlugin(final Path jar) throws Exception {
    final Thread thread = Thread.currentThread();
    final ClassLoader previous = thread.getContextClassLoader();
    thread.setContextClassLoader(BungeeLayer.class.getClassLoader());
    try {
      final PluginDescription description = readDescription(jar);
      if (pluginManager.getPlugin(description.getName()) != null) {
        throw new IllegalStateException(description.getName() + " is already loaded");
      }
      for (final String depend : description.getDepends()) {
        if (pluginManager.getPlugin(depend) == null) {
          throw new IllegalStateException("Missing dependency " + depend);
        }
      }
      final Map<String, PluginDescription> toLoad = new java.util.HashMap<>();
      toLoad.put(description.getName(), description);
      field(PluginManager.class, "toLoad").set(pluginManager, toLoad);
      pluginManager.loadPlugins();
      final Plugin plugin = pluginManager.getPlugin(description.getName());
      if (plugin == null) {
        throw new IllegalStateException(description.getName() + " failed to load, see above");
      }
      registerContainer(plugin);
      plugin.onEnable();
      commands.sync();
      return description.getName();
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  private static Field field(final Class<?> owner, final String name)
      throws NoSuchFieldException {
    final Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static PluginDescription readDescription(final Path path) throws java.io.IOException {
    final Constructor constructor = new Constructor(new LoaderOptions());
    final PropertyUtils propertyUtils = constructor.getPropertyUtils();
    propertyUtils.setSkipMissingProperties(true);
    constructor.setPropertyUtils(propertyUtils);
    try (JarFile jar = new JarFile(path.toFile())) {
      JarEntry entry = jar.getJarEntry("bungee.yml");
      if (entry == null) {
        entry = jar.getJarEntry("plugin.yml");
      }
      if (entry == null) {
        throw new java.io.IOException(path.getFileName() + " has no bungee.yml or plugin.yml");
      }
      try (InputStream in = jar.getInputStream(entry)) {
        final PluginDescription description = new Yaml(constructor).loadAs(in,
            PluginDescription.class);
        if (description == null || description.getName() == null
            || description.getMain() == null) {
          throw new java.io.IOException(path.getFileName() + " has no name or main");
        }
        description.setFile(path.toFile());
        return description;
      }
    }
  }

  /**
   * Reads the descriptions of our jars and hands them to Bungee's PluginManager. Bungee's own
   * detectPlugins would scan every jar in the folder, including Velocity plugins, and complain
   * about each of them.
   */
  @SuppressWarnings("unchecked")
  private void queuePlugins() {
    final Constructor constructor = new Constructor(new LoaderOptions());
    final PropertyUtils propertyUtils = constructor.getPropertyUtils();
    propertyUtils.setSkipMissingProperties(true);
    constructor.setPropertyUtils(propertyUtils);
    final Yaml yaml = new Yaml(constructor);

    final Map<String, PluginDescription> toLoad;
    try {
      final Field field = PluginManager.class.getDeclaredField("toLoad");
      field.setAccessible(true);
      toLoad = (Map<String, PluginDescription>) field.get(pluginManager);
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException("Incompatible BungeeCord API version", e);
    }

    for (final Path path : jars) {
      try (JarFile jar = new JarFile(path.toFile())) {
        JarEntry entry = jar.getJarEntry("bungee.yml");
        if (entry == null) {
          entry = jar.getJarEntry("plugin.yml");
        }
        try (InputStream in = jar.getInputStream(entry)) {
          final PluginDescription description = yaml.loadAs(in, PluginDescription.class);
          if (description == null || description.getName() == null
              || description.getMain() == null) {
            logger.error("Skipping {}: its bungee.yml/plugin.yml has no name or main",
                path.getFileName());
            continue;
          }
          description.setFile(path.toFile());
          if (toLoad.containsKey(description.getName())) {
            logger.error("Skipping {}: a plugin named {} is already queued", path.getFileName(),
                description.getName());
            continue;
          }
          toLoad.put(description.getName(), description);
        }
      } catch (final Exception e) {
        logger.error("Could not read BungeeCord plugin {}", path.getFileName(), e);
      }
    }
  }

  private void registerContainer(final Plugin plugin) {
    final PluginDescription description = plugin.getDescription();
    String id = description.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    if (id.isEmpty() || !Character.isLetter(id.charAt(0))) {
      id = "b" + id;
    }
    if (id.length() > 64) {
      id = id.substring(0, 64);
    }
    if (velocity.getPluginManager().getPlugin(id).isPresent()) {
      id = ("bungee-" + id).substring(0, Math.min(64, id.length() + 7));
    }
    pluginManagerV().registerPlugin(container(id, description.getName(),
        description.getVersion(), description.getDescription(), plugin));
  }

  private VelocityPluginManager pluginManagerV() {
    return (VelocityPluginManager) velocity.getPluginManager();
  }

  private PluginContainer container(final String id, final String name, final String version,
                                    final String description, final Object instance) {
    final VelocityPluginContainer container = new VelocityPluginContainer(
        new VelocityPluginDescription(id, name, version, description, null, List.of(),
            List.of(), List.of(), pluginsDirectory));
    container.setInstance(instance);
    return container;
  }

  // ------------------------------------------------------------------ lookups

  VelocityServer velocity() {
    return velocity;
  }

  Path pluginsDirectory() {
    return pluginsDirectory;
  }

  PluginManager pluginManager() {
    return pluginManager;
  }

  BungeeScheduler scheduler() {
    return scheduler;
  }

  BungeeConsole console() {
    return console;
  }

  BungeeConfig config() {
    return config;
  }

  ServerMap serverMap() {
    return serverMap;
  }

  Cookies cookies() {
    return cookies;
  }

  ListenerInfo listenerInfo() {
    return listenerInfo != null ? listenerInfo : BungeeConfig.listener(this);
  }

  BungeePlayer player(final Player player) {
    return players.computeIfAbsent(player.getUniqueId(), id -> new BungeePlayer(this, player));
  }

  /**
   * Players whose PlayerDisconnectEvent is running. BungeeCord still lists them then.
   *
   * @return the players
   */
  Set<Player> leaving() {
    return leaving;
  }

  void forgetPlayer(final Player player) {
    players.remove(player.getUniqueId());
  }

  CommandSender sender(final CommandSource source) {
    return source instanceof Player player ? player(player) : console;
  }

  /**
   * Returns the ServerInfo for a registered server name.
   *
   * @param name the server name
   * @return the info
   */
  BungeeServerInfo serverInfo(final String name) {
    return serverInfos.computeIfAbsent(name, n -> {
      final RegisteredServer server = velocity.getServer(n).orElse(null);
      final java.net.InetSocketAddress address = server == null
          ? java.net.InetSocketAddress.createUnresolved(n, 25565)
          : server.getServerInfo().getAddress();
      return new BungeeServerInfo(this, n, address, "", false);
    });
  }

  void forgetServerInfo(final String name) {
    serverInfos.remove(name);
  }

  /**
   * Returns the Velocity server for a Bungee ServerInfo, registering it when a plugin built the
   * info itself.
   *
   * @param info the info
   * @return the registered server
   */
  RegisteredServer registered(final ServerInfo info) {
    if (info instanceof BungeeServerInfo bungee) {
      return bungee.registeredOrCreate();
    }
    return velocity.getServer(info.getName()).orElseGet(() -> velocity.registerServer(
        new com.velocitypowered.api.proxy.server.ServerInfo(info.getName(), info.getAddress())));
  }
}
