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
import java.util.Map;
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
    public void start() {
        JCRPublicationService.getInstance().registerListener(this);
        logger.info("Content revision history: listening for publication events");
    }

    public void stop() {
        JCRPublicationService.getInstance().unregisterListener(this);
        logger.info("Content revision history: stopped listening for publication events");
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
        return JCRTemplate.getInstance().doExecuteWithSystemSession(null, WORKSPACE,
                (JCRCallback<Map<String, Set<String>>>) session -> {
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
            logger.info("Scheduled revision snapshot capture for {} page(s)", pages.size());
        } catch (Exception e) {
            logger.error("Could not schedule the revision snapshot capture job for {}", payload, e);
        }
    }
}
