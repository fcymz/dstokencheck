package com.ruoyi.dstokencheck.ui;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;

/**
 * Places a background image on a card, and the framed balance region along with it.
 *
 * <p>The widget, the region editor and the editor's live preview all have to agree on where a
 * normalised region lands, so the transform lives here once. Three copies of it would drift apart
 * and the number would sit somewhere other than the box the user drew.
 */
public final class BackgroundLayout {

    private BackgroundLayout() {
    }

    /**
     * Where an image of {@code imageW x imageH} is drawn to cover a {@code boxW x boxH} card.
     *
     * <p>Cover, not stretch: the image keeps its proportions and is centred, overflowing on the
     * longer axis. Applying a background also resizes the card to the image, so the two normally
     * coincide and nothing is cropped at all.
     */
    public static Rectangle coverRect(int imageW, int imageH, int boxW, int boxH) {
        return centred(imageW, imageH, coverScale(imageW, imageH, boxW, boxH), boxW, boxH);
    }

    /** The scale at which an image covers a box: the larger of the two ratios. */
    public static double coverScale(int imageW, int imageH, int boxW, int boxH) {
        if (imageW <= 0 || imageH <= 0) {
            return 1.0;
        }
        return Math.max(Math.max(1, boxW) / (double) imageW, Math.max(1, boxH) / (double) imageH);
    }

    /** The scale at which an image fits inside a box: the smaller of the two ratios. */
    public static double containScale(int imageW, int imageH, int boxW, int boxH) {
        if (imageW <= 0 || imageH <= 0) {
            return 1.0;
        }
        return Math.min(Math.max(1, boxW) / (double) imageW, Math.max(1, boxH) / (double) imageH);
    }

    /** An image of {@code imageW x imageH} at {@code scale}, centred in a {@code boxW x boxH} box. */
    public static Rectangle centred(int imageW, int imageH, double scale, int boxW, int boxH) {
        int w = Math.max(1, boxW);
        int h = Math.max(1, boxH);
        if (imageW <= 0 || imageH <= 0) {
            return new Rectangle(0, 0, w, h);
        }
        int dw = (int) Math.round(imageW * scale);
        int dh = (int) Math.round(imageH * scale);
        return new Rectangle((w - dw) / 2, (h - dh) / 2, dw, dh);
    }

    /**
     * The framed region in the coordinates of the card, trimmed to {@code boxW x boxH}.
     *
     * <p>Aspect ratios far from the image's can push part of the box off the card, at which point
     * the visible remainder is what the number has to fit into. Returns {@code null} when nothing
     * usable is left.
     */
    public static Rectangle2D.Float regionOn(Rectangle2D.Float region, Rectangle cover,
                                             int boxW, int boxH) {
        if (region == null) {
            return null;
        }
        float x = (float) (cover.x + region.x * cover.width);
        float y = (float) (cover.y + region.y * cover.height);
        float x2 = Math.min((float) (x + region.width * cover.width), boxW);
        float y2 = Math.min((float) (y + region.height * cover.height), boxH);
        x = Math.max(x, 0f);
        y = Math.max(y, 0f);
        if (x2 - x < 1f || y2 - y < 1f) {
            return null;
        }
        return new Rectangle2D.Float(x, y, x2 - x, y2 - y);
    }
}
