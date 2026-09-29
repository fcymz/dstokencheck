import com.ruoyi.dstokencheck.config.AppConfig;
import com.ruoyi.dstokencheck.security.SecretStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Development check for the "记住我" storage path: the encrypted key must survive a save/reload
 * cycle, and the plaintext must never appear in the config file.
 *
 * <p><b>This writes to whatever {@code user.home} points at.</b> Run it against a throwaway
 * directory so it cannot disturb a real setup:
 *
 * <pre>java -Duser.home=%TEMP%\probe -cp target\dstokencheck.jar;. SecretProbe</pre>
 *
 * <p>Its {@code --clear} mode erases stored credentials from that location.
 */
public class SecretProbe {

    public static void main(String[] args) throws Exception {
        // Helper modes so a launch can be tested against a pre-seeded config.
        if (args.length >= 2 && "--set".equals(args[0])) {
            AppConfig c = new AppConfig();
            c.clearCredentials();
            c.setRememberApiKey(true);
            c.setProtectedApiKey(SecretStore.protect(args[1]));
            c.save();
            System.out.println("stored encrypted key for " + args[1]);
            return;
        }
        if (args.length >= 1 && "--clear".equals(args[0])) {
            AppConfig c = new AppConfig();
            c.clearCredentials();
            c.save();
            System.out.println("cleared");
            return;
        }

        // A stand-in, not a credential. Assembled at runtime so this file holds no literal that a
        // secret scanner (including GitHub push protection) could flag as a leaked key.
        String key = "unit-test" + "-value-not-a-credential-0123456789";

        System.out.println("scheme            : " + SecretStore.activeScheme());

        // ---- 1. crypto primitives ----
        String stored = SecretStore.protect(key);
        System.out.println("protected         : " + abbrev(stored));
        System.out.println("hides plaintext   : " + (!stored.contains(key) ? "OK" : "FAILED"));
        System.out.println("round-trip        : " + (key.equals(SecretStore.unprotect(stored)) ? "OK" : "FAILED"));
        System.out.println("tampered rejected : "
                + (SecretStore.unprotect(stored.substring(0, stored.length() - 4) + "AAAA") == null ? "OK" : "FAILED"));
        System.out.println("empty protected   : " + ("".equals(SecretStore.protect("")) ? "OK" : "FAILED"));
        System.out.println("null unprotect    : " + (SecretStore.unprotect(null) == null ? "OK" : "FAILED"));
        System.out.println("garbage unprotect : " + (SecretStore.unprotect("not-a-secret") == null ? "OK" : "FAILED"));

        // ---- 2. config persistence, as the dialog would do it ----
        AppConfig config = new AppConfig();
        config.clearCredentials();
        config.setRememberApiKey(true);
        config.setProtectedApiKey(SecretStore.protect(key));
        config.save();

        Path file = config.getFile().toPath();
        String onDisk = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        System.out.println("config path       : " + file);
        System.out.println("plaintext on disk : " + (onDisk.contains(key) ? "FAILED (found!)" : "OK (absent)"));

        // ---- 3. a fresh instance must recover the key, as a new launch would ----
        AppConfig reloaded = new AppConfig();
        System.out.println("hasStoredApiKey   : " + (reloaded.hasStoredApiKey() ? "OK" : "FAILED"));
        String recovered = SecretStore.unprotect(reloaded.getProtectedApiKey());
        System.out.println("recovered key     : " + (key.equals(recovered) ? "OK" : "FAILED -> " + recovered));

        // ---- 4. 退出登录 must actually forget it ----
        reloaded.clearCredentials();
        reloaded.save();
        AppConfig afterLogout = new AppConfig();
        System.out.println("logout clears     : " + (!afterLogout.hasStoredApiKey()
                && afterLogout.getProtectedApiKey().isEmpty() ? "OK" : "FAILED"));

        // ---- 5. remember=false must not persist ----
        AppConfig noRemember = new AppConfig();
        noRemember.clearCredentials();
        noRemember.setRememberApiKey(false);
        noRemember.setProtectedApiKey("");
        noRemember.save();
        System.out.println("no-remember empty : " + (!new AppConfig().hasStoredApiKey() ? "OK" : "FAILED"));
    }

    private static String abbrev(String s) {
        return s == null ? "null" : (s.length() <= 48 ? s : s.substring(0, 48) + "...");
    }
}
