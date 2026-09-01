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
        // persisted verbatim and echoed back, so the panel displayed it as the applied setting;
        // only when FileInstall re-parsed the file did the positiveInt helper notice, and it then
        // substituted the module default and logged a warning that never reached the UI, leaving
        // the operator believing a value the running system was not using.
        if (maxSnapshots != null && maxSnapshots < 1) {
            throw new BaseGqlClientException(
                    "maxSnapshots must be at least 1; " + maxSnapshots + " would keep no history"
                    + " at all. Leave it unset to use the module default.",
                    ErrorType.ValidationError);
        }
        SiteSettingsRegistry registry = SiteSettingsAccess.registry();
        SiteCaptureSettings current = registry.forSite(siteKey);

        // Absent means "leave as it is", not "clear it". A panel that only edits one field must not
        // silently reset the rest, and a null from GraphQL cannot be told apart from an omission.
        // The secret is never written from here: it belongs in a file whose permissions an
        // administrator controls, and a GraphQL argument would end up in request logs. withChanges
        // carries the already-resolved credential across without exposing it.
        SiteCaptureSettings updated = current.withChanges(
                siteKey,
                captureEnabled != null ? captureEnabled : current.isCaptureEnabled(),
                maxSnapshots != null ? maxSnapshots : current.getMaxSnapshots(),
                captureUser != null ? captureUser : current.getCaptureUser(),
                baseUrl != null ? baseUrl : current.getBaseUrl());
        try {
            registry.save(updated);
        } catch (IOException couldNotWrite) {
            throw new RuntimeException("Could not write the settings for site " + siteKey
                    + ": " + couldNotWrite.getMessage(), couldNotWrite);
        }
        return new GqlSiteSettings(siteKey, true, updated);
    }

    @GraphQLField
    @GraphQLDescription("Removes this site's settings, returning it to the module defaults."
            + " Requires the siteAdminContentRevisionHistory permission on that site.")
    public boolean deleteSiteSettings(@GraphQLName("siteKey") @GraphQLNonNull String siteKey) {
        SiteSettingsAccess.requireSiteAdmin(siteKey);
        try {
            SiteSettingsAccess.registry().delete(siteKey);
            return true;
        } catch (IOException couldNotDelete) {
            throw new RuntimeException("Could not remove the settings for site " + siteKey
                    + ": " + couldNotDelete.getMessage(), couldNotDelete);
        }
    }
}
