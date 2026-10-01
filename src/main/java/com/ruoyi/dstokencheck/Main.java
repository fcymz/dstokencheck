package com.ruoyi.dstokencheck;

import com.ruoyi.dstokencheck.autostart.AutoStart;
import com.ruoyi.dstokencheck.config.AppConfig;
import com.ruoyi.dstokencheck.config.Preset;
import com.ruoyi.dstokencheck.model.BalanceSnapshot;
import com.ruoyi.dstokencheck.model.Wallet;
import com.ruoyi.dstokencheck.net.DeepSeekClient;
import com.ruoyi.dstokencheck.security.SecretStore;
import com.ruoyi.dstokencheck.ui.ApiKeyDialog;
import com.ruoyi.dstokencheck.ui.BackgroundRegionDialog;
import com.ruoyi.dstokencheck.ui.BalanceBoard;
import com.ruoyi.dstokencheck.ui.Theme;
import com.ruoyi.dstokencheck.util.Log;

import javax.swing.JLabel;
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

        if (args.length > 0 && "--preset".equals(args[0])) {
            runPresetCommand(args);
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
        File running = AutoStart.runningJar();
        if (running != null) {
            return running.getAbsolutePath();
        }
        File launcher = AutoStart.launcherJar();
        return launcher == null ? "(not a jar)" : launcher.getAbsolutePath();
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
        boolean[] pass = new boolean[19];

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

            pass[9] = checkBackgroundConfig();

            pass[10] = checkBalanceSitsInsideFramedRegion();

            pass[11] = checkBackgroundRegionEditor();

            pass[12] = checkCropMatchesWindow();

            pass[13] = checkIrregularCropMatchesWindow();

            pass[14] = checkTransparentPicture();

            pass[15] = checkBundledPreset(menu);

            pass[16] = checkUserPreset();

            pass[17] = checkMenuSkin(menu);

            pass[18] = checkPeakHoursAndUsage();

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

    private static void clickOnEdt(final javax.swing.AbstractButton item) throws Exception {
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

    /**
     * The custom background must survive a save/reload cycle, and the framed region must be pinned
     * inside the image even when the stored numbers are nonsense.
     *
     * <p>Also proves that removing a background really deletes the copy the app made, rather than
     * leaving an orphaned image in the user's config directory.
     */
    private static boolean checkBackgroundConfig() {
        AppConfig probe = new AppConfig();
        // Start from a known state: an earlier run may have left a background behind.
        probe.removeBackgroundImage();
        java.io.File source = new java.io.File(AppConfig.directory(), "selftest-source.png");
        try {
            writeTestImage(source, 300, 150, new java.awt.Color(0x40, 0x40, 0x40));
            java.io.File stored = probe.storeBackgroundImage(source);
            probe.setBalanceRegion(new java.awt.geom.Rectangle2D.Float(0.20f, 0.30f, 0.60f, 0.40f));
            probe.setImageCrop(new java.awt.geom.Rectangle2D.Float(0.10f, 0.20f, 0.50f, 0.60f));
            probe.setBalanceTextColor(new java.awt.Color(0xFF, 0xCC, 0x00));
            probe.save();

            AppConfig reloaded = new AppConfig();
            java.awt.geom.Rectangle2D.Float r = reloaded.getBalanceRegion();
            java.awt.geom.Rectangle2D.Float c = reloaded.getImageCrop();
            boolean roundTrip = reloaded.hasBackgroundImage()
                    && stored != null && stored.isFile()
                    && r != null
                    && Math.abs(r.x - 0.20f) < 0.002f && Math.abs(r.y - 0.30f) < 0.002f
                    && Math.abs(r.width - 0.60f) < 0.002f && Math.abs(r.height - 0.40f) < 0.002f
                    && c != null
                    && Math.abs(c.x - 0.10f) < 0.002f && Math.abs(c.y - 0.20f) < 0.002f
                    && Math.abs(c.width - 0.50f) < 0.002f && Math.abs(c.height - 0.60f) < 0.002f
                    && reloaded.getBalanceTextColor().equals(new java.awt.Color(0xFF, 0xCC, 0x00));

            // A region that would hang off the edge has to be pulled back inside.
            reloaded.setBalanceRegion(new java.awt.geom.Rectangle2D.Float(0.9f, 0.9f, 0.5f, 0.5f));
            java.awt.geom.Rectangle2D.Float clamped = reloaded.getBalanceRegion();
            boolean inside = clamped.x >= 0f && clamped.y >= 0f
                    && clamped.x + clamped.width <= 1.001f
                    && clamped.y + clamped.height <= 1.001f;

            // A "crop" of the whole picture is the same thing as no crop, and is stored as such.
            reloaded.setImageCrop(new java.awt.geom.Rectangle2D.Float(0f, 0f, 1f, 1f));
            boolean fullFrameDropped = !reloaded.hasImageCrop()
                    && reloaded.getEffectiveCrop().width == 1f;

            java.io.File copy = reloaded.getBackgroundImageFile();
            reloaded.removeBackgroundImage();
            boolean removed = !reloaded.hasBackgroundImage()
                    && !reloaded.hasImageCrop()
                    && (copy == null || !copy.exists());

            boolean ok = roundTrip && inside && fullFrameDropped && removed;
            System.out.println((ok ? "PASS" : "FAIL") + "  \u80cc\u666f\u56fe\u8bbe\u7f6e"
                    + " (\u4fdd\u5b58/\u8bfb\u56de=" + roundTrip
                    + ", \u8d8a\u754c\u533a\u57df\u88ab\u6536\u56de=" + inside
                    + ", \u5168\u5e45\u88c1\u526a\u4e0d\u5199\u76d8=" + fullFrameDropped
                    + ", \u5220\u9664\u540e\u65e0\u6b8b\u7559=" + removed + ")");
            return ok;
        } catch (Exception e) {
            System.out.println("FAIL  \u80cc\u666f\u56fe\u8bbe\u7f6e: " + e);
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
        }
    }

    /**
     * The point of the feature: with a background image configured, the balance figure must be
     * painted inside the framed box and nowhere else.
     *
     * <p>Checked on the pixels the widget actually produces. The region is deliberately off-centre,
     * so text drawn at the old fixed spot would land outside it and fail the test.
     */
    private static boolean checkBalanceSitsInsideFramedRegion() {
        final int cardW = 300;
        final int cardH = 150;
        final float rx = 0.52f;
        final float ry = 0.32f;
        final float rw = 0.44f;
        final float rh = 0.34f;

        AppConfig config = new AppConfig();
        java.io.File source = new java.io.File(AppConfig.directory(), "selftest-region.png");
        final BalanceBoard[] ref = new BalanceBoard[1];
        try {
            writeTestImage(source, cardW, cardH, new java.awt.Color(0x40, 0x40, 0x40));
            config.storeBackgroundImage(source);
            config.setBalanceRegion(new java.awt.geom.Rectangle2D.Float(rx, ry, rw, rh));
            config.setBalanceTextColor(java.awt.Color.WHITE);
            config.save();

            DeepSeekClient demoClient = new DeepSeekClient();
            demoClient.setApiKey("sk-demo-000000000000000000000000");
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    BalanceBoard b = new BalanceBoard(config, demoClient, null);
                    // Off-screen: this runs while the selftest window is on the desktop.
                    b.setLocation(-4000, -4000);
                    b.setSize(cardW, cardH);
                    b.setVisible(true);
                    b.start();
                    ref[0] = b;
                }
            });
            // Let the first (immediate) refresh put a balance into the frame.
            Thread.sleep(700);

            java.awt.image.BufferedImage shot = new java.awt.image.BufferedImage(
                    cardW, cardH, java.awt.image.BufferedImage.TYPE_INT_RGB);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    java.awt.Graphics2D g = shot.createGraphics();
                    try {
                        ref[0].paint(g);
                    } finally {
                        g.dispose();
                    }
                }
            });
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    ref[0].dispose();
                }
            });

            // The image is exactly the size of the card, so the cover transform is the identity.
            int x0 = Math.round(rx * cardW);
            int y0 = Math.round(ry * cardH);
            int x1 = x0 + Math.round(rw * cardW);
            int y1 = y0 + Math.round(rh * cardH);

            int bright = 0;
            int outside = 0;
            for (int y = 0; y < cardH; y++) {
                for (int x = 0; x < cardW; x++) {
                    int rgb = shot.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    // White-ish: the balance figure (and its shadow is black, so it is excluded).
                    if (r > 200 && g > 200 && b > 200) {
                        bright++;
                        if (x < x0 || x >= x1 || y < y0 || y >= y1) {
                            outside++;
                        }
                    }
                }
            }

            boolean ok = bright > 50 && outside == 0;
            System.out.println((ok ? "PASS" : "FAIL") + "  \u4f59\u989d\u6570\u5b57\u843d\u5728\u6846\u5b9a\u533a\u57df\u5185"
                    + " (\u533a\u57df=" + x0 + "," + y0 + "-" + x1 + "," + y1
                    + ", \u4eae\u8272\u50cf\u7d20=" + bright
                    + ", \u533a\u57df\u5916\u4eae\u8272\u50cf\u7d20=" + outside + ")");
            return ok;
        } catch (Exception e) {
            System.out.println("FAIL  \u4f59\u989d\u6570\u5b57\u843d\u5728\u6846\u5b9a\u533a\u57df\u5185: " + e);
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            config.removeBackgroundImage();
        }
    }

    /**
     * Drives the background editor the way a user does: open it, drag a box on the picture, save.
     *
     * <p>The dialog is a window of hand-painted, hand-hit-tested controls, and the part that can
     * break silently is the mapping between a drag in screen coordinates and the normalised region
     * that ends up in the settings. So this does not poke at internals — it posts real mouse events
     * at the canvas and then reads what the widget would be told.
     *
     * <p>Also checks the obvious dead end: with no picture chosen there is nothing to save, and the
     * save button has to say so.
     */
    private static boolean checkBackgroundRegionEditor() {
        AppConfig config = new AppConfig();
        config.removeBackgroundImage();
        java.io.File source = new java.io.File(AppConfig.directory(), "selftest-editor.png");

        boolean disabledWithoutImage = false;
        boolean canvasFound = false;
        boolean saved = false;
        boolean regionChanged = false;
        boolean regionValid = false;
        try {
            // ---- no picture yet: saving must not be possible ----
            final BackgroundRegionDialog[] opened = new BackgroundRegionDialog[1];
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    opened[0] = showEditor(config);
                }
            });
            Thread.sleep(400);
            javax.swing.AbstractButton save = findButton(opened[0].getContentPane(),
                    "\u4fdd\u5b58\u5e76\u5e94\u7528");
            disabledWithoutImage = save != null && !save.isEnabled();
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    opened[0].dispose();
                }
            });

            // ---- with a picture: drag a box and keep it ----
            writeTestImage(source, 300, 150, new java.awt.Color(0x40, 0x40, 0x40));
            config.storeBackgroundImage(source);
            config.setBalanceRegion(new java.awt.geom.Rectangle2D.Float(0.20f, 0.30f, 0.60f, 0.40f));
            config.save();

            final BackgroundRegionDialog[] edited = new BackgroundRegionDialog[1];
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    edited[0] = showEditor(config);
                }
            });
            Thread.sleep(600);

            java.awt.Component canvas = findByName(edited[0].getContentPane(), "ImageCanvas");
            if (canvas != null && canvas.isShowing() && canvas.getWidth() > 20) {
                canvasFound = true;
                // From the top-left corner (outside both the picture and the current box) to the
                // middle: that is a "new region" drag, and it must replace the stored one.
                java.awt.Point origin = canvas.getLocationOnScreen();
                int midX = canvas.getWidth() / 2;
                int midY = canvas.getHeight() / 2;
                post(canvas, java.awt.event.MouseEvent.MOUSE_PRESSED,
                        new java.awt.Point(origin.x + 6, origin.y + 6));
                Thread.sleep(80);
                post(canvas, java.awt.event.MouseEvent.MOUSE_DRAGGED,
                        new java.awt.Point(origin.x + midX, origin.y + midY));
                Thread.sleep(80);
                post(canvas, java.awt.event.MouseEvent.MOUSE_RELEASED,
                        new java.awt.Point(origin.x + midX, origin.y + midY));
                Thread.sleep(150);
            }

            javax.swing.AbstractButton save2 = findButton(edited[0].getContentPane(),
                    "\u4fdd\u5b58\u5e76\u5e94\u7528");
            if (save2 != null && save2.isEnabled()) {
                clickOnEdt(save2);
                Thread.sleep(250);
            }
            saved = edited[0].isConfirmed();

            java.awt.geom.Rectangle2D.Float r = config.getBalanceRegion();
            regionValid = r != null
                    && r.x >= 0f && r.y >= 0f
                    && r.x + r.width <= 1.001f && r.y + r.height <= 1.001f
                    && r.width > 0.01f && r.height > 0.01f;
            regionChanged = r != null
                    && (Math.abs(r.x - 0.20f) > 0.02f || Math.abs(r.y - 0.30f) > 0.02f
                    || Math.abs(r.width - 0.60f) > 0.02f || Math.abs(r.height - 0.40f) > 0.02f);

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    if (edited[0] != null) {
                        edited[0].dispose();
                    }
                }
            });

            boolean ok = disabledWithoutImage && canvasFound && saved && regionChanged && regionValid;
            System.out.println((ok ? "PASS" : "FAIL") + "  \u80cc\u666f\u56fe\u7f16\u8f91\u5668"
                    + " (\u65e0\u56fe\u65f6\u4e0d\u53ef\u4fdd\u5b58=" + disabledWithoutImage
                    + ", \u627e\u5230\u753b\u5e03=" + canvasFound
                    + ", \u62d6\u62fd\u540e\u4fdd\u5b58\u6210\u529f=" + saved
                    + ", \u533a\u57df\u5df2\u6539\u53d8=" + regionChanged
                    + ", \u533a\u57df\u5408\u6cd5=" + regionValid + ")");
            return ok;
        } catch (Exception e) {
            System.out.println("FAIL  \u80cc\u666f\u56fe\u7f16\u8f91\u5668: " + e);
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            config.removeBackgroundImage();
        }
    }

    /** Opens the editor non-modally, so the test keeps control of the event thread. */
    private static BackgroundRegionDialog showEditor(AppConfig config) {
        BackgroundRegionDialog dialog = new BackgroundRegionDialog(null, config,
                new BackgroundRegionDialog.PreviewData("DeepSeek \u4f59\u989d", "\u00a587.65", "CNY", "", ""));
        dialog.setModal(false);
        dialog.setVisible(true);
        return dialog;
    }

    /** Depth-first search for the first component whose class has this simple name. */
    private static java.awt.Component findByName(java.awt.Container root, String simpleName) {
        if (root == null) {
            return null;
        }
        for (java.awt.Component c : root.getComponents()) {
            if (c.getClass().getSimpleName().equals(simpleName)) {
                return c;
            }
            if (c instanceof java.awt.Container) {
                java.awt.Component found = findByName((java.awt.Container) c, simpleName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Finds a button by its exact label, or null when there is no such button. */
    private static javax.swing.AbstractButton findButton(java.awt.Container root, String label) {
        if (root == null) {
            return null;
        }
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.AbstractButton
                    && label.equals(((javax.swing.AbstractButton) c).getText())) {
                return (javax.swing.AbstractButton) c;
            }
            if (c instanceof java.awt.Container) {
                javax.swing.AbstractButton found =
                        findButton((java.awt.Container) c, label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * The point of cropping: the window's edges are the crop's edges.
     *
     * <p>Checked on a picture of four fat vertical stripes, cropping to the middle two. The left
     * edge of the card must then be green and the right edge blue, with the outer red and yellow
     * stripes nowhere to be seen — that is exactly "the window shows this crop and nothing else".
     *
     * <p>Also drags a corner afterwards: with the picture in place the card must keep the crop's
     * proportions, or one side of the resize would pull the edges off the crop again.
     */
    private static boolean checkCropMatchesWindow() {
        final int cardW = 400;
        final int cardH = 200;
        AppConfig config = new AppConfig();
        config.removeBackgroundImage();
        java.io.File source = new java.io.File(AppConfig.directory(), "selftest-crop.png");
        final BalanceBoard[] ref = new BalanceBoard[1];
        try {
            java.awt.image.BufferedImage stripes = new java.awt.image.BufferedImage(
                    400, 100, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D sg = stripes.createGraphics();
            try {
                java.awt.Color[] colors = {
                    new java.awt.Color(0xC0, 0x20, 0x20), new java.awt.Color(0x20, 0xC0, 0x40),
                    new java.awt.Color(0x20, 0x60, 0xE0), new java.awt.Color(0xE0, 0xC0, 0x20),
                };
                for (int i = 0; i < colors.length; i++) {
                    sg.setColor(colors[i]);
                    sg.fillRect(i * 100, 0, 100, 100);
                }
            } finally {
                sg.dispose();
            }
            javax.imageio.ImageIO.write(stripes, "png", source);
            config.storeBackgroundImage(source);
            // The middle half of the picture: green then blue. 2:1.
            config.setImageCrop(new java.awt.geom.Rectangle2D.Float(0.25f, 0f, 0.5f, 1f));
            config.setBalanceRegion(new java.awt.geom.Rectangle2D.Float(0.30f, 0.40f, 0.40f, 0.20f));
            config.setBalanceTextColor(java.awt.Color.WHITE);
            // A deliberately wrong shape, to prove the card corrects itself to the crop.
            config.setBounds(new Rectangle(60, 60, cardW, 137));
            config.save();

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    DeepSeekClient demoClient = new DeepSeekClient();
                    demoClient.setApiKey("sk-demo-000000000000000000000000");
                    BalanceBoard b = new BalanceBoard(config, demoClient, null);
                    b.setLocation(-4000, -4000);
                    b.setVisible(true);
                    b.start();
                    ref[0] = b;
                }
            });
            Thread.sleep(700);

            Rectangle bounds = ref[0].getBounds();
            boolean aspectFitted = Math.abs(bounds.width / (double) bounds.height - 2.0) < 0.06;

            final java.awt.image.BufferedImage shot = new java.awt.image.BufferedImage(
                    bounds.width, bounds.height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    java.awt.Graphics2D g = shot.createGraphics();
                    try {
                        ref[0].paint(g);
                    } finally {
                        g.dispose();
                    }
                }
            });

            int midY = bounds.height / 2;
            int left = shot.getRGB(10, midY);
            int right = shot.getRGB(bounds.width - 20, midY);
            boolean leftIsGreen = green(left) > red(left) + 30 && green(left) > blue(left) + 30;
            boolean rightIsBlue = blue(right) > red(right) + 60 && blue(right) > green(right) + 60;

            // The picture is the window, not something inside the app's card: its own corners are
            // the window's corners (no rounding), and no frame is stroked over its edge.
            java.awt.Shape windowShape = ref[0].getShape();
            boolean squareCorners = windowShape != null
                    && windowShape.contains(1.0, 1.0)
                    && windowShape.contains(bounds.width - 2.0, bounds.height - 2.0);
            int cornerTL = shot.getRGB(6, 6);
            int cornerBR = shot.getRGB(bounds.width - 7, bounds.height - 7);
            boolean cornersArePicture =
                    green(cornerTL) > red(cornerTL) + 30 && green(cornerTL) > blue(cornerTL) + 30
                    && blue(cornerBR) > red(cornerBR) + 60 && blue(cornerBR) > green(cornerBR) + 60;

            // Nothing of the cropped-away stripes may be visible anywhere on the card.
            boolean noRedOrYellow = true;
            for (int y = 0; y < bounds.height && noRedOrYellow; y += 7) {
                for (int x = 0; x < bounds.width; x += 7) {
                    int rgb = shot.getRGB(x, y);
                    if (red(rgb) > green(rgb) + 60 && red(rgb) > blue(rgb) + 60) {
                        noRedOrYellow = false;
                        break;
                    }
                }
            }

            // Resizing must keep the crop's proportions.
            drag(ref[0], new Point(bounds.x + bounds.width - 2, bounds.y + bounds.height - 2), 90, 10);
            Thread.sleep(400);
            Rectangle resized = ref[0].getBounds();
            boolean aspectKept = resized.width > bounds.width
                    && Math.abs(resized.width / (double) resized.height - 2.0) < 0.08;

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    ref[0].dispose();
                }
            });

            boolean ok = aspectFitted && leftIsGreen && rightIsBlue && noRedOrYellow && aspectKept
                    && squareCorners && cornersArePicture;
            System.out.println((ok ? "PASS" : "FAIL")
                    + "  \u7a97\u53e3\u8fb9\u7f18\u4e0e\u88c1\u526a\u4e00\u81f4"
                    + " (\u7a97\u53e3=" + bounds.width + "x" + bounds.height
                    + " \u6bd4\u4f8b\u5df2\u8ddf\u968f\u88c1\u526a=" + aspectFitted
                    + ", \u5de6\u8fb9\u7f18\u662f\u88c1\u526a\u5de6\u8fb9=" + leftIsGreen
                    + ", \u53f3\u8fb9\u7f18\u662f\u88c1\u526a\u53f3\u8fb9=" + rightIsBlue
                    + ", \u88ab\u88c1\u6389\u7684\u989c\u8272\u672a\u51fa\u73b0=" + noRedOrYellow
                    + ", \u7f29\u653e\u540e\u4fdd\u6301\u6bd4\u4f8b=" + aspectKept
                    + " -> " + resized.width + "x" + resized.height
                    + ", \u56db\u89d2\u4e0d\u518d\u5706\u89d2=" + squareCorners
                    + ", \u56db\u89d2\u5c31\u662f\u56fe\u7247=" + cornersArePicture + ")");
            return ok;
        } catch (Exception e) {
            System.out.println("FAIL  \u7a97\u53e3\u8fb9\u7f18\u4e0e\u88c1\u526a\u4e00\u81f4: " + e);
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            config.removeBackgroundImage();
        }
    }

    /**
     * An irregular crop must shape the window to the outline itself, not just size it.
     *
     * <p>Two halves. First the lasso machinery: a dense trace has to come back as a simplified
     * outline, not as a rectangle and not as hundreds of points. Then the result: with a diamond
     * crop the window's own region must contain the middle and exclude the corners, and the painted
     * corners must be blank — which is what "the window's edge is the crop's edge" means once the
     * outline stops being a box.
     */
    private static boolean checkIrregularCropMatchesWindow() {
        final int cardW = 400;
        final int cardH = 200;
        AppConfig config = new AppConfig();
        config.removeBackgroundImage();
        java.io.File source = new java.io.File(AppConfig.directory(), "selftest-outline.png");
        final BalanceBoard[] ref = new BalanceBoard[1];
        try {
            writeStripeImage(source, 400, 100);

            // ---- the lasso: a dense circle trace, in image fractions ----
            java.util.List<java.awt.geom.Point2D.Float> trace =
                    new java.util.ArrayList<java.awt.geom.Point2D.Float>();
            for (int i = 0; i <= 240; i++) {
                double a = 2 * Math.PI * i / 240.0;
                trace.add(new java.awt.geom.Point2D.Float(
                        (float) (0.5 + 0.35 * Math.cos(a)), (float) (0.5 + 0.35 * Math.sin(a))));
            }
            com.ruoyi.dstokencheck.model.CropShape traced =
                    com.ruoyi.dstokencheck.model.CropShape.fromTrace(trace, 0.006f);
            java.awt.geom.Rectangle2D.Float tb = traced == null ? null : traced.bounds();
            boolean traceSimplified = traced != null
                    && !traced.isRectangle()
                    && traced.size() >= 8 && traced.size() <= com.ruoyi.dstokencheck.model.CropShape.MAX_POINTS
                    && tb != null
                    && Math.abs(tb.x - 0.15f) < 0.02f && Math.abs(tb.y - 0.15f) < 0.02f
                    && Math.abs(tb.width - 0.70f) < 0.02f && Math.abs(tb.height - 0.70f) < 0.02f;

            // ---- the diamond: corners cut off, edge midpoints kept ----
            java.util.List<java.awt.geom.Point2D.Float> diamond =
                    new java.util.ArrayList<java.awt.geom.Point2D.Float>();
            diamond.add(new java.awt.geom.Point2D.Float(0.50f, 0.10f));
            diamond.add(new java.awt.geom.Point2D.Float(0.90f, 0.50f));
            diamond.add(new java.awt.geom.Point2D.Float(0.50f, 0.90f));
            diamond.add(new java.awt.geom.Point2D.Float(0.10f, 0.50f));

            config.storeBackgroundImage(source);
            config.setBalanceRegion(new java.awt.geom.Rectangle2D.Float(0.40f, 0.42f, 0.20f, 0.16f));
            config.setBalanceTextColor(java.awt.Color.WHITE);
            config.setCropShape(com.ruoyi.dstokencheck.model.CropShape.of(diamond));
            config.setBounds(new Rectangle(60, 60, cardW, cardH));
            config.save();

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    DeepSeekClient demoClient = new DeepSeekClient();
                    demoClient.setApiKey("sk-demo-000000000000000000000000");
                    BalanceBoard b = new BalanceBoard(config, demoClient, null);
                    b.setLocation(-4000, -4000);
                    b.setVisible(true);
                    b.start();
                    ref[0] = b;
                }
            });
            Thread.sleep(700);

            Rectangle bounds = ref[0].getBounds();
            // The outline spans 0.1..0.9 of a 400x100 picture, so the window has to come out 4:1.
            double expectedAspect = 0.80 * 400 / (0.80 * 100);
            boolean aspectFitted =
                    Math.abs(bounds.width / (double) bounds.height - expectedAspect) < 0.1;

            // The window region itself, as the OS sees it.
            java.awt.Shape windowShape = ref[0].getShape();
            boolean shapeIsOutline = windowShape != null
                    && windowShape.contains(bounds.width / 2.0, bounds.height / 2.0)
                    && !windowShape.contains(2.0, 2.0)
                    && !windowShape.contains(bounds.width - 2.0, bounds.height - 2.0);

            final java.awt.image.BufferedImage shot = new java.awt.image.BufferedImage(
                    bounds.width, bounds.height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    java.awt.Graphics2D g = shot.createGraphics();
                    try {
                        ref[0].paint(g);
                    } finally {
                        g.dispose();
                    }
                }
            });

            int empty = new java.awt.Color(
                    com.ruoyi.dstokencheck.ui.Theme.BG_BOTTOM.getRGB()).getRGB();
            int[][] inside = {
                {bounds.width / 2, bounds.height / 8},
                {bounds.width / 8, bounds.height / 2},
                {bounds.width * 7 / 8, bounds.height / 2},
                {bounds.width / 2, bounds.height * 7 / 8},
            };
            boolean insidePainted = true;
            for (int[] p : inside) {
                if (shot.getRGB(p[0], p[1]) == empty) {
                    insidePainted = false;
                    break;
                }
            }
            int[][] corners = {
                {2, 2}, {bounds.width - 3, 2},
                {2, bounds.height - 3}, {bounds.width - 3, bounds.height - 3},
            };
            boolean cornersEmpty = true;
            for (int[] p : corners) {
                if (shot.getRGB(p[0], p[1]) != empty) {
                    cornersEmpty = false;
                    break;
                }
            }

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    ref[0].dispose();
                }
            });

            boolean ok = traceSimplified && aspectFitted && shapeIsOutline
                    && insidePainted && cornersEmpty;
            System.out.println((ok ? "PASS" : "FAIL") + "  \u4e0d\u89c4\u5219\u88c1\u526a"
                    + " (\u624b\u7ed8\u8f6e\u5ed3\u5df2\u7b80\u5316=" + traceSimplified
                    + (traced == null ? "" : " " + traced.size() + "\u70b9")
                    + ", \u7a97\u53e3\u6bd4\u4f8b\u8ddf\u968f=" + aspectFitted
                    + ", \u7a97\u53e3\u5f62\u72b6=\u8f6e\u5ed3=" + shapeIsOutline
                    + ", \u8f6e\u5ed3\u5185\u6709\u56fe=" + insidePainted
                    + ", \u56db\u89d2\u5df2\u88c1\u6389=" + cornersEmpty + ")");
            return ok;
        } catch (Exception e) {
            System.out.println("FAIL  \u4e0d\u89c4\u5219\u88c1\u526a: " + e);
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            config.removeBackgroundImage();
        }
    }

    /**
     * A see-through picture must stay see-through: the window goes translucent, and the pixels the
     * picture leaves empty are not painted at all.
     *
     * <p>This is the difference between "the desktop shows through my cut-out" and "my cut-out
     * arrived as a black rectangle", which is what an opaque window does with transparency. Also
     * checks the other side of it: a picture that merely <em>has</em> an alpha channel but no
     * see-through pixels must stay on the plain opaque path.
     */
    private static boolean checkTransparentPicture() {
        AppConfig config = new AppConfig();
        config.removeBackgroundImage();
        java.io.File cutout = new java.io.File(AppConfig.directory(), "selftest-cutout.png");
        java.io.File opaqueAlpha = new java.io.File(AppConfig.directory(), "selftest-alpha-opaque.png");
        final BalanceBoard[] ref = new BalanceBoard[1];
        try {
            // A transparent margin around an opaque plate.
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    200, 100, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            try {
                g.setComposite(java.awt.AlphaComposite.Src);
                g.setColor(new java.awt.Color(0, 0, 0, 0));
                g.fillRect(0, 0, 200, 100);
                g.setComposite(java.awt.AlphaComposite.SrcOver);
                g.setColor(new java.awt.Color(0x20, 0x60, 0xE0));
                g.fillRect(40, 20, 120, 60);
            } finally {
                g.dispose();
            }
            javax.imageio.ImageIO.write(img, "png", cutout);

            // Same colour model, but nothing is actually see-through.
            java.awt.image.BufferedImage solid = new java.awt.image.BufferedImage(
                    200, 100, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D sg = solid.createGraphics();
            try {
                sg.setColor(new java.awt.Color(0x20, 0x60, 0xE0));
                sg.fillRect(0, 0, 200, 100);
            } finally {
                sg.dispose();
            }
            javax.imageio.ImageIO.write(solid, "png", opaqueAlpha);

            config.storeBackgroundImage(cutout);
            config.setBalanceRegion(new java.awt.geom.Rectangle2D.Float(0.30f, 0.40f, 0.40f, 0.20f));
            config.setBalanceTextColor(java.awt.Color.WHITE);
            config.setBounds(new Rectangle(60, 60, 400, 200));
            config.save();

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    DeepSeekClient demoClient = new DeepSeekClient();
                    demoClient.setApiKey("sk-demo-000000000000000000000000");
                    BalanceBoard b = new BalanceBoard(config, demoClient, null);
                    b.setLocation(-4000, -4000);
                    b.setVisible(true);
                    b.start();
                    ref[0] = b;
                }
            });
            Thread.sleep(700);

            Rectangle bounds = ref[0].getBounds();
            boolean wentTranslucent = ref[0].getBackground().getAlpha() == 0;

            // Painted into an alpha image: wherever the picture is transparent, nothing is painted,
            // which is precisely the pixels the desktop will show through.
            final java.awt.image.BufferedImage shot = new java.awt.image.BufferedImage(
                    bounds.width, bounds.height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    java.awt.Graphics2D g2 = shot.createGraphics();
                    try {
                        ref[0].paint(g2);
                    } finally {
                        g2.dispose();
                    }
                }
            });
            int margin = shot.getRGB(3, 3) >>> 24;
            int plate = shot.getRGB(bounds.width / 2, bounds.height / 2) >>> 24;
            boolean marginUnpainted = margin == 0;
            boolean platePainted = plate == 255;

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    ref[0].dispose();
                }
            });

            // Now the same picture with no see-through pixels at all.
            config.setBackgroundImageName("");
            config.storeBackgroundImage(opaqueAlpha);
            config.save();
            final boolean[] stayedOpaque = {false};
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    DeepSeekClient demoClient = new DeepSeekClient();
                    demoClient.setApiKey("sk-demo-000000000000000000000000");
                    BalanceBoard b = new BalanceBoard(config, demoClient, null);
                    b.setLocation(-4000, -4000);
                    stayedOpaque[0] = b.getBackground().getAlpha() == 255;
                    b.dispose();
                }
            });

            boolean ok = wentTranslucent && marginUnpainted && platePainted && stayedOpaque[0];
            System.out.println((ok ? "PASS" : "FAIL") + "  \u900f\u660e\u80cc\u666f\u56fe"
                    + " (\u7a97\u53e3\u8f6c\u4e3a\u9010\u50cf\u7d20\u900f\u660e=" + wentTranslucent
                    + ", \u900f\u660e\u5904\u672a\u7ed8\u5236=" + marginUnpainted
                    + " (alpha=" + margin + ")"
                    + ", \u4e0d\u900f\u660e\u5904\u5df2\u7ed8\u5236=" + platePainted
                    + " (alpha=" + plate + ")"
                    + ", \u65e0\u900f\u660e\u50cf\u7d20\u65f6\u4fdd\u6301\u4e0d\u900f\u660e=" + stayedOpaque[0] + ")");
            return ok;
        } catch (Exception e) {
            System.out.println("FAIL  \u900f\u660e\u80cc\u666f\u56fe: " + e);
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            cutout.delete();
            //noinspection ResultOfMethodCallIgnored
            opaqueAlpha.delete();
            config.removeBackgroundImage();
        }
    }

    /** A picture of four fat vertical stripes: which part is on screen is then obvious. */
    private static void writeStripeImage(java.io.File target, int w, int h)
            throws java.io.IOException {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            java.awt.Color[] colors = {
                new java.awt.Color(0xC0, 0x20, 0x20), new java.awt.Color(0x20, 0xC0, 0x40),
                new java.awt.Color(0x20, 0x60, 0xE0), new java.awt.Color(0xE0, 0xC0, 0x20),
            };
            for (int i = 0; i < colors.length; i++) {
                g.setColor(colors[i]);
                g.fillRect(i * (w / colors.length), 0, w / colors.length, h);
            }
        } finally {
            g.dispose();
        }
        javax.imageio.ImageIO.write(img, "png", target);
    }

    private static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }
    private static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }

    /**
     * The bundled preset has to be complete and harmless: it must carry the whole look, and it must
     * carry no credential and no window position.
     *
     * <p>The credential half is checked twice on purpose — once against the shipped file (so a
     * future edit that pastes a settings file in wholesale is caught at the source) and once against
     * a freshly applied config (so the applier cannot be the thing that copies one in).
     */
    private static boolean checkBundledPreset(JPopupMenu menu) {
        java.util.List<Preset> presets = Preset.bundled();
        if (presets.isEmpty()) {
            System.out.println("FAIL  \u9884\u8bbe\u914d\u7f6e: \u6ca1\u6709\u627e\u5230\u5185\u7f6e\u9884\u8bbe");
            return false;
        }
        Preset preset = presets.get(0);
        // One click means the menu really offers it, not just that the file exists.
        javax.swing.JMenu presetMenu = findSubMenu(menu, "\u9884\u8bbe\u914d\u7f6e");
        boolean inMenu = presetMenu != null && findMenuItem(presetMenu, preset.getName()) != null;
        // Saving, deleting and the folder are the rest of the feature; a preset you cannot save is
        // not a preset.
        boolean actionsInMenu = presetMenu != null
                && findMenuItem(presetMenu, "\u4fdd\u5b58\u5f53\u524d\u914d\u7f6e\u4e3a\u9884\u8bbe\u2026") != null
                && findMenuItem(presetMenu, "\u5220\u9664\u9884\u8bbe\u2026") != null
                && findMenuItem(presetMenu, "\u6253\u5f00\u9884\u8bbe\u6587\u4ef6\u5939\u2026") != null;
        AppConfig config = new AppConfig();
        config.removeBackgroundImage();
        config.clearCredentials();
        config.save();
        try {
            boolean named = preset.getName().contains("\u84dd\u8272\u5927\u80a5\u9c7c");

            // Nothing credential-shaped may appear in the shipped preset file.
            boolean fileClean = true;
            java.io.InputStream in = Preset.class.getResourceAsStream(
                    "/presets/" + preset.getId() + "/preset.properties");
            if (in == null) {
                fileClean = false;
            } else {
                try {
                    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[4096];
                    int read;
                    while ((read = in.read(chunk)) > 0) {
                        buffer.write(chunk, 0, read);
                    }
                    String text = new String(buffer.toByteArray(), "UTF-8");
                    // Comment lines are allowed to name the keys; real settings are not.
                    for (String line : text.split("\n")) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                            continue;
                        }
                        if (trimmed.startsWith("apiKeyProtected") || trimmed.startsWith("rememberApiKey")) {
                            fileClean = false;
                        }
                    }
                } finally {
                    in.close();
                }
            }

            Rectangle positionBefore = config.getBounds();
            preset.applyTo(config);
            AppConfig reloaded = new AppConfig();
            java.io.File image = reloaded.getBackgroundImageFile();
            boolean imageCopied = image != null && image.isFile() && image.length() > 1000;
            boolean cropKept = reloaded.hasImageCrop();
            java.awt.geom.Rectangle2D.Float region = reloaded.getBalanceRegion();
            boolean regionKept = region != null && Math.abs(region.y - 0.6705f) < 0.002f;
            boolean colorKept = reloaded.getBalanceTextColor().equals(new java.awt.Color(0x54A0FF));
            boolean scaleKept = Math.abs(reloaded.getFontScale() - 1.4f) < 0.01f;
            boolean sizeKept = reloaded.getBounds().width == 336 && reloaded.getBounds().height == 335;
            // A preset brings a size, never a spot on the screen.
            boolean positionKept = reloaded.getBounds().x == positionBefore.x
                    && reloaded.getBounds().y == positionBefore.y;
            boolean noCredentials = reloaded.getProtectedApiKey().isEmpty()
                    && !reloaded.isRememberApiKey();

            boolean ok = named && inMenu && actionsInMenu && fileClean && imageCopied && cropKept && regionKept
                    && colorKept && scaleKept && sizeKept && positionKept && noCredentials;
            System.out.println((ok ? "PASS" : "FAIL") + "  \u9884\u8bbe\u914d\u7f6e"
                    + " (" + preset.getName()
                    + ", \u83dc\u5355\u91cc\u53ef\u9009=" + inMenu + " \u53ef\u4fdd\u5b58/\u5220\u9664=" + actionsInMenu
                    + ", \u56fe\u7247\u5df2\u590d\u5236=" + imageCopied
                    + ", \u88c1\u526a=" + cropKept + ", \u4f59\u989d\u6846=" + regionKept
                    + ", \u989c\u8272=" + colorKept + ", \u5b57\u53f7=" + scaleKept
                    + ", \u5c3a\u5bf8=" + sizeKept
                    + ", \u4e0d\u6539\u7a97\u53e3\u4f4d\u7f6e=" + positionKept
                    + ", \u4e0d\u5e26\u51ed\u636e=" + (fileClean && noCredentials) + ")");
            return ok;
        } catch (Exception e) {
            System.out.println("FAIL  \u9884\u8bbe\u914d\u7f6e: " + e);
            return false;
        } finally {
            resetSelftestConfig();
        }
    }

    /**
     * Puts the throwaway settings back to a neutral state.
     *
     * <p>The self test reuses one temporary home across runs, so a check that changes the font
     * scale or the card size has to put them back — otherwise the next run starts from the previous
     * one's leftovers and unrelated checks fail for no reason.
     */
    private static void resetSelftestConfig() {
        AppConfig fresh = new AppConfig();
        fresh.removeBackgroundImage();
        fresh.clearCredentials();
        fresh.setRefreshSeconds(60);
        fresh.setFontScale(1f);
        fresh.setOpacity(1f);
        fresh.setAlwaysOnTop(true);
        fresh.setBalanceRegion(null);
        fresh.setBalanceTextColor(new java.awt.Color(0xE9, 0xEE, 0xF8));
        fresh.setBounds(new Rectangle(-1, -1, 360, 180));
        fresh.save();
    }

    /**
     * Saving the current look as a preset and getting it back again, unchanged.
     *
     * <p>This is the round trip the feature exists for, so it is checked as one: build a
     * distinctive look, save it, wipe the settings, apply the saved preset, and compare. The saved
     * file must also stay free of credentials, since a preset is something a user may well hand to
     * somebody else.
     */
    private static boolean checkUserPreset() {
        AppConfig config = new AppConfig();
        resetSelftestConfig();
        java.io.File source = new java.io.File(AppConfig.directory(), "selftest-user-preset.png");
        try {
            writeTestImage(source, 300, 150, new java.awt.Color(0x33, 0x66, 0x99));
            config.storeBackgroundImage(source);
            config.setImageCrop(new java.awt.geom.Rectangle2D.Float(0.10f, 0.10f, 0.80f, 0.80f));
            config.setBalanceRegion(new java.awt.geom.Rectangle2D.Float(0.20f, 0.30f, 0.60f, 0.20f));
            config.setBalanceTextColor(new java.awt.Color(0xFF, 0x88, 0x44));
            config.setFontScale(1.2f);
            config.setBounds(new Rectangle(11, 22, 400, 200));
            config.save();

            java.io.File folder = Preset.save("\u81ea\u6d4b\u9884\u8bbe", config);
            java.io.File settingsFile = new java.io.File(folder, "preset.properties");
            boolean saved = settingsFile.isFile();
            int pictures = 0;
            java.io.File[] files = folder.listFiles();
            if (files != null) {
                for (java.io.File file : files) {
                    if (file.getName().startsWith("background.")) {
                        pictures++;
                    }
                }
            }
            boolean pictureSaved = pictures == 1;

            // A preset is meant to be shareable: no credential keys, not even commented out ones
            // that a naive paste would bring in as real settings.
            boolean fileClean = true;
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(settingsFile), "UTF-8"));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("apiKeyProtected") || trimmed.startsWith("rememberApiKey")) {
                        fileClean = false;
                    }
                }
            } finally {
                reader.close();
            }

            Preset saved2 = null;
            for (Preset candidate : Preset.userPresets()) {
                if ("\u81ea\u6d4b\u9884\u8bbe".equals(candidate.getName())) {
                    saved2 = candidate;
                }
            }
            boolean listed = saved2 != null && saved2.isUserPreset();

            // Wipe everything, then apply the preset and see the look come back. The target has to
            // be re-read, or we would be applying to the object that still holds the look we just
            // saved — including its window position, which a preset must never carry.
            resetSelftestConfig();
            boolean applied = false;
            if (saved2 != null) {
                saved2.applyTo(new AppConfig());
                applied = true;
            }
            AppConfig back = new AppConfig();
            java.io.File restoredImage = back.getBackgroundImageFile();
            java.awt.geom.Rectangle2D.Float region = back.getBalanceRegion();
            java.awt.geom.Rectangle2D.Float crop = back.getImageCrop();
            boolean imageOk = restoredImage != null && restoredImage.isFile();
            boolean colorOk = back.getBalanceTextColor().equals(new java.awt.Color(0xFF, 0x88, 0x44));
            boolean scaleOk = Math.abs(back.getFontScale() - 1.2f) < 0.01f;
            boolean regionOk = region != null && Math.abs(region.y - 0.30f) < 0.002f;
            boolean cropOk = crop != null && Math.abs(crop.width - 0.80f) < 0.002f;
            boolean sizeOk = back.getBounds().width == 400 && back.getBounds().height == 200;
            boolean placeOk = back.getBounds().x == -1 && back.getBounds().y == -1;
            boolean restored = applied && imageOk && colorOk && scaleOk && regionOk && cropOk
                    && sizeOk && placeOk;
            boolean noCredentials = back.getProtectedApiKey().isEmpty() && !back.isRememberApiKey();

            boolean removed = saved2 != null && Preset.delete(saved2) && !Preset.exists("\u81ea\u6d4b\u9884\u8bbe");

            boolean ok = saved && pictureSaved && fileClean && listed && restored
                    && noCredentials && removed;
            System.out.println((ok ? "PASS" : "FAIL") + "  \u4fdd\u5b58\u4e3a\u9884\u8bbe"
                    + " (\u5199\u5165=" + saved + ", \u56fe\u7247\u5df2\u5b58=" + pictureSaved
                    + ", \u53ef\u5217\u51fa=" + listed
                    + ", \u5957\u7528\u540e\u8fd8\u539f=" + restored
                    + " [\u56fe=" + imageOk + " \u8272=" + colorOk + " \u5b57\u53f7=" + scaleOk
                    + " \u6846=" + regionOk + " \u88c1\u526a=" + cropOk + " \u5c3a\u5bf8=" + sizeOk
                    + " \u4f4d\u7f6e=" + placeOk + "]"
                    + ", \u4e0d\u5e26\u51ed\u636e=" + (fileClean && noCredentials)
                    + ", \u53ef\u5220\u9664=" + removed + ")");
            return ok;
        } catch (Exception e) {
            System.out.println("FAIL  \u4fdd\u5b58\u4e3a\u9884\u8bbe: " + e);
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            resetSelftestConfig();
        }
    }

    /**
     * The right-click menu has to wear the app's own colours, and only the right rows may carry a
     * glyph.
     *
     * <p>The second half is not hypothetical: the look and feel paints the check and arrow icons for
     * <em>any</em> item whose icon field is set, so a first attempt put a tick and a chevron on
     * every single row. The render is inspected by pixels for exactly that reason — a stray tick is
     * invisible to any assertion about colours.
     */
    private static boolean checkMenuSkin(final JPopupMenu menu) throws Exception {
        if (menu == null) {
            System.out.println("FAIL  \u53f3\u952e\u83dc\u5355\u5916\u89c2: \u6ca1\u6709\u83dc\u5355");
            return false;
        }
        boolean popupSkinned = menu.getUI() != null
                && menu.getUI().getClass().getName().contains("MenuSkin");
        boolean surface = com.ruoyi.dstokencheck.ui.Theme.SURFACE.equals(menu.getBackground());
        boolean padded = menu.getBorder() != null
                && menu.getBorder().getBorderInsets(menu).left >= 4;

        menu.setSize(menu.getPreferredSize());
        menu.doLayout();
        final java.awt.image.BufferedImage shot = new java.awt.image.BufferedImage(
                Math.max(1, menu.getWidth()), Math.max(1, menu.getHeight()),
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                java.awt.Graphics2D g = shot.createGraphics();
                try {
                    // A light backdrop: anything the menu fails to paint shows up as light, so the
                    // "is this row on the dark surface" question has an answer.
                    g.setColor(java.awt.Color.WHITE);
                    g.fillRect(0, 0, shot.getWidth(), shot.getHeight());
                    menu.paint(g);
                } finally {
                    g.dispose();
                }
            }
        });

        int rows = 0;
        int rowsSkinned = 0;
        int plainWithGlyph = 0;
        int submenuWithoutGlyph = 0;
        int strayTick = 0;
        int lightRows = 0;
        for (java.awt.Component component : menu.getComponents()) {
            if (!(component instanceof JMenuItem)) {
                continue;
            }
            JMenuItem item = (JMenuItem) component;
            rows++;
            if (item.getUI() != null && item.getUI().getClass().getName().contains("MenuSkin")) {
                rowsSkinned++;
            }
            int top = Math.max(0, item.getY());
            int height = Math.min(item.getHeight(), shot.getHeight() - top);
            if (height <= 0) {
                continue;
            }
            // Right gutter: where a submenu chevron belongs.
            boolean rightGlyph = hasInk(shot, item.getX() + item.getWidth() - 20, top, 18, height);
            // Just before the text starts: where a stray tick would land.
            boolean leftGlyph = hasInk(shot, item.getX() + 2, top, 9, height);
            boolean lightRow = !hasInk(shot, item.getX() + 40, top, 40, height);
            if (item instanceof javax.swing.JMenu) {
                if (!rightGlyph) {
                    submenuWithoutGlyph++;
                }
            } else {
                if (rightGlyph) {
                    plainWithGlyph++;
                }
                if (leftGlyph && !(item instanceof javax.swing.JCheckBoxMenuItem)) {
                    strayTick++;
                }
            }
            if (lightRow) {
                lightRows++;
            }
        }

        boolean ok = popupSkinned && surface && padded && rows > 0 && rowsSkinned == rows
                && plainWithGlyph == 0 && submenuWithoutGlyph == 0 && strayTick == 0 && lightRows == 0;
        System.out.println((ok ? "PASS" : "FAIL") + "  \u53f3\u952e\u83dc\u5355\u5916\u89c2"
                + " (\u9762\u677f\u5df2\u6362\u80a4=" + popupSkinned
                + ", \u5e95\u8272=\u5e94\u7528\u8868\u9762\u8272=" + surface
                + ", \u5185\u8fb9\u8ddd=" + padded
                + ", \u884c\u5df2\u6362\u80a4=" + rowsSkinned + "/" + rows
                + ", \u666e\u901a\u884c\u591a\u4f59\u7bad\u5934=" + plainWithGlyph
                + ", \u5b50\u83dc\u5355\u7f3a\u7bad\u5934=" + submenuWithoutGlyph
                + ", \u591a\u4f59\u52fe=" + strayTick
                + ", \u6ca1\u753b\u4e0a\u7684\u767d\u884c=" + lightRows + ")");
        return ok;
    }

    /** True when any pixel in the box is brighter than the menu surface (text, tint or icon). */
    private static boolean hasInk(java.awt.image.BufferedImage image, int x, int y, int w, int h) {
        for (int yy = Math.max(0, y); yy < Math.min(image.getHeight(), y + h); yy++) {
            for (int xx = Math.max(0, x); xx < Math.min(image.getWidth(), x + w); xx++) {
                int rgb = image.getRGB(xx, yy);
                int sum = ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
                if (sum > 200) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Hovering the balance shows which tariff is running and what today has cost.
     *
     * <p>Three things have to hold: the tariff rule at its exact boundaries, the arithmetic behind
     * "used today", and the fact that the widget's own texts and colours really do change — in both
     * layouts, the card's labels and the text painted over a picture.
     */
    private static boolean checkPeakHoursAndUsage() {
        // --- the published rule, at the minute each window opens and closes (Beijing time) ---
        boolean opens = com.ruoyi.dstokencheck.model.PeakHours.isPeak(beijing("2026-10-08T09:00"));
        boolean inside = com.ruoyi.dstokencheck.model.PeakHours.isPeak(beijing("2026-10-08T11:59"));
        boolean closes = !com.ruoyi.dstokencheck.model.PeakHours.isPeak(beijing("2026-10-08T12:00"));
        boolean gap = !com.ruoyi.dstokencheck.model.PeakHours.isPeak(beijing("2026-10-08T13:00"));
        boolean evening = com.ruoyi.dstokencheck.model.PeakHours.isPeak(beijing("2026-10-08T14:00"));
        boolean done = !com.ruoyi.dstokencheck.model.PeakHours.isPeak(beijing("2026-10-08T18:00"));
        boolean saturday = !com.ruoyi.dstokencheck.model.PeakHours.isPeak(beijing("2026-10-10T10:00"));
        boolean holiday = !com.ruoyi.dstokencheck.model.PeakHours.isPeak(beijing("2026-10-01T09:30"));
        boolean rule = opens && inside && closes && gap && evening && done && saturday && holiday;

        // --- today's spending, measured by watching the balance move ---
        AppConfig config = new AppConfig();
        config.clearDailyUsage();
        float baseline = config.trackDailyUsage(100f, "CNY");
        float spent = config.trackDailyUsage(88.5f, "CNY");
        float toppedUp = config.trackDailyUsage(120f, "CNY");
        float afterTopUp = config.trackDailyUsage(115f, "CNY");
        float otherWallet = config.trackDailyUsage(50f, "USD");
        boolean usage = baseline == 0f && Math.abs(spent - 11.5f) < 0.01f && toppedUp == 0f
                && Math.abs(afterTopUp - 5f) < 0.01f && otherWallet == 0f;
        config.clearDailyUsage();

        // Two instants that pin the rule down: a weekday morning inside a peak window, and a
        // weekday lunchtime between the two windows.
        final long peakAt = beijing("2026-10-08T10:00");
        final long offPeakAt = beijing("2026-10-08T12:30");

        // --- what the card layout actually shows ---
        boolean cardLayout = false;
        try {
            final BalanceBoard[] ref = new BalanceBoard[1];
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    DeepSeekClient demoClient = new DeepSeekClient();
                    demoClient.setApiKey("sk-demo-000000000000000000000000");
                    BalanceBoard b = new BalanceBoard(new AppConfig(), demoClient, null);
                    b.setLocation(-4000, -4000);
                    b.setVisible(true);
                    b.start();
                    ref[0] = b;
                }
            });
            Thread.sleep(700);

            final java.util.List<JLabel> labels = new java.util.ArrayList<JLabel>();
            collectLabels(ref[0].getContentPane(), labels);
            final java.util.Map<JLabel, String> beforeText = new java.util.HashMap<JLabel, String>();
            for (JLabel label : labels) {
                beforeText.put(label, label.getText());
            }

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    ref[0].setBalanceHovered(true, peakAt);
                }
            });
            boolean busyLine = false;
            boolean usageLine = false;
            for (JLabel label : labels) {
                if ("\u73b0\u5728\u662f\u7e41\u5fd9\u65f6\u6bb5".equals(label.getText())) {
                    busyLine = label.getForeground().equals(Theme.DANGER);
                }
                if (label.getText() != null
                        && label.getText().startsWith("\u4eca\u65e5\u5df2\u4f7f\u7528\u4f59\u989d")) {
                    usageLine = true;
                }
            }
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    ref[0].setBalanceHovered(true, offPeakAt);
                }
            });
            boolean idleLine = false;
            for (JLabel label : labels) {
                if ("\u73b0\u5728\u662f\u7a7a\u95f2\u65f6\u6bb5".equals(label.getText())) {
                    idleLine = label.getForeground().equals(Theme.GOOD);
                }
            }
            cardLayout = busyLine && idleLine && usageLine;

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    ref[0].setBalanceHovered(false);
                }
            });
            boolean restored = true;
            for (JLabel label : labels) {
                if (!beforeText.get(label).equals(label.getText())) {
                    restored = false;
                }
            }
            cardLayout = cardLayout && restored;
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    ref[0].dispose();
                }
            });
        } catch (Exception e) {
            System.out.println("FAIL  \u60ac\u6d6e\u663e\u793a: " + e);
            return false;
        }

        // A picture layout paints the balance itself, so the swap is checked by pixels there.
        boolean pictureLayout = false;
        java.io.File source = new java.io.File(AppConfig.directory(), "selftest-hover.png");
        try {
            writeTestImage(source, 300, 150, new java.awt.Color(0x30, 0x30, 0x30));
            final AppConfig pictureConfig = new AppConfig();
            pictureConfig.storeBackgroundImage(source);
            pictureConfig.setImageCrop(new java.awt.geom.Rectangle2D.Float(0f, 0f, 1f, 1f));
            pictureConfig.setBalanceRegion(new java.awt.geom.Rectangle2D.Float(0.2f, 0.35f, 0.6f, 0.3f));
            pictureConfig.save();

            final BalanceBoard[] ref = new BalanceBoard[1];
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    DeepSeekClient demoClient = new DeepSeekClient();
                    demoClient.setApiKey("sk-demo-000000000000000000000000");
                    BalanceBoard b = new BalanceBoard(pictureConfig, demoClient, null);
                    b.setLocation(-4000, -4000);
                    b.setVisible(true);
                    b.start();
                    ref[0] = b;
                }
            });
            Thread.sleep(700);
            // Nothing is hovered unless it is asked for: the pointer watcher wants a moving mouse.
            // Both halves of the rule are forced here, because whichever one the wall clock is in,
            // the other one is the branch nobody would ever test.
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    ref[0].setBalanceHovered(true, peakAt);
                }
            });
            int peakRed = countColour(ref[0], Theme.DANGER);
            int peakGreen = countColour(ref[0], Theme.GOOD);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    ref[0].setBalanceHovered(true, offPeakAt);
                }
            });
            int idleGreen = countColour(ref[0], Theme.GOOD);
            int idleRed = countColour(ref[0], Theme.DANGER);
            pictureLayout = peakRed > 0 && peakGreen == 0 && idleGreen > 0 && idleRed == 0;
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    ref[0].dispose();
                }
            });
        } catch (Exception e) {
            System.out.println("FAIL  \u60ac\u6d6e\u663e\u793a: " + e);
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            // The picture this check installed would otherwise still be configured for the next run,
            // which leaves the board in picture mode and changes the minimum size other checks use.
            config.clearDailyUsage();
            resetSelftestConfig();
        }

        boolean ok = rule && usage && cardLayout && pictureLayout;
        System.out.println((ok ? "PASS" : "FAIL") + "  \u60ac\u6d6e\u663e\u793a\u65f6\u6bb5\u4e0e\u4eca\u65e5\u7528\u91cf"
                + " (\u65f6\u6bb5\u89c4\u5219=" + rule
                + ", \u4eca\u65e5\u7528\u91cf=\u57fa\u51c6/\u6d88\u8017/\u5145\u503c/\u518d\u6d88\u8017/\u6362\u5e01\u79cd=" + usage
                + ", \u5361\u7247\u5e03\u5c40\u6587\u5b57\u4e0e\u989c\u8272=" + cardLayout
                + ", \u80cc\u666f\u56fe\u5e03\u5c40\u6309\u50cf\u7d20=" + pictureLayout + ")");
        return ok;
    }

    /** An instant written as Beijing local time, e.g. {@code 2026-10-08T09:00}. */
    private static long beijing(String localDateTime) {
        return java.time.LocalDateTime.parse(localDateTime)
                .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }

    private static void collectLabels(java.awt.Container root, java.util.List<JLabel> out) {
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof JLabel) {
                out.add((JLabel) component);
            } else if (component instanceof java.awt.Container) {
                collectLabels((java.awt.Container) component, out);
            }
        }
    }

    /** Pixels of the painted window that are close to this colour: a rough "was it drawn" test. */
    private static int countColour(final BalanceBoard board, final java.awt.Color wanted) {
        final int[] hits = {0};
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    Rectangle bounds = board.getBounds();
                    java.awt.image.BufferedImage shot = new java.awt.image.BufferedImage(
                            Math.max(1, bounds.width), Math.max(1, bounds.height),
                            java.awt.image.BufferedImage.TYPE_INT_RGB);
                    java.awt.Graphics2D g = shot.createGraphics();
                    try {
                        board.paint(g);
                    } finally {
                        g.dispose();
                    }
                    for (int y = 0; y < shot.getHeight(); y++) {
                        for (int x = 0; x < shot.getWidth(); x++) {
                            int rgb = shot.getRGB(x, y);
                            if (Math.abs(((rgb >> 16) & 0xFF) - wanted.getRed()) < 26
                                    && Math.abs(((rgb >> 8) & 0xFF) - wanted.getGreen()) < 26
                                    && Math.abs((rgb & 0xFF) - wanted.getBlue()) < 26) {
                                hits[0]++;
                            }
                        }
                    }
                }
            });
        } catch (Exception ignored) {
            return 0;
        }
        return hits[0];
    }

    /** Writes a flat-colour PNG, used as a stand-in for a user's background image. */
    private static void writeTestImage(java.io.File target, int w, int h, java.awt.Color color)
            throws java.io.IOException {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        javax.imageio.ImageIO.write(img, "png", target);
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

    /**
     * {@code --preset [id]} — list the bundled looks, or apply one and exit.
     *
     * <p>Same thing the right-click menu does, for a machine that is being set up from a script.
     */
    private static void runPresetCommand(String[] args) {
        if (args.length >= 2 && "save".equalsIgnoreCase(args[1].trim())) {
            if (args.length < 3 || args[2].trim().isEmpty()) {
                System.err.println("用法: java -jar dstokencheck.jar --preset save <\u540d\u5b57>");
                System.exit(1);
            }
            try {
                java.io.File folder = Preset.save(args[2].trim(), new AppConfig());
                System.out.println("\u5df2\u4fdd\u5b58\u9884\u8bbe: " + args[2].trim());
                System.out.println("\u9884\u8bbe\u76ee\u5f55  : " + folder.getAbsolutePath());
            } catch (Exception e) {
                System.err.println("\u4fdd\u5b58\u9884\u8bbe\u5931\u8d25: " + e.getMessage());
                System.exit(1);
            }
            return;
        }

        java.util.List<Preset> mine = Preset.userPresets();
        java.util.List<Preset> bundled = Preset.bundled();
        if (args.length < 2) {
            System.out.println("\u53ef\u7528\u7684\u9884\u8bbe\u914d\u7f6e\uff1a");
            for (Preset preset : mine) {
                System.out.println("  [\u6211\u7684] " + preset.getName());
            }
            for (Preset preset : bundled) {
                System.out.println("  [\u5185\u7f6e] " + preset.getName() + "    " + preset.getId()
                        + (preset.getDescription().isEmpty() ? "" : "    (" + preset.getDescription() + ")"));
            }
            System.out.println();
            System.out.println("\u7528\u6cd5: java -jar dstokencheck.jar --preset <\u540d\u5b57>");
            System.out.println("      java -jar dstokencheck.jar --preset save <\u540d\u5b57>");
            return;
        }

        String wanted = args[1].trim();
        // The user's own presets win over a bundled one of the same name: they saved it deliberately.
        java.util.List<Preset> candidates = new java.util.ArrayList<Preset>(mine);
        candidates.addAll(bundled);
        for (Preset preset : candidates) {
            if (!preset.getId().equalsIgnoreCase(wanted) && !preset.getName().equals(wanted)) {
                continue;
            }
            AppConfig config = new AppConfig();
            try {
                preset.applyTo(config);
            } catch (Exception e) {
                System.err.println("\u5e94\u7528\u9884\u8bbe\u5931\u8d25: " + e.getMessage());
                System.exit(1);
            }
            System.out.println("\u5df2\u5e94\u7528\u9884\u8bbe: " + preset.getName());
            System.out.println("\u914d\u7f6e\u6587\u4ef6  : " + config.getFile().getAbsolutePath());
            System.out.println("\u80cc\u666f\u56fe    : " + orNone(String.valueOf(config.getBackgroundImageFile())));
            return;
        }
        System.err.println("\u627e\u4e0d\u5230\u9884\u8bbe: " + wanted);
        System.exit(1);
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
