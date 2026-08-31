import gql from 'graphql-tag';

/**
 * Does the site administration panel actually APPEAR, and does it save?
 *
 * This file exists because of a failure a GraphQL test could not have caught. The API worked, the
 * bundle built, the remote loaded, the browser console was clean -- and no menu entry ever
 * appeared. The cause was ordering, not correctness: init registered the adminRoute directly, but
 * the app shell calls a remote's init BEFORE its own extension points exist, so the route went into
 * a registry the navigation had not read yet and was dropped in silence. Nothing that inspects the
 * server can see that; only loading the shell and looking at its navigation can.
 *
 * Mutation-checked, and the result is worth recording: moving the registry.add('adminRoute', ...)
 * call out of the 'jahiaApp-init' callback and back into init() fails ONLY the first test. Opening
 * the panel by its URL still works, because the router reads the registry when it renders, while
 * the navigation tree was already built. So the deep-link test is not a substitute for the
 * navigation test: the bug this file exists for is 'registered but unreachable', and only walking
 * the navigation the way an administrator does can see it.
 */
describe('Site administration panel for revision history', () => {
    const siteKey = 'digitall';

    // The route key registered by src/javascript/SiteSettings/register.jsx. The shell builds the
    // URL from it, so a rename here is a broken bookmark for every administrator.
    const routeKey = 'contentRevisionHistorySettings';
    const adminRoot = `/jahia/administration/${siteKey}`;
    const panelUrl = `${adminRoot}/${routeKey}`;

    // The shell loads every remote over the network before it can render a nav entry.
    const shellTimeoutMs = 90000;

    const PANEL = '[data-sel-role="crh-site-settings"]';
    const MAX_SNAPSHOTS = '[data-sel-role="crh-max-snapshots"]';
    const BASE_URL = '[data-sel-role="crh-base-url"]';
    const SAVE = '[data-sel-role="crh-save"]';
    const RESET = '[data-sel-role="crh-reset"]';

    const settingsQuery = gql`
        query crhSettings($siteKey: String!) {
            contentRevisionHistory {
                siteSettings(siteKey: $siteKey) {
                    configured
                    maxSnapshots
                    baseUrl
                }
            }
        }
    `;

    /** Clears any per-site settings so a test never inherits the previous one's writes. */
    const resetSettings = () => cy.apollo({
        mutation: gql`
            mutation crhReset($siteKey: String!) {
                contentRevisionHistory { deleteSiteSettings(siteKey: $siteKey) }
            }
        `,
        variables: {siteKey},
        errorPolicy: 'ignore'
    });

    before(() => {
        cy.login();
        resetSettings();
    });

    after(() => {
        cy.login();
        resetSettings();
    });

    it('lists the panel in the site administration navigation', () => {
        cy.login();
        cy.visit('/jahia/administration');
        // The Sites branch starts collapsed and holds no anchor, so it has to be opened the way an
        // administrator opens it. Deep-linking instead would prove the route resolves but not that
        // anyone could ever find it.
        cy.contains('Sites', {timeout: shellTimeoutMs}).click({force: true});
        // Asserted on the translated label, not on a CSS class: the label is what an administrator
        // looks for, and it also proves the i18n namespace resolved. A raw key would read
        // 'content-revision-history:settings.label' and fail here.
        cy.contains('Revision history', {timeout: shellTimeoutMs}).should('be.visible');
    });

    it('renders the settings form when the panel is opened', () => {
        cy.login();
        cy.visit(panelUrl);
        cy.get(PANEL, {timeout: shellTimeoutMs}).should('exist');
        cy.get(MAX_SNAPSHOTS).should('exist');
        cy.get(BASE_URL).should('exist');
    });

    it('saves a per-site value and reports the site as configured', () => {
        cy.login();
        cy.visit(panelUrl);
        cy.get(PANEL, {timeout: shellTimeoutMs}).should('exist');

        cy.get(MAX_SNAPSHOTS).clear().type('7');
        cy.get(BASE_URL).clear().type('http://127.0.0.1:8080');
        cy.get(SAVE).click();

        // Asserted through the API rather than the DOM: the point of the panel is that it WRITES
        // the per-site configuration the capture path reads, and only the server can confirm that.
        cy.waitUntil(
            () => cy.apollo({query: settingsQuery, variables: {siteKey}})
                .then(({data}) => data?.contentRevisionHistory?.siteSettings?.maxSnapshots === 7),
            {timeout: 30000, interval: 1000, errorMsg: 'the panel never persisted maxSnapshots'}
        );

        cy.apollo({query: settingsQuery, variables: {siteKey}}).then(({data}) => {
            const s = data.contentRevisionHistory.siteSettings;
            expect(s.configured, 'site now has its own settings').to.be.true;
            expect(s.baseUrl).to.equal('http://127.0.0.1:8080');
        });
    });

    it('returns the site to the module defaults when reset', () => {
        cy.login();
        cy.visit(panelUrl);
        cy.get(PANEL, {timeout: shellTimeoutMs}).should('exist');
        cy.get(RESET).click();

        cy.waitUntil(
            () => cy.apollo({query: settingsQuery, variables: {siteKey}})
                .then(({data}) => data?.contentRevisionHistory?.siteSettings?.configured === false),
            {timeout: 30000, interval: 1000, errorMsg: 'the panel never cleared the site settings'}
        );
    });

    // Every assertion above runs as root, which is exactly how the jContent visibility bug survived
    // two releases. An editor holds jcr:write on the site and still must not administer capture:
    // the account the module authenticates with is configured here.
    it('hides the panel from an editor who cannot administer the site', () => {
        cy.login('mathias', 'password');
        cy.visit(adminRoot, {failOnStatusCode: false});
        // Waits for the shell to finish loading its remotes before asserting an absence, or this
        // passes simply because nothing has rendered yet.
        cy.get('body', {timeout: shellTimeoutMs}).should('be.visible');
        cy.wait(5000);
        cy.contains('Revision history').should('not.exist');
    });

    it('refuses the settings API to an editor even when the URL is typed by hand', () => {
        // The hidden menu entry is not the security boundary; this is.
        cy.login('mathias', 'password');
        // cy.request rather than cy.apollo: the refusal arrives as HTTP 200 carrying an errors
        // array, and the apollo helper does not hand that array back to the caller.
        cy.request({
            method: 'POST',
            url: '/modules/graphql',
            headers: {'Content-Type': 'application/json', Origin: Cypress.config('baseUrl')},
            body: {query: `{contentRevisionHistory{siteSettings(siteKey:"${siteKey}"){siteKey}}}`},
            failOnStatusCode: false
        }).then(res => {
            const errors = res.body.errors || [];
            expect(errors.length, 'an editor must be refused').to.be.greaterThan(0);
            expect(errors[0].message).to.contain('siteAdminContentRevisionHistory');
            expect(res.body.data?.contentRevisionHistory?.siteSettings,
                'and must be handed no settings at all').to.be.null;
        });
    });
});
