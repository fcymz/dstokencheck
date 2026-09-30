package com.ruoyi.dstokencheck.ui;

import javax.swing.JPanel;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;

/**
 * The rounded gradient card every window of this app is built on.
 *
 * <p>Opaque and clipped with {@link java.awt.Window#setShape}, never a translucent background: on a
 * per-pixel translucent window Java2D emits LCD glyphs with alpha 0 and the whole card renders blank
 * (see {@code BalanceBoard.applyShape}). The rounded look comes from the window shape; this panel
 * only has to paint the same corners so the border follows them.
 */
public class CardPanel extends JPanel {

    private final int arc;

    public CardPanel(int arc) {
        this.arc = arc;
        setOpaque(false);
    }

    public int getArc() {
        return arc;
    }

    /** The rounded outline of this card, for panels that want to match its corners. */
    public Shape outline() {
        return new RoundRectangle2D.Float(0, 0, Math.max(1, getWidth()) - 1,
                Math.max(1, getHeight()) - 1, arc, arc);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape shape = outline();
            java.awt.Shape old = g2.getClip();
            g2.clip(shape);
            g2.setPaint(new GradientPaint(0, 0, Theme.BG_TOP, 0, getHeight(), Theme.BG_BOTTOM));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setClip(old);
            g2.setColor(Theme.alpha(Theme.ACCENT, 90));
            g2.draw(shape);
        } finally {
            g2.dispose();
        }
    }
}
