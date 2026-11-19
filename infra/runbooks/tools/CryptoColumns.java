import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Offline helper for CryptoService-compatible AES-GCM columns. No Spring classpath required. */
public final class CryptoColumns {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoColumns() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            usage("expected one mode");
        }
        switch (args[0]) {
            case "encrypt" -> System.out.println(encrypt(readStdin(), key("APP_ENCRYPTION_KEY")));
            case "decrypt" -> System.out.println(decrypt(readStdin().trim(), key("APP_ENCRYPTION_KEY")));
            case "rotate" -> rotateLines(key("OLD_APP_ENCRYPTION_KEY"), key("NEW_APP_ENCRYPTION_KEY"));
            case "rotate-tsv" -> rotateTsv(key("OLD_APP_ENCRYPTION_KEY"), key("NEW_APP_ENCRYPTION_KEY"));
            default -> usage("unknown mode: " + args[0]);
        }
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("usage: java CryptoColumns.java encrypt|decrypt|rotate|rotate-tsv");
        System.err.println("  encrypt     stdin=plaintext,          env=APP_ENCRYPTION_KEY");
        System.err.println("  decrypt     stdin=ciphertext,         env=APP_ENCRYPTION_KEY");
        System.err.println("  rotate      stdin=ciphertext lines,   env=OLD/NEW_APP_ENCRYPTION_KEY");
        System.err.println("  rotate-tsv  stdin=id<TAB>ciphertext,   env=OLD/NEW_APP_ENCRYPTION_KEY");
        System.exit(2);
    }

    private static byte[] key(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        byte[] key = Base64.getDecoder().decode(value.trim());
        if (key.length < 32) {
            throw new IllegalStateException(name + " must decode to at least 32 bytes");
        }
        return key;
    }

    private static String readStdin() throws Exception {
        return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String encrypt(String plaintext, byte[] key) throws GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
    }

    private static String decrypt(String token, byte[] key) throws GeneralSecurityException {
        byte[] combined = Base64.getUrlDecoder().decode(token);
        if (combined.length <= IV_BYTES + TAG_BITS / 8) {
            throw new GeneralSecurityException("ciphertext is too short");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, combined, 0, IV_BYTES));
        byte[] plaintext = cipher.doFinal(combined, IV_BYTES, combined.length - IV_BYTES);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private static void rotateLines(byte[] oldKey, byte[] newKey) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            String token = line.trim();
            if (token.isEmpty()) {
                continue;
            }
            System.out.println(encrypt(decrypt(token, oldKey), newKey));
        }
    }

    private static void rotateTsv(byte[] oldKey, byte[] newKey) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab <= 0 || tab == line.length() - 1) {
                throw new IllegalArgumentException("expected id<TAB>ciphertext: " + line.substring(0, Math.min(20, line.length())));
            }
            String id = line.substring(0, tab);
            String token = line.substring(tab + 1).trim();
            System.out.println(id + "\t" + encrypt(decrypt(token, oldKey), newKey));
        }
    }
}
