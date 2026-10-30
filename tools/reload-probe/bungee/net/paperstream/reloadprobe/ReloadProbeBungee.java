package net.paperstream.reloadprobe;

import java.util.concurrent.TimeUnit;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

public final class ReloadProbeBungee extends Plugin implements Listener {

  private Probe probe;

  @Override
  public void onEnable() {
    probe = new Probe("bungee", getLogger()::info, getLogger()::warning);
    getProxy().getScheduler().schedule(this, probe::tick, 2, 2, TimeUnit.SECONDS);
    getProxy().getPluginManager().registerListener(this, this);
    // Nur fuer die Gegenprobe: eigener Thread, den kein Proxy beim Entladen stoppen kann
    if (new java.io.File(getDataFolder().getParentFile(), "reloadprobe-leak").exists()) {
      Thread leak = new Thread(() -> {
        while (true) {
          try {
            Thread.sleep(2000);
          } catch (InterruptedException e) {
            return;
          }
          probe.tick();
        }
      }, "ReloadProbe-leak");
      leak.setDaemon(true);
      leak.start();
    }
  }

  @Override
  public void onDisable() {
    if (probe != null) {
      probe.stop();
    }
  }

  @EventHandler
  public void onSwitch(ServerSwitchEvent event) {
    probe.serverSwitch(event.getPlayer().getName(), event.getPlayer().getServer().getInfo().getName());
  }
}
