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

package net.paperstream.paperproxy.plugin;

import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.plugin.VelocityPluginManager;
import com.velocitypowered.proxy.plugin.loader.VelocityPluginContainer;
import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.paperstream.paperproxy.api.event.PluginRuntimeChangeEvent;
import net.paperstream.paperproxy.bungee.BungeeLayerBootstrap;
import net.paperstream.paperproxy.bungee.BungeeLayerHandle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Loads, unloads and reloads single Velocity or BungeeCord plugins while the proxy runs.
 *
 * <p>Experimental by nature: a plugin that keeps threads or static state alive is detected and
 * reported, but cannot always be cleaned up. Every operation runs on one background thread, so
 * reloads never overlap and never block a network thread.
 */
public final class PluginReloader {

  /** What happened, for the command's feedback. */
  public record Outcome(boolean success, String key, String plugin, String detail) {
  }

  private static final Logger logger = LogManager.getLogger(PluginReloader.class);
  private static final long LEAK_CHECK_SECONDS = 30;

  private final VelocityServer server;
  private final ExecutorService queue = Executors.newSingleThreadExecutor(Thread.ofPlatform()
      .name("PaperProxy Plugin Reloader").daemon(true).factory());
  private final AtomicInteger changes = new AtomicInteger();

  /**
   * Creates the reloader.
   *
   * @param server the proxy
   */
  public PluginReloader(final VelocityServer server) {
    this.server = server;
  }

  /**
   * Returns how often plugins were changed at runtime since start.
   *
   * @return the count
   */
  public int changes() {
    return changes.get();
  }

  /**
   * Tells whether a plugin may never be reloaded.
   *
   * @param name the plugin id or name
   * @return true if blocked
   */
  public boolean isBlocked(final String name) {
    final String lower = name.toLowerCase(Locale.ROOT);
    return lower.equals("velocity") || lower.equals("paperproxy-bungee")
        || server.getPaperProxyConfig().values().reloadBlocked().contains(lower);
  }

  /**
   * Reloads a plugin from its jar (which may have been replaced).
   *
   * @param name the plugin id or name
   * @return the outcome
   */
  public CompletableFuture<Outcome> reload(final String name) {
    return CompletableFuture.supplyAsync(() -> {
      final Outcome unloaded = unload0(name);
      if (!unloaded.success()) {
        return unloaded;
      }
      return load0(Path.of(unloaded.detail()), unloaded.plugin());
    }, queue);
  }

  /**
   * Unloads a plugin.
   *
   * @param name the plugin id or name
   * @return the outcome
   */
  public CompletableFuture<Outcome> unload(final String name) {
    return CompletableFuture.supplyAsync(() -> unload0(name), queue);
  }

  /**
   * Loads a plugin jar from the plugins folder.
   *
   * @param fileName the jar file name
   * @return the outcome
   */
  public CompletableFuture<Outcome> load(final String fileName) {
    return CompletableFuture.supplyAsync(() -> {
      final Path plugins = Path.of("plugins").toAbsolutePath().normalize();
      final Path jar = plugins.resolve(fileName).normalize();
      if (!jar.startsWith(plugins) || !Files.isRegularFile(jar)) {
        return new Outcome(false, "paperproxy.plugin.file-not-found", fileName, "");
      }
      return load0(jar, fileName);
    }, queue);
  }

  // ------------------------------------------------------------------ implementation

  private Outcome unload0(final String name) {
    if (isBlocked(name)) {
      return new Outcome(false, "paperproxy.plugin.blocked", name, "");
    }
    final BungeeLayerHandle bungee = server.getBungeeLayer();
    if (bungee != null) {
      final Optional<String> bungeeName = bungee.findPlugin(name);
      // Gleichnamiges Velocity-Plugin geht vor, ausser der Bungee-Name passt exakt
      final VelocityPluginManager manager = (VelocityPluginManager) server.getPluginManager();
      final boolean velocityFirst = bungeeName.isPresent() && !bungeeName.get().equals(name)
          && manager.getPlugin(name.toLowerCase(Locale.ROOT))
              .filter(c -> !manager.isFacade(c)).isPresent();
      if (bungeeName.isPresent() && !velocityFirst) {
        if (isBlocked(bungeeName.get())) {
          return new Outcome(false, "paperproxy.plugin.blocked", bungeeName.get(), "");
        }
        final Path file = bungee.pluginFile(bungeeName.get());
        try {
          final ClassLoader loader = bungee.unloadPlugin(bungeeName.get());
          server.getEventManager().forgetClassLoader(loader);
          scheduleLeakCheck(bungeeName.get(), loader);
        } catch (final RuntimeException e) {
          logger.error("Could not unload {}", bungeeName.get(), e);
          return new Outcome(false, "paperproxy.plugin.failed", bungeeName.get(), e.toString());
        }
        changed(bungeeName.get(), false);
        return new Outcome(true, "paperproxy.plugin.unloaded", bungeeName.get(), file.toString());
      }
    }

    final Optional<PluginContainer> found = server.getPluginManager().getPlugin(
        name.toLowerCase(Locale.ROOT));
    if (found.isEmpty() || found.get().getInstance().isEmpty()) {
      return new Outcome(false, "paperproxy.plugin.not-found", name, "");
    }
    final PluginContainer container = found.get();
    final PluginDescription description = container.getDescription();
    final List<String> dependents = dependents(description.getId());
    if (!dependents.isEmpty()) {
      return new Outcome(false, "paperproxy.plugin.has-dependents", description.getId(),
          String.join(", ", dependents));
    }
    final Optional<Path> source = description.getSource();
    if (source.isEmpty()) {
      return new Outcome(false, "paperproxy.plugin.not-found", name, "");
    }
    final Object instance = container.getInstance().get();

    try {
      server.getEventManager().fireOnly(container, new ProxyShutdownEvent())
          .get(10, TimeUnit.SECONDS);
    } catch (final Exception e) {
      logger.warn("{} did not shut down cleanly: {}", description.getId(), e.toString());
    }
    server.getEventManager().unregisterListeners(instance);
    for (final String alias : List.copyOf(server.getCommandManager().getAliases())) {
      final CommandMeta meta = server.getCommandManager().getCommandMeta(alias);
      if (meta != null && meta.getPlugin() == instance) {
        server.getCommandManager().unregister(alias);
      }
    }
    for (final ScheduledTask task : List.copyOf(server.getScheduler().tasksByPlugin(instance))) {
      task.cancel();
    }
    if (container instanceof VelocityPluginContainer velocityContainer
        && velocityContainer.hasExecutorService()) {
      velocityContainer.getExecutorService().shutdownNow();
    }
    ((VelocityPluginManager) server.getPluginManager()).unregisterPlugin(container);
    final ClassLoader loader = instance.getClass().getClassLoader();
    server.getEventManager().forgetClassLoader(loader);
    if (loader instanceof Closeable closeable) {
      try {
        closeable.close();
      } catch (final IOException e) {
        logger.warn("Could not close the class loader of {}", description.getId(), e);
      }
    }
    scheduleLeakCheck(description.getId(), loader);
    changed(description.getId(), false);
    return new Outcome(true, "paperproxy.plugin.unloaded", description.getId(),
        source.get().toString());
  }

