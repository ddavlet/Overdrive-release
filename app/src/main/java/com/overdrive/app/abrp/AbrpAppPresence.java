package com.overdrive.app.abrp;

import android.content.Context;

import com.overdrive.app.logging.DaemonLogger;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Detects whether the ABRP Android app is installed and currently in use on this
 * head unit, so telemetry can be streamed only while the user is actually
 * route-planning (the biggest possible data saving — zero uploads otherwise).
 *
 * "Active" has two modes (read live from {@link AbrpConfig}):
 *   - foreground : ABRP was the resumed/top activity within a grace window
 *                  (survives quick app-switches; pauses shortly after leaving ABRP).
 *   - running    : the ABRP process is alive at all (better for background navigation).
 *
 * Foreground detection uses {@code dumpsys activity activities} via a shell — the
 * same privileged idiom the rest of the app uses (AccSentryDaemon, ServiceLauncher).
 * Results are cached briefly so we never hammer dumpsys.
 */
public class AbrpAppPresence {

    private static final String TAG = "AbrpAppPresence";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final long CHECK_TTL_MS = 8000;

    private final AbrpConfig config;
    private final Context context;

    private volatile long lastCheckMs = 0;
    private volatile long lastForegroundSeenMs = 0;
    private volatile boolean lastProcessAlive = false;
    private volatile boolean lastInstalled = false;
    private volatile String lastForegroundPkg = null;

    public AbrpAppPresence(AbrpConfig config, Context context) {
        this.config = config;
        this.context = context;
    }

    /** Whether the ABRP package is installed on this device (cached with the foreground probe). */
    public boolean isInstalled() {
        refreshIfStale();
        return lastInstalled;
    }

    /** Human-readable presence for the status panel: "foreground" / "running" / "background" / "not installed". */
    public String describe() {
        refreshIfStale();
        if (!lastInstalled) return "not installed";
        if (isForegroundWithinGrace()) return "foreground";
        if (lastProcessAlive) return "running";
        return "background";
    }

    /** True if telemetry should be allowed to flow right now. */
    public boolean isActive() {
        refreshIfStale();
        if (!lastInstalled) {
            // Can't gate on an app that isn't here — fail open so the user isn't
            // silently never-sending. The caller surfaces a warning instead.
            return true;
        }
        boolean foregroundMode = !"running".equalsIgnoreCase(config.getAppActiveMode());
        return foregroundMode ? isForegroundWithinGrace() : lastProcessAlive;
    }

    private boolean isForegroundWithinGrace() {
        long graceMs = Math.max(0, config.getAppGraceSeconds()) * 1000L;
        return lastForegroundSeenMs > 0 && (System.currentTimeMillis() - lastForegroundSeenMs) <= graceMs;
    }

    private void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastCheckMs < CHECK_TTL_MS) return;
        lastCheckMs = now;

        String pkg = config.getAppPackage();
        if (pkg == null || pkg.isEmpty()) pkg = "com.iternio.abrpapp";

        lastInstalled = checkInstalled(pkg);
        if (!lastInstalled) {
            lastProcessAlive = false;
            return;
        }

        String top = readForegroundPackage();
        lastForegroundPkg = top;
        if (top != null && top.contains(pkg)) {
            lastForegroundSeenMs = now;
        }
        lastProcessAlive = (top != null && top.contains(pkg)) || isProcessAlive(pkg);
    }

    private boolean checkInstalled(String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Parse the resumed/top activity package from dumpsys. */
    private String readForegroundPackage() {
        // Look at the lines that name the currently resumed / focused activity.
        String out = runShell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|mCurrentFocus' | head -n 5");
        if (out == null || out.isEmpty()) {
            out = runShell("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' | head -n 5");
        }
        if (out == null) return null;
        // Extract a token of the form "<package>/<activity>".
        for (String line : out.split("\n")) {
            int slash = line.indexOf('/');
            if (slash <= 0) continue;
            int start = slash;
            while (start > 0) {
                char ch = line.charAt(start - 1);
                if (ch == ' ' || ch == '{' || ch == '=') break;
                start--;
            }
            String token = line.substring(start, slash);
            if (token.contains(".")) return token; // looks like a package name
        }
        return null;
    }

    private boolean isProcessAlive(String pkg) {
        String out = runShell("pidof " + pkg);
        return out != null && !out.trim().isEmpty();
    }

    private String runShell(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                int lines = 0;
                while ((line = r.readLine()) != null && lines++ < 20) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            logger.debug("shell failed: " + e.getMessage());
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
