package org.jahia.modules.revisionhistory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SnapshotCaptureJob#encode} builds the {@code uuid:lang,lang;...} payload that crosses
 * from the publication thread into the Quartz job's {@link org.quartz.JobDataMap}. Its
 * correctness is entirely about the encoding rules, so it is tested as a pure function here.
 */
class SnapshotCaptureJobTest {

    @Test
    @DisplayName("encodes a single page with a single language")
    void encodesSinglePageSingleLanguage() {
        // Arrange
        List<String> uuids = List.of("11111111-1111-1111-1111-111111111111");
        List<Set<String>> languages = List.of(setOf("en"));

        // Act
        String encoded = SnapshotCaptureJob.encode(uuids, languages);

        // Assert
        assertEquals("11111111-1111-1111-1111-111111111111:en", encoded);
    }

    @Test
    @DisplayName("encodes multiple pages, separated by semicolons, languages by commas")
    void encodesMultiplePagesAndLanguages() {
        // Arrange
        List<String> uuids = List.of("uuid-a", "uuid-b");
        List<Set<String>> languages = List.of(setOf("en", "fr"), setOf("de"));

        // Act
        String encoded = SnapshotCaptureJob.encode(uuids, languages);

        // Assert
        assertEquals("uuid-a:en,fr;uuid-b:de", encoded);
    }

    @Test
    @DisplayName("a page with an empty language set is skipped entirely, not emitted with no languages")
    void skipsPageWithEmptyLanguageSet() {
        // Arrange -- an empty-language entry must never produce a dangling "uuid:" segment,
        // which SnapshotCaptureJob.captureOnePageSafely would then split into a single
        // empty-string "language".
        List<String> uuids = List.of("uuid-a", "uuid-b", "uuid-c");
        List<Set<String>> languages = List.of(setOf("en"), setOf(), setOf("de"));

        // Act
        String encoded = SnapshotCaptureJob.encode(uuids, languages);

        // Assert
        assertEquals("uuid-a:en;uuid-c:de", encoded);
        assertFalse(encoded.contains("uuid-b"), "a page with no languages must not appear at all");
    }

    @Test
    @DisplayName("all pages having empty language sets yields an empty payload")
    void allEmptyLanguageSetsYieldsEmptyString() {
        // Arrange
        List<String> uuids = List.of("uuid-a", "uuid-b");
        List<Set<String>> languages = List.of(setOf(), setOf());

        // Act
        String encoded = SnapshotCaptureJob.encode(uuids, languages);

        // Assert
        assertEquals("", encoded);
    }

    @Test
    @DisplayName("duplicate languages for a page are deduplicated, preserving first-seen order")
    void dedupesLanguagesPreservingOrder() {
        // Arrange -- a LinkedHashSet iteration order depends on insertion order of the *input*
        // set, so we build an input whose iteration already contains a duplicate-shaped scenario
        // by re-inserting through a LinkedHashSet built the same way encode() builds its own.
        Set<String> languagesWithDuplicateIntent = new LinkedHashSet<>(Arrays.asList("fr", "en", "fr", "de", "en"));
        List<String> uuids = List.of("uuid-a");
        List<Set<String>> languages = List.of(languagesWithDuplicateIntent);

        // Act
        String encoded = SnapshotCaptureJob.encode(uuids, languages);

        // Assert -- "fr" and "en" each appear exactly once, in the order first inserted
        assertEquals("uuid-a:fr,en,de", encoded);
    }

    @Test
    @DisplayName("caps at MAX_PAGES_PER_PUBLICATION (500); further pages are truncated, not encoded")
    void capsAtMaxPagesPerPublication() {
        // Arrange -- one event must never be able to enqueue the world
        int totalPages = RevisionHistoryConstants.MAX_PAGES_PER_PUBLICATION + 50;
        List<String> uuids = new ArrayList<>(totalPages);
        List<Set<String>> languages = new ArrayList<>(totalPages);
        for (int i = 0; i < totalPages; i++) {
            uuids.add("uuid-" + i);
            languages.add(setOf("en"));
        }

        // Act
        String encoded = SnapshotCaptureJob.encode(uuids, languages);

        // Assert -- exactly MAX_PAGES_PER_PUBLICATION entries are emitted
        String[] entries = encoded.split(";");
        assertEquals(RevisionHistoryConstants.MAX_PAGES_PER_PUBLICATION, entries.length);
        assertEquals("uuid-0:en", entries[0]);
        assertEquals("uuid-" + (RevisionHistoryConstants.MAX_PAGES_PER_PUBLICATION - 1) + ":en",
                entries[entries.length - 1]);
        assertFalse(encoded.contains("uuid-" + RevisionHistoryConstants.MAX_PAGES_PER_PUBLICATION + ":"),
                "the 501st page (index == cap) must be truncated away");
    }

    @Test
    @DisplayName("an empty input list encodes to an empty string")
    void emptyInputEncodesToEmptyString() {
        // Act
        String encoded = SnapshotCaptureJob.encode(List.of(), List.of());

        // Assert
        assertEquals("", encoded);
    }

    private static Set<String> setOf(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}
