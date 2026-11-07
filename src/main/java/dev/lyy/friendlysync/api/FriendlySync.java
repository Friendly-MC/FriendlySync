package dev.lyy.friendlysync.api;

import dev.lyy.friendlysync.util.Preconditions;

/**
 * Static provider accessor for {@link FriendlySyncApi}.
 */
public final class FriendlySync {

    private static FriendlySyncApi api;

    private FriendlySync() {
    }

    public static FriendlySyncApi get() {
        Preconditions.checkState(api != null, "FriendlySyncApi has not been initialized yet!");
        return api;
    }

    public static void setApi(FriendlySyncApi instance) {
        api = instance;
    }
}
