package com.ruoyi.dstokencheck.config;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * A ready-made look that ships inside the jar.
 *
 * <p>A preset is a folder under {@code /presets} holding a {@code preset.properties} with the same
 * keys as a user's settings file, plus the picture it uses. It deliberately carries no credentials
 * and no window position: a preset is a look, and where a window belongs depends on whose screen it
 * is. Everything else — the picture, its crop, where the figure sits on it, the colour, the font
 * scale, the card size — travels with it, so choosing one is a single click.
 *
 * <p>{@code /presets/index.txt} lists the folders. A directory listing is not something a jar can
 * be asked for, and an index is also the documented way to add another preset.
 */
public final class Preset {

    private static final String ROOT = "/presets/";
    private static final String INDEX = ROOT + "index.txt";

    private final String id;
    private final String name;
    private final String description;
    private final Properties settings;
    /** Resource path of the bundled picture, or null when the preset only sets colours and sizes. */
    private final String imageResource;

    private Preset(String id, String name, String description, Properties settings,
                   String imageResource) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.settings = settings;
        this.imageResource = imageResource;
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

    /** Every preset bundled with this build, in the order the index lists them. */
    public static List<Preset> bundled() {
        List<Preset> presets = new ArrayList<Preset>();
        for (String id : readIndex()) {
            Preset preset = load(id.trim());
            if (preset != null) {
                presets.add(preset);
            }
        }
        return presets;
    }

    /**
     * Applies the preset over the user's settings.
     *
     * <p>The picture is copied into the config directory rather than referenced in the jar, so it
     * behaves exactly like an imported one: it survives, and a later release replacing the bundled
     * copy cannot change what is on screen.
     */
    public void applyTo(AppConfig config) throws IOException {
        if (imageResource != null) {
            InputStream in = Preset.class.getResourceAsStream(imageResource);
            if (in == null) {
                throw new IOException("\u9884\u8bbe\u56fe\u7247\u7f3a\u5931\uff1a" + imageResource);
            }
            try {
                File stored = config.importBackgroundImage(in, extensionOf(imageResource));
                config.setBackgroundImageName(stored.getName());
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
        config.applyPresetSettings(settings);
        config.save();
    }

    private static Preset load(String id) {
        if (id.isEmpty()) {
            return null;
        }
        String base = ROOT + id + "/";
        InputStream in = Preset.class.getResourceAsStream(base + "preset.properties");
        if (in == null) {
            return null;
        }
        Properties settings = new Properties();
        try {
            // Properties.load(InputStream) decodes as ISO-8859-1, which would turn the Chinese name
            // into mojibake; the preset files are UTF-8.
            Reader reader = new InputStreamReader(in, "UTF-8");
            try {
                settings.load(reader);
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            return null;
        }
        String image = null;
        for (String candidate : new String[]{"background.png", "background.jpg", "background.jpeg"}) {
            if (Preset.class.getResource(base + candidate) != null) {
                image = base + candidate;
                break;
            }
        }
        return new Preset(id,
                settings.getProperty("preset.name", id).trim(),
                settings.getProperty("preset.description", "").trim(),
                settings, image);
    }

    private static List<String> readIndex() {
        List<String> ids = new ArrayList<String>();
        InputStream in = Preset.class.getResourceAsStream(INDEX);
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
            // An unreadable index simply means no presets.
        }
        return ids;
    }

    private static String extensionOf(String resource) {
        int dot = resource.lastIndexOf('.');
        return dot < 0 ? "png" : resource.substring(dot + 1);
    }
}
