package dev.lyy.friendlysync.messaging;

import com.google.gson.Gson;
import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.messaging.message.ServerHeartbeatMessage;
import dev.lyy.friendlysync.messaging.message.SessionInvalidateMessage;
import dev.lyy.friendlysync.messaging.message.TransferReadyMessage;
import dev.lyy.friendlysync.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Resilient Redis Pub/Sub messaging service with connection pooling and bounded exponential backoff reconnection.
 */
public final class RedisMessagingService implements MessagingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisMessagingService.class);

    private static final String CHANNEL_TRANSFER = "friendlysync:transfer";
    private static final String CHANNEL_TRANSFER_REQUEST = "friendlysync:transfer_request";
    private static final String CHANNEL_INVALIDATE = "friendlysync:invalidate";
    private static final String CHANNEL_SERVER = "friendlysync:server";
    private static final String STASH_PREFIX = "friendlysync:stash:";

    private final FriendlySyncConfig config;
    private final Gson gson = new Gson();
    private final Random random = new Random();

    private final List<Consumer<TransferReadyMessage>> transferListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<dev.lyy.friendlysync.messaging.message.TransferRequestMessage>> transferRequestListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SessionInvalidateMessage>> invalidateListeners = new CopyOnWriteArrayList<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private volatile JedisPool jedisPool;
    private volatile ExecutorService subscriberExecutor;
    private volatile JedisPubSub currentPubSub;

    public RedisMessagingService(FriendlySyncConfig config) {
        this.config = Preconditions.checkNotNull(config, "config cannot be null");
    }

    @Override
    public synchronized void initialize() {
        if (!config.isRedisEnabled()) {
            LOGGER.info("Redis coordination is disabled in config. Operating purely on durable SQL authority.");
            return;
        }

        LOGGER.info("Initializing Redis connection to {}:{}...", config.getRedisHost(), config.getRedisPort());

        DefaultJedisClientConfig.Builder clientConfig = DefaultJedisClientConfig.builder()
            .timeoutMillis(config.getRedisTimeout())
            .autoNegotiateProtocol(false)
            .ssl(config.isRedisSsl())
            .database(config.getRedisDatabase());

        if (config.getRedisPassword() != null && !config.getRedisPassword().isEmpty()) {
            if (config.getRedisUsername() != null && !config.getRedisUsername().isEmpty()) {
                clientConfig.user(config.getRedisUsername());
            }
            clientConfig.password(config.getRedisPassword());
        }

        HostAndPort hostAndPort = new HostAndPort(config.getRedisHost(), config.getRedisPort());
        this.jedisPool = new JedisPool(hostAndPort, clientConfig.build());

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ping();
            connected.set(true);
            LOGGER.info("Connected to Redis successfully.");
        } catch (Exception e) {
            connected.set(false);
            LOGGER.warn("Initial Redis connection failed (will retry in background): {}", e.getMessage());
        }

        running.set(true);
        ClassLoader classLoader = getClass().getClassLoader();
        this.subscriberExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "FriendlySync-Redis-Subscriber");
            t.setDaemon(true);
            t.setContextClassLoader(classLoader);
            return t;
        });

        subscriberExecutor.submit(this::subscribeLoop);
    }

    private void subscribeLoop() {
        long backoffMs = 1000;
        while (running.get()) {
            try {
                this.currentPubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        handleIncomingMessage(channel, message);
                    }

                    @Override
                    public void onSubscribe(String channel, int subscribedChannels) {
                        connected.set(true);
                        LOGGER.info("Subscribed to Redis channel: {}", channel);
                    }

                    @Override
                    public void onUnsubscribe(String channel, int subscribedChannels) {
                        LOGGER.info("Unsubscribed from Redis channel: {}", channel);
                    }
                };

                try (Jedis subscriber = jedisPool.getResource()) {
                    subscriber.subscribe(currentPubSub, CHANNEL_TRANSFER, CHANNEL_TRANSFER_REQUEST, CHANNEL_INVALIDATE, CHANNEL_SERVER);
                }
                backoffMs = 1000; // Reset backoff on successful run
            } catch (Exception e) {
                connected.set(false);
                if (!running.get()) {
                    break;
                }

                long jitter = random.nextInt(500);
                long sleepTime = Math.min(30000, backoffMs) + jitter;
                LOGGER.warn("Redis subscription disconnected ({}); reconnecting in {}ms...", e.getMessage(), sleepTime);

                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoffMs = Math.min(30000, backoffMs * 2);
            }
        }
    }

    private void handleIncomingMessage(String channel, String payload) {
        try {
            if (CHANNEL_TRANSFER.equals(channel)) {
                TransferReadyMessage msg = gson.fromJson(payload, TransferReadyMessage.class);
                if (msg != null && msg.protocolVersion() == TransferReadyMessage.CURRENT_PROTOCOL_VERSION) {
                    for (Consumer<TransferReadyMessage> listener : transferListeners) {
                        try {
                            listener.accept(msg);
                        } catch (Exception e) {
                            LOGGER.error("Error dispatching transfer message to listener: {}", e.getMessage(), e);
                        }
                    }
                }
            } else if (CHANNEL_TRANSFER_REQUEST.equals(channel)) {
                dev.lyy.friendlysync.messaging.message.TransferRequestMessage msg =
                    gson.fromJson(payload, dev.lyy.friendlysync.messaging.message.TransferRequestMessage.class);
                if (msg != null && msg.protocolVersion() == dev.lyy.friendlysync.messaging.message.TransferRequestMessage.CURRENT_PROTOCOL_VERSION) {
                    for (Consumer<dev.lyy.friendlysync.messaging.message.TransferRequestMessage> listener : transferRequestListeners) {
                        try {
                            listener.accept(msg);
                        } catch (Exception e) {
                            LOGGER.error("Error dispatching transfer request message to listener: {}", e.getMessage(), e);
                        }
                    }
                }
            } else if (CHANNEL_INVALIDATE.equals(channel)) {
                SessionInvalidateMessage msg = gson.fromJson(payload, SessionInvalidateMessage.class);
                if (msg != null && msg.protocolVersion() == SessionInvalidateMessage.CURRENT_PROTOCOL_VERSION) {
                    for (Consumer<SessionInvalidateMessage> listener : invalidateListeners) {
                        try {
                            listener.accept(msg);
                        } catch (Exception e) {
                            LOGGER.error("Error dispatching invalidate message to listener: {}", e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to parse incoming Redis message on {}: {}", channel, e.getMessage(), e);
        }
    }

    @Override
    public void publishTransferReady(TransferReadyMessage message) {
        publish(CHANNEL_TRANSFER, gson.toJson(message));
    }

    @Override
    public void publishTransferRequest(dev.lyy.friendlysync.messaging.message.TransferRequestMessage message) {
        publish(CHANNEL_TRANSFER_REQUEST, gson.toJson(message));
    }

    @Override
    public void publishSessionInvalidate(SessionInvalidateMessage message) {
        publish(CHANNEL_INVALIDATE, gson.toJson(message));
    }

    @Override
    public void publishServerHeartbeat(ServerHeartbeatMessage message) {
        publish(CHANNEL_SERVER, gson.toJson(message));
    }

    private void publish(String channel, String message) {
        if (!config.isRedisEnabled() || jedisPool == null || jedisPool.isClosed()) {
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.publish(channel, message);
        } catch (Exception e) {
            LOGGER.warn("Failed to publish message to Redis {}: {}", channel, e.getMessage());
        }
    }

    @Override
    public void registerTransferListener(Consumer<TransferReadyMessage> listener) {
        transferListeners.add(listener);
    }

    @Override
    public void registerTransferRequestListener(Consumer<dev.lyy.friendlysync.messaging.message.TransferRequestMessage> listener) {
        transferRequestListeners.add(listener);
    }

    @Override
    public void registerInvalidateListener(Consumer<SessionInvalidateMessage> listener) {
        invalidateListeners.add(listener);
    }

    @Override
    public boolean isHealthy() {
        return !config.isRedisEnabled() || connected.get();
    }

    @Override
    public void stashTransferPayload(java.util.UUID playerUuid, byte[] payload, int ttlSeconds) {
        if (playerUuid == null || payload == null || !config.isTransferStashEnabled()
            || jedisPool == null || jedisPool.isClosed()) {
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            byte[] key = (STASH_PREFIX + playerUuid).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            jedis.setex(key, ttlSeconds, payload);
            LOGGER.debug("[STASH] Stashed transfer payload for {} ({} bytes, ttl={}s)", playerUuid, payload.length, ttlSeconds);
        } catch (Exception e) {
            LOGGER.warn("[STASH] Failed to stash transfer payload for {} in Redis (fallback to SQL will occur): {}",
                playerUuid, e.getMessage());
        }
    }

    @Override
    public byte[] retrieveTransferPayload(java.util.UUID playerUuid) {
        if (playerUuid == null || !config.isTransferStashEnabled() || jedisPool == null || jedisPool.isClosed()) {
            return null;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            byte[] key = (STASH_PREFIX + playerUuid).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] data;
            try {
                data = jedis.getDel(key);
            } catch (Exception notSupported) {
                data = jedis.get(key);
                if (data != null) {
                    jedis.del(key);
                }
            }
            if (data != null) {
                LOGGER.debug("[STASH] Retrieved and cleared transfer stash for {} ({} bytes)", playerUuid, data.length);
            }
            return data;
        } catch (Exception e) {
            LOGGER.warn("[STASH] Failed to retrieve transfer stash for {} from Redis: {}", playerUuid, e.getMessage());
            return null;
        }
    }

    @Override
    public void clearTransferPayload(java.util.UUID playerUuid) {
        if (playerUuid == null || !config.isRedisEnabled() || jedisPool == null || jedisPool.isClosed()) {
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            byte[] key = (STASH_PREFIX + playerUuid).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            jedis.del(key);
        } catch (Exception ignored) {
        }
    }

    @Override
    public synchronized void close() {
        running.set(false);
        connected.set(false);

        if (currentPubSub != null && currentPubSub.isSubscribed()) {
            try {
                currentPubSub.unsubscribe();
            } catch (Exception ignored) {
            }
        }

        if (subscriberExecutor != null) {
            subscriberExecutor.shutdownNow();
            try {
                if (!subscriberExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    LOGGER.warn("Redis subscriber executor did not terminate in 2 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            subscriberExecutor = null;
        }

        if (jedisPool != null && !jedisPool.isClosed()) {
            LOGGER.info("Closing Redis connection pool...");
            jedisPool.close();
            jedisPool = null;
        }
    }
}
