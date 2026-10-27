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
        System.out.println("ExtraDimPluginTest passed");
    }

    static Map<String, Object> settings(boolean controlLevel, int level) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("controlLevel", controlLevel);
        settings.put("level", level);
        settings.put("pollSeconds", 15);
        return settings;
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
}
