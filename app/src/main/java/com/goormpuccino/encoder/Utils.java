package com.goormpuccino.encoder;

public class Utils {
    public static void sleep(long mili) {
        try {
            Thread.sleep(mili);
        } catch (Exception e) {
            // Do nothing
        }
    }
}
