package org.jahia.modules.revisionhistory;

import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.Value;
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

    /** A multi-valued string property, which is now read rather than skipped. */
    private static Property multi(String name, String... values) throws RepositoryException {
        Property p = mock(Property.class);
        when(p.getName()).thenReturn(name);
        when(p.getType()).thenReturn(PropertyType.STRING);
        when(p.isMultiple()).thenReturn(true);
        Value[] wrapped = new Value[values.length];
        for (int i = 0; i < values.length; i++) {
            Value v = mock(Value.class);
            when(v.getString()).thenReturn(values[i]);
            wrapped[i] = v;
        }
        when(p.getValues()).thenReturn(wrapped);
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
    @DisplayName("non-string properties are skipped, so dates and references never reach a snapshot")
    void skipsNonString() throws Exception {
        JCRNodeWrapper node = nodeWith(Arrays.asList(
                prop("when", PropertyType.DATE, "2026-08-11", false),
                prop("body", PropertyType.STRING, "Kept.", false)), false);

        assertEquals(Collections.singletonList("Kept."),
                RevisionHistoryFunctions.textProperties(node));
    }

    @Test
    @DisplayName("a multi-valued string IS content, and keeps its stored order")
    void includesMultiValued() throws Exception {
        // This test replaces one that asserted multi-valued properties were skipped. That was the
        // behaviour, and it was silent content loss: a type storing its bullet points in a
        // multi-valued string beside a single-valued heading would, when an editor rewrote every
        // bullet, still hash identically -- so capture recorded UNCHANGED and the record stated
        // that nothing in the page text had changed. Order within the property is preserved
        // because it is editorial; only the ordering BETWEEN properties is normalised by name.
        JCRNodeWrapper node = nodeWith(Arrays.asList(
                multi("bullets", "First point.", "Second point."),
                prop("heading", PropertyType.STRING, "Findings", false)), false);

        assertEquals(Arrays.asList("First point.", "Second point.", "Findings"),
                RevisionHistoryFunctions.textProperties(node));
    }

    @Test
    @DisplayName("blank values inside a multi-valued property are dropped, not emitted")
    void dropsBlankMultiValues() throws Exception {
        JCRNodeWrapper node = nodeWith(Collections.singletonList(
                multi("bullets", "Kept.", "   ", "")), false);

        assertEquals(Collections.singletonList("Kept."),
                RevisionHistoryFunctions.textProperties(node));
    }

    @Test
    @DisplayName("a node whose only text is its title is not reported as losing content")
    void titleOnlyNodeIsNotAFallThrough() throws Exception {
        // The fallback view emits '## <title>', so this node DOES reach the snapshot. Warning about
        // it trained an operator to filter the message, which then hid the genuine case.
        JCRNodeWrapper node = nodeWith(Collections.emptyList(), false);
        when(node.hasProperty("jcr:title")).thenReturn(true);
        JCRPropertyWrapper title = mock(JCRPropertyWrapper.class);
        when(title.getString()).thenReturn("A heading");
        when(node.getProperty("jcr:title")).thenReturn(title);

        // Asserting the DECISION, not the returned list. hasTitle only gates the warning, so an
        // assertion on textProperties(...) could not observe it at all: the previous version of
        // this test was byte-identical in effect to the no-title test below, and would have passed
        // with hasTitle deleted or always false.
        assertTrue(RevisionHistoryFunctions.textProperties(node).isEmpty(),
                "it contributes no property text, which is correct");
        assertFalse(
                RevisionHistoryFunctions.nothingReachesTheSnapshot(node, Collections.emptyList()),
                "a title IS emitted by the view, so this must not be reported as content lost");
    }

    @Test
    @DisplayName("a node with neither text, children nor title IS reported as losing content")
    void nothingAtAllIsReported() throws Exception {
        JCRNodeWrapper node = nodeWith(Collections.emptyList(), false);

        assertTrue(
                RevisionHistoryFunctions.nothingReachesTheSnapshot(node, Collections.emptyList()),
                "nothing of this node reaches the snapshot, which must be reported");
    }

    @Test
    @DisplayName("a container with children is not reported, even with no text of its own")
    void containerIsNotReported() throws Exception {
        JCRNodeWrapper node = nodeWith(Collections.emptyList(), true);

        assertFalse(
                RevisionHistoryFunctions.nothingReachesTheSnapshot(node, Collections.emptyList()),
                "its children carry the content; warning here would be noise on every page");
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

    // ------------------------------------------------------------------ #18: plain strings are text

    @Test
    @DisplayName("#18: a plain string is escaped, so a literal <style> cannot swallow the page")
    void plainStringIsEscapedForTheMarkupStream() throws Exception {
        // No definition on the mock, which is also what an undefined (residual) property looks like:
        // treated as plain, because escaping markup is recoverable and parsing text is not.
        JCRNodeWrapper node = nodeWith(Collections.singletonList(
                prop("hint", PropertyType.STRING, "Set the <style> attribute & <!-- note -->.", false)), false);

        List<String> values = RevisionHistoryFunctions.textProperties(node);

        assertEquals(Collections.singletonList(
                "Set the &lt;style&gt; attribute &amp; &lt;!-- note --&gt;."), values);
    }

    @Test
    @DisplayName("#18: a rich-text property is passed through raw, because it IS markup")
    void richTextIsPassedThroughRaw() throws Exception {
        Property rich = prop("body", PropertyType.STRING, "<p>Hello <b>there</b></p>", false);
        org.jahia.services.content.nodetypes.ExtendedPropertyDefinition definition =
                mock(org.jahia.services.content.nodetypes.ExtendedPropertyDefinition.class);
        when(definition.getSelector()).thenReturn(org.jahia.services.content.nodetypes.SelectorType.RICHTEXT);
        when(rich.getDefinition()).thenReturn(definition);
        JCRNodeWrapper node = nodeWith(Collections.singletonList(rich), false);

        List<String> values = RevisionHistoryFunctions.textProperties(node);

        assertEquals(Collections.singletonList("<p>Hello <b>there</b></p>"), values);
    }

    @Test
    @DisplayName("#18: a small-text selector is plain even though its definition is readable")
    void smallTextIsPlain() throws Exception {
        Property small = prop("label", PropertyType.STRING, "a < b", false);
        org.jahia.services.content.nodetypes.ExtendedPropertyDefinition definition =
                mock(org.jahia.services.content.nodetypes.ExtendedPropertyDefinition.class);
        when(definition.getSelector()).thenReturn(org.jahia.services.content.nodetypes.SelectorType.SMALLTEXT);
        when(small.getDefinition()).thenReturn(definition);

        assertFalse(RevisionHistoryFunctions.isRichText(small));
        assertEquals("a &lt; b", RevisionHistoryFunctions.escapeForMarkup("a < b"));
    }

    @Test
    @DisplayName("#18: a definition that cannot be read means plain, not rich")
    void unreadableDefinitionMeansPlain() throws Exception {
        Property broken = prop("x", PropertyType.STRING, "text", false);
        when(broken.getDefinition()).thenThrow(new RepositoryException("gone"));

        assertFalse(RevisionHistoryFunctions.isRichText(broken));
    }
}
