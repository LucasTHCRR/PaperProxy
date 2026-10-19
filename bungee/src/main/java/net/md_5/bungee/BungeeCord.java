package net.md_5.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.scheduler.BungeeScheduler;

/**
 * Stand-in for BungeeCord's main class. The real one is not part of the BungeeCord API, but
 * many plugins call {@code BungeeCord.getInstance()} instead of {@code ProxyServer.getInstance()}.
 * PaperProxy's proxy object extends this class, so those calls work.
 */
public abstract class BungeeCord extends ProxyServer
{

    /**
     * Set to false while the proxy shuts down, like in BungeeCord.
     */
    public volatile boolean isRunning = true;

    /**
     * Returns the running proxy.
     *
     * @return the proxy
     */
    public static BungeeCord getInstance()
    {
        return (BungeeCord) ProxyServer.getInstance();
    }

    @Override
    public abstract BungeeScheduler getScheduler();
}
