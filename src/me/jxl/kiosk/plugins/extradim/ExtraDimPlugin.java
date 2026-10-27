// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.extradim;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Controls Android's Extra dim (Settings.Secure reduce_bright_colors_activated) through
 * Kiosk Satellite's Shizuku connection. Shell access (UID 2000) is enough because the
 * shell user holds WRITE_SECURE_SETTINGS.
 *
 * Only one Shizuku command may be pending at a time, so every request goes through tick():
 * a queued write wins over a scheduled read, and every write is followed by a read that
 * confirms what the tablet actually did.
 */
public final class ExtraDimPlugin implements KioskPlugin {
    static final String SWITCH_KEY = "extra_dim";
    static final String SWITCH_NAME = "Extra dim";
    static final String SETTINGS = "/system/bin/settings";
    static final String SH = "/system/bin/sh";
    static final String MODE_OFF = "Off";
    static final String MODE_LUX = "Room light";
    static final String MODE_PANEL = "Panel brightness";
    static final String LIGHT_EVENT = "device.light";
    static final String BRIGHTNESS_EVENT = "screen.brightness";
    static final String READ_SCRIPT =
        "settings get secure reduce_bright_colors_activated; settings get secure reduce_bright_colors_level";

    private static final long MIN_GAP = TimeUnit.MILLISECONDS.toNanos(300);

    private final LongSupplier clock;
    private final boolean runTimer;
    private ScheduledExecutorService timer;

    private PluginHost host;
    private boolean pending;
    private long requestId;
    private long lastRequest;
    private long nextPoll;

    // Settings
    private boolean controlLevel;
    private int level = 50;
    private long pollNanos = TimeUnit.SECONDS.toNanos(15);
    private String autoMode = MODE_OFF;

    // Automatic mode
    private final AutoController auto = new AutoController();
    private String subscribed;
    private long autoRevision;
    private String autoText;
    private boolean noSensor;

    // State
    private Boolean knownOn;      // null until the first successful read
    private Integer knownLevel;
    private Boolean wantedOn;     // a write waiting to run, null when none
    private boolean lastAccess;
    private boolean accessPublished;

    public ExtraDimPlugin() { this(System::nanoTime, true); }
    ExtraDimPlugin(LongSupplier clock, boolean runTimer) {
        this.clock = clock;
        this.runTimer = runTimer;
    }

