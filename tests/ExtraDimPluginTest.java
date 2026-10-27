// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.extradim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.PluginHost;

/** Runs the plugin against a fake host that behaves like `settings` on a tablet. */
public final class ExtraDimPluginTest {
    static final class FakeHost implements PluginHost {
        boolean granted = true;
        String activated = "null";
        String level = "50";
        boolean failNext;
        final List<String[]> commands = new ArrayList<>();
        final Map<String, Boolean> switches = new HashMap<>();
        final Map<String, String> texts = new HashMap<>();
        String status;
        boolean statusError;
        CommandCallback waiting;
        Object waitingData;
        final java.util.Set<String> subscriptions = new java.util.HashSet<>();
        final List<Runnable> later = new ArrayList<>();
        Double lux = 50.0;
        boolean sensorPresent = true;
        Double panel = 0.5;

        @Override public void subscribe(String event) { subscriptions.add(event); }
        @Override public void unsubscribe(String event) { subscriptions.remove(event); }
        @Override public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
            if ("getLightLevel".equals(command)) {
                Map<String, Object> level = new HashMap<>();
                level.put("present", sensorPresent);
                level.put("lux", lux);
                level.put("live", true);
                later.add(() -> callback.onResult(true, level, null));
            } else if ("getBrightness".equals(command) && Boolean.TRUE.equals(arguments.get("panel"))) {
                later.add(() -> callback.onResult(true, panel, null));
            } else {
                later.add(() -> callback.onResult(false, null, "unsupported"));
            }
        }

