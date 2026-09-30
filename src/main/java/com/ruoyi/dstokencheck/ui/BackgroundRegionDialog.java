package com.ruoyi.dstokencheck.ui;

import com.ruoyi.dstokencheck.config.AppConfig;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Lets the user choose a background image and frame the area the balance should occupy.
 *
 * <p>The image is shown scaled to fit, with a draggable / resizable rectangle on top. Drag on empty
 * space to draw a new box, drag inside it to move it, drag a corner handle to resize. The balance
 * figure is previewed live inside the box using the same renderer the widget uses, so what is
 * framed here is what appears on the desktop.
 *
 * <p>Only edits configuration; the caller reloads the widget afterwards.
 */
public class BackgroundRegionDialog extends JDialog {

    /** Handle hit radius, in screen pixels. */
    private static final int HANDLE = 7;

    private final AppConfig config;
    private final String previewText;
    private final String previewSubtitle;

    private final ImageCanvas canvas;
    private final JLabel hintLabel = new JLabel(" ");
    private final JLabel colorSwatch = new JLabel(" ");

    /** Name configured when the dialog opened, restored if the user cancels. */
    private final String originalImageName;

    private Color textColor;
    private boolean confirmed;

    public BackgroundRegionDialog(Frame owner, AppConfig config, String previewText,
                                  String previewSubtitle) {
        super(owner, "自定义背景图", true);
        this.config = config;
        this.originalImageName = config.getBackgroundImageName();
        this.previewText = previewText == null ? "\u00a587.65" : previewText;
        this.previewSubtitle = previewSubtitle == null ? "" : previewSubtitle;
        this.textColor = config.getBalanceTextColor();

        BufferedImage image = loadConfiguredImage();
        Rectangle2D.Float region = config.getBalanceRegion();
        if (image != null && region == null) {
            region = AppConfig.defaultBalanceRegion();
        }
        canvas = new ImageCanvas(image, region);

        buildUi();
        pack();
        setSize(900, 700);
        setLocationRelativeTo(owner);
        updateHint();
        updateSwatch();

        // The widget is always-on-top, so the dialog has to be too or it opens behind it.
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
    }

    /** True when the user confirmed; the config has already been written. */
    public boolean isConfirmed() {
        return confirmed;
    }

