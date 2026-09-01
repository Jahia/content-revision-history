package org.jahia.modules.revisionhistory;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.render.filter.cache.ModuleCacheProvider;
import org.jahia.services.scheduler.BackgroundJob;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.ItemNotFoundException;
import javax.jcr.RepositoryException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.*;

/**
 * Does the actual capture work, off the publication thread and off the request path.
 *
 * <p>Scheduled by {@link PublicationSnapshotListener} through
 * {@code SchedulerService.scheduleJobNow(detail, true)} -- RAM scheduler, because this work is
 * transient: if the node dies mid-capture the right answer is not to replay it later against
 * content that has since moved on, it is to let the next publication capture the current
 * state. What must not be lost is the <em>knowledge</em> that a capture was owed, and that is
 * durable: a missing snapshot always leaves a status on the per-language folder.
 *
 * <p>Publication latency is untouched: the listener only enqueues.
 *
 * <p><b>Known and accepted:</b> capture is asynchronous, so a snapshot holds the live page as
 * it stood when the guest render ran, while its name and {@code crh:snapshotDate} carry the
 * instant of the publication that triggered it. Two publications inside the same second can
 * therefore collapse: the first job may observe the second publication's text. That is
 * bounded (the rate limiter refuses the follow-up, and the refusal is recorded on the folder,
 * so the record says "a capture was skipped" rather than quietly showing one fewer revision)
 * and it needs a publication rate no human editor reaches. The alternative -- capturing
 * synchronously on the publication thread -- reintroduces exactly the coupling this design
 * removes.
 */
