package com.arnavjot.glimpse.common;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A one-shot value sent through LiveData (an error to show once, a navigation).
 * LiveData redelivers its last value to new observers, e.g. after rotation; wrapping it
 * means the message is shown once, not again on every re-subscribe.
 */
public final class Event<T> {

    @NonNull private final T content;
    private boolean handled;

    public Event(@NonNull T content) {
        this.content = content;
    }

    /** The content the first time it is asked for, then null. */
    @Nullable
    public synchronized T consume() {
        if (handled) return null;
        handled = true;
        return content;
    }

    @NonNull
    public T peek() {
        return content;
    }
}
