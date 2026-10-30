package net.paperstream.reloadprobe;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

@Plugin(id = "reloadprobe", name = "ReloadProbe", version = "1.0.0", authors = {"LucasTHCR"},
    description = "Shows leftover tasks and duplicate listeners after a plugin reload")
public final class ReloadProbeVelocity {

  private final ProxyServer proxy;
  private final Logger logger;
  private Probe probe;

  @Inject
  public ReloadProbeVelocity(ProxyServer proxy, Logger logger) {
    this.proxy = proxy;
    this.logger = logger;
  }

  @Subscribe
  public void onInit(ProxyInitializeEvent event) {
    probe = new Probe("velocity", logger::info, logger::warn);
    proxy.getScheduler().buildTask(this, probe::tick).delay(2, TimeUnit.SECONDS).repeat(2, TimeUnit.SECONDS).schedule();
  }

  @Subscribe
  public void onShutdown(ProxyShutdownEvent event) {
    if (probe != null) {
      probe.stop();
    }
  }

  @Subscribe
  public void onSwitch(ServerConnectedEvent event) {
    probe.serverSwitch(event.getPlayer().getUsername(), event.getServer().getServerInfo().getName());
  }
}
