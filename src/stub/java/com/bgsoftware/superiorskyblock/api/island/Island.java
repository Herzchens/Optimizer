package com.bgsoftware.superiorskyblock.api.island;

import com.bgsoftware.superiorskyblock.api.key.Key;

public interface Island {
    void handleBlockBreak(Key key, int amount, int flags);
}
