package org.jahia.modules.revisionhistory;

import org.jahia.api.Constants;
import org.jahia.registries.ServicesRegistry;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPublicationService;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.PublicationEvent;
import org.jahia.services.content.PublicationEventListener;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.scheduler.BackgroundJob;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.ItemNotFoundException;
import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.AbstractMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.*;

/**
 * The trigger for revision capture: publication, not traffic.
 *
 * <p>Capture used to hang off a render filter, which could not deliver the guarantee the
 * feature is sold on. Jahia's HTML output cache short-circuits the render chain before a
 * priority-98 filter is reached, so whether a page version was ever recorded depended on cache
 * state and on somebody happening to visit -- a page or a language nobody browsed simply had
 * no history, and a gap in the record was indistinguishable from "the content did not change",
 * which is the one distinction the whole feature rests on. It was also reachable by anyone:
 * an unauthenticated request with a random cache-busting query parameter forced a full second
 * render plus a repository write, without limit.
 *
 * <p>Publication is the correct trigger because it is the moment a change becomes publicly
 * visible, it happens exactly once per change, it is already authenticated and authorised, and
 * it carries per-language granularity in
 * {@link PublicationEvent.ContentPublicationInfo#getPublicationLanguages()}.
 *
 * <p>This listener does no rendering and no writing. It resolves which pages a publication
 * touched and hands them to {@link SnapshotCaptureJob}, so publication latency is unaffected.
 */
public class PublicationSnapshotListener implements PublicationEventListener {

    private static final Logger logger = LoggerFactory.getLogger(PublicationSnapshotListener.class);

    /** Registered on module start; Jahia calls this back for every completed publication. */
    /**
     * Jobs this listener has enqueued but that may not have fired yet, as (name, group) pairs.
     *
     * <p>Jobs are scheduled in Quartz's RAM store, whose lifecycle is independent of this
     * bundle. If the module is stopped between a publication and the job firing, Quartz would
     * still try to run {@link SnapshotCaptureJob} from a stopped bundle, and whatever failed
     * there would never reach the durable status recorder -- leaving the publication with no
     * trace at all, which is exactly the silent gap this module exists to prevent.
     */
    private final Map<Map.Entry<String, String>, Boolean> scheduledJobs = new ConcurrentHashMap<>();

    private static volatile PublicationSnapshotListener INSTANCE;

    public void start() {
        INSTANCE = this;
        JCRPublicationService.getInstance().registerListener(this);
        logger.info("Content revision history: listening for publication events");
    }

    public void stop() {
        // Order matters: cancel BEFORE clearing INSTANCE. If INSTANCE were nulled first,
        // a job entering executeJahiaJob in the gap would find no instance, fail to
        // deregister itself, and then be passed to deleteJob while already running --
        // contradicting the guarantee that a running job is left alone.
        JCRPublicationService.getInstance().unregisterListener(this);
        cancelOutstandingJobs();
        INSTANCE = null;
        logger.info("Content revision history: stopped listening for publication events");
    }

    /**
     * Deletes still-pending capture jobs so Quartz cannot execute them against a stopped
     * bundle. A job already executing is left alone: its classes are loaded and it records its
     * own outcome durably, so letting it finish is safer than interrupting it mid-write.
     */
    private void cancelOutstandingJobs() {
        if (scheduledJobs.isEmpty()) {
            return;
        }
        int cancelled = 0;
        try {
            Scheduler scheduler = ServicesRegistry.getInstance().getSchedulerService().getRAMScheduler();
            for (Map.Entry<String, String> job : scheduledJobs.keySet()) {
                try {
                    if (scheduler.deleteJob(job.getKey(), job.getValue())) {
                        cancelled++;
                    }
                } catch (Exception e) {
                    logger.warn("Could not cancel pending capture job {}", job.getKey(), e);
                }
            }
        } catch (Exception e) {
            logger.warn("Could not reach the scheduler to cancel pending capture jobs", e);
        } finally {
            scheduledJobs.clear();
        }
        if (cancelled > 0) {
            logger.info("Cancelled {} pending revision snapshot capture job(s) on module stop", cancelled);
        }
    }

    /** Called by the job when it starts, so a completed job is not cancelled later. */
    static void jobStarted(String name, String group) {
        PublicationSnapshotListener active = INSTANCE;
        if (active != null) {
            active.scheduledJobs.remove(new AbstractMap.SimpleEntry<>(name, group));
        }
    }

