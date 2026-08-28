package org.jahia.modules.revisionhistory;

import org.jahia.services.content.JCRNodeWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Binary;
import javax.jcr.RepositoryException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.MAX_MARKDOWN_BYTES;
import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.PROP_MARKDOWN;

/**
 * Reads a snapshot's Markdown out of its binary property.
 *
 * <p>Shared by the comparison service and the jContent preview so there is one place that knows
 * how to do it -- and, more to the point, one place that enforces the size cap and disposes the
 * {@link Binary}. A second copy of this is how a binary handle gets leaked on one path but not
 * the other.
 *
 * <p>The property is {@code binary} rather than {@code string} precisely so that reading a
 * snapshot's <em>metadata</em> does not drag a whole page of Markdown along with it; that only
 * pays off if the payload is fetched deliberately, which is what this class is for.
 */
public final class SnapshotPayload {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotPayload.class);

    private SnapshotPayload() {
        // static utility
    }

    /**
     * @return the snapshot's Markdown, or an empty string when it has none
     * @throws RepositoryException if the payload cannot be read at all
     */
    public static String read(JCRNodeWrapper snapshot) throws RepositoryException {
        if (snapshot == null || !snapshot.hasProperty(PROP_MARKDOWN)) {
            return "";
        }
        Binary binary = snapshot.getProperty(PROP_MARKDOWN).getBinary();
        try (InputStream in = binary.getStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                // Re-applied on read even though capture enforces it on write: these are public
                // and editor-facing request paths, and neither may become a way to pull an
                // arbitrarily large binary into heap because something else wrote one.
                if (buffer.size() + read > MAX_MARKDOWN_BYTES) {
                    logger.warn("Snapshot {} exceeds the {} byte cap on read; payload truncated",
                            snapshot.getPath(), MAX_MARKDOWN_BYTES);
                    break;
                }
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RepositoryException("Could not read snapshot " + snapshot.getPath(), e);
        } finally {
            // Jackrabbit hands out a handle to a stored binary; not disposing it keeps a file
            // descriptor or a temp file alive for the life of the session.
            binary.dispose();
        }
    }
}