        @Override public Map<String, Object> shizukuState() {
            Map<String, Object> state = new HashMap<>();
            state.put("granted", granted);
            state.put("status", granted ? "ready" : "permission_required");
            return state;
        }
        @Override public void executeShizuku(String[] command, int timeoutMs, CommandCallback callback) {
            if (waiting != null) throw new IllegalStateException("A command is already pending");
            commands.add(command);
            Map<String, Object> result = new HashMap<>();
            result.put("timedOut", false);
            result.put("truncated", false);
            result.put("stderr", "");
            if (failNext) {
                failNext = false;
                result.put("exitCode", 255);
                result.put("stderr", "Security exception");
                result.put("stdout", "");
            } else {
                String joined = String.join(" ", command);
                if (joined.contains("settings put secure reduce_bright_colors_level")) {
                    level = joined.replaceAll(".*reduce_bright_colors_level (\\d+).*", "$1");
                }
                if (command[0].equals(ExtraDimPlugin.SETTINGS)) activated = command[4];
                else if (joined.contains("put secure reduce_bright_colors_activated 1")) activated = "1";
                result.put("exitCode", 0);
                result.put("stdout", joined.contains("settings get") ? activated + "\n" + level + "\n" : "");
            }
            waiting = callback;
            waitingData = result;
        }
        /** Delivers the pending command's result, like KS's callback executor. */
        boolean deliver() {
            if (!later.isEmpty()) {
                List<Runnable> run = new ArrayList<>(later);
                later.clear();
                run.forEach(Runnable::run);
                return true;
            }
            if (waiting == null) return false;
            CommandCallback callback = waiting;
            waiting = null;
            callback.onResult(true, waitingData, null);
            return true;
        }
        @Override public void showWindow(String title, String message, String buttonLabel) {}
        @Override public void hideWindow() {}
        @Override public void log(String message) {}
        @Override public void status(String message, boolean error) { status = message; statusError = error; }
        @Override public void publishSwitch(String key, String name, boolean state) { switches.put(key, state); }
        @Override public void publishTextSensor(String key, String name, String state) { texts.put(key, state); }
        @Override public void publishBinarySensor(String key, String name, String deviceClass, Boolean state) {}
    }

    static long now = 1;

    public static void main(String[] args) {
        readsInitialState();
        switchTurnsOnAndConfirms();
        toggleAndCommands();
        intensityIsWritten();
        intensityChangeAppliesWhileOn();
        failureRevertsSwitch();
        noAccessDoesNothing();
        pollPicksUpQuickSettingsChange();
        autoControllerHysteresisAndDelay();
        roomLightMode();
        panelBrightnessMode();
        noLightSensor();
        switchingModesChangesSubscription();
        System.out.println("ExtraDimPluginTest passed");
    }

    static Map<String, Object> settings(boolean controlLevel, int level) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("controlLevel", controlLevel);
        settings.put("level", level);
        settings.put("pollSeconds", 15);
        settings.put("autoMode", "Off");
        settings.put("luxOn", 5);
        settings.put("luxOff", 20);
        settings.put("panelOn", 2);
        settings.put("panelOff", 10);
        settings.put("autoDelay", 10);
        return settings;
    }

    static Map<String, Object> autoSettings(String mode) {
        Map<String, Object> settings = settings(false, 50);
        settings.put("autoMode", mode);
        return settings;
    }

    static Map<String, Object> event(String key, Object value) {
        return java.util.Collections.<String, Object>singletonMap(key, value);
    }

    static ExtraDimPlugin plugin() { return new ExtraDimPlugin(() -> now, false); }
    static void advance(long millis) { now += TimeUnit.MILLISECONDS.toNanos(millis); }

    /** Delivers results and ticks until the plugin is idle. */
    static void settle(FakeHost host, ExtraDimPlugin plugin) {
        for (int i = 0; i < 20; i++) {
            advance(400);
            plugin.tick();
            if (!host.deliver()) {
                advance(400);
                plugin.tick();
                if (host.waiting == null) return;
            }
        }
        throw new AssertionError("Plugin never went idle");
    }

    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    static void readsInitialState() {
        FakeHost host = new FakeHost();
        ExtraDimPlugin plugin = plugin();
        plugin.start(host, settings(false, 50));
        settle(host, plugin);
        check(Boolean.FALSE.equals(host.switches.get("extra_dim")), "null setting should read as off");
        check("50%".equals(host.texts.get("level")), "level should be published");
        plugin.stop();
    }

    static void switchTurnsOnAndConfirms() {
        FakeHost host = new FakeHost();
        ExtraDimPlugin plugin = plugin();
        plugin.start(host, settings(false, 50));
        settle(host, plugin);
        int before = host.commands.size();
        plugin.onEvent("switch.extra_dim", java.util.Collections.<String, Object>singletonMap("on", true));
        settle(host, plugin);
        String[] write = host.commands.get(before);
        check(Arrays.equals(write, new String[] {"/system/bin/settings", "put", "secure", "reduce_bright_colors_activated", "1"}),
            "should write activated=1, got " + Arrays.toString(write));
        check(host.commands.size() == before + 2, "write should be followed by a confirming read");
        check(Boolean.TRUE.equals(host.switches.get("extra_dim")), "switch should report on");
        check("1".equals(host.activated), "tablet should be on");
        plugin.stop();
    }

    static void toggleAndCommands() {
        FakeHost host = new FakeHost();
        ExtraDimPlugin plugin = plugin();
        plugin.start(host, settings(false, 50));
        settle(host, plugin);
        plugin.execute("toggle", java.util.Collections.<String, Object>emptyMap());
        settle(host, plugin);
        check("1".equals(host.activated), "toggle from off should turn on");
        plugin.execute("toggle", java.util.Collections.<String, Object>emptyMap());
        settle(host, plugin);
        check("0".equals(host.activated), "toggle from on should turn off");
        plugin.execute("turnOn", java.util.Collections.<String, Object>emptyMap());
        settle(host, plugin);
        check("1".equals(host.activated), "turnOn");
        plugin.execute("turnOff", java.util.Collections.<String, Object>emptyMap());
        settle(host, plugin);
        check("0".equals(host.activated), "turnOff");
        try {
            plugin.execute("explode", java.util.Collections.<String, Object>emptyMap());
            throw new AssertionError("unknown command should throw");
        } catch (IllegalArgumentException expected) {}
        plugin.stop();
    }

    static void intensityIsWritten() {
        FakeHost host = new FakeHost();
        ExtraDimPlugin plugin = plugin();
        plugin.start(host, settings(true, 80));
        settle(host, plugin);
        plugin.execute("turnOn", java.util.Collections.<String, Object>emptyMap());
        settle(host, plugin);
        check("80".equals(host.level), "level should be written, was " + host.level);
        check("1".equals(host.activated), "should be on");
        check("80%".equals(host.texts.get("level")), "level sensor should update");
        plugin.execute("turnOff", java.util.Collections.<String, Object>emptyMap());
        settle(host, plugin);
        check("80".equals(host.level), "turning off leaves the level alone");
        plugin.stop();
    }

    static void intensityChangeAppliesWhileOn() {
        FakeHost host = new FakeHost();
        host.activated = "1";
        ExtraDimPlugin plugin = plugin();
        plugin.start(host, settings(true, 30));
        settle(host, plugin);
        plugin.configure(settings(true, 70));
        settle(host, plugin);
        check("70".equals(host.level), "new intensity should apply while on, was " + host.level);
        plugin.stop();
    }

    static void failureRevertsSwitch() {
        FakeHost host = new FakeHost();
        ExtraDimPlugin plugin = plugin();
        plugin.start(host, settings(false, 50));
        settle(host, plugin);
        host.failNext = true;
        host.switches.put("extra_dim", true); // HA optimistic state
        plugin.onEvent("switch.extra_dim", java.util.Collections.<String, Object>singletonMap("on", true));
        settle(host, plugin);
        check(Boolean.FALSE.equals(host.switches.get("extra_dim")), "failed write should republish off");
        check(host.statusError && host.status.contains("Security exception"), "failure should show stderr: " + host.status);
        plugin.stop();
    }

    static void noAccessDoesNothing() {
        FakeHost host = new FakeHost();
        host.granted = false;
        ExtraDimPlugin plugin = plugin();
        plugin.start(host, settings(false, 50));
        plugin.execute("turnOn", java.util.Collections.<String, Object>emptyMap());
        settle(host, plugin);
        check(host.commands.isEmpty(), "no commands without Shizuku access");
        check(host.status != null && host.status.contains("Shizuku"), "status should explain access");
        plugin.stop();
    }

    static void pollPicksUpQuickSettingsChange() {
        FakeHost host = new FakeHost();
        ExtraDimPlugin plugin = plugin();
        plugin.start(host, settings(false, 50));
        settle(host, plugin);
        host.activated = "1"; // user flipped it in quick settings
        advance(16_000);
        settle(host, plugin);
        check(Boolean.TRUE.equals(host.switches.get("extra_dim")), "poll should pick up the change");
        plugin.stop();
    }

    static void autoControllerHysteresisAndDelay() {
        AutoController auto = new AutoController();
        long delay = TimeUnit.SECONDS.toNanos(10);
        auto.configure(5, 20, delay);
        check(auto.poll(0) == null, "no reading, no decision");
        auto.reading(12.0);
        check(auto.poll(0) == null, "between thresholds at start does nothing");
        auto.reading(3.0);
        check(Boolean.TRUE.equals(auto.poll(0)), "first dark reading applies at once");
        auto.reading(12.0);
        check(auto.poll(TimeUnit.SECONDS.toNanos(60)) == null, "between thresholds keeps the decision");
        auto.reading(25.0);
        long t = TimeUnit.SECONDS.toNanos(100);
        check(auto.poll(t) == null, "bright must hold for the delay");
        auto.reading(4.0);
        check(auto.poll(t + TimeUnit.SECONDS.toNanos(5)) == null, "back to dark before the delay cancels it");
        auto.reading(25.0);
        check(auto.poll(t + TimeUnit.SECONDS.toNanos(6)) == null, "bright again restarts the delay");
        check(auto.poll(t + TimeUnit.SECONDS.toNanos(15)) == null, "still within the restarted delay");
        check(Boolean.FALSE.equals(auto.poll(t + TimeUnit.SECONDS.toNanos(17))), "bright after the delay turns off");
        check(auto.poll(t + TimeUnit.SECONDS.toNanos(60)) == null, "no repeat decisions");
        auto.reading(Double.NaN);
        check(auto.reading() == 25.0, "NaN readings are ignored");
    }

    static void roomLightMode() {
        FakeHost host = new FakeHost();
        host.lux = 2.0;
        ExtraDimPlugin plugin = plugin();
        plugin.start(host, autoSettings("Room light"));
        check(host.subscriptions.contains("device.light"), "should watch the light sensor");
        settle(host, plugin);
        check("1".equals(host.activated), "a dark room at start turns Extra dim on");
        check(host.texts.get("auto").startsWith("Room light 2 lux, dark"), "auto sensor: " + host.texts.get("auto"));

        plugin.onEvent("ks.device.light", event("lux", 12.0));
        advance(30_000);
        settle(host, plugin);
        check("1".equals(host.activated), "between thresholds stays on");

        plugin.onEvent("ks.device.light", event("lux", 40.0));
        settle(host, plugin);
        check("1".equals(host.activated), "bright has to hold for the delay");
        advance(11_000);
        settle(host, plugin);
        check("0".equals(host.activated), "bright after the delay turns it off");

        // A manual change sticks until the light crosses the other threshold.
        plugin.execute("turnOn", java.util.Collections.<String, Object>emptyMap());
        settle(host, plugin);
        plugin.onEvent("ks.device.light", event("lux", 45.0));
        advance(30_000);
        settle(host, plugin);
        check("1".equals(host.activated), "manual on stays on in a bright room");
        plugin.stop();
    }

    static void panelBrightnessMode() {
        FakeHost host = new FakeHost();
        host.panel = 0.4;
        ExtraDimPlugin plugin = plugin();
        plugin.start(host, autoSettings("Panel brightness"));
        check(host.subscriptions.contains("screen.brightness"), "should watch brightness");
        settle(host, plugin);
        check(!"1".equals(host.activated), "bright panel leaves Extra dim off");
        check(host.texts.get("auto").startsWith("Panel brightness 40%, bright"), "auto sensor: " + host.texts.get("auto"));
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("level", 0.0);
        payload.put("panel", 0.01);
        plugin.onEvent("ks.screen.brightness", payload);
        advance(11_000);
        settle(host, plugin);
        check("1".equals(host.activated), "panel at minimum turns Extra dim on");
        plugin.stop();
    }

    static void noLightSensor() {
        FakeHost host = new FakeHost();
        host.sensorPresent = false;
        ExtraDimPlugin plugin = plugin();
        plugin.start(host, autoSettings("Room light"));
        settle(host, plugin);
        check(host.texts.get("auto").contains("no light sensor"), "should explain missing sensor: " + host.texts.get("auto"));
        plugin.stop();
    }

    static void switchingModesChangesSubscription() {
        FakeHost host = new FakeHost();
        ExtraDimPlugin plugin = plugin();
        plugin.start(host, autoSettings("Room light"));
        settle(host, plugin);
        plugin.configure(autoSettings("Panel brightness"));
        check(host.subscriptions.equals(java.util.Collections.singleton("screen.brightness")), "subs: " + host.subscriptions);
        plugin.configure(autoSettings("Off"));
        check(host.subscriptions.isEmpty(), "Off should unsubscribe");
        settle(host, plugin);
        check("Off".equals(host.texts.get("auto")), "auto sensor should say Off");
        plugin.stop();
    }
}