    @Override
    public void onPublicationCompleted(PublicationEvent event) {
        try {
            if (!isPublicationToLive(event)) {
                return;
            }
            Map<String, Set<String>> languagesByPage = resolveRevisionedPages(event);
            if (languagesByPage.isEmpty()) {
                return;
            }
            schedule(languagesByPage, event.getTimestamp());
        } catch (RepositoryException | RuntimeException e) {
            // A broken revision history must never break publication.
            logger.error("Could not schedule revision snapshot capture for a publication event", e);
        }
    }

    private boolean isPublicationToLive(PublicationEvent event) {
        try {
            JCRSessionWrapper destination = event.getDestinationSession();
            return destination == null
                    || Constants.LIVE_WORKSPACE.equals(destination.getWorkspace().getName());
        } catch (RuntimeException e) {
            logger.debug("Could not determine the publication destination workspace", e);
            return true;
        }
    }

    /**
     * Maps every published node onto the {@code jmix:publiclyRevisioned} page that owns it.
     *
     * <p>Publishing a single paragraph must snapshot its page, so the owning page has to be
     * resolved rather than assumed from the node type: only the outermost published node of a
     * page publication is itself a {@code jnt:page}.
     */
    private Map<String, Set<String>> resolveRevisionedPages(PublicationEvent event)
            throws RepositoryException {
        Collection<PublicationEvent.ContentPublicationInfo> infos = event.getContentPublicationInfos();
        if (infos == null || infos.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, WORKSPACE, null, (JCRCallback<Map<String, Set<String>>>) session -> {
                    Map<String, Set<String>> languagesByPage = new LinkedHashMap<>();
                    Map<String, String> memo = new HashMap<>();
                    int inspected = 0;
                    for (PublicationEvent.ContentPublicationInfo info : infos) {
                        if (++inspected > MAX_PUBLICATION_INFOS_INSPECTED
                                || languagesByPage.size() >= MAX_PAGES_PER_PUBLICATION) {
                            logger.warn("Publication event too large; inspected {} nodes and stopped",
                                    inspected - 1);
                            break;
                        }
                        String pageUuid = owningRevisionedPage(session, info, memo);
                        if (pageUuid == null) {
                            continue;
                        }
                        Set<String> languages = languagesByPage
                                .computeIfAbsent(pageUuid, k -> new LinkedHashSet<>());
                        Collection<String> published = info.getPublicationLanguages();
                        if (published != null) {
                            languages.addAll(published);
                        }
                    }
                    fillInMissingLanguages(session, languagesByPage);
                    return languagesByPage;
                });
    }

