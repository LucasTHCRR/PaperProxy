/*
 * Copyright (C) 2018-2023 Velocity Contributors
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

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPreShutdownEvent;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.util.Favicon;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.api.util.ProxyVersion;
import com.velocitypowered.proxy.command.VelocityCommandManager;
import com.velocitypowered.proxy.command.builtin.CallbackCommand;
import com.velocitypowered.proxy.command.builtin.GlistCommand;
import com.velocitypowered.proxy.command.builtin.SendCommand;
import com.velocitypowered.proxy.command.builtin.ServerCommand;
import com.velocitypowered.proxy.command.builtin.ShutdownCommand;
import com.velocitypowered.proxy.command.builtin.VelocityCommand;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.player.resourcepack.VelocityResourcePackInfo;
import com.velocitypowered.proxy.connection.util.ServerListPingHandler;
import com.velocitypowered.proxy.console.VelocityConsole;
import com.velocitypowered.proxy.crypto.EncryptionUtils;
import com.velocitypowered.proxy.event.VelocityEventManager;
import com.velocitypowered.proxy.network.ConnectionManager;
import com.velocitypowered.proxy.plugin.VelocityPluginManager;
import com.velocitypowered.proxy.plugin.loader.VelocityPluginContainer;
import com.velocitypowered.proxy.plugin.loader.VelocityPluginDescription;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.util.FaviconSerializer;
import com.velocitypowered.proxy.protocol.util.GameProfileSerializer;
import com.velocitypowered.proxy.scheduler.VelocityScheduler;
import com.velocitypowered.proxy.server.ServerMap;
import com.velocitypowered.proxy.util.AddressUtil;
import com.velocitypowered.proxy.util.ClosestLocaleMatcher;
import com.velocitypowered.proxy.util.ResourceUtils;
import com.velocitypowered.proxy.util.VelocityChannelRegistrar;
import com.velocitypowered.proxy.util.ratelimit.Ratelimiter;
import com.velocitypowered.proxy.util.ratelimit.Ratelimiters;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.audience.ForwardingAudience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.MiniMessageTranslationStore;
import net.kyori.adventure.translation.GlobalTranslator;
import net.paperstream.paperproxy.PaperProxyApi;
import net.paperstream.paperproxy.PaperProxyBranding;
import net.paperstream.paperproxy.api.PaperProxyProvider;
import net.paperstream.paperproxy.bridge.BridgeReports;
import net.paperstream.paperproxy.bungee.BungeeLayerBootstrap;
import net.paperstream.paperproxy.bungee.BungeeLayerHandle;
import net.paperstream.paperproxy.command.NetworkCommands;
import net.paperstream.paperproxy.command.PaperProxyCommand;
import net.paperstream.paperproxy.command.PartyCommand;
import net.paperstream.paperproxy.command.PunishCommands;
import net.paperstream.paperproxy.config.PaperProxyConfig;
import net.paperstream.paperproxy.forwarding.Forwarding;
import net.paperstream.paperproxy.messages.PaperProxyMessages;
import net.paperstream.paperproxy.network.AntiBot;
import net.paperstream.paperproxy.network.DiscordWebhook;
import net.paperstream.paperproxy.network.HealthChecker;
import net.paperstream.paperproxy.network.HiddenCommands;
import net.paperstream.paperproxy.network.Limbo;
import net.paperstream.paperproxy.network.MetricsEndpoint;
import net.paperstream.paperproxy.network.Motd;
import net.paperstream.paperproxy.network.NetworkRules;
import net.paperstream.paperproxy.network.PingCache;
import net.paperstream.paperproxy.network.ProxyStats;
import net.paperstream.paperproxy.network.QueryPassthrough;
import net.paperstream.paperproxy.network.RestartScheduler;
import net.paperstream.paperproxy.network.ServerGroups;
import net.paperstream.paperproxy.network.ServerQueue;
import net.paperstream.paperproxy.party.Parties;
import net.paperstream.paperproxy.plugin.PluginReloader;
import net.paperstream.paperproxy.plugin.UpdateFolder;
import net.paperstream.paperproxy.punish.Punishments;
import net.paperstream.paperproxy.sync.NetworkSync;
import net.paperstream.paperproxy.update.UpdateChecker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bstats.MetricsBase;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Implementation of {@link ProxyServer}.
 */
public class VelocityServer implements ProxyServer, ForwardingAudience {

  public static final String VELOCITY_URL = "https://papermc.io/software/velocity";

  private static final Logger logger = LogManager.getLogger(VelocityServer.class);
  public static final Gson GENERAL_GSON = new GsonBuilder()
      .registerTypeHierarchyAdapter(Favicon.class, FaviconSerializer.INSTANCE)
      .registerTypeHierarchyAdapter(GameProfile.class, GameProfileSerializer.INSTANCE)
      .create();
  private static final Gson PRE_1_16_PING_SERIALIZER = new GsonBuilder()
      .registerTypeHierarchyAdapter(
          Component.class,
          ProtocolUtils.getJsonChatSerializer(ProtocolVersion.MINECRAFT_1_15_2)
                  .serializer().getAdapter(Component.class)
      )
      .registerTypeHierarchyAdapter(Favicon.class, FaviconSerializer.INSTANCE)
      .create();
  private static final Gson PRE_1_20_3_PING_SERIALIZER = new GsonBuilder()
      .registerTypeHierarchyAdapter(
          Component.class,
          ProtocolUtils.getJsonChatSerializer(ProtocolVersion.MINECRAFT_1_20_2)
                  .serializer().getAdapter(Component.class)
      )
      .registerTypeHierarchyAdapter(Favicon.class, FaviconSerializer.INSTANCE)
      .create();
  private static final Gson MODERN_PING_SERIALIZER = new GsonBuilder()
      .registerTypeHierarchyAdapter(
          Component.class,
          ProtocolUtils.getJsonChatSerializer(ProtocolVersion.MINECRAFT_1_20_3)
                  .serializer().getAdapter(Component.class)
      )
      .registerTypeHierarchyAdapter(Favicon.class, FaviconSerializer.INSTANCE)
      .create();
  private static final int PRE_SHUTDOWN_TIMEOUT =
            Integer.getInteger("velocity.pre-shutdown-timeout", 10);

