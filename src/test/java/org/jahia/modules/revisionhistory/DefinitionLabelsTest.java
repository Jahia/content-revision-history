package org.jahia.modules.revisionhistory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every node type and property declared in the CND must have a label in the resource bundle.
 *
 * <p>Jahia falls back to displaying the raw bundle key when a label is missing, so an editor sees
 * {@code crh_snapshotDate} where a caption should be. Nothing fails, nothing is logged, and the
 * only way to notice is to open the type in jContent and look -- which is how several properties
 * on the two system types shipped unlabelled.
 *
 * <p>This is a text-level check on purpose. The alternative is standing up the type registry,
 * which needs a repository; parsing the two files that actually ship keeps the guard in the fast
 * gate, where it will run on every build rather than only when someone remembers.
 */
class DefinitionLabelsTest {

    /** {@code [crh:revisionEntry] > jnt:content, ...} */
    private static final Pattern TYPE = Pattern.compile("^\\[([^\\]]+)]");

    /** {@code - revisionLabel (string) mandatory} and {@code - crh:snapshotDate (date) ...} */
    private static final Pattern PROPERTY = Pattern.compile("^\\s*-\\s*([A-Za-z0-9_:]+)\\s*\\(");

    private static String read(String resource) throws IOException {
        try (InputStream in = DefinitionLabelsTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " must be on the classpath");
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    text.append(line).append('\n');
                }
            }
            return text.toString();
        }
    }

    /** Bundle keys, ignoring comments and blank lines. */
    private static Set<String> bundleKeys() throws IOException {
        Set<String> keys = new HashSet<>();
        for (String line : read("/resources/content-revision-history.properties").split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                continue;
            }
            keys.add(trimmed.substring(0, trimmed.indexOf('=')).trim());
        }
        return keys;
    }

    /** A CND name becomes a bundle key by replacing the namespace colon with an underscore. */
    private static String asKey(String cndName) {
        return cndName.replace(':', '_');
    }

    @Test
    @DisplayName("Every CND type and property has a label, so no editor sees a raw key")
    void everyDeclarationIsLabelled() throws IOException {
        Set<String> keys = bundleKeys();
        List<String> missing = new ArrayList<>();
        String currentType = null;

        for (String line : read("/META-INF/definitions.cnd").split("\n")) {
            Matcher type = TYPE.matcher(line);
            if (type.find()) {
                currentType = type.group(1);
                if (!keys.contains(asKey(currentType))) {
                    missing.add(asKey(currentType) + "   (type label)");
                }
                continue;
            }

            Matcher property = PROPERTY.matcher(line);
            if (property.find() && currentType != null) {
                // Namespaced properties keep their prefix in the key, matching the convention the
                // platform's own modules use (jnt_vanityURL.j_url).
                String key = asKey(currentType) + '.' + asKey(property.group(1));
                if (!keys.contains(key)) {
                    missing.add(key);
                }
            }
        }

        assertTrue(missing.isEmpty(),
                "these declarations would render as raw keys in Content Editor:\n  "
                        + String.join("\n  ", missing));
    }

    @Test
    @DisplayName("The default bundle and its _en copy stay identical, or one locale silently lags")
    void theTwoBundlesAgree() throws IOException {
        // They are maintained as copies. A key added to one and not the other means English
        // readers -- which is to say most readers -- get the raw key back for that entry only.
        assertEquals(read("/resources/content-revision-history.properties"),
                read("/resources/content-revision-history_en.properties"),
                "content-revision-history.properties and its _en copy have diverged");
    }
}