    /**
     * A published node carrying no i18n property reports no publication language. Falling back
     * to the site's languages costs an extra render or two; guessing "none" would cost a hole
     * in the record, and holes are the failure mode this design exists to remove.
     */
    private void fillInMissingLanguages(JCRSessionWrapper session,
                                        Map<String, Set<String>> languagesByPage) {
        for (Map.Entry<String, Set<String>> entry : languagesByPage.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                continue;
            }
            try {
                JCRSiteNode site = session.getNodeByIdentifier(entry.getKey()).getResolveSite();
                Set<String> languages = site == null ? null : site.getActiveLiveLanguages();
                if (languages != null) {
                    entry.getValue().addAll(languages);
                }
            } catch (RepositoryException e) {
                logger.warn("Could not determine languages for page {}", entry.getKey(), e);
            }
            if (entry.getValue().isEmpty()) {
                // Every later stage drops a page with no languages, and each drop used to be
                // silent, so the publication ended up indistinguishable from "nothing changed".
                // There is no durable status to write here: the status is keyed BY language, and
                // the language is precisely what could not be resolved. Loud is the best
                // available answer.
                logger.error("Page {} published with no resolvable language (site unreadable, or"
                        + " no active live language); no snapshot will be captured for it",
                        entry.getKey());
            }
        }
    }

    /**
     * @param memo path to owning revisioned-page uuid ({@code ""} for none). Only non-page
     *             ancestors are memoised: a memo entry on a page path would shadow sub-pages,
     *             which are pages in their own right and may opt in independently.
     * @return the uuid of the nearest enclosing revisioned page, or null
     */
    private String owningRevisionedPage(JCRSessionWrapper session,
                                        PublicationEvent.ContentPublicationInfo info,
                                        Map<String, String> memo) {
        String remembered = lookUpMemo(info.getNodePath(), memo);
        if (remembered != null) {
            return remembered.isEmpty() ? null : remembered;
        }
        try {
            JCRNodeWrapper current = session.getNodeByIdentifier(info.getNodeIdentifier());
            List<String> visitedContentPaths = new ArrayList<>();
            while (current != null && current.getPath().startsWith("/sites/")) {
                if (current.isNodeType(PAGE_TYPE)) {
                    String result = current.isNodeType(REVISIONED_PAGE_MIXIN)
                            ? current.getIdentifier() : "";
                    memoise(memo, visitedContentPaths, result);
                    return result.isEmpty() ? null : result;
                }
                visitedContentPaths.add(current.getPath());
                current = current.getParent();
            }
            memoise(memo, visitedContentPaths, "");
            return null;
        } catch (ItemNotFoundException | javax.jcr.PathNotFoundException gone) {
            return null;
        } catch (RepositoryException e) {
            logger.warn("Could not resolve the page owning {}", info.getNodePath(), e);
            return null;
        }
    }

    private String lookUpMemo(String path, Map<String, String> memo) {
        String candidate = path;
        while (candidate != null && candidate.length() > 1) {
            String hit = memo.get(candidate);
            if (hit != null) {
                return hit;
            }
            int slash = candidate.lastIndexOf('/');
            candidate = slash <= 0 ? null : candidate.substring(0, slash);
        }
        return null;
    }

    private void memoise(Map<String, String> memo, List<String> paths, String result) {
        for (String path : paths) {
            memo.put(path, result);
        }
    }

    private void schedule(Map<String, Set<String>> languagesByPage, long publicationTimestamp) {
        List<String> pages = new ArrayList<>(languagesByPage.keySet());
        List<Set<String>> languages = new ArrayList<>();
        for (String page : pages) {
            languages.add(languagesByPage.get(page));
        }
        String payload = SnapshotCaptureJob.encode(pages, languages);
        if (payload.isEmpty()) {
            logger.error("Publication touched {} revisioned page(s) but none could be encoded for"
                    + " capture; no snapshot job was scheduled", pages.size());
            return;
        }
        try {
            JobDetail detail = BackgroundJob.createJahiaJob(
                    "Content revision history snapshot capture", SnapshotCaptureJob.class);
            detail.getJobDataMap().put(SnapshotCaptureJob.JOB_PAGES, payload);
            detail.getJobDataMap().put(SnapshotCaptureJob.JOB_PUBLICATION_TIMESTAMP,
                    publicationTimestamp);
            // useRAM = true: the work is transient, and re-running it hours later against
            // content that has moved on would record the wrong thing, not a missing thing.
            ServicesRegistry.getInstance().getSchedulerService().scheduleJobNow(detail, true);
            scheduledJobs.put(new AbstractMap.SimpleEntry<>(detail.getName(), detail.getGroup()), Boolean.TRUE);
            logger.info("Scheduled revision snapshot capture for {} page(s)", pages.size());
        } catch (Exception e) {
            logger.error("Could not schedule the revision snapshot capture job for {}", payload, e);
            recordSchedulingFailure(languagesByPage, e);
        }
    }

    /**
     * Writes a durable FAILED status for every page/language the job would have captured.
     *
     * <p>Unlike the no-language case above, the languages are known here, so the failure can be
     * recorded where an operator will actually look. Without this the folder kept whatever the
     * previous publication left on it, and the newest publication looked already captured when
     * it had never been attempted.
     */
    private void recordSchedulingFailure(Map<String, Set<String>> languagesByPage, Exception cause) {
        RevisionSnapshotService service = new RevisionSnapshotService();
        String message = "The capture job could not be scheduled ("
                + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ")";
        for (Map.Entry<String, Set<String>> entry : languagesByPage.entrySet()) {
            for (String language : entry.getValue()) {
                try {
                    service.recordStatus(siteKeyOf(entry.getKey()), entry.getKey(), language,
                            CaptureStatus.FAILED, message);
                } catch (RuntimeException alsoFailed) {
                    // Recording the failure must never mask the failure being recorded.
                    // recordStatus declares no checked exception because it swallows its own
                    // repository errors internally -- which is a separate reported finding, not
                    // one this batch touches.
                    logger.error("Could not record the scheduling failure for page {} [{}]",
                            entry.getKey(), language, alsoFailed);
                }
            }
        }
    }

    /** @return the site key owning the page, or "unknown" when it cannot be resolved */
    private String siteKeyOf(String pageUuid) {
        try {
            return JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, WORKSPACE, null,
                    (JCRCallback<String>) session -> {
                        JCRNodeWrapper page = session.getNodeByIdentifier(pageUuid);
                        JCRSiteNode site = page.getResolveSite();
                        return site == null ? "unknown" : site.getSiteKey();
                    });
        } catch (RepositoryException | RuntimeException unresolvable) {
            logger.warn("Could not resolve the site owning page {}", pageUuid, unresolvable);
            return "unknown";
        }
    }
}
