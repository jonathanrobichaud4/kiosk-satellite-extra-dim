// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.extradim;

/**
 * Decides when automatic mode should turn Extra dim on or off from a stream of readings.
 *
 * A reading at or below {@code onAt} is dark and at or above {@code offAt} is bright. Anything in
 * between keeps the last decision, so the gap between the two thresholds stops flicker. A new zone
 * must hold for {@code delayNanos} before it counts, except for the very first decision after a
 * reset, which applies at once. Only crossings produce decisions, so a manual change sticks until
 * the light crosses the other threshold.
 */
final class AutoController {
    private double onAt;
    private double offAt;
    private long delayNanos;
    private Boolean committedDark;
    private Boolean pendingDark;
    private long pendingSince;
    private Double reading;

    void configure(double onAt, double offAt, long delayNanos) {
        boolean changed = onAt != this.onAt || offAt != this.offAt;
        this.onAt = onAt;
        this.offAt = Math.max(offAt, onAt);
        this.delayNanos = delayNanos;
        // New thresholds re-evaluate the current reading straight away.
        if (changed) resetDecision();
    }

    void reset() {
        reading = null;
        resetDecision();
    }

    private void resetDecision() {
        committedDark = null;
        pendingDark = null;
    }

    void reading(Double value) {
        if (value != null && !value.isNaN() && !value.isInfinite()) reading = value;
    }

    Double reading() { return reading; }

    /** True while the latest reading counts as dark, false while bright, null in between or unknown. */
    Boolean zone() {
        if (reading == null) return null;
        if (reading <= onAt) return Boolean.TRUE;
        if (reading >= offAt) return Boolean.FALSE;
        return null;
    }

    /** TRUE to turn Extra dim on, FALSE to turn it off, null for no change. */
    Boolean poll(long now) {
        Boolean dark = zone();
        if (dark == null || dark.equals(committedDark)) {
            pendingDark = null;
            return null;
        }
        if (!dark.equals(pendingDark)) {
            pendingDark = dark;
            pendingSince = now;
        }
        if (committedDark != null && now - pendingSince < delayNanos) return null;
        committedDark = dark;
        pendingDark = null;
        return dark;
    }
}
