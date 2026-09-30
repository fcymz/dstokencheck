package com.ruoyi.dstokencheck.ui;

import javax.swing.Icon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.basic.BasicCheckBoxMenuItemUI;
import javax.swing.plaf.basic.BasicMenuUI;
import javax.swing.plaf.basic.BasicMenuItemUI;
import javax.swing.plaf.basic.BasicPopupMenuSeparatorUI;
import javax.swing.plaf.basic.BasicPopupMenuUI;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Paints the right-click menu in the app's own colours.
 *
 * <p>Without this the menu is the look and feel's default: a light grey slab with bright blue
 * separators, dark submenu arrows that vanish against the app, and cramped rows — the one piece of
 * system furniture left in a window that is otherwise hand-painted. Everything here is flat and
 * dark, matching the cards and buttons: a rounded surface, a hairline border, roomy rows, and a
 * soft highlight under the pointer.
 *
 * <p>Applied per component rather than through {@code UIManager} defaults, because a look and feel
 * only accepts a UI class name it can instantiate reflectively, which would force every one of
 * these classes into a public file of its own.
 *
 * <p>The rounded corners need the popup's own window to be shaped. That is done best-effort: if the
 * platform refuses, the menu is simply square, which is still a dark, tidy menu.
 */
public final class MenuSkin {

    private static final int ARC = 12;
    private static final int ROW_PAD_X = 11;
    private static final int ROW_PAD_Y = 5;
    private static final int POPUP_PAD = 6;
    private static final int HOVER_ARC = 8;

    private static final Icon ARROW = new ChevronIcon();
    private static final Icon CHECK = new CheckIcon();
    private static final Color DISABLED = new Color(78, 88, 108);
    private static final Color SEPARATOR = new Color(41, 50, 68);

    private MenuSkin() {
    }

    /** Styles a popup and everything inside it, submenus included. Safe to re-apply after a rebuild. */
    public static void apply(JPopupMenu menu) {
        if (menu == null) {
            return;
        }
        // The preset submenu is rebuilt every time it opens and this is called again right after.
        // Replacing the popup's UI would uninstall the old one while its own "about to show"
        // callback is still queued — and that callback reaches into a field the uninstall clears.
        if (!(menu.getUI() instanceof FlatPopupMenuUI)) {
            menu.setUI(new FlatPopupMenuUI());
        }
        for (Component component : menu.getComponents()) {
            if (component instanceof JMenu) {
                JMenu submenu = (JMenu) component;
                submenu.setUI(new FlatMenuUI());
                apply(submenu.getPopupMenu());
            } else if (component instanceof JCheckBoxMenuItem) {
                ((JCheckBoxMenuItem) component).setUI(new FlatCheckBoxMenuItemUI());
            } else if (component instanceof JMenuItem) {
                ((JMenuItem) component).setUI(new FlatMenuItemUI());
            } else if (component instanceof JSeparator) {
                ((JSeparator) component).setUI(new FlatSeparatorUI());
            }
        }
    }

    private static void styleRow(JMenuItem item) {
        item.setFont(Theme.ui(Font.PLAIN, 12f));
        item.setForeground(Theme.TEXT);
        item.setBackground(Theme.SURFACE);
        // The popup paints the surface; a row only ever paints its own highlight.
        item.setOpaque(false);
        item.setBorder(new EmptyBorder(ROW_PAD_Y, ROW_PAD_X, ROW_PAD_Y, ROW_PAD_X));
        item.setIconTextGap(8);
    }

