package org.comicreader.app;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

final class Secrets {
    static void put(Context context, String name, String value)
            throws GeneralSecurityException, IOException {
        if (!context.getSharedPreferences("secrets", 0)
                .edit()
                .putString(name, encrypt(value))
                .commit()) throw new IOException("Cannot save the session");
    }

    static String get(Context context, String name) throws GeneralSecurityException, IOException {
        String value = context.getSharedPreferences("secrets", 0).getString(name, "");
        if (value.isEmpty()) return null;
        return new String(decrypt(value), StandardCharsets.UTF_8);
    }

    static void remove(Context context, String name) throws IOException {
        if (!context.getSharedPreferences("secrets", 0).edit().remove(name).commit())
            throw new IOException("Cannot remove the session");
    }

    private static synchronized javax.crypto.SecretKey key()
            throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias("webdav")) {
            KeyGenerator generator =
                    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(
                    new KeyGenParameterSpec.Builder(
                                    "webdav",
                                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build());
            generator.generateKey();
        }
        return (javax.crypto.SecretKey) store.getKey("webdav", null);
    }

    static void configure(Context context, String endpoint, String user, String password)
            throws GeneralSecurityException, IOException {
        if (user.contains(":")) throw new IOException("The username cannot contain a colon");
        String value = user.isEmpty() && password.isEmpty() ? "" : encrypt(user + ":" + password);
        if (!context.getSharedPreferences("cloud", 0)
                .edit()
                .putString("endpoint", endpoint)
                .putString("auth", value)
                .putString("provider", "webdav")
                .commit()) throw new IOException("Cannot save the account");
    }

    static String authorization(Context context) throws GeneralSecurityException, IOException {
        String value = context.getSharedPreferences("cloud", 0).getString("auth", "");
        if (value.isEmpty()) return "";
        return "Basic " + Base64.encodeToString(decrypt(value), Base64.NO_WRAP);
    }

    private static String encrypt(String value) throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                + "."
                + Base64.encodeToString(
                        cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static byte[] decrypt(String value) throws GeneralSecurityException, IOException {
        String[] parts = value.split("\\.");
        if (parts.length != 2)
            throw new IOException("Session or credentials are damaged; reconnect the account");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP));
    }
}
