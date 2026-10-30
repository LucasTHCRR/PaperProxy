package net.paperstream.reloadprobe;

import java.util.Properties;
import java.util.function.Consumer;

// Zustand liegt in den System-Properties, weil jede geladene Instanz einen eigenen Classloader hat.
public final class Probe {

  private static final long TICK_WINDOW_MS = 3000;
  private static final long SWITCH_WINDOW_MS = 1000;

  private final String prefix;
  private final Consumer<String> info;
  private final Consumer<String> warn;
  private final int generation;
  private volatile boolean stopped;

  public Probe(String platform, Consumer<String> info, Consumer<String> warn) {
    this.prefix = "reloadprobe." + platform + ".";
    this.info = info;
    this.warn = warn;
    Properties p = System.getProperties();
    synchronized (p) {
      generation = Integer.parseInt(p.getProperty(prefix + "generation", "0")) + 1;
      p.setProperty(prefix + "generation", Integer.toString(generation));
    }
    info.accept("[ReloadProbe] started generation " + generation);
  }

  public void tick() {
    if (stopped) {
      warn.accept("[ReloadProbe] LEAK task of stopped generation " + generation + " still runs");
    }
    long now = System.currentTimeMillis();
    Properties p = System.getProperties();
    synchronized (p) {
      for (String key : p.stringPropertyNames()) {
        if (!key.startsWith(prefix + "tick.")) {
          continue;
        }
        int other = Integer.parseInt(key.substring((prefix + "tick.").length()));
        long last = Long.parseLong(p.getProperty(key));
        if (other != generation && now - last < TICK_WINDOW_MS) {
          warn.accept("[ReloadProbe] LEAK task of generation " + other + " runs next to generation " + generation);
        }
      }
      p.setProperty(prefix + "tick." + generation, Long.toString(now));
    }
    info.accept("[ReloadProbe] tick generation " + generation);
  }

  public void serverSwitch(String player, String server) {
    long now = System.currentTimeMillis();
    String key = prefix + "switch." + player + "." + server;
    Properties p = System.getProperties();
    synchronized (p) {
      String last = p.getProperty(key);
      if (last != null && now - Long.parseLong(last) < SWITCH_WINDOW_MS) {
        warn.accept("[ReloadProbe] DUPLICATE listener: switch of " + player + " to " + server + " handled twice");
      }
      p.setProperty(key, Long.toString(now));
    }
    info.accept("[ReloadProbe] switch generation " + generation + " " + player + " -> " + server);
  }

  public void stop() {
    stopped = true;
    System.getProperties().remove(prefix + "tick." + generation);
    info.accept("[ReloadProbe] stopped generation " + generation);
  }
}
