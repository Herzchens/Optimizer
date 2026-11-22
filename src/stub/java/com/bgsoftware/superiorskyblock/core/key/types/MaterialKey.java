package com.bgsoftware.superiorskyblock.core.key.types;

import com.bgsoftware.superiorskyblock.api.key.Key;
import org.bukkit.Material;

public class MaterialKey implements Key {
    private final String globalKey;
    private final String subKey;

    public MaterialKey(String globalKey, String subKey) {
        this.globalKey = globalKey;
        this.subKey = subKey;
    }

    public static MaterialKey of(Material material) {
        return new MaterialKey(material.name(), "");
    }

    @Override
    public String getGlobalKey() {
        return globalKey;
    }

    @Override
    public String getSubKey() {
        return subKey;
    }

    @Override
    public int compareTo(Key o) {
        return 0;
    }
}

