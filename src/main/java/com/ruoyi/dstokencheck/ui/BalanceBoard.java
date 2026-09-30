package com.ruoyi.dstokencheck.ui;

import com.ruoyi.dstokencheck.autostart.AutoStart;
import com.ruoyi.dstokencheck.config.AppConfig;
import com.ruoyi.dstokencheck.config.Preset;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.ruoyi.dstokencheck.model.BalanceSnapshot;
import com.ruoyi.dstokencheck.model.CropShape;
import com.ruoyi.dstokencheck.model.Wallet;
import com.ruoyi.dstokencheck.net.DeepSeekClient;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The desktop widget: a borderless window showing the DeepSeek balance.
 *
 * <p>Because the window is undecorated it gets no system title bar and no OS resize grips, so both
 * are reimplemented here. A single application-wide {@link AWTEventListener} watches mouse events:
 * presses inside the outer few pixels of the frame start a resize in that direction, presses
 * anywhere else drag the window, and motion near an edge updates the resize cursor.
 */
public class BalanceBoard extends JFrame {

    private static final int EDGE = 5;          // grab thickness in px
    private static final int MIN_W = 240;
    private static final int MIN_H = 130;
    private static final int ARC = 16;          // rounded-corner diameter

    // Base font sizes at 100%; every one is multiplied by AppConfig.getFontScale().
    private static final float F_TITLE = 11.5f;
    private static final float F_AMOUNT = 34f;
    private static final float F_SMALL = 10.5f;
    private static final float FONT_SCALE_STEP = 0.05f;

    /**
     * {@code HWND_TOPMOST} from winuser.h — jna-platform's WinUser does not expose it.
     *
     * <p>The {@code L} matters: {@code Pointer.createConstant} is overloaded for {@code int} and
     * {@code long}, and the {@code int} overload zero-extends, so {@code createConstant(-1)} yields
     * 0xFFFFFFFF rather than -1. Passing that as {@code hWndInsertAfter} fails with
     * ERROR_INVALID_WINDOW_HANDLE (1400) and the window never moves.
     */
    private static final HWND HWND_TOPMOST = new HWND(Pointer.createConstant(-1L));

    /** Fully transparent window background; see applyPictureTranslucency for why it is needed. */
    private static final Color TRANSPARENT = new Color(0, 0, 0, 0);

    private static final int NONE = 0;
    private static final int WEST = 1;
    private static final int EAST = 2;
    private static final int NORTH = 4;
    private static final int SOUTH = 8;

    /**
     * Smallest card that may show a picture.
     *
     * <p>The label-driven minimum is about fitting the title, the big number and the footer; with a
     * background the card's proportions are the picture's, and a wide crop would otherwise be
     * squashed into 240×130 — pulling the window's edges off the crop again.
     */
    private static final int MIN_PICTURE_W = 160;
    private static final int MIN_PICTURE_H = 80;

    /** Callbacks the widget uses to hand control back to the application. */
    public interface AuthEvents {
        /** The stored key was rejected; the app should ask for a new one. */
        void onAuthFailed();

        /** The user picked 退出登录; the app should forget the credential and re-prompt. */
        void onLogout();
    }

    private final AppConfig config;
    private final DeepSeekClient client;
    private final AuthEvents authEvents;

    private final BoardPanel board = new BoardPanel();
    private final JLabel titleLabel = new JLabel("DeepSeek \u4f59\u989d");
    private final JLabel amountLabel = new JLabel("\u2014");
    private final JLabel currencyLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel accountLabel = new JLabel(" ");
    private final JPanel extraPanel = new JPanel();
    /** Holds the amount / currency column; hidden while a custom background is in use. */
    private final JPanel centerPanel = new JPanel();
    /** Account + status, pinned to the bottom in both modes. */
    private final JPanel footerPanel = new JPanel();
    /** Title and window buttons; hidden for an irregular outline (see updateChromeVisibility). */
    private final JPanel titleBarPanel = new JPanel();
    private final IconButton pinButton = new IconButton(IconButton.Glyph.PIN, "\u7f6e\u9876\u5f00\u5173");
    private final IconButton refreshButton = new IconButton(IconButton.Glyph.REFRESH, "\u7acb\u5373\u5237\u65b0");
    private final IconButton closeButton = new IconButton(IconButton.Glyph.CLOSE, "\u9690\u85cf\u7a97\u53e3");

    /** The user's background image, or null when the built-in gradient card is used. */
    private BufferedImage backgroundImage;
    /** The visible part of that image (normalised), or null for all of it. */
    private Rectangle2D.Float imageCrop;
    /** The crop's silhouette; a rectangle unless the user traced an irregular one. */
    private CropShape cropShape;
    /** True when the picture has see-through pixels, so the window itself is translucent. */
    private boolean imageHasAlpha;
    /** True while the window is on the per-pixel translucent path. */
    private boolean translucent;
    /** Normalised box on that image where the balance is drawn. */
    private Rectangle2D.Float balanceRegion;
    /** Compact "CNY ≈ 12.4M tokens · 含赠送 …" line shown inside the framed box. */
    private String regionSubtitle = "";

    private final Timer refreshTimer;
    /** Periodically puts the widget back at the front of the topmost band. See reassertTopMost(). */
    private final Timer topMostGuard;
    private final AWTEventListener mouseWatcher;

    private int resizeEdge = NONE;
    private Point pressPoint;
    private Rectangle pressBounds;
    private boolean dragging;
    private boolean busy;