    private BufferedImage loadConfiguredImage() {
        File file = config.getBackgroundImageFile();
        if (file == null) {
            return null;
        }
        try {
            return ImageIO.read(file);
        } catch (IOException e) {
            return null;
        }
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // ---- toolbar ----
        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.X_AXIS));

        JButton chooseButton = new JButton("选择图片…");
        chooseButton.addActionListener(e -> chooseImage());
        toolbar.add(chooseButton);

        toolbar.add(Box.createHorizontalStrut(8));
        JButton colorButton = new JButton("文字颜色…");
        colorButton.addActionListener(e -> chooseColor());
        toolbar.add(colorButton);

        toolbar.add(Box.createHorizontalStrut(6));
        colorSwatch.setOpaque(true);
        colorSwatch.setPreferredSize(new Dimension(22, 22));
        colorSwatch.setMaximumSize(new Dimension(22, 22));
        colorSwatch.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        toolbar.add(colorSwatch);

        toolbar.add(Box.createHorizontalStrut(8));
        JButton resetButton = new JButton("重置区域");
        resetButton.addActionListener(e -> canvas.setRegion(AppConfig.defaultBalanceRegion()));
        toolbar.add(resetButton);

        toolbar.add(Box.createHorizontalGlue());
        JLabel title = new JLabel("在图片上拖拽框出余额显示区域");
        title.setForeground(new Color(0x55, 0x55, 0x55));
        toolbar.add(title);
        root.add(toolbar, BorderLayout.NORTH);

        // ---- canvas ----
        JScrollPane scroller = new JScrollPane(canvas);
        scroller.setBorder(BorderFactory.createLineBorder(new Color(0xCC, 0xCC, 0xCC)));
        root.add(scroller, BorderLayout.CENTER);

        // ---- bottom ----
        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        hintLabel.setFont(hintLabel.getFont().deriveFont(Font.PLAIN, 11f));
        hintLabel.setForeground(new Color(0x66, 0x66, 0x66));
        bottom.add(hintLabel, BorderLayout.CENTER);

        JPanel actions = new JPanel();
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.add(Box.createHorizontalGlue());

        JButton cancelButton = new JButton("取消");
        cancelButton.addActionListener(e -> {
            confirmed = false;
            rollbackImport();
            dispose();
        });
        actions.add(cancelButton);

        actions.add(Box.createHorizontalStrut(8));
        JButton okButton = new JButton("确定");
        okButton.addActionListener(e -> onConfirm());
        actions.add(okButton);

        bottom.add(actions, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);

        setContentPane(root);
        getRootPane().setDefaultButton(okButton);
    }

    private void chooseImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择背景图片");
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "图片 (*.png, *.jpg, *.jpeg, *.gif, *.bmp)", "png", "jpg", "jpeg", "gif", "bmp"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File source = chooser.getSelectedFile();
        try {
            // Copy into the config directory straight away: that is also how we validate the file.
            config.storeBackgroundImage(source);
        } catch (IOException ex) {
            hintLabel.setText("无法读取该图片：" + ex.getMessage());
            return;
        }
        BufferedImage image = loadConfiguredImage();
        canvas.setImage(image);
        canvas.setRegion(AppConfig.defaultBalanceRegion());
        updateHint();
    }

    private void chooseColor() {
        Color picked = JColorChooser.showDialog(this, "选择余额文字颜色", textColor);
        if (picked != null) {
            textColor = picked;
            updateSwatch();
            canvas.repaint();
        }
    }

    private void onConfirm() {
        if (canvas.getImage() == null) {
            hintLabel.setText("请先选择一张图片");
            return;
        }
        Rectangle2D.Float region = canvas.getRegion();
        if (region == null || region.width <= 0.01f || region.height <= 0.01f) {
            hintLabel.setText("请先在图片上拖拽框出显示区域");
            return;
        }
        config.setBalanceRegion(region);
        config.setBalanceTextColor(textColor);
        config.save();
        // Every import during this session except the chosen one is now unreachable: drop it.
        config.pruneBackgroundImages(config.getBackgroundImageFile());
        confirmed = true;
        dispose();
    }

    /**
     * Undoes everything this dialog wrote to the config directory.
     *
     * <p>Imports are copied in as soon as they are picked, so the canvas has something real to show.
     * A cancelled dialog must therefore put the directory back exactly as it found it — otherwise
     * every cancelled attempt would leave another copy of the image behind.
     */
    private void rollbackImport() {
        config.restoreBackgroundImageName(originalImageName);
        config.pruneBackgroundImages(config.getBackgroundImageFile());
    }

    private void updateSwatch() {
        colorSwatch.setBackground(textColor);
        colorSwatch.repaint();
    }

    private void updateHint() {
        if (canvas.getImage() == null) {
            hintLabel.setText("请先点「选择图片…」挑一张背景图；图片会被复制到配置目录，原图删掉也不影响。");
        } else {
            hintLabel.setText("拖拽空白处＝新建区域，拖拽框内＝移动，拖拽四角＝缩放。"
                    + "区域按图片比例保存，确定后小窗口会调整为图片比例。");
        }
        getContentPane().revalidate();
        getContentPane().repaint();
    }

    // ------------------------------------------------------------------ canvas

    /** Image + selection overlay. */
    private class ImageCanvas extends JPanel {

        private BufferedImage image;
        /** Normalised 0..1 region. */
        private Rectangle2D.Float region;

        private static final int MODE_NONE = 0;
        private static final int MODE_NEW = 1;
        private static final int MODE_MOVE = 2;
        private static final int MODE_RESIZE = 3;

        private int mode = MODE_NONE;
        private int activeCorner = -1;
        private Point dragStart;
        private Rectangle2D.Float dragStartRegion;

        ImageCanvas(BufferedImage image, Rectangle2D.Float region) {
            this.image = image;
            this.region = region;
            setBackground(new Color(0xF2, 0xF2, 0xF2));
            setPreferredSize(new Dimension(860, 560));
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            installMouseHandlers();
        }

        BufferedImage getImage() {
            return image;
        }

        void setImage(BufferedImage image) {
            this.image = image;
            repaint();
        }

        Rectangle2D.Float getRegion() {
            return region;
        }

        void setRegion(Rectangle2D.Float region) {
            this.region = region;
            repaint();
        }

        /** Where the image is drawn inside this component, preserving aspect ratio. */
        private Rectangle imageBounds() {
            int pw = getWidth();
            int ph = getHeight();
            if (image == null || pw <= 0 || ph <= 0) {
                return new Rectangle(0, 0, Math.max(1, pw), Math.max(1, ph));
            }
            double scale = Math.min(pw / (double) image.getWidth(), ph / (double) image.getHeight());
            int w = (int) Math.round(image.getWidth() * scale);
            int h = (int) Math.round(image.getHeight() * scale);
            return new Rectangle((pw - w) / 2, (ph - h) / 2, w, h);
        }

        private Rectangle2D.Float toScreen(Rectangle2D.Float normalised) {
            Rectangle b = imageBounds();
            return new Rectangle2D.Float(
                    (float) (b.x + normalised.x * b.width),
                    (float) (b.y + normalised.y * b.height),
                    (float) (normalised.width * b.width),
                    (float) (normalised.height * b.height));
        }

        private void installMouseHandlers() {
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    beginDrag(e.getPoint());
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    mode = MODE_NONE;
                    activeCorner = -1;
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    continueDrag(e.getPoint());
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    updateCursor(e.getPoint());
                }
            });
        }

        private int cornerAt(Point p) {
            if (region == null) {
                return -1;
            }
            Rectangle2D.Float r = toScreen(region);
            Point[] corners = {
                    new Point((int) r.x, (int) r.y),
                    new Point((int) (r.x + r.width), (int) r.y),
                    new Point((int) r.x, (int) (r.y + r.height)),
                    new Point((int) (r.x + r.width), (int) (r.y + r.height)),
            };
            for (int i = 0; i < corners.length; i++) {
                if (Math.abs(p.x - corners[i].x) <= HANDLE && Math.abs(p.y - corners[i].y) <= HANDLE) {
                    return i;
                }
            }
            return -1;
        }

        private void beginDrag(Point p) {
            if (image == null) {
                return;
            }
            dragStart = p;
            dragStartRegion = region == null ? null : new Rectangle2D.Float(
                    region.x, region.y, region.width, region.height);

            int corner = cornerAt(p);
            if (corner >= 0) {
                mode = MODE_RESIZE;
                activeCorner = corner;
                return;
            }
            if (region != null && toScreen(region).contains(p)) {
                mode = MODE_MOVE;
                return;
            }
            mode = MODE_NEW;
            region = new Rectangle2D.Float();
            dragStartRegion = null;
            repaint();
        }

        private void continueDrag(Point p) {
            if (image == null || mode == MODE_NONE) {
                return;
            }
            Rectangle b = imageBounds();
            float nx = clamp01((p.x - b.x) / (float) b.width);
            float ny = clamp01((p.y - b.y) / (float) b.height);
            float sx = clamp01((dragStart.x - b.x) / (float) b.width);
            float sy = clamp01((dragStart.y - b.y) / (float) b.height);

            switch (mode) {
                case MODE_NEW: {
                    float x = Math.min(nx, sx);
                    float y = Math.min(ny, sy);
                    region = new Rectangle2D.Float(x, y, Math.abs(nx - sx), Math.abs(ny - sy));
                    break;
                }
                case MODE_MOVE: {
                    if (dragStartRegion == null) {
                        break;
                    }
                    float dx = nx - sx;
                    float dy = ny - sy;
                    float x = clamp(dragStartRegion.x + dx, 0f, 1f - dragStartRegion.width);
                    float y = clamp(dragStartRegion.y + dy, 0f, 1f - dragStartRegion.height);
                    region = new Rectangle2D.Float(x, y, dragStartRegion.width, dragStartRegion.height);
                    break;
                }
                case MODE_RESIZE: {
                    if (dragStartRegion == null) {
                        break;
                    }
                    float left = dragStartRegion.x;
                    float top = dragStartRegion.y;
                    float right = left + dragStartRegion.width;
                    float bottom = top + dragStartRegion.height;
                    // corners: 0=NW 1=NE 2=SW 3=SE
                    if (activeCorner == 0) {
                        left = nx;
                        top = ny;
                    } else if (activeCorner == 1) {
                        right = nx;
                        top = ny;
                    } else if (activeCorner == 2) {
                        left = nx;
                        bottom = ny;
                    } else if (activeCorner == 3) {
                        right = nx;
                        bottom = ny;
                    }
                    float x = Math.min(left, right);
                    float y = Math.min(top, bottom);
                    region = new Rectangle2D.Float(x, y,
                            Math.abs(right - left), Math.abs(bottom - top));
                    break;
                }
                default:
                    break;
            }
            repaint();
        }

        private void updateCursor(Point p) {
            if (image == null) {
                return;
            }
            if (cornerAt(p) >= 0) {
                setCursor(Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR));
            } else if (region != null && toScreen(region).contains(p)) {
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            } else {
                setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

                if (image == null) {
                    g2.setColor(new Color(0x99, 0x99, 0x99));
                    g2.drawString("尚未选择图片", 20, 30);
                    return;
                }

                Rectangle b = imageBounds();
                g2.drawImage(image, b.x, b.y, b.width, b.height, null);

                if (region == null) {
                    return;
                }
                Rectangle2D.Float r = toScreen(region);

                // Preview the balance exactly as the widget will draw it.
                BalanceTextRenderer.drawRegion(g2, previewText, previewSubtitle, r, textColor);

                // Dim everything outside the region so the choice is obvious.
                java.awt.geom.Area outside = new java.awt.geom.Area(new Rectangle(0, 0, getWidth(), getHeight()));
                outside.subtract(new java.awt.geom.Area(r));
                g2.setColor(new Color(0, 0, 0, 70));
                g2.fill(outside);

                g2.setColor(new Color(0x2D, 0x8C, 0xFF));
                g2.setStroke(new java.awt.BasicStroke(2f));
                g2.draw(r);

                for (Point corner : new Point[]{
                        new Point((int) r.x, (int) r.y),
                        new Point((int) (r.x + r.width), (int) r.y),
                        new Point((int) r.x, (int) (r.y + r.height)),
                        new Point((int) (r.x + r.width), (int) (r.y + r.height))}) {
                    g2.setColor(Color.WHITE);
                    g2.fillRect(corner.x - HANDLE / 2, corner.y - HANDLE / 2, HANDLE, HANDLE);
                    g2.setColor(new Color(0x2D, 0x8C, 0xFF));
                    g2.drawRect(corner.x - HANDLE / 2, corner.y - HANDLE / 2, HANDLE, HANDLE);
                }
            } finally {
                g2.dispose();
            }
        }

        private float clamp01(float v) {
            return clamp(v, 0f, 1f);
        }

        private float clamp(float v, float lo, float hi) {
            return Math.max(lo, Math.min(hi, v));
        }
    }
}
