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

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.ConnectionHandshakeEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.player.CookieReceiveEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.PlayerSettingsChangedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.permission.PermissionFunction;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PermissionCheckEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PlayerHandshakeEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.ServerDisconnectEvent;
import net.md_5.bungee.api.event.ServerKickEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.event.SettingsChangedEvent;
import net.md_5.bungee.protocol.packet.Handshake;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Listens to Velocity's events, fires the matching BungeeCord events and writes what Bungee
 * plugins changed (cancel, new target, new message, ...) back into the Velocity event.
 *
 * <p>Bungee's async events (login, ping) wait for registered intents without blocking any
 * thread: they run as Velocity {@link EventTask}s that resume once Bungee's callback fires.
 */
public final class EventBridge {

  private static final Logger logger = LogManager.getLogger(EventBridge.class);
  /** A Bungee plugin that never completes its intent must not hang a login forever. */
  private static final long ASYNC_TIMEOUT_SECONDS = 30;

  private final BungeeLayer layer;
  private final Map<UUID, ServerInfo> initialTargets = new ConcurrentHashMap<>();

  EventBridge(final BungeeLayer layer) {
    this.layer = layer;
  }

  /** Set while a BungeeCord permission check asks Velocity, so the two never loop. */
  static final ThreadLocal<Boolean> ASKING_VELOCITY = ThreadLocal.withInitial(() -> false);

  // ------------------------------------------------------------ permissions

  /**
   * Lets BungeeCord permission plugins (LuckPerms-Bungee and others) answer Velocity's
   * permission checks too, so proxy commands like /send or /pp servers follow them.
   *
   * @param event the event
   */
  @Subscribe(order = PostOrder.LATE)
  public void onPermissionsSetup(final PermissionsSetupEvent event) {
    if (!(event.getSubject() instanceof Player player)) {
      return;
    }
    final PermissionFunction velocity = event.createFunction(player);
    event.setProvider(subject -> permission -> {
      final Tristate original = velocity.getPermissionValue(permission);
      if (ASKING_VELOCITY.get()) {
        return original;
      }
      final BungeePlayer sender = layer.player(player);
      final boolean base = original == Tristate.TRUE;
      final boolean result = layer.pluginManager().callEvent(
          new PermissionCheckEvent(sender, permission, base)).hasPermission();
      return result == base ? original : Tristate.fromBoolean(result);
    });
  }

  // ------------------------------------------------------------------ login

  /**
   * Handshake.
   *
   * @param event the event
   */
  @Subscribe
  public void onHandshake(final ConnectionHandshakeEvent event) {
    final java.net.InetSocketAddress host = event.getConnection().getVirtualHost().orElse(null);
    final Handshake handshake = new Handshake(event.getConnection().getProtocolVersion()
        .getProtocol(), host == null ? "" : host.getHostString(), host == null ? 0 : host.getPort(),
        event.getIntent().id());
    call(new PlayerHandshakeEvent(new BungeePendingConnection(layer, event.getConnection(), null,
        null), handshake));
  }

  /**
   * Pre login.
   *
   * @param event the event
   * @return the task waiting for Bungee's intents
   */
  @Subscribe
  public EventTask onPreLogin(final PreLoginEvent event) {
    if (!event.getResult().isAllowed()) {
      return null;
    }
    final BungeePendingConnection pending = new BungeePendingConnection(layer,
        event.getConnection(), event.getUsername(), event.getUniqueId());
    return async(done -> new net.md_5.bungee.api.event.PreLoginEvent(pending, (result, error) -> {
      if (result.isCancelled()) {
        event.setResult(PreLoginEvent.PreLoginComponentResult.denied(reason(result.getReason())));
      } else if (Boolean.TRUE.equals(pending.forcedOnlineMode())) {
        event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
      } else if (Boolean.FALSE.equals(pending.forcedOnlineMode())) {
        event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
      }
      done.run();
    }));
  }

