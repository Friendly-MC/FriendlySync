package dev.lyy.friendlysync;

import dev.lyy.friendlysync.listener.PlayerConnectionListener;
import dev.lyy.friendlysync.session.PlayerSessionCoordinator;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PlayerConnectionListenerTest {

    private PlayerSessionCoordinator coordinator;
    private PlayerConnectionListener listener;

    @BeforeEach
    void setUp() {
        coordinator = mock(PlayerSessionCoordinator.class);
        listener = new PlayerConnectionListener(coordinator);
    }

    @Test
    @DisplayName("Fail-closed: unexpected exception in handleAsyncPreLogin disallows login and triggers handleLoginDisallowed")
    void preLoginThrowsUnexpectedExceptionFailsClosed() {
        UUID uuid = UUID.randomUUID();
        String name = "GlitchyPlayer";

        AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(uuid);
        when(event.getName()).thenReturn(name);
        when(event.getLoginResult()).thenReturn(AsyncPlayerPreLoginEvent.Result.ALLOWED);

        doThrow(new NullPointerException("Simulated crash in coordinator"))
            .when(coordinator).handleAsyncPreLogin(event);

        listener.onAsyncPreLogin(event);

        verify(event).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER), any(Component.class));
        verify(coordinator).handleLoginDisallowed(uuid);
    }

    @Test
    @DisplayName("Normal pre-login does not disallow player when coordinator succeeds")
    void normalPreLoginDoesNotDisallow() {
        UUID uuid = UUID.randomUUID();
        String name = "NormalPlayer";

        AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(uuid);
        when(event.getName()).thenReturn(name);
        when(event.getLoginResult()).thenReturn(AsyncPlayerPreLoginEvent.Result.ALLOWED);

        listener.onAsyncPreLogin(event);

        verify(coordinator).handleAsyncPreLogin(event);
        verify(event, never()).disallow(any(AsyncPlayerPreLoginEvent.Result.class), any(Component.class));
        verify(coordinator, never()).handleLoginDisallowed(uuid);
    }

    @Test
    @DisplayName("Already disallowed event skips coordinator handleAsyncPreLogin")
    void alreadyDisallowedPreLoginSkipsCoordinator() {
        UUID uuid = UUID.randomUUID();
        String name = "BannedPlayer";

        AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(uuid);
        when(event.getName()).thenReturn(name);
        when(event.getLoginResult()).thenReturn(AsyncPlayerPreLoginEvent.Result.KICK_BANNED);

        listener.onAsyncPreLogin(event);

        verify(coordinator, never()).handleAsyncPreLogin(any());
        verify(coordinator, never()).handleLoginDisallowed(any());
    }

    @Test
    @DisplayName("Pre-login monitor cleans up disallowed logins")
    void preLoginMonitorCleansUpDisallowed() {
        UUID uuid = UUID.randomUUID();
        String name = "KickedPlayer";

        AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(uuid);
        when(event.getName()).thenReturn(name);
        when(event.getLoginResult()).thenReturn(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST);

        listener.onAsyncPreLoginMonitor(event);

        verify(coordinator).handleLoginDisallowed(uuid);
    }

    @Test
    @DisplayName("Player login monitor cleans up disallowed logins")
    void playerLoginMonitorCleansUpDisallowed() {
        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);

        PlayerLoginEvent event = mock(PlayerLoginEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getResult()).thenReturn(PlayerLoginEvent.Result.KICK_OTHER);

        listener.onPlayerLogin(event);

        verify(coordinator).handleLoginDisallowed(uuid);
    }
}
