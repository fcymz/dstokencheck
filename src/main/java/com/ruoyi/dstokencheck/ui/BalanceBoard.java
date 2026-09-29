package com.ruoyi.dstokencheck.ui;

import com.ruoyi.dstokencheck.autostart.AutoStart;
import com.ruoyi.dstokencheck.config.AppConfig;
import com.ruoyi.dstokencheck.model.BalanceSnapshot;
import com.ruoyi.dstokencheck.model.Wallet;
import com.ruoyi.dstokencheck.net.DeepSeekClient;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
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
import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;

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

    private static final int NONE = 0;
    private static final int WEST = 1;
    private static final int EAST = 2;
    private static final int NORTH = 4;
    private static final int SOUTH = 8;

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
    private final IconButton pinButton = new IconButton(IconButton.Glyph.PIN, "\u7f6e\u9876\u5f00\u5173");
    private final IconButton refreshButton = new IconButton(IconButton.Glyph.REFRESH, "\u7acb\u5373\u5237\u65b0");
    private final IconButton closeButton = new IconButton(IconButton.Glyph.CLOSE, "\u9690\u85cf\u7a97\u53e3");

    private final Timer refreshTimer;
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

        // Keep the rounded shape in step with the window as the user resizes it.
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                applyShape();
            }
        });

        setMinimumSize(minimumBoardSize());
        Rectangle saved = config.getBounds();
        setSize(Math.max(minimumBoardSize().width, saved.width),
                Math.max(minimumBoardSize().height, saved.height));
        if (config.hasSavedPosition() && isOnScreen(saved)) {
            setLocation(saved.x, saved.y);
        } else {
            setLocationRelativeTo(null);
        }

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        refreshTimer = new Timer(
                Math.max(AppConfig.MIN_REFRESH_SECONDS, config.getRefreshSeconds()) * 1000, e -> refresh());
        refreshTimer.setInitialDelay(0);

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
        JPanel titleBar = new JPanel(new BorderLayout());
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
        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

        amountLabel.setForeground(Theme.TEXT);
        amountLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        currencyLabel.setForeground(Theme.TEXT_DIM);
        currencyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        extraPanel.setOpaque(false);
        extraPanel.setLayout(new BoxLayout(extraPanel, BoxLayout.Y_AXIS));
        extraPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        center.add(Box.createVerticalGlue());
        center.add(amountLabel);
        center.add(currencyLabel);
        center.add(extraPanel);
        center.add(Box.createVerticalGlue());
        board.add(center, BorderLayout.CENTER);

        // ---- footer ----
        JPanel footer = new JPanel(new GridLayout(2, 1, 0, 1));
        footer.setOpaque(false);
        accountLabel.setForeground(Theme.alpha(Theme.ACCENT_SOFT, 200));
        statusLabel.setForeground(Theme.TEXT_DIM);
        footer.add(accountLabel);
        footer.add(statusLabel);
        board.add(footer, BorderLayout.SOUTH);

        refreshButton.addActionListener(e -> refresh());
        pinButton.addActionListener(e -> toggleAlwaysOnTop());
        closeButton.addActionListener(e -> shutdown());

        applyFonts();

        JPopupMenu menu = buildContextMenu();
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
        return minimumBoardSize().width;
    }

    private int minH() {
        return minimumBoardSize().height;
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

        Rectangle b = getBounds();
        float ratio = now / old;
        setMinimumSize(minimumBoardSize());
        setBounds(b.x, b.y,
                Math.max(minW(), Math.round(b.width * ratio)),
                Math.max(minH(), Math.round(b.height * ratio)));
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
        sizeItem.addActionListener(e -> setSize(Math.max(minW(), 360), Math.max(minH(), 180)));
        menu.add(sizeItem);

        JMenuItem smallItem = new JMenuItem("\u5c0f\u7a97\u53e3");
        smallItem.addActionListener(e -> setSize(minW(), minH()));
        menu.add(smallItem);

        JMenuItem bigItem = new JMenuItem("\u5927\u7a97\u53e3 (420\u00d7220 \u00d7 \u5b57\u4f53)");
        bigItem.addActionListener(e -> setSize(
                Math.max(minW(), Math.round(420 * config.getFontScale())),
                Math.max(minH(), Math.round(220 * config.getFontScale()))));
        menu.add(bigItem);

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
        setAlwaysOnTop(on);
        config.setAlwaysOnTop(on);
        config.save();
        pinButton.setActive(on);
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
     * Clips the window to a rounded rectangle.
     *
     * <p>Deliberately <em>not</em> done with per-pixel translucency
     * ({@code setBackground(new Color(0,0,0,0))}). On Windows a per-pixel translucent window
     * forces Java2D down a path where LCD text antialiasing emits glyphs with alpha 0: every label
     * paints successfully but ends up completely transparent, so the window shows its gradient and
     * any vector-drawn icons while all text silently vanishes. An opaque window clipped by
     * {@code setShape} gives the same rounded look with normal text.
     *
     * @return true when the shape was applied
     */
    private boolean applyShape() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return false;
        }
        try {
            setShape(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), ARC, ARC));
            return true;
        } catch (Exception e) {
            // Not supported here; fall back to square corners rather than showing nothing.
            return false;
        }
    }

    // -------------------------------------------------------------- lifecycle

    public void start() {
        accountLabel.setText(currentKeyLabel());
        refreshTimer.start();
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

        BigDecimal bonus = snap.bonusInPrimaryCurrency();
        if (bonus != null && bonus.signum() > 0) {
            addLine("\u542b\u8d60\u9001 " + snap.primarySymbol() + format(bonus), Theme.alpha(Theme.GOOD, 210));
        }
        for (Wallet w : snap.secondaryWallets()) {
            addLine(w.getSymbol() + format(w.getBalance()) + " " + w.getCurrency()
                    + (w.isBonus() ? "  (\u8d60\u9001)" : ""),
                    w.isBonus() ? Theme.alpha(Theme.GOOD, 210) : Theme.TEXT_DIM);
        }

        extraPanel.revalidate();
        extraPanel.repaint();

        statusLabel.setForeground(Theme.TEXT_DIM);
        statusLabel.setText("\u66f4\u65b0\u4e8e " + new SimpleDateFormat("HH:mm:ss").format(new Date(snap.getFetchedAtMillis())));
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
        setBounds(b);
    }

    private void endGesture() {
        if (dragging || resizeEdge != NONE) {
            config.setBounds(getBounds());
            config.save();
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

    /** Paints the rounded gradient card behind the labels. */
    private static class BoardPanel extends JPanel {
        private boolean rounded = true;

        void setRounded(boolean rounded) {
            this.rounded = rounded;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                int arc = rounded ? 16 : 0;

                java.awt.Shape clip = rounded
                        ? new RoundRectangle2D.Float(0, 0, w - 1, h - 1, arc, arc)
                        : new Rectangle(0, 0, w, h);
                g2.setClip(clip);
                g2.setPaint(new GradientPaint(0, 0, Theme.BG_TOP, 0, h, Theme.BG_BOTTOM));
                g2.fillRect(0, 0, w, h);
                g2.setClip(null);

                if (rounded) {
                    g2.setColor(Theme.alpha(Theme.ACCENT, 90));
                } else {
                    g2.setColor(Theme.BORDER);
                }
                g2.setStroke(new BasicStroke(1f));
                g2.draw(clip);
            } finally {
                g2.dispose();
            }
        }
    }
}
