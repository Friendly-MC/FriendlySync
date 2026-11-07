package dev.lyy.friendlysync.listener;

import dev.lyy.friendlysync.session.PlayerSessionCoordinator;
import dev.lyy.friendlysync.util.Preconditions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin listener handling player join and disconnect events, delegating lifecycle logic to the coordinator.
 */
public final class PlayerConnectionListener implements Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerConnectionListener.class);

    private final PlayerSessionCoordinator sessionCoordinator;

    public PlayerConnectionListener(PlayerSessionCoordinator sessionCoordinator) {
        this.sessionCoordinator = Preconditions.checkNotNull(sessionCoordinator, "sessionCoordinator cannot be null");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            sessionCoordinator.handleAsyncPreLogin(event);
        } catch (Throwable t) {
            LOGGER.error("[PRE-LOGIN] Unexpected error during pre-login for {} ({}): failing closed",
                event.getName(), event.getUniqueId(), t);
            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text("Failed to initialize session. Please reconnect.", NamedTextColor.RED)
            );
            sessionCoordinator.handleLoginDisallowed(event.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAsyncPreLoginMonitor(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            sessionCoordinator.handleLoginDisallowed(event.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            sessionCoordinator.handleLoginDisallowed(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        sessionCoordinator.handlePlayerJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        sessionCoordinator.handlePlayerQuit(event.getPlayer());
    }
}
