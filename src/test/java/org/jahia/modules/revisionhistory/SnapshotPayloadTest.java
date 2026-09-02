package org.jahia.modules.revisionhistory;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.jcr.Binary;
import javax.jcr.RepositoryException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.MAX_MARKDOWN_BYTES;
import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.PROP_MARKDOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The read side of the payload, which had no tests at all -- which is how it came to truncate
 * silently for several releases.
 *
 * <p>What matters here is not that the cap exists but that exceeding it is REPORTED. A snapshot
 * missing its tail is indistinguishable from a page that was genuinely shorter, so a caller that
 * cannot tell will render it as the complete record; the public comparison did exactly that, and
 * reported every line past the cut as removed.
 */
class SnapshotPayloadTest {

    /** @param stored the bytes the snapshot's binary property holds */
    private static JCRNodeWrapper snapshotHolding(byte[] stored) throws RepositoryException {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        // JCRNodeWrapper.getProperty returns the WRAPPER type, not javax.jcr.Property.
        JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
        Binary binary = mock(Binary.class);
        when(node.getPath()).thenReturn("/sites/x/contents/revision-history/p/en/20260101T000000000Z-abcdef12");
        when(node.hasProperty(PROP_MARKDOWN)).thenReturn(true);
        when(node.getProperty(PROP_MARKDOWN)).thenReturn(property);
        when(property.getBinary()).thenReturn(binary);
        when(binary.getStream()).thenReturn(new ByteArrayInputStream(stored));
        return node;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a payload within the cap is returned whole and reported complete")
    void withinTheCapIsComplete() throws Exception {
        JCRNodeWrapper node = snapshotHolding(utf8("# Policy\n\nA short page.\n"));

        SnapshotContent content = SnapshotPayload.read(node);

        assertEquals("# Policy\n\nA short page.\n", content.getMarkdown());
        assertFalse(content.isTruncated(), "a payload that fitted must not be reported as truncated");
    }

    @Test
    @DisplayName("a payload over the cap is reported truncated, not returned as if whole")
    void overTheCapIsReportedTruncated() throws Exception {
        // The defect: this returned the prefix with isTruncated() unavailable, so the comparison
        // treated it as the complete snapshot.
        JCRNodeWrapper node = snapshotHolding(utf8("x".repeat(MAX_MARKDOWN_BYTES + 8192)));

        SnapshotContent content = SnapshotPayload.read(node);

        assertTrue(content.isTruncated(),
                "the whole point: a caller must be able to tell it is holding only the start");
        assertTrue(content.getMarkdown().length() <= MAX_MARKDOWN_BYTES,
                "the cap must still bound what is pulled into heap on a public request path");
        assertFalse(content.getMarkdown().isEmpty(),
                "the prefix is still returned -- refusing outright would hide a snapshot that is"
                        + " mostly readable, and the notice is what makes showing it honest");
    }

    @Test
    @DisplayName("a payload exactly at the cap is complete, not truncated")
    void exactlyAtTheCapIsComplete() throws Exception {
        // The boundary in both directions. Reporting a payload that fitted exactly as truncated
        // would put a "this comparison is incomplete" warning on a comparison that is fine, and a
        // warning that cries wolf is how a real one gets ignored.
        JCRNodeWrapper node = snapshotHolding(utf8("y".repeat(MAX_MARKDOWN_BYTES)));

        SnapshotContent content = SnapshotPayload.read(node);

        assertEquals(MAX_MARKDOWN_BYTES, content.getMarkdown().length());
        assertFalse(content.isTruncated(), "exactly at the cap is not over it");
    }

    @Test
    @DisplayName("a snapshot with no payload reads as empty and complete")
    void noPayloadReadsEmpty() throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.hasProperty(anyString())).thenReturn(false);

        SnapshotContent content = SnapshotPayload.read(node);

        assertEquals("", content.getMarkdown());
        assertFalse(content.isTruncated(), "absent is not truncated: there is nothing to warn about");
    }

    @Test
    @DisplayName("a null node reads as empty rather than throwing into a render")
    void nullNodeReadsEmpty() throws Exception {
        SnapshotContent content = SnapshotPayload.read(null);

        assertEquals("", content.getMarkdown());
        assertFalse(content.isTruncated());
    }

    @Test
    @DisplayName("the binary is disposed even when the read stops at the cap")
    void binaryIsDisposedOnTheTruncatedPath() throws Exception {
        // Jackrabbit hands out a handle to a stored binary; not disposing it keeps a file
        // descriptor or a temp file alive for the life of the session. The early break added for
        // the cap is exactly the kind of exit that skips cleanup if it is not in a finally.
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        // JCRNodeWrapper.getProperty returns the WRAPPER type, not javax.jcr.Property.
        JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
        Binary binary = mock(Binary.class);
        when(node.getPath()).thenReturn("/sites/x/oversized");
        when(node.hasProperty(PROP_MARKDOWN)).thenReturn(true);
        when(node.getProperty(PROP_MARKDOWN)).thenReturn(property);
        when(property.getBinary()).thenReturn(binary);
        when(binary.getStream())
                .thenReturn(new ByteArrayInputStream(utf8("z".repeat(MAX_MARKDOWN_BYTES + 8192))));

        SnapshotContent content = SnapshotPayload.read(node);

        assertTrue(content.isTruncated(), "precondition: this test is about the truncated path");
        verify(binary, times(1)).dispose();
    }
}
