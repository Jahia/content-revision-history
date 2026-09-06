package org.jahia.modules.revisionhistory;

/**
 * What one site has configured for revision capture.
 *
 * <p>Immutable, and every field has a defined fallback, so a site with no configuration of its own
 * behaves exactly as the module did before per-site settings existed. That matters more than it
 * looks: the alternative is an upgrade that silently changes what gets captured.
 *
 * <p>The capture endpoint can be overridden per site, though it rarely should be. It addresses this
 * node's own HTTP connector, which a site does not have one of, so the node-level
 * {@link CaptureEndpoint} value is the fallback and is right for almost every deployment. It is
 * emphatically NOT the site's public address: a public host rewrites or refuses the
 * {@code /cms/render/...} paths capture asks for, and the symptom is a flat HTTP 404 on every page.
 * Measured: fetching over loopback puts no {@code 127.0.0.1} into a snapshot -- site-relative links
 * stay relative -- so there is no content-correctness reason to reach for the public host.
 *
 * <p>Rate limits are absent, and that one is not overridable: they protect the node, and several
 * sites each staying under their own limit could still overwhelm it together.
 */
public final class SiteCaptureSettings {

    /** Used for any site with no configuration of its own. */
    static final SiteCaptureSettings DEFAULTS = new SiteCaptureSettings(
            null, true, RevisionHistoryConstants.MAX_SNAPSHOTS_PER_PAGE_LANGUAGE, null, null, null);

    private final String siteKey;
    private final boolean captureEnabled;
    private final int maxSnapshots;
    private final String captureUser;
    private final String authorization;
    private final String baseUrl;
    private final java.util.Set<String> excludedProperties;

    SiteCaptureSettings(String siteKey, boolean captureEnabled, int maxSnapshots,
                        String captureUser, String authorization, String baseUrl) {
        this(siteKey, captureEnabled, maxSnapshots, captureUser, authorization, baseUrl,
                java.util.Collections.emptySet());
    }

    SiteCaptureSettings(String siteKey, boolean captureEnabled, int maxSnapshots,
                        String captureUser, String authorization, String baseUrl,
                        java.util.Set<String> excludedProperties) {
        this.siteKey = siteKey;
        this.captureEnabled = captureEnabled;
        this.maxSnapshots = maxSnapshots;
        this.captureUser = captureUser;
        this.authorization = authorization;
        this.baseUrl = baseUrl;
        // Copied and made unmodifiable: an immutable settings object cannot hand out a set a caller
        // could mutate. Insertion order is kept only so logs and toString read predictably.
        this.excludedProperties = excludedProperties == null || excludedProperties.isEmpty()
                ? java.util.Collections.emptySet()
                : java.util.Collections.unmodifiableSet(
                        new java.util.LinkedHashSet<>(excludedProperties));
    }

    /**
     * @return where capture addresses this node for this site, or {@code null} to use the
     *         node-level {@link CaptureEndpoint} setting
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /** @return the site this applies to, or {@code null} for the defaults */
    public String getSiteKey() {
        return siteKey;
    }

    /**
     * @return false to stop capturing this site without uninstalling the module or removing the
     *         mixin from every page
     */
    public boolean isCaptureEnabled() {
        return captureEnabled;
    }

    /** @return how many snapshots to keep per page and language before pruning the oldest */
    public int getMaxSnapshots() {
        return maxSnapshots;
    }

    /**
     * @return the account capture renders as, recorded on each snapshot as provenance, or
     *         {@code null} when capture is anonymous for this site
     */
    public String getCaptureUser() {
        return captureUser;
    }

    /** @return the Authorization header for capture renders, or {@code null} to render anonymously */
    String getAuthorization() {
        return authorization;
    }