    private static void paintRow(Graphics g, JMenuItem item) {
        boolean hot = item.isArmed() || (item instanceof JMenu && item.isSelected());
        if (!hot) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(item.isEnabled() ? Theme.SURFACE_HOVER : Theme.SURFACE);
            g2.fill(new RoundRectangle2D.Float(0, 0, item.getWidth(), item.getHeight(),
                    HOVER_ARC, HOVER_ARC));
            if (item.isEnabled()) {
                // A hairline in the accent colour reads as "this is the row you are on" without
                // shouting, and it keeps the highlight visible on a light desktop behind the menu.
                g2.setColor(new Color(Theme.ACCENT.getRed(), Theme.ACCENT.getGreen(),
                        Theme.ACCENT.getBlue(), 70));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, item.getWidth() - 1f,
                        item.getHeight() - 1f, HOVER_ARC, HOVER_ARC));
            }
        } finally {
            g2.dispose();
        }
    }

    /** A plain row. */
    private static class FlatMenuItemUI extends BasicMenuItemUI {
        @Override
        protected void installDefaults() {
            super.installDefaults();
            styleRow(menuItem);
            selectionForeground = Theme.TEXT;
            selectionBackground = Theme.SURFACE_HOVER;
            acceleratorForeground = Theme.TEXT_DIM;
            acceleratorSelectionForeground = Theme.TEXT;
            disabledForeground = DISABLED;
            // The look and feel paints the check and arrow icons for *any* item whose icon field is
            // set, not only for the items that are supposed to have one. Leave both empty here.
            arrowIcon = null;
            checkIcon = null;
        }

        @Override
        protected void paintBackground(Graphics g, JMenuItem item, Color background) {
            paintRow(g, item);
        }
    }

    /** A submenu title; the arrow is the one this app needs to be able to see. */
    private static class FlatMenuUI extends BasicMenuUI {
        @Override
        protected void installDefaults() {
            super.installDefaults();
            styleRow(menuItem);
            selectionForeground = Theme.TEXT;
            selectionBackground = Theme.SURFACE_HOVER;
            acceleratorForeground = Theme.TEXT_DIM;
            acceleratorSelectionForeground = Theme.TEXT;
            disabledForeground = DISABLED;
            arrowIcon = ARROW;
            checkIcon = null;
        }

        @Override
        protected void paintBackground(Graphics g, JMenuItem item, Color background) {
            paintRow(g, item);
        }
    }

    /** 开机自启 and anything else that toggles. */
    private static class FlatCheckBoxMenuItemUI extends BasicCheckBoxMenuItemUI {
        @Override
        protected void installDefaults() {
            super.installDefaults();
            styleRow(menuItem);
            selectionForeground = Theme.TEXT;
            selectionBackground = Theme.SURFACE_HOVER;
            disabledForeground = DISABLED;
            checkIcon = CHECK;
            arrowIcon = null;
        }

        @Override
        protected void paintBackground(Graphics g, JMenuItem item, Color background) {
            paintRow(g, item);
        }
    }

    /** The separator: one quiet hairline instead of the look and feel's bright blue groove. */
    private static class FlatSeparatorUI extends BasicPopupMenuSeparatorUI {
        @Override
        public Dimension getPreferredSize(JComponent c) {
            return new Dimension(1, 7);
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(SEPARATOR);
                g2.fillRect(4, c.getHeight() / 2, Math.max(0, c.getWidth() - 8), 1);
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * The menu surface itself: one rounded, outlined card, and a window shaped to match so the
     * corners are actually round rather than filled in with the surface colour.
     */
    private static class FlatPopupMenuUI extends BasicPopupMenuUI {
        private PopupMenuListener shaper;
        private ComponentListener reshaper;

        @Override
        public void installDefaults() {
            super.installDefaults();
            popupMenu.setFont(Theme.ui(Font.PLAIN, 12f));
            popupMenu.setBackground(Theme.SURFACE);
            popupMenu.setOpaque(true);
            popupMenu.setBorder(new EmptyBorder(POPUP_PAD, POPUP_PAD, POPUP_PAD, POPUP_PAD));
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.BORDER);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, c.getWidth() - 1f, c.getHeight() - 1f,
                        ARC, ARC));
            } finally {
                g2.dispose();
            }
        }

        @Override
        protected void installListeners() {
            super.installListeners();
            reshaper = new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    shapeWindow();
                }
            };
            popupMenu.addComponentListener(reshaper);
            shaper = new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                    // The window only exists once the popup is about to be shown, and its size may
                    // still arrive afterwards; both hooks call the same idempotent shaping.
                    SwingUtilities.invokeLater(FlatPopupMenuUI.this::shapeWindow);
                }

                @Override
                public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                    // nothing to do
                }

                @Override
                public void popupMenuCanceled(PopupMenuEvent e) {
                    // nothing to do
                }
            };
            popupMenu.addPopupMenuListener(shaper);
        }

        @Override
        protected void uninstallListeners() {
            if (reshaper != null) {
                popupMenu.removeComponentListener(reshaper);
                reshaper = null;
            }
            if (shaper != null) {
                popupMenu.removePopupMenuListener(shaper);
                shaper = null;
            }
            super.uninstallListeners();
        }

        private void shapeWindow() {
            // A queued callback can outlive its UI being uninstalled, which clears this field.
            if (popupMenu == null) {
                return;
            }
            Window window = SwingUtilities.getWindowAncestor(popupMenu);
            // Only the popup's own window. A lightweight popup's ancestor is the app window, and
            // reshaping that would round off the widget itself.
            if (!(window instanceof JWindow)) {
                return;
            }
            Dimension size = window.getSize();
            if (size.width <= 2 || size.height <= 2) {
                return;
            }
            try {
                window.setShape(new RoundRectangle2D.Float(0, 0, size.width, size.height, ARC, ARC));
            } catch (Throwable ignored) {
                // Shaping is a nicety; a square menu in the right colours is still fine.
            }
        }
    }

    /** A right-pointing chevron for submenus; the default arrow is invisible on a dark surface. */
    private static class ChevronIcon implements Icon {
        @Override
        public int getIconWidth() {
            return 8;
        }

        @Override
        public int getIconHeight() {
            return 12;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c.isEnabled() ? Theme.TEXT_DIM : DISABLED);
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(new Line2D.Float(x + 2f, y + 2f, x + 5.5f, y + 6f));
                g2.draw(new Line2D.Float(x + 5.5f, y + 6f, x + 2f, y + 10f));
            } finally {
                g2.dispose();
            }
        }
    }

    /** The tick for 开机自启. */
    private static class CheckIcon implements Icon {
        @Override
        public int getIconWidth() {
            return 13;
        }

        @Override
        public int getIconHeight() {
            return 12;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c.isEnabled() ? Theme.ACCENT_SOFT : DISABLED);
                g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D.Float tick = new Path2D.Float();
                tick.moveTo(x + 1.5f, y + 6.5f);
                tick.lineTo(x + 4.5f, y + 9.5f);
                tick.lineTo(x + 10f, y + 2.5f);
                g2.draw(tick);
            } finally {
                g2.dispose();
            }
        }
    }
}
