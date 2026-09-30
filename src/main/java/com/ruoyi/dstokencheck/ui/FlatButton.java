package com.ruoyi.dstokencheck.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * A flat, rounded button in the app's own colours.
 *
 * <p>The cross-platform look and feel draws a beveled grey button that looks nothing like the rest
 * of this app, and it ignores most colour keys. Painting the button here keeps every dialog
 * consistent and lets a "primary" action stand out from the secondary ones next to it.
 *
 * <p>The text is still painted by the UI delegate: the button is made non-opaque with no content
 * area fill, so the delegate draws only the label — including its font, alignment and disabled
 * state — while the background comes from {@link #paintComponent}.
 */
public class FlatButton extends JButton {

    /** Primary buttons carry the accent colour; secondary ones are quiet surfaces. */
    public enum Kind { PRIMARY, SECONDARY }

    private static final int ARC = 9;

    private final Kind kind;
    private boolean hover;
    private boolean active;

    public FlatButton(String text, Kind kind) {
        super(text);
        this.kind = kind;
        setFont(Theme.ui(kind == Kind.PRIMARY ? Font.BOLD : Font.PLAIN, 11.5f));
        setForeground(kind == Kind.PRIMARY ? Color.WHITE : Theme.TEXT);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setBorder(BorderFactory.createEmptyBorder(7, 14, 8, 14));
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

    /** Marks a button that represents a mode, so the chosen one stays lit. */
    public void setActive(boolean active) {
        this.active = active;
        setForeground(active && kind == Kind.SECONDARY ? Theme.ACCENT_SOFT : foregroundFor(kind));
        repaint();
    }

    public boolean isActive() {
        return active;
    }

    private static Color foregroundFor(Kind kind) {
        return kind == Kind.PRIMARY ? Color.WHITE : Theme.TEXT;
    }

    /** Slightly smaller padding than the default, for buttons that sit in a dense row. */
    public FlatButton compact() {
        setBorder(BorderFactory.createEmptyBorder(5, 10, 6, 10));
        return this;
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        // The font metrics ignore the rounded corners; keep a little breathing room on both sides.
        d.width = Math.max(d.width, 64);
        return d;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape shape = new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), ARC, ARC);

            Color fill;
            Color border;
            if (kind == Kind.PRIMARY) {
                fill = hover && isEnabled() ? brighten(Theme.BUTTON_BG, 0.10f) : Theme.BUTTON_BG;
                border = Theme.alpha(Theme.ACCENT_SOFT, 110);
            } else if (active) {
                fill = Theme.alpha(Theme.ACCENT, hover ? 70 : 52);
                border = Theme.alpha(Theme.ACCENT_SOFT, 170);
            } else {
                fill = hover ? Theme.SURFACE_HOVER : Theme.SURFACE;
                border = Theme.alpha(Theme.BORDER, 190);
            }
            if (!isEnabled()) {
                fill = Theme.alpha(Theme.SURFACE, 120);
                border = Theme.alpha(Theme.BORDER, 90);
            }

            g2.setColor(fill);
            g2.fill(shape);
            g2.setColor(border);
            g2.draw(shape);
        } finally {
            g2.dispose();
        }
        // Paints the label only: the delegate is told not to fill the content area.
        super.paintComponent(g);
    }

    private static Color brighten(Color c, float amount) {
        return new Color(
                Math.min(255, Math.round(c.getRed() + (255 - c.getRed()) * amount)),
                Math.min(255, Math.round(c.getGreen() + (255 - c.getGreen()) * amount)),
                Math.min(255, Math.round(c.getBlue() + (255 - c.getBlue()) * amount)));
    }
}
