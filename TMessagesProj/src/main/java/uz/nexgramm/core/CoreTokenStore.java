package uz.nexgramm.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

// NexGramm: core tokens per Telegram user id, encrypted with an AES-GCM key kept in Android Keystore.
// Android < 6 has no Keystore AES keys: tokens then live in memory only (login again after restart).
final class CoreTokenStore {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "nexgramm_core_tokens";
    private static final String PREFS = "nexgramm_core";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final java.util.HashMap<Long, CoreTokens> memoryOnly = new java.util.HashMap<>();
    private static final java.util.HashMap<String, String> memorySecrets = new java.util.HashMap<>();

    private CoreTokenStore() {
    }

    private static String prefKey(long tgUserId) {
        return "tokens_" + tgUserId;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized CoreTokens load(long tgUserId) {
        if (Build.VERSION.SDK_INT < 23) {
            return memoryOnly.get(tgUserId);
        }
        final String json = loadSecret(prefKey(tgUserId));
        if (json == null) {
            return null;
        }
        try {
            return CoreTokens.fromJson(json);
        } catch (Exception e) {
            clearSecret(prefKey(tgUserId));
            return null;
        }
    }

    static synchronized boolean save(long tgUserId, CoreTokens tokens) {
        if (Build.VERSION.SDK_INT < 23) {
            memoryOnly.put(tgUserId, tokens);
            return true;
        }
        try {
            return saveSecret(prefKey(tgUserId), tokens.toJson());
        } catch (Exception e) {
            return false;
        }
    }

    // Any secret string (Pixelfed client and tokens too), AES-GCM with the Keystore key.
    // Android < 6: memory only. A key that cannot be decrypted is removed (logged out).
    static synchronized String loadSecret(String name) {
        if (Build.VERSION.SDK_INT < 23) {
            return memorySecrets.get(name);
        }
        final String stored = prefs().getString(name, null);
        if (stored == null) {
            return null;
        }
        try {
            final int dot = stored.indexOf(':');
            final byte[] iv = Base64.decode(stored.substring(0, dot), Base64.NO_WRAP);
            final byte[] data = Base64.decode(stored.substring(dot + 1), Base64.NO_WRAP);
            final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Key gone or data damaged: treat as absent (nothing about the value is logged)
            prefs().edit().remove(name).commit();
            return null;
        }
    }

    static synchronized boolean saveSecret(String name, String value) {
        if (Build.VERSION.SDK_INT < 23) {
            memorySecrets.put(name, value);
            return true;
        }
        try {
            final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            final byte[] data = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            final String stored = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP);
            return prefs().edit().putString(name, stored).commit();
        } catch (Exception e) {
            return false;
        }
    }

    static synchronized void clearSecret(String name) {
        memorySecrets.remove(name);
        if (Build.VERSION.SDK_INT >= 23) {
            prefs().edit().remove(name).commit();
        }
    }

    static synchronized void clear(long tgUserId) {
        memoryOnly.remove(tgUserId);
        if (Build.VERSION.SDK_INT >= 23) {
            prefs().edit().remove(prefKey(tgUserId)).commit();
        }
    }

    @androidx.annotation.RequiresApi(23)
    private static SecretKey key() throws Exception {
        final KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        final KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        final KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return generator.generateKey();
    }
}
