package dev.codex.chaoxingdeadline;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.security.SecureRandom;

public final class BridgeAuth {
    public static final String PREFS_NAME = "bridge_auth";
    public static final String KEY_TOKEN = "token";
    public static final String EXTRA_TOKEN = "bridge_token";
    private static final String TAG = "ChaoxingDeadline";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private BridgeAuth() {
    }

    public static String ensureToken(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String token = prefs.getString(KEY_TOKEN, "");
        if (token != null && token.length() >= 32) {
            return token;
        }
        token = newToken();
        prefs.edit().putString(KEY_TOKEN, token).apply();
        return token;
    }

    public static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return toHex(bytes);
    }

    public static void attach(Context context, Intent intent) {
        intent.putExtra(EXTRA_TOKEN, ensureToken(context));
    }

    public static boolean isValid(Context context, Intent intent) {
        if (intent == null) {
            return false;
        }
        String expected = ensureToken(context);
        String actual = intent.getStringExtra(EXTRA_TOKEN);
        boolean valid = expected.equals(actual);
        if (!valid) {
            Log.w(TAG, "Rejected unauthenticated bridge broadcast: " + intent.getAction());
        }
        return valid;
    }

    private static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            chars[i * 2] = HEX[value >>> 4];
            chars[i * 2 + 1] = HEX[value & 0x0f];
        }
        return new String(chars);
    }
}
