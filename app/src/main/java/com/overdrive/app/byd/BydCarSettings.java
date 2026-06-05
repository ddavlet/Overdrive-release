package com.overdrive.app.byd;

import android.content.ContentResolver;
import android.content.Context;

import com.overdrive.app.daemon.DaemonBootstrap;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local read/write access to BYD's in-car settings (the "carsettings" provider),
 * exposed so a curated allowlist of CAN-backed vehicle settings can be surfaced as
 * controllable Home Assistant entities. Fully local — no BYD cloud involved.
 *
 * BYD's own settings UI reads/writes through {@code android.provider.CarSettings.UserTableData}
 * (e.g. {@code getSystemInt(cr,key,def)} / {@code putInt(cr,key,value)}). That class is
 * provided by the system framework at runtime, so we reach it by reflection — exactly
 * like the BYDAuto* HAL devices — and fail closed (read = default, write = false) when
 * it's unavailable (e.g. on a dev emulator).
 *
 * Only the curated {@link #registry()} keys are ever read or written; arbitrary keys are
 * rejected to keep CAN writes safe and predictable.
 */
public final class BydCarSettings {

    private static final String TAG = "BydCarSettings";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final String CLS = "android.provider.CarSettings$UserTableData";

    /** How a setting maps onto a Home Assistant entity. */
    public enum Kind { BOOL, INT_RANGE, INT_ENUM }

    /** One curated, controllable car setting. */
    public static final class CarSetting {
        public final String key;       // provider key
        public final Kind kind;
        public final String name;      // HA friendly name
        public final String icon;      // mdi:...
        public final int min, max, step;   // INT_RANGE
        public final int[] options;        // INT_ENUM allowed values
        public final String unit;          // INT_RANGE unit (nullable)
        CarSetting(String key, Kind kind, String name, String icon,
                   int min, int max, int step, int[] options, String unit) {
            this.key = key; this.kind = kind; this.name = name; this.icon = icon;
            this.min = min; this.max = max; this.step = step; this.options = options; this.unit = unit;
        }
    }

    // ── Curated allowlist (verified key names from the BYD carsettings provider) ──
    private static final List<CarSetting> REGISTRY = buildRegistry();

    private static List<CarSetting> buildRegistry() {
        List<CarSetting> r = new ArrayList<>();
        // Locking / security / convenience
        r.add(bool("children_lock", "Child Lock", "mdi:car-door-lock"));
        r.add(bool("shut_window_after_locking", "Close Windows on Lock", "mdi:window-closed-variant"));
        r.add(bool("auto_mirror_for_lock", "Fold Mirrors on Lock", "mdi:car-side"));
        r.add(bool("rain_close_window", "Auto-close Windows in Rain", "mdi:weather-rainy"));
        r.add(enumInt("auto_lock_time", "Auto-lock Delay", "mdi:lock-clock", new int[]{0, 10, 30, 60, 120}));
        // Driving dynamics / ADAS
        r.add(bool("esp_assist", "Stability Control (ESP)", "mdi:car-traction-control"));
        r.add(bool("avh_assist", "Auto Vehicle Hold", "mdi:car-brake-hold"));
        r.add(bool("aeb", "Automatic Emergency Braking", "mdi:car-emergency"));
        r.add(bool("lane_keeping", "Lane Keeping Assist", "mdi:road-variant"));
        r.add(bool("daytime_running_lamp", "Daytime Running Lights", "mdi:car-light-dimmed"));
        r.add(enumInt("energy_recycle_setting", "Regen Level", "mdi:battery-charging", new int[]{0, 1, 2, 3}));
        r.add(enumInt("power_management", "Drive Mode", "mdi:car-sports", new int[]{0, 1, 2}));
        r.add(enumInt("auto_wipe", "Auto Wiper Sensitivity", "mdi:wiper", new int[]{0, 1, 2, 3}));
        // Charging / units / comfort
        r.add(enumInt("charge_limit", "Charge Limit %", "mdi:battery-charging-80", new int[]{50, 60, 70, 80, 90, 100}));
        r.add(enumInt("unit_temperature", "Temperature Unit (0=C,1=F)", "mdi:temperature-celsius", new int[]{0, 1}));
        r.add(range("lighting_ambient_brightness", "Ambient Light Brightness", "mdi:track-light", 0, 10, 1, null));
        return Collections.unmodifiableList(r);
    }

    private static CarSetting bool(String k, String n, String i) {
        return new CarSetting(k, Kind.BOOL, n, i, 0, 1, 1, null, null);
    }
    private static CarSetting enumInt(String k, String n, String i, int[] opts) {
        return new CarSetting(k, Kind.INT_ENUM, n, i, 0, 0, 0, opts, null);
    }
    private static CarSetting range(String k, String n, String i, int min, int max, int step, String unit) {
        return new CarSetting(k, Kind.INT_RANGE, n, i, min, max, step, null, unit);
    }

    public static List<CarSetting> registry() { return REGISTRY; }

    private static CarSetting find(String key) {
        for (CarSetting s : REGISTRY) if (s.key.equals(key)) return s;
        return null;
    }

    // ── Singleton ────────────────────────────────────────────────────────
    private static volatile BydCarSettings instance;
    public static BydCarSettings getInstance() {
        if (instance == null) {
            synchronized (BydCarSettings.class) {
                if (instance == null) instance = new BydCarSettings();
            }
        }
        return instance;
    }

    private final ConcurrentHashMap<String, Integer> cache = new ConcurrentHashMap<>();
    private volatile long lastRefreshMs = 0;
    private static final long REFRESH_TTL_MS = 30_000;
    private volatile boolean unavailable = false;

    private BydCarSettings() {}

    private ContentResolver resolver() {
        try {
            Context ctx = DaemonBootstrap.getContext();
            return ctx != null ? ctx.getContentResolver() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Read an int setting via the BYD framework helper (reflection). */
    public int readInt(String key, int def) {
        ContentResolver cr = resolver();
        if (cr == null) return def;
        try {
            Class<?> cls = Class.forName(CLS);
            Method m;
            try {
                m = cls.getMethod("getSystemInt", ContentResolver.class, String.class, int.class);
            } catch (NoSuchMethodException e) {
                m = cls.getMethod("getInt", ContentResolver.class, String.class, int.class);
            }
            Object v = m.invoke(null, cr, key, def);
            return (v instanceof Integer) ? (Integer) v : def;
        } catch (Throwable t) {
            unavailable = true;
            logger.debug("readInt(" + key + ") failed: " + t.getMessage());
            return def;
        }
    }

    /** Write an int setting (only allowlisted keys). Returns true on success. */
    public boolean writeInt(String key, int value) {
        CarSetting s = find(key);
        if (s == null) {
            logger.warn("Refusing to write non-allowlisted setting: " + key);
            return false;
        }
        if (!isValid(s, value)) {
            logger.warn("Refusing out-of-domain value " + value + " for " + key);
            return false;
        }
        ContentResolver cr = resolver();
        if (cr == null) return false;
        try {
            Class<?> cls = Class.forName(CLS);
            Method m;
            try {
                m = cls.getMethod("putInt", ContentResolver.class, String.class, int.class);
            } catch (NoSuchMethodException e) {
                m = cls.getMethod("setSystemInt", ContentResolver.class, String.class, int.class);
            }
            Object r = m.invoke(null, cr, key, value);
            boolean ok = !(r instanceof Boolean) || (Boolean) r;
            if (ok) cache.put(key, value);
            return ok;
        } catch (Throwable t) {
            logger.warn("writeInt(" + key + "=" + value + ") failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean isValid(CarSetting s, int value) {
        switch (s.kind) {
            case BOOL: return value == 0 || value == 1;
            case INT_RANGE: return value >= s.min && value <= s.max;
            case INT_ENUM:
                if (s.options == null) return true;
                for (int o : s.options) if (o == value) return true;
                return false;
            default: return false;
        }
    }

    /** Refresh the cache from the provider (throttled). */
    public void refresh(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastRefreshMs) < REFRESH_TTL_MS) return;
        lastRefreshMs = now;
        if (unavailable && !force) return;
        for (CarSetting s : REGISTRY) {
            int v = readInt(s.key, Integer.MIN_VALUE);
            if (v != Integer.MIN_VALUE) cache.put(s.key, v);
        }
    }

    /**
     * Append the cached settings as {@code setting_<key>} fields to the telemetry
     * snapshot so they flow through the per-field state topics for HA read-back.
     */
    public void snapshotInto(JSONObject payload) {
        refresh(false);
        for (CarSetting s : REGISTRY) {
            Integer v = cache.get(s.key);
            if (v != null) {
                try { payload.put("setting_" + s.key, (int) v); } catch (Exception ignored) {}
            }
        }
    }

    public boolean isUnavailable() { return unavailable; }
}
