package org.jahia.modules.revisionhistory.graphql;

import graphql.ErrorType;
import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import org.jahia.modules.graphql.provider.dxm.BaseGqlClientException;
import org.jahia.modules.revisionhistory.SiteCaptureSettings;
import org.jahia.modules.revisionhistory.SiteSettingsRegistry;

import java.io.IOException;

/** Everything this module adds to the root mutation, under one field. */
@GraphQLName("CrhMutation")
@GraphQLDescription("Content Revision History")
public class RevisionHistoryMutation {

    @GraphQLField
    @GraphQLDescription("Writes this site's settings. Requires the siteAdminContentRevisionHistory permission on that"
            + " site. Returns the settings as they now stand.")
    public GqlSiteSettings saveSiteSettings(
            @GraphQLName("siteKey") @GraphQLNonNull String siteKey,
            @GraphQLName("captureEnabled") Boolean captureEnabled,
            @GraphQLName("maxSnapshots") Integer maxSnapshots,
            @GraphQLName("captureUser") String captureUser,
            @GraphQLName("baseUrl") String baseUrl) {
        SiteSettingsAccess.requireSiteAdmin(siteKey);
        // Refused here rather than written and quietly replaced later. An out-of-range value was
        // persisted verbatim and echoed back, so the panel displayed it as the applied setting.
        // only when FileInstall re-parsed the file did the positiveInt helper notice, and it then
        // substituted the module default and logged a warning that never reached the UI, leaving
        // the operator believing a value the running system was not using.
        if (maxSnapshots != null && maxSnapshots < SiteSettingsRegistry.MIN_MAX_SNAPSHOTS) {
            throw new BaseGqlClientException(
                    "maxSnapshots must be at least " + SiteSettingsRegistry.MIN_MAX_SNAPSHOTS + "; "
                    + maxSnapshots + " cannot be honoured, because retention never deletes a page's"
                    + " newest snapshot. Leave it unset to use the module default.",
                    ErrorType.ValidationError);
        }
        // Refused here as well as in save(), so the administrator gets a validation error naming
        // the field instead of an "Internal Server Error(s) while executing query".
        if (isPresent(baseUrl) && !SiteSettingsRegistry.addressesThisNode(baseUrl)) {
            throw new BaseGqlClientException(
                    "baseUrl must equal this node's own loopback connector, not " + baseUrl
                    + ". Capture fetches the page from this node itself, so any other address --"
                    + " a different port, a path, a query or a fragment -- would send the capture"
                    + " credential elsewhere or store another response as this site's revision"
                    + " history. It must be exactly http://127.0.0.1:<this node's port> with no"
                    + " trailing path, or send an empty value to clear the setting and let the"
                    + " connector be detected.",
                    ErrorType.ValidationError);
        }
        SiteSettingsRegistry registry = SiteSettingsAccess.registry();
        SiteCaptureSettings current = registry.forSite(siteKey);

        // Absent means "leave as it is", not "clear it". A panel that only edits one field must not
        // silently reset the rest, and a null from GraphQL cannot be told apart from an omission.
        // An EMPTY STRING is how a field is cleared, and it has to be: with null as the only way to
        // say "no value", neither captureUser nor baseUrl could ever be emptied once set. An
        // administrator who mistyped baseUrl had no way back except "Use defaults", which also
        // discards maxSnapshots and captureEnabled -- and with baseUrl now refused unless it
        // addresses this node, being unable to clear a bad one would have been a dead end.
        // The secret is never written from here: it belongs in a file whose permissions an
        // administrator controls, and a GraphQL argument would end up in request logs. withChanges
        // carries the already-resolved credential across without exposing it.
        SiteCaptureSettings updated = current.withChanges(
                siteKey,
                captureEnabled != null ? captureEnabled : current.isCaptureEnabled(),
                maxSnapshots != null ? maxSnapshots : current.getMaxSnapshots(),
                captureUser != null ? emptyToNull(captureUser) : current.getCaptureUser(),
                baseUrl != null ? emptyToNull(baseUrl) : current.getBaseUrl());
        try {
            registry.save(updated);
        } catch (IllegalArgumentException | IllegalStateException refused) {
            // The registry's own validation (a line break or backslash in a value, a key it cannot
            // name a file for, a file it refuses to rewrite unread). These messages are written for
            // the administrator; a plain RuntimeException made DefaultGraphQLErrorHandler collapse
            // them to "Internal Server Error(s) while executing query" (issue #30).
            throw new BaseGqlClientException(refused.getMessage(), refused, ErrorType.ValidationError);
        } catch (IOException couldNotWrite) {
            throw new BaseGqlClientException("Could not write the settings for site " + siteKey
                    + ": " + couldNotWrite.getMessage(), couldNotWrite, ErrorType.ExecutionAborted);
        }
        return new GqlSiteSettings(siteKey, true, updated);
    }

    /** Empty means "clear this setting"; see the note in {@link #saveSiteSettings}. */
    private static String emptyToNull(String value) {
        return value.trim().isEmpty() ? null : value.trim();
    }

    /** True when the caller sent a value to apply, as opposed to omitting or clearing the field. */
    private static boolean isPresent(String value) {
        return value != null && !value.trim().isEmpty();
    }

    @GraphQLField
    @GraphQLDescription("Removes this site's settings, returning it to the module defaults."
            + " Requires the siteAdminContentRevisionHistory permission on that site.")
    public boolean deleteSiteSettings(@GraphQLName("siteKey") @GraphQLNonNull String siteKey) {
        SiteSettingsAccess.requireSiteAdmin(siteKey);
        try {
            SiteSettingsAccess.registry().delete(siteKey);
            return true;
        } catch (IllegalArgumentException refused) {
            throw new BaseGqlClientException(refused.getMessage(), refused, ErrorType.ValidationError);
        } catch (IOException couldNotDelete) {
            throw new BaseGqlClientException("Could not remove the settings for site " + siteKey
                    + ": " + couldNotDelete.getMessage(), couldNotDelete, ErrorType.ExecutionAborted);
        }
    }
}
