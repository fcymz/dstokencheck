package com.ruoyi.dstokencheck.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.util.HashSet;
import java.util.Set;

/** Colors, fonts and small painting helpers shared by the widget and the login window. */
public final class Theme {

    private Theme() {
    }

    public static final Color BG_TOP = new Color(24, 30, 44);
    public static final Color BG_BOTTOM = new Color(15, 19, 30);
    public static final Color BORDER = new Color(58, 70, 94);
    public static final Color TEXT = new Color(233, 238, 248);
    public static final Color TEXT_DIM = new Color(132, 145, 170);
    public static final Color ACCENT = new Color(84, 160, 255);
    public static final Color ACCENT_SOFT = new Color(96, 200, 255);
    public static final Color GOOD = new Color(88, 214, 141);
    public static final Color WARN = new Color(245, 178, 74);
    public static final Color DANGER = new Color(240, 104, 104);
    public static final Color FIELD_BG = new Color(28, 35, 50);
    public static final Color BUTTON_BG = new Color(56, 116, 214);
    /** Raised blocks inside a card (toolbars, sidebar sections). */
    public static final Color SURFACE = new Color(25, 32, 45);
    /** The same block under the pointer. */
    public static final Color SURFACE_HOVER = new Color(35, 44, 61);
    /** Deep backdrop behind a picture, so a light image has a frame to sit in. */
    public static final Color CANVAS = new Color(11, 14, 21);

    private static final String UI_FAMILY = pickFamily(
            "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC",
            "Source Han Sans SC", "SimHei", "Dialog");

    private static final String MONO_FAMILY = pickFamily(
            "Consolas", "JetBrains Mono", "Cascadia Mono", "DejaVu Sans Mono", "Monospaced");

    /**
     * Picks the first installed family so Chinese labels render instead of showing tofu boxes.
     */
    private static String pickFamily(String... candidates) {
        Set<String> available = new HashSet<String>();
        try {
            for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
                available.add(f);
            }
        } catch (Throwable t) {
            return candidates[candidates.length - 1];
        }
        for (String c : candidates) {
            if (available.contains(c)) {
                return c;
            }
        }
        return candidates[candidates.length - 1];
    }

    public static Font ui(int style, float size) {
        return new Font(UI_FAMILY, style, Math.round(size)).deriveFont(size);
    }

    public static Font mono(int style, float size) {
        return new Font(MONO_FAMILY, style, Math.round(size)).deriveFont(size);
    }

    /** Blends two colors; {@code t} is 0..1. */
    public static Color mix(Color a, Color b, float t) {
        float u = Math.max(0f, Math.min(1f, t));
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * u),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * u),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * u),
                Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * u));
    }

    /** Same color at a different alpha. */
    public static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    /**
     * Fades the top and bottom edges of a user image towards black.
     *
     * <p>The widget's own title bar and footer sit on top of whatever picture the user chose, and a
     * bright photo would otherwise leave them unreadable. Gradients rather than flat fills, so the
     * picture is not boxed in by two obvious bars. Shared by the widget and its miniature preview.
     */
    public static void paintEdgeScrim(Graphics2D g2, int w, int h) {
        int band = Math.max(16, Math.min(h / 3, 64));
        g2.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 125), 0, band, new Color(0, 0, 0, 0)));
        g2.fillRect(0, 0, w, band);
        g2.setPaint(new GradientPaint(0, h - band, new Color(0, 0, 0, 0), 0, h, new Color(0, 0, 0, 135)));
        g2.fillRect(0, h - band, w, band);
    }

    /**
     * The chequered mat that means "nothing is drawn here".
     *
     * <p>Used behind a picture in the editor and its preview. Without it a transparent PNG simply
     * looks like a dark picture, which is exactly the confusion this is here to prevent — the
     * desktop will show through those pixels, the editor cannot show the desktop, so it shows the
     * convention instead.
     */
    public static void paintCheckerboard(Graphics2D g2, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int cell = 10;
        java.awt.Shape old = g2.getClip();
        g2.clipRect(x, y, w, h);
        g2.setColor(new Color(0x3C, 0x42, 0x4E));
        g2.fillRect(x, y, w, h);
        g2.setColor(new Color(0x2F, 0x35, 0x40));
        for (int row = 0; row * cell < h; row++) {
            for (int col = 0; col * cell < w; col++) {
                if (((row + col) & 1) == 0) {
                    continue;
                }
                g2.fillRect(x + col * cell, y + row * cell, cell, cell);
            }
        }
        g2.setClip(old);
    }
}
