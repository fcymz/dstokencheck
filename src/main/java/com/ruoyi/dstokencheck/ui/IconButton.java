package com.ruoyi.dstokencheck.ui;

import javax.swing.JButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;

/**
 * A small borderless button that draws its own glyph.
 *
 * <p>Vector glyphs are used instead of an icon font or emoji so the widget looks the same on every
 * machine and on JDK 8, where colour-emoji rendering is unavailable.
 */
public class IconButton extends JButton {

    public enum Glyph { CLOSE, REFRESH, PIN, MINIMISE, MENU, IMAGE }

    private final Glyph glyph;
    private boolean hover;
    private boolean active;

    public IconButton(Glyph glyph, String tooltip) {
        this.glyph = glyph;
        setPreferredSize(new Dimension(22, 22));
        setMinimumSize(new Dimension(22, 22));
        setMaximumSize(new Dimension(22, 22));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setRolloverEnabled(false);
        setToolTipText(tooltip);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = false;
                repaint();
            }
        });
    }

    public void setActive(boolean active) {
        this.active = active;
        repaint();
    }

    public boolean isActive() {
        return active;
    }

    /** Resizes the button so the icon keeps pace with the text around it. */
    public void setIconSize(int size) {
        Dimension d = new Dimension(size, size);
        setPreferredSize(d);
        setMinimumSize(d);
        setMaximumSize(d);
        revalidate();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();

            if (hover) {
                Color base = (glyph == Glyph.CLOSE) ? Theme.DANGER : Theme.ACCENT;
                g2.setColor(Theme.alpha(base, 55));
                g2.fill(new Ellipse2D.Float(0, 0, w, h));
            }

            Color stroke;
            if (glyph == Glyph.CLOSE && hover) {
                stroke = new Color(255, 235, 235);
            } else if (active) {
                stroke = Theme.ACCENT_SOFT;
            } else if (hover) {
                stroke = Theme.TEXT;
            } else {
                stroke = Theme.TEXT_DIM;
            }
            paintGlyph(g2, glyph, w, h, stroke);
        } finally {
            g2.dispose();
        }
    }

    /**
     * Draws one glyph centred in a {@code w x h} box.
     *
     * <p>Static and colour-parameterised so the same artwork can also serve as an
     * {@link javax.swing.Icon} on an ordinary button — see {@link GlyphIcon}.
     */
    public static void paintGlyph(Graphics2D g2, Glyph glyph, int w, int h, Color color) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) * 0.26f;

        switch (glyph) {
            case CLOSE:
                g2.draw(new Line2D.Float(cx - r, cy - r, cx + r, cy + r));
                g2.draw(new Line2D.Float(cx + r, cy - r, cx - r, cy + r));
                break;
            case REFRESH: {
                g2.draw(new java.awt.geom.Arc2D.Float(cx - r, cy - r, r * 2, r * 2, 40, 270,
                        java.awt.geom.Arc2D.OPEN));
                // arrow head at the open end of the arc
                g2.draw(new Line2D.Float(cx + r * 0.78f, cy - r * 0.62f, cx + r * 1.22f, cy - r * 0.30f));
                g2.draw(new Line2D.Float(cx + r * 1.22f, cy - r * 0.30f, cx + r * 0.72f, cy - r * 0.05f));
                break;
            }
            case PIN:
                g2.draw(new Line2D.Float(cx + r * 0.9f, cy - r * 0.9f, cx - r * 0.1f, cy + r * 0.1f));
                g2.draw(new Line2D.Float(cx - r * 0.6f, cy - r * 0.35f, cx + r * 0.35f, cy + r * 0.6f));
                g2.draw(new Line2D.Float(cx - r * 0.35f, cy + r * 0.35f, cx - r * 0.9f, cy + r * 0.9f));
                break;
            case MINIMISE:
                g2.draw(new Line2D.Float(cx - r, cy + r * 0.6f, cx + r, cy + r * 0.6f));
                break;
            case IMAGE: {
                // A framed picture: rectangle, horizon and a sun, drawn to the same radius as the
                // other glyphs so it sits evenly next to text.
                float rw = r * 1.35f;
                float rh = r * 1.05f;
                g2.draw(new java.awt.geom.Rectangle2D.Float(cx - rw, cy - rh, rw * 2, rh * 2));
                g2.fill(new Ellipse2D.Float(cx - rw * 0.5f, cy - rh * 0.55f, r * 0.5f, r * 0.5f));
                g2.draw(new Line2D.Float(cx - rw, cy + rh * 0.45f, cx - rw * 0.25f, cy - rh * 0.15f));
                g2.draw(new Line2D.Float(cx - rw * 0.25f, cy - rh * 0.15f, cx + rw * 0.4f, cy + rh * 0.45f));
                break;
            }
            case MENU:
            default:
                for (int i = -1; i <= 1; i++) {
                    float y = cy + i * (r * 0.72f);
                    g2.fill(new Ellipse2D.Float(cx - 1.5f, y - 1.5f, 3f, 3f));
                }
                break;
        }
    }

    /**
     * Puts a {@link Glyph} on any button that takes an icon.
     *
     * <p>It paints in the component's foreground colour, so the same glyph works on a light or a
     * dark button without a second copy of the artwork.
     */
    public static class GlyphIcon implements javax.swing.Icon {

        private final Glyph glyph;
        private final int size;

        public GlyphIcon(Glyph glyph, int size) {
            this.glyph = glyph;
            this.size = size;
        }

        @Override
        public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.translate(x, y);
                Color color = c == null || c.getForeground() == null ? Theme.TEXT : c.getForeground();
                if (!c.isEnabled()) {
                    color = Theme.TEXT_DIM;
                }
                paintGlyph(g2, glyph, size, size, color);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }
}
