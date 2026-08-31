package org.jahia.modules.revisionhistory;

import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The generic markdown fallback's content source.
 *
 * <p>These exist because the fallback emitted {@code jcr:title} and nothing else, so any node
 * holding its text in another property rendered completely empty. On a real advisory page that cost
 * a leaf its 388 characters, made every backfilled instant compose to the page heading alone, and
 * stored one snapshot for a page that had changed five times. The same hole applied to live capture.
 */
class RevisionHistoryFunctionsTextPropertiesTest {

    /** A single-valued property of the given type. */
    private static Property prop(String name, int type, String value, boolean multiple)
            throws RepositoryException {
        Property p = mock(Property.class);
        when(p.getName()).thenReturn(name);
        when(p.getType()).thenReturn(type);
        when(p.isMultiple()).thenReturn(multiple);
        when(p.getString()).thenReturn(value);
        return p;
    }

    private static JCRNodeWrapper nodeWith(List<Property> properties, boolean hasChild)
            throws RepositoryException {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getPath()).thenReturn("/sites/x/home/page/leaf");
        when(node.getPrimaryNodeTypeName()).thenReturn("jdnt:advisorySection");

        PropertyIterator properties1 = mock(PropertyIterator.class);
        Iterator<Property> it = properties.iterator();
        when(properties1.hasNext()).thenAnswer(i -> it.hasNext());
        when(properties1.nextProperty()).thenAnswer(i -> it.next());
        when(node.getProperties()).thenReturn(properties1);

        JCRNodeIteratorWrapper children = mock(JCRNodeIteratorWrapper.class);
        if (hasChild) {
            javax.jcr.Node child = mock(javax.jcr.Node.class);
            when(child.getName()).thenReturn("nested");
            Iterator<javax.jcr.Node> ci = Collections.singletonList(child).iterator();
            when(children.hasNext()).thenAnswer(i -> ci.hasNext());
            when(children.nextNode()).thenAnswer(i -> ci.next());
        } else {
            when(children.hasNext()).thenReturn(false);
        }
        when(node.getNodes()).thenReturn(children);
        return node;
    }

    @Test
    @DisplayName("a node's text property is emitted, which is the whole point")
    void emitsTextBearingProperty() throws Exception {
        // Arrange: the shape that rendered empty in production -- prose in a property that is
        // neither jcr:title nor the jnt:bigText 'text' the specialised view knows about.
        JCRNodeWrapper node = nodeWith(Collections.singletonList(
                prop("body", PropertyType.STRING, "Check whether the exploit applies.", false)), false);

        // Act
        List<String> values = RevisionHistoryFunctions.textProperties(node);

        // Assert
        assertEquals(Collections.singletonList("Check whether the exploit applies."), values);
    }

    @Test
    @DisplayName("system namespaces are skipped: they carry structure, never prose")
    void skipsSystemNamespaces() throws Exception {
        JCRNodeWrapper node = nodeWith(Arrays.asList(
                prop("jcr:title", PropertyType.STRING, "A title", false),
                prop("j:lastPublishedBy", PropertyType.STRING, "someone@example.com", false),
                prop("body", PropertyType.STRING, "Real content.", false)), false);

        List<String> values = RevisionHistoryFunctions.textProperties(node);

        // jcr:title is excluded here because the view emits it separately as a heading; emitting it
        // twice would double every title in every snapshot.
        assertEquals(Collections.singletonList("Real content."), values);
    }

    @Test
    @DisplayName("only single-valued strings, so references and dates never reach a snapshot")
    void skipsNonStringAndMultiValued() throws Exception {
        JCRNodeWrapper node = nodeWith(Arrays.asList(
                prop("when", PropertyType.DATE, "2026-08-11", false),
                prop("tags", PropertyType.STRING, "one", true),
                prop("body", PropertyType.STRING, "Kept.", false)), false);

        assertEquals(Collections.singletonList("Kept."),
                RevisionHistoryFunctions.textProperties(node));
    }

    @Test
    @DisplayName("ordering is stable by name, because snapshots are diffed against each other")
    void ordersByName() throws Exception {
        JCRNodeWrapper node = nodeWith(Arrays.asList(
                prop("zeta", PropertyType.STRING, "last", false),
                prop("alpha", PropertyType.STRING, "first", false)), false);

        // Property iteration order is not guaranteed by JCR, so an unsorted emission would make a
        // snapshot differ from its predecessor for no editorial reason and every diff would churn.
        assertEquals(Arrays.asList("first", "last"),
                RevisionHistoryFunctions.textProperties(node));
    }

    @Test
    @DisplayName("blank values are dropped rather than emitted as empty lines")
    void dropsBlankValues() throws Exception {
        JCRNodeWrapper node = nodeWith(Arrays.asList(
                prop("body", PropertyType.STRING, "   ", false),
                prop("intro", PropertyType.STRING, "Kept.", false)), false);

        assertEquals(Collections.singletonList("Kept."),
                RevisionHistoryFunctions.textProperties(node));
    }

    @Test
    @DisplayName("an unreadable property costs only itself, not the whole node's content")
    void oneBadPropertyDoesNotLoseTheRest() throws Exception {
        Property broken = mock(Property.class);
        when(broken.getName()).thenReturn("broken");
        when(broken.isMultiple()).thenThrow(new RepositoryException("cannot read"));

        JCRNodeWrapper node = nodeWith(Arrays.asList(
                broken,
                prop("body", PropertyType.STRING, "Survives.", false)), false);

        assertEquals(Collections.singletonList("Survives."),
                RevisionHistoryFunctions.textProperties(node));
    }

    @Test
    @DisplayName("a null node answers with an empty list, because a view cannot handle a throw")
    void nullNodeIsTotal() {
        assertTrue(RevisionHistoryFunctions.textProperties(null).isEmpty());
    }

    @Test
    @DisplayName("a node with neither text nor children yields nothing, and says so in the log")
    void emptyLeafIsReported() throws Exception {
        // The loud fall-through. The assertion here is only that it does not throw and returns
        // empty; the WARN it logs is what makes the loss visible to an operator.
        JCRNodeWrapper node = nodeWith(Collections.emptyList(), false);

        assertTrue(RevisionHistoryFunctions.textProperties(node).isEmpty());
    }

    @Test
    @DisplayName("a container contributes no text of its own and that is not a loss")
    void containerWithChildrenIsNotAnEmptyLeaf() throws Exception {
        // A jnt:contentList holds no prose; its children carry it. This must not be reported as
        // content vanishing, or the warning becomes noise on every page.
        JCRNodeWrapper node = nodeWith(Collections.emptyList(), true);

        assertTrue(RevisionHistoryFunctions.textProperties(node).isEmpty());
    }
}
