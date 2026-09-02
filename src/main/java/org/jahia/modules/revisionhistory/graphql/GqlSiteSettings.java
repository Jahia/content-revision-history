package org.jahia.modules.revisionhistory.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import org.jahia.modules.revisionhistory.SiteCaptureSettings;

/** One site's capture settings, as the site-settings panel reads them. */
@GraphQLName("CrhSiteSettings")
@GraphQLDescription("Revision capture settings for one site")
public class GqlSiteSettings {

    private final String siteKey;
    private final boolean configured;
    private final SiteCaptureSettings settings;

    public GqlSiteSettings(String siteKey, boolean configured, SiteCaptureSettings settings) {
        this.siteKey = siteKey;
        this.configured = configured;
        this.settings = settings;
    }

    @GraphQLField
    @GraphQLDescription("The site these settings apply to")
    public String getSiteKey() {
        return siteKey;
    }

    @GraphQLField
    @GraphQLDescription("False when this site has no settings of its own and is using the module defaults")
    public boolean isConfigured() {
        return configured;
    }

    @GraphQLField
    @GraphQLDescription("Whether pages on this site are captured at all")
    public boolean isCaptureEnabled() {
        return settings.isCaptureEnabled();
    }

    @GraphQLField
    @GraphQLDescription("Snapshots kept per page and language before the oldest are pruned")
    public int getMaxSnapshots() {
        return settings.getMaxSnapshots();
    }

    /**
     * This site's OWN value, deliberately, because the panel binds it to an editable field.
     *
     * <p>Returning the effective account here would have been a silent downgrade: a site inheriting
     * the module-wide account would display that name, and saving anything at all in the panel
     * would write it as this site's own {@code capture.user}. Since a site that names an account
     * uses that account's credential and no other, and no per-site secret exists for it, capture
     * for that site would then fall to anonymous -- with the panel showing the same name
     * throughout. {@link #getEffectiveCaptureUser} is what to display; this is what to edit.
     */
    @GraphQLField
    @GraphQLDescription("The account configured for THIS site, or null when it has none of its own."
            + " This is the editable value; see effectiveCaptureUser for what capture actually uses.")
    public String getCaptureUser() {
        return settings.getCaptureUser();
    }

    @GraphQLField
    @GraphQLDescription("The account capture actually renders as for this site: this site's own if"
            + " it has one, otherwise the module-wide account. Read-only.")
    public String getEffectiveCaptureUser() {
        return settings.getEffectiveCaptureUser();
    }

    /**
     * Never the secret, and never a resolved Authorization header. A panel has no use for either,
     * and a GraphQL response is logged, cached and copied into bug reports.
     */
    @GraphQLField
    @GraphQLDescription("True when a usable secret resolved, so capture can authenticate."
            + " The secret itself is never returned.")
    public boolean isCredentialResolved() {
        return settings.hasEffectiveCredential();
    }

    @GraphQLField
    @GraphQLDescription("Where capture addresses this node for this site, or null to use the node default")
    public String getBaseUrl() {
        return settings.getBaseUrl();
    }
}