public class SnapshotCaptureJob extends BackgroundJob {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotCaptureJob.class);

    /** Encoded as {@code uuid:lang[,lang];uuid:lang}. */
    static final String JOB_PAGES = "crh.pages";
    static final String JOB_PUBLICATION_TIMESTAMP = "crh.publicationTimestamp";

    private static final CaptureRateLimiter LIMITER = new CaptureRateLimiter(
            MIN_CAPTURE_INTERVAL_MILLIS, MAX_CAPTURES_PER_WINDOW, RATE_WINDOW_MILLIS);

    /** Lazy: the constructor probes JMX for the container port, which must not run at class load. */
    private static final class FetcherHolder {
        private static final GuestMarkdownFetcher INSTANCE = new GuestMarkdownFetcher();
    }

    private final RevisionSnapshotService snapshotService = new RevisionSnapshotService();
    private final RevisionEntryBinder entryBinder = new RevisionEntryBinder();

    @Override
    public void executeJahiaJob(JobExecutionContext context) {
        // Deregister immediately: once we are running, this job must not be cancelled by
        // a later module stop, and it records its own outcome durably from here on.
        PublicationSnapshotListener.jobStarted(context.getJobDetail().getName(),
                context.getJobDetail().getGroup());
        JobDataMap data = context.getJobDetail().getJobDataMap();
        String encoded = data.getString(JOB_PAGES);
        long timestamp = data.containsKey(JOB_PUBLICATION_TIMESTAMP)
                ? data.getLong(JOB_PUBLICATION_TIMESTAMP) : System.currentTimeMillis();
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        Instant captureInstant = Instant.ofEpochMilli(timestamp);
        for (String entry : encoded.split(";")) {
            captureOnePageSafely(entry, captureInstant, timestamp);
        }
    }

    private void captureOnePageSafely(String entry, Instant captureInstant, long cacheBuster) {
        int separator = entry.indexOf(':');
        if (separator <= 0) {
            return;
        }
        String pageUuid = entry.substring(0, separator);
        String[] languages = entry.substring(separator + 1).split(",");
        try {
            PageRef page = resolvePage(pageUuid);
            if (page == null) {
                logger.info("Page {} is gone or no longer publicly revisioned; nothing to capture",
                        pageUuid);
                return;
            }
            for (String language : languages) {
                capture(page, language.trim(), captureInstant, cacheBuster);
            }
        } catch (RepositoryException | RuntimeException e) {
            logger.error("Revision snapshot capture failed for page {}", pageUuid, e);
            for (String language : languages) {
                snapshotService.recordStatus(siteKeyOrUnknown(pageUuid), pageUuid, language.trim(),
                        CaptureStatus.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    private void capture(PageRef page, String language, Instant captureInstant, long cacheBuster) {
        // Checked before the rate limiter: a disabled site must not consume a site's allowance, and
        // must not be reported as rate limited when it was simply switched off.
        if (!SiteSettingsRegistry.settingsFor(page.siteKey).isCaptureEnabled()) {
            logger.info("Capture is disabled for site {}; page {} [{}] is not captured",
                    page.siteKey, page.uuid, language);
            snapshotService.recordStatus(page.siteKey, page.uuid, language,
                    CaptureStatus.DISABLED, "Capture is disabled for this site");
            return;
        }

        if (!LIMITER.tryAcquire(page.uuid, language, System.currentTimeMillis())) {
            logger.warn("Capture for page {} [{}] refused by the rate limiter", page.uuid, language);
            snapshotService.recordStatus(page.siteKey, page.uuid, language,
                    CaptureStatus.RATE_LIMITED, "Too many capture attempts for this page");
            return;
        }

        if (!flushFragmentCache(page.path)) {
            // The flush is the whole reason the ordering is deterministic. Without it the fetch
            // below can read a pre-publication fragment, hash identical to the previous
            // snapshot, and durably record UNCHANGED for a change that did happen. A wrong
            // record is worse than a visible gap, and unlike a gap it cannot be spotted later,
            // so refuse and say so; re-publishing the page puts it right.
            snapshotService.recordStatus(page.siteKey, page.uuid, language, CaptureStatus.FAILED,
                    "Could not flush the fragment cache before capture; refused rather than risk"
                    + " recording a stale render as UNCHANGED");
            return;
        }
        GuestMarkdownFetcher.Fetched fetched =
                FetcherHolder.INSTANCE.fetch(page.path, language, cacheBuster, page.siteKey);
        if (!fetched.isOk()) {
            logger.warn("No snapshot for page {} [{}]: {} ({})", page.path, language,
                    fetched.status, fetched.message);
            snapshotService.recordStatus(page.siteKey, page.uuid, language,
                    fetched.status, fetched.message);
            return;
        }

        try {
            CaptureStatus status = snapshotService.captureIfChanged(page.siteKey, page.uuid,
                    // The locale-aware overload exists precisely so that languages which do
                    // not start a sentence with a Latin capital still get one-sentence-per-line
                    // output. Calling the locale-less one here left it inert on the only
                    // production path, so a one-word edit to a CJK page produced a
                    // whole-paragraph diff -- the exact thing semanticLineBreaks prevents.
                    language, MarkdownNormalizer.normalize(fetched.body,
                            MarkdownNormalizer.localeFor(language)), captureInstant,
                    principalOfRecord(page.siteKey), fetched.sourceUrl);
            logger.info("Revision snapshot for {} [{}]: {}", page.path, language, status);
            // Only these two statuses mean the newest snapshot matches the live page, which is
            // the precondition for attaching an editor's revision entry to it. See
            // RevisionEntryBinder for why the other statuses must NOT bind.
            if (status == CaptureStatus.STORED || status == CaptureStatus.UNCHANGED) {
                entryBinder.bindNewEntries(page.siteKey, page.uuid, language);
            }
        } catch (RepositoryException | RuntimeException e) {
            logger.error("Storing the revision snapshot for {} [{}] failed", page.path, language, e);
            snapshotService.recordStatus(page.siteKey, page.uuid, language, CaptureStatus.FAILED,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Removes the page's cached fragments before rendering it.
     *
     * <p>The cache-busting query parameter only forces a miss on the page-level entry;
     * per-fragment entries are keyed on node path and template type, not on the URL.
     * Publication flushes them too, but that flush travels through JCR observation and is
     * therefore asynchronous -- a capture that raced it would snapshot the pre-publication
     * text and, because the hash would match the previous snapshot, silently record "no
     * change" for a change. Flushing here makes the ordering deterministic; it costs nothing
     * extra, since publication was about to flush exactly these entries anyway.
     */
    /**
     * The principal to stamp on the snapshot: the configured capture user, or {@code guest}.
     *
     * <p>This used to be the constant {@code guest} unconditionally, which was true while capture
     * could only ever be anonymous. Once a deployment configures a capture user it stops being
     * true, and a record that names the wrong principal is worse than one that names none:
     * {@code crh:capturedBy} is what tells a later reader whose view of the page the text
     * represents, and therefore who may safely be shown it.
     */
    /**
     * Whose view of the page the stored text represents, and therefore who may safely be shown it.
     *
     * <p>This has to agree with what {@link GuestMarkdownFetcher#authorizationFor} actually sent,
     * because the editor-facing description of the property says exactly that: "guest means anyone
     * could have read it, any other name means the snapshot may contain content the public cannot
     * see". It previously read only the module-wide principal, so it was wrong in both directions:
     *
     * <ul>
     *   <li>A site with its own {@code capture.user} and no global one fetched as that account and
     *       recorded {@code guest}, telling an editor that a snapshot full of restricted content
     *       was safe to show anybody.</li>
     *   <li>A configured {@code capture.user} whose secret did not resolve fetched ANONYMOUSLY --
     *       authorizationFor returns null and no header is sent -- yet recorded that account, so a
     *       plain guest render looked like privileged provenance.</li>
     * </ul>
     *
     * <p>So the decision is made from the resolved authorization, not from the configured name:
     * a name is recorded only when a credential for it actually went out.
     */
    private static String principalOfRecord(String siteKey) {
        SiteCaptureSettings site = SiteSettingsRegistry.settingsFor(siteKey);
        if (site.getAuthorization() != null) {
            String perSite = site.getCaptureUser();
            return perSite == null ? CAPTURE_PRINCIPAL : perSite;
        }
        if (CaptureIdentity.authorization() != null) {
            String configured = CaptureIdentity.principal();
            return configured == null ? CAPTURE_PRINCIPAL : configured;
        }
        // Nothing resolved anywhere: the render really was anonymous, whatever is configured.
        return CAPTURE_PRINCIPAL;
    }

    /** @return false when the cache could not be flushed, in which case do NOT capture */
    private boolean flushFragmentCache(String pagePath) {
        try {
            ModuleCacheProvider.getInstance().invalidate(pagePath, true);
            return true;
        } catch (RuntimeException e) {
            logger.error("Could not flush the fragment cache for {} before capture", pagePath, e);
            return false;
        }
    }

    /** @return the page, or null when it no longer exists or no longer opts in */
    private PageRef resolvePage(String pageUuid) throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, WORKSPACE, null, (JCRCallback<PageRef>) session -> {
                    try {
                        JCRNodeWrapper node = session.getNodeByIdentifier(pageUuid);
                        if (!node.isNodeType(REVISIONED_PAGE_MIXIN)) {
                            return null;
                        }
                        return new PageRef(pageUuid, node.getPath(),
                                node.getResolveSite().getSiteKey());
                    } catch (ItemNotFoundException gone) {
                        return null;
                    }
                });
    }

    private String siteKeyOrUnknown(String pageUuid) {
        try {
            PageRef page = resolvePage(pageUuid);
            return page == null ? "unknown" : page.siteKey;
        } catch (RepositoryException | RuntimeException e) {
            return "unknown";
        }
    }

    /** Immutable page coordinates resolved once per job. */
    private static final class PageRef {
        private final String uuid;
        private final String path;
        private final String siteKey;

        private PageRef(String uuid, String path, String siteKey) {
            this.uuid = uuid;
            this.path = path;
            this.siteKey = siteKey;
        }
    }

    /** Builds the {@code uuid:lang,lang;...} payload, capped so one event cannot enqueue the world. */
    static String encode(List<String> pageUuids, List<Set<String>> languagesPerPage) {
        StringBuilder encoded = new StringBuilder();
        int pages = Math.min(pageUuids.size(), MAX_PAGES_PER_PUBLICATION);
        if (pageUuids.size() > pages) {
            logger.error("Publication touched {} revisioned pages, over the cap of {}; the last"
                    + " {} will NOT be captured and carry no status of any kind",
                    pageUuids.size(), MAX_PAGES_PER_PUBLICATION, pageUuids.size() - pages);
        }
        for (int i = 0; i < pages; i++) {
            Set<String> languages = new LinkedHashSet<>(languagesPerPage.get(i));
            if (languages.isEmpty()) {
                // Dropping this silently made a page with no resolvable language look exactly
                // like a page that did not change.
                logger.error("No language could be resolved for revisioned page {}; it will NOT"
                        + " be captured for this publication", pageUuids.get(i));
                continue;
            }
            if (encoded.length() > 0) {
                encoded.append(';');
            }
            encoded.append(pageUuids.get(i)).append(':');
            List<String> ordered = new ArrayList<>(languages);
            for (int l = 0; l < ordered.size(); l++) {
                if (l > 0) {
                    encoded.append(',');
                }
                encoded.append(ordered.get(l));
            }
        }
        return encoded.toString();
    }
}
