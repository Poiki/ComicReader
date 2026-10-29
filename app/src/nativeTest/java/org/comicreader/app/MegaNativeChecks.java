package org.comicreader.app;

import android.content.Context;
import java.io.*;

/** JNI integration check; refuses to use an installation with a real saved account. */
final class MegaNativeChecks {
    static String run(Context context, boolean network) throws Exception {
            MegaClient client = MegaClient.instance(context);
            check(client.available(), "The actual MEGA SDK must be included");
            check(!client.loggedIn(), "Use a disposable test installation without a saved account");
            try { client.list(MegaClient.ROOT); throw new AssertionError("An anonymous account tree was accepted"); }
            catch (IOException expected) {
                check("Inicia sesión en MEGA".equals(expected.getMessage()), "JNI initialization failed: " + expected.getMessage());
            }
            File cache = new File(context.getNoBackupFilesDir(), "mega-sdk");
            check(cache.isDirectory() && cache.canWrite(), "The native cache must be private and writable");
            File temporary = File.createTempFile("native-check-", ".tmp", cache);
            var apiField = MegaClient.class.getDeclaredField("api"); apiField.setAccessible(true);
            nz.mega.sdk.MegaApiJava sdk = (nz.mega.sdk.MegaApiJava) apiField.get(client);
            try {
                try (OutputStream out = new FileOutputStream(temporary)) { out.write(new byte[]{1, 2, 3, 4, 5}); }
                String fingerprint = sdk.getFingerprint(temporary.getAbsolutePath());
                check(fingerprint != null && !fingerprint.isEmpty(), "The SDK must read private paths using native file IO");
            } finally { check(temporary.delete(), "The cache must support normal file operations"); }
            check(sdk.getFingerprint(temporary.getAbsolutePath()) == null, "The native SDK must reject a missing path");
            String outcome = "PASS actual MEGA JNI " + sdk.getVersion() + ", native file IO and private cache\n";
            if (network) {
                try {
                    client.login("comicreader-native-check@example.invalid", "NotARealAccount-2026", "");
                    throw new AssertionError("A reserved nonexistent account was accepted");
                } catch (IOException expected) {
                    String message = expected.getMessage();
                    check(message != null && (message.contains("(código -9)") || message.contains("denegó el acceso")),
                            "The SDK did not return the expected authentication callback: " + message);
                }
                check(!client.loggedIn(), "A rejected account must not persist a session");
                outcome += "PASS real SDK authentication callback for reserved nonexistent account\n";
            }
            client.suspend();
            return outcome;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
