package com.ruoyi.dstokencheck.ui;

import com.ruoyi.dstokencheck.config.AppConfig;
import com.ruoyi.dstokencheck.net.DeepSeekClient;
import com.ruoyi.dstokencheck.security.SecretStore;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.RoundRectangle2D;
import java.net.URI;

/**
 * Sign-in window. The only credential the app accepts is a DeepSeek API key.
 *
 * <p>The key is verified against {@code GET /user/balance} before the dialog closes, so a typo is
 * caught immediately. When "记住我" is ticked the key is encrypted via
 * {@link SecretStore} (Windows DPAPI by default) before being written to the config file; the
 * plaintext is never persisted.
 */
public class ApiKeyDialog extends JDialog {

    private static final String API_KEYS_URL = "https://platform.deepseek.com/api_keys";
    private static final int ARC = 18;

    private final AppConfig config;
    private final DeepSeekClient client;

    private final JPasswordField keyField = new JPasswordField();
    private final JCheckBox rememberBox = new JCheckBox("\u8bb0\u4f4f\u6211\uff08\u52a0\u5bc6\u4fdd\u5b58\u5230\u672c\u673a\uff09");
    private final JCheckBox showBox = new JCheckBox("\u663e\u793a");
    private final JLabel errorLabel = new JLabel(" ");
    private final JLabel hintLabel = new JLabel(" ");
    private final JLabel linkLabel = new JLabel("\u53bb platform.deepseek.com \u83b7\u53d6 API Key");
    private final JButton submitButton = new JButton("\u767b\u5f55");

    private boolean succeeded;
    private boolean busy;
    private Point dragOrigin;
    private Point windowOrigin;

