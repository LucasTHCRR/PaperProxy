package net.md_5.bungee.scheduler;

import java.util.concurrent.TimeUnit;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.api.scheduler.TaskScheduler;

/**
 * Same name as BungeeCord's scheduler class, for plugins compiled against
 * {@code BungeeCord.getScheduler()}. Hands every call to PaperProxy's scheduler.
 */
public class BungeeScheduler implements TaskScheduler
{

    private final TaskScheduler delegate;

    /**
     * Wraps a scheduler.
     *
     * @param delegate the real scheduler
     */
    public BungeeScheduler(TaskScheduler delegate)
    {
        this.delegate = delegate;
    }

    @Override
    public void cancel(int id)
    {
        delegate.cancel( id );
    }

    @Override
    public void cancel(ScheduledTask task)
    {
        delegate.cancel( task );
    }

    @Override
    public int cancel(Plugin plugin)
    {
        return delegate.cancel( plugin );
    }

    @Override
    public ScheduledTask runAsync(Plugin owner, Runnable task)
    {
        return delegate.runAsync( owner, task );
    }

    @Override
    public ScheduledTask schedule(Plugin owner, Runnable task, long delay, TimeUnit unit)
    {
        return delegate.schedule( owner, task, delay, unit );
    }

    @Override
    public ScheduledTask schedule(Plugin owner, Runnable task, long delay, long period, TimeUnit unit)
    {
        return delegate.schedule( owner, task, delay, period, unit );
    }

    @Override
    public Unsafe unsafe()
    {
        return delegate.unsafe();
    }
}
