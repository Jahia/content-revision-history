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
 * <p>The permission checked is {@code site-admin} on {@code /sites/<siteKey>}, evaluated against the
 * CURRENT user's own session. Measured on 8.2.x: root has it, an editor with {@code jcr:write} on the
 * same site does not -- so writing content does not imply administering the module's capture
 * settings, which is the distinction that matters here.
 */
final class SiteSettingsAccess {

    private static final Logger logger = LoggerFactory.getLogger(SiteSettingsAccess.class);

    private static final String SITE_ADMIN = "site-admin";

    private SiteSettingsAccess() {
    }

    /**
     * @throws IllegalStateException when the module is not running, rather than reporting settings
     *         that nothing is applying
     */
    static SiteSettingsRegistry registry() {
        SiteSettingsRegistry registry = SiteSettingsRegistry.active();
        if (registry == null) {
            throw new IllegalStateException(
                    "Content Revision History is not running on this node, so its settings cannot be"
                    + " read or written here.");
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
            if (viewer != null && viewer.getNode(path).hasPermission(SITE_ADMIN)) {
                return;
            }
        } catch (Exception denied) {
            // A site the caller cannot even read reports PathNotFoundException rather than
            // AccessDenied, so an exception here is a refusal like any other and must not leak
            // whether the site exists.
            logger.debug("Refusing site settings access to {}", path, denied);
        }
        throw new BaseGqlClientException(
                "The " + SITE_ADMIN + " permission on " + path + " is required",
                ErrorType.DataFetchingException);
    }
}
