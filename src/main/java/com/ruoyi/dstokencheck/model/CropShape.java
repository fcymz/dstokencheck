package com.ruoyi.dstokencheck.model;

import java.awt.Rectangle;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The outline of the part of a picture the widget shows.
 *
 * <p>Held as a closed polygon in 0..1 of the image, so a plain rectangle is just the four-point
 * case and an irregular one is the same kind of thing with more corners. The widget shapes its
 * window with this outline and clips its painting to it, which is what makes the window's edge
 * follow the crop — a lassoed subject ends up as a window in that subject's silhouette.
 *
 * <p>Points are kept in image coordinates rather than in window coordinates because the window is
 * resized freely (proportionally) and the editor shows the whole picture at any zoom. Everything
 * else is derived: {@link #bounds()} places the picture, {@link #toPath} draws it.
 */
public final class CropShape {

    /** Cap on corners: a window region built from hundreds of points gets slow to move. */
    public static final int MAX_POINTS = 96;
    private static final int MIN_POINTS = 3;
    /** Two points closer than this (in image fractions) are the same point. */
    private static final float SAME_POINT = 0.0008f;
    /** A crop narrower or shorter than this is not a crop. */
    private static final float MIN_SPAN = 0.01f;

    private final float[] xs;
    private final float[] ys;

    private CropShape(float[] xs, float[] ys) {
        this.xs = xs;
        this.ys = ys;
    }

    /** The four-point outline of a rectangle. */
    public static CropShape rectangle(Rectangle2D.Float r) {
        return new CropShape(
                new float[]{r.x, r.x + r.width, r.x + r.width, r.x},
                new float[]{r.y, r.y, r.y + r.height, r.y + r.height});
    }

    /**
     * Builds an outline from image-normalised points.
     *
     * @return null when there is nothing usable: fewer than three distinct corners, or a shape too
     *         thin to be a window
     */
    public static CropShape of(List<Point2D.Float> points) {
        if (points == null) {
            return null;
        }
        List<Point2D.Float> clean = new ArrayList<Point2D.Float>();
        for (Point2D.Float p : points) {
            if (p == null) {
                continue;
            }
            float x = clamp01(p.x);
            float y = clamp01(p.y);
            Point2D.Float last = clean.isEmpty() ? null : clean.get(clean.size() - 1);
            if (last == null || !same(last.x, last.y, x, y)) {
                clean.add(new Point2D.Float(x, y));
            }
        }
        // A traced loop normally comes back to where it started; the closing edge is implicit.
        while (clean.size() > 3) {
            Point2D.Float first = clean.get(0);
            Point2D.Float last = clean.get(clean.size() - 1);
            if (same(first.x, first.y, last.x, last.y)) {
                clean.remove(clean.size() - 1);
            } else {
                break;
            }
        }
        if (clean.size() < MIN_POINTS) {
            return null;
        }
        Rectangle2D.Float bounds = boundsOf(clean);
        if (bounds.width < MIN_SPAN || bounds.height < MIN_SPAN) {
            return null;
        }
        if (clean.size() > MAX_POINTS) {
            clean = decimate(clean, MAX_POINTS);
        }
        float[] xs = new float[clean.size()];
        float[] ys = new float[clean.size()];
        for (int i = 0; i < clean.size(); i++) {
            xs[i] = clean.get(i).x;
            ys[i] = clean.get(i).y;
        }
        return new CropShape(xs, ys);
    }

    /**
     * Turns a freehand trace into an outline.
     *
     * <p>A raw pointer trace has hundreds of jittery samples, which would be a slow and ugly window
     * region, so it is reduced to its corners first (Douglas–Peucker) and coarsened further if it is
     * still dense.
     *
     * @param trace     the dragged path, in image-normalised coordinates
     * @param tolerance how far the simplified line may stray, in image fractions
     */
    public static CropShape fromTrace(List<Point2D.Float> trace, float tolerance) {
        if (trace == null || trace.size() < MIN_POINTS) {
            return null;
        }
        List<Point2D.Float> loop = new ArrayList<Point2D.Float>(trace);
        Point2D.Float first = loop.get(0);
        loop.add(new Point2D.Float(first.x, first.y));
        List<Point2D.Float> simplified = withoutClosingPoint(simplify(loop, tolerance));
        float widened = tolerance;
        for (int i = 0; i < 6 && simplified.size() > MAX_POINTS; i++) {
            widened *= 1.7f;
            simplified = withoutClosingPoint(simplify(loop, widened));
        }
        return of(simplified);
    }

    /** The same outline moved by a delta, in image fractions. */
    public CropShape translated(float dx, float dy) {
        List<Point2D.Float> moved = new ArrayList<Point2D.Float>(xs.length);
        for (int i = 0; i < xs.length; i++) {
            moved.add(new Point2D.Float(clamp01(xs[i] + dx), clamp01(ys[i] + dy)));
        }
        CropShape shape = of(moved);
        return shape == null ? this : shape;
    }

    /**
     * The same outline mapped so that its bounding box becomes {@code target}.
     *
     * <p>This is how the editor's resize handles work on an irregular shape: stretching the frame
     * stretches every corner with it, keeping the silhouette's proportions relative to the frame.
     */
    public CropShape withBounds(Rectangle2D.Float target) {
        Rectangle2D.Float from = bounds();
        if (from.width <= 0f || from.height <= 0f) {
            return this;
        }
        List<Point2D.Float> mapped = new ArrayList<Point2D.Float>(xs.length);
        for (int i = 0; i < xs.length; i++) {
            mapped.add(new Point2D.Float(
                    target.x + (xs[i] - from.x) / from.width * target.width,
                    target.y + (ys[i] - from.y) / from.height * target.height));
        }
        CropShape shape = of(mapped);
        return shape == null ? this : shape;
    }

    public int size() {
        return xs.length;
    }

    /** The bounding box of the outline: what the picture is scaled and aligned by. */
    public Rectangle2D.Float bounds() {
        float minX = xs[0];
        float maxX = xs[0];
        float minY = ys[0];
        float maxY = ys[0];
        for (int i = 1; i < xs.length; i++) {
            minX = Math.min(minX, xs[i]);
            maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
        }
        return new Rectangle2D.Float(minX, minY, maxX - minX, maxY - minY);
    }

    /** True when the outline is exactly its own bounding box, i.e. a plain rectangular crop. */
    public boolean isRectangle() {
        if (xs.length != 4) {
            return false;
        }
        float[] cx = {bounds().x, bounds().x + bounds().width,
                      bounds().x + bounds().width, bounds().x};
        float[] cy = {bounds().y, bounds().y, bounds().y + bounds().height,
                      bounds().y + bounds().height};
        for (int i = 0; i < 4; i++) {
            if (!same(xs[i], ys[i], cx[i], cy[i])) {
                return false;
            }
        }
        return true;
    }

    /** The outline scaled into {@code imageRect}, the rectangle the whole picture is drawn in. */
    public Path2D.Float toPath(Rectangle imageRect) {
        Path2D.Float path = new Path2D.Float(Path2D.WIND_NON_ZERO);
        for (int i = 0; i < xs.length; i++) {
            float px = (float) (imageRect.x + xs[i] * imageRect.width);
            float py = (float) (imageRect.y + ys[i] * imageRect.height);
            if (i == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        path.closePath();
        return path;
    }

    /**
     * True when {@code box} (image-normalised) lies inside the outline.
     *
     * <p>The corners plus the centre are sampled rather than the true area: this only drives a
     * warning, and a box that pokes out between two corners by a hair is not worth the geometry.
     */
    public boolean contains(Rectangle2D.Float box) {
        Rectangle imageRect = new Rectangle(0, 0, 1000, 1000);
        Path2D.Float path = toPath(imageRect);
        float[] px = {box.x, box.x + box.width, box.x, box.x + box.width, box.x + box.width / 2f};
        float[] py = {box.y, box.y, box.y + box.height, box.y + box.height, box.y + box.height / 2f};
        for (int i = 0; i < px.length; i++) {
            if (!path.contains(px[i] * imageRect.width, py[i] * imageRect.height)) {
                return false;
            }
        }
        return true;
    }

    /** Compact text form for the settings file: {@code x,y;x,y;…}. */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.length; i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(String.format(Locale.ROOT, "%.4f,%.4f", xs[i], ys[i]));
        }
        return sb.toString();
    }

    /** Reads {@link #serialize()}'s output; null when the text is not a usable outline. */
    public static CropShape parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String[] pairs = raw.trim().split(";");
        if (pairs.length < MIN_POINTS || pairs.length > MAX_POINTS * 2) {
            return null;
        }
        List<Point2D.Float> points = new ArrayList<Point2D.Float>(pairs.length);
        for (String pair : pairs) {
            String[] xy = pair.split(",");
            if (xy.length != 2) {
                return null;
            }
            try {
                points.add(new Point2D.Float(
                        Float.parseFloat(xy[0].trim()), Float.parseFloat(xy[1].trim())));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return of(points);
    }

    // ------------------------------------------------------------------ maths

    private static Rectangle2D.Float boundsOf(List<Point2D.Float> points) {
        float minX = points.get(0).x;
        float maxX = minX;
        float minY = points.get(0).y;
        float maxY = minY;
        for (Point2D.Float p : points) {
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y);
            maxY = Math.max(maxY, p.y);
        }
        return new Rectangle2D.Float(minX, minY, maxX - minX, maxY - minY);
    }

    private static List<Point2D.Float> withoutClosingPoint(List<Point2D.Float> points) {
        List<Point2D.Float> out = new ArrayList<Point2D.Float>(points);
        while (out.size() > MIN_POINTS) {
            Point2D.Float first = out.get(0);
            Point2D.Float last = out.get(out.size() - 1);
            if (same(first.x, first.y, last.x, last.y)) {
                out.remove(out.size() - 1);
            } else {
                break;
            }
        }
        return out;
    }

    /** Douglas–Peucker: keep the points that carry the shape, drop the ones along a straight run. */
    private static List<Point2D.Float> simplify(List<Point2D.Float> points, float tolerance) {
        boolean[] keep = new boolean[points.size()];
        keep[0] = true;
        keep[points.size() - 1] = true;
        simplifyRun(points, 0, points.size() - 1, tolerance, keep);
        List<Point2D.Float> out = new ArrayList<Point2D.Float>();
        for (int i = 0; i < points.size(); i++) {
            if (keep[i]) {
                out.add(points.get(i));
            }
        }
        return out;
    }

    private static void simplifyRun(List<Point2D.Float> points, int from, int to,
                                    float tolerance, boolean[] keep) {
        if (to <= from + 1) {
            return;
        }
        float ax = points.get(from).x;
        float ay = points.get(from).y;
        float dx = points.get(to).x - ax;
        float dy = points.get(to).y - ay;
        float lengthSq = dx * dx + dy * dy;

        float worst = -1f;
        int worstIndex = -1;
        for (int i = from + 1; i < to; i++) {
            float px = points.get(i).x - ax;
            float py = points.get(i).y - ay;
            float distance;
            if (lengthSq <= 0f) {
                distance = (float) Math.hypot(px, py);
            } else {
                float t = Math.max(0f, Math.min(1f, (px * dx + py * dy) / lengthSq));
                distance = (float) Math.hypot(px - t * dx, py - t * dy);
            }
            if (distance > worst) {
                worst = distance;
                worstIndex = i;
            }
        }
        if (worst > tolerance && worstIndex > from) {
            keep[worstIndex] = true;
            simplifyRun(points, from, worstIndex, tolerance, keep);
            simplifyRun(points, worstIndex, to, tolerance, keep);
        }
    }

    /** Even sampling, as a last resort when simplification cannot get under the point cap. */
    private static List<Point2D.Float> decimate(List<Point2D.Float> points, int target) {
        List<Point2D.Float> out = new ArrayList<Point2D.Float>(target);
        for (int i = 0; i < target; i++) {
            out.add(points.get((int) Math.round(i * (points.size() - 1) / (double) (target - 1))));
        }
        return out;
    }

    private static boolean same(float ax, float ay, float bx, float by) {
        return Math.abs(ax - bx) < SAME_POINT && Math.abs(ay - by) < SAME_POINT;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
