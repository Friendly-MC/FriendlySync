package dev.lyy.friendlysync.messaging;

import dev.lyy.friendlysync.messaging.message.ServerHeartbeatMessage;
import dev.lyy.friendlysync.messaging.message.SessionInvalidateMessage;
import dev.lyy.friendlysync.messaging.message.TransferReadyMessage;

import java.util.function.Consumer;

/**
 * Coordination messaging abstraction across servers.
 */
public interface MessagingService extends AutoCloseable {

    void initialize();

    void publishTransferReady(TransferReadyMessage message);

    void publishTransferRequest(dev.lyy.friendlysync.messaging.message.TransferRequestMessage message);

    void publishSessionInvalidate(SessionInvalidateMessage message);

    void publishServerHeartbeat(ServerHeartbeatMessage message);

    void registerTransferListener(Consumer<TransferReadyMessage> listener);

    void registerTransferRequestListener(Consumer<dev.lyy.friendlysync.messaging.message.TransferRequestMessage> listener);

    void registerInvalidateListener(Consumer<SessionInvalidateMessage> listener);

    boolean isHealthy();

    @Override
    void close();
}
