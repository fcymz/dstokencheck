package com.ruoyi.dstokencheck.config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * A saved look: picture, crop, framed box, colour, font scale, card size.
 *
 * <p>Two kinds, deliberately the same shape on disk so one loader and one apply path serve both:
 *
 * <ul>
 *   <li><b>Bundled</b> presets live inside the jar under {@code /presets}. They ship with the app
 *       and cannot be edited.</li>
 *   <li><b>User</b> presets live in {@code <config dir>/presets/<name>/} and are whatever the user
 *       saved with 保存当前配置为预设. Being plain files in the config directory, they survive
 *       upgrades and can be copied between machines.</li>
 * </ul>
 *
 * <p>A preset never carries credentials or a window position: it is a look, and where a window
 * belongs depends on whose screen it is.
 */
public final class Preset {

    private static final String RESOURCE_ROOT = "/presets/";
    private static final String RESOURCE_INDEX = RESOURCE_ROOT + "index.txt";
    private static final String FOLDER = "presets";
    private static final String SETTINGS_FILE = "preset.properties";
    private static final String IMAGE_BASE = "background";

    private final String id;
    private final String name;
    private final String description;
    private final Properties settings;
    /** Resource path of a bundled picture, or null. */
    private final String resourceImage;
    /** Folder of a user preset, or null for a bundled one. */
    private final File folder;
    /** Picture file of a user preset, or null. */
    private final File imageFile;

