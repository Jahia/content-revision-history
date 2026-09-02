package org.jahia.modules.revisionhistory;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.jahia.services.content.nodetypes.initializers.ChoiceListInitializer;
import org.jahia.services.content.nodetypes.initializers.ChoiceListInitializerService;
import org.jahia.services.content.nodetypes.initializers.ChoiceListValue;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Binary;
import javax.jcr.RepositoryException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.PROP_MARKDOWN;
import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.PROP_SNAPSHOT_DATE;
import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.ROOT_FOLDER_NAME;
import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.SNAPSHOT_TYPE;
import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.WORKSPACE;

/**
 * Lets an editor choose which snapshot a {@code crh:revisionEntry} describes.
 *
 * <h2>Why this exists</h2>
 * {@link RevisionEntryBinder} binds every unbound entry on a page to the ONE current snapshot.
 * For the normal editorial rhythm that is exactly right: a publication produces one entry and one
 * snapshot, and they belong together.
 *
 * <p>Backfill breaks that pairing completely. Reconstructing history produces many snapshots at
 * once, and the entries describing them can only be written afterwards, so every one of them would
 * bind to the newest snapshot and every comparison would report that nothing changed. Leaving
 * {@code crh:snapshotRef} empty keeps the automatic behaviour; setting it pins the entry to the
 * snapshot the editor actually means.
 *
 * <h2>Registration</h2>
 * {@link ChoiceListInitializerService} is a plain bean with a {@code Map} and a setter: it has no
 * OSGi service tracker and no add/remove, so the platform populates it only from Spring contexts.
 * This module has no Spring context by design, so the component puts itself into that live map on
 * activation and takes itself out again on deactivation. The map is a mutable {@code LinkedHashMap}
 * (verified on 8.2.3.2), and {@code remove(key, value)} is used so a newer registration under the
 * same key is never clobbered by an older bundle shutting down.
 *
 * <h2>Which session reads the snapshots</h2>
 * A system session reads them, and the reason is no longer the one this gave. The tree's ACL
 * inheritance is not broken any more: {@code RevisionSnapshotService.restoreInheritance} repairs
 * the pre-1.4 lockdown on every capture, so snapshots follow the permissions of the site's content
 * folder and an editor with read there CAN read them. Two reasons remain. An instance upgraded but
 * not yet republished still carries the old lockdown until its next capture, and reading through
 * the viewer's session would silently return an empty option list there. And the gate below is
 * needed regardless of either: a snapshot captured by a configured principal can hold text the
 * viewer may not see on the live page, so the current user must be able to read the PAGE before
 * any snapshot of it is described to them.
 */
