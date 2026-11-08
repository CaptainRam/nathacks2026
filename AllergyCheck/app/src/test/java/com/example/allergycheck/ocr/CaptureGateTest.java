package com.example.allergycheck.ocr;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.allergycheck.ocr.FrameReadability.Verdict;

import org.junit.Before;
import org.junit.Test;

/**
 * The clock is passed in, so these pin the exact gating behaviour without waiting thirty seconds of
 * real time.
 */
public class CaptureGateTest {

    /** One analysis interval at the live path's ~4 fps. */
    private static final long STEP = 250L;

    private CaptureGate gate;
    private long now;

    @Before
    public void setUp() {
        gate = new CaptureGate();
        now = 100_000L;
        gate.start(now);
    }

    @Test
    public void disabledWhileSearching() {
        assertFalse(update(Verdict.SEARCHING).captureEnabled());
    }

    @Test
    public void enabledWhenReady() {
        assertTrue(update(Verdict.READY).captureEnabled());
    }

    @Test
    public void reDisablesWhenTheFrameGoesBadAgain() {
        // The regression this class exists to fix: the old gate unlocked once and never re-locked,
        // so the button stayed pressable on frames the app had already judged unreadable.
        assertTrue(update(Verdict.READY).captureEnabled());
        assertFalse(update(Verdict.TOO_FAR).captureEnabled());
        assertTrue(update(Verdict.READY).captureEnabled());
        assertFalse(update(Verdict.NO_INGREDIENT_LIST).captureEnabled());
    }

    @Test
    public void staysDisabledForTheWholeGracePeriod() {
        while (now < 100_000L + CaptureGate.FORCE_ENABLE_MILLIS - STEP) {
            assertFalse("enabled too early at " + (now - 100_000L) + "ms",
                    update(Verdict.SEARCHING).captureEnabled());
        }
    }

    @Test
    public void givesUpAndEnablesAfterTheGracePeriod() {
        now += CaptureGate.FORCE_ENABLE_MILLIS;
        assertTrue(update(Verdict.SEARCHING).captureEnabled());
    }

    @Test
    public void onceGivenUpItStaysEnabled() {
        // Latched on purpose: the button must not vanish from under a thumb already moving to it.
        now += CaptureGate.FORCE_ENABLE_MILLIS;
        update(Verdict.SEARCHING);
        assertTrue(update(Verdict.TOO_FAR).captureEnabled());
        assertTrue(update(Verdict.TOO_FAR).captureEnabled());
    }

    @Test
    public void aGoodSpellDoesNotPostponeGivingUp() {
        // Wall-clock from start(), not a run of bad frames: half a minute on this screen and the
        // button works, which is a rule a user can actually predict.
        now += CaptureGate.FORCE_ENABLE_MILLIS / 2;
        update(Verdict.READY);
        update(Verdict.READY);
        now += CaptureGate.FORCE_ENABLE_MILLIS / 2;
        assertTrue(update(Verdict.SEARCHING).captureEnabled());
    }

    @Test
    public void gracePeriodDoesNotRunBeforeStart() {
        // start() is called when the camera binds, so a slow camera open must not eat into it.
        CaptureGate unstarted = new CaptureGate();
        assertFalse(unstarted.update(Verdict.SEARCHING, now + CaptureGate.FORCE_ENABLE_MILLIS * 10)
                .captureEnabled());
    }

    @Test
    public void autoCaptureOffNeverFires() {
        for (int frame = 0; frame < 40; frame++) {
            CaptureGate.Decision decision = update(Verdict.READY);
            assertFalse(decision.captureNow());
            assertFalse(decision.autoCaptureArmed());
        }
    }

    @Test
    public void autoCaptureArmsOnReadyThenFiresAfterTheDwell() {
        gate.setAutoCaptureEnabled(true);
        assertTrue(update(Verdict.READY).autoCaptureArmed());
        long armedAt = now;
        while (now < armedAt + CaptureGate.AUTO_CAPTURE_DWELL_MILLIS - STEP) {
            assertFalse("fired early", update(Verdict.READY).captureNow());
        }
        now = armedAt + CaptureGate.AUTO_CAPTURE_DWELL_MILLIS;
        assertTrue(gate.update(Verdict.READY, now).captureNow());
    }

    @Test
    public void autoCaptureFiresOnlyOnce() {
        gate.setAutoCaptureEnabled(true);
        update(Verdict.READY);
        now += CaptureGate.AUTO_CAPTURE_DWELL_MILLIS;
        assertTrue(update(Verdict.READY).captureNow());
        assertFalse(update(Verdict.READY).captureNow());
        assertFalse(update(Verdict.READY).captureNow());
    }

    @Test
    public void leavingReadyCancelsThePendingShot() {
        gate.setAutoCaptureEnabled(true);
        update(Verdict.READY);
        assertFalse(update(Verdict.TOO_FAR).autoCaptureArmed());
        now += CaptureGate.AUTO_CAPTURE_DWELL_MILLIS * 2;
        assertFalse(update(Verdict.TOO_FAR).captureNow());
    }

    @Test
    public void reEnteringReadyRestartsTheDwellFromZero() {
        // Resuming a part-finished countdown would shoot a frame nobody vouched for.
        gate.setAutoCaptureEnabled(true);
        update(Verdict.READY);
        now += CaptureGate.AUTO_CAPTURE_DWELL_MILLIS - STEP;
        update(Verdict.TOO_FAR);
        update(Verdict.READY);
        assertFalse(update(Verdict.READY).captureNow());
        now += CaptureGate.AUTO_CAPTURE_DWELL_MILLIS;
        assertTrue(update(Verdict.READY).captureNow());
    }

    @Test
    public void switchingAutoCaptureOffCancelsThePendingShot() {
        gate.setAutoCaptureEnabled(true);
        update(Verdict.READY);
        gate.setAutoCaptureEnabled(false);
        now += CaptureGate.AUTO_CAPTURE_DWELL_MILLIS * 2;
        assertFalse(update(Verdict.READY).captureNow());
    }

    @Test
    public void givingUpNeverTriggersAutoCapture() {
        // The give-up timer exists so a person can overrule the heuristic. Letting it fire the
        // shutter would be the app overruling itself and photographing a frame it expects to fail.
        gate.setAutoCaptureEnabled(true);
        now += CaptureGate.FORCE_ENABLE_MILLIS;
        for (int frame = 0; frame < 20; frame++) {
            CaptureGate.Decision decision = update(Verdict.SEARCHING);
            assertTrue(decision.captureEnabled());
            assertFalse(decision.captureNow());
            assertFalse(decision.autoCaptureArmed());
        }
    }

    @Test
    public void stopSuppressesAnyFurtherCapture() {
        gate.setAutoCaptureEnabled(true);
        update(Verdict.READY);
        gate.stop();
        now += CaptureGate.AUTO_CAPTURE_DWELL_MILLIS * 2;
        assertFalse(update(Verdict.READY).captureNow());
    }

    private CaptureGate.Decision update(Verdict verdict) {
        now += STEP;
        return gate.update(verdict, now);
    }
}
