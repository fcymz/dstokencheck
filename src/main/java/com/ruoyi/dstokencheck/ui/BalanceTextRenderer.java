package com.ruoyi.dstokencheck.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;

/**
 * Draws the balance figure to fill an arbitrary box.
 *
 * <p>Used for the custom-background mode, where the user frames a region on their own image and
 * the number has to fit whatever they chose. The font is scaled to the box rather than to the
 * window, so the same code drives both the widget and the region editor's live preview.
 *
 * <p>A soft drop shadow is drawn underneath: a user-supplied image can be any colour, and without
 * it light text on a light photo (or dark on dark) becomes unreadable.
 */
public final class BalanceTextRenderer {

    private BalanceTextRenderer() {
    }

    /** Fraction of the box height the glyphs aim to occupy, leaving a little breathing room. */
    private static final double HEIGHT_FILL = 0.78;
    /** Fraction of the box width the text may occupy before it is shrunk to fit. */
    private static final double WIDTH_FILL = 0.94;
    /** Share of the box height handed to the currency / token line when there is one. */
    private static final double SUBTITLE_SHARE = 0.28;
    private static final float MIN_SIZE = 6f;

    /**
     * Draws the balance figure plus its currency line inside {@code box}.
     *
     * <p>With a custom background there is no room for the widget's normal label column — the image
     * owns the whole window — so the amount and the information that used to sit underneath it
     * ("CNY ≈ 12.4M tokens", bonus, the other wallets) share the framed box instead. The amount gets
     * the top part and the smaller line the bottom; both are fitted, so a narrow frame shrinks the
     * text rather than clipping it.
     */
    public static Rectangle2D drawRegion(Graphics2D g2, String amount, String subtitle,
                                         Rectangle2D box, Color color) {
        return drawRegion(g2, amount, subtitle, box, color, null, false);
    }

    /**
     * Same layout, with the font and the caption's colour left to the caller.
     *
     * <p>The digits are set in a monospace face, which has no CJK glyphs: the tariff line
     * ("现在是空闲时段") has to be drawn in the UI font or it arrives as a row of empty boxes.
     *
     * @param subtitleColor colour for the small line, or null to derive it from {@code color}
     * @param uiFont        true to set the lines in the UI font instead of the monospace one
     */
    public static Rectangle2D drawRegion(Graphics2D g2, String amount, String subtitle,
                                  Rectangle2D box, Color color, Color subtitleColor,
                                  boolean uiFont) {
        if (amount == null || amount.isEmpty() || color == null) {
            return null;
        }
        if (box.getWidth() < 2 || box.getHeight() < 2) {
            return null;
        }
        if (subtitle == null || subtitle.trim().isEmpty()) {
            return drawFitted(g2, amount, box, color, uiFont);
        }

        double subtitleHeight = Math.max(9, box.getHeight() * SUBTITLE_SHARE);
        // Never let the caption eat the number: on a short box it gives way instead.
        subtitleHeight = Math.min(subtitleHeight, box.getHeight() * 0.45);
        double gap = Math.max(1, box.getHeight() * 0.04);
        double amountHeight = box.getHeight() - subtitleHeight - gap;
        if (amountHeight < 8) {
            return drawFitted(g2, amount, box, color, uiFont);
        }

        Rectangle2D.Double amountBox = new Rectangle2D.Double(
                box.getX(), box.getY(), box.getWidth(), amountHeight);
        Rectangle2D.Double subtitleBox = new Rectangle2D.Double(
                box.getX(), box.getY() + amountHeight + gap, box.getWidth(), subtitleHeight);

        Rectangle2D drawn = drawFitted(g2, amount, amountBox, color, uiFont);
        // A little dimmer than the number, so the figure stays the thing you read first. The caller
        // may name the caption's colour instead — the hover line is the user's own colour.
        Rectangle2D caption = drawFitted(g2, subtitle, subtitleBox,
                subtitleColor == null ? Theme.alpha(color, 205) : subtitleColor, uiFont);
        if (drawn == null) {
            return caption;
        }
        return caption == null ? drawn : drawn.createUnion(caption);
    }

    /**
     * Draws {@code text} centred inside {@code box}.
     *
     * @param g2    graphics to draw into; its clip is respected but not modified
     * @param text  the string to draw
     * @param box   target rectangle in the same coordinate space as {@code g2}
     * @param color foreground colour
     */
    public static Rectangle2D drawFitted(Graphics2D g2, String text, Rectangle2D box, Color color) {
        return drawFitted(g2, text, box, color, false);
    }

    /** As above, optionally in the UI font; see {@link #drawRegion(Graphics2D, String, String, Rectangle2D, Color, boolean)}. */
    public static Rectangle2D drawFitted(Graphics2D g2, String text, Rectangle2D box, Color color,
                                  boolean uiFont) {
        if (text == null || text.isEmpty() || color == null) {
            return null;
        }
        if (box.getWidth() < 2 || box.getHeight() < 2) {
            return null;
        }

        Graphics2D g = (Graphics2D) g2.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // Start from the tallest font the box can hold, then shrink for width if needed.
            float size = (float) Math.max(MIN_SIZE, box.getHeight() * HEIGHT_FILL);
            Font font = uiFont ? Theme.ui(Font.BOLD, size) : Theme.mono(Font.BOLD, size);
            FontMetrics fm = g.getFontMetrics(font);
            int textWidth = fm.stringWidth(text);

            double maxWidth = box.getWidth() * WIDTH_FILL;
            if (textWidth > maxWidth && textWidth > 0) {
                size = (float) Math.max(MIN_SIZE, size * maxWidth / textWidth);
                font = uiFont ? Theme.ui(Font.BOLD, size) : Theme.mono(Font.BOLD, size);
                fm = g.getFontMetrics(font);
                textWidth = fm.stringWidth(text);
            }

            int x = (int) Math.round(box.getCenterX() - textWidth / 2.0);
            int y = (int) Math.round(box.getCenterY() + (fm.getAscent() - fm.getDescent()) / 2.0);

            // Shadow offset scales with the font so it stays visible at any size.
            float offset = Math.max(1f, size * 0.045f);
            g.setColor(new Color(0, 0, 0, 170));
            g.setFont(font);
            g.drawString(text, Math.round(x + offset), Math.round(y + offset));

            g.setColor(color);
            g.drawString(text, x, y);
            // What was actually painted, so a caller can put something else beside it.
            return new Rectangle2D.Double(x, y - fm.getAscent(), textWidth, fm.getHeight());
        } finally {
            g.dispose();
        }
    }
}