  private final ConnectionManager cm;
  private final ProxyOptions options;
  private @MonotonicNonNull VelocityConfiguration configuration;
  private @MonotonicNonNull KeyPair serverKeyPair;
  private final ServerMap servers;
  private final VelocityCommandManager commandManager;
  private final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);
  private boolean shutdown = false;
  private final VelocityPluginManager pluginManager;

  private final Map<UUID, ConnectedPlayer> connectionsByUuid = new ConcurrentHashMap<>();
  private final Map<String, ConnectedPlayer> connectionsByName = new ConcurrentHashMap<>();
  private final Object sessionIdLock = new Object();
  private volatile @Nullable UUID sessionId;
  private final VelocityConsole console;
  private @MonotonicNonNull Ratelimiter<InetAddress> ipAttemptLimiter;
  private @MonotonicNonNull Ratelimiter<UUID> commandRateLimiter;
  private @MonotonicNonNull Ratelimiter<UUID> tabCompleteRateLimiter;
  private final VelocityEventManager eventManager;
  private final VelocityScheduler scheduler;
  private final VelocityChannelRegistrar channelRegistrar = new VelocityChannelRegistrar();
  private final ServerListPingHandler serverListPingHandler;
  private final PaperProxyMessages messages = new PaperProxyMessages(Path.of(""));
  private volatile @Nullable BungeeLayerHandle bungeeLayer;
  private final PaperProxyConfig paperProxyConfig = new PaperProxyConfig(Path.of(""));
  private final Forwarding forwarding = new Forwarding(this, paperProxyConfig, Path.of(""));
  private final BridgeReports bridgeReports = new BridgeReports(this);
  private final PingCache pingCache = new PingCache(
      () -> paperProxyConfig.values().pingCacheSeconds());
  private final HealthChecker healthChecker = new HealthChecker(this);
  private final NetworkRules networkRules = new NetworkRules(this);
  private final ServerGroups serverGroups = new ServerGroups(this);
  private final ServerQueue serverQueue = new ServerQueue(this);
  private final AntiBot antiBot = new AntiBot(this, Path.of(""));
  private final MetricsEndpoint metricsEndpoint = new MetricsEndpoint(this);
  private final NetworkSync networkSync = new NetworkSync(this);
  private final DiscordWebhook discordWebhook = new DiscordWebhook(this);
  private final RestartScheduler restartScheduler = new RestartScheduler(this);
  private final Punishments punishments = new Punishments(this, Path.of(""));
  private final Parties parties = new Parties(this);
  private final ProxyStats proxyStats = new ProxyStats();
  private final Limbo limbo = new Limbo(this);
  private final PaperProxyApi paperProxyApi = new PaperProxyApi(this);
  private volatile boolean listening;
  private final Motd motd = new Motd(Path.of(""));
  private final PluginReloader pluginReloader = new PluginReloader(this);
  private final UpdateChecker updateChecker = new UpdateChecker(this);

  VelocityServer(final ProxyOptions options) {
    pluginManager = new VelocityPluginManager(this);
    eventManager = new VelocityEventManager(pluginManager);
    commandManager = new VelocityCommandManager(eventManager, pluginManager);
    scheduler = new VelocityScheduler(pluginManager);
    console = new VelocityConsole(this);
    cm = new ConnectionManager(this);
    servers = new ServerMap(this);
    serverListPingHandler = new ServerListPingHandler(this);
    this.options = options;
  }

  public KeyPair getServerKeyPair() {
    return serverKeyPair;
  }

  /**
   * Reads paperproxy.toml and, when PaperGuard is used, its secret.
   */
  private void loadPaperProxyConfig() {
    paperProxyConfig.load();
    eventManager.setWatchdogMillis(paperProxyConfig.values().watchdogMillis());
    motd.load();
    pingCache.clear();
    healthChecker.start();
    serverQueue.start();
    metricsEndpoint.apply();
    if (listening) {
      // On the first load the bind address is not known yet; the sync starts after binding.
      networkSync.apply();
    }
    if (forwarding.paperGuardInUse()) {
      try {
        forwarding.loadSecret();
      } catch (IOException e) {
        logger.error("Unable to load the PaperGuard secret; PaperGuard logins will fail", e);
      }
    }
  }

  /**
   * Returns the PaperProxy settings from paperproxy.toml.
   *
   * @return the settings
   */
  public PaperProxyConfig getPaperProxyConfig() {
    return paperProxyConfig;
  }

  /**
   * Returns the per-server forwarding and PaperGuard.
   *
   * @return the forwarding
   */
  public Forwarding getForwarding() {
    return forwarding;
  }

  /**
   * Returns the BungeeCord layer, or null when no BungeeCord plugin is installed.
   *
   * @return the layer
   */
  public @Nullable BungeeLayerHandle getBungeeLayer() {
    return bungeeLayer;
  }

  /**
   * Returns the update checker.
   *
   * @return the checker
   */
  public UpdateChecker getUpdateChecker() {
    return updateChecker;
  }

  /**
   * Returns the runtime plugin loader.
   *
   * @return the reloader
   */
  public PluginReloader getPluginReloader() {
    return pluginReloader;
  }

  /**
   * Returns the motd.yml settings.
   *
   * @return the MOTD
   */
  public Motd getMotd() {
    return motd;
  }

  /**
   * Returns the server list cache.
   *
   * @return the cache
   */
  public PingCache getPingCache() {
    return pingCache;
  }

  /**
   * Returns the backend health checks.
   *
   * @return the checker
   */
  public HealthChecker getHealthChecker() {
    return healthChecker;
  }

  /**
   * Returns maintenance, version and health rules.
   *
   * @return the rules
   */
  public NetworkRules getNetworkRules() {
    return networkRules;
  }

  private @Nullable InetSocketAddress transferTarget() {
    final String target = paperProxyConfig.values().transferOnShutdown();
    if (target.isEmpty()) {
      return null;
    }
    try {
      final InetSocketAddress parsed = AddressUtil.parseAddress(target);
      logger.info("Sending players to {} instead of disconnecting them", target);
      // Unresolved, so the client gets the host exactly as configured.
      return InetSocketAddress.createUnresolved(
          URI.create("tcp://" + target).getHost(), parsed.getPort());
    } catch (IllegalArgumentException | IllegalStateException e) {
      logger.error("shutdown.transfer-to '{}' is not a valid host:port", target);
      return null;
    }
  }

  /**
   * Returns the Redis network sync.
   *
   * @return the sync
   */
  public NetworkSync getNetworkSync() {
    return networkSync;
  }

  /**
   * Returns the Discord webhook sender.
   *
   * @return the webhook
   */
  public DiscordWebhook getDiscordWebhook() {
    return discordWebhook;
  }

  /**
   * Returns the restart scheduler.
   *
   * @return the scheduler
   */
  public RestartScheduler getRestartScheduler() {
    return restartScheduler;
  }

  /**
   * Returns the bans and mutes.
   *
   * @return the punishments
   */
  public Punishments getPunishments() {
    return punishments;
  }

  /**
   * Returns the limbo for players whose server restarts.
   *
   * @return the limbo
   */
  public Limbo getLimbo() {
    return limbo;
  }

  /**
   * Returns the counters for the metrics endpoint.
   *
   * @return the counters
   */
  public ProxyStats getProxyStats() {
    return proxyStats;
  }

  /**
   * Returns the parties.
   *
   * @return the parties
   */
  public Parties getParties() {
    return parties;
  }

  /**
   * Returns the bot protection.
   *
   * @return the protection
   */
  public AntiBot getAntiBot() {
    return antiBot;
  }

  /**
   * Returns the server groups.
   *
   * @return the groups
   */
  public ServerGroups getServerGroups() {
    return serverGroups;
  }

  /**
   * Returns the queues for full or offline servers.
   *
   * @return the queue
   */
  public ServerQueue getServerQueue() {
    return serverQueue;
  }

  /**
   * Returns what PaperGuard reported about the backends.
   *
   * @return the reports
   */
  public BridgeReports getBridgeReports() {
    return bridgeReports;
  }

  /**
   * Returns the messages.yml store.
   *
   * @return the messages
   */
  public PaperProxyMessages getMessages() {
    return messages;
  }

  @Override
  public VelocityConfiguration getConfiguration() {
    return this.configuration;
  }

  @Override
  public ProxyVersion getVersion() {
    Package pkg = VelocityServer.class.getPackage();
    String implName;
    String implVersion;
    String implVendor;
    if (pkg != null) {
      implName = MoreObjects.firstNonNull(pkg.getImplementationTitle(), PaperProxyBranding.NAME);
      implVersion = MoreObjects.firstNonNull(pkg.getImplementationVersion(), "<unknown>");
      implVendor = MoreObjects.firstNonNull(pkg.getImplementationVendor(),
          "PaperProxy Contributors");
    } else {
      implName = PaperProxyBranding.NAME;
      implVersion = "<unknown>";
      implVendor = "PaperProxy Contributors";
    }

    return new ProxyVersion(implName, implVendor, implVersion);
  }

  private VelocityPluginContainer createVirtualPlugin() {
    ProxyVersion version = getVersion();
    PluginDescription description = new VelocityPluginDescription(
        "velocity", version.getName(), version.getVersion(), "The Velocity proxy",
            version.getName().equals("Velocity") ? VELOCITY_URL : null,
            ImmutableList.of(version.getVendor()), Collections.emptyList(),
            Collections.emptyList(), null);
    VelocityPluginContainer container = new VelocityPluginContainer(description);
    container.setInstance(VelocityVirtualPlugin.INSTANCE);
    return container;
  }

  @Override
  public VelocityCommandManager getCommandManager() {
    return commandManager;
  }

  void awaitProxyShutdown() {
    cm.getBossGroup().terminationFuture().syncUninterruptibly();
  }

  @EnsuresNonNull({"serverKeyPair", "servers", "pluginManager", "eventManager", "scheduler",
      "console", "cm", "configuration"})
  void start() {
    PaperProxyBranding.printBanner(logger, getVersion());
    PaperProxyProvider.set(paperProxyApi);
    logger.info("Booting up {} {}...", getVersion().getName(), getVersion().getVersion());
    console.setupStreams();
    pluginManager.registerPlugin(this.createVirtualPlugin());

    // Yes, you're reading that correctly. We're generating a 1024-bit RSA keypair. Sounds
    // dangerous, right? We're well within the realm of factoring such a key...
    //
    // You can blame Mojang. For the record, we also don't consider the Minecraft protocol
    // encryption scheme to be secure, and it has reached the point where any serious cryptographic
    // protocol needs a refresh. There are multiple obvious weaknesses, and this is far from the
    // most serious.
    //
    // If you are using Minecraft in a security-sensitive application, *I don't know what to say.*
    serverKeyPair = EncryptionUtils.createRsaKeyPair(1024);

    cm.logChannelInformation();

    // Initialize commands first
    final BrigadierCommand velocityParentCommand = VelocityCommand.create(this);
    commandManager.register(
        commandManager.metaBuilder(velocityParentCommand)
            .plugin(VelocityVirtualPlugin.INSTANCE)
            .build(),
        velocityParentCommand
    );
    final BrigadierCommand callbackCommand = CallbackCommand.create();
    commandManager.register(
        commandManager.metaBuilder(callbackCommand)
            .plugin(VelocityVirtualPlugin.INSTANCE)
            .build(),
        callbackCommand
    );
    final BrigadierCommand serverCommand = ServerCommand.create(this);
    commandManager.register(
        commandManager.metaBuilder(serverCommand)
            .plugin(VelocityVirtualPlugin.INSTANCE)
            .build(),
        serverCommand
    );
    final BrigadierCommand shutdownCommand = ShutdownCommand.command(this);
    commandManager.register(
        commandManager.metaBuilder(shutdownCommand)
            .plugin(VelocityVirtualPlugin.INSTANCE)
            .aliases("end", "stop")
            .build(),
        shutdownCommand
    );
    new GlistCommand(this).register();
    new SendCommand(this).register();
    final BrigadierCommand paperProxyCommand = PaperProxyCommand.create(this);
    commandManager.register(
        commandManager.metaBuilder(paperProxyCommand)
            .plugin(VelocityVirtualPlugin.INSTANCE)
            .aliases("pp")
            .build(),
        paperProxyCommand
    );

    this.doStartupConfigLoad();
    this.loadPaperProxyConfig();

    registerTranslations();

    channelRegistrar.register(BridgeReports.CHANNEL);
    eventManager.register(VelocityVirtualPlugin.INSTANCE, bridgeReports);
    eventManager.register(VelocityVirtualPlugin.INSTANCE, networkRules);
    eventManager.register(VelocityVirtualPlugin.INSTANCE, serverQueue);
    eventManager.register(VelocityVirtualPlugin.INSTANCE, antiBot);
    eventManager.register(VelocityVirtualPlugin.INSTANCE, networkSync);
    eventManager.register(VelocityVirtualPlugin.INSTANCE, new HiddenCommands(this));
    eventManager.register(VelocityVirtualPlugin.INSTANCE, new QueryPassthrough(this));
    antiBot.start();
    eventManager.register(VelocityVirtualPlugin.INSTANCE, punishments);
    eventManager.register(VelocityVirtualPlugin.INSTANCE, parties);
    eventManager.register(VelocityVirtualPlugin.INSTANCE, proxyStats);
    eventManager.register(VelocityVirtualPlugin.INSTANCE, limbo);
    limbo.start();
    punishments.start();
    restartScheduler.start();
    eventManager.register(VelocityVirtualPlugin.INSTANCE, motd);
    eventManager.register(VelocityVirtualPlugin.INSTANCE, updateChecker);
    updateChecker.start();
    NetworkCommands.register(this);
    if (paperProxyConfig.values().party().enabled()) {
      PartyCommand.register(this);
    }
    if (paperProxyConfig.values().punish()) {
      PunishCommands.register(this);
    }

    for (ServerInfo cliServer : options.getServers()) {
      servers.register(cliServer);
    }

    if (!options.isIgnoreConfigServers()) {
      for (Map.Entry<String, String> entry : configuration.getServers().entrySet()) {
        servers.register(new ServerInfo(entry.getKey(), AddressUtil.parseAddress(entry.getValue())));
      }
    }

    ipAttemptLimiter = Ratelimiters.createWithMilliseconds(configuration.getLoginRatelimit());
    commandRateLimiter = Ratelimiters.createWithMilliseconds(configuration.getCommandRatelimit());
    tabCompleteRateLimiter = Ratelimiters.createWithMilliseconds(configuration.getTabCompleteRatelimit());
    loadPlugins();

    // Go ahead and fire the proxy initialization event. We block since plugins should have a chance
    // to fully initialize before we accept any connections to the server.
    eventManager.fire(new ProxyInitializeEvent()).join();

    // init console permissions after plugins are loaded
    console.setupPermissions();

    final Integer port = this.options.getPort();
    if (port != null) {
      logger.debug("Overriding bind port to {} from command line option", port);
      this.cm.bind(new InetSocketAddress(configuration.getBind().getHostString(), port));
    } else {
      this.cm.bind(configuration.getBind());
    }
    listening = true;
    networkSync.apply();
    discordWebhook.send(DiscordWebhook.Kind.PROXY, "paperproxy.discord.proxy-started");

    final Boolean haproxy = this.options.isHaproxy();
    if (haproxy != null) {
      logger.debug("Overriding HAProxy protocol to {} from command line option", haproxy);
      configuration.setProxyProtocol(haproxy);
    }

    if (configuration.isQueryEnabled()) {
      this.cm.queryBind(configuration.getBind().getHostString(), configuration.getQueryPort());
    }

    final String defaultPackage = new String(
        new byte[] { 'o', 'r', 'g', '.', 'b', 's', 't', 'a', 't', 's' });
    if (!MetricsBase.class.getPackage().getName().startsWith(defaultPackage)) {
      Metrics.VelocityMetrics.startMetrics(this, configuration.getMetrics());
    } else {
      logger.warn("debug environment, metrics is disabled!");
    }
  }

  private void registerTranslations() {
    final MiniMessageTranslationStore translationRegistry =
            MiniMessageTranslationStore.create(Key.key("velocity", "translations"));
    translationRegistry.defaultLocale(Locale.US);
    try {
      ResourceUtils.visitResources(VelocityServer.class, path -> {
        logger.info("Loading localizations...");

        final Path langPath = Path.of("lang");

        try {
          if (!Files.exists(langPath)) {
            Files.createDirectory(langPath);
            try (final Stream<Path> files = Files.walk(path)) {
              files.filter(Files::isRegularFile).forEach(file -> {
                try {
                  final Path langFile = langPath.resolve(file.getFileName().toString());
                  if (!Files.exists(langFile)) {
                    try (final InputStream is = Files.newInputStream(file)) {
                      Files.copy(is, langFile);
                    }
                  }
                } catch (IOException e) {
                  logger.error("Encountered an I/O error whilst loading translations", e);
                }
              });
            }

          }

          try (final Stream<Path> files = Files.walk(langPath)) {
            files.filter(Files::isRegularFile).forEach(file -> {
              final String filename = com.google.common.io.Files
                      .getNameWithoutExtension(file.getFileName().toString());
              final String localeName = filename.replace("messages_", "")
                      .replace("messages", "")
                      .replace('_', '-');
              final Locale locale = localeName.isBlank()
                      ? Locale.US
                      : Locale.forLanguageTag(localeName);

              translationRegistry.registerAll(locale, file, false);
              ClosestLocaleMatcher.INSTANCE.registerKnown(locale);
            });
          }

        } catch (IOException e) {
          logger.error("Encountered an I/O error whilst loading translations", e);
        }
      }, "com", "velocitypowered", "proxy", "l10n");
    } catch (IOException e) {
      logger.error("Encountered an I/O error whilst loading translations", e);
    }
    // messages.yml wins over Velocity's own translations, see PaperProxyMessages#translator
    messages.load();
    GlobalTranslator.translator().addSource(messages.translator(translationRegistry));
  }

  @SuppressFBWarnings("DM_EXIT")
  private void doStartupConfigLoad() {
    try {
      Path configPath = Path.of("velocity.toml");
      configuration = VelocityConfiguration.read(configPath);

      if (!configuration.validate()) {
        logger.error("Your configuration is invalid. Velocity will not start up until the errors "
            + "are resolved.");
        LogManager.shutdown();
        System.exit(1);
      }

      commandManager.setAnnounceProxyCommands(configuration.isAnnounceProxyCommands());
    } catch (Exception e) {
      logger.error("Unable to read/load/save your velocity.toml. The server will shut down.", e);
      LogManager.shutdown();
      System.exit(1);
    }
  }

  private void loadPlugins() {
    logger.info("Loading plugins...");

    try {
      Path pluginPath = Path.of("plugins");

      if (!pluginPath.toFile().exists()) {
        Files.createDirectory(pluginPath);
      } else {
        if (!pluginPath.toFile().isDirectory()) {
          logger.warn("Plugin location {} is not a directory, continuing without loading plugins",
              pluginPath);
          return;
        }

        UpdateFolder.apply(pluginPath);
        pluginManager.loadPlugins(pluginPath);
      }
    } catch (Exception e) {
      logger.error("Couldn't load plugins", e);
    }

    // Register the plugin main classes so that we can fire the proxy initialize event
    for (PluginContainer plugin : pluginManager.getPlugins()) {
      Optional<?> instance = plugin.getInstance();
      if (instance.isPresent()) {
        try {
          eventManager.registerInternally(plugin, instance.get());
        } catch (Exception e) {
          logger.error("Unable to register plugin listener for {}",
              plugin.getDescription().getName().orElse(plugin.getDescription().getId()), e);
        }
      }
    }

    logger.info("Loaded {} plugins", pluginManager.getPlugins().size());

    // BungeeCord plugins load after Velocity plugins, so Velocity plugin IDs win on conflicts.
    BungeeLayerBootstrap.start(this, Path.of("plugins")).ifPresent(layer -> {
      this.bungeeLayer = layer;
      try {
        layer.enable();
      } catch (Exception e) {
        logger.error("Couldn't enable the BungeeCord plugins", e);
      }
    });
  }

  public Bootstrap createBootstrap(@Nullable EventLoopGroup group) {
    return this.cm.createWorker(group);
  }

  public ChannelInitializer<Channel> getBackendChannelInitializer() {
    return this.cm.backendChannelInitializer.get();
  }

  public ServerListPingHandler getServerListPingHandler() {
    return serverListPingHandler;
  }

  public boolean isShutdown() {
    return shutdown;
  }

  /**
   * Reloads the proxy's configuration.
   *
   * @return {@code true} if successful, {@code false} if we can't read the configuration
   * @throws IOException if we can't read {@code velocity.toml}
   */
  public boolean reloadConfiguration() throws IOException {
    Path configPath = Path.of("velocity.toml");
    VelocityConfiguration newConfiguration = VelocityConfiguration.read(configPath);

    if (!newConfiguration.validate()) {
      return false;
    }
    messages.load();
    loadPaperProxyConfig();

    // Re-register servers. If a server is being replaced or removed, make sure to note what
    // players need to move back to a fallback server.
    Collection<ConnectedPlayer> evacuate = new ArrayList<>();
    for (Map.Entry<String, String> entry : configuration.getServers().entrySet()) {
      if (newConfiguration.getServers().keySet().stream()
          .anyMatch(name -> name.equalsIgnoreCase(entry.getKey()))) {
        continue;
      }

      RegisteredServer rs = servers.getServer(entry.getKey()).orElse(null);
      if (rs == null) {
        continue;
      }

      for (Player player : rs.getPlayersConnected()) {
        if (!(player instanceof ConnectedPlayer)) {
          throw new IllegalStateException("Expected ConnectedPlayer for player " + player
              + " on server " + rs.getServerInfo().getName() + ", got "
              + player.getClass().getName());
        }
        evacuate.add((ConnectedPlayer) player);
      }
      servers.unregister(rs.getServerInfo());
    }

    for (Map.Entry<String, String> entry : newConfiguration.getServers().entrySet()) {
      ServerInfo newInfo = new ServerInfo(entry.getKey(), AddressUtil.parseAddress(entry.getValue()));
      RegisteredServer rs = servers.getServer(entry.getKey()).orElse(null);
      if (rs == null) {
        servers.register(newInfo);
      } else if (!rs.getServerInfo().equals(newInfo)) {
        for (Player player : rs.getPlayersConnected()) {
          if (!(player instanceof ConnectedPlayer)) {
            throw new IllegalStateException("Expected ConnectedPlayer for player " + player
                + " on server " + rs.getServerInfo().getName() + ", got "
                + player.getClass().getName());
          }
          evacuate.add((ConnectedPlayer) player);
        }
        servers.unregister(rs.getServerInfo());
        servers.register(newInfo);
      }
    }

    // Apply the new configuration before evacuation so fallback lookups use the updated try list.
    VelocityConfiguration previousConfiguration = this.configuration;
    this.configuration = newConfiguration;

    // If we had any players to evacuate, let's move them now. Wait until they are all moved off.
    if (!evacuate.isEmpty()) {
      CountDownLatch latch = new CountDownLatch(evacuate.size());
      for (ConnectedPlayer player : evacuate) {
        player.resetServersToTry();
        Optional<RegisteredServer> next = player.getNextServerToTry();
        if (next.isPresent()) {
          player.createConnectionRequest(next.get()).connectWithIndication()
              .whenComplete((success, ex) -> {
                if (ex != null || success == null || !success) {
                  player.disconnect(Component.text("Your server has been changed, but we could "
                      + "not move you to any fallback servers."));
                }
                latch.countDown();
              });
        } else {
          latch.countDown();
          player.disconnect(Component.text("Your server has been changed, but we could "
              + "not move you to any fallback servers."));
        }
      }
      try {
        latch.await();
      } catch (InterruptedException e) {
        logger.error("Interrupted whilst moving players", e);
        Thread.currentThread().interrupt();
      }
    }

    // If we have a new bind address, bind to it
    if (!previousConfiguration.getBind().equals(newConfiguration.getBind())) {
      this.cm.bind(newConfiguration.getBind());
      this.cm.close(previousConfiguration.getBind());
    }

    boolean queryPortChanged = newConfiguration.getQueryPort() != previousConfiguration.getQueryPort();
    boolean queryAlreadyEnabled = previousConfiguration.isQueryEnabled();
    boolean queryEnabled = newConfiguration.isQueryEnabled();
    if (queryAlreadyEnabled && (!queryEnabled || queryPortChanged)) {
      this.cm.close(new InetSocketAddress(
          previousConfiguration.getBind().getHostString(), previousConfiguration.getQueryPort()));
    }
    if (queryEnabled && (!queryAlreadyEnabled || queryPortChanged)) {
      this.cm.queryBind(newConfiguration.getBind().getHostString(),
          newConfiguration.getQueryPort());
    }

    commandManager.setAnnounceProxyCommands(newConfiguration.isAnnounceProxyCommands());
    ipAttemptLimiter = Ratelimiters.createWithMilliseconds(newConfiguration.getLoginRatelimit());
    eventManager.fireAndForget(new ProxyReloadEvent());
    return true;
  }

  /**
   * Shuts down the proxy, kicking players with the specified reason.
   *
   * @param explicitExit whether the user explicitly shut down the proxy
   * @param reason       message to kick online players with
   */
  public void shutdown(boolean explicitExit, Component reason) {
    if (eventManager == null || pluginManager == null || cm == null || scheduler == null) {
      throw new AssertionError();
    }

    if (!shutdownInProgress.compareAndSet(false, true)) {
      return;
    }

    Runnable shutdownProcess = () -> {
      logger.info("Shutting down the proxy...");
      discordWebhook.send(DiscordWebhook.Kind.PROXY, "paperproxy.discord.proxy-stopping");

      // Stop accepting new connections, but keep DNS available to pre-shutdown handlers.
      cm.closeEndpoints(true);

      try {
        eventManager.fire(new ProxyPreShutdownEvent())
                .toCompletableFuture()
                .get(PRE_SHUTDOWN_TIMEOUT, TimeUnit.SECONDS);
      } catch (TimeoutException ignored) {
        logger.warn("Your plugins took over {} seconds during pre shutdown.",
                PRE_SHUTDOWN_TIMEOUT);
      } catch (ExecutionException ee) {
        logger.error("Exception in ProxyPreShutdownEvent handler; continuing shutdown.", ee);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
        logger.warn("Interrupted while waiting for ProxyPreShutdownEvent; continuing shutdown.");
      }

      cm.shutdown();

      ImmutableList<ConnectedPlayer> players = ImmutableList.copyOf(connectionsByUuid.values());
      final InetSocketAddress transferTo = transferTarget();
      for (ConnectedPlayer player : players) {
        if (transferTo != null
            && player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_20_5)) {
          // PaperProxy: hand the player to another proxy instead of kicking them.
          player.transferToHost(transferTo);
        } else {
          player.disconnect(reason);
        }
      }

      try {
        boolean timedOut = false;

        try {
          // Wait for the connections finish tearing down, this
          // makes sure that all the disconnect events are being fired

          CompletableFuture<Void> playersTeardownFuture = CompletableFuture.allOf(players.stream()
                  .map(ConnectedPlayer::getTeardownFuture)
                  .toArray((IntFunction<CompletableFuture<Void>[]>) CompletableFuture[]::new));

          playersTeardownFuture.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
          timedOut = true;
        } catch (ExecutionException e) {
          timedOut = true;
          logger.error("Exception while tearing down player connections", e);
        }

        final BungeeLayerHandle layer = this.bungeeLayer;
        if (layer != null) {
          try {
            layer.shutdown();
          } catch (Exception e) {
            logger.error("Exception while disabling BungeeCord plugins", e);
          }
        }

        eventManager.fire(new ProxyShutdownEvent()).join();
        // Plugins may start async saves in their shutdown handlers: let them finish.
        paperProxyApi.shutdown(10);
        antiBot.save();
        metricsEndpoint.stop();
        networkSync.stop();

        timedOut = !scheduler.shutdown() || timedOut;

        if (timedOut) {
          logger.error("Your plugins took over 10 seconds to shut down.");
        }
      } catch (InterruptedException e) {
        // Not much we can do about this...
        Thread.currentThread().interrupt();
      }

      updateChecker.applyOnShutdown();

      // Since we manually removed the shutdown hook, we need to handle the shutdown ourselves.
      LogManager.shutdown();

      shutdown = true;

      if (explicitExit) {
        System.exit(0);
      }
    };

    if (explicitExit) {
      Thread thread = new Thread(shutdownProcess);
      thread.start();
    } else {
      shutdownProcess.run();
    }
  }

  /**
   * Calls {@link #shutdown(boolean, Component)} with the default reason "Proxy shutting down".
   *
   * @param explicitExit whether the user explicitly shut down the proxy
   */
  public void shutdown(boolean explicitExit) {
    shutdown(explicitExit, Component.translatable("velocity.kick.shutdown"));
  }

  @Override
  public void shutdown(Component reason) {
    shutdown(true, reason);
  }

  @Override
  public void shutdown() {
    shutdown(true);
  }

  @Override
  public void closeListeners() {
    this.cm.closeEndpoints(false);
  }

  public HttpClient createHttpClient() {
    return cm.createHttpClient();
  }

  public @MonotonicNonNull Ratelimiter<InetAddress> getIpAttemptLimiter() {
    return ipAttemptLimiter;
  }

  public @MonotonicNonNull Ratelimiter<UUID> getCommandRateLimiter() {
    return commandRateLimiter;
  }

  public @MonotonicNonNull Ratelimiter<UUID> getTabCompleteRateLimiter() {
    return tabCompleteRateLimiter;
  }

  /**
   * Checks if the {@code connection} can be registered with the proxy.
   *
   * @param connection the connection to check
   * @return {@code true} if we can register the connection, {@code false} if not
   */
  public boolean canRegisterConnection(ConnectedPlayer connection) {
    if (configuration.isOnlineMode() && configuration.isOnlineModeKickExistingPlayers()) {
      return true;
    }
    String lowerName = connection.getUsername().toLowerCase(Locale.US);
    return !(connectionsByName.containsKey(lowerName)
        || connectionsByUuid.containsKey(connection.getUniqueId()));
  }

  /**
   * Attempts to register the {@code connection} with the proxy.
   *
   * @param connection the connection to register
   * @return {@code true} if we registered the connection, {@code false} if not
   */
  public boolean registerConnection(ConnectedPlayer connection) {
    String lowerName = connection.getUsername().toLowerCase(Locale.US);

    if (!this.configuration.isOnlineModeKickExistingPlayers()) {
      if (connectionsByName.putIfAbsent(lowerName, connection) != null) {
        return false;
      }
      if (connectionsByUuid.putIfAbsent(connection.getUniqueId(), connection) != null) {
        connectionsByName.remove(lowerName, connection);
        return false;
      }
    } else {
      ConnectedPlayer existing = connectionsByUuid.get(connection.getUniqueId());
      if (existing != null) {
        existing.disconnect(Component.translatable("multiplayer.disconnect.duplicate_login"));
      }

      // We can now replace the entries as needed.
      connectionsByName.put(lowerName, connection);
      connectionsByUuid.put(connection.getUniqueId(), connection);
    }
    return true;
  }

  /**
   * Unregisters the given player from the proxy.
   *
   * @param connection the connection to unregister
   */
  public void unregisterConnection(ConnectedPlayer connection) {
    connectionsByName.remove(connection.getUsername().toLowerCase(Locale.US), connection);
    connectionsByUuid.remove(connection.getUniqueId(), connection);
    connection.disconnected();

    if (this.sessionId != null && connectionsByUuid.isEmpty()) {
      synchronized (this.sessionIdLock) {
        if (connectionsByUuid.isEmpty()) {
          this.sessionId = null;
        }
      }
    }
  }

  /**
   * Returns the metrics session ID for this proxy, generating one if none is currently active. The
   * ID is shared by every player connected during a populated period and is regenerated once the
   * proxy empties.
   *
   * @return the current session ID
   */
  public UUID getSessionId() {
    UUID uuid = this.sessionId;
    if (uuid != null) {
      return uuid;
    }
    synchronized (this.sessionIdLock) {
      uuid = this.sessionId;
      if (uuid == null) {
        uuid = UUID.randomUUID();
        this.sessionId = uuid;
      }
      return uuid;
    }
  }

  @Override
  public Optional<Player> getPlayer(String username) {
    Preconditions.checkNotNull(username, "username");
    return Optional.ofNullable(connectionsByName.get(username.toLowerCase(Locale.US)));
  }

  @Override
  public Optional<Player> getPlayer(UUID uuid) {
    Preconditions.checkNotNull(uuid, "uuid");
    return Optional.ofNullable(connectionsByUuid.get(uuid));
  }

  @Override
  public Collection<Player> matchPlayer(String partialName) {
    Objects.requireNonNull(partialName);

    return getAllPlayers().stream().filter(p -> p.getUsername()
            .regionMatches(true, 0, partialName, 0, partialName.length()))
        .collect(Collectors.toList());
  }

  @Override
  public Collection<RegisteredServer> matchServer(String partialName) {
    Objects.requireNonNull(partialName);

    return getAllServers().stream().filter(s -> s.getServerInfo().getName()
            .regionMatches(true, 0, partialName, 0, partialName.length()))
        .collect(Collectors.toList());
  }

  @Override
  public Collection<Player> getAllPlayers() {
    return ImmutableList.copyOf(connectionsByUuid.values());
  }

  @Override
  public int getPlayerCount() {
    return connectionsByUuid.size();
  }

  @Override
  public Optional<RegisteredServer> getServer(String name) {
    return servers.getServer(name);
  }

  @Override
  public Collection<RegisteredServer> getAllServers() {
    return servers.getAllServers();
  }

  @Override
  public RegisteredServer createRawRegisteredServer(ServerInfo server) {
    return servers.createRawRegisteredServer(server);
  }

  @Override
  public RegisteredServer registerServer(ServerInfo server) {
    return servers.register(server);
  }

  @Override
  public void unregisterServer(ServerInfo server) {
    servers.unregister(server);
  }

  @Override
  public VelocityConsole getConsoleCommandSource() {
    return console;
  }

  @Override
  public PluginManager getPluginManager() {
    return pluginManager;
  }

  @Override
  public VelocityEventManager getEventManager() {
    return eventManager;
  }

  @Override
  public VelocityScheduler getScheduler() {
    return scheduler;
  }

  @Override
  public VelocityChannelRegistrar getChannelRegistrar() {
    return channelRegistrar;
  }

  @Override
  public boolean isShuttingDown() {
    return shutdownInProgress.get();
  }

  @Override
  public InetSocketAddress getBoundAddress() {
    if (configuration == null) {
      throw new IllegalStateException(
          "No configuration"); // even though you'll never get the chance... heh, heh
    }
    return configuration.getBind();
  }

  @Override
  public @NonNull Iterable<? extends Audience> audiences() {
    Collection<Audience> audiences = new ArrayList<>(this.getPlayerCount() + 1);
    audiences.add(this.console);
    audiences.addAll(this.getAllPlayers());
    return audiences;
  }

  /**
   * Returns a Gson instance for use in serializing server ping instances.
   *
   * @param version the protocol version in use
   * @return the Gson instance
   */
  public static Gson getPingGsonInstance(ProtocolVersion version) {
    if (version == ProtocolVersion.UNKNOWN
        || version.noLessThan(ProtocolVersion.MINECRAFT_1_20_3)) {
      return MODERN_PING_SERIALIZER;
    }
    if (version.noLessThan(ProtocolVersion.MINECRAFT_1_16)) {
      return PRE_1_20_3_PING_SERIALIZER;
    }
    return PRE_1_16_PING_SERIALIZER;
  }

  @Override
  public ResourcePackInfo.Builder createResourcePackBuilder(String url) {
    return new VelocityResourcePackInfo.BuilderImpl(url);
  }
}
