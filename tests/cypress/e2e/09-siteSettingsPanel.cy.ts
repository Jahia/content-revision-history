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

        cy.get(MAX_SNAPSHOTS).clear();
        cy.get(MAX_SNAPSHOTS).type('7');
        cy.get(BASE_URL).clear();
        cy.get(BASE_URL).type('http://127.0.0.1:8080');
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

    // The Save button lives in the page header, which is where the shell puts panel actions but is
    // also a long way from the form and greyed out until something changes. It was missed on a real
    // instance, so the form can also be committed from the keyboard without leaving the field.
    it('saves with Ctrl+Enter from inside a field', () => {
        cy.login();
        cy.visit(panelUrl);
        cy.get(PANEL, {timeout: shellTimeoutMs}).should('exist');

        // Typed and committed without the pointer ever moving, which is the point of the shortcut.
        cy.get(MAX_SNAPSHOTS).clear();
        cy.get(MAX_SNAPSHOTS).type('13{ctrl}{enter}');

        cy.waitUntil(
            () => cy.apollo({query: settingsQuery, variables: {siteKey}})
                .then(({data}) => data?.contentRevisionHistory?.siteSettings?.maxSnapshots === 13),
            {timeout: 30000, interval: 1000, errorMsg: 'Ctrl+Enter did not save the form'}
        );
    });

    it('advertises the shortcut rather than hiding it', () => {
        cy.login();
        cy.visit(panelUrl);
        cy.get(PANEL, {timeout: shellTimeoutMs}).should('exist');
        // A shortcut nobody can discover helps only the person who wrote it.
        cy.get('[data-sel-role="crh-shortcut-hint"]').should('contain.text', 'Ctrl+Enter');
    });

    it('does nothing on Ctrl+Enter when there is nothing to save', () => {
        cy.login();
        // This test asserts on an ABSENCE, so it has to own its starting state. The preceding tests
        // leave the site configured, and without this the assertion reads their writes and fails
        // for a reason that has nothing to do with the shortcut.
        resetSettings();
        cy.visit(panelUrl);
        cy.get(PANEL, {timeout: shellTimeoutMs}).should('exist');
        cy.get(MAX_SNAPSHOTS).should('exist');

        // No edit has been made, so the shortcut must not write. Otherwise it would create a
        // per-site configuration for a site that had deliberately been left on the defaults.
        // Watching the network proves the absence; a fixed wait only proves that nothing had
        // happened YET, and would pass under CI load even if the shortcut did write a moment later.
        cy.intercept('POST', '**/modules/graphql', req => {
            const ops = Array.isArray(req.body) ? req.body : [req.body];
            if (ops.some(o => typeof o?.query === 'string' && o.query.includes('saveSiteSettings'))) {
                throw new Error('Ctrl+Enter wrote settings when nothing had been edited');
            }

            req.continue();
        });

        cy.get('body').type('{ctrl}{enter}');
        // Round-trip a real query so any save the shortcut fired would already have been sent.
        cy.apollo({query: settingsQuery, variables: {siteKey}});
        cy.apollo({query: settingsQuery, variables: {siteKey}}).then(({data}) => {
            expect(data.contentRevisionHistory.siteSettings.configured,
                'an unchanged form must not write a configuration').to.be.false;
        });
    });

    // Reported from a real instance: the panel would not save. It was not the button. Apollo
    // resolves a mutation whose response carries a GraphQL errors array instead of rejecting it, so
    // the old .then() ran on failure exactly as on success: it cleared the draft and refetched,
    // which discarded what had been typed, greyed out Save and showed nothing at all. The write
    // looked like it had worked and had not.
    it('reports a refused write instead of discarding it', () => {
        cy.login();
        cy.intercept('POST', '**/modules/graphql', req => {
            // The shell's Apollo client BATCHES: the body is an array of operations, so a naive
            // req.body.query is undefined and never matches. Getting this wrong makes the intercept
            // silently pass everything through, and the test then proves nothing while looking green.
            const ops = Array.isArray(req.body) ? req.body : [req.body];
            const isSave = ops.some(o => typeof o?.query === 'string' && o.query.includes('saveSiteSettings'));
            if (!isSave) {
                req.continue();
                return;
            }

            // The shape a real refusal takes: HTTP 200 carrying errors, not a transport failure.
            const refusal = {
                data: {contentRevisionHistory: {saveSiteSettings: null}},
                errors: [{message: 'Could not write the settings for site digitall'}]
            };
            req.reply({
                statusCode: 200,
                body: Array.isArray(req.body) ? [refusal] : refusal
            });
        });

        cy.visit(panelUrl);
        cy.get(PANEL, {timeout: shellTimeoutMs}).should('exist');
        cy.get(MAX_SNAPSHOTS).clear();
        cy.get(MAX_SNAPSHOTS).type('42');
        cy.get(SAVE).click();

        cy.get('[data-sel-role="crh-write-error"]', {timeout: 20000})
            .should('contain.text', 'Could not write the settings');
        // The edit is the administrator's work: a failed write must not throw it away, and Save has
        // to stay usable so the write can be retried once the cause is fixed.
        cy.get(MAX_SNAPSHOTS).should('have.value', '42');
        cy.get(SAVE).should('not.be.disabled');
    });

    // Reported from a real instance: "the text is stuck to the left and top". The panel was built
    // from plain divs, so it had none of the page furniture the shell's own panels get. It is now
    // moonstone's LayoutContent with hasPadding, and this guards the two ways that regresses:
    // losing the padding, and letting the form grow until the header actions leave the screen.
    it('lays the form out with padding and without horizontal overflow', () => {
        cy.login();
        cy.visit(panelUrl);
        cy.get(PANEL, {timeout: shellTimeoutMs}).should('exist');
        cy.get(BASE_URL).should('exist');

        cy.window().then(win => {
            const doc = win.document.documentElement;
            const box = (sel: string) => win.document.querySelector(sel)!.getBoundingClientRect();
            const panel = box(PANEL);
            const field = box('#crh-field-capture-enabled');

            expect(doc.scrollWidth, 'the page must not scroll sideways')
                .to.be.at.most(doc.clientWidth);
            expect(field.left, 'the form must be inset from the panel edge, not flush against it')
                .to.be.greaterThan(panel.left);
            expect(field.top, 'and must sit below the header, not at the top edge')
                .to.be.greaterThan(panel.top);
            // A settings form read at full monitor width is unreadable, and an unconstrained one
            // also pushes the header actions off screen.
            expect(field.width, 'the form keeps a readable measure').to.be.at.most(760);

            [SAVE, RESET].forEach(sel => {
                const b = box(sel);
                expect(b.right, `${sel} must be on screen`).to.be.at.most(doc.clientWidth);
                expect(b.left, `${sel} must be on screen`).to.be.at.least(0);
            });
        });
    });

    // Every assertion above runs as root, which is exactly how the jContent visibility bug survived
    // two releases. An editor holds jcr:write on the site and still must not administer capture:
    // the account the module authenticates with is configured here.
    it('hides the panel from an editor who cannot administer the site', () => {
        cy.login('mathias', 'password');
        cy.visit(adminRoot, {failOnStatusCode: false});
        // Waits for the shell to finish loading its remotes before asserting an absence, or this
        // passes simply because nothing has rendered yet.
        // Wait for a signal that does NOT depend on privilege: window.jahia exists only once the
        // app shell has booted and loaded its remotes, for any user. An earlier version waited for
        // the 'Administration' nav entry, which a mere editor never sees at all -- so it timed out
        // on exactly the user this test is about. A fixed wait would have been worse still: it
        // would pass simply because nothing had rendered yet.
        cy.window({timeout: shellTimeoutMs}).should(win => {
            expect((win as unknown as {jahia?: unknown}).jahia, 'the app shell must have booted')
                .to.not.be.undefined;
        });
        cy.contains('Revision history').should('not.exist');
        // And the absence must be a permission decision, not a crash that stopped the render.
        cy.then(() => {
            expect(Cypress.env('uncaughtErrors') || [],
                'the panel must be hidden by permission, not by a page error').to.be.empty;
        });
    });

    it('refuses the settings API to an editor even when the URL is typed by hand', () => {
        // The hidden menu entry is not the security boundary; this is.
        cy.login('mathias', 'password');
        // Cy.request rather than cy.apollo: the refusal arrives as HTTP 200 carrying an errors
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
