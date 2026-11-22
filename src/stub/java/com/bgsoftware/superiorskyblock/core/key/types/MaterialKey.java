package com.bgsoftware.superiorskyblock.core.key.types;

import com.bgsoftware.superiorskyblock.api.key.Key;

public class MaterialKey implements Key {
    private final String globalKey;
    private final String subKey;

    public MaterialKey(String globalKey, String subKey) {
        this.globalKey = globalKey;
        this.subKey = subKey;
    }

    @Override
    public String getGlobalKey() {
        return globalKey;
    }

    @Override
    public String getSubKey() {
        return subKey;
    }
}