    private Preset(String id, String name, String description, Properties settings,
                   String resourceImage, File folder, File imageFile) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.settings = settings;
        this.resourceImage = resourceImage;
        this.folder = folder;
        this.imageFile = imageFile;
    }

    /** What to show in the menu. */
    public String getName() {
        return name;
    }

    /** One line explaining the preset, or an empty string. */
    public String getDescription() {
        return description;
    }

    public String getId() {
        return id;
    }

    /** True for a preset the user saved, which is the only kind that can be deleted. */
    public boolean isUserPreset() {
        return folder != null;
    }

    public File getFolder() {
        return folder;
    }

    // -------------------------------------------------------------- locations

    /** Where user presets live: a folder inside the config directory, created on demand. */
    public static File directory() {
        return new File(AppConfig.directory(), FOLDER);
    }

    /** True when a preset of this name is already saved, so the UI can warn before overwriting. */
    public static boolean exists(String name) {
        return name != null && !name.trim().isEmpty()
                && new File(directory(), folderName(name)).isDirectory();
    }

    public static List<Preset> bundled() {
        List<Preset> presets = new ArrayList<Preset>();
        for (String id : resourceIndex()) {
            Preset preset = loadBundled(id.trim());
            if (preset != null) {
                presets.add(preset);
            }
        }
        return presets;
    }

    /** Presets the user saved, sorted by name so the menu does not shuffle between openings. */
    public static List<Preset> userPresets() {
        List<Preset> presets = new ArrayList<Preset>();
        File[] folders = directory().listFiles();
        if (folders != null) {
            Arrays.sort(folders, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File candidate : folders) {
                Preset preset = loadUser(candidate);
                if (preset != null) {
                    presets.add(preset);
                }
            }
        }
        return presets;
    }

    // ------------------------------------------------------------------ save

    /**
     * Writes the current settings out as a preset of this name, picture and all.
     *
     * <p>Saving over an existing name replaces it completely — including a picture the old version
     * had, so a preset can never end up showing a picture it did not save.
     */
    public static File save(String name, AppConfig config) throws IOException {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            throw new IOException("\u540d\u5b57\u4e0d\u80fd\u4e3a\u7a7a");
        }
        File folder = new File(directory(), folderName(clean));
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("\u65e0\u6cd5\u521b\u5efa\u9884\u8bbe\u76ee\u5f55");
        }
        // Drop the previous picture first: an overwrite must not leave the old one behind.
        for (File stale : imagesIn(folder)) {
            //noinspection ResultOfMethodCallIgnored
            stale.delete();
        }

        Properties settings = config.toPresetProperties(clean);
        File target = new File(folder, SETTINGS_FILE);
        Writer writer = new OutputStreamWriter(new FileOutputStream(target), "UTF-8");
        try {
            settings.store(writer, "DeepSeek Balance Board preset (no credentials, no window position)");
        } finally {
            writer.close();
        }

        File picture = config.getBackgroundImageFile();
        if (picture != null) {
            File copy = new File(folder, IMAGE_BASE + "." + extensionOf(picture.getName()));
            copyFile(picture, copy);
        }
        return folder;
    }

    /** Removes a user preset and everything in it. Bundled presets cannot be deleted. */
    public static boolean delete(Preset preset) {
        if (preset == null || preset.folder == null || !preset.folder.isDirectory()) {
            return false;
        }
        File root = directory();
        if (!root.equals(preset.folder.getParentFile())) {
            // Refuse to walk outside our own folder, whatever the caller passed in.
            return false;
        }
        File[] files = preset.folder.listFiles();
        if (files != null) {
            for (File file : files) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
        return preset.folder.delete();
    }

    // ----------------------------------------------------------------- apply

    /**
     * Applies the preset over the user's settings.
     *
     * <p>The picture is copied into the config directory rather than referenced where it sits, so a
     * preset behaves exactly like a hand-imported picture: the user can move or delete the preset
     * afterwards without changing what is on screen. A preset with no picture clears the current
     * one — it describes a whole look, not a patch.
     */
    public void applyTo(AppConfig config) throws IOException {
        InputStream picture = openImage();
        if (picture != null) {
            try {
                config.setBackgroundImageName(
                        config.importBackgroundImage(picture, imageExtension()).getName());
            } finally {
                closeQuietly(picture);
            }
        } else {
            config.setBackgroundImageName("");
        }
        config.applyPresetSettings(settings);
        if (picture == null) {
            config.pruneBackgroundImages(null);
        }
        config.save();
    }

    private InputStream openImage() throws IOException {
        if (imageFile != null) {
            return new FileInputStream(imageFile);
        }
        return resourceImage == null ? null : Preset.class.getResourceAsStream(resourceImage);
    }

    private String imageExtension() {
        String source = imageFile != null ? imageFile.getName() : resourceImage;
        return source == null ? "png" : extensionOf(source);
    }

    // ---------------------------------------------------------------- loading

    private static Preset loadBundled(String id) {
        if (id.isEmpty()) {
            return null;
        }
        String base = RESOURCE_ROOT + id + "/";
        InputStream in = Preset.class.getResourceAsStream(base + SETTINGS_FILE);
        if (in == null) {
            return null;
        }
        Properties settings = readSettings(in);
        if (settings == null) {
            return null;
        }
        String image = null;
        for (String candidate : new String[]{
            base + IMAGE_BASE + ".png", base + IMAGE_BASE + ".jpg", base + IMAGE_BASE + ".jpeg"}) {
            if (Preset.class.getResource(candidate) != null) {
                image = candidate;
                break;
            }
        }
        return new Preset(id,
                settings.getProperty("preset.name", id).trim(),
                settings.getProperty("preset.description", "").trim(),
                settings, image, null, null);
    }

    private static Preset loadUser(File folder) {
        if (folder == null || !folder.isDirectory()) {
            return null;
        }
        File settingsFile = new File(folder, SETTINGS_FILE);
        if (!settingsFile.isFile()) {
            return null;
        }
        Properties settings;
        try {
            settings = readSettings(new FileInputStream(settingsFile));
        } catch (IOException e) {
            return null;
        }
        if (settings == null) {
            return null;
        }
        File[] images = imagesIn(folder);
        return new Preset(folder.getName(),
                settings.getProperty("preset.name", folder.getName()).trim(),
                settings.getProperty("preset.description", "").trim(),
                settings, null, folder, images.length == 0 ? null : images[0]);
    }

    private static Properties readSettings(InputStream in) {
        Properties settings = new Properties();
        try {
            // Properties.load(InputStream) decodes as ISO-8859-1, which turns a Chinese preset name
            // into mojibake; both shipped and saved presets are UTF-8.
            Reader reader = new InputStreamReader(in, "UTF-8");
            try {
                settings.load(reader);
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            return null;
        }
        return settings;
    }

    private static List<String> resourceIndex() {
        List<String> ids = new ArrayList<String>();
        InputStream in = Preset.class.getResourceAsStream(RESOURCE_INDEX);
        if (in == null) {
            return ids;
        }
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        ids.add(trimmed);
                    }
                }
            } finally {
                reader.close();
            }
        } catch (IOException ignored) {
            // An unreadable index simply means no bundled presets.
        }
        return ids;
    }

    // ----------------------------------------------------------------- naming

    /**
     * A folder name for a user-supplied preset name.
     *
     * <p>Only characters a file name cannot hold are replaced; Chinese is kept as it is, since
     * every supported file system here handles it and the folder is the only place the name lives
     * besides the settings file itself.
     */
    private static String folderName(String name) {
        String cleaned = name.trim().replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.length() > 48) {
            cleaned = cleaned.substring(0, 48);
        }
        return cleaned.isEmpty() ? "preset-" + System.currentTimeMillis() : cleaned;
    }

    private static File[] imagesIn(File folder) {
        File[] found = folder.listFiles();
        if (found == null) {
            return new File[0];
        }
        List<File> images = new ArrayList<File>();
        for (File file : found) {
            String lower = file.getName().toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith(IMAGE_BASE + ".") && file.isFile()) {
                images.add(file);
            }
        }
        return images.toArray(new File[0]);
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String ext = fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
            for (String known : new String[]{"png", "jpg", "jpeg", "gif", "bmp"}) {
                if (known.equals(ext)) {
                    return ext;
                }
            }
        }
        return "png";
    }

    private static void copyFile(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
        try {
            java.io.OutputStream out = new FileOutputStream(to);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            } finally {
                out.close();
            }
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }
}