  private Outcome load0(final Path jar, final String label) {
    final long started = System.nanoTime();
    if (BungeeLayerBootstrap.isBungeePlugin(jar)) {
      final BungeeLayerHandle bungee = server.getBungeeLayer();
      if (bungee == null) {
        return new Outcome(false, "paperproxy.plugin.failed", label,
            "the BungeeCord layer only starts with the proxy; restart to load the first "
                + "BungeeCord plugin");
      }
      try {
        final String name = bungee.loadPlugin(jar);
        changed(name, true);
        return new Outcome(true, "paperproxy.plugin.loaded", name, millis(started));
      } catch (final Exception e) {
        logger.error("Could not load {}", jar.getFileName(), e);
        return new Outcome(false, "paperproxy.plugin.failed", label, e.getMessage());
      }
    }
    try {
      final VelocityPluginManager manager = (VelocityPluginManager) server.getPluginManager();
      final PluginContainer container = manager.loadPlugin(jar);
      final Object instance = container.getInstance().orElseThrow();
      server.getEventManager().registerInternally(container, instance);
      server.getEventManager().fireOnly(container, new ProxyInitializeEvent())
          .get(30, TimeUnit.SECONDS);
      changed(container.getDescription().getId(), true);
      return new Outcome(true, "paperproxy.plugin.loaded", container.getDescription().getId(),
          millis(started));
    } catch (final Exception e) {
      logger.error("Could not load {}", jar.getFileName(), e);
      return new Outcome(false, "paperproxy.plugin.failed", label, String.valueOf(e.getMessage()));
    }
  }

  private List<String> dependents(final String id) {
    final List<String> out = new ArrayList<>();
    for (final PluginContainer plugin : server.getPluginManager().getPlugins()) {
      if (plugin.getDescription().getDependency(id).filter(d -> !d.isOptional()).isPresent()) {
        out.add(plugin.getDescription().getId());
      }
    }
    return out;
  }

  private void changed(final String plugin, final boolean loaded) {
    final int count = changes.incrementAndGet();
    server.getEventManager().fireAndForget(new PluginRuntimeChangeEvent(plugin, loaded));
    logger.warn("Plugins were changed at runtime {} time(s) since start. If anything behaves "
        + "oddly, restart the proxy before reporting a bug.", count);
  }

  private void scheduleLeakCheck(final String name, final @Nullable ClassLoader loader) {
    if (loader == null) {
      return;
    }
    final WeakReference<ClassLoader> reference = new WeakReference<>(loader);
    CompletableFuture.delayedExecutor(LEAK_CHECK_SECONDS, TimeUnit.SECONDS).execute(() -> {
      System.gc();
      final ClassLoader alive = reference.get();
      if (alive == null) {
        // Collected: the plugin is completely gone.
        return;
      }
      int threads = 0;
      for (final Thread thread : Thread.getAllStackTraces().keySet()) {
        if (thread.isAlive() && (thread.getContextClassLoader() == alive
            || thread.getClass().getClassLoader() == alive)) {
          threads++;
        }
      }
      if (threads > 0) {
        logger.warn("{} still has {} thread(s) running after it was unloaded. Restart the proxy "
            + "to free them.", name, threads);
      } else {
        logger.warn("{} is still referenced after it was unloaded (memory leak in the plugin or "
            + "another plugin holding on to it). Restart the proxy if memory grows.", name);
      }
    });
  }

  private static String millis(final long startedNanos) {
    return String.valueOf((System.nanoTime() - startedNanos) / 1_000_000L);
  }
}