    public BalanceBoard(AppConfig config, DeepSeekClient client, AuthEvents authEvents) {
        super("DeepSeek \u4f59\u989d");
        this.config = config;
        this.client = client;
        this.authEvents = authEvents;

        setUndecorated(true);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setAlwaysOnTop(config.isAlwaysOnTop());
        pinButton.setActive(config.isAlwaysOnTop());

        // Opaque base colour: the rounded look comes from setShape(), never from an
        // alpha-0 background (see applyShape for why that matters).
        setBackground(Theme.BG_BOTTOM);

        boolean shaped = applyShape();
        board.setRounded(shaped);

        setContentPane(board);
        buildUi();

        applyOpacity();

        // A background chosen in an earlier run must come back without any user action.
        applyConfiguredBackground();

        // Keep the rounded shape in step with the window as the user resizes it.
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                applyShape();
            }
        });

        setMinimumSize(new Dimension(minW(), minH()));
        Rectangle saved = config.getBounds();
        setSize(Math.max(minW(), saved.width), Math.max(minH(), saved.height));
        if (config.hasSavedPosition() && isOnScreen(saved)) {
            setLocation(saved.x, saved.y);
        } else {
            setLocationRelativeTo(null);
        }

        // A background makes the card's edges the crop's edges, so the proportions are not the
        // user's to choose freely: correct them here too, in case the settings were edited by hand
        // or saved by an older build that allowed any shape.
        if (backgroundImage != null) {
            fitWindowToImageAspect();
        }

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }

            // Losing activation is exactly what happens when the taskbar is clicked, and the
            // taskbar then sits above this window even though it is still flagged topmost.
            @Override
            public void windowDeactivated(WindowEvent e) {
                reassertTopMost();
            }

            @Override
            public void windowActivated(WindowEvent e) {
                reassertTopMost();
            }

            @Override
            public void windowDeiconified(WindowEvent e) {
                reassertTopMost();
            }
        });

        refreshTimer = new Timer(
                Math.max(AppConfig.MIN_REFRESH_SECONDS, config.getRefreshSeconds()) * 1000, e -> refresh());
        refreshTimer.setInitialDelay(0);

        // Safety net for the cases that raise no activation event (taskbar previews, shell flyouts).
        // One SetWindowPos per second is negligible and cannot steal focus.
        topMostGuard = new Timer(1000, e -> reassertTopMost());
        topMostGuard.setInitialDelay(1000);

        mouseWatcher = createMouseWatcher();
        Toolkit.getDefaultToolkit().addAWTEventListener(
                mouseWatcher, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
    }

    // ------------------------------------------------------------------- UI

    private void buildUi() {
        board.setLayout(new BorderLayout(0, 0));
        board.setBorder(BorderFactory.createEmptyBorder(EF(), 12, 10, 12));
        board.setOpaque(false);

        // ---- title bar (also the drag handle) ----
        JPanel titleBar = titleBarPanel;
        titleBar.setLayout(new BorderLayout());
        titleBar.setOpaque(false);
        titleLabel.setFont(Theme.ui(Font.PLAIN, 11.5f));
        titleLabel.setForeground(Theme.TEXT_DIM);
        titleBar.add(titleLabel, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        buttons.setOpaque(false);
        buttons.add(pinButton);
        buttons.add(refreshButton);
        buttons.add(closeButton);
        titleBar.add(buttons, BorderLayout.EAST);
        board.add(titleBar, BorderLayout.NORTH);

        // ---- big number ----
        centerPanel.setOpaque(false);
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));

        amountLabel.setForeground(Theme.TEXT);
        amountLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        currencyLabel.setForeground(Theme.TEXT_DIM);
        currencyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        extraPanel.setOpaque(false);
        extraPanel.setLayout(new BoxLayout(extraPanel, BoxLayout.Y_AXIS));
        extraPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        centerPanel.add(Box.createVerticalGlue());
        centerPanel.add(amountLabel);
        centerPanel.add(currencyLabel);
        centerPanel.add(extraPanel);
        centerPanel.add(Box.createVerticalGlue());
        board.add(centerPanel, BorderLayout.CENTER);

        // ---- footer ----
        footerPanel.setLayout(new GridLayout(2, 1, 0, 1));
        footerPanel.setOpaque(false);
        accountLabel.setForeground(Theme.alpha(Theme.ACCENT_SOFT, 200));
        statusLabel.setForeground(Theme.TEXT_DIM);
        footerPanel.add(accountLabel);
        footerPanel.add(statusLabel);
        board.add(footerPanel, BorderLayout.SOUTH);

        refreshButton.addActionListener(e -> refresh());
        pinButton.addActionListener(e -> toggleAlwaysOnTop());
        closeButton.addActionListener(e -> shutdown());

        applyFonts();

        JPopupMenu menu = buildContextMenu();
        MenuSkin.apply(menu);
        board.setComponentPopupMenu(menu);
        titleLabel.setComponentPopupMenu(menu);
        amountLabel.setComponentPopupMenu(menu);
        currencyLabel.setComponentPopupMenu(menu);
        statusLabel.setComponentPopupMenu(menu);
        accountLabel.setComponentPopupMenu(menu);

        // Ctrl + wheel resizes the text, the quickest way to "just make it bigger".
        board.addMouseWheelListener(e -> {
            if (e.isControlDown()) {
                setFontScale(config.getFontScale()
                        + (e.getWheelRotation() < 0 ? FONT_SCALE_STEP : -FONT_SCALE_STEP));
                e.consume();
            }
        });
    }

    // ------------------------------------------------------------ font scale

    /** Re-applies every label's font from the configured scale. */
    private void applyFonts() {
        float s = config.getFontScale();
        titleLabel.setFont(Theme.ui(Font.PLAIN, F_TITLE * s));
        amountLabel.setFont(Theme.mono(Font.BOLD, F_AMOUNT * s));
        currencyLabel.setFont(Theme.ui(Font.PLAIN, F_SMALL * s));
        accountLabel.setFont(Theme.ui(Font.PLAIN, F_SMALL * s));
        statusLabel.setFont(Theme.ui(Font.PLAIN, F_SMALL * s));
        for (Component c : extraPanel.getComponents()) {
            if (c instanceof JLabel) {
                ((JLabel) c).setFont(Theme.ui(Font.PLAIN, F_SMALL * s));
            }
        }
        // Keep the buttons in proportion, but never shrink them below a comfortable click target.
        int icon = Math.max(16, Math.round(22 * s));
        pinButton.setIconSize(icon);
        refreshButton.setIconSize(icon);
        closeButton.setIconSize(icon);
        board.revalidate();
        board.repaint();
    }

    /** Smallest sensible window for the current font size, so text is never clipped away. */
    private Dimension minimumBoardSize() {
        float s = config.getFontScale();
        return new Dimension(Math.round(MIN_W * s), Math.round(MIN_H * s));
    }

    private int minW() {
        return backgroundImage != null ? MIN_PICTURE_W : minimumBoardSize().width;
    }

    private int minH() {
        return backgroundImage != null ? MIN_PICTURE_H : minimumBoardSize().height;
    }

    /**
     * Changes the font scale, growing or shrinking the window by the same ratio so the layout keeps
     * its proportions. The user can still resize freely afterwards.
     */
    private void setFontScale(float scale) {
        float old = config.getFontScale();
        config.setFontScale(scale);
        float now = config.getFontScale();
        if (Math.abs(now - old) < 0.0001f) {
            return;
        }
        config.save();
        setMinimumSize(new Dimension(minW(), minH()));

        // The scale drives every label; with a picture the labels are hidden and the figure is
        // fitted to the framed box instead, so only the setting changes — resizing the card by the
        // same ratio would pull its edges off the crop.
        if (backgroundImage == null) {
            Rectangle b = getBounds();
            float ratio = now / old;
            setBounds(b.x, b.y,
                    Math.max(minW(), Math.round(b.width * ratio)),
                    Math.max(minH(), Math.round(b.height * ratio)));
        }
        applyFonts();
        applyShape();

        statusLabel.setForeground(Theme.TEXT_DIM);
        statusLabel.setText("\u5b57\u4f53 " + Math.round(now * 100) + "%");
    }

    /** Left padding keeps every child clear of the resize border. */
    private static int EF() {
        return EDGE + 3;
    }

    private JPopupMenu buildContextMenu() {
        JPopupMenu menu = new JPopupMenu();
        // Force a heavyweight popup. A lightweight one is painted *inside* this window, which
        // causes two separate problems for a widget this small: the menu is clipped by the window
        // bounds, and its items fall within the drag surface, so pressing one starts a window drag
        // and the click never reaches the item.
        menu.setLightWeightPopupEnabled(false);

        JMenuItem refreshItem = new JMenuItem("\u7acb\u5373\u5237\u65b0");
        refreshItem.addActionListener(e -> refresh());
        menu.add(refreshItem);

        JMenuItem logoutItem = new JMenuItem("\u9000\u51fa\u767b\u5f55");
        logoutItem.setToolTipText("\u6e05\u9664\u672c\u673a\u4fdd\u5b58\u7684 API Key \u5e76\u91cd\u65b0\u8f93\u5165");
        logoutItem.addActionListener(e -> {
            if (authEvents != null) {
                authEvents.onLogout();
            }
        });
        menu.add(logoutItem);

        menu.add(new JSeparator());

        // Logon startup. Kept clickable whenever the platform supports it, even if the command
        // cannot be built right now, so the user gets an explanation instead of a dead grey entry.
        final JCheckBoxMenuItem autoStartItem = new JCheckBoxMenuItem("\u5f00\u673a\u81ea\u542f");
        autoStartItem.setSelected(AutoStart.isSupported() && AutoStart.isEnabled());
        autoStartItem.setEnabled(AutoStart.isSupported());
        if (!AutoStart.isSupported()) {
            autoStartItem.setToolTipText("\u4ec5 Windows \u652f\u6301");
        } else if (AutoStart.buildCommand() == null) {
            autoStartItem.setToolTipText("\u9700\u8981\u4ee5 java -jar dstokencheck.jar \u65b9\u5f0f\u8fd0\u884c\u624d\u80fd\u8bbe\u7f6e");
        } else {
            autoStartItem.setToolTipText("\u767b\u5f55 Windows \u540e\u81ea\u52a8\u542f\u52a8\u672c\u7a0b\u5e8f");
        }
        autoStartItem.addActionListener(e -> toggleAutoStart(autoStartItem));
        menu.add(autoStartItem);

        menu.add(new JSeparator());

        JMenuItem pinItem = new JMenuItem("\u7a97\u53e3\u7f6e\u9876");
        pinItem.addActionListener(e -> toggleAlwaysOnTop());
        menu.add(pinItem);

        final JMenuItem sizeItem = new JMenuItem("\u6062\u590d\u9ed8\u8ba4\u5927\u5c0f");
        sizeItem.addActionListener(e -> applySize(360, 180));
        menu.add(sizeItem);

        JMenuItem smallItem = new JMenuItem("\u5c0f\u7a97\u53e3");
        smallItem.addActionListener(e -> applySize(minW(), minH()));
        menu.add(smallItem);

        JMenuItem bigItem = new JMenuItem("\u5927\u7a97\u53e3 (420\u00d7220 \u00d7 \u5b57\u4f53)");
        bigItem.addActionListener(e -> applySize(
                Math.round(420 * config.getFontScale()),
                Math.round(220 * config.getFontScale())));
        menu.add(bigItem);

        menu.add(new JSeparator());

        final JMenu presetMenu = new JMenu("\u9884\u8bbe\u914d\u7f6e");
        // Rebuilt whenever it opens: a preset can be saved while the app is running, and the menu
        // has to offer it without a restart.
        presetMenu.getPopupMenu().addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                rebuildPresetMenu(presetMenu);
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                // nothing to do
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                // nothing to do
            }
        });
        rebuildPresetMenu(presetMenu);
        menu.add(presetMenu);

        menu.add(new JSeparator());

        JMenuItem backgroundItem = new JMenuItem("\u80cc\u666f\u56fe\u2026");
        backgroundItem.setToolTipText("\u9009\u62e9\u4e00\u5f20\u56fe\u7247\uff0c\u5e76\u6846\u5b9a\u4f59\u989d\u663e\u793a\u7684\u4f4d\u7f6e");
        // Deferred: this opens a modal dialog, and a nested event loop started while the popup is
        // still on screen can leave the menu stuck behind (or in front of) the dialog.
        backgroundItem.addActionListener(e -> SwingUtilities.invokeLater(this::openBackgroundDialog));
        menu.add(backgroundItem);

        final JMenuItem clearBackgroundItem = new JMenuItem("\u6062\u590d\u9ed8\u8ba4\u80cc\u666f");
        clearBackgroundItem.setToolTipText("\u79fb\u9664\u81ea\u5b9a\u4e49\u80cc\u666f\u56fe\uff0c\u56de\u5230\u5185\u7f6e\u6df1\u8272\u5361\u7247");
        clearBackgroundItem.addActionListener(e -> clearBackgroundImage());
        menu.add(clearBackgroundItem);

        // Whether there is a background to remove is only knowable while the menu is open; the
        // config file is the single source of truth, so it is read at that moment.
        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                clearBackgroundItem.setEnabled(config.hasBackgroundImage());
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                // nothing to do
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                // nothing to do
            }
        });

        menu.add(new JSeparator());

        menu.add(buildFontScaleMenu());

        menu.add(new JSeparator());

        // 5s / 10s / 30s / 60s / 300s
        for (final int seconds : new int[]{5, 10, 30, 60, 300}) {
            JMenuItem item = new JMenuItem("\u6bcf " + seconds + " \u79d2\u5237\u65b0");
            item.addActionListener(e -> setRefreshSeconds(seconds));
            menu.add(item);
        }

        menu.add(new JSeparator());

        JMenuItem quitItem = new JMenuItem("\u9000\u51fa");
        quitItem.addActionListener(e -> {
            shutdown();
            System.exit(0);
        });
        menu.add(quitItem);

        return menu;
    }

    /**
     * Submenu for text size: quick steps, presets, and a slider for free adjustment.
     *
     * <p>The scale is a multiplier over every font in the widget, so one control resizes all of it
     * coherently rather than making the user tune each label.
     */
    private JMenu buildFontScaleMenu() {
        JMenu fontMenu = new JMenu("\u5b57\u4f53\u5927\u5c0f (" + Math.round(config.getFontScale() * 100) + "%)");

        JMenuItem larger = new JMenuItem("\u66f4\u5927   (Ctrl + \u6eda\u8f6e\u4e0a)");
        larger.addActionListener(e -> setFontScale(config.getFontScale() + FONT_SCALE_STEP));
        fontMenu.add(larger);

        JMenuItem smaller = new JMenuItem("\u66f4\u5c0f   (Ctrl + \u6eda\u8f6e\u4e0b)");
        smaller.addActionListener(e -> setFontScale(config.getFontScale() - FONT_SCALE_STEP));
        fontMenu.add(smaller);

        fontMenu.addSeparator();

        for (final int percent : new int[]{80, 100, 125, 150, 175, 200, 250}) {
            JMenuItem item = new JMenuItem(percent + "%");
            item.addActionListener(e -> setFontScale(percent / 100f));
            fontMenu.add(item);
        }

        fontMenu.addSeparator();

        JMenuItem custom = new JMenuItem("\u81ea\u5b9a\u4e49\u2026");
        custom.addActionListener(e -> showFontScaleDialog());
        fontMenu.add(custom);

        return fontMenu;
    }

    /**
     * Slider for free adjustment (70%–250%). Changes are applied and saved as the slider moves, so
     * the widget itself is the live preview.
     */
    private void showFontScaleDialog() {
        final JDialog dialog = new JDialog(this, "\u5b57\u4f53\u5927\u5c0f", false);
        dialog.setLayout(new BorderLayout(10, 8));
        // The widget is always-on-top, so the dialog has to be too or it hides behind it.
        dialog.setAlwaysOnTop(true);

        final JLabel valueLabel = new JLabel();
        valueLabel.setHorizontalAlignment(JLabel.CENTER);

        final int minPercent = Math.round(0.7f * 100);
        final int maxPercent = Math.round(2.5f * 100);
        final JSlider slider = new JSlider(minPercent, maxPercent, Math.round(config.getFontScale() * 100));
        slider.setMajorTickSpacing(30);
        slider.setMinorTickSpacing(10);
        slider.setPaintTicks(true);
        slider.setPaintLabels(true);

        valueLabel.setText(slider.getValue() + "%");

        final boolean[] guard = {false};
        slider.addChangeListener(e -> {
            if (guard[0]) {
                return;
            }
            guard[0] = true;
            try {
                setFontScale(slider.getValue() / 100f);
                valueLabel.setText(slider.getValue() + "%");
            } finally {
                guard[0] = false;
            }
        });

        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.setBorder(BorderFactory.createEmptyBorder(14, 16, 4, 16));
        center.add(slider, BorderLayout.CENTER);
        center.add(valueLabel, BorderLayout.SOUTH);

        JButton resetButton = new JButton("\u6062\u590d 100%");
        resetButton.addActionListener(e -> slider.setValue(100));

        JButton doneButton = new JButton("\u5b8c\u6210");
        doneButton.addActionListener(e -> dialog.dispose());

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        south.add(resetButton);
        south.add(doneButton);

        dialog.add(center, BorderLayout.CENTER);
        dialog.add(south, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(doneButton);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /**
     * Applies the 开机自启 toggle. The registry is written first: if that fails the checkbox is
     * reverted, so the menu never claims a state the system is not actually in.
     */
    private void toggleAutoStart(JCheckBoxMenuItem item) {
        boolean want = item.isSelected();
        try {
            if (want) {
                AutoStart.enable();
            } else {
                AutoStart.disable();
            }
            // The registry entry is the state; nothing else needs recording.
            statusLabel.setForeground(Theme.TEXT_DIM);
            statusLabel.setText(want ? "\u5df2\u5f00\u542f\u5f00\u673a\u81ea\u542f" : "\u5df2\u5173\u95ed\u5f00\u673a\u81ea\u542f");
        } catch (AutoStart.AutoStartException ex) {
            item.setSelected(!want);
            statusLabel.setForeground(Theme.DANGER);
            statusLabel.setText(truncate(ex.getMessage(), 60));
            // The footer is tiny and truncated, and this was an explicit user action, so show the
            // explanation properly rather than leaving the user wondering why the tick reverted.
            javax.swing.JOptionPane.showMessageDialog(this, ex.getMessage(),
                    "\u5f00\u673a\u81ea\u542f\u8bbe\u7f6e\u5931\u8d25", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }

    private void setRefreshSeconds(int seconds) {        config.setRefreshSeconds(seconds);
        config.save();
        refreshTimer.setDelay(seconds * 1000);
        refreshTimer.setInitialDelay(seconds * 1000);
        statusLabel.setText("\u5237\u65b0\u95f4\u9694\u5df2\u8bbe\u4e3a " + seconds + " \u79d2");
    }

    private void toggleAlwaysOnTop() {
        boolean on = !isAlwaysOnTop();
        config.setAlwaysOnTop(on);
        config.save();
        applyAlwaysOnTop(on);
    }

    /** Applies the pin state and keeps the guard timer in step with it. */
    private void applyAlwaysOnTop(boolean on) {
        setAlwaysOnTop(on);
        pinButton.setActive(on);
        if (on) {
            reassertTopMost();
            if (!topMostGuard.isRunning()) {
                topMostGuard.start();
            }
        } else {
            topMostGuard.stop();
        }
    }

    /**
     * Puts this window back at the front of the topmost band, using the Win32 API.
     *
     * <p>{@code setAlwaysOnTop(true)} only sets {@code WS_EX_TOPMOST}. The taskbar is topmost as
     * well, and inside that band the most recently activated window wins — so after the widget is
     * dragged over the taskbar and the taskbar is clicked, the taskbar ends up covering the widget
     * even though {@code isAlwaysOnTop()} still reports {@code true}. A fresh
     * {@code SetWindowPos(HWND_TOPMOST)} re-seats it at the front.
     *
     * <p>{@code SWP_NOACTIVATE} is essential: it re-orders the window without taking focus, so the
     * guard never interrupts whatever the user is typing into.
     *
     * <p>Skipped while one of our own dialogs is open, otherwise the widget would jump in front of
     * its own settings window.
     */
    private void reassertTopMost() {
        if (!isAlwaysOnTop() || !isDisplayable() || hasVisibleOwnedWindow()) {
            return;
        }
        if (!Platform.isWindows()) {
            // Other platforms keep always-on-top without this, so just re-apply the flag.
            setAlwaysOnTop(true);
            return;
        }
        try {
            Pointer handle = Native.getWindowPointer(this);
            if (handle == null) {
                return;
            }
            User32.INSTANCE.SetWindowPos(new HWND(handle), HWND_TOPMOST, 0, 0, 0, 0,
                    WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOACTIVATE);
        } catch (Throwable t) {
            // A cosmetic z-order fix must never be able to break the widget.
        }
    }

    /** True while one of our own dialogs is up, so the widget does not jump in front of it. */
    private boolean hasVisibleOwnedWindow() {
        for (java.awt.Window owned : getOwnedWindows()) {
            if (owned.isVisible()) {
                return true;
            }
        }
        return false;
    }

    private void applyOpacity() {
        if (config.getOpacity() >= 1f) {
            // Stay on the plain opaque path; only opt into a layered window when asked.
            return;
        }
        try {
            setOpacity(config.getOpacity());
        } catch (Exception ignored) {
            // Opacity needs translucency support; ignore when unavailable.
        }
    }

    /**
     * Puts the window on the per-pixel translucent path when the picture has see-through pixels.
     *
     * <p>An opaque window has a background colour, so every transparent pixel of the picture is
     * composited onto it — which is why a cut-out PNG used to arrive as a black rectangle. Only a
     * per-pixel translucent window lets the desktop show through where the picture is transparent,
     * and only that path can also blend the soft half-transparent edge of a cut-out properly.
     *
     * <p>The cost is the one {@link #applyShape} warns about: on such a window LCD text
     * antialiasing can emit glyphs with alpha 0. Everything drawn here is therefore antialiased in
     * greyscale — the balance figure in {@link BalanceTextRenderer}, the error line by the same
     * rule — and the window keeps a shape so the rest of its rectangle stays out of the way.
     */
    private void applyPictureTranslucency() {
        boolean want = backgroundImage != null && imageHasAlpha;
        if (want == translucent) {
            return;
        }
        try {
            setBackground(want ? TRANSPARENT : Theme.BG_BOTTOM);
            translucent = want;
            applyOpacity();
        } catch (Exception e) {
            // Some platforms only allow switching this before the window is on screen. Keep the mode
            // we are in and say so on the picture itself — the footer is hidden by then, and a black
            // rectangle with no explanation is worse than a rectangle with one.
            String hint = want
                    ? "\u5f53\u524d\u7cfb\u7edf\u4e0d\u652f\u6301\u7a97\u53e3\u900f\u660e\uff0c\u80cc\u666f\u56fe\u7684\u900f\u660e\u533a\u57df\u4f1a\u663e\u793a\u4e3a\u6df1\u8272"
                    : "\u80cc\u666f\u56fe\u5df2\u66f4\u6362\uff0c\u91cd\u542f\u540e\u751f\u6548";
            statusLabel.setForeground(Theme.WARN);
            statusLabel.setText(hint);
            board.setErrorText(hint);
        }
    }

    /**
     * True when any pixel of the picture is not fully opaque.
     *
     * <p>Sampled rather than scanned: a transparent background covers a large part of the picture,
     * and a full pass over a 24-megapixel photo is not worth it every time one is loaded.
     */
    private static boolean hasTranslucency(BufferedImage image) {
        if (image == null || !image.getColorModel().hasAlpha()) {
            return false;
        }
        int stepX = Math.max(1, image.getWidth() / 400);
        int stepY = Math.max(1, image.getHeight() / 400);
        for (int y = 0; y < image.getHeight(); y += stepY) {
            for (int x = 0; x < image.getWidth(); x += stepX) {
                if ((image.getRGB(x, y) >>> 24) < 250) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Clips the window to its outline.
     *
     * <p>Without a picture that is the app's rounded card. With one the window <em>is</em> the
     * picture: the crop's rectangle with its own four corners, or the traced silhouette — no
     * rounding and no frame, because cutting a corner off the picture or stroking a line around it
     * would make it a picture inside a card again.
     *
     * <p>Note the second reason this exists. A window whose background is left fully transparent
     * would be clipped to nothing on some platforms, and it is also the shape that keeps the
     * app's own pixels out of the picture's business — for a see-through picture the window is
     * additionally put on the per-pixel translucent path, see
     * {@link #applyPictureTranslucency()}.
     *
     * @return true when the shape was applied
     */
    private boolean applyShape() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return false;
        }
        try {
            setShape(windowOutline());
            return true;
        } catch (Exception e) {
            // Not supported here; fall back to square corners rather than showing nothing.
            return false;
        }
    }

    /**
     * The window's outline in its own coordinates.
     *
     * <p>With a picture: the traced silhouette, or the crop's rectangle corner to corner. Without
     * one: the rounded card every other window of this app uses.
     */
    private java.awt.Shape windowOutline() {
        if (backgroundImage != null) {
            if (cropShape != null && !cropShape.isRectangle()) {
                // Same transform as the painting: the outline is normalised to the picture, so it
                // has to travel through wherever the picture is drawn on the card.
                Rectangle image = BackgroundLayout.imageRect(imageCrop,
                        backgroundImage.getWidth(), backgroundImage.getHeight(),
                        getWidth(), getHeight());
                return cropShape.toPath(image);
            }
            return new Rectangle(0, 0, getWidth(), getHeight());
        }
        return new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), ARC, ARC);
    }

    // -------------------------------------------------------------- lifecycle

    public void start() {
        accountLabel.setText(currentKeyLabel());
        refreshTimer.start();
        applyAlwaysOnTop(isAlwaysOnTop());
    }

    /** Footer label: which key is in use, masked, and whether it is remembered. */
    private String currentKeyLabel() {
        String key = client.getApiKey();
        if (key == null || key.trim().isEmpty()) {
            return " ";
        }
        return "API Key " + DeepSeekClient.maskKey(key)
                + (config.hasStoredApiKey() ? "  \u00b7 \u5df2\u8bb0\u4f4f" : "  \u00b7 \u4ec5\u672c\u6b21");
    }

    private void shutdown() {
        refreshTimer.stop();
        topMostGuard.stop();
        if (mouseWatcher != null) {
            try {
                Toolkit.getDefaultToolkit().removeAWTEventListener(mouseWatcher);
            } catch (Exception ignored) {
                // already removed
            }
        }
        config.setBounds(getBounds());
        config.save();
        setVisible(false);
        dispose();
    }

    // --------------------------------------------------------------- refresh

    private void refresh() {
        if (busy) {
            return;
        }
        busy = true;
        refreshButton.setActive(true);
        statusLabel.setForeground(Theme.TEXT_DIM);
        statusLabel.setText("\u6b63\u5728\u5237\u65b0\u2026");

        if (isDemo()) {
            busy = false;
            refreshButton.setActive(false);
            render(demoSnapshot());
            accountLabel.setText("\u6f14\u793a\u6570\u636e\uff08--demo\uff09");
            return;
        }

        new SwingWorker<BalanceSnapshot, Void>() {
            @Override
            protected BalanceSnapshot doInBackground() throws Exception {
                return client.fetchBalance();
            }

            @Override
            protected void done() {
                busy = false;
                refreshButton.setActive(false);
                try {
                    render(get());
                } catch (java.util.concurrent.ExecutionException ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    if (cause instanceof DeepSeekClient.DeepSeekException
                            && ((DeepSeekClient.DeepSeekException) cause).isAuthFailure()) {
                        showError(cause.getMessage());
                        if (authEvents != null) {
                            authEvents.onAuthFailed();
                        }
                    } else {
                        String msg = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                        showError(msg);
                    }
                } catch (Exception ex) {
                    showError(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                }
            }
        }.execute();
    }

    private void render(BalanceSnapshot snap) {
        amountLabel.setText(snap.primarySymbol() + format(snap.totalInPrimaryCurrency()));

        StringBuilder cur = new StringBuilder(snap.primaryCurrency());
        if (snap.getTotalAvailableTokenEstimation() != null
                && !snap.getTotalAvailableTokenEstimation().isEmpty()) {
            cur.append("  \u2248 ").append(compactTokens(snap.getTotalAvailableTokenEstimation()))
                    .append(" tokens");
        }
        currencyLabel.setText(cur.toString());

        extraPanel.removeAll();

        // Collected once: the same lines are shown as extra rows in the normal layout and joined
        // into a single caption inside the framed box in custom-background mode.
        List<String> info = new ArrayList<String>();
        BigDecimal bonus = snap.bonusInPrimaryCurrency();
        if (bonus != null && bonus.signum() > 0) {
            String line = "\u542b\u8d60\u9001 " + snap.primarySymbol() + format(bonus);
            info.add(line);
            addLine(line, Theme.alpha(Theme.GOOD, 210));
        }
        for (Wallet w : snap.secondaryWallets()) {
            String line = w.getSymbol() + format(w.getBalance()) + " " + w.getCurrency()
                    + (w.isBonus() ? "  (\u8d60\u9001)" : "");
            info.add(line);
            addLine(line, w.isBonus() ? Theme.alpha(Theme.GOOD, 210) : Theme.TEXT_DIM);
        }

        extraPanel.revalidate();
        extraPanel.repaint();

        StringBuilder caption = new StringBuilder(currencyLabel.getText().trim());
        for (String line : info) {
            caption.append("  \u00b7  ").append(line);
        }
        regionSubtitle = caption.toString();
        board.setRegionText(amountLabel.getText(), regionSubtitle, config.getBalanceTextColor());

        statusLabel.setForeground(Theme.TEXT_DIM);
        statusLabel.setText("\u66f4\u65b0\u4e8e " + new SimpleDateFormat("HH:mm:ss").format(new Date(snap.getFetchedAtMillis())));
        board.setErrorText("");
        board.repaint();
    }

    private void addLine(String text, Color color) {        JLabel line = new JLabel(text);
        line.setFont(Theme.ui(Font.PLAIN, F_SMALL * config.getFontScale()));
        line.setForeground(color);
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        extraPanel.add(line);
    }

    private void showError(String message) {
        statusLabel.setForeground(Theme.DANGER);
        statusLabel.setText(truncate(message, 60));
        // The footer may be hidden behind a picture, so the same message goes onto the picture.
        board.setErrorText(truncate(message, 70));
    }

    /** True when the widget was started with {@code -Ddstokencheck.demo=true} to preview layout. */
    public static boolean isDemo() {
        return "true".equalsIgnoreCase(System.getProperty("dstokencheck.demo", ""));
    }

    private static BalanceSnapshot demoSnapshot() {
        java.util.List<Wallet> wallets = new java.util.ArrayList<Wallet>();
        wallets.add(new Wallet("CNY", new BigDecimal("87.65"), new BigDecimal("12000000"), false));
        wallets.add(new Wallet("USD", new BigDecimal("3.20"), new BigDecimal("450000"), true));
        return new BalanceSnapshot(wallets, "12450000", null, System.currentTimeMillis(), "demo");
    }

    private static String format(BigDecimal d) {
        if (d == null) {
            return "0.00";
        }
        return d.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Shortens a long token count (12450000 -> "12.45M") so it fits the narrow card. */
    private static String compactTokens(String raw) {
        try {
            BigDecimal d = new BigDecimal(raw.trim());
            BigDecimal million = new BigDecimal("1000000");
            BigDecimal thousand = new BigDecimal("1000");
            if (d.abs().compareTo(million) >= 0) {
                return d.divide(million, 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "M";
            }
            if (d.abs().compareTo(thousand) >= 0) {
                return d.divide(thousand, 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "K";
            }
            return d.toPlainString();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "\u2026";
    }

    private static boolean isOnScreen(Rectangle r) {
        for (java.awt.GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (gd.getDefaultConfiguration().getBounds().intersects(r)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------- move + resize handling

    private AWTEventListener createMouseWatcher() {
        return new AWTEventListener() {
            @Override
            public void eventDispatched(AWTEvent event) {
                if (!(event instanceof MouseEvent)) {
                    return;
                }
                MouseEvent me = (MouseEvent) event;
                if (!isFromThisWindow(me)) {
                    return;
                }
                switch (me.getID()) {
                    case MouseEvent.MOUSE_MOVED:
                        if (!dragging && resizeEdge == NONE) {
                            applyCursor(edgeAt(me.getXOnScreen(), me.getYOnScreen()));
                        }
                        break;
                    case MouseEvent.MOUSE_PRESSED:
                        if (SwingUtilities.isLeftMouseButton(me)) {
                            beginGesture(me);
                        }
                        break;
                    case MouseEvent.MOUSE_DRAGGED:
                        if (dragging || resizeEdge != NONE) {
                            continueGesture(me);
                        }
                        break;
                    case MouseEvent.MOUSE_RELEASED:
                        endGesture();
                        break;
                    default:
                        break;
                }
            }
        };
    }

    private boolean isFromThisWindow(MouseEvent me) {
        Object src = me.getSource();
        if (!(src instanceof Component)) {
            return false;
        }
        Component component = (Component) src;
        // Menu popups are not part of the drag surface. Normally they live in their own window (the
        // menu is shown heavyweight), but if a look and feel falls back to a lightweight popup the
        // items would otherwise be inside this window and every click would start a drag.
        if (component instanceof JPopupMenu
                || SwingUtilities.getAncestorOfClass(JPopupMenu.class, component) != null
                || SwingUtilities.getAncestorOfClass(JMenuBar.class, component) != null) {
            return false;
        }
        return SwingUtilities.getWindowAncestor(component) == this;
    }

    private void beginGesture(MouseEvent me) {
        Object src = me.getSource();
        // Icon buttons handle their own clicks.
        if (src instanceof IconButton) {
            return;
        }
        int edge = edgeAt(me.getXOnScreen(), me.getYOnScreen());
        pressPoint = me.getLocationOnScreen();
        pressBounds = getBounds();
        if (edge != NONE) {
            resizeEdge = edge;
            dragging = false;
        } else {
            dragging = true;
            resizeEdge = NONE;
        }
    }

    private void continueGesture(MouseEvent me) {
        Point now = me.getLocationOnScreen();
        int dx = now.x - pressPoint.x;
        int dy = now.y - pressPoint.y;

        if (dragging) {
            setLocation(pressBounds.x + dx, pressBounds.y + dy);
            return;
        }
        Rectangle b = new Rectangle(pressBounds);
        // Limits follow the font scale, so larger text cannot be squeezed into a clipping window.
        int limitW = minW();
        int limitH = minH();
        if ((resizeEdge & WEST) != 0) {
            int w = pressBounds.width - dx;
            if (w >= limitW) {
                b.x = pressBounds.x + dx;
                b.width = w;
            }
        }
        if ((resizeEdge & EAST) != 0) {
            b.width = Math.max(limitW, pressBounds.width + dx);
        }
        if ((resizeEdge & NORTH) != 0) {
            int h = pressBounds.height - dy;
            if (h >= limitH) {
                b.y = pressBounds.y + dy;
                b.height = h;
            }
        }
        if ((resizeEdge & SOUTH) != 0) {
            b.height = Math.max(limitH, pressBounds.height + dy);
        }
        if (backgroundImage != null) {
            b = lockAspect(b);
        }
        setBounds(b);
    }

    /**
     * Forces a resize to keep the visible picture's proportions.
     *
     * <p>Without this the window's edges would stop matching the crop the moment one side is
     * dragged on its own: the picture would be cut off or squeezed, which is exactly what cropping
     * exists to settle. Whatever edge or corner the user grabbed stays under the pointer.
     */
    private Rectangle lockAspect(Rectangle wanted) {
        double aspect = backgroundAspect();
        int w = Math.max(1, wanted.width);
        int h = Math.max(1, wanted.height);
        boolean horizontal = (resizeEdge & (WEST | EAST)) != 0;
        boolean vertical = (resizeEdge & (NORTH | SOUTH)) != 0;

        if (horizontal && !vertical) {
            h = (int) Math.round(w / aspect);
        } else if (vertical && !horizontal) {
            w = (int) Math.round(h * aspect);
        } else {
            // A corner: follow whichever side the pointer pushed further, relatively.
            double dw = Math.abs(w - pressBounds.width) / (double) Math.max(1, pressBounds.width);
            double dh = Math.abs(h - pressBounds.height) / (double) Math.max(1, pressBounds.height);
            if (dw >= dh) {
                h = (int) Math.round(w / aspect);
            } else {
                w = (int) Math.round(h * aspect);
            }
        }
        if (w < minW() || h < minH()) {
            Rectangle floored = aspectLockedSize(aspect, w, h, Integer.MAX_VALUE, Integer.MAX_VALUE);
            w = floored.width;
            h = floored.height;
        }

        int x = wanted.x;
        int y = wanted.y;
        if ((resizeEdge & WEST) != 0) {
            x = pressBounds.x + pressBounds.width - w;
        } else if ((resizeEdge & EAST) == 0) {
            x = pressBounds.x + (pressBounds.width - w) / 2;
        }
        if ((resizeEdge & NORTH) != 0) {
            y = pressBounds.y + pressBounds.height - h;
        } else if ((resizeEdge & SOUTH) == 0) {
            y = pressBounds.y + (pressBounds.height - h) / 2;
        }
        return new Rectangle(x, y, w, h);
    }

    /**
     * Applies a requested size, keeping the picture's proportions when a background is in use —
     * otherwise the size presets would break the alignment that cropping exists to guarantee.
     */
    private void applySize(int wantedW, int wantedH) {
        if (backgroundImage != null) {
            Rectangle screen = usableScreenBounds();
            Rectangle size = aspectLockedSize(backgroundAspect(), wantedW, wantedH,
                    Math.max(minW(), screen.width), Math.max(minH(), screen.height));
            setSize(size.width, size.height);
            return;
        }
        setSize(Math.max(minW(), Math.max(1, wantedW)), Math.max(minH(), Math.max(1, wantedH)));
    }

    private void endGesture() {
        if (dragging || resizeEdge != NONE) {
            config.setBounds(getBounds());
            config.save();
            // A move can drop the window out of the topmost band, and dragging it over the taskbar
            // is the usual way this bug shows up. Re-seat it as soon as the gesture ends.
            reassertTopMost();
        }
        dragging = false;
        resizeEdge = NONE;
        pressPoint = null;
        pressBounds = null;
    }

    /** Which edge/corner the given screen point grabs, if any. */
    private int edgeAt(int sx, int sy) {
        Rectangle b = getBounds();
        Rectangle outer = new Rectangle(b.x - EDGE, b.y - EDGE, b.width + 2 * EDGE, b.height + 2 * EDGE);
        if (!outer.contains(sx, sy)) {
            return NONE;
        }
        Rectangle inner = new Rectangle(b.x + EDGE, b.y + EDGE, b.width - 2 * EDGE, b.height - 2 * EDGE);
        if (inner.contains(sx, sy)) {
            return NONE;
        }
        int edge = NONE;
        if (sx < b.x + EDGE) {
            edge |= WEST;
        } else if (sx > b.x + b.width - EDGE) {
            edge |= EAST;
        }
        if (sy < b.y + EDGE) {
            edge |= NORTH;
        } else if (sy > b.y + b.height - EDGE) {
            edge |= SOUTH;
        }
        if (edge == NONE) {
            // Inside the padded corner region: treat as that corner.
            edge = (sx < b.x + b.width / 2 ? WEST : EAST) | (sy < b.y + b.height / 2 ? NORTH : SOUTH);
        }
        return edge;
    }

    private void applyCursor(int edge) {
        int type;
        switch (edge) {
            case NORTH:
                type = Cursor.N_RESIZE_CURSOR;
                break;
            case SOUTH:
                type = Cursor.S_RESIZE_CURSOR;
                break;
            case WEST:
                type = Cursor.W_RESIZE_CURSOR;
                break;
            case EAST:
                type = Cursor.E_RESIZE_CURSOR;
                break;
            case NORTH | WEST:
                type = Cursor.NW_RESIZE_CURSOR;
                break;
            case NORTH | EAST:
                type = Cursor.NE_RESIZE_CURSOR;
                break;
            case SOUTH | WEST:
                type = Cursor.SW_RESIZE_CURSOR;
                break;
            case SOUTH | EAST:
                type = Cursor.SE_RESIZE_CURSOR;
                break;
            default:
                type = Cursor.DEFAULT_CURSOR;
                break;
        }
        Cursor want = Cursor.getPredefinedCursor(type);
        if (!want.equals(getCursor())) {
            setCursor(want);
            board.setCursor(want);
        }
    }

    // ------------------------------------------------------------ background

    /**
     * Pulls the background image and its framed region out of the settings and applies them.
     *
     * <p>Called at startup and after every edit. A background that cannot be read is treated as "no
     * background" rather than as an error: the widget still has a balance to show, and the built-in
     * gradient card always works.
     */
    private void applyConfiguredBackground() {
        File file = config.getBackgroundImageFile();
        BufferedImage image = null;
        if (file != null) {
            try {
                image = ImageIO.read(file);
            } catch (IOException | RuntimeException e) {
                image = null;
            }
        }
        backgroundImage = image;
        imageCrop = image == null ? null : config.getImageCrop();
        cropShape = image == null ? null : config.getEffectiveCropShape();
        imageHasAlpha = hasTranslucency(image);
        balanceRegion = image == null ? null : config.getBalanceRegion();
        if (image != null && balanceRegion == null) {
            balanceRegion = AppConfig.defaultBalanceRegion();
        }

        board.setBackgroundImage(image, imageCrop, cropShape, balanceRegion);
        board.setRegionText(amountLabel.getText(), regionSubtitle, config.getBalanceTextColor());
        // The image fills the whole card, so the label column would only cover it; the figure moves
        // into the framed box instead.
        centerPanel.setVisible(image == null);
        updateChromeVisibility();
        applyPictureTranslucency();
        board.revalidate();
        board.repaint();
    }

    /**
     * Shows or hides the title bar and footer.
     *
     * <p>Once a picture is set the window is the picture, so none of the app's own furniture is
     * drawn over it: no title bar, no window buttons, no footer, and none of the edge shading that
     * exists to keep that text readable. What is left is the picture and the balance figure, which
     * is the point of choosing one. Everything else stays in the right-click menu.
     */
    private void updateChromeVisibility() {
        boolean picture = backgroundImage != null;
        titleBarPanel.setVisible(!picture);
        footerPanel.setVisible(!picture);
        board.setChromeVisible(!picture);
    }

    /** Opens the image picker / region framer, and applies the result when it is confirmed. */
    private void openBackgroundDialog() {
        BackgroundRegionDialog.PreviewData preview = new BackgroundRegionDialog.PreviewData(
                titleLabel.getText(), amountLabel.getText(), regionSubtitle,
                accountLabel.getText(), statusLabel.getText());
        BackgroundRegionDialog dialog = new BackgroundRegionDialog(this, config, preview);
        dialog.setVisible(true);
        if (!dialog.isConfirmed()) {
            return;
        }
        applyConfiguredBackground();
        if (backgroundImage != null) {
            // Match the card to the image: the user framed the box on the picture, so the card has
            // to show the picture with the same proportions or the number lands somewhere else.
            fitWindowToImageAspect();
            statusLabel.setForeground(Theme.TEXT_DIM);
            statusLabel.setText("\u5df2\u5e94\u7528\u80cc\u666f\u56fe");
        }
    }

    private void clearBackgroundImage() {
        config.removeBackgroundImage();
        applyConfiguredBackground();
        statusLabel.setForeground(Theme.TEXT_DIM);
        statusLabel.setText("\u5df2\u6062\u590d\u9ed8\u8ba4\u80cc\u666f");
    }

    /**
     * Fills the 预设配置 submenu: saving first, then what the user has saved, then what shipped.
     */
    private void rebuildPresetMenu(JMenu presetMenu) {
        presetMenu.removeAll();

        JMenuItem save = new JMenuItem("\u4fdd\u5b58\u5f53\u524d\u914d\u7f6e\u4e3a\u9884\u8bbe\u2026");
        save.setToolTipText("\u628a\u73b0\u5728\u7684\u80cc\u666f\u56fe\u3001\u88c1\u526a\u3001\u989c\u8272\u4e0e\u5b57\u53f7\u5b58\u6210\u4e00\u4e2a\u9884\u8bbe");
        save.addActionListener(e -> saveCurrentAsPreset());
        presetMenu.add(save);

        List<Preset> mine = Preset.userPresets();
        if (!mine.isEmpty()) {
            presetMenu.add(new JSeparator());
            for (final Preset preset : mine) {
                JMenuItem item = new JMenuItem(preset.getName());
                item.setToolTipText("\u4e00\u952e\u5957\u7528\uff08\u81ea\u5df1\u4fdd\u5b58\u7684\uff09");
                item.addActionListener(e -> applyPreset(preset));
                presetMenu.add(item);
            }
        }

        List<Preset> bundled = Preset.bundled();
        if (!bundled.isEmpty()) {
            presetMenu.add(new JSeparator());
            for (final Preset preset : bundled) {
                JMenuItem item = new JMenuItem(preset.getName());
                item.setToolTipText(preset.getDescription().isEmpty()
                        ? "\u4e00\u952e\u5957\u7528\uff08\u5185\u7f6e\uff09"
                        : preset.getDescription());
                item.addActionListener(e -> applyPreset(preset));
                presetMenu.add(item);
            }
        }

        presetMenu.add(new JSeparator());
        JMenuItem deleteItem = new JMenuItem("\u5220\u9664\u9884\u8bbe\u2026");
        deleteItem.setToolTipText("\u5220\u9664\u81ea\u5df1\u4fdd\u5b58\u7684\u9884\u8bbe\uff08\u5185\u7f6e\u9884\u8bbe\u4e0d\u53ef\u5220\u9664\uff09");
        deleteItem.addActionListener(e -> deletePresetFlow());
        presetMenu.add(deleteItem);

        JMenuItem openFolder = new JMenuItem("\u6253\u5f00\u9884\u8bbe\u6587\u4ef6\u5939\u2026");
        openFolder.setToolTipText("\u81ea\u5df1\u4fdd\u5b58\u7684\u9884\u8bbe\u90fd\u5728\u8fd9\u91cc\uff0c\u53ef\u4ee5\u590d\u5236\u7ed9\u522b\u4eba");
        openFolder.addActionListener(e -> openPresetFolder());
        presetMenu.add(openFolder);

        // The items were just replaced, so they need the app's own look again.
        MenuSkin.apply(presetMenu.getPopupMenu());
    }

    /**
     * Lists the user's own presets so one can be removed.
     *
     * <p>One row per preset, each labelled with the name it deletes: a stray click on a plain
     * "delete" button would be a disaster, but "删除 蓝色大肥鱼" says exactly what it does.
     */
    private void deletePresetFlow() {
        final List<Preset> mine = Preset.userPresets();
        if (mine.isEmpty()) {
            statusLabel.setForeground(Theme.TEXT_DIM);
            statusLabel.setText("\u8fd8\u6ca1\u6709\u81ea\u5df1\u4fdd\u5b58\u7684\u9884\u8bbe");
            return;
        }
        final JDialog dialog = new JDialog(this, "\u5220\u9664\u9884\u8bbe", true);
        dialog.setUndecorated(true);
        dialog.setBackground(Theme.BG_BOTTOM);
        CardPanel card = new CardPanel(18);
        card.setLayout(new BorderLayout());
        card.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 20, 16, 20));

        JPanel rows = new JPanel();
        rows.setOpaque(false);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("\u5220\u9664\u9884\u8bbe");
        title.setFont(Theme.ui(Font.BOLD, 14f));
        title.setForeground(Theme.TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        rows.add(title);
        JLabel hint = new JLabel("\u70b9\u51fb\u5373\u5220\u9664\uff0c\u65e0\u6cd5\u6062\u590d");
        hint.setFont(Theme.ui(Font.PLAIN, 10.5f));
        hint.setForeground(Theme.TEXT_DIM);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        rows.add(Box.createVerticalStrut(4));
        rows.add(hint);
        rows.add(Box.createVerticalStrut(12));
        for (final Preset preset : mine) {
            FlatButton row = new FlatButton("\u5220\u9664\u3000" + preset.getName(), FlatButton.Kind.SECONDARY);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            row.addActionListener(e -> {
                boolean gone = Preset.delete(preset);
                statusLabel.setForeground(gone ? Theme.TEXT_DIM : Theme.DANGER);
                statusLabel.setText(gone
                        ? "\u5df2\u5220\u9664\u9884\u8bbe\uff1a" + preset.getName()
                        : "\u5220\u9664\u5931\u8d25\uff1a" + preset.getName());
                dialog.dispose();
            });
            rows.add(row);
            rows.add(Box.createVerticalStrut(6));
        }
        card.add(rows, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        FlatButton cancel = new FlatButton("\u53d6\u6d88", FlatButton.Kind.SECONDARY);
        cancel.addActionListener(e -> dialog.dispose());
        actions.add(cancel);
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 0, 0, 0));
        south.add(actions, BorderLayout.EAST);
        card.add(south, BorderLayout.SOUTH);

        dialog.setContentPane(card);
        dialog.pack();
        dialog.setSize(Math.max(360, dialog.getWidth()), dialog.getHeight());
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /** Asks for a name and writes the current look out as a preset. */
    private void saveCurrentAsPreset() {
        final List<Preset> existing = Preset.userPresets();
        String suggested = existing.isEmpty() ? "" : existing.get(existing.size() - 1).getName();
        TextPromptDialog dialog = new TextPromptDialog(this,
                "\u4fdd\u5b58\u4e3a\u9884\u8bbe\u914d\u7f6e",
                "\u8f93\u5165\u9884\u8bbe\u914d\u7f6e\u540d",
                suggested,
                text -> {
                    String name = text == null ? "" : text.trim();
                    if (name.isEmpty()) {
                        return null;
                    }
                    if (Preset.exists(name)) {
                        return "\u5df2\u6709\u540c\u540d\u9884\u8bbe\uff0c\u4fdd\u5b58\u4f1a\u8986\u76d6\u5b83";
                    }
                    return "\u4fdd\u5b58\u540e\u53ef\u5728\u53f3\u952e\u83dc\u5355\u300c\u9884\u8bbe\u914d\u7f6e\u300d\u91cc\u627e\u5230";
                });
        dialog.setVisible(true);
        String name = dialog.getValue();
        if (name == null) {
            return;
        }
        boolean overwritten = Preset.exists(name);
        try {
            Preset.save(name, config);
        } catch (Exception e) {
            showError("\u4fdd\u5b58\u9884\u8bbe\u5931\u8d25\uff1a" + e.getMessage());
            return;
        }
        statusLabel.setForeground(Theme.TEXT_DIM);
        statusLabel.setText("\u5df2\u4fdd\u5b58\u9884\u8bbe\uff1a" + name + (overwritten ? "\uff08\u5df2\u8986\u76d6\uff09" : ""));
    }

    /** Opens the preset folder, where user presets are plain folders that can be copied or deleted. */
    private void openPresetFolder() {
        java.io.File folder = Preset.directory();
        if (!folder.isDirectory() && !folder.mkdirs()) {
            showError("\u65e0\u6cd5\u6253\u5f00\u9884\u8bbe\u6587\u4ef6\u5939");
            return;
        }
        try {
            java.awt.Desktop.getDesktop().open(folder);
        } catch (Exception e) {
            showError("\u65e0\u6cd5\u6253\u5f00\u9884\u8bbe\u6587\u4ef6\u5939\uff1a" + folder.getAbsolutePath());
        }
    }

    /**
     * Switches the widget to a saved look in one go: picture, crop, framed box, colour, font scale,
     * card size and the rest of it. Used by the 预设配置 menu and by {@code --preset}.
     */
    public void applyPreset(Preset preset) {
        if (preset == null) {
            return;
        }
        try {
            preset.applyTo(config);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            statusLabel.setForeground(Theme.DANGER);
            statusLabel.setText("\u9884\u8bbe\u5e94\u7528\u5931\u8d25\uff1a" + message);
            // The footer is hidden behind a picture, so the failure has to appear on the picture.
            board.setErrorText("\u9884\u8bbe\u5e94\u7528\u5931\u8d25\uff1a" + message);
            return;
        }
        applyConfiguredBackground();
        Rectangle saved = config.getBounds();
        applySize(saved.width, saved.height);
        applyAlwaysOnTop(config.isAlwaysOnTop());
        applyFonts();
        refreshTimer.setDelay(config.getRefreshSeconds() * 1000);
        refreshTimer.setInitialDelay(config.getRefreshSeconds() * 1000);
        statusLabel.setForeground(Theme.TEXT_DIM);
        statusLabel.setText("\u5df2\u5e94\u7528 " + preset.getName());
    }

    /**
     * Resizes the card to the visible picture's aspect ratio, keeping the width and staying on
     * screen. This is what puts the window's edges on the crop's edges.
     */
    private void fitWindowToImageAspect() {
        if (backgroundImage == null || backgroundImage.getWidth() <= 0) {
            return;
        }
        Rectangle screen = usableScreenBounds();
        Rectangle size = aspectLockedSize(backgroundAspect(), Math.max(minW(), getWidth()), 1,
                Math.max(minW(), (int) (screen.width * 0.9f)),
                Math.max(minH(), (int) (screen.height * 0.85f)));
        setSize(size.width, size.height);
    }

    /**
     * The card's size at a fixed aspect ratio, aiming for {@code wantedW x wantedH}.
     *
     * <p>Floor and ceiling are applied to whichever axis binds and the other is derived, so the
     * proportions survive both — a 4:1 crop must not be pushed into a 2:1 window just because a
     * window is not allowed to be short.
     */
    private Rectangle aspectLockedSize(double aspect, int wantedW, int wantedH,
                                       int ceilingW, int ceilingH) {
        int w = Math.max(1, wantedW);
        int h = Math.max(1, wantedH);
        if (w / aspect >= h) {
            h = (int) Math.round(w / aspect);
        } else {
            w = (int) Math.round(h * aspect);
        }
        if (w < minW()) {
            w = minW();
            h = (int) Math.round(w / aspect);
        }
        if (h < minH()) {
            h = minH();
            w = (int) Math.round(h * aspect);
        }
        if (w > ceilingW) {
            w = ceilingW;
            h = (int) Math.round(w / aspect);
        }
        if (h > ceilingH) {
            h = ceilingH;
            w = (int) Math.round(h * aspect);
        }
        // A ceiling can push the other axis back under the floor on a small screen; the floor wins,
        // since a card too small to read is worse than one that is a little too large.
        if (w < minW()) {
            w = minW();
            h = (int) Math.round(w / aspect);
        }
        return new Rectangle(0, 0, Math.max(1, w), Math.max(1, h));
    }

    /** Width-to-height ratio of the part of the picture the window shows. */
    private double backgroundAspect() {
        if (backgroundImage == null || backgroundImage.getHeight() <= 0) {
            return 1.5;
        }
        return BackgroundLayout.cropAspect(imageCrop,
                backgroundImage.getWidth(), backgroundImage.getHeight());
    }

    private Rectangle usableScreenBounds() {
        GraphicsConfiguration gc = getGraphicsConfiguration();
        if (gc != null) {
            return gc.getBounds();
        }
        return new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
    }

    /** Paints the rounded card behind the labels. */
    private static class BoardPanel extends JPanel {
        private boolean rounded = true;
        private BufferedImage backgroundImage;
        /** Visible part of that image (normalised), or null for all of it. */
        private Rectangle2D.Float imageCrop;
        /** The crop's silhouette; a rectangle unless the user traced an irregular one. */
        private CropShape cropShape;
        /** Normalised 0..1 box on the image where the balance goes; null when not framed yet. */
        private Rectangle2D.Float region;
        private String regionText = "";
        private String regionSubtitle = "";
        private Color regionTextColor = Color.WHITE;
        /** False when the title bar and footer are hidden, which also drops the edge scrims. */
        private boolean chromeVisible = true;
        /** Last failed refresh, shown on the picture when there is no footer to show it in. */
        private String errorText = "";

        void setChromeVisible(boolean visible) {
            this.chromeVisible = visible;
            repaint();
        }

        void setErrorText(String text) {
            this.errorText = text == null ? "" : text;
            repaint();
        }

        void setRounded(boolean rounded) {
            this.rounded = rounded;
        }

        void setBackgroundImage(BufferedImage image, Rectangle2D.Float crop,
                                CropShape shape, Rectangle2D.Float region) {
            this.backgroundImage = image;
            this.imageCrop = crop;
            this.cropShape = shape;
            this.region = region;
            repaint();
        }

        void setRegionText(String amount, String subtitle, Color color) {
            this.regionText = amount == null ? "" : amount;
            this.regionSubtitle = subtitle == null ? "" : subtitle;
            this.regionTextColor = color == null ? Color.WHITE : color;
            repaint();
        }

        /**
         * Where the image lands on the card.
         *
         * <p>The configured crop is scaled to cover the card, so the card's edges are the crop's
         * edges. Cover rather than stretch: if the card has since been left at another aspect ratio
         * the picture is trimmed evenly instead of being squashed.
         */
        private Rectangle imageBounds() {
            int w = Math.max(1, getWidth());
            int h = Math.max(1, getHeight());
            if (backgroundImage == null) {
                return new Rectangle(0, 0, w, h);
            }
            return BackgroundLayout.imageRect(imageCrop,
                    backgroundImage.getWidth(), backgroundImage.getHeight(), w, h);
        }

        /**
         * The framed box in card coordinates.
         *
         * <p>The region is stored relative to the image, so it travels through the same cover
         * transform the image does. Whatever a very different aspect ratio pushes outside the card
         * is trimmed by the shared layout, exactly as it is in the region editor's preview.
         */
        Rectangle2D.Float regionOnCard() {
            if (backgroundImage == null || region == null) {
                return null;
            }
            return BackgroundLayout.regionOn(region, imageBounds(), getWidth(), getHeight());
        }

        @Override
        public void paint(Graphics g) {
            super.paint(g);
            // Drawn after the children: a wide frame can reach under the footer, and the number is
            // the one thing that must never end up hidden.
            paintRegionText(g);
            paintErrorText(g);
        }

        private void paintRegionText(Graphics g) {
            if (regionText.isEmpty()) {
                return;
            }
            Rectangle2D.Float box = regionOnCard();
            if (box == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                BalanceTextRenderer.drawRegion(g2, regionText, regionSubtitle, box, regionTextColor);
            } finally {
                g2.dispose();
            }
        }

        /**
         * Reports a failed refresh on the picture itself.
         *
         * <p>A picture window has no footer to put a message in, and leaving the last good number on
         * screen while the fetches fail would quietly lie about the balance. The chip only appears
         * when something is actually wrong.
         */
        private void paintErrorText(Graphics g) {
            if (chromeVisible || errorText.isEmpty()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Greyscale text on purpose: on a per-pixel translucent window LCD subpixel
                // antialiasing can emit glyphs with alpha 0, which would make this line invisible.
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(Theme.ui(Font.PLAIN, 10.5f));
                FontMetrics fm = g2.getFontMetrics();
                String text = fitText(g2, errorText, Math.max(60, getWidth() - 28));
                int w = fm.stringWidth(text) + 16;
                int h = fm.getHeight() + 6;
                int x = 8;
                int y = Math.max(4, getHeight() - h - 8);
                g2.setColor(new Color(0, 0, 0, 165));
                g2.fill(new RoundRectangle2D.Float(x, y, w, h, 8, 8));
                g2.setColor(Theme.DANGER);
                g2.drawString(text, x + 8, y + 3 + fm.getAscent());
            } finally {
                g2.dispose();
            }
        }

        private static String fitText(Graphics2D g2, String text, int maxWidth) {
            FontMetrics fm = g2.getFontMetrics();
            if (fm.stringWidth(text) <= maxWidth) {
                return text;
            }
            int end = text.length();
            while (end > 1 && fm.stringWidth(text.substring(0, end) + "\u2026") > maxWidth) {
                end--;
            }
            return text.substring(0, end) + "\u2026";
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                int arc = rounded ? 16 : 0;

                java.awt.Shape clip = cardOutline(w, h, arc);
                java.awt.Shape old = g2.getClip();
                // Clipped here as well as on the window: offscreen renders (--shot, the self test)
                // do not go through the window region, and they must look the same as the desktop.
                g2.clip(clip);
                if (backgroundImage != null) {
                    Rectangle img = imageBounds();
                    // Smooth scaling, matching the editor's preview: a photo is usually many times
                    // the size of the card, and the default nearest-neighbour looks shattered.
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(backgroundImage, img.x, img.y, img.width, img.height, null);
                    // Keeps the title bar and footer readable over a bright photo. Nothing to keep
                    // readable when they are hidden, and the scrim would only dull the picture.
                    if (chromeVisible) {
                        Theme.paintEdgeScrim(g2, w, h);
                    }
                } else {
                    g2.setPaint(new GradientPaint(0, 0, Theme.BG_TOP, 0, h, Theme.BG_BOTTOM));
                    g2.fillRect(0, 0, w, h);
                }
                g2.setClip(old);

                // A card draws its own edge; a picture's edge is the window's, and stroking it would
                // put the frame back around a window that is supposed to be the picture.
                if (backgroundImage == null) {
                    g2.setColor(Theme.alpha(Theme.ACCENT, 90));
                    g2.setStroke(new BasicStroke(1f));
                    g2.draw(clip);
                }
            } finally {
                g2.dispose();
            }
        }

        /** The card's silhouette: the crop's outline when it is irregular, else the rounded card. */
        private java.awt.Shape cardOutline(int w, int h, int arc) {
            if (backgroundImage != null) {
                if (cropShape != null && !cropShape.isRectangle()) {
                    // Through the image transform, not stretched to the card: the outline is stored
                    // relative to the whole picture, exactly like the balance region is.
                    return cropShape.toPath(imageBounds());
                }
                return new Rectangle(0, 0, w, h);
            }
            return rounded
                    ? new RoundRectangle2D.Float(0, 0, w - 1, h - 1, arc, arc)
                    : new Rectangle(0, 0, w, h);
        }
    }
}