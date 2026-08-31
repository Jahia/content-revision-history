package org.jahia.modules.revisionhistory.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import org.jahia.modules.revisionhistory.SiteSettingsRegistry;

/**
 * Everything this module adds to the root query, under one field.
 *
 * <p>One namespaced container rather than fields on Query itself. Two bundles registering the same
 * global field make DXGraphQLProvider refuse the duplicate and the WHOLE schema fails to build, not
 * just the offending module -- so a flat field is a hazard to every other module on the platform,
 * not only to this one.
 */
@GraphQLName("CrhQuery")
@GraphQLDescription("Content Revision History")
public class RevisionHistoryQuery {

    @GraphQLField
    @GraphQLDescription("Revision capture settings for one site."
            + " Requires the siteAdminContentRevisionHistory permission on that site.")
    public GqlSiteSettings getSiteSettings(
            @GraphQLName("siteKey") @GraphQLNonNull String siteKey) {
        SiteSettingsAccess.requireSiteAdmin(siteKey);
        SiteSettingsRegistry registry = SiteSettingsAccess.registry();
        return new GqlSiteSettings(siteKey, registry.isConfigured(siteKey), registry.forSite(siteKey));
    }
}
