package dev.lyy.friendlysync.scheduler;

import org.bukkit.plugin.Plugin;

/** Uses the shared Paper schedulers with Folia ownership checks. */
public final class FoliaScheduler extends PaperScheduler {
    public FoliaScheduler(Plugin plugin) {
        super(plugin, true);
    }
}
