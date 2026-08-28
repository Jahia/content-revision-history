package org.jahia.modules.revisionhistory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per page-and-language cap on capture attempts.
 *
 * <p>Capture is expensive -- a full guest render of a page plus a JCR write -- so it must
 * never be drivable faster than a human editor can publish, whatever ends up calling it.
 *
 * <p>Two independent limits, because they stop different things:
 * <ul>
 *   <li>a minimum interval, which collapses the burst of publication events a single editor
 *       action can produce;</li>
 *   <li>a rolling-window count, which bounds total work per page over time.</li>
 * </ul>
 *
 * <p>State is JVM-local and bounded by an LRU. In a cluster each node limits its own share,
 * which is the conservative direction: the cap can only be exceeded by a factor of the cluster
 * size, and repository growth is separately bounded by
 * {@link RevisionHistoryConstants#MAX_SNAPSHOTS_PER_PAGE_LANGUAGE}.
 */
final class CaptureRateLimiter {

    private static final int MAX_TRACKED_KEYS = 2_000;

    private final long minIntervalMillis;
    private final int maxPerWindow;
    private final long windowMillis;

    /** Access-ordered LRU; eldest entry evicted once the tracked-key budget is exceeded. */
    private final Map<String, Deque<Long>> attempts =
            new LinkedHashMap<String, Deque<Long>>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Deque<Long>> eldest) {
                    return size() > MAX_TRACKED_KEYS;
                }
            };

    CaptureRateLimiter(long minIntervalMillis, int maxPerWindow, long windowMillis) {
        this.minIntervalMillis = minIntervalMillis;
        this.maxPerWindow = maxPerWindow;
        this.windowMillis = windowMillis;
    }

    /**
     * Records an attempt if it is allowed.
     *
     * @return true when the caller may proceed with a capture
     */
    synchronized boolean tryAcquire(String pageUuid, String language, long nowMillis) {
        String key = pageUuid + '/' + language;
        Deque<Long> history = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());

        while (!history.isEmpty() && nowMillis - history.peekFirst() > windowMillis) {
            history.pollFirst();
        }
        Long last = history.peekLast();
        if (last != null && nowMillis - last < minIntervalMillis) {
            return false;
        }
        if (history.size() >= maxPerWindow) {
            return false;
        }
        history.addLast(nowMillis);
        return true;
    }
}