  /**
   * Login.
   *
   * @param event the event
   * @return the task waiting for Bungee's intents
   */
  @Subscribe
  public EventTask onLogin(final LoginEvent event) {
    if (!event.getResult().isAllowed()) {
      return null;
    }
    final BungeePendingConnection pending = new BungeePendingConnection(layer, event.getPlayer(),
        event.getPlayer().getUsername(), event.getPlayer().getUniqueId());
    return async(done -> new net.md_5.bungee.api.event.LoginEvent(pending, (result, error) -> {
      if (result.isCancelled()) {
        event.setResult(ResultedEvent.ComponentResult.denied(reason(result.getReason())));
      }
      done.run();
    }));
  }

  /**
   * Post login.
   *
   * @param event the event
   * @return the task waiting for Bungee's intents
   */
  @Subscribe
  public EventTask onPostLogin(final PostLoginEvent event) {
    final BungeePlayer player = layer.player(event.getPlayer());
    return async(done -> new net.md_5.bungee.api.event.PostLoginEvent(player, null,
        (result, error) -> {
          if (result.getTarget() != null) {
            initialTargets.put(player.getUniqueId(), result.getTarget());
          }
          done.run();
        }));
  }

  /**
   * Applies the target a Bungee plugin chose in PostLoginEvent.
   *
   * @param event the event
   */
  @Subscribe
  public void onChooseInitialServer(final PlayerChooseInitialServerEvent event) {
    final ServerInfo target = initialTargets.remove(event.getPlayer().getUniqueId());
    if (target != null) {
      event.setInitialServer(layer.registered(target));
    }
  }

  /**
   * Server list ping.
   *
   * @param event the event
   * @return the task waiting for Bungee's intents
   */
  @Subscribe
  public EventTask onPing(final ProxyPingEvent event) {
    final BungeePendingConnection pending = new BungeePendingConnection(layer,
        event.getConnection(), null, null);
    final com.velocitypowered.api.proxy.server.ServerPing original = event.getPing();
    return async(done -> new net.md_5.bungee.api.event.ProxyPingEvent(pending,
        Pings.toBungee(original), (result, error) -> {
          if (result.getResponse() != null) {
            event.setPing(Pings.toVelocity(result.getResponse(), original));
          }
          done.run();
        }));
  }

  // ------------------------------------------------------------------ servers

  /**
   * Server connect.
   *
   * @param event the event
   */
  @Subscribe
  public void onServerPreConnect(final ServerPreConnectEvent event) {
    final RegisteredServer target = event.getResult().getServer().orElse(null);
    if (target == null) {
      return;
    }
    final BungeePlayer player = layer.player(event.getPlayer());
    final ServerConnectEvent.Reason reason = event.getPreviousServer() == null
        && event.getPlayer().getCurrentServer().isEmpty()
        ? ServerConnectEvent.Reason.JOIN_PROXY : player.takeConnectReason();
    final ServerInfo original = layer.serverInfo(target.getServerInfo().getName());
    final ServerConnectEvent bungee = call(new ServerConnectEvent(player, original, reason, null));
    if (bungee.isCancelled() || bungee.getTarget() == null) {
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
    } else if (!bungee.getTarget().getName().equals(original.getName())) {
      event.setResult(ServerPreConnectEvent.ServerResult.allowed(
          layer.registered(bungee.getTarget())));
    }
  }

  /**
   * Server switch finished.
   *
   * @param event the event
   */
  @Subscribe
  public void onServerPostConnect(final ServerPostConnectEvent event) {
    final BungeePlayer player = layer.player(event.getPlayer());
    final ServerConnection connection = event.getPlayer().getCurrentServer().orElse(null);
    final RegisteredServer previous = event.getPreviousServer();
    if (previous != null) {
      call(new ServerDisconnectEvent(player, layer.serverInfo(previous.getServerInfo().getName())));
    }
    if (connection != null) {
      call(new ServerConnectedEvent(player, new BungeeServer(layer, connection)));
    }
    call(new ServerSwitchEvent(player, previous == null ? null
        : layer.serverInfo(previous.getServerInfo().getName())));
  }

