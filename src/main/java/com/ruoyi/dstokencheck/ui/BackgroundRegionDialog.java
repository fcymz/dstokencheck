package com.ruoyi.dstokencheck.ui;

import com.ruoyi.dstokencheck.config.AppConfig;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Picks a background image and frames the area the balance occupies.
 *
 * <p>Deliberately a full editor rather than a file dialog plus a number: the point of the feature is
 * that the figure lands exactly where the user wants it, so the dialog shows the picture, the box,
 * the figure rendered inside that box, and a miniature of the finished card side by side. What is
 * framed here is what appears on the desktop.
 *
 * <p>Interaction: drag empty space to draw a box, drag inside it to move it, drag any of the eight
 * handles to resize, arrow keys to nudge (Shift for bigger steps), Ctrl + wheel to zoom the canvas.
 *
 * <p>Only edits configuration; the caller reloads the widget afterwards.
 */
public class BackgroundRegionDialog extends JDialog {

    private static final int ARC = 18;
    private static final int SIDEBAR_W = 276;
    private static final int PREF_W = 1010;
    private static final int PREF_H = 706;
    private static final int CANVAS_ARC = 12;
    /** Drawn size of a region handle, and how close the pointer has to get to grab it. */
    private static final int HANDLE = 9;
    private static final int GRAB = 13;
    /** A region can never be smaller than this, in canvas pixels. */
    private static final int MIN_REGION_PX = 16;
    /** "适应窗口" stops here, so a 32×32 icon is not blown up into a blurry wall. */
    private static final double MAX_FIT_SCALE = 4.0;
    private static final double MIN_ZOOM = 0.1;
    private static final double MAX_ZOOM = 6.0;
    private static final int PREVIEW_W = 244;
    private static final int PREVIEW_H = 150;
    /** Longest side of the copy the canvas paints from; the original stays for all geometry. */
    private static final int DISPLAY_MAX_SIDE = 1600;

    /** One-click colours; the field below the chips accepts any other value. */
    private static final int[] PRESET_COLORS = {
        0xFFFFFF, 0x101820, 0xE9EEF8, 0x54A0FF, 0x60C8FF, 0x58D68D,
        0xF5B24A, 0xF06868, 0xA78BFA, 0xFFC107, 0x2EE6C5, 0xFF8AC0,
    };

    /**
     * The text the widget is showing right now.
     *
     * <p>Passed in rather than invented here, so the miniature is the real card — including the
     * currency line and the footer — instead of an approximation the user has to translate.
     */
    public static final class PreviewData {
        private final String title;
        private final String amount;
        private final String subtitle;
        private final String account;
        private final String status;

        public PreviewData(String title, String amount, String subtitle,
                           String account, String status) {
            this.title = orEmpty(title);
            this.amount = orEmpty(amount);
            this.subtitle = orEmpty(subtitle);
            this.account = orEmpty(account);
            this.status = orEmpty(status);
        }

        private static String orEmpty(String s) {
            return s == null ? "" : s;
        }
    }

    private final AppConfig config;
    private final PreviewData preview;
    /** Image name configured when the dialog opened, restored if the user cancels. */
    private final String originalImageName;
    private final CardPanel card;

    private final WidgetPreview previewPanel = new WidgetPreview();
    private final JLabel readoutLabel = new JLabel(" ");
    private final JLabel sizeLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel zoomLabel = new JLabel(" ");
    private final CurrentSwatch currentSwatch = new CurrentSwatch();
    private final JTextField hexField = new JTextField();
    private final List<ColorChip> chips = new ArrayList<ColorChip>();
    private final FlatButton fitButton = new FlatButton("\u9002\u5e94\u7a97\u53e3", FlatButton.Kind.SECONDARY);
    private final FlatButton oneToOneButton = new FlatButton("1:1", FlatButton.Kind.SECONDARY);
    private final FlatButton resetButton = new FlatButton("\u91cd\u7f6e\u533a\u57df", FlatButton.Kind.SECONDARY);
    private final FlatButton saveButton =
            new FlatButton("\u4fdd\u5b58\u5e76\u5e94\u7528", FlatButton.Kind.PRIMARY);

    private ImageCanvas canvas;
    private JScrollPane scrollPane;

    private Color textColor;
    private boolean confirmed;
    private Point dragOrigin;
    private Point windowOrigin;

