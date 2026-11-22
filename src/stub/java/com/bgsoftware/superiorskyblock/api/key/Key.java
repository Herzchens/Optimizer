package com.bgsoftware.superiorskyblock.api.key;

public interface Key extends Comparable<Key> {
    String getGlobalKey();
    String getSubKey();
}

