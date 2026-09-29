package com.ruoyi.dstokencheck.config;

import java.awt.Rectangle;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * Persisted settings, stored in {@code ~/.dstokencheck/config.properties}.
 *
 * <p>Only the session <em>token</em> is kept — never the password. If the token expires the user is
 * asked to sign in again.
 */
public class AppConfig {

    private static final String DIR_NAME = ".dstokencheck";
    private static final String FILE_NAME = "config.properties";

    private final File file;
    private final Properties props = new Properties();

    // defaults
    /** Lowest allowed refresh period, in seconds. */
    public static final int MIN_REFRESH_SECONDS = 5;

    private int refreshSeconds = 60;
    private boolean alwaysOnTop = true;
    private float opacity = 1f;
    /** Multiplier applied to every font in the widget; 1.0 is the designed size. */
    private float fontScale = 1f;
    private int x = -1;
    private int y = -1;
    private int width = 360;
    private int height = 180;

    /** The per-user directory holding the config file and the startup log. */
    public static File directory() {
        return new File(System.getProperty("user.home", "."), DIR_NAME);
    }

    public AppConfig() {
        File dir = directory();
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        this.file = new File(dir, FILE_NAME);
        load();
    }

    public File getFile() {
        return file;
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            props.load(in);
        } catch (IOException ignored) {
            return;
        } finally {
            closeQuietly(in);
        }
        refreshSeconds = getInt("refreshSeconds", refreshSeconds);
        alwaysOnTop = getBool("alwaysOnTop", alwaysOnTop);
        opacity = getFloat("opacity", opacity);
        fontScale = getFloat("fontScale", fontScale);
        x = getInt("window.x", x);
        y = getInt("window.y", y);
        width = getInt("window.width", width);
        height = getInt("window.height", height);
    }

    public void save() {
        props.setProperty("refreshSeconds", String.valueOf(refreshSeconds));
        props.setProperty("alwaysOnTop", String.valueOf(alwaysOnTop));
        props.setProperty("opacity", String.valueOf(opacity));
        props.setProperty("fontScale", String.valueOf(fontScale));
        props.setProperty("window.x", String.valueOf(x));
        props.setProperty("window.y", String.valueOf(y));
        props.setProperty("window.width", String.valueOf(width));
        props.setProperty("window.height", String.valueOf(height));

        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            props.store(out, "DeepSeek Balance Board settings (contains a session token, keep private)");
        } catch (IOException ignored) {
            // Settings are a convenience; failing to persist must not break the app.
        } finally {
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    /**
     * The stored API key, already encrypted by
     * {@link com.ruoyi.dstokencheck.security.SecretStore}. Never plaintext on disk.
     */
    public String getProtectedApiKey() {
        return props.getProperty("apiKeyProtected", "");
    }

    public void setProtectedApiKey(String protectedKey) {
        if (protectedKey == null || protectedKey.isEmpty()) {
            props.remove("apiKeyProtected");
        } else {
            props.setProperty("apiKeyProtected", protectedKey);
        }
    }

    /** Whether the user asked for the key to be kept between runs. */
    public boolean isRememberApiKey() {
        return getBool("rememberApiKey", false);
    }

    public void setRememberApiKey(boolean remember) {
        props.setProperty("rememberApiKey", String.valueOf(remember));
    }

    /** True when a usable key is on file and the user opted to remember it. */
    public boolean hasStoredApiKey() {
        return isRememberApiKey() && !getProtectedApiKey().isEmpty();
    }

    /**
     * Forgets every credential, including plaintext keys written by earlier versions that offered
     * account/password sign-in.
     */
    public void clearCredentials() {
        props.remove("apiKeyProtected");
        props.remove("rememberApiKey");
        // Legacy keys from older builds.
        props.remove("apiKey");
        props.remove("token");
        props.remove("account");
        props.remove("deviceId");
        // Logon startup lives in the registry, not here; drop any stale copy.
        props.remove("autoStart");
    }

    public int getRefreshSeconds() {
        return refreshSeconds;
    }

    public void setRefreshSeconds(int refreshSeconds) {
        this.refreshSeconds = Math.max(MIN_REFRESH_SECONDS, refreshSeconds);
    }

    public float getFontScale() {
        return fontScale;
    }

    /** Clamps to a range where the layout still works; 1.0 is the designed size. */
    public void setFontScale(float fontScale) {
        this.fontScale = Math.max(0.7f, Math.min(2.5f, fontScale));
    }

    public boolean isAlwaysOnTop() {
        return alwaysOnTop;
    }

    public void setAlwaysOnTop(boolean alwaysOnTop) {
        this.alwaysOnTop = alwaysOnTop;
    }

    public float getOpacity() {
        return opacity;
    }

    public void setOpacity(float opacity) {
        this.opacity = Math.max(0.3f, Math.min(1f, opacity));
    }

    public Rectangle getBounds() {
        return new Rectangle(x, y, width, height);
    }

    public boolean hasSavedPosition() {
        return x >= 0 && y >= 0;
    }

    public void setBounds(Rectangle r) {
        if (r == null) {
            return;
        }
        this.x = r.x;
        this.y = r.y;
        this.width = r.width;
        this.height = r.height;
    }

    private int getInt(String key, int def) {
        try {
            return Integer.parseInt(props.getProperty(key, String.valueOf(def)).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private float getFloat(String key, float def) {
        try {
            return Float.parseFloat(props.getProperty(key, String.valueOf(def)).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private boolean getBool(String key, boolean def) {
        return Boolean.parseBoolean(props.getProperty(key, String.valueOf(def)).trim());
    }
}
