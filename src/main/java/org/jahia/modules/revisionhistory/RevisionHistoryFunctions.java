package org.jahia.modules.revisionhistory;

import javax.jcr.RepositoryException;

/**
 * The only surface the JSP views may call into.
 *
 * <p>Everything reachable from a view goes through here, so the boundary between "content a
 * visitor supplied" and "content this module renders" is one small, reviewable file rather than
 * being spread across JSPs. Both methods are total: they answer for every input, including
 * null, because a view has no sensible way to handle an exception thrown mid-render.
 */
public final class RevisionHistoryFunctions {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(RevisionHistoryFunctions.class);

    /**
     * Stateless and cheap to construct, but there is no reason to build one per rendered page.
     */
    private static final RevisionDiffService DIFF_SERVICE = new RevisionDiffService();

    private RevisionHistoryFunctions() {
        // EL function holder
    }

    /**
     * Makes editor-authored rich text safe to emit unescaped.
     *
     * @see RichTextSanitizer
     */
    public static String sanitize(String html) {
        return RichTextSanitizer.sanitize(html);
    }

    /**
     * A captured snapshot's Markdown, for the jContent preview.
     *
     * <p>Reads through the caller's own session, deliberately: unlike the public comparison, this
     * is an editorial view of a single node, so whoever is previewing must already have been
     * allowed to see it. Escalating to a system session here would hand the snapshot tree to
     * anyone who could reach the preview.
     *
     * @return the Markdown, or an empty string if it cannot be read; never null, because a view
     *         has no sensible way to handle an exception thrown mid-render
     */
    public static String snapshotMarkdown(org.jahia.services.content.JCRNodeWrapper snapshot) {
        try {
            return SnapshotPayload.read(snapshot);
        } catch (RepositoryException e) {
            LOGGER.error("Could not read the snapshot payload for preview", e);
            return "";
        }
    }

    /**
     * A revision history's entries, newest first by {@code revisionDate}.
     *
     * <p>The list view renders in this order and {@link #compareAll} pairs in this order, so the
     * control that opens a comparison and the panel it opens can never mean different revisions.
     *
     * @see RevisionEntryOrder for why order is derived rather than positional
     */
    public static java.util.List<org.jahia.services.content.JCRNodeWrapper> orderedEntries(
            org.jahia.services.content.JCRNodeWrapper history) {
        return RevisionEntryOrder.newestFirst(history);
    }

    /**
     * Compares two revisions of the same history.
     *
     * <p>Both identifiers come from the visitor's form selection and are therefore untrusted; the
     * history node is server-supplied and is what constrains them to entries of that history.
     *
     * @return always a view; ask {@link RevisionDiffView#isAvailable()} before reading the diff
     */
    public static RevisionDiffView compare(String historyIdentifier, String oneIdentifier,
                                           String otherIdentifier, String language) {
        return DIFF_SERVICE.compare(historyIdentifier, oneIdentifier, otherIdentifier, language);
    }