    /**
     * @return the names of string properties this site keeps OUT of the {@code .markdown} output,
     *         never null; empty for a site that excludes nothing (the default)
     *
     * <p>The markdown fallback emits every text-bearing string property so nothing is silently lost
     * from the record, and the same output is served at the anonymous {@code .markdown} URL of an
     * opted-in page -- so a property a site keeps out of its templates still reaches an anonymous
     * visitor (GHSA-q67w-prc3-ch5h #3). This opt-out list lets a site trim named properties from
     * both the archive and the response. It is a file-only advanced setting
     * ({@code capture.excludedProperties}), like the capture credential -- preserved verbatim
     * across panel saves, with no GraphQL or panel surface.
     */
    public java.util.Set<String> getExcludedProperties() {
        return excludedProperties;
    }

    /**
     * @return whether a usable credential resolved, without exposing it
     *
     * <p>What callers outside this package are allowed to know. The header itself stays
     * package-private: a GraphQL response is logged, cached and pasted into bug reports, and an
     * accessor that exists is an accessor that eventually gets called.
     */
    public boolean hasResolvedCredential() {
        return authorization != null;
    }

    /**
     * @return the account this site ACTUALLY captures as, or {@code null} when it captures
     *         anonymously
     *
     * <p>Distinct from {@link #getCaptureUser()}, which is only what this site's own file says. A
     * site with no account of its own captures with the module-wide one, and a panel that reported
     * the literal value called such a site anonymous -- telling a site administrator that its
     * snapshots hold only public text when they may hold whatever the module-wide account can read.
     *
     * <p>Delegates so the precedence has exactly one implementation; see
     * {@code GuestMarkdownFetcher.authorizationFor}, which the fetch path uses.
     */
    public String getEffectiveCaptureUser() {
        return GuestMarkdownFetcher.effectiveCaptureUser(this);
    }

    /** @return whether the credential this site actually captures with resolved */
    public boolean hasEffectiveCredential() {
        return GuestMarkdownFetcher.authorizationFor(this) != null;
    }

    /**
     * @return a copy with the editable fields replaced, carrying the resolved credential across
     *
     * <p>The credential is deliberately not a parameter. It is resolved from a secret file whose
     * permissions an administrator controls, so it is not something an editing caller supplies --
     * and passing it through a public signature would put it somewhere it could be logged.
     *
     * <p>The site key IS a parameter, and must be: the common case is configuring a site that has
     * none yet, where the caller starts from {@link #DEFAULTS}, whose key is null. Carrying that
     * null through would write a file with no siteKey and fail its own validation, on the very
     * first save of every site.
     */
    public SiteCaptureSettings withChanges(String siteKey, boolean captureEnabled, int maxSnapshots,
                                           String captureUser, String baseUrl) {
        // The credential is carried across only while it still belongs to the same account. It is
        // resolved from a secret file, and this method cannot resolve one, so keeping the old
        // header after the account changed made the object -- and the mutation's own response --
        // report a resolved credential for an account no secret was ever checked against. An
        // administrator switching to an account whose secret is not provisioned yet was told the
        // switch had taken effect. Dropping it means "unknown until the file is re-read", which is
        // the truth; FileInstall delivers the real answer moments later.
        String carried = java.util.Objects.equals(this.captureUser, captureUser)
                ? authorization : null;
        // excludedProperties is not a panel field; carry it across so the object stays truthful
        // until FileInstall re-delivers the (preserved) capture.excludedProperties line.
        return new SiteCaptureSettings(siteKey, captureEnabled, maxSnapshots, captureUser,
                carried, baseUrl, excludedProperties);
    }

    @Override
    public String toString() {
        // No secret and no header: this ends up in logs.
        return "SiteCaptureSettings[siteKey=" + siteKey
                + ", captureEnabled=" + captureEnabled
                + ", maxSnapshots=" + maxSnapshots
                + ", captureUser=" + (captureUser == null ? "(anonymous)" : captureUser)
                + ", baseUrl=" + (baseUrl == null ? "(node default)" : baseUrl)
                + ", excludedProperties=" + excludedProperties + ']';
    }
}
