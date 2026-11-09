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

    // ---- read quality: the app must not photograph a frame it can tell it misread --------------

    @Test
    public void aBadReadNeverFires() {
        // The frame parses as an ingredient list and is otherwise READY, but the text came back
        // fragmented ("citric ac d"). Shooting it produces a confident, wrong report — strictly
        // worse than a failed scan, because nothing downstream can tell the difference.
        gate.setAutoCaptureEnabled(true);
        for (int frame = 0; frame < 40; frame++) {
            assertFalse("fired on a bad read", updateDirty(Verdict.READY).captureNow());
        }
    }

    @Test
    public void aBadReadStillLeavesTheButtonEnabled() {
        // The whole point of keeping this out of the verdict. The post-capture pipeline conditions
        // the image far more aggressively than the live path can afford to and often reads a panel
        // the live frame fumbled, so the user must always be able to overrule.
        gate.setAutoCaptureEnabled(true);
        assertTrue(updateDirty(Verdict.READY).captureEnabled());
    }

    @Test
    public void aBadReadIsReportedAsHeld() {
        // Surfaced so the screen can say so: a countdown that silently never completes leaves the
        // user holding still waiting for a shutter that is not coming.
        gate.setAutoCaptureEnabled(true);
        CaptureGate.Decision decision = updateDirty(Verdict.READY);
        assertTrue(decision.autoCaptureHeld());
        assertFalse(decision.autoCaptureArmed());
    }

    @Test
    public void nothingIsHeldWhileAutoCaptureIsOff() {
        // With no automatic shot pending there is nothing to withhold, and saying otherwise would
        // put an explanation on screen for a decision the app never made.
        assertFalse(updateDirty(Verdict.READY).autoCaptureHeld());
    }

    @Test
    public void nothingIsHeldOnAFrameThatIsNotReadyAnyway() {
        gate.setAutoCaptureEnabled(true);
        assertFalse(updateDirty(Verdict.TOO_FAR).autoCaptureHeld());
    }

    @Test
    public void aBadReadRestartsTheDwell() {
        // Not a pause. The shot must land on a run of frames that all read well, so one bad frame
        // part-way through sends the countdown back to zero rather than letting it resume.
        gate.setAutoCaptureEnabled(true);
        update(Verdict.READY);
        now += CaptureGate.AUTO_CAPTURE_DWELL_MILLIS - STEP;
        updateDirty(Verdict.READY);
        assertFalse(update(Verdict.READY).captureNow());
        now += CaptureGate.AUTO_CAPTURE_DWELL_MILLIS;
        assertTrue(update(Verdict.READY).captureNow());
    }

    @Test
    public void cleanFramesAfterABadSpellStillFire() {
        // Holding the shutter must be recoverable: once the read steadies, auto-capture works
        // normally again. Otherwise one blurred frame would disable the feature for the session.
        gate.setAutoCaptureEnabled(true);
        for (int frame = 0; frame < 6; frame++) {
            updateDirty(Verdict.READY);
        }
        update(Verdict.READY);
        now += CaptureGate.AUTO_CAPTURE_DWELL_MILLIS;
        assertTrue(update(Verdict.READY).captureNow());
    }

    @Test
    public void theHeldExplanationClearsOnceTheReadSteadies() {
        gate.setAutoCaptureEnabled(true);
        assertTrue(updateDirty(Verdict.READY).autoCaptureHeld());
        assertFalse(update(Verdict.READY).autoCaptureHeld());
    }

    // ---- refresh: the periodic stale check, which carries no frame of its own ------------------

    @Test
    public void refreshNeverFiresEvenOnAHeldReady() {
        // The regression this split exists for. The dwell is shorter than the window after which a
        // stalled recogniser is noticed, so a gate that let the watchdog advance it answered a total
        // analysis stall by taking the photo — on evidence over a second old.
        gate.setAutoCaptureEnabled(true);
        update(Verdict.READY);
        for (int tick = 0; tick < 20; tick++) {
            now += 400L;
            assertFalse("the watchdog fired the shutter", gate.refresh(Verdict.READY, now)
                    .captureNow());
        }
    }

    @Test
    public void refreshDoesNotAdvanceTheDwell() {
        // Interleaved exactly as the screen does it: frames at ~250 ms, the stale check at 400 ms.
        // Only the frames may count towards the shot.
        gate.setAutoCaptureEnabled(true);
        update(Verdict.READY);
        long armedAt = now;
        now = armedAt + CaptureGate.AUTO_CAPTURE_DWELL_MILLIS * 2;
        assertFalse(gate.refresh(Verdict.READY, now).captureNow());
        // The next real frame is what completes it.
        assertTrue(gate.update(Verdict.READY, now).captureNow());
    }

    @Test
    public void refreshDoesNotCancelAnActiveCountdown() {
        // The watchdog ticks between frames, so cancelling on its say-so would mean auto-capture
        // could never complete at all.
        gate.setAutoCaptureEnabled(true);
        update(Verdict.READY);
        long armedAt = now;
        now += 400L;
        assertTrue(gate.refresh(Verdict.READY, now).autoCaptureArmed());
        now = armedAt + CaptureGate.AUTO_CAPTURE_DWELL_MILLIS;
        assertTrue(gate.update(Verdict.READY, now).captureNow());
    }

    @Test
    public void refreshCancelsACountdownWhoseVerdictHasLapsed() {
        // Once the stale check has dropped the display back to SEARCHING the shot is off; leaving
        // it armed would strand the screen on "taking the photo".
        gate.setAutoCaptureEnabled(true);
        update(Verdict.READY);
        now += 400L;
        assertFalse(gate.refresh(Verdict.SEARCHING, now).autoCaptureArmed());
    }

    @Test
    public void refreshKeepsTheGiveUpTimerRunning() {
        // A user whose recogniser never produces a single frame is the one who most needs the
        // button to come alive, and the watchdog is the only thing still ticking for them.
        now += CaptureGate.FORCE_ENABLE_MILLIS;
        assertTrue(gate.refresh(Verdict.SEARCHING, now).captureEnabled());
    }

    private CaptureGate.Decision update(Verdict verdict) {
        now += STEP;
        return gate.update(verdict, true, now);
    }

    /** One frame whose ingredient list parsed but came back fragmented; see {@code ReadQuality}. */
    private CaptureGate.Decision updateDirty(Verdict verdict) {
        now += STEP;
        return gate.update(verdict, false, now);
    }
}