  /**
   * Kicked from a backend server.
   *
   * @param event the event
   */
  @Subscribe
  public void onKick(final KickedFromServerEvent event) {
    final BungeePlayer player = layer.player(event.getPlayer());
    final KickedFromServerEvent.ServerKickResult original = event.getResult();
    final ServerInfo redirect =
        original instanceof KickedFromServerEvent.RedirectPlayer redirectPlayer
            ? layer.serverInfo(redirectPlayer.getServer().getServerInfo().getName()) : null;
    final BaseComponent reason = Chat.toBungee(event.getServerKickReason().orElse(Component.empty()));

    final ServerKickEvent bungee = new ServerKickEvent(player,
        layer.serverInfo(event.getServer().getServerInfo().getName()), reason, redirect,
        event.kickedDuringServerConnect() ? ServerKickEvent.State.CONNECTING
            : ServerKickEvent.State.CONNECTED);
    bungee.setCancelled(redirect != null);
    call(bungee);

    final boolean unchanged = bungee.isCancelled() == (redirect != null)
        && Objects.equals(bungee.getCancelServer(), redirect)
        && bungee.getReason() == reason;
    if (unchanged) {
      return;
    }
    if (bungee.isCancelled() && bungee.getCancelServer() != null) {
      event.setResult(KickedFromServerEvent.RedirectPlayer.create(
          layer.registered(bungee.getCancelServer()), Chat.toAdventure(bungee.getReason())));
    } else if (bungee.isCancelled()) {
      event.setResult(KickedFromServerEvent.Notify.create(Chat.toAdventure(bungee.getReason())));
    } else {
      event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
          Chat.toAdventure(bungee.getReason())));
    }
  }

  /**
   * Disconnect.
   *
   * @param event the event
   */
  @Subscribe
  public void onDisconnect(final DisconnectEvent event) {
    initialTargets.remove(event.getPlayer().getUniqueId());
    if (event.getLoginStatus() != DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN
        && event.getLoginStatus() != DisconnectEvent.LoginStatus.PRE_SERVER_JOIN) {
      layer.forgetPlayer(event.getPlayer());
      return;
    }
    final BungeePlayer player = layer.player(event.getPlayer());
    layer.leaving().add(event.getPlayer());
    try {
      event.getPlayer().getCurrentServer().ifPresent(server -> call(new ServerDisconnectEvent(
          player, layer.serverInfo(server.getServerInfo().getName()))));
      call(new PlayerDisconnectEvent(player));
    } finally {
      layer.leaving().remove(event.getPlayer());
      layer.forgetPlayer(event.getPlayer());
    }
  }

  // ------------------------------------------------------------------ chat and commands

  /**
   * Chat.
   *
   * @param event the event
   */
  @Subscribe
  public void onChat(final PlayerChatEvent event) {
    if (!event.getResult().isAllowed()) {
      return;
    }
    final BungeePlayer player = layer.player(event.getPlayer());
    final String message = event.getResult().getMessage().orElse(event.getMessage());
    final ChatEvent bungee = call(new ChatEvent(player, server(event.getPlayer()), message));
    if (bungee.isCancelled()) {
      event.setResult(PlayerChatEvent.ChatResult.denied());
    } else if (!bungee.getMessage().equals(message)) {
      event.setResult(PlayerChatEvent.ChatResult.message(bungee.getMessage()));
    }
  }

  /**
   * Commands typed by players. BungeeCord reports them as chat messages starting with a slash.
   *
   * @param event the event
   */
  @Subscribe
  public void onCommand(final CommandExecuteEvent event) {
    if (!(event.getCommandSource() instanceof Player velocityPlayer)
        || !event.getResult().isAllowed()) {
      return;
    }
    final String command = event.getResult().getCommand().orElse(event.getCommand());
    final ChatEvent bungee = call(new ChatEvent(layer.player(velocityPlayer),
        server(velocityPlayer), "/" + command));
    if (bungee.isCancelled()) {
      event.setResult(CommandExecuteEvent.CommandResult.denied());
    } else if (!bungee.getMessage().equals("/" + command)) {
      final String changed = bungee.getMessage();
      if (changed.startsWith("/")) {
        event.setResult(CommandExecuteEvent.CommandResult.command(changed.substring(1)));
      } else {
        // A plugin turned the command into a chat message.
        event.setResult(CommandExecuteEvent.CommandResult.denied());
        velocityPlayer.spoofChatInput(changed);
      }
    }
  }

  /**
   * Plugin messages in both directions.
   *
   * @param event the event
   */
  @Subscribe
  public void onPluginMessage(final com.velocitypowered.api.event.connection.PluginMessageEvent
                                  event) {
    if (!event.getResult().isAllowed()) {
      return;
    }
    final Connection sender = connection(event.getSource());
    final Connection receiver = connection(event.getTarget());
    if (sender == null || receiver == null) {
      return;
    }
    final net.md_5.bungee.api.event.PluginMessageEvent bungee = call(
        new net.md_5.bungee.api.event.PluginMessageEvent(sender, receiver,
            event.getIdentifier().getId(), event.getData()));
    if (bungee.isCancelled()) {
      event.setResult(com.velocitypowered.api.event.connection.PluginMessageEvent.ForwardResult.handled());
    }
  }

  /**
   * Client settings.
   *
   * @param event the event
   */
  @Subscribe
  public void onSettings(final PlayerSettingsChangedEvent event) {
    call(new SettingsChangedEvent(layer.player(event.getPlayer())));
  }

  /**
   * Cookies requested through the Bungee API.
   *
   * @param event the event
   */
  @Subscribe
  public void onCookie(final CookieReceiveEvent event) {
    if (layer.cookies().complete(event.getPlayer(), event.getOriginalKey(),
        event.getOriginalData())) {
      event.setResult(CookieReceiveEvent.ForwardResult.handled());
    }
  }

  // ------------------------------------------------------------------ helpers

  private <E extends net.md_5.bungee.api.plugin.Event> E call(final E event) {
    try {
      return layer.pluginManager().callEvent(event);
    } catch (final Throwable t) {
      logger.error("A BungeeCord plugin failed while handling {}", event.getClass().getSimpleName(),
          t);
      return event;
    }
  }

  /**
   * Runs a Bungee async event as a Velocity continuation.
   *
   * @param factory creates the Bungee event; its callback must run the given Runnable
   * @return the task
   */
  private EventTask async(
      final java.util.function.Function<Runnable, ? extends net.md_5.bungee.api.plugin.Event>
          factory) {
    return EventTask.withContinuation(continuation -> {
      final AtomicBoolean resumed = new AtomicBoolean();
      final Runnable done = () -> {
        if (resumed.compareAndSet(false, true)) {
          continuation.resume();
        }
      };
      final net.md_5.bungee.api.plugin.Event event;
      try {
        event = factory.apply(done);
      } catch (final Throwable t) {
        resumeWith(continuation, resumed, t);
        return;
      }
      layer.velocity().getScheduler().buildTask(layer, () -> {
        if (resumed.compareAndSet(false, true)) {
          logger.warn("A BungeeCord plugin did not complete its intent for {} within {} seconds;"
              + " continuing without it", event.getClass().getSimpleName(), ASYNC_TIMEOUT_SECONDS);
          continuation.resume();
        }
      }).delay(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS).schedule();
      try {
        layer.pluginManager().callEvent(event);
      } catch (final Throwable t) {
        logger.error("A BungeeCord plugin failed while handling {}",
            event.getClass().getSimpleName(), t);
        done.run();
      }
    });
  }

  private static void resumeWith(final Continuation continuation, final AtomicBoolean resumed,
                                 final Throwable error) {
    if (resumed.compareAndSet(false, true)) {
      continuation.resumeWithException(error);
    }
  }

  private Connection server(final Player player) {
    return player.getCurrentServer().map(server -> (Connection) new BungeeServer(layer, server))
        .orElse(null);
  }

  private Connection connection(final Object endpoint) {
    if (endpoint instanceof Player player) {
      return layer.player(player);
    }
    if (endpoint instanceof ServerConnection server) {
      return new BungeeServer(layer, server);
    }
    return null;
  }

  private static Component reason(final BaseComponent reason) {
    return reason == null ? Component.text("Kicked") : Chat.toAdventure(reason);
  }
}
