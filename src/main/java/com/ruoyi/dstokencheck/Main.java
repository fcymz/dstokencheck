package com.ruoyi.dstokencheck;

import com.ruoyi.dstokencheck.autostart.AutoStart;
import com.ruoyi.dstokencheck.config.AppConfig;
import com.ruoyi.dstokencheck.model.BalanceSnapshot;
import com.ruoyi.dstokencheck.model.Wallet;
import com.ruoyi.dstokencheck.net.DeepSeekClient;
import com.ruoyi.dstokencheck.security.SecretStore;
import com.ruoyi.dstokencheck.ui.ApiKeyDialog;
import com.ruoyi.dstokencheck.ui.BalanceBoard;
import com.ruoyi.dstokencheck.util.Log;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.io.File;

/**
 * Entry point for the DeepSeek balance widget.
 *
 * <p>Runs the GUI with no arguments. {@code --diag <API_KEY>} exercises the balance call from a
 * terminal, {@code --selftest} checks the window's drag/resize handling, {@code --shot <dir>}
 * renders the windows to PNG, and {@code --demo} shows the widget with sample data.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        if (args.length > 0 && "--diag".equals(args[0])) {
            runDiagnostics(args);
            return;
        }

        if (args.length > 0 && "--autostart".equals(args[0])) {
            runAutoStartCommand(args);
            return;
        }

        boolean launchedAtLogon = false;
        for (String a : args) {
            if ("--demo".equals(a)) {
                System.setProperty("dstokencheck.demo", "true");
            }
            if (AutoStart.AUTOSTART_FLAG.equals(a)) {
                launchedAtLogon = true;
            }
        }

        if (args.length > 0 && "--selftest".equals(args[0])) {
            runSelfTest();
            return;
        }

        if (args.length > 0 && "--shot".equals(args[0])) {
            runShots(args.length > 1 ? args[1] : ".");
            return;
        }

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("当前环境没有图形界面，无法运行窗口程序。");
            System.exit(1);
        }

        final boolean autoStarted = launchedAtLogon;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                startGui(autoStarted);
            }
        });
    }

    // ------------------------------------------------------------------- GUI

    private static void startGui(boolean autoStarted) {
        AppConfig config = new AppConfig();
        DeepSeekClient client = new DeepSeekClient();

        Log.write("--- launch: startedAtLogon=" + autoStarted
                + " java=" + System.getProperty("java.version")
                + " jar=" + ownJarPath() + " ---");
        Log.write("config: rememberApiKey=" + config.isRememberApiKey()
                + " hasProtectedKey=" + !config.getProtectedApiKey().isEmpty()
                + " autoStartRegistryEntry=" + AutoStart.isEnabled());

        reconcileAutoStart(config);

        if (BalanceBoard.isDemo()) {
            showBoard(config, client);
            return;
        }

        if (autoStarted && !config.hasStoredApiKey()) {
            // Windows started us at logon but there is no credential to show a balance for.
            // Exiting quietly avoids a modal dialog greeting the user on every logon; launching
            // the app by hand still prompts as usual.
            Log.write("started at logon but no saved API key -> exiting without a window");
            System.exit(0);
            return;
        }

        String key = loadStoredKey(config);
        if (key == null) {
            // Nothing remembered: ask for a key before anything else.
            Log.write("no usable stored key -> showing the login window");
            if (!promptForKey(null, config, client)) {
                Log.write("login cancelled -> exiting");
                System.exit(0);
                return;
            }
            key = client.getApiKey();
        } else {
            Log.write("stored key decrypted OK -> showing the widget");
        }
        client.setApiKey(key);

        showBoard(config, client);
    }

    /** Where this build is running from, for the startup log. */
    private static String ownJarPath() {
        File jar = AutoStart.launcherJar();
        return jar == null ? "(not a jar)" : jar.getAbsolutePath();
    }

    /**
     * Keeps the Windows Run entry healthy.
     *
     * <p>The registry entry is the single source of truth for 开机自启 — the menu checkbox reads it
     * and toggling writes it — so this only has to keep an <em>existing</em> entry correct: it
     * refreshes the stable jar copy and rewrites the command if the JRE or jar path has changed.
     *
     * <p>It deliberately never creates or removes the entry. Creating it would resurrect a feature
     * the user turned off; removing it would destroy one they set up by hand.
     */
    private static void reconcileAutoStart(AppConfig config) {
        if (!AutoStart.isEnabled()) {
            return;
        }
        try {
            // Refresh the stable copy first: this is what keeps the entry valid after a rebuild.
            boolean stable = AutoStart.installStableJar();
            if (!AutoStart.isUpToDate()) {
                AutoStart.enable();
                Log.write("autostart: registry entry (re)written -> " + AutoStart.currentCommand());
            } else {
                Log.write("autostart: registry entry already up to date (stableJar=" + stable + ")");
            }
        } catch (AutoStart.AutoStartException e) {
            Log.write("autostart: reconcile failed", e);
        }
    }

    /**
     * Decrypts the remembered key, or returns {@code null} when there is none or it cannot be
     * decrypted (different user/machine, or tampered file).
     */
    private static String loadStoredKey(AppConfig config) {
        if (!config.hasStoredApiKey()) {
            return null;
        }
        String stored = config.getProtectedApiKey();
        String plain = SecretStore.unprotect(stored);
        if (plain == null || plain.trim().isEmpty()) {
            // Unreadable: drop it so we do not keep failing on every launch.
            config.clearCredentials();
            config.save();
            return null;
        }
        return plain;
    }

    private static void showBoard(final AppConfig config, final DeepSeekClient client) {
        final BalanceBoard[] holder = new BalanceBoard[1];

        BalanceBoard board = new BalanceBoard(config, client, new BalanceBoard.AuthEvents() {
            @Override
            public void onAuthFailed() {
                // The saved key is no longer accepted: forget it and ask again.
                config.clearCredentials();
                config.save();
                reauthenticate(holder[0], config, client);
            }

            @Override
            public void onLogout() {
                config.clearCredentials();
                config.save();
                reauthenticate(holder[0], config, client);
            }
        });

        holder[0] = board;
        board.setVisible(true);
        board.start();
    }

    /**
     * Hides the widget and asks for a key again. When the user cancels, the app exits — there is
     * nothing useful to show without a credential.
     */
    private static void reauthenticate(BalanceBoard board, AppConfig config, DeepSeekClient client) {
        if (board != null) {
            board.setVisible(false);
            board.dispose();
        }
        // A successful dialog has already put the verified key on the client.
        if (!promptForKey(null, config, client)) {
            System.exit(0);
            return;
        }
        showBoard(config, client);
    }

    /** Shows the API-key dialog; returns true when the user authenticated successfully. */
    private static boolean promptForKey(java.awt.Frame owner, AppConfig config, DeepSeekClient client) {
        ApiKeyDialog dialog = new ApiKeyDialog(owner, config, client);
        dialog.setVisible(true);
        return dialog.isSucceeded();
    }

    // ------------------------------------------------------------ diagnostics

    /**
     * Exercises the window's hand-written drag/resize handling with synthetic mouse events.
     *
     * <p>An undecorated window gets no OS resize grips, so this logic is entirely ours and is the
     * part most likely to regress silently. Events are posted to the real event queue so the
     * registered {@code AWTEventListener} sees them exactly as it would from a real mouse.
     */
    private static void runSelfTest() {
        System.setProperty("dstokencheck.demo", "true");
        // Run against a throwaway home directory: the test moves/resizes the window, which persists
        // bounds, so without this it would overwrite the user's real settings and log.
        System.setProperty("user.home",
                new File(System.getProperty("java.io.tmpdir"), "dstokencheck-selftest").getAbsolutePath());

        final BalanceBoard[] ref = new BalanceBoard[1];
        final boolean[] logoutFired = new boolean[1];
        boolean[] pass = new boolean[9];

        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    DeepSeekClient demoClient = new DeepSeekClient();
                    demoClient.setApiKey("sk-demo-000000000000000000000000");
                    BalanceBoard b = new BalanceBoard(new AppConfig(), demoClient, new BalanceBoard.AuthEvents() {
                        @Override
                        public void onAuthFailed() {
                            // Not exercised here.
                        }

                        @Override
                        public void onLogout() {
                            logoutFired[0] = true;
                        }
                    });
                    b.setLocation(200, 200);
                    b.setSize(320, 170);
                    b.setVisible(true);
                    b.start();
                    ref[0] = b;
                }
            });
            Thread.sleep(800);

            BalanceBoard b = ref[0];

            Rectangle r0 = b.getBounds();
            drag(b, new Point(r0.x + r0.width - 2, r0.y + r0.height - 2), 60, 40);
            Thread.sleep(400);
            Rectangle r1 = b.getBounds();
            pass[0] = r1.width > r0.width && r1.height > r0.height;
            System.out.println((pass[0] ? "PASS" : "FAIL") + "  \u53f3\u4e0b\u89d2\u62d6\u62fd\u7f29\u653e: "
                    + r0.width + "x" + r0.height + " -> " + r1.width + "x" + r1.height);

            drag(b, new Point(r1.x + r1.width / 2, r1.y + r1.height / 2), 50, 30);
            Thread.sleep(400);
            Rectangle r2 = b.getBounds();
            pass[1] = r2.x == r1.x + 50 && r2.y == r1.y + 30;
            System.out.println((pass[1] ? "PASS" : "FAIL") + "  \u7a7a\u767d\u5904\u62d6\u52a8\u79fb\u52a8: ("
                    + r1.x + "," + r1.y + ") -> (" + r2.x + "," + r2.y + ")");

            drag(b, new Point(r2.x + r2.width - 2, r2.y + r2.height - 2), -2000, -2000);
            Thread.sleep(400);
            Rectangle r3 = b.getBounds();
            Dimension limit = b.getMinimumSize();
            pass[2] = r3.width >= limit.width && r3.height >= limit.height;
            System.out.println((pass[2] ? "PASS" : "FAIL") + "  \u6700\u5c0f\u5c3a\u5bf8\u9650\u5236: "
                    + r3.width + "x" + r3.height + " (\u4e0b\u9650 "
                    + limit.width + "x" + limit.height + ")");

            // The 退出登录 entry must exist in the right-click menu and actually fire its callback.
            JPopupMenu menu = (b.getContentPane() instanceof javax.swing.JComponent)
                    ? ((javax.swing.JComponent) b.getContentPane()).getComponentPopupMenu()
                    : null;
            JMenuItem logoutItem = findMenuItem(menu, "\u9000\u51fa\u767b\u5f55");
            pass[3] = logoutItem != null;
            if (pass[3]) {
                final JMenuItem item = logoutItem;
                SwingUtilities.invokeAndWait(new Runnable() {
                    @Override
                    public void run() {
                        item.doClick();
                    }
                });
                Thread.sleep(300);
                pass[3] = logoutFired[0];
            }
            System.out.println((pass[3] ? "PASS" : "FAIL")
                    + "  \u53f3\u952e\u83dc\u5355\u300c\u9000\u51fa\u767b\u5f55\u300d\u5b58\u5728\u4e14\u56de\u8c03\u751f\u6548");

            // 开机自启 must be clickable whenever the platform supports it. It used to be disabled
            // outright when the jar path could not be determined (running from an IDE), which left
            // an unexplained grey entry the user could not tick.
            JMenuItem autoStartItem = findMenuItem(menu, "\u5f00\u673a\u81ea\u542f");
            boolean commandAvailable = AutoStart.buildCommand() != null;
            pass[4] = autoStartItem != null && autoStartItem.isEnabled() == AutoStart.isSupported();
            System.out.println((pass[4] ? "PASS" : "FAIL")
                    + "  \u53f3\u952e\u83dc\u5355\u300c\u5f00\u673a\u81ea\u542f\u300d\u53ef\u70b9\u51fb"
                    + " (\u53ef\u5199\u5165\u6ce8\u5f00\u8868=" + commandAvailable
                    + ", \u8fd0\u884c\u65b9\u5f0f=" + (commandAvailable ? "jar" : "IDE/classes") + ")");

            // The context menu must open in its own window AND a press on one of its items must not
            // be taken for a window drag. With a lightweight popup both fail: the menu is clipped by
            // the small widget, and its items sit inside the drag surface, so clicking moves the
            // window and the click never lands on the item.
            pass[5] = checkMenuInteraction(b, menu);

            pass[6] = checkLauncherIsAsciiOnly();

            pass[7] = checkFontScaleMenu(b, menu);

            pass[8] = checkRefreshFloor(menu);

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    ref[0].dispose();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        boolean allPassed = true;
        for (boolean p : pass) {
            allPassed = allPassed && p;
        }
        System.exit(allPassed ? 0 : 1);
    }

    /**
     * Opens the widget's context menu and verifies it behaves as a real menu:
     * it must live in its own window (so the small widget cannot clip it) and pressing one of its
     * entries must not be interpreted as dragging the widget.
     *
     * <p>The harmless "立即刷新" entry is used for the press so the test has no side effects.
     */
    private static boolean checkMenuInteraction(BalanceBoard board, final JPopupMenu menu) throws Exception {
        if (menu == null) {
            System.out.println("FAIL  右键菜单不存在");
            return false;
        }
        final JPopupMenu popup = menu;
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                popup.show(board.getContentPane(), 20, 20);
            }
        });
        Thread.sleep(500);

        java.awt.Window popupWindow = SwingUtilities.getWindowAncestor(popup);
        boolean ownWindow = popup.isVisible() && popupWindow != null && popupWindow != board;

        JMenuItem refreshItem = findMenuItem(menu, "\u7acb\u5373\u5237\u65b0");
        Rectangle before = board.getBounds();
        if (refreshItem != null) {
            pressAndNudge(refreshItem, 10, 8);
            Thread.sleep(400);
        }
        boolean stayedPut = before.equals(board.getBounds());

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                popup.setVisible(false);
            }
        });

        boolean ok = ownWindow && stayedPut && refreshItem != null;
        System.out.println((ok ? "PASS" : "FAIL")
                + "  \u83dc\u5355\u72ec\u7acb\u7a97\u53e3=" + ownWindow
                + "\uff0c\u70b9\u51fb\u83dc\u5355\u9879\u4e0d\u62d6\u52a8\u7a97\u53e3=" + stayedPut);
        return ok;
    }

    /** Presses at a component's centre, nudges by (dx,dy), then releases. */
    private static void pressAndNudge(java.awt.Component src, int dx, int dy) throws Exception {
        Point origin = src.getLocationOnScreen();
        Point start = new Point(origin.x + src.getWidth() / 2, origin.y + src.getHeight() / 2);
        post(src, java.awt.event.MouseEvent.MOUSE_PRESSED, start);
        Thread.sleep(60);
        post(src, java.awt.event.MouseEvent.MOUSE_DRAGGED, new Point(start.x + dx, start.y + dy));
        Thread.sleep(60);
        post(src, java.awt.event.MouseEvent.MOUSE_RELEASED, new Point(start.x + dx, start.y + dy));
        Thread.sleep(60);
    }

    /**
     * {@code run.bat} must stay ASCII-only.
     *
     * <p>{@code cmd.exe} decodes a batch file using the OEM code page, not UTF-8. Non-ASCII bytes
     * (Chinese comments were the original offender) decode into garbage that can contain command
     * separators, so the comment lines get executed as commands: the script printed
     * "is not recognized as an internal or external command" and even ran {@code mvn clean package}
     * out of a comment, then never reached the launch step. The failure is silent and confusing, so
     * it is worth a permanent guard.
     *
     * <p>Skipped when the file cannot be located (for example when running from a classes directory).
     */
    private static boolean checkLauncherIsAsciiOnly() {
        java.io.File launcher = locateProjectFile("run.bat");
        if (launcher == null) {
            System.out.println("SKIP  \u672a\u627e\u5230 run.bat\uff0c\u8df3\u8fc7\u7f16\u7801\u68c0\u67e5");
            return true;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(launcher.toPath());
            int nonAscii = 0;
            for (byte b : bytes) {
                if ((b & 0xFF) > 127) {
                    nonAscii++;
                }
            }
            boolean ok = nonAscii == 0;
            System.out.println((ok ? "PASS" : "FAIL") + "  run.bat \u4ec5\u542b ASCII\u5b57\u7b26"
                    + " (\u975e ASCII \u5b57\u8282\u6570=" + nonAscii + ")");
            return ok;
        } catch (Exception e) {
            System.out.println("FAIL  \u65e0\u6cd5\u8bfb\u53d6 run.bat: " + e.getMessage());
            return false;
        }
    }

    /** Locates a file in the project root, relative to wherever this class was loaded from. */
    private static java.io.File locateProjectFile(String name) {
        try {
            java.net.URL location = Main.class.getProtectionDomain().getCodeSource().getLocation();
            java.io.File self = new java.io.File(location.toURI());
            // .../target/dstokencheck.jar -> project root is one level above target.
            java.io.File root = self.isFile() ? self.getParentFile().getParentFile() : self;
            java.io.File candidate = new java.io.File(root, name);
            return candidate.isFile() ? candidate : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Finds a menu entry by its exact label, or null when the menu has no such item. */
    private static JMenuItem findMenuItem(java.awt.Container menu, String label) {
        if (menu == null) {
            return null;
        }
        for (java.awt.Component c : childComponents(menu)) {
            if (c instanceof JMenuItem && label.equals(((JMenuItem) c).getText())) {
                return (JMenuItem) c;
            }
        }
        return null;
    }

    /** Finds a submenu whose label starts with {@code prefix}. */
    private static javax.swing.JMenu findSubMenu(java.awt.Container menu, String prefix) {
        if (menu == null) {
            return null;
        }
        for (java.awt.Component c : childComponents(menu)) {
            if (c instanceof javax.swing.JMenu
                    && ((javax.swing.JMenu) c).getText().startsWith(prefix)) {
                return (javax.swing.JMenu) c;
            }
        }
        return null;
    }

    /**
     * A {@link javax.swing.JMenu} keeps its entries in an internal popup, so {@code getComponents()}
     * does not see them; {@code getMenuComponents()} is the accessor for that case. A
     * {@link JPopupMenu} does hold its entries directly.
     */
    private static java.awt.Component[] childComponents(java.awt.Container menu) {
        if (menu instanceof javax.swing.JMenu) {
            return ((javax.swing.JMenu) menu).getMenuComponents();
        }
        return menu.getComponents();
    }

    /**
     * Drives the 字体大小 submenu for real: picking 200% must enlarge the widget's minimum size
     * (which is derived from the font scale), and picking 100% must put it back.
     */
    private static boolean checkFontScaleMenu(final BalanceBoard board, JPopupMenu menu) throws Exception {
        javax.swing.JMenu fontMenu = findSubMenu(menu, "\u5b57\u4f53\u5927\u5c0f");
        if (fontMenu == null) {
            System.out.println("FAIL  \u627e\u4e0d\u5230\u300c\u5b57\u4f53\u5927\u5c0f\u300d\u5b50\u83dc\u5355");
            return false;
        }
        JMenuItem large = findMenuItem(fontMenu, "200%");
        JMenuItem normal = findMenuItem(fontMenu, "100%");
        if (large == null || normal == null) {
            System.out.println("FAIL  \u5b57\u4f53\u5b50\u83dc\u5355\u7f3a\u5c11 100%/200% \u9884\u8bbe");
            return false;
        }

        Dimension before = board.getMinimumSize();
        clickOnEdt(large);
        Thread.sleep(300);
        Dimension enlarged = board.getMinimumSize();

        clickOnEdt(normal);
        Thread.sleep(300);
        Dimension restored = board.getMinimumSize();

        boolean ok = enlarged.width > before.width
                && enlarged.height > before.height
                && Math.abs(restored.width - before.width) <= 1;
        System.out.println((ok ? "PASS" : "FAIL") + "  \u5b57\u4f53\u5927\u5c0f\u53ef\u8c03: \u4e0b\u9650 "
                + before.width + "x" + before.height + " -> " + enlarged.width + "x" + enlarged.height
                + " -> " + restored.width + "x" + restored.height);
        return ok;
    }

    private static void clickOnEdt(final JMenuItem item) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                item.doClick();
            }
        });
    }

    /** The refresh floor must be 5 seconds and be enforced by the config. */
    private static boolean checkRefreshFloor(JPopupMenu menu) {
        AppConfig probe = new AppConfig();
        probe.setRefreshSeconds(1);
        boolean clamped = probe.getRefreshSeconds() == 5;
        boolean menuHas5 = findMenuItem(menu, "\u6bcf 5 \u79d2\u5237\u65b0") != null;
        boolean ok = AppConfig.MIN_REFRESH_SECONDS == 5 && clamped && menuHas5;
        System.out.println((ok ? "PASS" : "FAIL") + "  \u6700\u4f4e\u5237\u65b0\u5468\u671f=5\u79d2"
                + " (\u9650\u5236=" + AppConfig.MIN_REFRESH_SECONDS
                + ", \u4f20\u5165 1 \u79d2\u540e\u5f97\u5230=" + probe.getRefreshSeconds()
                + ", \u83dc\u5355\u9879=" + menuHas5 + ")");
        return ok;
    }

    /** Presses at a screen point, drags by (dx,dy) in steps, then releases. */
    private static void drag(BalanceBoard board, Point screenStart, int dx, int dy) throws Exception {
        java.awt.Component src = board.getContentPane();

        post(src, java.awt.event.MouseEvent.MOUSE_PRESSED, screenStart);
        Thread.sleep(80);
        for (int i = 1; i <= 5; i++) {
            post(src, java.awt.event.MouseEvent.MOUSE_DRAGGED,
                    new Point(screenStart.x + dx * i / 5, screenStart.y + dy * i / 5));
            Thread.sleep(50);
        }
        post(src, java.awt.event.MouseEvent.MOUSE_RELEASED,
                new Point(screenStart.x + dx, screenStart.y + dy));
        Thread.sleep(80);
    }

    /**
     * Posts a synthetic mouse event whose <em>screen</em> position is {@code screenPoint}.
     *
     * <p>The public {@code MouseEvent} constructor bakes the absolute coordinates in as
     * {@code component.getLocationOnScreen() + local}, so the local point has to be derived from the
     * component position at construction time. Reusing one local point across a drag would make the
     * screen position drift as the window moves — an artefact of the test, not of the app, because a
     * real event carries the true cursor position.
     */
    private static void post(java.awt.Component src, int id, Point screenPoint) {
        Point origin = src.getLocationOnScreen();
        int lx = screenPoint.x - origin.x;
        int ly = screenPoint.y - origin.y;
        int mods = (id == java.awt.event.MouseEvent.MOUSE_DRAGGED)
                ? java.awt.event.InputEvent.BUTTON1_DOWN_MASK : 0;
        java.awt.event.MouseEvent e = new java.awt.event.MouseEvent(
                src, id, System.currentTimeMillis(), mods, lx, ly, 1, false,
                java.awt.event.MouseEvent.BUTTON1);
        java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(e);
    }

    /**
     * Renders the two windows straight into PNG files.
     *
     * <p>Painting offscreen is DPI-independent and does not depend on screen capture, which makes it
     * a reliable way to check the layout on a scaled display. The target is {@code TYPE_INT_RGB} to
     * match the opaque on-screen surface.
     */
    private static void runShots(String dir) {
        System.setProperty("dstokencheck.demo", "true");
        final java.io.File outDir = new java.io.File(dir);
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
        final java.io.File dialogPng = new java.io.File(outDir, "apikey-window.png");
        final java.io.File boardPng = new java.io.File(outDir, "demo-window.png");

        try {
            final AppConfig config = new AppConfig();
            final DeepSeekClient client = new DeepSeekClient();
            final ApiKeyDialog[] dialogRef = new ApiKeyDialog[1];
            final BalanceBoard[] boardRef = new BalanceBoard[1];

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    ApiKeyDialog dialog = new ApiKeyDialog(null, config, client);
                    // Modality would block the invoking thread inside setVisible.
                    dialog.setModal(false);
                    dialogRef[0] = dialog;

                    BalanceBoard board = new BalanceBoard(config, client, null);
                    board.setSize(360, 180);
                    board.setLocation(0, 0);
                    boardRef[0] = board;

                    dialog.setVisible(true);
                    board.setVisible(true);
                    board.start();
                }
            });

            // Off the EDT: let the refresh timer fire and the windows finish rendering.
            Thread.sleep(900);

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    try {
                        shoot(dialogRef[0], dialogPng);
                        shoot(boardRef[0], boardPng);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    dialogRef[0].dispose();
                    boardRef[0].dispose();
                }
            });
            System.out.println("截图已保存到 " + outDir.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("生成截图失败: " + e);
            System.exit(1);
        }
        System.exit(0);
    }

    private static void shoot(java.awt.Window window, java.io.File target) throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                Math.max(1, window.getWidth()), Math.max(1, window.getHeight()),
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            window.paint(g);
        } finally {
            g.dispose();
        }
        javax.imageio.ImageIO.write(img, "png", target);
        System.out.println("  " + target.getName() + " (" + img.getWidth() + "x" + img.getHeight() + ")");
    }

    /**
     * {@code --autostart [status|on|off]} — inspect or change logon startup without the GUI.
     * Mirrors the menu so both stay in sync (the config file records the intent either way).
     */
    private static void runAutoStartCommand(String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase() : "status";

        System.out.println("系统支持 : " + (AutoStart.isSupported() ? "是" : "否（仅 Windows）"));
        System.out.println("注册表项 : HKCU\\" + "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
                + "\\" + AutoStart.VALUE_NAME);
        System.out.println("稳定副本 : " + AutoStart.stableJarPath().getAbsolutePath()
                + " (存在=" + AutoStart.stableJarPath().isFile() + ")");
        System.out.println("将要写入 : " + orNone(AutoStart.buildCommand()));
        System.out.println("当前写入 : " + orNone(AutoStart.currentCommand()));
        System.out.println("是否最新 : " + (AutoStart.isUpToDate() ? "是" : "否"));

        try {
            if ("on".equals(action)) {
                AutoStart.enable();
                System.out.println("=> 已开启开机自启");
            } else if ("off".equals(action)) {
                AutoStart.disable();
                System.out.println("=> 已关闭开机自启");
            } else if (!"status".equals(action)) {
                System.out.println("用法: --autostart [status|on|off]");
                return;
            }
        } catch (AutoStart.AutoStartException e) {
            System.err.println("失败: " + e.getMessage());
            System.exit(1);
        }
        System.out.println("=> 当前状态: " + (AutoStart.isEnabled() ? "已开启" : "未开启"));
    }

    private static String orNone(String s) {
        return (s == null || s.isEmpty()) ? "(无)" : s;
    }

    private static void runDiagnostics(String[] args) {        if (args.length < 2) {
            System.out.println("用法: java -jar dstokencheck.jar --diag <API_KEY>");
            System.out.println();
            System.out.println("存储加密方式: " + SecretStore.activeScheme());
            return;
        }
        DeepSeekClient client = new DeepSeekClient();
        try {
            System.out.println("正在读取余额 ...");
            BalanceSnapshot snap = client.fetchBalance(args[1]);
            printSnapshot(snap);
        } catch (Exception e) {
            System.err.println("失败: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printSnapshot(BalanceSnapshot snap) {
        System.out.println("========== 余额 ==========");
        for (Wallet w : snap.getWallets()) {
            System.out.println("  " + w.getCurrency() + "  " + w.getBalance()
                    + (w.isBonus() ? "  (赠送)" : ""));
        }
        System.out.println("  合计(" + snap.primaryCurrency() + "): "
                + snap.primarySymbol() + snap.totalInPrimaryCurrency().toPlainString());
        System.out.println("  来源: " + snap.getSource());
    }
}
