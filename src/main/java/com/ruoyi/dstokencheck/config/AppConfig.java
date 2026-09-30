package com.ruoyi.dstokencheck.config;

import com.ruoyi.dstokencheck.model.CropShape;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Locale;
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

    /** Prefix of the copies kept beside the config file, e.g. {@code background-1730000000000.png}. */
    private static final String BACKGROUND_PREFIX = "background-";

    /** File name (inside {@link #directory()}) of the custom background, empty when none is set. */
    private String backgroundImageName = "";

    /** Where the balance figure goes on that image, normalised to the image, or null. */
    private Rectangle2D.Float balanceRegion;

    /**
     * The part of the image the widget shows, normalised to the image, or null for all of it.
     *
     * <p>Cropping is what lets the card's own edges land on the picture's edges: the visible area
     * becomes the whole window, so nothing outside the crop can leak in and nothing inside it is
     * lost to a bad aspect ratio.
     */
    private Rectangle2D.Float imageCrop;

    /**
     * The outline of the crop when it is not a plain rectangle, or null.
     *
     * <p>{@link #imageCrop} stays the bounding box — it is what the window's proportions come from —
     * and this holds the actual silhouette inside it.
     */
    private CropShape cropShape;

    /**
     * Colour of the balance figure in custom-background mode. Matches {@code Theme.TEXT} by default;
     * the value is repeated here rather than imported so the settings layer stays free of UI types.
     */
    private Color balanceTextColor = new Color(0xE9EEF8);

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

        backgroundImageName = props.getProperty("backgroundImage", "").trim();
        balanceRegion = parseRegion(props.getProperty("balanceRegion"));
        imageCrop = parseRegion(props.getProperty("imageCrop"));
        cropShape = CropShape.parse(props.getProperty("imageCropShape"));
        if (cropShape != null) {
            // Trust the outline over the box: the two are written together, and the outline is the
            // one the user actually drew.
            imageCrop = cropShape.bounds();
        }
        balanceTextColor = parseColor(props.getProperty("balanceTextColor"), balanceTextColor);
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

        if (backgroundImageName.isEmpty()) {
            props.remove("backgroundImage");
        } else {
            props.setProperty("backgroundImage", backgroundImageName);
        }
        if (balanceRegion == null) {
            props.remove("balanceRegion");
        } else {
            props.setProperty("balanceRegion", formatRegion(balanceRegion));
        }
        if (imageCrop == null) {
            props.remove("imageCrop");
        } else {
            props.setProperty("imageCrop", formatRegion(imageCrop));
        }
        if (cropShape == null || cropShape.isRectangle()) {
            props.remove("imageCropShape");
        } else {
            props.setProperty("imageCropShape", cropShape.serialize());
        }
        props.setProperty("balanceTextColor", formatColor(balanceTextColor));

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

    // ------------------------------------------------------------- background

    /**
     * The stored background image, or {@code null} when none is configured.
     *
     * <p>The user's own file is never referenced directly: importing copies it into the config
     * directory, so moving or deleting the original afterwards cannot break the widget.
     */
    public File getBackgroundImageFile() {
        if (backgroundImageName.isEmpty() || !isSafeImageName(backgroundImageName)) {
            return null;
        }
        File f = new File(directory(), backgroundImageName);
        return f.isFile() ? f : null;
    }

    public String getBackgroundImageName() {
        return backgroundImageName;
    }

    /** True when a usable background image is configured. */
    public boolean hasBackgroundImage() {
        return getBackgroundImageFile() != null;
    }

    /**
     * Copies {@code source} into the config directory and makes it the configured background.
     *
     * <p>The file is decoded first: a rename of a non-image (or a truncated download) would
     * otherwise only fail later, inside the paint code, where it is much harder to explain.
     *
     * <p>Nothing is written to disk here — the caller persists the setting — so a cancelled edit can
     * be undone with {@link #restoreBackgroundImageName(String)} plus
     * {@link #pruneBackgroundImages(File)}, which deletes the copy again.
     *
     * @return the newly written file, for {@link #pruneBackgroundImages(File)}
     */
    public File storeBackgroundImage(File source) throws IOException {
        if (source == null || !source.isFile()) {
            throw new IOException("\u6587\u4ef6\u4e0d\u5b58\u5728");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(source.toPath());
        } catch (IOException e) {
            throw new IOException("\u65e0\u6cd5\u8bfb\u53d6\u8be5\u6587\u4ef6");
        }
        return storeBackgroundBytes(bytes, extensionOf(source.getName()));
    }

    /**
     * Takes a picture from somewhere other than the file system — a preset bundled in the jar — and
     * stores it exactly like an imported one.
     */
    public File importBackgroundImage(InputStream source, String extension) throws IOException {
        if (source == null) {
            throw new IOException("\u9884\u8bbe\u56fe\u7247\u7f3a\u5931");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = source.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return storeBackgroundBytes(buffer.toByteArray(), extensionOf("background." + extension));
    }

    private File storeBackgroundBytes(byte[] bytes, String extension) throws IOException {
        BufferedImage probe;
        try {
            probe = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new IOException("\u65e0\u6cd5\u89e3\u6790\u8be5\u56fe\u7247");
        }
        if (probe == null) {
            throw new IOException("\u4e0d\u662f\u53ef\u8bc6\u522b\u7684\u56fe\u7247\u683c\u5f0f");
        }

        File dir = directory();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("\u65e0\u6cd5\u521b\u5efa\u914d\u7f6e\u76ee\u5f55");
        }
        // A fresh name per import keeps the import non-destructive: the previous copy stays intact
        // until the edit is confirmed, so cancelling can never lose the old background.
        File target = new File(dir, BACKGROUND_PREFIX + System.currentTimeMillis() + "." + extension);
        try {
            Files.write(target.toPath(), bytes);
        } catch (IOException e) {
            // Do not leave a half-written copy behind for the next launch to trip over.
            deleteIfStoredImage(target);
            throw new IOException("\u590d\u5236\u56fe\u7247\u5931\u8d25\uff1a" + e.getMessage());
        }
        backgroundImageName = target.getName();
        return target;
    }

    /** Removes every stored copy except {@code keep}; called once an import is confirmed. */
    public void pruneBackgroundImages(File keep) {
        File[] files = directory().listFiles();
        if (files == null) {
            return;
        }
        String keepName = keep == null ? null : keep.getName();
        for (File f : files) {
            if (f.getName().startsWith(BACKGROUND_PREFIX) && !f.getName().equals(keepName)) {
                deleteIfStoredImage(f);
            }
        }
    }

    /** Drops the custom background entirely: the setting and every stored copy of the image. */
    public void removeBackgroundImage() {
        backgroundImageName = "";
        balanceRegion = null;
        imageCrop = null;
        cropShape = null;
        pruneBackgroundImages(null);
        save();
    }

    /** Restores the in-memory setting after a cancelled edit; does not touch the file system. */
    public void setBackgroundImageName(String name) {
        this.backgroundImageName = name == null ? "" : name;
    }

    /**
     * Overwrites the look settings with a preset's values.
     *
     * <p>Only the keys a preset actually carries are touched, so a preset that says nothing about,
     * say, opacity leaves the user's own choice alone. Credentials are never read from a preset —
     * they are not in {@code preset.properties} to begin with, and this method does not look for
     * them. The window's position is kept for the same reason: a preset names a size, not a spot on
     * somebody else's screen.
     */
    public void applyPresetSettings(Properties preset) {
        Rectangle2D.Float crop = parseRegion(preset.getProperty("imageCrop"));
        CropShape shape = CropShape.parse(preset.getProperty("imageCropShape"));
        if (shape != null) {
            setCropShape(shape);
        } else if (crop != null) {
            setImageCrop(crop);
        }
        Rectangle2D.Float region = parseRegion(preset.getProperty("balanceRegion"));
        if (region != null) {
            setBalanceRegion(region);
        }
        Color color = parseColor(preset.getProperty("balanceTextColor"), null);
        if (color != null) {
            setBalanceTextColor(color);
        }
        setFontScale(parseFloat(preset.getProperty("fontScale"), fontScale));
        setOpacity(parseFloat(preset.getProperty("opacity"), opacity));
        String top = preset.getProperty("alwaysOnTop");
        if (top != null) {
            setAlwaysOnTop(Boolean.parseBoolean(top.trim()));
        }
        setRefreshSeconds(parseInt(preset.getProperty("refreshSeconds"), refreshSeconds));
        setBounds(new Rectangle(x, y,
                parseInt(preset.getProperty("window.width"), width),
                parseInt(preset.getProperty("window.height"), height)));
    }

    private static float parseFloat(String raw, float fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Where the balance figure is drawn on the image, normalised to 0..1, or {@code null} when the
     * user has not framed an area yet.
     */
    public Rectangle2D.Float getBalanceRegion() {
        if (balanceRegion == null) {
            return null;
        }
        return new Rectangle2D.Float(balanceRegion.x, balanceRegion.y,
                balanceRegion.width, balanceRegion.height);
    }

    /** Stores a normalised region, clamped so it always stays inside the image. */
    public void setBalanceRegion(Rectangle2D.Float region) {
        if (region == null) {
            balanceRegion = null;
            return;
        }
        float w = clamp(region.width, 0.01f, 1f);
        float h = clamp(region.height, 0.01f, 1f);
        float x = clamp(region.x, 0f, 1f - w);
        float y = clamp(region.y, 0f, 1f - h);
        balanceRegion = new Rectangle2D.Float(x, y, w, h);
    }

    /** A sensible starting box: a wide band across the middle of the image. */
    public static Rectangle2D.Float defaultBalanceRegion() {
        return new Rectangle2D.Float(0.08f, 0.36f, 0.84f, 0.28f);
    }

    /**
     * The part of the image the widget shows, or null when the whole picture is used.
     *
     * <p>Stored only when it really is a crop: a full-frame "crop" is the same thing as no crop,
     * and leaving it out keeps a settings file that a human can still read.
     */
    public Rectangle2D.Float getImageCrop() {
        if (imageCrop == null) {
            return null;
        }
        return new Rectangle2D.Float(imageCrop.x, imageCrop.y, imageCrop.width, imageCrop.height);
    }

    /** The crop the widget should draw, never null: a full-frame crop when nothing is set. */
    public Rectangle2D.Float getEffectiveCrop() {
        Rectangle2D.Float crop = getImageCrop();
        return crop == null ? new Rectangle2D.Float(0f, 0f, 1f, 1f) : crop;
    }

    /** True when part of the picture has been cropped away. */
    public boolean hasImageCrop() {
        return imageCrop != null;
    }

    /** True when the crop is not a plain rectangle, so the window has to be shaped to it. */
    public boolean hasIrregularCrop() {
        return cropShape != null && !cropShape.isRectangle();
    }

    /**
     * The outline the widget should show, never null: the crop's silhouette, or the bounding box as
     * a rectangle when the crop is a plain box (or nothing is cropped at all).
     */
    public CropShape getEffectiveCropShape() {
        if (cropShape != null) {
            return cropShape;
        }
        return CropShape.rectangle(getEffectiveCrop());
    }

    /**
     * Stores an outline. The bounding box is derived from it, since that is what decides the
     * window's proportions and where the picture sits; a rectangular outline is stored as a plain
     * crop and the outline itself is dropped.
     */
    public void setCropShape(CropShape shape) {
        if (shape == null) {
            cropShape = null;
            setImageCrop(null);
            return;
        }
        Rectangle2D.Float bounds = shape.bounds();
        if (bounds.width < MIN_CROP || bounds.height < MIN_CROP) {
            return;
        }
        setImageCrop(bounds);
        cropShape = shape.isRectangle() ? null : shape;
    }

    /** Stores a normalised crop; a full-frame one is dropped, and small ones are clamped away. */
    public void setImageCrop(Rectangle2D.Float crop) {
        if (crop == null || isFullFrame(crop)) {
            imageCrop = null;
            // A silhouette of the whole picture is the same as no crop at all.
            cropShape = null;
            return;
        }
        float w = clamp(crop.width, MIN_CROP, 1f);
        float h = clamp(crop.height, MIN_CROP, 1f);
        float x = clamp(crop.x, 0f, 1f - w);
        float y = clamp(crop.y, 0f, 1f - h);
        imageCrop = new Rectangle2D.Float(x, y, w, h);
    }

    /** Smallest crop we accept, as a fraction of the image; below this the card is a sliver. */
    public static final float MIN_CROP = 0.05f;

    private static boolean isFullFrame(Rectangle2D.Float crop) {
        float eps = 0.0005f;
        return Math.abs(crop.x) < eps && Math.abs(crop.y) < eps
                && Math.abs(crop.width - 1f) < eps && Math.abs(crop.height - 1f) < eps;
    }

    public Color getBalanceTextColor() {
        return balanceTextColor;
    }

    public void setBalanceTextColor(Color color) {
        if (color != null) {
            // Opaque only: the renderer draws its own shadow, and a translucent colour on an
            // arbitrary photo is the fastest way to an unreadable number.
            balanceTextColor = new Color(color.getRed(), color.getGreen(), color.getBlue());
        }
    }

    // ---------------------------------------------------------- value helpers

    /** Extensions we are willing to store; anything else is kept as PNG. */
    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
            for (String known : new String[]{"png", "jpg", "jpeg", "gif", "bmp"}) {
                if (known.equals(ext)) {
                    return ext;
                }
            }
        }
        return "png";
    }

    /** A stored name must be a plain file name inside our own directory. */
    private static boolean isSafeImageName(String name) {
        return !name.isEmpty()
                && name.indexOf('/') < 0
                && name.indexOf('\\') < 0
                && !name.contains("..");
    }

    /** Deletes a file only when it really is one of our stored copies. */
    private static void deleteIfStoredImage(File file) {
        if (file == null || !file.getName().startsWith(BACKGROUND_PREFIX)) {
            return;
        }
        if (!directory().equals(file.getParentFile())) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static String formatRegion(Rectangle2D.Float r) {
        return round4(r.x) + "," + round4(r.y) + "," + round4(r.width) + "," + round4(r.height);
    }

    private static String round4(float v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static Rectangle2D.Float parseRegion(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.trim().split(",");
        if (parts.length != 4) {
            return null;
        }
        try {
            float x = Float.parseFloat(parts[0].trim());
            float y = Float.parseFloat(parts[1].trim());
            float w = Float.parseFloat(parts[2].trim());
            float h = Float.parseFloat(parts[3].trim());
            if (w <= 0.01f || h <= 0.01f || x < 0f || y < 0f || x + w > 1.001f || y + h > 1.001f) {
                return null;
            }
            return new Rectangle2D.Float(x, y, w, h);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatColor(Color c) {
        return String.format(Locale.ROOT, "%06x", c.getRGB() & 0xFFFFFF);
    }

    private static Color parseColor(String raw, Color fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return new Color(Integer.parseInt(raw.trim(), 16) & 0xFFFFFF);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
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
