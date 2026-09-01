package org.jahia.modules.revisionhistory.graphql;

import graphql.ErrorType;
import org.jahia.modules.graphql.provider.dxm.BaseGqlClientException;
import org.jahia.modules.revisionhistory.SiteSettingsRegistry;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The authorisation gate for every site-settings operation, in one place.
 *
 * <p>Deliberately not per resolver: an unguarded resolver added later is the failure this shape is
 * meant to make hard, and a single method is easier to audit than four call sites.
 *
 * <p>The permission is evaluated on {@code /sites/<siteKey>} against the CURRENT user's own session.
 * Measured on 8.2.x for the parent {@code site-admin}: root has it, an editor with {@code jcr:write}
 * on the same site does not -- so writing content does not imply administering the module's capture
 * settings, which is the distinction that matters here.
 */
final class SiteSettingsAccess {

    private static final Logger logger = LoggerFactory.getLogger(SiteSettingsAccess.class);

    /**
     * The module's own permission, declared in {@code src/main/import/permissions.xml} as a child of
     * {@code site-admin}. Jahia permissions are hierarchical, so a role granting the whole
     * {@code site-admin} group grants this one too and no role has to be edited on upgrade; naming
     * it separately is what lets an administrator revoke JUST this module without revoking site
     * administration.
     */
    private static final String PERMISSION = "siteAdminContentRevisionHistory";

    private SiteSettingsAccess() {
    }

    /**
     * @throws BaseGqlClientException when the module is not running, rather than reporting settings
     *         that nothing is applying
     *
     * <p>A client error for the same reason {@link #requireSiteAdmin} is one: this is reachable in
     * the ordinary course of events -- an administrator opening the panel during a bundle restart
     * -- and a raw {@code IllegalStateException} reaches them as "Internal Server Error(s) while
     * executing query", which reads as a bug in the module rather than "try again in a moment".
     */
    static SiteSettingsRegistry registry() {
        SiteSettingsRegistry registry = SiteSettingsRegistry.active();
        if (registry == null) {
            throw new BaseGqlClientException(
                    "Content Revision History is not running on this node, so its settings cannot"
                    + " be read or written here. If the module is being restarted, retry shortly.",
                    ErrorType.ExecutionAborted);
        }
        return registry;
    }

    /**
     * @throws BaseGqlClientException when the current user may not administer this site
     *
     * <p>Thrown as a CLIENT error rather than a plain SecurityException. A SecurityException
     * reaches the caller as "Internal Server Error(s) while executing query", which tells an
     * operator nothing and reads as a bug in the module rather than a refusal. The provider's own
     * GqlAccessDeniedException would be the exact fit, but its constructor is package-private and
     * therefore not available to a module.
     */
    static void requireSiteAdmin(String siteKey) {
        if (siteKey == null || siteKey.trim().isEmpty()) {
            throw new IllegalArgumentException("siteKey is required");
        }
        String path = "/sites/" + siteKey;
        try {
            JCRSessionWrapper viewer = JCRSessionFactory.getInstance().getCurrentUserSession();
            if (viewer != null && viewer.getNode(path).hasPermission(PERMISSION)) {
                return;
            }
        } catch (Exception denied) {
            // A site the caller cannot even read reports PathNotFoundException rather than
            // AccessDenied, so an exception here is a refusal like any other and must not leak
            // whether the site exists.
            logger.debug("Refusing site settings access to {}", path, denied);
        }
        throw new BaseGqlClientException(
                "The " + PERMISSION + " permission on " + path + " is required",
                ErrorType.DataFetchingException);
    }
}
