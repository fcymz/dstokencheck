package com.ruoyi.dstokencheck.ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

/**
 * Asks for a single line of text, in the app's own colours.
 *
 * <p>{@code JOptionPane.showInputDialog} would be one line of code, but it arrives as a light grey
 * system dialog in the middle of a dark app, and the preset feature is otherwise entirely in the
 * app's own style. This is small enough to be worth the consistency.
 *
 * <p>The caller can supply a hint function so the dialog comments on what is being typed — the
 * preset name field uses it to warn that saving will overwrite an existing preset, before the user
 * commits to anything.
 */
public class TextPromptDialog extends JDialog {

    /** Comments on the current text; return null or an empty string when there is nothing to say. */
    public interface Hint {
        String hintFor(String text);
    }

    private static final int ARC = 18;

    private final JTextField field;
    private final JLabel hintLabel = new JLabel(" ");
    private final Hint hint;

    private String value;
    private boolean confirmed;
    private Point dragOrigin;
    private Point windowOrigin;

    public TextPromptDialog(Frame owner, String title, String label, String initial, Hint hint) {
        super(owner, title, true);
        this.hint = hint;

        setUndecorated(true);
        // Opaque window + setShape for the rounded corners; see CardPanel for why not translucency.
        setBackground(Theme.BG_BOTTOM);

        field = new JTextField(initial == null ? "" : initial);
        field.setFont(Theme.ui(Font.PLAIN, 12.5f));
        field.setForeground(Theme.TEXT);
        field.setCaretColor(Theme.ACCENT_SOFT);
        field.setBackground(Theme.FIELD_BG);
        field.setOpaque(true);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER, 1),
                BorderFactory.createEmptyBorder(7, 9, 7, 9)));
        field.addActionListener(e -> onConfirm());
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateHint();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateHint();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateHint();
            }
        });

        buildUi(title, label);
        pack();
        setSize(Math.max(390, getWidth()), getHeight());
        setLocation(centred(owner));

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                onCancel();
            }
        });

        updateHint();
        SwingUtilities.invokeLater(field::requestFocusInWindow);
    }

    /** The text the user accepted, or null when the dialog was cancelled. */
    public String getValue() {
        return confirmed ? value : null;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    private void buildUi(String title, String label) {
        CardPanel card = new CardPanel(ARC);
        card.setLayout(new BorderLayout());

        JPanel root = new JPanel(new BorderLayout());
        root.setOpaque(false);
        root.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        JPanel headings = new JPanel();
        headings.setOpaque(false);
        headings.setLayout(new BoxLayout(headings, BoxLayout.Y_AXIS));
        JLabel heading = new JLabel(title);
        heading.setFont(Theme.ui(Font.BOLD, 14f));
        heading.setForeground(Theme.TEXT);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel caption = new JLabel(label);
        caption.setFont(Theme.ui(Font.PLAIN, 10.5f));
        caption.setForeground(Theme.TEXT_DIM);
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);
        headings.add(heading);
        headings.add(Box.createVerticalStrut(4));
        headings.add(caption);
        root.add(headings, BorderLayout.NORTH);
        installDrag(headings);
        installDrag(heading);
        installDrag(caption);

        JPanel middle = new JPanel();
        middle.setOpaque(false);
        middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height));
        hintLabel.setFont(Theme.ui(Font.PLAIN, 10f));
        hintLabel.setForeground(Theme.WARN);
        hintLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        middle.add(Box.createVerticalStrut(12));
        middle.add(field);
        middle.add(Box.createVerticalStrut(6));
        middle.add(hintLabel);
        root.add(middle, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        FlatButton cancel = new FlatButton("\u53d6\u6d88", FlatButton.Kind.SECONDARY);
        cancel.addActionListener(e -> onCancel());
        actions.add(cancel);
        FlatButton ok = new FlatButton("\u4fdd\u5b58", FlatButton.Kind.PRIMARY);
        ok.addActionListener(e -> onConfirm());
        actions.add(ok);
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        south.add(actions, BorderLayout.EAST);
        root.add(south, BorderLayout.SOUTH);

        card.add(root, BorderLayout.CENTER);
        setContentPane(card);
    }

    private void updateHint() {
        String text = hint == null ? null : hint.hintFor(field.getText());
        hintLabel.setText(text == null || text.isEmpty() ? " " : text);
    }

    private void onConfirm() {
        String text = field.getText() == null ? "" : field.getText().trim();
        if (text.isEmpty()) {
            hintLabel.setText("\u8bf7\u8f93\u5165\u540d\u5b57");
            return;
        }
        value = text;
        confirmed = true;
        dispose();
    }

    private void onCancel() {
        confirmed = false;
        value = null;
        dispose();
    }

    /** Over the widget when it has an owner, otherwise in the middle of that screen. */
    private Point centred(Frame owner) {
        GraphicsConfiguration gc = owner != null ? owner.getGraphicsConfiguration() : null;
        Rectangle screen;
        if (gc != null) {
            Rectangle bounds = gc.getBounds();
            Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            screen = new Rectangle(bounds.x + in.left, bounds.y + in.top,
                    bounds.width - in.left - in.right, bounds.height - in.top - in.bottom);
        } else {
            screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        }
        int x = owner != null ? owner.getX() + (owner.getWidth() - getWidth()) / 2
                : screen.x + (screen.width - getWidth()) / 2;
        int y = owner != null ? owner.getY() + (owner.getHeight() - getHeight()) / 2
                : screen.y + (screen.height - getHeight()) / 2;
        x = Math.max(screen.x, Math.min(x, screen.x + screen.width - getWidth()));
        y = Math.max(screen.y, Math.min(y, screen.y + screen.height - getHeight()));
        return new Point(x, y);
    }

    private void installDrag(JComponent c) {
        c.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
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
}
