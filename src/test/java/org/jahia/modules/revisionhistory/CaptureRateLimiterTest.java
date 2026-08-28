package org.jahia.modules.revisionhistory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CaptureRateLimiter} is the module's only defence against capture being driven faster
 * than a human editor can publish. All timestamps below are synthetic ({@code nowMillis} is a
 * parameter of {@link CaptureRateLimiter#tryAcquire}), so no wall-clock or sleep is needed.
 */
class CaptureRateLimiterTest {

    private static final String PAGE = "11111111-1111-1111-1111-111111111111";
    private static final String LANG = "en";

    @Test
    @DisplayName("first attempt for a key is always allowed")
    void firstAttemptIsAllowed() {
        // Arrange
        CaptureRateLimiter limiter = new CaptureRateLimiter(1_000L, 60, 3_600_000L);

        // Act
        boolean allowed = limiter.tryAcquire(PAGE, LANG, 0L);

        // Assert
        assertTrue(allowed, "an empty history must never refuse the first attempt");
    }

    @Test
    @DisplayName("refuses a second attempt inside the minimum interval")
    void refusesInsideMinimumInterval() {
        // Arrange -- a 1s minimum interval collapses a burst of near-simultaneous events
        CaptureRateLimiter limiter = new CaptureRateLimiter(1_000L, 60, 3_600_000L);
        assertTrue(limiter.tryAcquire(PAGE, LANG, 0L));

        // Act -- 500ms later, still inside the 1s minimum interval
        boolean secondAttempt = limiter.tryAcquire(PAGE, LANG, 500L);

        // Assert
        assertFalse(secondAttempt, "an attempt inside minIntervalMillis must be refused");
    }

    @Test
    @DisplayName("allows a second attempt once the minimum interval has elapsed")
    void allowsAfterMinimumIntervalElapses() {
        // Arrange
        CaptureRateLimiter limiter = new CaptureRateLimiter(1_000L, 60, 3_600_000L);
        assertTrue(limiter.tryAcquire(PAGE, LANG, 0L));

        // Act -- exactly at the boundary: the guard is "< minIntervalMillis", so 1000 must pass
        boolean secondAttempt = limiter.tryAcquire(PAGE, LANG, 1_000L);

        // Assert
        assertTrue(secondAttempt, "an attempt at exactly minIntervalMillis must be allowed");
    }

    @Test
    @DisplayName("refuses once maxPerWindow attempts have been recorded inside the window")
    void refusesOnceWindowCapReached() {
        // Arrange -- a tiny window cap (2) and a minimum interval short enough not to interfere
        CaptureRateLimiter limiter = new CaptureRateLimiter(0L, 2, 3_600_000L);
        assertTrue(limiter.tryAcquire(PAGE, LANG, 0L));
        assertTrue(limiter.tryAcquire(PAGE, LANG, 1L));

        // Act -- a third attempt, still well inside the one-hour window
        boolean thirdAttempt = limiter.tryAcquire(PAGE, LANG, 2L);

        // Assert
        assertFalse(thirdAttempt, "a third attempt must be refused once maxPerWindow is reached");
    }

    @Test
    @DisplayName("the window slides: capacity returns once old entries fall out of the window")
    void windowSlidesAndFreesCapacity() {
        // Arrange -- cap of 1 per a 1000ms window
        CaptureRateLimiter limiter = new CaptureRateLimiter(0L, 1, 1_000L);
        assertTrue(limiter.tryAcquire(PAGE, LANG, 0L));
        // Still inside the window: refused because the cap of 1 is already used
        assertFalse(limiter.tryAcquire(PAGE, LANG, 999L));

        // Act -- past the window (nowMillis - firstEntry > windowMillis evicts the first entry)
        boolean afterWindow = limiter.tryAcquire(PAGE, LANG, 1_001L);

        // Assert
        assertTrue(afterWindow, "capacity must return once the earlier entry ages out of the window");
    }

    @Test
    @DisplayName("distinct pages and languages are tracked independently")
    void distinctKeysAreIndependent() {
        // Arrange -- exhaust the single slot available for PAGE/en
        CaptureRateLimiter limiter = new CaptureRateLimiter(0L, 1, 3_600_000L);
        assertTrue(limiter.tryAcquire(PAGE, "en", 0L));
        assertFalse(limiter.tryAcquire(PAGE, "en", 1L));

        // Act -- same page, different language; must not share the throttled key's history
        boolean differentLanguage = limiter.tryAcquire(PAGE, "fr", 1L);

        // Assert
        assertTrue(differentLanguage, "the key is pageUuid+language; a different language must be unaffected");
    }

    @Test
    @DisplayName("LRU eviction past MAX_TRACKED_KEYS frees a previously-throttled key")
    void lruEvictsThrottledKeyPastTrackedKeyBudget() {
        // Arrange -- MAX_TRACKED_KEYS is a private constant (2000); rediscover it by pushing keys
        // until the throttled key becomes acquirable again, then prove that number is bounded
        // (i.e. eviction actually happened, not that the limiter forgot how to throttle).
        CaptureRateLimiter limiter = new CaptureRateLimiter(0L, 1, 3_600_000L);
        String throttledKeyPage = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        assertTrue(limiter.tryAcquire(throttledKeyPage, LANG, 0L));
        assertFalse(limiter.tryAcquire(throttledKeyPage, LANG, 1L),
                "sanity check: the key must be throttled before we try to evict it");

        // Act -- touch many distinct keys so the LRU map evicts the eldest entries. The throttled
        // key was used (accessed) at time 1, so pushing far more than MAX_TRACKED_KEYS (2000)
        // brand-new keys through the map is guaranteed to evict it under a bounded LRU.
        final int keysToPush = 2_500;
        for (int i = 0; i < keysToPush; i++) {
            limiter.tryAcquire("distinct-page-" + i, LANG, 2L + i);
        }
        boolean afterEviction = limiter.tryAcquire(throttledKeyPage, LANG, keysToPush + 100L);

        // Assert -- the key was evicted from the LRU (its history was forgotten), so it is
        // treated as brand new and allowed again, proving MAX_TRACKED_KEYS bounds memory.
        assertTrue(afterEviction,
                "a key pushed out by the LRU must be re-acquirable as if it were new");
    }

    @Test
    @DisplayName("a re-touched key survives LRU eviction while an equally-old, untouched key does not")
    void recentlyAccessedKeySurvivesEvictionOverAnUntouchedPeer() {
        // Arrange -- two keys inserted back-to-back, so they start out equally "old". Only
        // access-order (not insertion-order) explains a different outcome for the two.
        CaptureRateLimiter limiter = new CaptureRateLimiter(0L, 1, 3_600_000L);
        String untouchedPage = "cccccccc-cccc-cccc-cccc-cccccccccccc";
        String touchedPage = "dddddddd-dddd-dddd-dddd-dddddddddddd";
        assertTrue(limiter.tryAcquire(untouchedPage, LANG, 0L));
        assertTrue(limiter.tryAcquire(touchedPage, LANG, 1L));
        // Order is now [untouchedPage (eldest), touchedPage]

        // Act -- push 1000 filler keys, then re-touch touchedPage (moving it to the MRU end,
        // ahead of every filler key so far), then push just enough further filler keys to force
        // exactly one eviction. The entry evicted must be whichever is currently eldest.
        for (int i = 0; i < 1_000; i++) {
            limiter.tryAcquire("filler-page-" + i, LANG, 10L + i);
        }
        // Re-touch: still refused (maxPerWindow=1, well inside the window), but this access
        // moves touchedPage to the most-recently-used end, ahead of untouchedPage and every
        // filler pushed so far.
        assertFalse(limiter.tryAcquire(touchedPage, LANG, 2_000L));
        // Map currently holds 1002 entries (untouchedPage, touchedPage, filler-page-0..999) and
        // MAX_TRACKED_KEYS is 2000, so 999 more distinct insertions bring size to exactly 2001,
        // triggering precisely one eviction -- of whatever is eldest at that moment.
        for (int i = 1_000; i < 1_999; i++) {
            limiter.tryAcquire("filler-page-" + i, LANG, 3_000L + i);
        }

        // Assert -- untouchedPage was never re-accessed, so it was still the eldest entry and
        // is the one evicted: its history is gone and it is acquirable again.
        boolean untouchedAfterEviction = limiter.tryAcquire(untouchedPage, LANG, 10_000L);
        assertTrue(untouchedAfterEviction,
                "the untouched key must be the one evicted, since it was never moved off the LRU front");

        // touchedPage was moved to the MRU end by the re-touch, so it must have survived the
        // single eviction above and still be throttled.
        boolean touchedAfterEviction = limiter.tryAcquire(touchedPage, LANG, 10_001L);
        assertFalse(touchedAfterEviction,
                "a re-touched key must survive LRU eviction that removes an equally-old, untouched peer");
    }
}
