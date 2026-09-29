package com.ruoyi.dstokencheck.autostart;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Registers the widget to start when the user logs in.
 *
 * <p>On Windows this writes a value under the per-user
 * {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Run} key, which is the standard mechanism
 * for a logon-startup application. It is per-user and needs no administrator rights, and it is
 * trivially reversible — removing the value (or the 开机自启 checkbox) undoes it completely.
 *
 * <p>The stored command pins both the JRE and the jar by absolute path, so it keeps working
 * regardless of the current working directory. If the jar is later moved the entry would go stale,
 * which is why {@link #enable()} is called again on every start while the setting is on.
 */
public final class AutoStart {

    /** Registry path, relative to HKEY_CURRENT_USER. */
    private static final String RUN_KEY_PATH = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    /** Value name. Deliberately stable so the entry can always be found and removed. */
    public static final String VALUE_NAME = "DeepSeekBalanceBoard";

    /**
     * Passed to the auto-started instance so it can tell how it was launched.
     *
     * <p>Deliberately distinct from the {@code --autostart on|off|status} management command:
     * sharing one flag would make Windows' logon launch run the management command and exit
     * instead of showing the widget.
     */
    public static final String AUTOSTART_FLAG = "--at-logon";

    private AutoStart() {
    }

    /** Raised when the registry could not be updated; the message is safe to show to the user. */
    public static class AutoStartException extends Exception {
        public AutoStartException(String message) {
            super(message);
        }

        public AutoStartException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** True when this platform supports logon startup (Windows only). */
    public static boolean isSupported() {
        try {
            return Platform.isWindows();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The command Windows would run at logon.
     *
     * @return the command, or {@code null} when the app is not running from a jar (for example
     *         straight out of an IDE), in which case there is nothing sensible to register
     */
    public static String buildCommand() {
        File jar = launcherJar();
        File launcher = launcherExecutable();
        if (jar == null || launcher == null) {
            return null;
        }
        return "\"" + launcher.getAbsolutePath() + "\" -jar \"" + jar.getAbsolutePath() + "\" "
                + AUTOSTART_FLAG;
    }

    /** Stable per-user location for the copy the Run entry points at. */
    public static File stableJarPath() {
        String localAppData = System.getenv("LOCALAPPDATA");
        File base = (localAppData != null && !localAppData.trim().isEmpty())
                ? new File(localAppData)
                : new File(System.getProperty("user.home", "."), ".local/share");
        return new File(new File(base, "dstokencheck"), "dstokencheck.jar");
    }

    /**
     * The jar this process was actually loaded from, or null when running from a classes directory.
     * Distinct from {@link #launcherJar()}, which may be the stable copy.
     */
    public static File runningJar() {
        return applicationJar();
    }

    /**
     * The jar the Run entry should launch.
     *
     * <p>Prefers the stable copy. Registering the running jar directly is unsafe when the app is
     * launched from a build directory: {@code mvn clean} deletes it, and because the logon launch
     * then fails there is no way for the app to repair the entry — auto-start silently stays broken.
     */
    public static File launcherJar() {
        File stable = stableJarPath();
        if (stable.isFile()) {
            return stable;
        }
        return applicationJar();
    }

    /**
     * Copies the running jar to {@link #stableJarPath()} so the Run entry survives rebuilds.
     * Called whenever the setting is applied or reconciled, which also refreshes a stale copy.
     *
     * @return true when a usable stable copy exists afterwards
     */
    public static boolean installStableJar() {
        File source = applicationJar();
        File target = stableJarPath();
        if (source == null) {
            return false;
        }
        try {
            if (source.getCanonicalFile().equals(target.getCanonicalFile())) {
                return true;
            }
            File dir = target.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                return false;
            }
            boolean current = target.isFile()
                    && target.length() == source.length()
                    && target.lastModified() >= source.lastModified();
            if (!current) {
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** True when the Run entry exists. */
    public static boolean isEnabled() {
        if (!isSupported()) {
            return false;
        }
        try {
            return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH, VALUE_NAME);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The command currently stored in the registry, or {@code null} when there is none. */
    public static String currentCommand() {
        if (!isEnabled()) {
            return null;
        }
        try {
            return Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH, VALUE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when the registry entry matches what {@link #buildCommand()} would write now. */
    public static boolean isUpToDate() {
        String stored = currentCommand();
        String wanted = buildCommand();
        return stored != null && stored.equals(wanted);
    }

    /** Adds or refreshes the Run entry. Idempotent. */
    public static void enable() throws AutoStartException {
        if (!isSupported()) {
            throw new AutoStartException("当前系统不支持开机自启（仅 Windows）");
        }
        String command = buildCommand();
        if (command == null) {
            throw new AutoStartException(
                    "无法确定程序路径：请用 run.bat 或 java -jar dstokencheck.jar 启动后再设置"
                            + "（在 IDE 里直接运行无法确定 jar 路径）");
        }
        // Put a copy somewhere stable first, so a later `mvn clean` cannot break auto-start.
        installStableJar();
        command = buildCommand();
        try {
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH, VALUE_NAME, command);
        } catch (Throwable t) {
            throw new AutoStartException("写入注册表失败: " + t.getMessage(), t);
        }
    }

    /** Removes the Run entry. Safe to call when it is already absent. */
    public static void disable() throws AutoStartException {
        if (!isSupported()) {
            throw new AutoStartException("当前系统不支持开机自启（仅 Windows）");
        }
        try {
            if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH, VALUE_NAME)) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH, VALUE_NAME);
            }
        } catch (Throwable t) {
            throw new AutoStartException("删除注册表项失败: " + t.getMessage(), t);
        }
    }

    // ------------------------------------------------------------- internals

    /** The jar this class was loaded from, or null when not running from a jar. */
    private static File applicationJar() {
        try {
            URL location = AutoStart.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            File file = new File(location.toURI());
            if (file.isFile() && file.getName().toLowerCase().endsWith(".jar")) {
                return file;
            }
        } catch (Exception ignored) {
            // Fall through: the caller reports that auto-start is unavailable.
        }
        return null;
    }

    /**
     * {@code javaw.exe} if present, so the auto-started instance shows no console window;
     * otherwise {@code java.exe}.
     */
    private static File launcherExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isEmpty()) {
            return null;
        }
        File bin = new File(javaHome, "bin");
        File javaw = new File(bin, "javaw.exe");
        if (javaw.isFile()) {
            return javaw;
        }
        File java = new File(bin, "java.exe");
        if (java.isFile()) {
            return java;
        }
        // Non-Windows layouts, or a JRE without the .exe suffix.
        File plain = new File(bin, "java");
        return plain.isFile() ? plain : null;
    }
}
