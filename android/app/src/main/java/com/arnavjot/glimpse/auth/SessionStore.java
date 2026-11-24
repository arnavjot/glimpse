package com.arnavjot.glimpse.auth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Where the session survives app restarts. Encrypted on the device; in memory in tests. */
public interface SessionStore {

    /** The saved session, or null (none, or it can't be read back). */
    @Nullable
    Session load();

    void save(@NonNull Session session);

    void clear();
}