@Component(service = SnapshotChoiceListInitializer.class, immediate = true)
public class SnapshotChoiceListInitializer implements ChoiceListInitializer {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotChoiceListInitializer.class);

    /** Key the CND refers to: {@code choicelist[crhSnapshots]}. */
    static final String KEY = "crhSnapshots";

    /**
     * Newest snapshots offered. The module default for snapshots per page and language is 500, and
     * a 500-option dropdown is unusable long before it is slow. Each option also costs one binary
     * read, so this is the bound on that too.
     *
     * <p>500 is a DEFAULT, not a ceiling: a site may configure more, and nothing caps it from
     * above. This constant is therefore the only thing actually bounding this dropdown -- which
     * also means a site configured above 100 has older snapshots that cannot be pinned from the
     * picker at all.
     */
    static final int MAX_CHOICES = 100;

    /** Characters of the first line shown beside the date. */
    static final int EXCERPT_CHARS = 60;

    /**
     * Bytes read from the markdown to find that first line. Deliberately small: the excerpt is a
     * hint, and reading a megabyte per option to render a dropdown is not worth it.
     */
    private static final int EXCERPT_BYTES = 512;

    private static final String SEPARATOR = " — ";

    @Activate
    void activate() {
        try {
            ChoiceListInitializerService.getInstance().getInitializers().put(KEY, this);
            logger.info("Registered the '{}' choice list initializer", KEY);
        } catch (RuntimeException e) {
            // A missing picker degrades the edit form; it must never stop the bundle starting.
            logger.error("Could not register the '{}' choice list initializer;"
                    + " revision entries will fall back to automatic binding", KEY, e);
        }
    }

    @Deactivate
    void deactivate() {
        try {
            ChoiceListInitializerService.getInstance().getInitializers().remove(KEY, this);
        } catch (RuntimeException e) {
            logger.warn("Could not unregister the '{}' choice list initializer", KEY, e);
        }
    }

    @Override
    public List<ChoiceListValue> getChoiceListValues(ExtendedPropertyDefinition definition,
                                                     String param,
                                                     List<ChoiceListValue> values,
                                                     Locale locale,
                                                     Map<String, Object> context) {
        if (context == null) {
            return Collections.emptyList();
        }
        JCRNodeWrapper start = firstNodeIn(context, "contextNode", "contextParent");
        if (start == null) {
            return Collections.emptyList();
        }
        try {
            JCRNodeWrapper page = pageOf(start);
            if (page == null || !viewerMayReadPage(page.getIdentifier())) {
                return Collections.emptyList();
            }
            return choicesFor(page.getResolveSite().getSiteKey(), page.getIdentifier(),
                    languageOf(locale, start), locale);
        } catch (RepositoryException | RuntimeException e) {
            // An initializer that throws blanks the whole edit engine field.
            logger.warn("Could not list snapshots for the revision entry picker", e);
            return Collections.emptyList();
        }
    }

    // ------------------------------------------------------------------ the pure half

    /**
     * What the editor reads: the capture instant, then the line that distinguishes this snapshot.
     *
     * <p>To the SECOND, not the minute. Captures land milliseconds apart during one publication,
     * so minute precision printed several options identically and left the editor choosing blind
     * between them.
     */
    static String label(Calendar capturedAt, String excerpt, Locale locale) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                locale == null ? Locale.ENGLISH : locale);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        String when = format.format(capturedAt.getTime());
        return excerpt == null || excerpt.isEmpty() ? when : when + SEPARATOR + excerpt;
    }

    /**
     * The first line of the snapshot that carries anything, trimmed to {@code maxChars}.
     *
     * <p>Control characters are removed rather than escaped. This text was captured from a
     * rendered page, so it is not trusted: a newline or a NUL reaching an option label would
     * corrupt the list the editor is choosing from.
     */
    /**
     * The line that tells this snapshot apart from the others, trimmed to {@code maxChars}.
     *
     * <p>Every snapshot of a page opens with the same line, because {@code jnt_page/markdown}
     * emits {@code "# <page title>"} first. Using it made every option in the dropdown read
     * identically, which is worse than showing nothing: it looks like information while telling
     * the editor nothing about which snapshot to pick. So a single leading level-1 heading is
     * skipped, and the next line that carries text is shown instead.
     *
     * <p>Only the top-level heading. {@code ## Affected versions} is content, and on a page whose
     * snapshot really is nothing but its title the heading is still shown, because a bare date is
     * less useful than a title.
     */
    static String distinguishingExcerpt(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String[] lines = text.split("\n");
        String pageHeading = "";
        for (int i = 0; i < lines.length; i++) {
            String cleaned = stripControlCharacters(lines[i]).trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            boolean isPageHeading = cleaned.startsWith("# ");
            if (isPageHeading && pageHeading.isEmpty()) {
                pageHeading = cleaned;
                continue;
            }
            return truncate(cleaned, maxChars);
        }
        return truncate(pageHeading, maxChars);
    }

    private static String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars - 1) + "…";
    }

    static String firstMeaningfulLine(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        for (String line : text.split("\n")) {
            String cleaned = stripControlCharacters(line).trim();
            if (!cleaned.isEmpty()) {
                return truncate(cleaned, maxChars);
            }
        }
        return "";
    }

    private static String stripControlCharacters(String line) {
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!Character.isISOControl(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ the JCR half

    private List<ChoiceListValue> choicesFor(String siteKey, String pageUuid, String language,
                                             Locale locale) throws RepositoryException {
        String folderPath = "/sites/" + siteKey + "/contents/" + ROOT_FOLDER_NAME
                + '/' + pageUuid + '/' + language;
        return JCRTemplate.getInstance().doExecuteWithSystemSession(null, WORKSPACE, null,
                (JCRCallback<List<ChoiceListValue>>) session -> {
                    List<JCRNodeWrapper> snapshots = snapshotsIn(session, folderPath);
                    // Newest first: the entry an editor is most likely describing is the recent one,
                    // and the folder's own child order is creation order.
                    snapshots.sort(Comparator.comparing(SnapshotChoiceListInitializer::nameOf).reversed());
                    List<ChoiceListValue> choices = new ArrayList<>();
                    for (JCRNodeWrapper snapshot : snapshots) {
                        if (choices.size() >= MAX_CHOICES) {
                            break;
                        }
                        choices.add(new ChoiceListValue(
                                label(capturedAt(snapshot), excerptOf(snapshot), locale),
                                snapshot.getName()));
                    }
                    return choices;
                });
    }

    private static List<JCRNodeWrapper> snapshotsIn(JCRSessionWrapper session, String folderPath) {
        List<JCRNodeWrapper> found = new ArrayList<>();
        try {
            for (JCRNodeWrapper child : session.getNode(folderPath).getNodes()) {
                if (child.isNodeType(SNAPSHOT_TYPE)) {
                    found.add(child);
                }
            }
        } catch (RepositoryException noHistoryYet) {
            logger.debug("No snapshot folder at {} yet", folderPath, noHistoryYet);
        }
        return found;
    }

    private static String nameOf(JCRNodeWrapper node) {
        return node.getName();
    }

    private static Calendar capturedAt(JCRNodeWrapper snapshot) throws RepositoryException {
        return snapshot.hasProperty(PROP_SNAPSHOT_DATE)
                ? snapshot.getProperty(PROP_SNAPSHOT_DATE).getDate()
                : Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    /**
     * Reads a bounded prefix of the stored markdown.
     *
     * <p>The prefix may cut a multi-byte character in half; the decoder yields a replacement
     * character for the tail and the excerpt is truncated anyway, so it shows as a hint that is a
     * character short rather than as corruption.
     */
    private static String excerptOf(JCRNodeWrapper snapshot) {
        if (!hasMarkdown(snapshot)) {
            return "";
        }
        Binary binary = null;
        try {
            binary = snapshot.getProperty(PROP_MARKDOWN).getBinary();
            try (InputStream in = binary.getStream()) {
                byte[] buffer = new byte[EXCERPT_BYTES];
                int filled = 0;
                while (filled < buffer.length) {
                    int read = in.read(buffer, filled, buffer.length - filled);
                    if (read < 0) {
                        break;
                    }
                    filled += read;
                }
                return distinguishingExcerpt(
                        new String(buffer, 0, filled, StandardCharsets.UTF_8), EXCERPT_CHARS);
            }
        } catch (RepositoryException | IOException | RuntimeException e) {
            logger.debug("No excerpt available for a snapshot", e);
            return "";
        } finally {
            if (binary != null) {
                // The binary handle is a file descriptor on some providers; a dropdown that opens
                // a hundred of them and drops them on the floor is how a repository runs out.
                binary.dispose();
            }
        }
    }

    private static boolean hasMarkdown(JCRNodeWrapper snapshot) {
        try {
            return snapshot.hasProperty(PROP_MARKDOWN);
        } catch (RepositoryException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ context

    private static JCRNodeWrapper firstNodeIn(Map<String, Object> context, String... keys) {
        for (String key : keys) {
            Object candidate = context.get(key);
            if (candidate instanceof JCRNodeWrapper) {
                return (JCRNodeWrapper) candidate;
            }
        }
        return null;
    }

    /** Walks up to the page the entry lives on. */
    /**
     * @return the node whose snapshots this edit form may offer, or null when there is none
     *
     * <p>Walked to the nearest {@code jnt:page} until a content node could be revisioned too, at
     * which point the picker would have offered the enclosing page's snapshots for an entry that
     * describes the content node's -- so an editor would pin a revision to the wrong text.
     */
    private static JCRNodeWrapper pageOf(JCRNodeWrapper start) throws RepositoryException {
        return RevisionedAncestor.of(start);
    }

    /**
     * The content language whose snapshots to offer.
     *
     * <p>Snapshots are stored per language, and an entry describes the page in the language being
     * edited. The edit engine passes that locale; the node's own resolved locale is the fallback,
     * and it is never guessed -- offering another language's snapshots would let an editor pin an
     * entry to text the page never showed in this one.
     */
    private static String languageOf(Locale locale, JCRNodeWrapper node) {
        if (locale != null) {
            return locale.toString();
        }
        Locale fromNode = node.getLanguage() == null ? null : new Locale(node.getLanguage());
        return fromNode == null ? Locale.ENGLISH.toString() : fromNode.toString();
    }

    /**
     * Mirrors {@code RevisionDiffService#viewerMayReadHistory}: everything below reads with a
     * system session that bypasses ACLs, so the current user's own rights have to be asked first.
     *
     * <p>Package-private so a test can assert the verdict directly rather than through the whole
     * initializer, which denies for a dozen reasons at once when there is no repository.
     */
    boolean viewerMayReadPage(String pageIdentifier) {
        try {
            JCRSessionWrapper viewer = JCRSessionFactory.getInstance().getCurrentUserSession();
            return viewer != null && viewer.getNodeByIdentifier(pageIdentifier) != null;
        } catch (RepositoryException | RuntimeException denied) {
            logger.debug("Not offering snapshots of page {}: the current user cannot read it",
                    pageIdentifier, denied);
            return false;
        }
    }
}
