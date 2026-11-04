package com.waha.coupon;

import java.security.SecureRandom;

// Random coupon codes. The alphabet leaves out look-alike characters (I, O, 0, 1).
final class CodeGenerator {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private CodeGenerator() {}

    static String generate(String prefix) {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return prefix == null || prefix.isEmpty() ? sb.toString() : prefix + "-" + sb;
    }
}