    @Override public synchronized void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        subscribed = null;
        autoText = null;
        noSensor = false;
        applySettings(settings);
        nextPoll = 0;
        if (runTimer) {
            timer = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "extra-dim-control");
                thread.setDaemon(true);
                return thread;
            });
            timer.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
        }
        tick();
    }

    @Override public synchronized void configure(Map<String, Object> settings) {
        boolean wasControlling = controlLevel;
        int previousLevel = level;
        applySettings(settings);

        // Apply a new intensity straight away while Extra dim is on.
        if (controlLevel && Boolean.TRUE.equals(knownOn) && wantedOn == null
            && (!wasControlling || previousLevel != level)) {
            wantedOn = Boolean.TRUE;
        }
        tick();
    }

    @Override public synchronized void execute(String command, Map<String, Object> arguments) {
        if ("turnOn".equals(command)) request(true);
        else if ("turnOff".equals(command)) request(false);
        else if ("toggle".equals(command)) request(!currentOn());
        else throw new IllegalArgumentException("Unknown command: " + command);
    }

    @Override public synchronized void onEvent(String event, Map<String, Object> payload) {
        if (("switch." + SWITCH_KEY).equals(event)) {
            Object on = payload == null ? null : payload.get("on");
            if (!(on instanceof Boolean)) throw new IllegalArgumentException("Extra dim switch requires a boolean");
            request((Boolean) on);
        } else if (("ks." + LIGHT_EVENT).equals(event)) {
            if (MODE_LUX.equals(autoMode)) auto.reading(number(payload == null ? null : payload.get("lux")));
            tick();
        } else if (("ks." + BRIGHTNESS_EVENT).equals(event)) {
            if (MODE_PANEL.equals(autoMode)) auto.reading(number(payload == null ? null : payload.get("panel")));
            tick();
        } else if ("shizuku.state".equals(event)) {
            nextPoll = 0;
            tick();
        }
    }

    @Override public void stop() {
        ScheduledExecutorService old;
        synchronized (this) {
            requestId++;
            host = null;
            pending = false;
            wantedOn = null;
            knownOn = null;
            knownLevel = null;
            accessPublished = false;
            subscribed = null;
            autoRevision++;
            auto.reset();
            old = timer;
            timer = null;
        }
        if (old != null) {
            old.shutdownNow();
            try {
                old.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void applySettings(Map<String, Object> settings) {
        controlLevel = Boolean.TRUE.equals(settings.get("controlLevel"));
        Object rawLevel = settings.get("level");
        if (rawLevel instanceof Number) level = clamp(((Number) rawLevel).intValue(), 0, 100);
        Object rawPoll = settings.get("pollSeconds");
        if (rawPoll instanceof Number) {
            pollNanos = TimeUnit.SECONDS.toNanos(clamp(((Number) rawPoll).intValue(), 5, 300));
        }
        String nextMode = String.valueOf(settings.get("autoMode"));
        if (!MODE_LUX.equals(nextMode) && !MODE_PANEL.equals(nextMode)) nextMode = MODE_OFF;
        long delay = TimeUnit.SECONDS.toNanos(clamp(intSetting(settings, "autoDelay", 10), 0, 300));
        if (MODE_LUX.equals(nextMode)) {
            auto.configure(doubleSetting(settings, "luxOn", 5), doubleSetting(settings, "luxOff", 20), delay);
        } else if (MODE_PANEL.equals(nextMode)) {
            auto.configure(doubleSetting(settings, "panelOn", 2) / 100.0, doubleSetting(settings, "panelOff", 10) / 100.0, delay);
        }
        if (!nextMode.equals(autoMode)) {
            autoMode = nextMode;
            auto.reset();
            autoRevision++;
            noSensor = false;
        }
        updateSubscription();
    }

    private void updateSubscription() {
        if (host == null) return;
        String wanted = MODE_LUX.equals(autoMode) ? LIGHT_EVENT : MODE_PANEL.equals(autoMode) ? BRIGHTNESS_EVENT : null;
        if (wanted == null ? subscribed == null : wanted.equals(subscribed)) return;
        try {
            if (subscribed != null) host.unsubscribe(subscribed);
            subscribed = null;
            if (wanted == null) return;
            // Subscribe first, then read the starting value, as the SDK recommends.
            host.subscribe(wanted);
            subscribed = wanted;
            readInitial(wanted);
        } catch (RuntimeException failure) {
            host.status("Automatic mode could not watch " + (LIGHT_EVENT.equals(wanted) ? "the light sensor" : "brightness")
                + ": " + message(failure), true);
        }
    }

    private void readInitial(String event) {
        final PluginHost owner = host;
        final long revision = autoRevision;
        final boolean light = LIGHT_EVENT.equals(event);
        Map<String, Object> arguments = light
            ? Collections.<String, Object>emptyMap()
            : Collections.<String, Object>singletonMap("panel", Boolean.TRUE);
        owner.executeCommand(light ? "getLightLevel" : "getBrightness", arguments, (ok, data, error) -> {
            synchronized (ExtraDimPlugin.this) {
                if (host != owner || revision != autoRevision || !ok) return;
                // An event that arrived meanwhile is newer than this read.
                if (auto.reading() != null) return;
                if (light) {
                    Map<?, ?> level = data instanceof Map ? (Map<?, ?>) data : null;
                    if (level != null && Boolean.FALSE.equals(level.get("present"))) {
                        noSensor = true;
                        tick();
                        return;
                    }
                    auto.reading(level == null ? null : number(level.get("lux")));
                } else {
                    auto.reading(number(data));
                }
                tick();
            }
        });
    }

    /** Applies automatic decisions and refreshes the Automatic sensor. */
    private void autoStep(long now) {
        if (MODE_OFF.equals(autoMode)) {
            publishAuto("Off");
            return;
        }
        if (noSensor && MODE_LUX.equals(autoMode)) {
            publishAuto("Room light: this tablet has no light sensor");
            return;
        }
        Double reading = auto.reading();
        Boolean zone = auto.zone();
        String where = zone == null ? "between thresholds" : zone ? "dark" : "bright";
        publishAuto(reading == null ? autoMode + ": waiting for a reading"
            : MODE_LUX.equals(autoMode)
            ? String.format(Locale.ROOT, "Room light %.0f lux, %s", reading, where)
            : String.format(Locale.ROOT, "Panel brightness %.0f%%, %s", reading * 100, where));
        Boolean turnOn = auto.poll(now);
        if (turnOn != null && !turnOn.equals(knownOn) && wantedOn == null) wantedOn = turnOn;
    }

    private void publishAuto(String text) {
        if (text.equals(autoText)) return;
        autoText = text;
        host.publishTextSensor("auto", "Automatic", text);
    }

    private boolean currentOn() {
        if (wantedOn != null) return wantedOn;
        return Boolean.TRUE.equals(knownOn);
    }

    private void request(boolean on) {
        if (host == null) return;
        if (!granted()) {
            host.status("Extra dim was not changed. Grant Kiosk Satellite access in Shizuku access above.", true);
            republish();
            return;
        }
        wantedOn = on;
        tick();
    }

    /** Runs at most one Shizuku command. Called by the timer, events and finished commands. */
    synchronized void tick() {
        if (host == null) return;
        long now = clock.getAsLong();
        boolean access;
        try {
            access = granted();
        } catch (RuntimeException failure) {
            access = false;
        }
        publishAccess(access);
        if (!access) {
            wantedOn = null;
            Map<String, Object> state = safeState();
            String status = state == null ? null : String.valueOf(state.get("status"));
            host.status("permission_required".equals(status) || "denied".equals(status)
                ? "Tap Shizuku access above and allow Kiosk Satellite."
                : "Start Shizuku on the tablet, then grant Kiosk Satellite access.", false);
            return;
        }
        autoStep(now);
        if (pending || (lastRequest != 0 && now - lastRequest < MIN_GAP)) return;
        if (wantedOn != null) {
            boolean on = wantedOn;
            wantedOn = null;
            run(writeCommand(on), true, now);
        } else if (now >= nextPoll) {
            run(new String[] {SH, "-c", READ_SCRIPT}, false, now);
        }
    }

    String[] writeCommand(boolean on) {
        if (on && controlLevel) {
            // Both values are integers this plugin controls, so the script holds no untrusted text.
            return new String[] {SH, "-c",
                "settings put secure reduce_bright_colors_level " + level
                    + " && settings put secure reduce_bright_colors_activated 1"};
        }
        return new String[] {SETTINGS, "put", "secure", "reduce_bright_colors_activated", on ? "1" : "0"};
    }

    private void run(String[] command, boolean write, long now) {
        final PluginHost owner = host;
        final long token = ++requestId;
        pending = true;
        lastRequest = now;
        nextPoll = now + pollNanos;
        try {
            owner.executeShizuku(command, 3000, (ok, data, error) -> finished(owner, token, write, ok, data, error));
        } catch (RuntimeException failure) {
            pending = false;
            owner.status("Shizuku could not run the command: " + message(failure), true);
            if (write) republish();
        }
    }

    private synchronized void finished(PluginHost owner, long token, boolean write, boolean ok, Object data, String error) {
        if (host != owner || token != requestId) return;
        pending = false;
        Map<?, ?> result = data instanceof Map ? (Map<?, ?>) data : null;
        String failure = null;
        if (!ok || result == null) failure = error == null ? "Shizuku request failed." : error;
        else if (Boolean.TRUE.equals(result.get("timedOut"))) failure = "The settings command timed out.";
        else if (!(result.get("exitCode") instanceof Number) || ((Number) result.get("exitCode")).intValue() != 0) {
            String stderr = String.valueOf(result.get("stderr")).trim();
            failure = "The settings command failed" + (stderr.isEmpty() || "null".equals(stderr) ? "." : ": " + trim(stderr));
        }
        if (failure != null) {
            host.status(failure, true);
            if (write) republish();
            return;
        }
        if (write) {
            // Confirm what the tablet actually applied.
            nextPoll = 0;
        } else {
            parseRead(String.valueOf(result.get("stdout")));
        }
        tick();
    }

    void parseRead(String stdout) {
        String[] lines = stdout.trim().split("\\s+");
        Boolean on = null;
        if (lines.length > 0) {
            // A device that never used the feature reports "null", which means off.
            if ("1".equals(lines[0])) on = Boolean.TRUE;
            else if ("0".equals(lines[0]) || "null".equals(lines[0])) on = Boolean.FALSE;
        }
        Integer readLevel = null;
        if (lines.length > 1) {
            try {
                readLevel = Integer.parseInt(lines[1]);
            } catch (NumberFormatException ignored) {
                readLevel = null;
            }
        }
        if (on == null) {
            host.status("Unexpected reply from settings: " + trim(stdout), true);
            return;
        }
        knownOn = on;
        knownLevel = readLevel;
        host.publishSwitch(SWITCH_KEY, SWITCH_NAME, on);
        host.publishTextSensor("level", "Extra dim intensity", readLevel == null ? null : readLevel + "%");
        host.status("Extra dim is " + (on ? "on" : "off")
            + (readLevel == null ? "." : " at " + readLevel + "% intensity."), false);
    }

    private void republish() {
        if (host != null && knownOn != null) host.publishSwitch(SWITCH_KEY, SWITCH_NAME, knownOn);
    }

    private void publishAccess(boolean access) {
        if (accessPublished && access == lastAccess) return;
        host.publishBinarySensor("shizuku", "Shizuku access", "connectivity", access);
        lastAccess = access;
        accessPublished = true;
    }

    private boolean granted() {
        Map<String, Object> state = safeState();
        return state != null && Boolean.TRUE.equals(state.get("granted"));
    }

    private Map<String, Object> safeState() {
        try {
            return host.shizukuState();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    // Visible for tests.
    synchronized Boolean knownOn() { return knownOn; }
    synchronized Integer knownLevel() { return knownLevel; }

    private static Double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }
    private static int intSetting(Map<String, Object> settings, String key, int fallback) {
        Object value = settings.get(key);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }
    private static double doubleSetting(Map<String, Object> settings, String key, double fallback) {
        Object value = settings.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static String trim(String text) { return text.length() > 200 ? text.substring(0, 200) : text; }
    private static String message(RuntimeException failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