    public ApiKeyDialog(Frame owner, AppConfig config, DeepSeekClient client) {
        super(owner, "DeepSeek API Key", true);
        this.config = config;
        this.client = client;

        setUndecorated(true);
        // Opaque window + setShape() for rounded corners: an alpha-0 background would put Java2D on
        // the per-pixel translucency path, where LCD text antialiasing renders glyphs fully
        // transparent and the whole form appears blank.
        setBackground(Theme.BG_BOTTOM);
        setContentPane(new CardPanel());

        buildUi();
        pack();
        setSize(400, getHeight());
        applyShape();
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                applyShape();
            }
        });
        setLocationRelativeTo(owner);
    }

    public boolean isSucceeded() {
        return succeeded;
    }

    private void applyShape() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        try {
            setShape(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), ARC, ARC));
        } catch (Exception ignored) {
            // Square corners are an acceptable fallback.
        }
    }

    private void buildUi() {
        JPanel root = (JPanel) getContentPane();
        root.setLayout(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(12, 20, 16, 20));

        // ---- title row ----
        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        JLabel title = new JLabel("DeepSeek API Key \u767b\u5f55");
        title.setFont(Theme.ui(Font.BOLD, 14f));
        title.setForeground(Theme.TEXT);
        titleRow.add(title, BorderLayout.WEST);

        IconButton close = new IconButton(IconButton.Glyph.CLOSE, "\u53d6\u6d88");
        close.addActionListener(e -> {
            succeeded = false;
            setVisible(false);
        });
        titleRow.add(close, BorderLayout.EAST);
        root.add(titleRow, BorderLayout.NORTH);

        // ---- form ----
        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(Box.createVerticalStrut(12));

        form.add(fieldLabel("API Key"));
        style(keyField);
        keyField.setEchoChar('\u2022');
        form.add(keyField);

        form.add(Box.createVerticalStrut(8));
        showBox.setOpaque(false);
        showBox.setFont(Theme.ui(Font.PLAIN, 10.5f));
        showBox.setForeground(Theme.TEXT_DIM);
        showBox.setFocusPainted(false);
        showBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        showBox.addActionListener(e -> keyField.setEchoChar(showBox.isSelected() ? (char) 0 : '\u2022'));
        form.add(showBox);

        form.add(Box.createVerticalStrut(4));
        rememberBox.setOpaque(false);
        rememberBox.setFont(Theme.ui(Font.PLAIN, 10.5f));
        rememberBox.setForeground(Theme.TEXT);
        rememberBox.setFocusPainted(false);
        rememberBox.setSelected(config.isRememberApiKey() || !config.hasStoredApiKey());
        rememberBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(rememberBox);

        form.add(Box.createVerticalStrut(6));
        hintLabel.setFont(Theme.ui(Font.PLAIN, 10f));
        hintLabel.setForeground(Theme.TEXT_DIM);
        hintLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        hintLabel.setText("\u52a0\u5bc6\u65b9\u5f0f: " + SecretStore.activeScheme());
        form.add(hintLabel);

        errorLabel.setFont(Theme.ui(Font.PLAIN, 10.5f));
        errorLabel.setForeground(Theme.DANGER);
        errorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(errorLabel);

        root.add(form, BorderLayout.CENTER);

        // ---- buttons ----
        JPanel south = new JPanel(new GridLayout(2, 1, 0, 8));
        south.setOpaque(false);

        submitButton.setFont(Theme.ui(Font.BOLD, 12f));
        submitButton.setForeground(Color.WHITE);
        submitButton.setBackground(Theme.BUTTON_BG);
        submitButton.setFocusPainted(false);
        submitButton.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
        submitButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        submitButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                submit();
            }
        });
        south.add(submitButton);

        linkLabel.setFont(Theme.ui(Font.PLAIN, 10.5f));
        linkLabel.setForeground(Theme.ACCENT_SOFT);
        linkLabel.setHorizontalAlignment(JLabel.CENTER);
        linkLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        linkLabel.setToolTipText(API_KEYS_URL);
        linkLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openApiKeysPage();
            }
        });
        south.add(linkLabel);

        root.add(south, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(submitButton);

        installDrag(root);
        installDrag(title);
        installDrag(titleRow);
        SwingUtilities.invokeLater(keyField::requestFocusInWindow);
    }

    private JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.ui(Font.PLAIN, 10.5f));
        l.setForeground(Theme.TEXT_DIM);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(0, 1, 3, 0));
        return l;
    }

    private void style(JPasswordField f) {
        f.setFont(Theme.mono(Font.PLAIN, 12f));
        f.setForeground(Theme.TEXT);
        f.setCaretColor(Theme.ACCENT_SOFT);
        f.setBackground(Theme.FIELD_BG);
        f.setOpaque(true);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER, 1),
                BorderFactory.createEmptyBorder(7, 9, 7, 9)));
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        f.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    private void openApiKeysPage() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(API_KEYS_URL));
                return;
            }
        } catch (Exception ignored) {
            // Fall through to the tooltip, which already shows the URL.
        }
        setError("\u65e0\u6cd5\u81ea\u52a8\u6253\u5f00\u6d4f\u89c8\u5668\uff0c\u8bf7\u624b\u52a8\u8bbf\u95ee " + API_KEYS_URL);
    }

    private void submit() {
        if (busy) {
            return;
        }
        final String key = new String(keyField.getPassword()).trim();
        if (key.isEmpty()) {
            setError("\u8bf7\u8f93\u5165 API Key");
            return;
        }

        setError(null);
        busy = true;
        submitButton.setEnabled(false);
        submitButton.setText("\u9a8c\u8bc1\u4e2d\u2026");

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                // Verify against the live endpoint so a bad key never gets saved.
                client.fetchBalance(key);
                return null;
            }

            @Override
            protected void done() {
                busy = false;
                submitButton.setEnabled(true);
                submitButton.setText("\u767b\u5f55");
                try {
                    get();
                    persist(key);
                    client.setApiKey(key);
                    succeeded = true;
                    setVisible(false);
                } catch (Exception ex) {
                    setError(message(ex));
                    keyField.selectAll();
                }
            }
        }.execute();
    }

    /** Stores the key according to the "记住我" choice, always encrypted. */
    private void persist(String key) {
        boolean remember = rememberBox.isSelected();
        config.setRememberApiKey(remember);
        if (remember) {
            config.setProtectedApiKey(SecretStore.protect(key));
        } else {
            config.setProtectedApiKey("");
        }
        config.save();
    }

    private static String message(Exception ex) {
        Throwable cause = (ex instanceof java.util.concurrent.ExecutionException && ex.getCause() != null)
                ? ex.getCause() : ex;
        String m = cause.getMessage();
        return (m == null || m.isEmpty()) ? cause.toString() : m;
    }

    private void setError(String text) {
        String message = (text == null || text.isEmpty()) ? " " : text;
        errorLabel.setText(message);
        errorLabel.setToolTipText(message.trim().isEmpty() ? null : message);
    }

    // --- dragging an undecorated dialog ---

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
                setLocation(windowOrigin.x + (now.x - dragOrigin.x), windowOrigin.y + (now.y - dragOrigin.y));
            }
        });
    }

    /** Rounded gradient card behind the form. */
    private static class CardPanel extends JPanel {
        CardPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                java.awt.Shape shape = new RoundRectangle2D.Float(0, 0, w - 1, h - 1, ARC, ARC);
                g2.setClip(shape);
                g2.setPaint(new java.awt.GradientPaint(0, 0, Theme.BG_TOP, 0, h, Theme.BG_BOTTOM));
                g2.fillRect(0, 0, w, h);
                g2.setClip(null);
                g2.setColor(Theme.alpha(Theme.ACCENT, 90));
                g2.draw(shape);
            } finally {
                g2.dispose();
            }
        }
    }
}