    public BackgroundRegionDialog(Frame owner, AppConfig config, PreviewData preview) {
        super(owner, "\u81ea\u5b9a\u4e49\u80cc\u666f\u56fe", true);
        this.config = config;
        this.preview = preview == null ? new PreviewData("", "", "", "", "") : preview;
        this.originalImageName = config.getBackgroundImageName();
        this.textColor = config.getBalanceTextColor();

        BufferedImage image = loadConfiguredImage();
        Rectangle2D.Float region = config.getBalanceRegion();
        if (image != null && region == null) {
            region = AppConfig.defaultBalanceRegion();
        }
        canvas = new ImageCanvas(image, region);

        setUndecorated(true);
        setBackground(Theme.BG_BOTTOM);
        card = new CardPanel(ARC);
        setContentPane(card);

        buildUi();
        installEscape();
        updateColorControls();
        updateZoomControls();
        updateReadout();
        updateEnabledState();
        setStatus(image == null
                ? "\u5148\u9009\u4e00\u5f20\u80cc\u666f\u56fe"
                : "\u5728\u56fe\u4e0a\u62d6\u62fd\u6846\u51fa\u4f59\u989d\u663e\u793a\u533a\u57df", false);
        sizeAndCentre(owner);

        // The widget is always-on-top, so this window has to be too or it opens behind it.
        setAlwaysOnTop(true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (!confirmed) {
                    rollbackImport();
                }
            }
        });
        SwingUtilities.invokeLater(canvas::requestFocusInWindow);
    }

    /** True when the user confirmed; the config has already been written. */
    public boolean isConfirmed() {
        return confirmed;
    }

    // ------------------------------------------------------------------- shell

    private void sizeAndCentre(Frame owner) {
        Rectangle screen = screenBounds(owner);
        int w = Math.min(PREF_W, Math.round(screen.width * 0.94f));
        int h = Math.min(PREF_H, Math.round(screen.height * 0.94f));
        setSize(w, h);
        setLocation(screen.x + (screen.width - w) / 2, screen.y + (screen.height - h) / 2);
    }

    /**
     * The usable area of the screen the widget is on.
     *
     * <p>Centring on the owner would push a window this size off a small screen, and a fixed
     * "primary screen" would put it on the wrong monitor for anyone working on a second display.
     */
    private Rectangle screenBounds(Frame owner) {
        GraphicsConfiguration gc = owner != null ? owner.getGraphicsConfiguration() : null;
        if (gc == null) {
            gc = getGraphicsConfiguration();
        }
        if (gc == null) {
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        }
        Rectangle bounds = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(bounds.x + in.left, bounds.y + in.top,
                bounds.width - in.left - in.right, bounds.height - in.top - in.bottom);
    }

    private void buildUi() {
        card.setLayout(new BorderLayout(0, 0));
        card.setBorder(BorderFactory.createEmptyBorder(14, 18, 16, 18));
        card.add(buildTitleRow(), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(16, 0));
        body.setOpaque(false);
        body.add(buildCanvasColumn(), BorderLayout.CENTER);
        body.add(buildSidebar(), BorderLayout.EAST);
        card.add(body, BorderLayout.CENTER);

        card.add(buildFooter(), BorderLayout.SOUTH);
    }

    private JPanel buildTitleRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(0, 2, 14, 0));

        JPanel headings = new JPanel();
        headings.setOpaque(false);
        headings.setLayout(new BoxLayout(headings, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("\u81ea\u5b9a\u4e49\u80cc\u666f\u56fe");
        title.setFont(Theme.ui(Font.BOLD, 15f));
        title.setForeground(Theme.TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel subtitle = new JLabel("\u9009\u4e00\u5f20\u56fe\u7247\uff0c\u5728\u56fe\u4e0a\u6846\u51fa\u4f59\u989d\u8981\u663e\u793a\u7684\u4f4d\u7f6e");
        subtitle.setFont(Theme.ui(Font.PLAIN, 10.5f));
        subtitle.setForeground(Theme.TEXT_DIM);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        headings.add(title);
        headings.add(Box.createVerticalStrut(3));
        headings.add(subtitle);
        row.add(headings, BorderLayout.WEST);

        IconButton close = new IconButton(IconButton.Glyph.CLOSE, "\u53d6\u6d88 (Esc)");
        close.addActionListener(e -> onCancel());
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(close);
        row.add(right, BorderLayout.EAST);

        // The whole title row doubles as the drag handle: the window has no system title bar.
        installDrag(row);
        installDrag(headings);
        installDrag(title);
        installDrag(subtitle);
        return row;
    }

    private JPanel buildCanvasColumn() {
        JPanel column = new JPanel(new BorderLayout(0, 9));
        column.setOpaque(false);
        column.add(buildToolbar(), BorderLayout.NORTH);

        scrollPane = new JScrollPane(canvas);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(18);
        styleScrollBar(scrollPane.getVerticalScrollBar());
        styleScrollBar(scrollPane.getHorizontalScrollBar());
        column.add(scrollPane, BorderLayout.CENTER);

        JPanel readout = new JPanel(new BorderLayout(10, 0));
        readout.setOpaque(false);
        // UI font, not the monospaced one: the labels are Chinese and Consolas has no CJK glyphs.
        readoutLabel.setFont(Theme.ui(Font.PLAIN, 10.5f));
        readoutLabel.setForeground(Theme.TEXT_DIM);
        sizeLabel.setFont(Theme.ui(Font.PLAIN, 10.5f));
        sizeLabel.setForeground(Theme.alpha(Theme.ACCENT_SOFT, 210));
        readout.add(readoutLabel, BorderLayout.WEST);
        readout.add(sizeLabel, BorderLayout.EAST);
        column.add(readout, BorderLayout.SOUTH);
        return column;
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setOpaque(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);

        FlatButton choose = new FlatButton("\u9009\u62e9\u56fe\u7247\u2026", FlatButton.Kind.PRIMARY);
        choose.setIcon(new IconButton.GlyphIcon(IconButton.Glyph.IMAGE, 15));
        choose.setIconTextGap(8);
        choose.addActionListener(e -> chooseImage());
        left.add(choose);

        resetButton.addActionListener(e -> {
            canvas.setRegion(AppConfig.defaultBalanceRegion());
            canvas.requestFocusInWindow();
        });
        left.add(resetButton);
        fitButton.addActionListener(e -> {
            canvas.setFit(true);
            canvas.requestFocusInWindow();
        });
        left.add(fitButton);

        oneToOneButton.addActionListener(e -> {
            canvas.setZoom(1.0);
            canvas.requestFocusInWindow();
        });
        left.add(oneToOneButton);
        bar.add(left, BorderLayout.WEST);

        zoomLabel.setFont(Theme.ui(Font.PLAIN, 10.5f));
        zoomLabel.setForeground(Theme.TEXT_DIM);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 4));
        right.setOpaque(false);
        right.add(zoomLabel);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JPanel buildSidebar() {
        JPanel side = new JPanel();
        side.setOpaque(false);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setPreferredSize(new Dimension(SIDEBAR_W, 10));

        JPanel previewSection = section("\u5c0f\u7a97\u53e3\u9884\u89c8");
        previewSection.add(previewPanel);
        previewSection.add(Box.createVerticalStrut(7));
        previewSection.add(hint("\u5b9e\u9645\u6548\u679c\uff08\u4fdd\u5b58\u540e\u7a97\u53e3\u4f1a\u6309\u56fe\u7247\u6bd4\u4f8b\u8c03\u6574\uff09"));
        side.add(previewSection);

        side.add(Box.createVerticalStrut(12));
        side.add(buildColorSection());
        side.add(Box.createVerticalStrut(12));
        side.add(buildTipsSection());
        side.add(Box.createVerticalGlue());
        return side;
    }

    private JPanel buildColorSection() {
        JPanel section = section("\u4f59\u989d\u6587\u5b57\u989c\u8272");

        JPanel grid = new JPanel(new GridLayout(2, 6, 6, 6));
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Capping the width is what keeps the chips square: GridLayout stretches its cells, and a
        // BoxLayout column would otherwise hand it the whole sidebar.
        Dimension gridSize = new Dimension(6 * 24 + 5 * 6, 2 * 24 + 6);
        grid.setPreferredSize(gridSize);
        grid.setMaximumSize(gridSize);
        for (int rgb : PRESET_COLORS) {
            ColorChip chip = new ColorChip(rgb);
            chips.add(chip);
            grid.add(chip);
        }
        section.add(grid);

        section.add(Box.createVerticalStrut(10));
        JPanel custom = new JPanel(new BorderLayout(8, 0));
        custom.setOpaque(false);
        custom.setAlignmentX(Component.LEFT_ALIGNMENT);
        custom.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        currentSwatch.setPreferredSize(new Dimension(26, 26));
        custom.add(currentSwatch, BorderLayout.WEST);

        hexField.setFont(Theme.mono(Font.PLAIN, 11.5f));
        hexField.setForeground(Theme.TEXT);
        hexField.setCaretColor(Theme.ACCENT_SOFT);
        hexField.setBackground(Theme.FIELD_BG);
        hexField.setOpaque(true);
        hexField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER, 1),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        hexField.setToolTipText("\u4efb\u610f\u989c\u8272\uff0c\u683c\u5f0f #RRGGBB");
        hexField.addActionListener(e -> applyHexField());
        hexField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                applyHexField();
            }
        });
        custom.add(hexField, BorderLayout.CENTER);
        section.add(custom);
        return section;
    }

    private JPanel buildTipsSection() {
        JPanel section = section("\u64cd\u4f5c\u63d0\u793a");
        for (String tip : new String[]{
            "\u62d6\u62fd\u7a7a\u767d\u5904 \u2192 \u65b0\u5efa\u533a\u57df",
            "\u62d6\u62fd\u6846\u5185 \u2192 \u79fb\u52a8\u533a\u57df",
            "\u62d6\u62fd\u516b\u4e2a\u5c0f\u65b9\u5757 \u2192 \u7f29\u653e\u533a\u57df",
            "\u65b9\u5411\u952e\u5fae\u8c03\uff0cShift \u52a0\u901f",
            "Ctrl + \u6eda\u8f6e \u2192 \u7f29\u653e\u753b\u5e03",
        }) {
            section.add(hint("\u2022  " + tip));
            section.add(Box.createVerticalStrut(4));
        }
        return section;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(14, 2, 0, 0));

        statusLabel.setFont(Theme.ui(Font.PLAIN, 10.5f));
        statusLabel.setForeground(Theme.TEXT_DIM);
        footer.add(statusLabel, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);

        FlatButton cancel = new FlatButton("\u53d6\u6d88", FlatButton.Kind.SECONDARY);
        cancel.addActionListener(e -> onCancel());
        actions.add(cancel);

        saveButton.addActionListener(e -> onConfirm());
        actions.add(saveButton);
        footer.add(actions, BorderLayout.EAST);

        getRootPane().setDefaultButton(saveButton);
        return footer;
    }

    /** A raised sidebar block with a heading. */
    private JPanel section(String heading) {
        Surface surface = new Surface(12, Theme.SURFACE);
        surface.setLayout(new BoxLayout(surface, BoxLayout.Y_AXIS));
        surface.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
        surface.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel label = new JLabel(heading);
        label.setFont(Theme.ui(Font.BOLD, 11f));
        label.setForeground(Theme.TEXT_DIM);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        surface.add(label);
        surface.add(Box.createVerticalStrut(9));
        return surface;
    }

    private JLabel hint(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.ui(Font.PLAIN, 9.5f));
        label.setForeground(Theme.alpha(Theme.TEXT_DIM, 215));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private void installEscape() {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                onCancel();
            }
        });
    }

    private void styleScrollBar(JScrollBar bar) {
        bar.setUI(new ThinScrollBarUI());
        bar.setPreferredSize(new Dimension(10, 10));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder());
    }

    private void installDrag(JComponent c) {
        c.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragOrigin = e.getLocationOnScreen();
                windowOrigin = getLocation();
            }
        });
        c.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragOrigin == null || windowOrigin == null) {
                    return;
                }
                Point now = e.getLocationOnScreen();
                setLocation(windowOrigin.x + (now.x - dragOrigin.x),
                        windowOrigin.y + (now.y - dragOrigin.y));
            }
        });
    }

    // ----------------------------------------------------------------- actions

    private void chooseImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("\u9009\u62e9\u80cc\u666f\u56fe\u7247");
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "\u56fe\u7247 (*.png, *.jpg, *.jpeg, *.gif, *.bmp)", "png", "jpg", "jpeg", "gif", "bmp"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            canvas.requestFocusInWindow();
            return;
        }
        File source = chooser.getSelectedFile();
        try {
            // Copying into the config directory is also how the file gets validated.
            config.storeBackgroundImage(source);
        } catch (IOException ex) {
            setStatus("\u65e0\u6cd5\u8bfb\u53d6\u8be5\u56fe\u7247\uff1a" + ex.getMessage(), true);
            return;
        }
        canvas.setImage(loadConfiguredImage());
        canvas.setRegion(AppConfig.defaultBalanceRegion());
        canvas.setFit(true);
        previewPanel.revalidate();
        previewPanel.repaint();
        statusLabel.setForeground(Theme.TEXT_DIM);
        setStatus("\u5df2\u5bfc\u5165 " + source.getName() + "\uff08\u5df2\u590d\u5236\u5230\u914d\u7f6e\u76ee\u5f55\uff0c\u539f\u56fe\u53ef\u5220\uff09", false);
        updateEnabledState();
        updateZoomControls();
        canvas.requestFocusInWindow();
    }

    private void applyColor(Color color) {
        if (color == null) {
            return;
        }
        textColor = new Color(color.getRed(), color.getGreen(), color.getBlue());
        updateColorControls();
        canvas.repaint();
        previewPanel.repaint();
        setStatus("\u6587\u5b57\u989c\u8272\u5df2\u66f4\u65b0", false);
    }

    private void applyHexField() {
        String raw = hexField.getText().trim();
        if (raw.startsWith("#")) {
            raw = raw.substring(1);
        }
        try {
            if (raw.length() != 6) {
                throw new NumberFormatException("length");
            }
            applyColor(new Color(Integer.parseInt(raw, 16)));
        } catch (NumberFormatException e) {
            setStatus("\u989c\u8272\u683c\u5f0f\u5e94\u4e3a #RRGGBB", true);
            updateColorControls();
        }
    }

    private void onConfirm() {
        if (canvas.getImage() == null) {
            setStatus("\u8bf7\u5148\u9009\u62e9\u4e00\u5f20\u56fe\u7247", true);
            return;
        }
        Rectangle2D.Float region = canvas.getRegion();
        if (region == null || region.width <= 0.01f || region.height <= 0.01f) {
            setStatus("\u8bf7\u5148\u5728\u56fe\u7247\u4e0a\u62d6\u62fd\u6846\u51fa\u663e\u793a\u533a\u57df", true);
            return;
        }
        config.setBalanceRegion(region);
        config.setBalanceTextColor(textColor);
        config.save();
        // Every import from this session except the chosen one is now unreachable: drop it.
        config.pruneBackgroundImages(config.getBackgroundImageFile());
        confirmed = true;
        dispose();
    }

    private void onCancel() {
        confirmed = false;
        rollbackImport();
        dispose();
    }

    /**
     * Undoes everything this dialog wrote to the config directory.
     *
     * <p>Imports are copied in as soon as they are picked, so the canvas has something real to show.
     * A cancelled dialog must therefore put the directory back exactly as it found it — otherwise
     * every abandoned attempt would leave another copy of the image behind.
     */
    private void rollbackImport() {
        config.restoreBackgroundImageName(originalImageName);
        config.pruneBackgroundImages(config.getBackgroundImageFile());
    }

    // ------------------------------------------------------------- view state

    private void setStatus(String message, boolean error) {
        statusLabel.setForeground(error ? Theme.DANGER : Theme.TEXT_DIM);
        statusLabel.setText(message == null || message.isEmpty() ? " " : message);
    }

    private void updateEnabledState() {
        boolean hasImage = canvas.getImage() != null;
        saveButton.setEnabled(hasImage);
        resetButton.setEnabled(hasImage);
        fitButton.setEnabled(hasImage);
        oneToOneButton.setEnabled(hasImage);
    }

    private void updateZoomControls() {
        boolean fit = canvas.isFit();
        fitButton.setActive(fit);
        oneToOneButton.setActive(!fit && Math.abs(canvas.getZoom() - 1.0) < 0.005);
        // Before the first layout the canvas has no size, so the "fit" scale is not knowable yet;
        // the canvas reports back once it has been laid out.
        int percent = Math.max(1, (int) Math.round(canvas.effectiveScale() * 100));
        zoomLabel.setText((fit ? "\u9002\u5e94\u7a97\u53e3 " : "") + percent + "%");
    }

    private void updateReadout() {
        Rectangle2D.Float r = canvas.getRegion();
        if (r == null) {
            readoutLabel.setText(" ");
            sizeLabel.setText(" ");
            return;
        }
        readoutLabel.setText(String.format(Locale.ROOT,
                "\u533a\u57df  \u5bbd %.0f%%  \u9ad8 %.0f%%  \u5de6 %.0f%%  \u4e0a %.0f%%",
                r.width * 100, r.height * 100, r.x * 100, r.y * 100));

        BufferedImage image = canvas.getImage();
        if (image == null) {
            sizeLabel.setText(" ");
            return;
        }
        Rectangle widget = config.getBounds();
        double widgetW = Math.max(160, widget.width);
        double widgetH = widgetW * image.getHeight() / (double) image.getWidth();
        sizeLabel.setText(String.format(Locale.ROOT,
                "\u5c0f\u7a97\u53e3\u5185\u7ea6 %.0f \u00d7 %.0f \u50cf\u7d20",
                r.width * widgetW, r.height * widgetH));
    }

    private void updateColorControls() {
        for (ColorChip chip : chips) {
            chip.repaint();
        }
        currentSwatch.repaint();
        // Never fight the user for the caret: only overwrite the field when it is not being typed in.
        if (!hexField.hasFocus()) {
            hexField.setText(String.format(Locale.ROOT, "#%06X", textColor.getRGB() & 0xFFFFFF));
        }
    }

    private BufferedImage loadConfiguredImage() {
        File file = config.getBackgroundImageFile();
        if (file == null) {
            return null;
        }
        try {
            return ImageIO.read(file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------- components

    /** A raised block inside the card; the sidebar is built from these. */
    private static class Surface extends JPanel {

        private final int arc;
        private final Color fill;

        Surface(int arc, Color fill) {
            this.arc = arc;
            this.fill = fill;
            setOpaque(false);
        }

        @Override
        public Dimension getMaximumSize() {
            // BoxLayout would otherwise stretch every section to fill the sidebar's height.
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Shape shape = new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                g2.setColor(fill);
                g2.fill(shape);
                g2.setColor(Theme.alpha(Theme.BORDER, 150));
                g2.draw(shape);
            } finally {
                g2.dispose();
            }
        }
    }

    /** One preset colour. The chosen one keeps an accent ring so the current value is obvious. */
    private class ColorChip extends JButton {

        private final Color color;
        private boolean hover;

        ColorChip(int rgb) {
            this.color = new Color(rgb);
            setPreferredSize(new Dimension(24, 24));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(String.format(Locale.ROOT, "#%06X", rgb));
            addActionListener(e -> applyColor(color));
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

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean current = (color.getRGB() & 0xFFFFFF) == (textColor.getRGB() & 0xFFFFFF);
                Shape shape = new RoundRectangle2D.Float(1, 1, getWidth() - 3, getHeight() - 3, 8, 8);
                g2.setColor(color);
                g2.fill(shape);
                if (current) {
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(2f));
                    g2.draw(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 9, 9));
                }
                g2.setColor(current ? Theme.ACCENT_SOFT
                        : Theme.alpha(hover ? Theme.TEXT : Theme.BORDER, hover ? 220 : 170));
                g2.setStroke(new BasicStroke(1.4f));
                g2.draw(shape);
            } finally {
                g2.dispose();
            }
        }
    }

    /** Shows the colour currently in use, next to the hex field. */
    private class CurrentSwatch extends JPanel {

        CurrentSwatch() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Shape shape = new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g2.setColor(textColor);
                g2.fill(shape);
                g2.setColor(Theme.alpha(Theme.BORDER, 200));
                g2.draw(shape);
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * A miniature of the finished widget.
     *
     * <p>Uses the same mapping and the same renderer as the real card, so it cannot drift away from
     * what the desktop will show. The card is drawn at the image's aspect ratio inside a fixed box,
     * which is exactly what applying a background does to the window.
     */
    private class WidgetPreview extends JPanel {

        WidgetPreview() {
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(PREVIEW_W, PREVIEW_H);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, PREVIEW_H);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (canvas == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                BufferedImage image = canvas.getImage();
                double aspect = image == null ? 2.0 : image.getWidth() / (double) image.getHeight();
                int w = getWidth();
                int h = (int) Math.round(w / Math.max(0.25, aspect));
                if (h > getHeight()) {
                    h = getHeight();
                    w = (int) Math.round(h * aspect);
                }
                w = Math.max(40, w);
                h = Math.max(28, h);
                int x = (getWidth() - w) / 2;
                int y = (getHeight() - h) / 2;

                Shape cardShape = new RoundRectangle2D.Float(x, y, w - 1, h - 1, 14, 14);
                Shape old = g2.getClip();
                g2.clip(cardShape);
                g2.translate(x, y);

                if (image != null) {
                    Rectangle cover = BackgroundLayout.coverRect(image.getWidth(), image.getHeight(), w, h);
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(canvas.displayImage(), cover.x, cover.y, cover.width, cover.height, null);
                    Theme.paintEdgeScrim(g2, w, h);
                } else {
                    g2.setPaint(new GradientPaint(0, 0, Theme.BG_TOP, 0, h, Theme.BG_BOTTOM));
                    g2.fillRect(0, 0, w, h);
                }

                paintChrome(g2, w, h);

                Rectangle2D.Float region = canvas.getRegion();
                if (image != null && region != null) {
                    Rectangle cover = BackgroundLayout.coverRect(image.getWidth(), image.getHeight(), w, h);
                    Rectangle2D.Float box = BackgroundLayout.regionOn(region, cover, w, h);
                    if (box != null) {
                        BalanceTextRenderer.drawRegion(g2, preview.amount, preview.subtitle, box, textColor);
                    }
                }
                g2.translate(-x, -y);
                g2.setClip(old);

                g2.setColor(Theme.alpha(Theme.ACCENT, 110));
                g2.draw(cardShape);
            } finally {
                g2.dispose();
            }
        }

        /** Stands in for the widget's own title bar and footer, at miniature scale. */
        private void paintChrome(Graphics2D g2, int w, int h) {
            int pad = Math.max(7, w / 26);
            if (!preview.title.isEmpty()) {
                g2.setFont(Theme.ui(Font.PLAIN, 7.5f));
                g2.setColor(Theme.alpha(Theme.TEXT_DIM, 235));
                g2.drawString(preview.title, pad, pad + 8);
            }
            // Three dots where the widget's pin / refresh / close buttons sit.
            g2.setColor(Theme.alpha(Theme.ACCENT_SOFT, 170));
            for (int i = 0; i < 3; i++) {
                int cx = w - pad - 5 - i * 11;
                g2.fillOval(cx - 2, pad + 2, 4, 4);
            }
            if (!preview.account.isEmpty()) {
                g2.setFont(Theme.ui(Font.PLAIN, 7f));
                g2.setColor(Theme.alpha(Theme.ACCENT_SOFT, 200));
                g2.drawString(clip(g2, preview.account, w - 2 * pad), pad, h - pad - 8);
            }
            if (!preview.status.isEmpty()) {
                g2.setFont(Theme.ui(Font.PLAIN, 7f));
                g2.setColor(Theme.alpha(Theme.TEXT_DIM, 225));
                g2.drawString(clip(g2, preview.status, w - 2 * pad), pad, h - pad + 2);
            }
        }
    }

    private static String clip(Graphics2D g2, String text, int maxWidth) {
        FontMetrics fm = g2.getFontMetrics();
        if (fm.stringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "\u2026";
        int end = text.length();
        while (end > 1 && fm.stringWidth(text.substring(0, end) + ellipsis) > maxWidth) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }

    /**
     * The picture with the framed box on top.
     *
     * <p>Works in canvas coordinates and stores the region normalised to the image, so the setting
     * survives any window size, any zoom and any later aspect change.
     */
    private class ImageCanvas extends JPanel implements Scrollable {

        private static final int NONE = 0;
        private static final int CREATE = 1;
        private static final int MOVE = 2;
        private static final int RESIZE = 3;

        private BufferedImage image;
        /** Downscaled copy used for painting; a 24-megapixel photo is re-scaled on every drag. */
        private BufferedImage displayImage;
        /** Normalised 0..1 region on the image. */
        private Rectangle2D.Float region;
        private boolean fit = true;
        private double zoom = 1.0;

        private int mode = NONE;
        private int handle = -1;
        private int hoverHandle = -1;
        private Point dragStart;
        private Rectangle2D.Float dragStartRegion;

        ImageCanvas(BufferedImage image, Rectangle2D.Float region) {
            this.image = image;
            this.displayImage = downscale(image);
            this.region = region;
            setOpaque(false);
            setFocusable(true);
            updateCursorForMode();
            installMouse();
            installKeys();
            // "适应窗口" is a percentage of this component, so it changes as the dialog is laid out.
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    updateZoomControls();
                }
            });
        }

        BufferedImage getImage() {
            return image;
        }

        /** The cached, screen-sized copy every painter should use. */
        BufferedImage displayImage() {
            return displayImage == null ? image : displayImage;
        }

        void setImage(BufferedImage image) {
            this.image = image;
            this.displayImage = downscale(image);
            updateCursorForMode();
            revalidate();
            repaint();
        }

        /** Crosshair while framing; a hand while the canvas is still the "choose a picture" prompt. */
        private void updateCursorForMode() {
            setCursor(Cursor.getPredefinedCursor(image == null
                    ? Cursor.HAND_CURSOR : Cursor.CROSSHAIR_CURSOR));
        }

        Rectangle2D.Float getRegion() {
            return region;
        }

        void setRegion(Rectangle2D.Float region) {
            this.region = region;
            regionChanged();
        }

        boolean isFit() {
            return fit;
        }

        double getZoom() {
            return zoom;
        }

        void setFit(boolean fit) {
            this.fit = fit;
            revalidate();
            repaint();
            updateZoomControls();
        }

        void setZoom(double z) {
            this.fit = false;
            this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
            revalidate();
            repaint();
            updateZoomControls();
        }

        /** Current pixels-per-image-pixel, whatever the mode. */
        double effectiveScale() {
            if (image == null) {
                return 1.0;
            }
            if (!fit) {
                return zoom;
            }
            double s = BackgroundLayout.containScale(image.getWidth(), image.getHeight(),
                    Math.max(1, getWidth()), Math.max(1, getHeight()));
            return Math.min(s, MAX_FIT_SCALE);
        }

        /** Where the picture is drawn: contained and centred, so the whole frame stays visible. */
        Rectangle imageRect() {
            if (image == null) {
                return new Rectangle(0, 0, Math.max(1, getWidth()), Math.max(1, getHeight()));
            }
            return BackgroundLayout.centred(image.getWidth(), image.getHeight(), effectiveScale(),
                    Math.max(1, getWidth()), Math.max(1, getHeight()));
        }

        /** The framed box in canvas coordinates. */
        private Rectangle2D.Float regionRect() {
            if (image == null) {
                return null;
            }
            return BackgroundLayout.regionOn(region, imageRect(), getWidth(), getHeight());
        }

        private void regionChanged() {
            repaint();
            updateReadout();
            previewPanel.repaint();
        }

        // ------------------------------------------------------------ painting

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                Shape backdrop = new RoundRectangle2D.Float(0, 0, w - 1, h - 1, CANVAS_ARC, CANVAS_ARC);
                g2.setColor(Theme.CANVAS);
                g2.fill(backdrop);

                if (image == null) {
                    paintEmptyState(g2, w, h);
                } else {
                    Rectangle cover = imageRect();
                    Shape old = g2.getClip();
                    g2.clip(backdrop);
                    // A soft shadow lifts the picture off the matte, so the letterboxing that a
                    // 16:9 photo leaves in a taller canvas reads as a frame rather than a void.
                    for (int i = 4; i >= 1; i--) {
                        g2.setColor(new Color(0, 0, 0, 22));
                        g2.fill(new RoundRectangle2D.Float(cover.x - i, cover.y - i + 1,
                                cover.width + 2 * i, cover.height + 2 * i, 6, 6));
                    }
                    // Smoothing matters here: a snapshot straight off a phone is many times the
                    // canvas and nearest-neighbour would shatter it into visible blocks.
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(displayImage(), cover.x, cover.y, cover.width, cover.height, null);

                    Rectangle2D.Float box = regionRect();
                    if (box != null) {
                        Area outside = new Area(cover);
                        outside.subtract(new Area(box));
                        g2.setColor(new Color(0, 0, 0, 105));
                        g2.fill(outside);
                        BalanceTextRenderer.drawRegion(g2, preview.amount, preview.subtitle,
                                box, textColor);
                    }
                    g2.setClip(old);

                    g2.setColor(Theme.alpha(Theme.BORDER, 190));
                    g2.drawRect(cover.x, cover.y, cover.width - 1, cover.height - 1);

                    if (box != null) {
                        g2.setColor(Theme.ACCENT_SOFT);
                        g2.setStroke(new BasicStroke(2f));
                        g2.draw(box);
                        g2.setStroke(new BasicStroke(1f));
                        paintHandles(g2, box);
                    }
                }

                g2.setColor(Theme.alpha(Theme.BORDER, 200));
                g2.draw(backdrop);
            } finally {
                g2.dispose();
            }
        }

        private void paintEmptyState(Graphics2D g2, int w, int h) {
            int boxW = Math.min(430, w - 90);
            int boxH = Math.min(190, h - 70);
            if (boxW < 80 || boxH < 60) {
                return;
            }
            int x = (w - boxW) / 2;
            int y = (h - boxH) / 2;
            g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    1f, new float[]{7f, 7f}, 0f));
            g2.setColor(Theme.alpha(Theme.BORDER, 220));
            g2.draw(new RoundRectangle2D.Float(x, y, boxW, boxH, 16, 16));
            g2.setStroke(new BasicStroke(1f));

            g2.setFont(Theme.ui(Font.PLAIN, 13f));
            g2.setColor(Theme.TEXT_DIM);
            drawCentred(g2, "\u8fd8\u6ca1\u6709\u9009\u62e9\u56fe\u7247", w / 2, y + boxH / 2 - 4);
            g2.setFont(Theme.ui(Font.PLAIN, 10.5f));
            g2.setColor(Theme.alpha(Theme.TEXT_DIM, 185));
            drawCentred(g2, "\u70b9\u51fb\u8fd9\u91cc\u6216\u5de6\u4e0a\u89d2\u300c\u9009\u62e9\u56fe\u7247\u2026\u300d\u6311\u4e00\u5f20\u80cc\u666f\u56fe",
                    w / 2, y + boxH / 2 + 18);
        }

        private void drawCentred(Graphics2D g2, String text, int cx, int baseline) {
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, cx - fm.stringWidth(text) / 2, baseline);
        }

        /** Eight grab points: four corners and the middle of each edge. */
        private Point[] handlePoints(Rectangle2D.Float r) {
            int x0 = (int) Math.round(r.x);
            int y0 = (int) Math.round(r.y);
            int x1 = (int) Math.round(r.x + r.width);
            int y1 = (int) Math.round(r.y + r.height);
            int cx = (x0 + x1) / 2;
            int cy = (y0 + y1) / 2;
            return new Point[]{
                new Point(x0, y0), new Point(cx, y0), new Point(x1, y0),
                new Point(x0, cy), new Point(x1, cy),
                new Point(x0, y1), new Point(cx, y1), new Point(x1, y1),
            };
        }

        private void paintHandles(Graphics2D g2, Rectangle2D.Float box) {
            Point[] points = handlePoints(box);
            for (int i = 0; i < points.length; i++) {
                int size = i == hoverHandle || i == handle ? HANDLE + 2 : HANDLE;
                int x = points[i].x - size / 2;
                int y = points[i].y - size / 2;
                Shape dot = new RoundRectangle2D.Float(x, y, size, size, 4, 4);
                g2.setColor(i == hoverHandle || i == handle ? Theme.ACCENT_SOFT : Color.WHITE);
                g2.fill(dot);
                g2.setColor(Theme.ACCENT);
                g2.draw(dot);
            }
        }

        // -------------------------------------------------------------- input

        private void installMouse() {
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        begin(e.getPoint());
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    mode = NONE;
                    handle = -1;
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    drag(e.getPoint());
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    hover(e.getPoint());
                }
            });
            addMouseWheelListener(e -> {
                if (e.isControlDown()) {
                    zoomBy(e.getWheelRotation() < 0 ? 1 : -1);
                    e.consume();
                }
            });
        }

        private void installKeys() {
            bindNudge(KeyEvent.VK_LEFT, "nudge-left", -1, 0);
            bindNudge(KeyEvent.VK_RIGHT, "nudge-right", 1, 0);
            bindNudge(KeyEvent.VK_UP, "nudge-up", 0, -1);
            bindNudge(KeyEvent.VK_DOWN, "nudge-down", 0, 1);
        }

        private void bindNudge(int keyCode, String name, final int dx, final int dy) {
            getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyCode, 0), name);
            getInputMap(JComponent.WHEN_FOCUSED)
                    .put(KeyStroke.getKeyStroke(keyCode, KeyEvent.SHIFT_DOWN_MASK), name + "-fast");
            AbstractAction action = new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    int step = (e.getModifiers() & ActionEvent.SHIFT_MASK) != 0 ? 10 : 1;
                    nudge(dx * step, dy * step);
                }
            };
            getActionMap().put(name, action);
            getActionMap().put(name + "-fast", action);
        }

        private void zoomBy(int steps) {
            double base = effectiveScale();
            setZoom(base * Math.pow(1.15, steps));
        }

        private void nudge(int dxPx, int dyPx) {
            if (region == null) {
                return;
            }
            Rectangle cover = imageRect();
            if (cover.width <= 0 || cover.height <= 0) {
                return;
            }
            setRegion(new Rectangle2D.Float(
                    clamp(region.x + dxPx / (float) cover.width, 0f, 1f - region.width),
                    clamp(region.y + dyPx / (float) cover.height, 0f, 1f - region.height),
                    region.width, region.height));
        }

        private void begin(Point p) {
            if (image == null) {
                // The empty canvas is one big "pick a picture" button; there is nothing else it
                // could mean, and it saves a trip to the toolbar.
                chooseImage();
                return;
            }
            dragStart = p;
            dragStartRegion = region == null ? null
                    : new Rectangle2D.Float(region.x, region.y, region.width, region.height);

            handle = handleAt(p);
            if (handle >= 0) {
                mode = RESIZE;
                return;
            }
            Rectangle2D.Float box = regionRect();
            if (box != null && box.contains(p)) {
                mode = MOVE;
                return;
            }
            mode = CREATE;
            region = new Rectangle2D.Float(0, 0, 0.01f, 0.01f);
            dragStartRegion = null;
            regionChanged();
        }

        private void drag(Point p) {
            if (image == null || mode == NONE) {
                return;
            }
            Rectangle cover = imageRect();
            if (cover.width <= 0 || cover.height <= 0) {
                return;
            }
            float minW = Math.min(1f, MIN_REGION_PX / (float) cover.width);
            float minH = Math.min(1f, MIN_REGION_PX / (float) cover.height);
            // Screen coordinates back to 0..1 of the image. The inverse of this open-coded
            // conversion lives in BackgroundLayout.regionOn, which is the direction three different
            // painters need to agree on.
            float nx = clamp((p.x - cover.x) / (float) cover.width, 0f, 1f);
            float ny = clamp((p.y - cover.y) / (float) cover.height, 0f, 1f);
            float sx = clamp((dragStart.x - cover.x) / (float) cover.width, 0f, 1f);
            float sy = clamp((dragStart.y - cover.y) / (float) cover.height, 0f, 1f);

            switch (mode) {
                case CREATE:
                    region = fromEdges(Math.min(nx, sx), Math.min(ny, sy),
                            Math.max(nx, sx), Math.max(ny, sy), minW, minH);
                    break;
                case MOVE:
                    if (dragStartRegion == null) {
                        break;
                    }
                    region = new Rectangle2D.Float(
                            clamp(dragStartRegion.x + (nx - sx), 0f, 1f - dragStartRegion.width),
                            clamp(dragStartRegion.y + (ny - sy), 0f, 1f - dragStartRegion.height),
                            dragStartRegion.width, dragStartRegion.height);
                    break;
                case RESIZE:
                    if (dragStartRegion == null) {
                        break;
                    }
                    float left = dragStartRegion.x;
                    float top = dragStartRegion.y;
                    float right = left + dragStartRegion.width;
                    float bottom = top + dragStartRegion.height;
                    // 0=NW 1=N 2=NE 3=W 4=E 5=SW 6=S 7=SE
                    if (handle == 0 || handle == 3 || handle == 5) {
                        left = nx;
                    }
                    if (handle == 2 || handle == 4 || handle == 7) {
                        right = nx;
                    }
                    if (handle == 0 || handle == 1 || handle == 2) {
                        top = ny;
                    }
                    if (handle == 5 || handle == 6 || handle == 7) {
                        bottom = ny;
                    }
                    region = fromEdges(left, top, right, bottom, minW, minH);
                    break;
                default:
                    break;
            }
            regionChanged();
        }

        /** Builds a region from two edges, ordering them and honouring the minimum size. */
        private Rectangle2D.Float fromEdges(float left, float top, float right, float bottom,
                                            float minW, float minH) {
            if (right < left) {
                float t = left;
                left = right;
                right = t;
            }
            if (bottom < top) {
                float t = top;
                top = bottom;
                bottom = t;
            }
            left = clamp(left, 0f, 1f);
            top = clamp(top, 0f, 1f);
            right = clamp(right, 0f, 1f);
            bottom = clamp(bottom, 0f, 1f);
            float w = Math.max(minW, right - left);
            float h = Math.max(minH, bottom - top);
            if (left + w > 1f) {
                left = Math.max(0f, 1f - w);
            }
            if (top + h > 1f) {
                top = Math.max(0f, 1f - h);
            }
            return new Rectangle2D.Float(left, top, w, h);
        }

        private void hover(Point p) {
            int found = handleAt(p);
            if (found != hoverHandle) {
                hoverHandle = found;
                repaint();
            }
            if (found >= 0) {
                setCursor(Cursor.getPredefinedCursor(cursorFor(found)));
            } else if (regionRect() != null && regionRect().contains(p)) {
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            } else {
                setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            }
        }

        private int handleAt(Point p) {
            Rectangle2D.Float box = regionRect();
            if (box == null) {
                return -1;
            }
            Point[] points = handlePoints(box);
            for (int i = 0; i < points.length; i++) {
                if (Math.abs(p.x - points[i].x) <= GRAB && Math.abs(p.y - points[i].y) <= GRAB) {
                    return i;
                }
            }
            return -1;
        }

        private int cursorFor(int index) {
            switch (index) {
                case 0:
                    return Cursor.NW_RESIZE_CURSOR;
                case 1:
                    return Cursor.N_RESIZE_CURSOR;
                case 2:
                    return Cursor.NE_RESIZE_CURSOR;
                case 3:
                    return Cursor.W_RESIZE_CURSOR;
                case 4:
                    return Cursor.E_RESIZE_CURSOR;
                case 5:
                    return Cursor.SW_RESIZE_CURSOR;
                case 6:
                    return Cursor.S_RESIZE_CURSOR;
                default:
                    return Cursor.SE_RESIZE_CURSOR;
            }
        }

        // ----------------------------------------------------------- scrollable

        @Override
        public Dimension getPreferredSize() {
            Dimension viewport = getParent() == null ? null : getParent().getSize();
            int vw = viewport == null ? 720 : Math.max(1, viewport.width);
            int vh = viewport == null ? 420 : Math.max(1, viewport.height);
            if (image == null || fit) {
                return new Dimension(vw, vh);
            }
            int iw = (int) Math.round(image.getWidth() * zoom);
            int ih = (int) Math.round(image.getHeight() * zoom);
            return new Dimension(Math.max(vw, iw + 28), Math.max(vh, ih + 28));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(720, 420);
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == javax.swing.SwingConstants.HORIZONTAL
                    ? Math.max(24, visibleRect.width - 24) : Math.max(24, visibleRect.height - 24);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return image == null || fit;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return image == null || fit;
        }
    }

    /** Scrollbars thin enough to belong to a dark card; the platform default is a grey slab. */
    private static class ThinScrollBarUI extends BasicScrollBarUI {

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zeroButton();
        }

        private JButton zeroButton() {
            JButton b = new JButton();
            Dimension zero = new Dimension(0, 0);
            b.setPreferredSize(zero);
            b.setMinimumSize(zero);
            b.setMaximumSize(zero);
            return b;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle track) {
            g.setColor(Theme.alpha(Theme.BORDER, 55));
            g.fillRect(track.x, track.y, track.width, track.height);
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle thumb) {
            if (thumb.height <= 0 || thumb.width <= 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean vertical = thumb.height >= thumb.width;
                int size = 7;
                int x = vertical ? thumb.x + (thumb.width - size) / 2 : thumb.x + 2;
                int y = vertical ? thumb.y + 2 : thumb.y + (thumb.height - size) / 2;
                int w = vertical ? size : thumb.width - 4;
                int h = vertical ? thumb.height - 4 : size;
                g2.setColor(Theme.alpha(Theme.ACCENT_SOFT, 140));
                g2.fillRoundRect(x, y, w, h, size, size);
            } finally {
                g2.dispose();
            }
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * A copy of the picture no larger than {@value #DISPLAY_MAX_SIDE} pixels on its long side, for
     * painting.
     *
     * <p>The stored file is untouched and all the geometry keeps using the original dimensions —
     * this only bounds the work one repaint has to do, which matters because the editor repaints on
     * every mouse move. Images already small enough are returned as they are.
     */
    private static BufferedImage downscale(BufferedImage source) {
        if (source == null) {
            return null;
        }
        int w = source.getWidth();
        int h = source.getHeight();
        int longest = Math.max(w, h);
        if (longest <= DISPLAY_MAX_SIDE) {
            return source;
        }
        double scale = DISPLAY_MAX_SIDE / (double) longest;
        int dw = Math.max(1, (int) Math.round(w * scale));
        int dh = Math.max(1, (int) Math.round(h * scale));
        // Keeps any transparency the user's PNG had; the widget composites it the same way.
        BufferedImage out = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, dw, dh, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
