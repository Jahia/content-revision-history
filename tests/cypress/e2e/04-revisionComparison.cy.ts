import gql from 'graphql-tag';
import {addNode, deleteNode, enableModule, publishAndWaitJobEnding, removeMixins, setNodeProperty} from '@jahia/cypress';

/**
 * Coverage for the two halves of the module actually being joined up, and for the comparison
 * that join makes possible.
 *
 * Before this file the snapshot store and the editorial revision list were unconnected: nothing
 * ever wrote the entry/snapshot link, so every "Compare" control was a dead end. Nothing failed,
 * because nothing asserted it -- which is the same blind spot that previously let a blank Compare
 * button ship twice with the suite green. These tests assert the join itself, not just its parts.
 *
 * Order matters within this file: each test builds on the state the previous one published. That
 * is deliberate -- binding is defined by what was already bound when a capture ran, so it can
 * only be tested as a sequence. The file uses its own scratch page and never touches the pages
 * the other specs rely on.
 */
describe('Revision comparison (entry binding + diff viewer)', () => {
    const siteKey = 'digitall';
    const language = 'en';
    const revisionedMixin = 'jmix:publiclyRevisioned';
    const homePath = `/sites/${siteKey}/home`;
    const pagePath = `${homePath}/crh-e2e-compare`;
    const areaPath = `${pagePath}/area-main`;
    const historyPath = `${areaPath}/history`;
    const textPath = `${areaPath}/policyText`;
    const historyRoot = `/sites/${siteKey}/contents/revision-history`;

    const captureTimeoutMs = 60000;
    const pollIntervalMs = 1000;
    /** Comfortably past MIN_CAPTURE_INTERVAL_MILLIS, so a publish is never RATE_LIMITED. */
    const captureRateLimitGraceMs = 2500;

    interface ApolloResult<T> {
        data?: T
        errors?: Array<{message: string}>
    }

    interface AddNodeQueryData {
        jcr: { addNode: { uuid: string } }
    }

    interface SnapshotsQueryData {
        jcr: {
            nodeByPath?: {
                children: {
                    nodes: Array<{
                        name: string
                        entryRefs?: { values: string[] } | null
                    }>
                }
            } | null
        }
    }

    const nodeUuidQuery = gql`query($path: String!) { jcr { nodeByPath(path: $path) { uuid } } }`;

    const liveChildrenQuery = gql`
        query($path: String!) {
            jcr(workspace: LIVE) {
                nodeByPath(path: $path) { children { nodes { name } } }
            }
        }
    `;

    const setBooleanMutation = gql`
        mutation($path: String!, $name: String!, $value: String!) {
            jcr {
                mutateNode(pathOrId: $path) {
                    mutateProperty(name: $name) { setValue(value: $value, type: BOOLEAN) }
                }
            }
        }
    `;

    const reorderMutation = gql`
        mutation($path: String!, $names: [String]!) {
            jcr { mutateNode(pathOrId: $path) { reorderChildren(names: $names) } }
        }
    `;

    const snapshotsQuery = gql`
        query($path: String!) {
            jcr(workspace: EDIT) {
                nodeByPath(path: $path) {
                    children {
                        nodes {
                            name
                            entryRefs: property(name: "crh:entryRefs") { values }
                        }
                    }
                }
            }
        }
    `;

    let pageUuid = '';
    let firstEntryUuid = '';
    let secondEntryUuid = '';
    let thirdEntryUuid = '';
    let lastPublishAt = 0;

    const folderPath = () => `${historyRoot}/${pageUuid}/${language}`;

    const getUuid = (path: string): Cypress.Chainable<string> =>
        cy.apollo({query: nodeUuidQuery, variables: {path}}).then((result: ApolloResult<{jcr: {nodeByPath?: {uuid: string}}}>) => {
            const uuid = result.data?.jcr?.nodeByPath?.uuid;
            expect(uuid, `${path} must resolve to a uuid`).to.be.a('string').and.not.be.empty;
            return uuid as string;
        });

    /** Entry uuid -> the snapshot node bound to it. */
    const bindings = (): Cypress.Chainable<Record<string, string>> =>
        cy.apollo({query: snapshotsQuery, variables: {path: folderPath()}}).then((result: ApolloResult<SnapshotsQueryData>) => {
            const map: Record<string, string> = {};
            for (const node of result.data?.jcr?.nodeByPath?.children.nodes ?? []) {
                for (const entryUuid of node.entryRefs?.values ?? []) {
                    map[entryUuid] = node.name;
                }
            }

            return map;
        });

    const pollUntil = <T>(fetch: () => Cypress.Chainable<T>, predicate: (value: T) => boolean, errorMsg: string): Cypress.Chainable<T> =>
        cy
            .waitUntil<T | false>(() => fetch().then(value => (predicate(value) ? value : false)), {
                timeout: captureTimeoutMs, interval: pollIntervalMs, errorMsg, verbose: true
            })
            // `as Chainable<T>`: waitUntil's signature admits `false` because that is what the
            // predicate returns while polling, but it THROWS on timeout rather than resolving
            // false, so no caller can observe it.
            .then(value => value as T) as Cypress.Chainable<T>;

    /** Paces publishes past the module's per-page-and-language rate limiter. */
    const publishTriggeringCapture = (): Cypress.Chainable<boolean> => {
        const waitMs = Math.max(0, captureRateLimitGraceMs - (Date.now() - lastPublishAt));

        return cy
            .waitUntil<boolean>(() => Date.now() - lastPublishAt >= captureRateLimitGraceMs, {
                timeout: waitMs + 5000, interval: 100,
                errorMsg: 'could not pace this publish past the module\'s capture rate limiter'
            })
            .then(() => {
                publishAndWaitJobEnding(pagePath, [language]);
                return cy.wrap(true, {log: false}).then(published => {
                    lastPublishAt = Date.now();
                    return published;
                });
            });
    };

    /**
     * Renders the LIVE page as the logged-in user.
     *
     * NOT as the public. `beforeEach` logs in, `cy.request` sends the browser's cookies, so every
     * render below is an EDITOR looking at a public page. That is worth knowing before adding
     * anything here: it is what let a defect that broke the comparison for every anonymous
     * visitor pass 31 of 31 tests in this file, repeatedly. Use `renderLiveAsGuest` to assert
     * anything about the public surface.
     */
    const renderLive = (query = ''): Cypress.Chainable<string> =>
        cy.request<string>({url: `/cms/render/live/${language}${pagePath}.html${query}`}).then(r => r.body);

    /**
     * Renders the LIVE page as a genuinely anonymous visitor, and proves it is one.
     *
     * The proof is not decoration. Dropping the session is invisible in the result -- a request
     * that quietly stayed authenticated returns exactly the same HTML as this one is supposed to
     * on a correct build -- so a guest test with no precondition passes just as well when it is
     * not actually a guest test, which is the failure mode this whole helper exists to answer.
     * Jahia does not grant `jcr:read_default` to `guest`, so an anonymous principal gets 404 on
     * the edit workspace and an authenticated one gets 200: that difference is the assertion.
     */
    const renderLiveAsGuest = (query = ''): Cypress.Chainable<string> =>
        cy.logout().then(() =>
            cy
                .request({
                    url: `/cms/render/default/${language}${pagePath}.html`,
                    failOnStatusCode: false
                })
                .then(editWorkspace => {
                    expect(
                        editWorkspace.status,
                        'this request is still authenticated, so nothing below is about the ' +
                            'public surface; the edit workspace answered instead of refusing'
                    ).to.eq(404);
                    return cy
                        .request<string>({
                            url: `/cms/render/live/${language}${pagePath}.html${query}`
                        })
                        .then(r => r.body);
                })
        );

    const setCollapsedByDefault = (value: boolean) =>
        cy
            .apollo({
                mutation: setBooleanMutation,
                variables: {path: historyPath, name: 'collapsedByDefault', value: String(value)}
            })
            .then((result: ApolloResult<unknown>) => {
                expect(result.errors, 'collapsedByDefault must be settable').to.be.undefined;
            });

    /**
     * Regression test for `orderable` on crh:revisionHistory, kept as a helper because it is the
     * exact call Jackrabbit rejected before the CND declared it: "child node ordering not
     * supported". Extending jmix:list does NOT confer orderability.
     *
     * Rendered order no longer depends on this -- it is derived from revisionDate now -- but
     * document order is still the tie-breaker for revisions sharing a date, so the capability has
     * to keep working.
     */
    const reorderNewestFirst = (names: string[]) =>
        cy
            .apollo({mutation: reorderMutation, variables: {path: historyPath, names}})
            .then((result: ApolloResult<unknown>) => {
                expect(result.errors, 'crh:revisionHistory must accept child reordering').to.be.undefined;
            });

    const addEntry = (name: string, label: string, summary: string) =>
        addNode({
            parentPathOrId: historyPath,
            primaryNodeType: 'crh:revisionEntry',
            name,
            properties: [
                {name: 'revisionLabel', value: label},
                {name: 'revisionDate', value: new Date().toISOString(), type: 'DATE'},
                {name: 'changeType', value: 'substantive'},
                {name: 'summary', value: summary, language}
            ]
        });

    before(() => {
        cy.login();
        enableModule('content-revision-history', siteKey);

        addNode({
            parentPathOrId: homePath,
            primaryNodeType: 'jnt:page',
            name: 'crh-e2e-compare',
            properties: [
                {name: 'jcr:title', value: 'CRH e2e - comparison', language},
                // 'home', not 'default'. The digitall template set has no template called
                // 'default', and an unresolvable template makes the LIVE .html render return
                // 404 -- while the .markdown render (which uses this module's own views and no
                // page template) still succeeds. Every assertion in this file that reads the
                // rendered page depends on this value being a template the site actually has.
                {name: 'j:templateName', value: 'home'}
            ],
            mixins: [revisionedMixin]
        })
            .then((result: ApolloResult<AddNodeQueryData>) => {
                expect(result.errors, 'the scratch page must be creatable').to.be.undefined;
                return addNode({parentPathOrId: pagePath, primaryNodeType: 'jnt:contentList', name: 'area-main'});
            })
            .then(() =>
                addNode({
                    parentPathOrId: areaPath,
                    primaryNodeType: 'jnt:bigText',
                    name: 'policyText',
                    properties: [{name: 'text', value: '<p>Support lasts twelve months after release.</p>', language}]
                })
            )
            .then(() => addNode({parentPathOrId: areaPath, primaryNodeType: 'crh:revisionHistory', name: 'history'}))
            .then((result: ApolloResult<AddNodeQueryData>) => {
                expect(result.errors, 'the revision history container must be creatable').to.be.undefined;
                return getUuid(pagePath);
            })
            .then(uuid => {
                pageUuid = uuid;
            });
    });

    beforeEach(() => {
        cy.login();
    });

    after(() => {
        cy.login();
        removeMixins(pagePath, [revisionedMixin]).then(null, () => undefined);
        deleteNode(pagePath).then(null, () => undefined);
        deleteNode(pagePath, 'LIVE').then(null, () => undefined);
        if (pageUuid) {
            deleteNode(`${historyRoot}/${pageUuid}`).then(null, () => undefined);
        }
    });

    // ---------------------------------------------------------------- binding

    it('binds a revision entry to the snapshot captured for the publication that carried it', () => {
        // The core of what was missing: the editorial half and the captured half joined.
        addEntry('rev-a', '1.0', '<p>Initial publication.</p>')
            .then((result: ApolloResult<AddNodeQueryData>) => {
                expect(result.errors, 'the first entry must be creatable').to.be.undefined;
                firstEntryUuid = result.data?.jcr.addNode.uuid as string;

                return publishTriggeringCapture();
            })
            .then(() =>
                pollUntil(
                    bindings,
                    map => Boolean(map[firstEntryUuid]),
                    `expected the entry to be bound to a snapshot within ${captureTimeoutMs}ms`
                )
            )
            .then(map => {
                expect(map[firstEntryUuid], 'the entry must name the snapshot it describes').to.be.a('string').and.not
                    .be.empty;
            });
    });

    it('leaves an already-bound entry on its own snapshot when a later capture stores a new one', () => {
        // Binding must be append-only. Rebinding would silently rewrite what an existing public
        // revision claims the page said -- the one thing an evidentiary record may never do.
        let originalSnapshot = '';

        bindings()
            .then(map => {
                originalSnapshot = map[firstEntryUuid];
                expect(originalSnapshot, 'the previous test must have bound the first entry').to.not.be.empty;

                // A real content change, so capture stores a genuinely new snapshot.
                return setNodeProperty(textPath, 'text', '<p>Support lasts eighteen months after release.</p>', language);
            })
            .then(() => addEntry('rev-b', '1.1', '<p>Extended the support window.</p>'))
            .then((result: ApolloResult<AddNodeQueryData>) => {
                expect(result.errors, 'the second entry must be creatable').to.be.undefined;
                secondEntryUuid = result.data?.jcr.addNode.uuid as string;

                // Newest first. Without this the list reads oldest-first and "previous" resolves
                // backwards: 1.0 would be compared against 1.1, and 1.1 -- as the last child --
                // would report itself as the earliest revision. Exactly inverted, and silently so.
                return reorderNewestFirst(['rev-b', 'rev-a']);
            })
            .then(() => publishTriggeringCapture())
            .then(() =>
                pollUntil(
                    bindings,
                    map => Boolean(map[secondEntryUuid]),
                    `expected the second entry to be bound within ${captureTimeoutMs}ms`
                )
            )
            .then(map => {
                expect(map[secondEntryUuid], 'the new entry must bind to the new snapshot').to.not.equal(
                    originalSnapshot
                );
                expect(map[firstEntryUuid], 'an already-bound entry must never be rebound').to.equal(originalSnapshot);
            });
    });

    it('binds a third revision, giving the selector a non-adjacent pair to compare', () => {
        // Three revisions is the smallest history where "compare any two" differs from "compare
        // with the previous one", so the tests below need one more than binding alone requires.
        setNodeProperty(textPath, 'text', '<p>Support lasts eighteen calendar months after release.</p>', language)
            .then(() => addEntry('rev-c', '1.2', '<p>Clarified that the months are calendar months.</p>'))
            .then((result: ApolloResult<AddNodeQueryData>) => {
                expect(result.errors, 'the third entry must be creatable').to.be.undefined;
                thirdEntryUuid = result.data?.jcr.addNode.uuid as string;

                return publishTriggeringCapture();
            })
            .then(() =>
                pollUntil(
                    bindings,
                    map => Boolean(map[thirdEntryUuid]),
                    `expected the third entry to be bound within ${captureTimeoutMs}ms`
                )
            )
            .then(map => {
                expect(map[thirdEntryUuid], 'the third entry must bind to its own snapshot').to.not.equal(
                    map[secondEntryUuid]
                );
                expect(map[firstEntryUuid], 'earlier bindings must still stand').to.not.be.undefined;
            });
    });

    // ---------------------------------------------------------------- comparison selector

    /** Builds the comparison URL the selector form produces on submit. */
    const comparisonUrl = (from: string, to: string) =>
        `?crhFrom=${from}&crhTo=${to}`;

    it('renders a selector offering every revision, defaulting to the two newest', () => {
        renderLive().then(html => {
            expect(html, 'the selector must render').to.contain('class="crh-compare-form"');
            expect(html, 'both ends of the comparison must be selectable').to.contain('name="crhFrom"');
            expect(html, 'both ends of the comparison must be selectable').to.contain('name="crhTo"');
            // The selector carries no styling of its own, but it must keep its semantics: the
            // fieldset groups the controls and the legend gives the group a purpose, because the
            // visible labels alone are "Compare" and "with" and "with" is not a usable accessible
            // name on its own. The legend is visually hidden, not removed.
            expect(html, 'the controls must still be grouped').to.contain('<fieldset>');
            expect(html, 'the group must keep a name, hidden from view').to.contain(
                '<legend class="crh-visually-hidden">'
            );
            expect(html, 'each select must have a label').to.contain('<label for="crh-from-');

            // Every revision appears in each select.
            const fromBlock = html.slice(html.indexOf('name="crhFrom"'), html.indexOf('name="crhTo"'));
            expect(
                (fromBlock.match(/<option /g) ?? []).length,
                'every revision must be selectable as the older end'
            ).to.equal(3);
        });
    });

    it('places the selector, and any result, above the list of revisions', () => {
        renderLive().then(html => {
            const section = html.slice(html.indexOf('<section class="crh-revision-history"'));
            expect(
                section.indexOf('crh-compare-form'),
                'the selector must come before the list it filters'
            ).to.be.lessThan(section.indexOf('crh-revision-list'));
        });
    });

    it('renders no em dash anywhere on the page', () => {
        renderLive().then(html => {
            const section = html.slice(html.indexOf('<section class="crh-revision-history"'));
            expect(section, 'no literal em dash').to.not.contain('\u2014');
            expect(section, 'and none as an entity either').to.not.contain('&#8212;');
        });
    });

    it('uses a small icon button that still carries an accessible name', () => {
        // An icon-only button with no text alternative announces as just "button". The name comes
        // from aria-label (the store's own pattern), and the SVG is aria-hidden so exactly one
        // name reaches the accessibility tree rather than two competing ones.
        renderLive().then(html => {
            const button = html.slice(html.indexOf('<button type="submit"'));
            const markup = button.slice(0, button.indexOf('</button>'));

            expect(markup, 'the control must be an icon, not text').to.contain('<svg');
            expect(markup, 'the graphic must not be announced separately').to.contain(
                'aria-hidden="true"'
            );
            expect(markup, 'the button must have an accessible name').to.contain(
                'aria-label="Compare"'
            );
            expect(markup, 'and a native tooltip, since this feature ships no script').to.contain(
                'title="Compare"'
            );
            // The module's own class, not the store's: store-btn lives in the store's stylesheet
            // and would style this control on exactly one site.
            expect(markup, 'the control must use this module\'s own class').to.contain(
                'class="crh-compare-btn"'
            );
        });
    });

    it('renders the snapshot markdown instead of showing its syntax', () => {
        // The rows used to show the module's own generated syntax literally: "# CRH e2e -
        // comparison", "**bold**". Rendering it is presentation only -- the diff is still computed
        // on the raw Markdown, which is what keeps one snapshot comparable to another regardless
        // of how either is displayed.
        renderLive(comparisonUrl(firstEntryUuid, thirdEntryUuid)).then(html => {
            const heading = /class="crh-md crh-md-h1">([^<]*)/.exec(html);

            expect(heading, 'the page-title line must be recognised as a level-1 heading').to.not.be
                .null;
            expect(
                (heading?.[1] ?? '').trim(),
                'the hashes are the syntax and must not reach the reader'
            ).to.not.match(/^#/);
            expect(
                (heading?.[1] ?? ''),
                'the heading text itself must survive being rendered'
            ).to.contain('CRH e2e');

            // Appearance only, and the assertion has to be scoped to the ROWS: the panel has a
            // legitimate <h3> of its own naming the two revisions, and the surrounding page render
            // is full of the page's real headings. What must not happen is a row emitting one,
            // which would splice the snapshot's outline into the host page's and break heading
            // order for anyone navigating by headings.
            const rows = /<ol class="crh-diff-rows"[\s\S]*?<\/ol>/.exec(html);

            expect(rows, 'the diff rows must be present to assert anything about them').to.not.be
                .null;
            expect(rows?.[0] ?? '', 'a diff row must not emit a real heading element').to.not.match(
                /<h[1-6][ >]/
            );

            // Rendering must not have cost the word-level highlighting, which is the one thing
            // that would make this a downgrade rather than an improvement.
            expect(html, 'a changed word must still be marked').to.contain('<mark>');
            expect(html, 'removals must still use <del>').to.contain('<del>');
            expect(html, 'additions must still use <ins>').to.contain('<ins>');
        });
    });

    it('compares two NON-ADJACENT revisions, which is the point of the selector', () => {
        // The per-revision controls this replaced could only ever answer about adjacent pairs.
        // "What changed between the version I agreed to and today" is almost never adjacent.
        renderLive(comparisonUrl(firstEntryUuid, thirdEntryUuid)).then(html => {
            expect(html, 'the comparison panel must render').to.contain('id="crh-comparison-');
            // The cumulative change across the whole span, not just the last step.
            expect(html, 'the word removed two revisions ago must be marked').to.contain(
                '<mark>twelve</mark>'
            );
            expect(html, 'the word present in the newest revision must be marked').to.contain(
                '<mark>eighteen</mark>'
            );
            expect(html, 'removals must use <del>').to.contain('<del>');
            expect(html, 'additions must use <ins>').to.contain('<ins>');
            expect(html, 'a text alternative to the +/- markers must be present').to.contain(
                'Removed line:'
            );
        });
    });

    it('serves the comparison to an anonymous visitor, which is who it is for', () => {
        // The defect this pins, reported from a live site: comparing two revisions answered
        // "One of the selected revisions is not part of this history." for anyone not logged in,
        // on entirely public content. The cause was the permission gate asking
        // JCRSessionFactory.getCurrentUserSession(), whose no-argument form resolves the current
        // USER but hard-defaults the WORKSPACE to `default` -- so the gate asked whether a
        // visitor could read the history in the EDIT workspace. Jahia never grants guests
        // `jcr:read_default`, so the answer was always no, and the module refused the public the
        // one feature it exists to provide. Every editor passed the same gate, which is why it
        // survived review, 266 unit tests and 31 tests in this file.
        renderLiveAsGuest(comparisonUrl(firstEntryUuid, thirdEntryUuid)).then(html => {
            expect(
                html,
                'the exact regression: a public comparison refused as though the revisions were ' +
                    'not part of the history the visitor just chose them from'
            ).to.not.contain('not part of this history');

            // Not just "no error": the comparison has to actually be there. Asserting only the
            // absence of the message would pass for a panel that renders empty, which is the
            // other way this has broken.
            expect(html, 'the comparison panel must render').to.contain('id="crh-comparison-');
            expect(html, 'the diff rows must render').to.contain('class="crh-diff-rows"');
            expect(html, 'the word removed two revisions ago must be marked').to.contain(
                '<mark>twelve</mark>'
            );
            expect(html, 'the word present in the newest revision must be marked').to.contain(
                '<mark>eighteen</mark>'
            );
        });
    });

    it('shows an anonymous visitor the same comparison it shows an editor', () => {
        // The stronger property, and the one that survives a partial fix. A gate that denies the
        // page rather than the history component returns an empty panel instead of a refusal:
        // no error message, no rows, nothing to assert against unless the two are compared. Both
        // reads are of published, public content, so anything but equality here is a leak in one
        // direction or a silent omission in the other.
        const rowsOf = (html: string) =>
            /<ol class="crh-diff-rows"[\s\S]*?<\/ol>/.exec(html)?.[0] ?? '';

        // Authenticated first: renderLiveAsGuest drops the session for the rest of the test.
        renderLive(comparisonUrl(firstEntryUuid, thirdEntryUuid)).then(asEditor => {
            const editorRows = rowsOf(asEditor);

            expect(editorRows, 'the authenticated render must have rows to compare against').to.not
                .be.empty;

            renderLiveAsGuest(comparisonUrl(firstEntryUuid, thirdEntryUuid)).then(asGuest => {
                expect(
                    rowsOf(asGuest),
                    'a visitor must see the whole comparison, not a trimmed or empty one'
                ).to.eq(editorRows);
            });
        });
    });

    it('is not decorated by a host theme that numbers list items with a pseudo-element', () => {
        // Reported from the Jahia Academy, whose theme numbers every ordered-list item with a
        // blue badge drawn in a pseudo-element. Both of this module's lists already set
        // `list-style: none`, which removes the NATIVE marker and does nothing about a generated
        // one, so the badge painted on every revision in the list and on every row of every
        // comparison -- and a diff can be hundreds of rows.
        //
        // This is really a specificity test. The host rule is (0,1,3) and the obvious
        // `.crh-revision-list > li::before` override is (0,1,2), which loses; the fix names the
        // component root as well to reach (0,2,2). Arithmetic is easy to get wrong, so this
        // asserts what a browser actually computes.
        cy.visit(`/cms/render/live/${language}${pagePath}.html`);

        cy.document().then(doc => {
            const style = doc.createElement('style');
            style.textContent =
                '.jac-content ol>li:before{content:counters(b,".");counter-increment:b;' +
                'background-color:#209fda;border-radius:50rem;color:#fff;display:inline-block;' +
                'height:22px;min-width:22px;position:absolute;left:-32px;top:4px}';
            doc.head.appendChild(style);
            doc.body.classList.add('jac-content');

            // A list this module does not own, and the reason this test is not vacuous: if the
            // injection above silently failed, every assertion below would report 'none' and pass
            // for entirely the wrong reason. This proves the host rule is in force.
            const control = doc.createElement('ol');
            control.innerHTML = '<li id="crh-host-rule-control">control</li>';
            doc.body.appendChild(control);
        });

        cy.get('#crh-host-rule-control').should($li => {
            expect(
                window.getComputedStyle($li[0], '::before').content,
                'precondition: the injected host rule must actually be in force'
            ).to.not.equal('none');
        });

        cy.get('summary.crh-revision-toggle').click();
        cy.get('.crh-revision-list > li').first().should($li => {
            expect(
                window.getComputedStyle($li[0], '::before').content,
                'the revision list must not carry the host theme\'s numbering'
            ).to.equal('none');
        });

        cy.get('select[name="crhFrom"]').select(firstEntryUuid);
        cy.get('select[name="crhTo"]').select(thirdEntryUuid);
        cy.get('button.crh-compare-btn').click();
        cy.get('.crh-diff-panel').should('be.visible');

        cy.get('.crh-diff-rows > li').first().should($li => {
            expect(
                window.getComputedStyle($li[0], '::before').content,
                'every comparison row would otherwise carry a numbered badge'
            ).to.equal('none');
        });
    });

    it('shows the comparison without putting anything in the address bar', () => {
        // The comparison used to arrive by navigation, so ?crhFrom=&crhTo=&#crh-comparison-
        // ended up in the URL of a page the visitor was only reading.
        cy.visit(`/cms/render/live/${language}${pagePath}.html`);

        cy.get('select[name="crhFrom"]').select(firstEntryUuid);
        cy.get('select[name="crhTo"]').select(thirdEntryUuid);
        cy.get('button.crh-compare-btn').click();

        cy.get('.crh-diff-panel').should('be.visible');
        cy.location('search').should('be.empty');
        cy.location('hash').should('be.empty');
    });

    it('never paints the panel inline while the comparison is in flight', () => {
        // The handler used to create the panel -- bordered, padded and empty -- BEFORE starting
        // the request, so it painted inline for the length of the round trip, filled with the
        // comparison, and only then jumped into the top layer. It read as a flip on every first
        // comparison. Delaying the response widens that window enough to assert on; without the
        // delay the bug would slip through between two commands.
        cy.intercept({method: 'GET', url: '**/crh-e2e-compare.html?crhFrom=*'}, request => {
            request.on('response', response => {
                response.setDelay(700);
            });
        }).as('comparison');

        cy.visit(`/cms/render/live/${language}${pagePath}.html`);
        cy.get('select[name="crhFrom"]').select(firstEntryUuid);
        cy.get('select[name="crhTo"]').select(thirdEntryUuid);
        cy.get('button.crh-compare-btn').click();

        // Mid-flight: the panel exists, and must not be on screen.
        cy.get('.crh-diff-panel').should('exist').and('not.be.visible');

        cy.wait('@comparison');
        cy.get('.crh-diff-panel').should('be.visible');
    });

    it('can be opened again after being dismissed', () => {
        // Regression test. The form action carries a fragment, so once the URL equalled
        // action + query + fragment, submitting the same pair again navigated to an IDENTICAL
        // URL -- a same-document fragment navigation, which never reloads. The page did not
        // re-run, the script did not re-fire, and the popup could not be reopened.
        cy.visit(`/cms/render/live/${language}${pagePath}.html`);

        cy.get('select[name="crhFrom"]').select(firstEntryUuid);
        cy.get('select[name="crhTo"]').select(thirdEntryUuid);
        cy.get('button.crh-compare-btn').click();
        cy.get('.crh-diff-panel').should('be.visible');

        // Dismiss the way Escape or a click outside would leave it.
        cy.get('.crh-diff-panel').then($panel => {
            ($panel[0] as unknown as {hidePopover: () => void}).hidePopover();
        });
        cy.get('.crh-diff-panel').should('not.be.visible');

        // The same pair again: the case that used to be a no-op.
        cy.get('button.crh-compare-btn').click();
        cy.get('.crh-diff-panel').should('be.visible');

        // Dismiss before touching the form again. An open popover sits in the browser's top
        // layer and covers the page beneath it, including the selector -- which is ordinary
        // popup behaviour, not a defect, and is what a visitor does too.
        cy.get('.crh-diff-panel').then($panel => {
            ($panel[0] as unknown as {hidePopover: () => void}).hidePopover();
        });

        // A different pair, to prove the content is refetched rather than merely reshown.
        cy.get('select[name="crhFrom"]').select(secondEntryUuid);
        cy.get('button.crh-compare-btn').click();
        cy.get('.crh-diff-panel').should('be.visible').and('contain.text', 'Comparing');
    });

    it('serves the comparison as a plain panel, so it survives without scripting', () => {
        // The popover attribute is added by script, never written into the markup: a browser
        // keeps [popover] hidden until something shows it, so shipping it would make the
        // comparison invisible to anyone with JavaScript off rather than merely un-popped.
        renderLive(comparisonUrl(firstEntryUuid, thirdEntryUuid)).then(html => {
            const panel = html.slice(html.indexOf('<section id="crh-comparison-'));
            const openTag = panel.slice(0, panel.indexOf('>') + 1);

            expect(openTag, 'the served panel must not carry popover').to.not.contain('popover');
            expect(openTag, 'nor claim to be a dialog before it is one').to.not.contain('role="dialog"');
            expect(html, 'and the comparison itself must be present in the response').to.contain(
                '<mark>'
            );
        });
    });

    it('upgrades that panel into a popup when scripting is available', () => {
        cy.visit(
            `/cms/render/live/${language}${pagePath}.html${comparisonUrl(firstEntryUuid, thirdEntryUuid)}`
        );

        cy.get('.crh-diff-panel')
            .should('be.visible')
            .and('have.attr', 'popover', 'auto')
            .and('have.attr', 'role', 'dialog');

        // NOT aria-modal: an auto popover does not trap focus or inert the page behind it, and
        // claiming otherwise would misdescribe it to assistive technology.
        cy.get('.crh-diff-panel').should('not.have.attr', 'aria-modal');

        // The comparison is inside the popup, not left behind on the page.
        // invoke('text') over the matched set rather than have.text: a word-level diff marks
        // EVERY changed word, so this pair carries two marks ("eighteen" and "calendar") and an
        // equality assertion was comparing against their concatenation.
        cy.get('.crh-diff-panel').within(() => {
            cy.get('del mark').invoke('text').should('contain', 'twelve');
            cy.get('ins mark').invoke('text').should('contain', 'eighteen');
        });
    });

    it('disappears when dismissed, rather than falling back into the page', () => {
        // Regression test for a real defect: the host site's normalize sets
        // `section { display: block }`, an AUTHOR rule, and author rules beat the UA rule that
        // hides a closed popover ([popover]:not(:popover-open){display:none}) whatever their
        // specificity. Dismissing the popup therefore dropped the comparison back into the page
        // as an inline panel instead of closing it.
        //
        // hidePopover() is used rather than Escape or a click outside because it is a script
        // call, not a user-agent gesture: Cypress's synthetic events cannot drive light dismiss,
        // but they can call this, and the state it produces is the same one those gestures reach.
        cy.visit(
            `/cms/render/live/${language}${pagePath}.html${comparisonUrl(firstEntryUuid, thirdEntryUuid)}`
        );
        cy.get('.crh-diff-panel').should('be.visible');

        cy.get('.crh-diff-panel').then($panel => {
            ($panel[0] as unknown as {hidePopover: () => void}).hidePopover();
        });

        cy.get('.crh-diff-panel').should('not.be.visible');
    });

    it('can be resized, and toggled to full screen, once it is a popup', () => {
        cy.visit(
            `/cms/render/live/${language}${pagePath}.html${comparisonUrl(firstEntryUuid, thirdEntryUuid)}`
        );
        cy.get('.crh-diff-panel').should('be.visible');

        // Native drag-to-resize, no script: it needs a non-visible overflow to work at all.
        cy.get('.crh-diff-panel').should('have.css', 'resize', 'both');

        // The two tools must line up. They are different elements -- the close is an <a>, the
        // toggle a <button> -- and an <a> is display:inline by default, where min-width,
        // min-height and the flex centring are all inert. That is what pulled them out of
        // alignment, so the geometry is asserted rather than the declaration that fixes it.
        cy.get('a.crh-diff-close').then($close => {
            cy.get('button.crh-diff-expand').then($expand => {
                const close = $close[0].getBoundingClientRect();
                const expand = $expand[0].getBoundingClientRect();

                expect(close.height, 'both tools must be the same height').to.equal(expand.height);
                expect(close.top, 'and sit on the same line').to.equal(expand.top);
                // Tightened from 32 to 44: the module targets WCAG 2.2 AAA, so SC 2.5.5 (44x44)
                // is the bar, not the AA-level SC 2.5.8 (24x24) this previously pinned. The test
                // was weaker than the standard the component claims.
                expect(close.height, 'and keep the AAA target size').to.be.at.least(44);
            });
        });

        // The toggle is server-rendered but only meaningful as a popup, so it is CSS-hidden on
        // the inline fallback where there is nothing to maximise.
        cy.get('button.crh-diff-expand')
            .should('be.visible')
            .and('have.attr', 'aria-pressed', 'false')
            .and('have.attr', 'aria-label', 'Full screen');

        cy.get('button.crh-diff-expand').click();

        // State is carried by aria-pressed, so one stable label serves both directions.
        cy.get('button.crh-diff-expand').should('have.attr', 'aria-pressed', 'true');
        cy.get('.crh-diff-panel').should('have.class', 'crh-diff-panel--full');
        // Maximised, the resize grip goes: the two would fight over the same inline size.
        cy.get('.crh-diff-panel').should('have.css', 'resize', 'none');

        cy.get('button.crh-diff-expand').click();
        cy.get('.crh-diff-panel').should('not.have.class', 'crh-diff-panel--full');
        cy.get('button.crh-diff-expand').should('have.attr', 'aria-pressed', 'false');
    });

    it('colours changed lines, without colour being the only signal', () => {
        renderLive(comparisonUrl(firstEntryUuid, thirdEntryUuid)).then(html => {
            const panel = html.slice(html.indexOf('id="crh-comparison-'));

            // The row tint. These were silently dropped once by a refactor and only an unused
            // CSS-token check caught it, so they are asserted here too.
            expect(panel, 'a removed line must be marked as such').to.contain('crh-diff-removed');
            expect(panel, 'an added line must be marked as such').to.contain('crh-diff-added');

            // Non-colour carriers keep it readable in monochrome, in forced-colours
            // mode, and with the stylesheet absent altogether (WCAG 1.4.1).
            expect(panel, 'a text alternative for the removed side').to.contain('Removed line:');
            expect(panel, 'a text alternative for the added side').to.contain('Added line:');
            expect(panel, 'semantic markup, not just colour').to.contain('<del>');
            expect(panel, 'semantic markup, not just colour').to.contain('<ins>');
            expect(panel, 'and the word-level highlight itself').to.contain('<mark>');
        });
    });

    it('closes with a cross in the corner, in either state', () => {
        // A link, not a popovertarget button: as a popup, following it navigates away and the
        // popup goes with it; inline, it simply clears the comparison. A popovertarget button
        // would be a dead control on the inline fallback.
        cy.visit(
            `/cms/render/live/${language}${pagePath}.html${comparisonUrl(firstEntryUuid, thirdEntryUuid)}`
        );
        cy.get('.crh-diff-panel').should('be.visible');

        // Icon-only, so the name has to come from aria-label or it announces as just "link".
        cy.get('a.crh-diff-close')
            .should('have.attr', 'aria-label', 'Close the comparison')
            .find('svg')
            .should('have.attr', 'aria-hidden', 'true');

        cy.get('a.crh-diff-close').click();

        cy.location('search').should('not.contain', 'crhFrom');
        cy.get('.crh-diff-panel').should('not.exist');
    });

    it('is an "auto" popover, which is what dismisses it on Escape and on a click outside', () => {
        // Asserted through the attribute rather than by simulating either gesture, and that is
        // MEASURED, not assumed: a real Escape keypress and a real click-outside were each tried
        // here and both left the popup open. Light dismiss is user-agent behaviour gated on
        // TRUSTED events, and Cypress dispatches synthetic ones (isTrusted=false), so the
        // browser's own handler ignores them. Such a test asserts Chrome's implementation, not
        // this module's, and fails for a reason that says nothing about the code.
        // (Ordinary clicks on links DO work -- element activation is not gated the same way --
        // which is why the close-cross test above clicks for real.)
        //
        // What IS this module's choice is "auto" over "manual": a manual popover has no light
        // dismiss at all, so a visitor could only close it by finding the cross.
        cy.visit(
            `/cms/render/live/${language}${pagePath}.html${comparisonUrl(firstEntryUuid, thirdEntryUuid)}`
        );

        cy.get('.crh-diff-panel')
            .should('be.visible')
            .and('have.attr', 'popover', 'auto');
    });

    it('shows the older revision on the left and the newer on the right', () => {
        renderLive(comparisonUrl(firstEntryUuid, thirdEntryUuid)).then(html => {
            const panel = html.slice(html.indexOf('id="crh-comparison-'));

            // Column headings name which side is which, using the revision labels themselves.
            const heads = [...panel.matchAll(/crh-diff-head">([^<]*)/g)].map(m => m[1].trim());
            expect(heads[0], 'the older revision heads the left column').to.equal('1.0');
            expect(heads[1], 'and the newer heads the right').to.equal('1.2');

            // A replaced line is ONE row carrying both sides, not two stacked rows.
            const changedRow = [...panel.matchAll(/<li class="crh-diff-row">([\s\S]*?)<\/li>/g)]
                .map(m => m[1])
                .find(row => row.includes('crh-diff-removed'));

            expect(changedRow, 'a changed line must produce a row').to.not.be.undefined;
            expect(
                (changedRow as string).indexOf('crh-diff-removed'),
                'the removed side must come before the added side in the DOM, so it renders left'
            ).to.be.lessThan((changedRow as string).indexOf('crh-diff-added'));

            // Both sides still carry their own semantics and text alternative.
            expect(changedRow, 'the old side uses <del>').to.contain('<del>');
            expect(changedRow, 'the new side uses <ins>').to.contain('<ins>');
        });
    });

    it('repeats an unchanged line identically on both sides', () => {
        // Asserted as a property, not against a known sentence: an earlier version matched the
        // page heading from a hand-tested site, which does not exist on the harness's scratch
        // page, so the test failed for a reason unrelated to what it checks.
        renderLive(comparisonUrl(firstEntryUuid, thirdEntryUuid)).then(html => {
            const panel = html.slice(html.indexOf('id="crh-comparison-'));
            const rows = [...panel.matchAll(/<li class="crh-diff-row">([\s\S]*?)<\/li>/g)]
                .map(m => m[1]);

            // Reads the two cells directly rather than stripping tags with a regex. The strip
            // was flagged by CodeQL as js/incomplete-multi-character-sanitization, and correctly
            // so: a single pass over `<[^>]+>` collapses `<scr<script>ipt>` INTO `<script`. There
            // is no injection path in a test that never renders its input, but a tag-stripping
            // helper is exactly the kind of thing that gets copied somewhere that does render.
            // Reading the cells is also more precise about what the test means.
            // Strip the visually-hidden side labels before comparing. Every cell now carries
            // one ("Older revision:" / "Newer revision:") so a screen reader can tell the two
            // copies of an unchanged line apart -- which means the two cells' MARKUP is
            // legitimately different while their CONTENT, which is what this test is about, is
            // identical. Matching raw markup conflated the two.
            // \s+ rather than a literal space between the tag name and the attribute: the JSP
            // wraps long tags, so this markup really is `<span\n    class="crh-visually-hidden">`.
            // A single-space pattern silently matched nothing and the labels stayed in, which is
            // how this helper failed the first time it ran.
            const withoutLabels = (row: string) =>
                row.replace(/<span\s+class="crh-visually-hidden">[\s\S]*?<\/span>/g, '');
            const sidesOf = (row: string) =>
                [
                    ...withoutLabels(row).matchAll(
                        /<span\s+class="crh-diff-side">([\s\S]*?)<\/span>/g
                    )
                ].map(m => m[1].trim());

            const unchanged = rows.find(
                row =>
                    !row.includes('crh-diff-removed') &&
                    !row.includes('crh-diff-added') &&
                    sidesOf(row).length === 2 &&
                    sidesOf(row)[0].length > 0
            );

            expect(unchanged, 'the comparison must contain an unchanged line to compare').to.not.be
                .undefined;

            const sides = sidesOf(unchanged as string);

            expect(sides.length, 'an unchanged row must fill both columns').to.equal(2);
            expect(
                sides[0],
                'and carry the same text in each: two views of one document, not two documents'
            ).to.equal(sides[1]);
        });
    });

    it('normalises the pair chronologically however the visitor picked them', () => {
        // Selecting newest-then-oldest must not invert the diff, or every addition would be
        // reported as a removal and the record would read backwards.
        renderLive(comparisonUrl(firstEntryUuid, thirdEntryUuid)).then(forwards => {
            renderLive(comparisonUrl(thirdEntryUuid, firstEntryUuid)).then(backwards => {
                const panel = (html: string) =>
                    html.slice(html.indexOf('id="crh-comparison-'), html.indexOf('</section>', html.indexOf('id="crh-comparison-')));
                expect(panel(backwards), 'the order of selection must not change the result').to.equal(
                    panel(forwards)
                );
            });
        });
    });

    it('explains rather than compares when the same revision is chosen twice', () => {
        renderLive(comparisonUrl(firstEntryUuid, firstEntryUuid)).then(html => {
            expect(html, 'the panel must say why there is nothing to compare').to.contain(
                'Those are the same revision'
            );
            expect(html, 'nothing may be compared against itself').to.not.contain('<mark>');
        });
    });

    it('refuses an identifier that is not a revision of this history', () => {
        // The containment check IS the access control: the service reads with a SYSTEM session,
        // so without it a crafted selection would render an arbitrary node on a public page.
        // The page's own uuid is a real, readable node -- and not an entry here.
        renderLive(comparisonUrl(firstEntryUuid, pageUuid)).then(html => {
            expect(html, 'the refusal must be stated, not rendered blank').to.contain(
                'not part of this history'
            );
            expect(html, 'no comparison may be produced for a foreign node').to.not.contain('<mark>');
        });
    });

    it('refuses a malformed identifier without erroring the page', () => {
        renderLive('?crhFrom=not-a-uuid%27%20or%201%3D1&crhTo=also-not-a-uuid').then(html => {
            expect(html, 'a malformed selection must be refused like any other').to.contain(
                'not part of this history'
            );
            expect(html, 'the rest of the page must still render').to.contain('crh-revision-list');
        });
    });

    it('renders no comparison panel until one is asked for', () => {
        renderLive().then(html => {
            expect(html, 'the revision list must still render').to.contain('crh-revision-list');
            expect(html, 'the panel must appear only on request').to.not.contain('id="crh-comparison-');
        });
    });

    // ---------------------------------------------------------------- collapsing

    it('renders the revision list inside a disclosure that starts closed', () => {
        // A revision history is supporting evidence for the page, not the page. Left open, a
        // long one pushes the content it describes off the screen.
        renderLive().then(html => {
            expect(html, 'the list must be wrapped in a native <details>').to.contain(
                '<details class="crh-revision-disclosure">'
            );
            expect(html, 'and must start closed').to.not.contain('crh-revision-disclosure" open');
            // The count has to be computed before the summary that reports it: JSTL has no
            // hoisting, and a <c:set> after its reader yields an empty value silently.
            // Derived from what actually rendered rather than hardcoded, so adding a revision to
            // an earlier test does not break this one for an unrelated reason.
            const rendered = (html.match(/<article class="crh-entry"/g) ?? []).length;
            expect(rendered, 'the list must have rendered some revisions').to.be.greaterThan(0);
            expect(html, 'the toggle must report how many revisions are hidden').to.contain(
                `${rendered} recorded revision(s)`
            );
            // Closed hides from view, not from the document: the record stays reachable by
            // search engines, find-in-page and assistive technology.
            expect(html, 'the entries must still be present in the DOM when closed').to.contain(
                'crh-revision-list'
            );
        });
    });

    it('honours an editor who turns the collapsed default off', () => {
        setCollapsedByDefault(false)
            .then(() => publishTriggeringCapture())
            .then(() => renderLive())
            .then(html => {
                expect(html, 'an expanded list must render open').to.contain(
                    '<details class="crh-revision-disclosure" open>'
                );

                // Restored so the ordering-dependent tests in this file see the default state.
                return setCollapsedByDefault(true);
            });
    });

    // ---------------------------------------------------------------- ordering

    it('puts a revision added at the end of the list into its correct place by date', () => {
        // THE regression test for the reported bug. Content Editor appends a new child at the
        // END, which under a newest-first reading is the OLDEST position -- so simply adding a
        // revision used to render the newest one last, as "the earliest recorded revision" with
        // no compare control, while the entry beside it compared against the wrong revision.
        // Order is derived from revisionDate now, so appending must no longer matter.
        const newestLabel = `2.0-${Date.now()}`;

        addEntry('rev-newest', newestLabel, '<p>Added last, dated newest.</p>')
            .then((result: ApolloResult<AddNodeQueryData>) => {
                expect(result.errors, 'the entry must be creatable').to.be.undefined;

                // Dated a day ahead of the others, and deliberately NOT reordered.
                return cy.apollo({
                    mutation: gql`
                        mutation($path: String!, $value: String!) {
                            jcr { mutateNode(pathOrId: $path) {
                                mutateProperty(name: "revisionDate") { setValue(value: $value, type: DATE) }
                            } }
                        }
                    `,
                    variables: {
                        path: `${historyPath}/rev-newest`,
                        value: new Date(Date.now() + 86400000).toISOString()
                    }
                });
            })
            .then(() => publishTriggeringCapture())
            .then(() => renderLive())
            .then(html => {
                const order = [...html.matchAll(/<h3 id="crh-entry-heading-[^"]*">\s*([^<]+)/g)]
                    .map(m => m[1].trim());

                expect(order[0], 'the newest revision by date must render first').to.equal(newestLabel);
                expect(order[order.length - 1], 'the oldest must render last').to.equal('1.0');

                // The selector must offer the revisions in that same order, or the list and the
                // control would disagree about which revision is which.
                const fromBlock = html.slice(html.indexOf('name="crhFrom"'), html.indexOf('name="crhTo"'));
                const optionOrder = [...fromBlock.matchAll(/<option [^>]*>([^(]+)\(/g)]
                    .map(m => m[1].trim());
                expect(optionOrder, 'the selector must list revisions in the rendered order').to.deep.equal(order);
            });
    });

    // ---------------------------------------------------------------- jContent preview

    it('makes a captured snapshot displayable, so jContent can preview it', () => {
        // A node is displayable only when a jnt:contentTemplate declares j:applyOn for its type;
        // jContent previews by asking for displayableNode and rendering whatever comes back.
        // Without the template this module ships, displayableNode was null and every render URL
        // answered 404 -- regardless of views, mixins or permissions.
        const snapshotQuery = gql`
            query($path: String!) {
                jcr(workspace: EDIT) {
                    nodeByPath(path: $path) {
                        children { nodes { path displayableNode { path } } }
                    }
                }
            }
        `;

        cy.apollo({query: snapshotQuery, variables: {path: folderPath()}}).then(
            (result: ApolloResult<{jcr: {nodeByPath?: {children: {nodes: Array<{path: string, displayableNode?: {path: string}}>}}}}>) => {
                const snapshots = result.data?.jcr?.nodeByPath?.children.nodes ?? [];
                expect(snapshots.length, 'the earlier tests must have captured snapshots').to.be.greaterThan(0);

                const snapshot = snapshots[0];
                expect(
                    snapshot.displayableNode?.path,
                    'a snapshot must be displayable in its own right, not via an ancestor'
                ).to.equal(snapshot.path);

                cy.request({url: `/cms/render/default/${language}${snapshot.path}.html`}).then(response => {
                    expect(response.status, 'the preview must render').to.equal(200);
                    expect(response.body, 'it must show the capture metadata').to.contain('Captured snapshot');
                    // Always guest: the guarantee the whole capture design rests on.
                    expect(response.body, 'it must show which principal captured it').to.contain('guest');
                    // Preformatted, never re-rendered as HTML: the snapshot is the evidence.
                    expect(response.body, 'the payload must be shown verbatim').to.contain(
                        'crh-snapshot-markdown'
                    );
                });
            }
        );
    });

    // ---------------------------------------------------------------- snapshot hygiene

    it('keeps snapshots out of the live workspace even when an ancestor is published', () => {
        // The mixin jmix:nolive sits on crh:snapshotFolder and crh:revisionSnapshot, and
        // JCRPublicationService honours it directly (the platform applies it to jnt:role, jnt:permission, jnt:component --
        // types that must never exist in live at all).
        //
        // Without it, publishing /sites/<site>/contents drags the whole evidentiary tree into
        // live: a second permanent copy of the same record, with no answer to which is
        // authoritative if they diverge, and an editorial gate these deliberately never had.
        // The comparison never needs them there -- it is computed server-side from `default`,
        // and a snapshot only ever contains what a guest could already see, because that is the
        // principal it was captured as.
        //
        // Work-in-progress would also skip publication and was rejected: it is an editorial
        // "not finished yet" badge that any editor can clear, so it states something untrue
        // about an immutable record and does not hold.
        publishAndWaitJobEnding(`/sites/${siteKey}/contents`, [language]);

        cy.apollo({query: liveChildrenQuery, variables: {path: `/sites/${siteKey}/contents`}}).then(
            (result: ApolloResult<{jcr: {nodeByPath?: {children: {nodes: Array<{name: string}>}}}}>) => {
                const names = (result.data?.jcr?.nodeByPath?.children.nodes ?? []).map(n => n.name);

                expect(names, 'the snapshot tree must never reach live').to.not.include(
                    'revision-history'
                );
            }
        );
    });

    it('keeps the revision list itself out of the snapshots it describes', () => {
        // Without a dedicated markdown view, crh:revisionHistory falls through to the generic
        // jnt:content fallback and the list is captured into the record it describes: publishing
        // a revision entry would then change the page, and every comparison would show the
        // changelog instead of the change.
        cy.request<string>({url: `/cms/render/live/${language}${pagePath}.markdown`}).then(response => {
            expect(response.body, 'the version labels must not appear in the captured Markdown').to.not.contain('1.1');
            expect(response.body, 'entry summaries must not appear in the captured Markdown').to.not.contain(
                'Extended the support window'
            );
            expect(response.body, 'the page content itself must still be captured').to.contain(
                // Matched on a word every revision keeps, not a whole phrase: asserting
                // "eighteen months" broke the day a test above changed the sentence to
                // "eighteen calendar months", for a reason unrelated to what this checks.
                'eighteen'
            );
        });
    });

    // ---------------------------------------------------------------- pinning

    /**
     * Reported from a real page: two revisions pinned to two reconstructed snapshots could not be
     * compared, and the panel said "No snapshot of the page was recorded for this revision" while
     * both snapshots existed and both entries named one.
     *
     * crh:entryRefs, which the comparison reads, is written by RevisionEntryBinder, and the binder
     * runs only after a capture, which happens only on publication. So an entry pinned by hand and
     * not followed by a publication was invisible to the comparison. That is the NORMAL workflow for
     * backfilled history, where every entry has to be pinned to a historical snapshot by hand.
     *
     * This entry is deliberately NEVER published after being created: publishing would run a
     * capture, the binder would bind it, and the test would pass for the wrong reason.
     *
     * Mutation-checked: removing the crh:snapshotRef pass from RevisionDiffService.snapshotsFor
     * fails this test with the exact message above.
     */
    it('compares a revision pinned by hand, with no publication to bind it', () => {
        const namesQuery = gql`
            query($path: String!) {
                jcr(workspace: EDIT) {
                    nodeByPath(path: $path) { children { nodes { name } } }
                }
            }
        `;

        let pinnedUuid = '';
        cy.apollo({query: namesQuery, variables: {path: folderPath()}})
            .then((result: ApolloResult<{jcr: {nodeByPath?: {children: {nodes: Array<{name: string}>}}}}>) => {
                const names = (result.data?.jcr?.nodeByPath?.children.nodes ?? [])
                    .map(n => n.name).sort();
                expect(names.length, 'the earlier tests must have captured snapshots')
                    .to.be.greaterThan(1);

                // The OLDEST snapshot, so the pin points somewhere the binder would never choose:
                // the binder always binds to the current one.
                return addNode({
                    parentPathOrId: historyPath,
                    primaryNodeType: 'crh:revisionEntry',
                    name: 'rev-pinned',
                    properties: [
                        {name: 'revisionLabel', value: '9.0'},
                        {name: 'revisionDate', value: new Date().toISOString(), type: 'DATE'},
                        {name: 'changeType', value: 'substantive'},
                        {name: 'summary', value: '<p>Pinned by hand.</p>', language},
                        // Crh:snapshotRef carries the prefix, unlike the four properties above.
                        // The CND declares it that way, and using the bare name here created the
                        // entry with no pin at all, silently.
                        {name: 'crh:snapshotRef', value: names[0]}
                    ]
                });
            })
            .then((result: ApolloResult<AddNodeQueryData>) => {
                expect(result.errors, 'the pinned entry must be creatable').to.be.undefined;
                pinnedUuid = result.data?.jcr.addNode.uuid as string;
                expect(pinnedUuid, 'the pinned entry must have an identifier').to.not.be.undefined;

                return cy.request<string>({
                    url: `/cms/render/default/${language}${pagePath}.html` +
                        comparisonUrl(pinnedUuid, firstEntryUuid),
                    failOnStatusCode: false
                });
            })
            .then(response => {
                expect(response.status).to.equal(200);
                expect(response.body, 'a pinned revision must not report a missing snapshot')
                    .to.not.contain('No snapshot of the page was recorded for this revision');
                expect(response.body, 'nor a missing previous snapshot')
                    .to.not.contain('No snapshot of the page was recorded for the previous revision');
            });
    });

    // ---------------------------------------------------------------- rich formatting

    it('renders blockquotes, rules, tables and links formatted, not as raw Markdown', () => {
        // The panel already renders headings and bold; these are the shapes added in 1.4.10. A new
        // revision carries a quote, a rule, a link and a table; compared against the plain-text
        // rev-c they arrive as added lines, which is where the new rendering has to show.
        const richHtml =
            '<blockquote><p>A quoted remark about the support policy.</p></blockquote>' +
            '<hr/>' +
            '<p>See the <a href="https://example.com/policy">full policy</a> for details.</p>' +
            '<table><tbody><tr><th>Tier</th><th>Window</th></tr>' +
            '<tr><td>Standard</td><td>Twelve months</td></tr></tbody></table>';
        let fourthEntryUuid = '';

        setNodeProperty(textPath, 'text', richHtml, language)
            .then(() => addEntry('rev-d', '1.3', '<p>Rich formatting: a quote, a rule, a link and a table.</p>'))
            .then((result: ApolloResult<AddNodeQueryData>) => {
                expect(result.errors, 'the rich-formatting entry must be creatable').to.be.undefined;
                fourthEntryUuid = result.data?.jcr.addNode.uuid as string;

                return publishTriggeringCapture();
            })
            .then(() =>
                pollUntil(
                    bindings,
                    map => Boolean(map[fourthEntryUuid]),
                    `expected the rich-formatting entry to bind within ${captureTimeoutMs}ms`
                )
            )
            .then(() => renderLive(comparisonUrl(thirdEntryUuid, fourthEntryUuid)))
            .then(html => {
                const rows = /<ol class="crh-diff-rows"[\s\S]*?<\/ol>/.exec(html)?.[0] ?? '';
                expect(rows, 'the diff rows must render').to.not.be.empty;

                // Each new shape renders formatted rather than as its own syntax.
                expect(rows, 'a blockquote renders as a quote, not a literal "> "').to.contain('crh-md-quote');
                expect(rows, 'a horizontal rule renders as a rule, not literal "---"').to.contain('crh-md-rule');
                expect(rows, 'a table renders as cells, not a line of pipes').to.contain('crh-md-cell');
                expect(rows, 'the table header/body separator renders as a divider').to.contain('crh-md-tsep');
                expect(rows, 'a link renders as an anchor').to.contain('<a class="crh-md-link"');
                expect(rows, 'the anchor points at its sanitised destination').to.contain(
                    'href="https://example.com/policy"'
                );

                // ...and the raw syntax is gone from the rendered rows.
                expect(rows, 'the link syntax must not reach the reader').to.not.contain(
                    '](https://example.com/policy)'
                );

                // Line-level shape stays appearance only: no real structural element is spliced into
                // the host page from a diff row.
                expect(rows, 'a row must not emit a real <table>').to.not.match(/<table[ >]/);
                expect(rows, 'a row must not emit a real <blockquote>').to.not.match(/<blockquote[ >]/);
            });
    });
});