    /**
     * Every text-bearing property this node carries, so the generic markdown fallback can emit the
     * node's content instead of only its title.
     *
     * <p>Written because it was missing. The fallback view emitted {@code jcr:title} and then
     * recursed into children, so any node holding its text in some OTHER property rendered
     * completely empty. Measured on a real advisory page: a leaf with 388 characters of stored text
     * rendered nothing at all, every instant of a backfill composed to the page heading alone, and
     * the run stored one snapshot for a page that had changed five times. Live capture had the same
     * hole. Silent content loss in a record meant to be authoritative is the worst failure this
     * module has, which is why the fallback must emit content it was not specialised for rather
     * than quietly emit nothing.
     *
     * <p>Only single-valued strings are returned, and system namespaces are skipped: {@code jcr:}
     * and {@code j:} carry structure and publication bookkeeping, never prose. Ordering is
     * alphabetical by property name, not definition order, because a snapshot is diffed against
     * its neighbours and a stable order matters more than a natural one.
     *
     * <p>The trade-off is deliberate: a non-prose string property (a link target, say) will appear
     * in the snapshot. Emitting a little too much is recoverable by specialising a view for that
     * type; emitting nothing loses the record and looks like success.
     *
     * @return the values, never null, in a stable order
     */
    public static java.util.List<String> textProperties(org.jahia.services.content.JCRNodeWrapper node) {
        java.util.List<String> values = new java.util.ArrayList<>();
        if (node == null) {
            return values;
        }
        java.util.SortedMap<String, java.util.List<String>> byName = new java.util.TreeMap<>();
        try {
            javax.jcr.PropertyIterator properties = node.getProperties();
            while (properties.hasNext()) {
                javax.jcr.Property property = properties.nextProperty();
                String name = property.getName();
                if (name.startsWith("jcr:") || name.startsWith("j:")) {
                    continue;
                }
                try {
                    if (property.getType() != javax.jcr.PropertyType.STRING) {
                        continue;
                    }
                    // Multi-valued properties are included, each value as its own block. Skipping
                    // them was silent content loss of the worst kind: a type storing its bullet
                    // points in a multi-valued string beside a single-valued heading would, when an
                    // editor rewrote every bullet, still hash identically -- so capture recorded
                    // UNCHANGED and the record stated that nothing in the page text had changed.
                    // Values keep their stored order within the property; properties are ordered by
                    // name because JCR does not guarantee iteration order.
                    java.util.List<String> propertyValues = new java.util.ArrayList<>();
                    if (property.isMultiple()) {
                        javax.jcr.Value[] stored = property.getValues();
                        for (javax.jcr.Value value : stored == null ? new javax.jcr.Value[0] : stored) {
                            String text = value.getString();
                            if (text != null && !text.trim().isEmpty()) {
                                propertyValues.add(text);
                            }
                        }
                    } else {
                        String value = property.getString();
                        if (value != null && !value.trim().isEmpty()) {
                            propertyValues.add(value);
                        }
                    }
                    if (!propertyValues.isEmpty()) {
                        byName.put(name, propertyValues);
                    }
                } catch (RepositoryException unreadable) {
                    // One unreadable property must not cost the whole node its content.
                    LOGGER.warn("Could not read property {} of {}, skipping it", name, node.getPath(), unreadable);
                }
            }
        } catch (RepositoryException cannotList) {
            LOGGER.warn("Could not list the properties of {}; its text will be missing from the"
                    + " snapshot", safePath(node), cannotList);
            return values;
        }
        byName.values().forEach(values::addAll);
        if (values.isEmpty() && !hasChildren(node) && !hasTitle(node)) {
            // The loud fall-through the design asked for. A node that contributes neither text nor
            // children is content that vanished from the record, and it must not do so in silence.
            LOGGER.warn("Node {} of type {} contributed NO text and has no children, so nothing of"
                    + " it reaches the snapshot. If it holds content, the markdown template type"
                    + " needs a view for that type.", safePath(node), safeType(node));
        }
        return values;
    }

    private static boolean hasChildren(org.jahia.services.content.JCRNodeWrapper node) {
        try {
            javax.jcr.NodeIterator children = node.getNodes();
            while (children.hasNext()) {
                if (!children.nextNode().getName().startsWith("j:")) {
                    return true;
                }
            }
        } catch (RepositoryException cannotList) {
            // Assume children rather than log a second warning for the same node.
            return true;
        }
        return false;
    }

    private static String safePath(org.jahia.services.content.JCRNodeWrapper node) {
        try {
            return node.getPath();
        } catch (Exception unavailable) {
            return "(path unavailable)";
        }
    }

    private static String safeType(org.jahia.services.content.JCRNodeWrapper node) {
        try {
            return node.getPrimaryNodeTypeName();
        } catch (Exception unavailable) {
            return "(type unavailable)";
        }
    }

    /**
     * Does the fallback view already emit something for this node?
     *
     * <p>The view emits {@code ## <jcr:title>} when a title is set, so a node whose only text is
     * its title DOES reach the snapshot and must not be reported as content vanishing. Warning
     * about it trained an operator to filter the message, which then hid the genuine case: a
     * content-bearing type contributing nothing at all.
     */
    private static boolean hasTitle(org.jahia.services.content.JCRNodeWrapper node) {
        try {
            return node.hasProperty("jcr:title")
                    && node.getProperty("jcr:title").getString() != null
                    && !node.getProperty("jcr:title").getString().trim().isEmpty();
        } catch (RepositoryException unreadable) {
            // Assume it has one rather than emit a warning we cannot substantiate.
            return true;
        }
    }
}
