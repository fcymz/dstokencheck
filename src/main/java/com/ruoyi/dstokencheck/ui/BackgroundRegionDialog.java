package com.ruoyi.dstokencheck.ui;

import com.ruoyi.dstokencheck.config.AppConfig;
import com.ruoyi.dstokencheck.model.CropShape;

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
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
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
    /** Nor can a crop: a card narrower than this could not show a number at all. */
    private static final int MIN_CROP_PX = 56;
    /** "适应窗口" stops here, so a 32×32 icon is not blown up into a blurry wall. */
    private static final double MAX_FIT_SCALE = 4.0;
    private static final double MIN_ZOOM = 0.1;
    private static final double MAX_ZOOM = 6.0;
    private static final int PREVIEW_W = 244;
    private static final int PREVIEW_H = 150;
    /** Longest side of the copy the canvas paints from; the original stays for all geometry. */
    private static final int DISPLAY_MAX_SIDE = 1600;
    /** What the canvas edits: the balance box, a rectangular crop, or a traced outline. */
    private static final int MODE_REGION = 0;
    private static final int MODE_RECT = 1;
    private static final int MODE_FREE = 2;
    /** How far a simplified freehand outline may stray from the trace, in image fractions. */
    private static final float TRACE_TOLERANCE = 0.006f;

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
    private final FlatButton fitButton = new FlatButton("适应窗口", FlatButton.Kind.SECONDARY);
    private final FlatButton oneToOneButton = new FlatButton("1:1", FlatButton.Kind.SECONDARY);
    private final FlatButton resetButton = new FlatButton("重置区域", FlatButton.Kind.SECONDARY);
    private final FlatButton regionModeButton = new FlatButton("余额区域", FlatButton.Kind.SECONDARY);
    private final FlatButton rectModeButton = new FlatButton("矩形裁剪", FlatButton.Kind.SECONDARY);
    private final FlatButton freeModeButton = new FlatButton("自由裁剪", FlatButton.Kind.SECONDARY);
    private final FlatButton saveButton =
            new FlatButton("保存并应用", FlatButton.Kind.PRIMARY);

    private ImageCanvas canvas;
    private JScrollPane scrollPane;

    private Color textColor;
    /** True while the canvas edits the crop instead of the balance box. */
    /** Which box the canvas edits: MODE_REGION, MODE_RECT or MODE_FREE. */
    private int mode = MODE_REGION;
    private boolean confirmed;
    private Point dragOrigin;
    private Point windowOrigin;

    public BackgroundRegionDialog(Frame owner, AppConfig config, PreviewData preview) {
        super(owner, "自定义背景图", true);
        this.config = config;
        this.preview = preview == null ? new PreviewData("", "", "", "", "") : preview;
        this.originalImageName = config.getBackgroundImageName();
        this.textColor = config.getBalanceTextColor();

        BufferedImage image = loadConfiguredImage();
        Rectangle2D.Float region = config.getBalanceRegion();
        if (image != null && region == null) {
            region = AppConfig.defaultBalanceRegion();
        }
        canvas = new ImageCanvas(image, config.getEffectiveCropShape(), region);

        setUndecorated(true);
        setBackground(Theme.BG_BOTTOM);
        card = new CardPanel(ARC);
        setContentPane(card);

        buildUi();
        installEscape();
        updateColorControls();
        updateZoomControls();
        updateModeControls();
        updateReadout();
        updateEnabledState();
        setStatus(image == null
                ? "先选一张背景图"
                : "拖拽框出余额显示区域；切到裁剪模式可裁掉多余边缘", false);
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
        JLabel title = new JLabel("自定义背景图");
        title.setFont(Theme.ui(Font.BOLD, 15f));
        title.setForeground(Theme.TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel subtitle = new JLabel("选一张图片，在图上框出余额要显示的位置");
        subtitle.setFont(Theme.ui(Font.PLAIN, 10.5f));
        subtitle.setForeground(Theme.TEXT_DIM);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        headings.add(title);
        headings.add(Box.createVerticalStrut(3));
        headings.add(subtitle);
        row.add(headings, BorderLayout.WEST);

        IconButton close = new IconButton(IconButton.Glyph.CLOSE, "取消 (Esc)");
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

        FlatButton choose = new FlatButton("选择图片…", FlatButton.Kind.PRIMARY);
        choose.setIcon(new IconButton.GlyphIcon(IconButton.Glyph.IMAGE, 15));
        choose.setIconTextGap(8);
        choose.addActionListener(e -> chooseImage());
        left.add(choose);

        // Three edit modes share one canvas: what a drag does depends on which one is lit.
        regionModeButton.addActionListener(e -> setMode(MODE_REGION));
        left.add(regionModeButton);
        rectModeButton.addActionListener(e -> setMode(MODE_RECT));
        left.add(rectModeButton);
        freeModeButton.addActionListener(e -> setMode(MODE_FREE));
        left.add(freeModeButton);

        resetButton.addActionListener(e -> resetActiveBox());
        left.add(resetButton);
        bar.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        fitButton.addActionListener(e -> {
            canvas.setFit(true);
            canvas.requestFocusInWindow();
        });
        right.add(fitButton);

        oneToOneButton.addActionListener(e -> {
            canvas.setZoom(1.0);
            canvas.requestFocusInWindow();
        });
        right.add(oneToOneButton);

        zoomLabel.setFont(Theme.ui(Font.PLAIN, 10.5f));
        zoomLabel.setForeground(Theme.TEXT_DIM);
        right.add(zoomLabel);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    /** Switches what the canvas edits, and keeps every mode-dependent label honest. */
    private void setMode(int which) {
        if (mode == which) {
            return;
        }
        mode = which;
        canvas.forgetGesture();
        updateModeControls();
        updateReadout();
        canvas.repaint();
        previewPanel.repaint();
        canvas.requestFocusInWindow();
    }

    /** True while the canvas is editing the crop rather than the balance box. */
    private boolean cropMode() {
        return mode != MODE_REGION;
    }

    private void updateModeControls() {
        regionModeButton.setActive(mode == MODE_REGION);
        rectModeButton.setActive(mode == MODE_RECT);
        freeModeButton.setActive(mode == MODE_FREE);
        resetButton.setText(cropMode() ? "重置裁剪" : "重置区域");
    }

    private void resetActiveBox() {
        if (cropMode()) {
            canvas.setCrop(CropShape.rectangle(BackgroundLayout.fullCrop()));
            setStatus("已取消裁剪，小窗口将显示整张图片", false);
        } else {
            canvas.setRegion(AppConfig.defaultBalanceRegion());
            setStatus("已重置余额区域", false);
        }
        canvas.requestFocusInWindow();
    }

    private JPanel buildSidebar() {
        JPanel side = new JPanel();
        side.setOpaque(false);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setPreferredSize(new Dimension(SIDEBAR_W, 10));

        JPanel previewSection = section("小窗口预览");
        previewSection.add(previewPanel);
        previewSection.add(Box.createVerticalStrut(7));
        previewSection.add(hint("实际效果（保存后窗口会按图片比例调整）"));
        side.add(previewSection);

        side.add(Box.createVerticalStrut(12));
        side.add(buildColorSection());
        side.add(Box.createVerticalStrut(12));
        side.add(buildTipsSection());
        side.add(Box.createVerticalGlue());
        return side;
    }

    private JPanel buildColorSection() {
        JPanel section = section("余额文字颜色");

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
        hexField.setToolTipText("任意颜色，格式 #RRGGBB");
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
        JPanel section = section("操作提示");
        for (String tip : new String[]{
            "拖拽空白处 → 新建区域",
            "拖拽框内 → 移动区域",
            "拖拽八个小方块 → 缩放区域",
            "「自由裁剪」里拖拽 → 手绘轮廓",
            "方向键微调，Shift 加速",
            "Ctrl + 滚轮 → 缩放画布",
        }) {
            section.add(hint("•  " + tip));
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

        FlatButton cancel = new FlatButton("取消", FlatButton.Kind.SECONDARY);
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
        chooser.setDialogTitle("选择背景图片");
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "图片 (*.png, *.jpg, *.jpeg, *.gif, *.bmp)", "png", "jpg", "jpeg", "gif", "bmp"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            canvas.requestFocusInWindow();
            return;
        }
        File source = chooser.getSelectedFile();
        try {
            // Copying into the config directory is also how the file gets validated.
            config.storeBackgroundImage(source);
        } catch (IOException ex) {
            setStatus("无法读取该图片：" + ex.getMessage(), true);
            return;
        }
        canvas.setImage(loadConfiguredImage());
        canvas.setRegion(AppConfig.defaultBalanceRegion());
        canvas.setFit(true);
        previewPanel.revalidate();
        previewPanel.repaint();
        statusLabel.setForeground(Theme.TEXT_DIM);
        setStatus("已导入 " + source.getName() + "（已复制到配置目录，原图可删）", false);
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
        setStatus("文字颜色已更新", false);
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
            setStatus("颜色格式应为 #RRGGBB", true);
            updateColorControls();
        }
    }

    private void onConfirm() {
        if (canvas.getImage() == null) {
            setStatus("请先选择一张图片", true);
            return;
        }
        Rectangle2D.Float region = canvas.getRegion();
        if (region == null || region.width <= 0.01f || region.height <= 0.01f) {
            setStatus("请先在图片上拖拽框出显示区域", true);
            return;
        }
        config.setCropShape(canvas.getCrop());
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
        config.setBackgroundImageName(originalImageName);
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
        zoomLabel.setText((fit ? "适应窗口 " : "") + percent + "%");
    }

    private void updateReadout() {
        Rectangle2D.Float r = canvas.getRegion();
        Rectangle2D.Float crop = canvas.getCropBounds();
        CropShape shape = canvas.getCrop();
        BufferedImage image = canvas.getImage();
        if (r == null || image == null) {
            readoutLabel.setText(" ");
            sizeLabel.setText(" ");
            return;
        }
        if (cropMode()) {
            // Pixels, not percentages: when deciding what to keep, the real size is what matters.
            readoutLabel.setText(String.format(Locale.ROOT,
                    "裁剪  %.0f × %.0f 像素  比例 %.2f:1%s",
                    crop.width * image.getWidth(), crop.height * image.getHeight(),
                    BackgroundLayout.cropAspect(crop, image.getWidth(), image.getHeight()),
                    shape.isRectangle() ? "" : String.format(Locale.ROOT,
                            "  ·  %d 个顶点", shape.size())));
        } else {
            readoutLabel.setText(String.format(Locale.ROOT,
                    "区域  宽 %.0f%%  高 %.0f%%  左 %.0f%%  上 %.0f%%",
                    r.width * 100, r.height * 100, r.x * 100, r.y * 100));
        }

        // The window shows the crop, so the number's size on screen has to be measured against it.
        Rectangle widget = config.getBounds();
        double widgetW = Math.max(160, widget.width);
        double widgetH = widgetW / BackgroundLayout.cropAspect(crop, image.getWidth(), image.getHeight());
        double regionW = r.width / crop.width * widgetW;
        double regionH = r.height / crop.height * widgetH;
        // An irregular outline can cut the number even when the box is inside the frame, so the
        // silhouette itself is what the warning is measured against.
        if (shape.contains(r)) {
            sizeLabel.setForeground(Theme.alpha(Theme.ACCENT_SOFT, 210));
            sizeLabel.setText(String.format(Locale.ROOT,
                    "小窗口内约 %.0f × %.0f 像素", regionW, regionH));
        } else {
            // Otherwise the number would be silently cut by the window's edge.
            sizeLabel.setForeground(Theme.WARN);
            sizeLabel.setText("余额区域超出裁剪轮廓，数字会被截断");
        }
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
                CropShape shape = canvas.getCrop();
                Rectangle2D.Float cropBounds = canvas.getCropBounds();
                double aspect = image == null ? 2.0
                        : BackgroundLayout.cropAspect(cropBounds,
                                image.getWidth(), image.getHeight());
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

                // The miniature has to be cut to the same silhouette as the window, or it would
                // promise a rectangular card the user is not going to get. With a picture the card
                // is the picture itself, sharp corners and all.
                boolean picture = image != null;
                Shape cardShape = picture
                        ? shape.toPath(new Rectangle(x, y, w, h))
                        : new RoundRectangle2D.Float(x, y, w - 1, h - 1, 14, 14);
                Shape old = g2.getClip();
                g2.clip(cardShape);
                g2.translate(x, y);

                if (image != null) {
                    Rectangle cover = BackgroundLayout.imageRect(cropBounds,
                            image.getWidth(), image.getHeight(), w, h);
                    Theme.paintCheckerboard(g2, 0, 0, w, h);
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(canvas.displayImage(), cover.x, cover.y, cover.width, cover.height, null);
                } else {
                    g2.setPaint(new GradientPaint(0, 0, Theme.BG_TOP, 0, h, Theme.BG_BOTTOM));
                    g2.fillRect(0, 0, w, h);
                    // The widget only draws its own title bar and footer on the plain card.
                    paintChrome(g2, w, h);
                }

                Rectangle2D.Float region = canvas.getRegion();
                if (image != null && region != null) {
                    Rectangle cover = BackgroundLayout.imageRect(cropBounds,
                            image.getWidth(), image.getHeight(), w, h);
                    Rectangle2D.Float box = BackgroundLayout.regionOn(region, cover, w, h);
                    if (box != null) {
                        BalanceTextRenderer.drawRegion(g2, preview.amount, preview.subtitle, box, textColor);
                    }
                }
                g2.translate(-x, -y);
                g2.setClip(old);

                // Only the card has an edge drawn round it; the picture's edge is the window's.
                if (!picture) {
                    g2.setColor(Theme.alpha(Theme.ACCENT, 110));
                    g2.draw(cardShape);
                }
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
        String ellipsis = "…";
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
        private static final int TRACE = 4;

        private BufferedImage image;
        /** Downscaled copy used for painting; a 24-megapixel photo is re-scaled on every drag. */
        private BufferedImage displayImage;
        /** Visible part of the picture; never null, and a rectangle unless one was traced. */
        private CropShape crop;
        /** Normalised 0..1 box on the image where the balance goes. */
        private Rectangle2D.Float region;
        private boolean fit = true;
        private double zoom = 1.0;

        private int dragMode = NONE;
        private int handle = -1;
        private int hoverHandle = -1;
        private Point dragStart;
        private Rectangle2D.Float dragStartBox;
        /** Points of a freehand trace in progress, in canvas coordinates. */
        private final List<Point> trace = new ArrayList<Point>();

        ImageCanvas(BufferedImage image, CropShape crop, Rectangle2D.Float region) {
            this.image = image;
            this.displayImage = downscale(image);
            this.crop = crop == null ? CropShape.rectangle(BackgroundLayout.fullCrop()) : crop;
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
            boxChanged();
        }

        CropShape getCrop() {
            return crop;
        }

        /** The crop's bounding box, which is what the picture is placed and sized by. */
        Rectangle2D.Float getCropBounds() {
            return crop.bounds();
        }

        void setCrop(CropShape crop) {
            this.crop = crop == null ? CropShape.rectangle(BackgroundLayout.fullCrop()) : crop;
            boxChanged();
        }

        /**
         * The box the current mode edits.
         *
         * <p>For an irregular crop this is its bounding box: dragging the frame then scales the whole
         * silhouette with it, which is the only sane way to resize a lassoed outline with a mouse.
         */
        private Rectangle2D.Float activeBox() {
            return cropMode() ? crop.bounds() : region;
        }

        private void applyActiveBox(Rectangle2D.Float box) {
            if (cropMode()) {
                // Keep the silhouette's shape: map it onto the new frame instead of replacing it.
                crop = mode == MODE_FREE ? crop.withBounds(box) : CropShape.rectangle(box);
            } else {
                region = box;
            }
            boxChanged();
        }

        /** Drops any in-progress drag, so a mode switch cannot finish the previous edit. */
        void forgetGesture() {
            dragMode = NONE;
            handle = -1;
            hoverHandle = -1;
            dragStart = null;
            dragStartBox = null;
            trace.clear();
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

        /** The framed balance box in canvas coordinates. */
        private Rectangle2D.Float regionRect() {
            if (image == null) {
                return null;
            }
            return BackgroundLayout.regionOn(region, imageRect(), getWidth(), getHeight());
        }

        /** The crop's bounding box in canvas coordinates, which is what the handles grab. */
        private Rectangle2D.Float cropRect() {
            if (image == null) {
                return null;
            }
            return BackgroundLayout.regionOn(crop.bounds(), imageRect(), getWidth(), getHeight());
        }

        /** The crop's silhouette in canvas coordinates. */
        private java.awt.Shape cropOutline() {
            if (image == null) {
                return null;
            }
            return crop.toPath(imageRect());
        }

        private void boxChanged() {
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
                    // Chequered mat first: where the picture is see-through the widget will show the
                    // desktop, and the editor has to say so somehow.
                    Theme.paintCheckerboard(g2, cover.x, cover.y, cover.width, cover.height);
                    // Smoothing matters here: a snapshot straight off a phone is many times the
                    // canvas and nearest-neighbour would shatter it into visible blocks.
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(displayImage(), cover.x, cover.y, cover.width, cover.height, null);

                    Rectangle2D.Float cropBox = cropRect();
                    Rectangle2D.Float regionBox = regionRect();
                    java.awt.Shape visible = cropOutline();

                    // Everything the crop throws away is pushed back, so the window's edge is
                    // visible on the picture rather than something the user has to imagine.
                    if (visible != null) {
                        Area discarded = new Area(cover);
                        discarded.subtract(new Area(visible));
                        g2.setColor(new Color(0, 0, 0, cropMode() ? 165 : 130));
                        g2.fill(discarded);
                    }
                    if (regionBox != null) {
                        Area outside = new Area(visible == null ? cover : visible);
                        outside.subtract(new Area(regionBox));
                        g2.setColor(new Color(0, 0, 0, cropMode() ? 60 : 105));
                        g2.fill(outside);
                        // The number is previewed in both modes, but only where the window would
                        // actually show it: a region sticking out of the crop is visibly cut here
                        // rather than silently cut on the desktop.
                        Shape overlayClip = g2.getClip();
                        if (visible != null) {
                            g2.clip(visible);
                        }
                        BalanceTextRenderer.drawRegion(g2, preview.amount, preview.subtitle,
                                regionBox, textColor);
                        g2.setClip(overlayClip);
                    }
                    g2.setClip(old);

                    g2.setColor(Theme.alpha(Theme.BORDER, 190));
                    g2.drawRect(cover.x, cover.y, cover.width - 1, cover.height - 1);

                    // The inactive box stays visible but quiet, so both are always in view.
                    if (visible != null) {
                        g2.setColor(cropMode() ? Color.WHITE : Theme.alpha(Color.WHITE, 150));
                        g2.setStroke(cropMode()
                                ? new BasicStroke(2f)
                                : new BasicStroke(1.4f, BasicStroke.CAP_ROUND,
                                        BasicStroke.JOIN_ROUND, 1f, new float[]{6f, 5f}, 0f));
                        g2.draw(visible);
                        g2.setStroke(new BasicStroke(1f));
                    }
                    // Thirds only make sense on a rectangle; the frame handles cover the rest.
                    if (cropBox != null && mode == MODE_RECT) {
                        paintThirds(g2, cropBox);
                    }
                    if (regionBox != null && cropMode()) {
                        g2.setColor(Theme.alpha(Theme.ACCENT_SOFT, 170));
                        g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND,
                                BasicStroke.JOIN_ROUND, 1f, new float[]{6f, 5f}, 0f));
                        g2.draw(regionBox);
                        g2.setStroke(new BasicStroke(1f));
                    }

                    Rectangle2D.Float active = cropMode() ? cropBox : regionBox;
                    if (active != null) {
                        g2.setColor(cropMode() ? Color.WHITE : Theme.ACCENT_SOFT);
                        g2.setStroke(new BasicStroke(2f));
                        g2.draw(active);
                        g2.setStroke(new BasicStroke(1f));
                        paintHandles(g2, active, cropMode());
                    }
                    paintTrace(g2);
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
            drawCentred(g2, "还没有选择图片", w / 2, y + boxH / 2 - 4);
            g2.setFont(Theme.ui(Font.PLAIN, 10.5f));
            g2.setColor(Theme.alpha(Theme.TEXT_DIM, 185));
            drawCentred(g2, "点击这里或左上角「选择图片…」挑一张背景图",
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

        private void paintHandles(Graphics2D g2, Rectangle2D.Float box, boolean light) {
            Point[] points = handlePoints(box);
            for (int i = 0; i < points.length; i++) {
                int size = i == hoverHandle || i == handle ? HANDLE + 2 : HANDLE;
                int x = points[i].x - size / 2;
                int y = points[i].y - size / 2;
                Shape dot = new RoundRectangle2D.Float(x, y, size, size, 4, 4);
                g2.setColor(i == hoverHandle || i == handle
                        ? (light ? Color.WHITE : Theme.ACCENT_SOFT) : Color.WHITE);
                g2.fill(dot);
                g2.setColor(light ? Theme.alpha(Theme.BORDER, 230) : Theme.ACCENT);
                g2.draw(dot);
            }
        }

        /**
         * Rule-of-thirds guides inside a box.
         *
         * <p>Only the rectangular crop gets them: choosing what to keep is a composition decision,
         * while the balance box is placed against the picture's content, where a grid would just be
         * noise — and an irregular outline has no thirds to speak of.
         */
        private void paintThirds(Graphics2D g2, Rectangle2D.Float box) {
            g2.setColor(Theme.alpha(Color.WHITE, 60));
            g2.setStroke(new BasicStroke(1f));
            for (int i = 1; i <= 2; i++) {
                int x = (int) Math.round(box.x + box.width * i / 3.0);
                int y = (int) Math.round(box.y + box.height * i / 3.0);
                g2.drawLine(x, (int) Math.round(box.y), x, (int) Math.round(box.y + box.height));
                g2.drawLine((int) Math.round(box.x), y, (int) Math.round(box.x + box.width), y);
            }
        }

        /** The freehand outline being drawn right now, so the user can see what they are tracing. */
        private void paintTrace(Graphics2D g2) {
            if (trace.size() < 2) {
                return;
            }
            Path2D.Float path = new Path2D.Float();
            path.moveTo(trace.get(0).x, trace.get(0).y);
            for (int i = 1; i < trace.size(); i++) {
                path.lineTo(trace.get(i).x, trace.get(i).y);
            }
            g2.setColor(Theme.alpha(Theme.ACCENT_SOFT, 220));
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(path);
            // A hint of the closing edge, since that is what the trace will become.
            g2.setColor(Theme.alpha(Theme.ACCENT_SOFT, 90));
            g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    1f, new float[]{5f, 5f}, 0f));
            g2.drawLine(trace.get(trace.size() - 1).x, trace.get(trace.size() - 1).y,
                    trace.get(0).x, trace.get(0).y);
            g2.setStroke(new BasicStroke(1f));
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
                    if (dragMode == TRACE) {
                        endTrace();
                    }
                    dragMode = NONE;
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
            Rectangle2D.Float box = activeBox();
            if (box == null) {
                return;
            }
            Rectangle cover = imageRect();
            if (cover.width <= 0 || cover.height <= 0) {
                return;
            }
            float dx = dxPx / (float) cover.width;
            float dy = dyPx / (float) cover.height;
            if (cropMode() && mode == MODE_FREE) {
                // Move the whole silhouette rather than its frame.
                float clampedX = clamp(dx, -box.x, 1f - box.x - box.width);
                float clampedY = clamp(dy, -box.y, 1f - box.y - box.height);
                setCrop(crop.translated(clampedX, clampedY));
                return;
            }
            applyActiveBox(new Rectangle2D.Float(
                    clamp(box.x + dx, 0f, 1f - box.width),
                    clamp(box.y + dy, 0f, 1f - box.height),
                    box.width, box.height));
        }

        private void begin(Point p) {
            if (image == null) {
                // The empty canvas is one big "pick a picture" button; there is nothing else it
                // could mean, and it saves a trip to the toolbar.
                chooseImage();
                return;
            }
            trace.clear();
            handle = handleAt(p);
            if (handle >= 0) {
                dragStart = p;
                dragStartBox = new Rectangle2D.Float(
                        activeBox().x, activeBox().y, activeBox().width, activeBox().height);
                dragMode = RESIZE;
                return;
            }
            Rectangle2D.Float onCanvas = activeRectOnCanvas();
            // In freehand mode a plain rectangular crop is there to be replaced, not dragged: a
            // fresh crop covers the whole picture, so "press inside" would otherwise swallow every
            // attempt to start drawing. Once an outline exists, dragging inside it moves it.
            boolean movable = mode == MODE_FREE ? !crop.isRectangle() : true;
            if (movable && onCanvas != null && onCanvas.contains(p)) {
                dragStart = p;
                dragStartBox = new Rectangle2D.Float(
                        activeBox().x, activeBox().y, activeBox().width, activeBox().height);
                dragMode = MOVE;
                return;
            }
            if (mode == MODE_FREE) {
                // Outside the current outline: draw a new one. Collecting raw pointer samples here
                // and simplifying on release keeps the trace smooth while it is being drawn.
                dragMode = TRACE;
                trace.add(p);
                return;
            }
            dragStart = p;
            dragStartBox = null;
            dragMode = CREATE;
            applyActiveBox(new Rectangle2D.Float(0, 0, 0.01f, 0.01f));
        }

        private void drag(Point p) {
            if (image == null || dragMode == NONE) {
                return;
            }
            if (dragMode == TRACE) {
                Point last = trace.isEmpty() ? null : trace.get(trace.size() - 1);
                if (last == null || Math.abs(last.x - p.x) + Math.abs(last.y - p.y) >= 3) {
                    trace.add(p);
                    repaint();
                }
                return;
            }
            Rectangle cover = imageRect();
            if (cover.width <= 0 || cover.height <= 0) {
                return;
            }
            // The crop may not become a sliver; the balance box has its own smaller floor.
            float floor = cropMode() ? MIN_CROP_PX : MIN_REGION_PX;
            float minW = Math.min(1f, floor / (float) cover.width);
            float minH = Math.min(1f, floor / (float) cover.height);
            // Screen coordinates back to 0..1 of the image. The inverse of this open-coded
            // conversion lives in BackgroundLayout.regionOn, which is the direction three different
            // painters need to agree on.
            float nx = clamp((p.x - cover.x) / (float) cover.width, 0f, 1f);
            float ny = clamp((p.y - cover.y) / (float) cover.height, 0f, 1f);
            float sx = clamp((dragStart.x - cover.x) / (float) cover.width, 0f, 1f);
            float sy = clamp((dragStart.y - cover.y) / (float) cover.height, 0f, 1f);

            Rectangle2D.Float next;
            switch (dragMode) {
                case CREATE:
                    next = fromEdges(Math.min(nx, sx), Math.min(ny, sy),
                            Math.max(nx, sx), Math.max(ny, sy), minW, minH);
                    break;
                case MOVE:
                    if (dragStartBox == null) {
                        return;
                    }
                    next = new Rectangle2D.Float(
                            clamp(dragStartBox.x + (nx - sx), 0f, 1f - dragStartBox.width),
                            clamp(dragStartBox.y + (ny - sy), 0f, 1f - dragStartBox.height),
                            dragStartBox.width, dragStartBox.height);
                    break;
                case RESIZE:
                    if (dragStartBox == null) {
                        return;
                    }
                    float left = dragStartBox.x;
                    float top = dragStartBox.y;
                    float right = left + dragStartBox.width;
                    float bottom = top + dragStartBox.height;
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
                    next = fromEdges(left, top, right, bottom, minW, minH);
                    break;
                default:
                    return;
            }
            applyActiveBox(next);
        }

        /** Turns the finished freehand trace into the crop outline. */
        private void endTrace() {
            if (dragMode != TRACE || trace.size() < 4) {
                trace.clear();
                return;
            }
            Rectangle cover = imageRect();
            if (cover.width <= 0 || cover.height <= 0) {
                trace.clear();
                return;
            }
            List<Point2D.Float> normalised = new ArrayList<Point2D.Float>(trace.size());
            for (Point p : trace) {
                normalised.add(new Point2D.Float(
                        (p.x - cover.x) / (float) cover.width,
                        (p.y - cover.y) / (float) cover.height));
            }
            trace.clear();
            CropShape traced = CropShape.fromTrace(normalised, TRACE_TOLERANCE);
            if (traced == null) {
                setStatus("拖拽画一圈才能裁出轮廓（太小了）", true);
                repaint();
                return;
            }
            Rectangle2D.Float bounds = traced.bounds();
            if (bounds.width < AppConfig.MIN_CROP || bounds.height < AppConfig.MIN_CROP) {
                setStatus("裁剪范围太小，请重新拖拽", true);
                repaint();
                return;
            }
            setCrop(traced);
            setStatus("已裁出 " + traced.size() + " 个顶点的轮廓，可拖框内移动、拖方块缩放", false);
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

        /** The box the current mode edits, in canvas coordinates. */
        private Rectangle2D.Float activeRectOnCanvas() {
            return cropMode() ? cropRect() : regionRect();
        }

        private void hover(Point p) {
            int found = handleAt(p);
            if (found != hoverHandle) {
                hoverHandle = found;
                repaint();
            }
            Rectangle2D.Float box = activeRectOnCanvas();
            if (found >= 0) {
                setCursor(Cursor.getPredefinedCursor(cursorFor(found)));
            } else if (box != null && box.contains(p)) {
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            } else {
                setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            }
        }

        private int handleAt(Point p) {
            Rectangle2D.Float box = activeRectOnCanvas();
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
