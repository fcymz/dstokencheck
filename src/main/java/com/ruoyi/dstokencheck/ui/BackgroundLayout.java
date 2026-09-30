package com.ruoyi.dstokencheck.ui;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;

/**
 * Places a background image on a card, and the framed balance region along with it.
 *
 * <p>The widget, the region editor and the editor's live preview all have to agree on where a
 * normalised region lands, so the transform lives here once. Three copies of it would drift apart
 * and the number would sit somewhere other than the box the user drew.
 *
 * <p>The same goes for cropping: the visible area of the picture becomes the whole card, so the
 * card's edges are the crop's edges.
 */
public final class BackgroundLayout {

    private BackgroundLayout() {
    }

    /** The whole picture, for callers that have no crop. */
    public static Rectangle2D.Float fullCrop() {
        return new Rectangle2D.Float(0f, 0f, 1f, 1f);
    }

    /**
     * Where an image of {@code imageW x imageH} is drawn on a {@code boxW x boxH} card that shows
     * exactly {@code crop} (normalised, or null for all of it).
     *
     * <p>The crop is scaled to cover the card and centred, so with the card at the crop's aspect
     * ratio — which is what applying a background does — the crop fills it exactly and the image's
     * edges land on the window's edges. Cover rather than stretch, so a card the user has since
     * reshaped crops the picture instead of squashing it.
     */
    public static Rectangle imageRect(Rectangle2D.Float crop, int imageW, int imageH,
                                      int boxW, int boxH) {
        int w = Math.max(1, boxW);
        int h = Math.max(1, boxH);
        if (imageW <= 0 || imageH <= 0) {
            return new Rectangle(0, 0, w, h);
        }
        if (crop == null) {
            crop = fullCrop();
        }
        double cropW = Math.max(1.0, crop.width * imageW);
        double cropH = Math.max(1.0, crop.height * imageH);
        double scale = Math.max(w / cropW, h / cropH);
        double dw = imageW * scale;
        double dh = imageH * scale;
        double x = (w - cropW * scale) / 2.0 - crop.x * dw;
        double y = (h - cropH * scale) / 2.0 - crop.y * dh;
        return new Rectangle((int) Math.round(x), (int) Math.round(y),
                (int) Math.round(dw), (int) Math.round(dh));
    }

    /** The width-to-height ratio of the visible part of the picture. */
    public static double cropAspect(Rectangle2D.Float crop, int imageW, int imageH) {
        Rectangle2D.Float c = crop == null ? fullCrop() : crop;
        double width = Math.max(1.0, c.width * imageW);
        double height = Math.max(1.0, c.height * imageH);
        return width / height;
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
     * <p>The region is stored relative to the whole image, so it travels through the same transform
     * the image does — {@code imageRect} — whatever part of the picture is visible. A region that
     * the card (or an aggressive crop) cuts into keeps its visible remainder: the number is fitted
     * into what is left rather than vanishing.
     */
    public static Rectangle2D.Float regionOn(Rectangle2D.Float region, Rectangle imageRect,
                                             int boxW, int boxH) {
        if (region == null) {
            return null;
        }
        float x = (float) (imageRect.x + region.x * imageRect.width);
        float y = (float) (imageRect.y + region.y * imageRect.height);
        float x2 = Math.min((float) (x + region.width * imageRect.width), boxW);
        float y2 = Math.min((float) (y + region.height * imageRect.height), boxH);
        x = Math.max(x, 0f);
        y = Math.max(y, 0f);
        if (x2 - x < 1f || y2 - y < 1f) {
            return null;
        }
        return new Rectangle2D.Float(x, y, x2 - x, y2 - y);
    }

    /** True when {@code inner} lies completely inside {@code outer}. */
    public static boolean contains(Rectangle2D.Float outer, Rectangle2D.Float inner) {
        if (outer == null || inner == null) {
            return false;
        }
        float eps = 0.002f;
        return inner.x >= outer.x - eps
                && inner.y >= outer.y - eps
                && inner.x + inner.width <= outer.x + outer.width + eps
                && inner.y + inner.height <= outer.y + outer.height + eps;
    }
}
