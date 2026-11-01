package com.harnkan.deal.util;

import java.util.UUID;

public final class Ids {

    private Ids() {
    }

    /** 32-character */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
