/*
 * ONE-SHOT BACKFILL: reconstruct revision snapshots for pages that existed before this module.
 *
 * Run it from Tools > Groovy console. It writes nothing unless dryRun is unchecked.
 *
 * ---------------------------------------------------------------------------------------------
 * HOW IT CAN WORK AT ALL
 *
 * JCRSessionWrapper.setVersionDate(Date) pins an ENTIRE session to an instant: every node read
 * through it, including nodes reached by walking down from the page, resolves to its state then.
 * That is what makes the historical page structure recoverable.
 *
 * The render chain does NOT do the same. `?v=<millis>` on a URL renders one content node
 * historically, but a container or a page renders its children at CURRENT content -- measured,
 * not assumed. So this script recomposes the page itself: it walks the pinned session for
 * structure and fetches each leaf's own `.markdown?v=` render, which is the real view.
 *
 * ---------------------------------------------------------------------------------------------
 * WHY IT VALIDATES ITSELF FIRST
 *
 * Recomposition mirrors what jnt_page/markdown and jnt_content/markdown do. A mirror can drift
 * from the thing it mirrors, and a migration that writes a subtly wrong record is worse than one
 * that refuses to run. So before writing anything, the script reconstructs the instants for which
 * a REAL captured snapshot already exists and compares byte for byte. Any mismatch aborts.
 *
 * That gate cannot fire on a page that predates the module and has no snapshots at all -- which
 * is exactly the page you want to backfill. Run it first on a page that HAS captured history to
 * establish that the composition is faithful for your content, then on the ones that do not.
 *
 * ---------------------------------------------------------------------------------------------
 * WHAT IT CANNOT DO, AND WILL TELL YOU ABOUT
 *
 *  * Deleted components are unrecoverable. `?v=` needs an addressable node; a component removed
 *    since then has no current path, so its text is missing from the reconstruction.
 *  * Coverage is bounded by version purging, which is one of the reasons this module exists
 *    rather than relying on JCR versioning.
 *  * crh:capturedBy is written as "reconstructed", never "guest". Live snapshots are captured over
 *    HTTP AS GUEST, which is what guarantees they hold only what the public could see. Retroactive
 *    reconstruction has no such guarantee: ACLs at that instant are unknown. The two must stay
 *    distinguishable in the record.
 */
import org.jahia.services.content.*

// ------------------------------------------------------------------ module classes
//
// The Groovy console runs in the platform classloader, which cannot see a module's packages, so
// this module's classes are loaded through its OSGi bundle rather than imported. The ACTIVE
// bundle is selected deliberately: a version bump leaves the previous one INSTALLED alongside it,
// and loading from the stale copy would run last release's normaliser against this release's
// views -- producing snapshots that differ from live captures for no visible reason.
def moduleBundle = {
    def ctx = Class.forName('org.jahia.osgi.FrameworkService').getMethod('getBundleContext').invoke(null)
    def candidates = ctx.bundles.findAll { it.symbolicName == 'content-revision-history' }
    def active = candidates.find { it.state == 32 }   // org.osgi.framework.Bundle.ACTIVE
    if (!active) {
        throw new IllegalStateException(
            "content-revision-history is not ACTIVE (found: " +
            candidates.collect { "${it.version}=state ${it.state}" } + ")")
    }
    return active
}()

def normalizerClass = moduleBundle.loadClass('org.jahia.modules.revisionhistory.MarkdownNormalizer')
// The LOCALE-AWARE overload, which is the one the live capture path calls. Using the
// locale-less one here reproduced, in the migration script, the exact defect
// SnapshotCaptureJob records as having been fixed: sentence boundaries were found with English
// rules, so a reconstructed zh or ja page never matched the captured one. The byte-for-byte gate
// then reported an unexplained MISMATCH and aborted the run -- or, forced through with
// ALLOW_UNEXPLAINED, stored snapshots that diff spuriously against every future live capture.
// The SAME function jnt_content/markdown/content.jsp calls. Reimplementing "which properties hold
// text" in Groovy is how the script and the view drift apart, and a drift here does not show up as
// a bug -- it shows up as a snapshot missing text, stored as an authoritative record.
def functionsClass = moduleBundle.loadClass('org.jahia.modules.revisionhistory.RevisionHistoryFunctions')
def textPropertiesMethod = functionsClass.getMethod('textProperties', JCRNodeWrapper)
def textProperties = { JCRNodeWrapper n -> textPropertiesMethod.invoke(null, n) as List }

def localeForMethod = normalizerClass.getMethod('localeFor', String)
def normalizeMethod = normalizerClass.getMethod('normalize', String, java.util.Locale)
// The language is a parameter rather than a captured constant: LANGUAGE is a typed local declared
// further down, and a closure defined up here cannot see it.
def normalize = { String raw, String lang ->
    normalizeMethod.invoke(null, raw, localeForMethod.invoke(null, lang)) as String
}

def serviceClass = moduleBundle.loadClass('org.jahia.modules.revisionhistory.RevisionSnapshotService')
def captureMethod = serviceClass.getMethod('captureIfChanged',
        String, String, String, String, java.time.Instant, String, String)

// ------------------------------------------------------------------ SETTINGS
//
// Edit these, then run. The console loads this script into its textarea, so the edit is
// local to the run and never touches the shipped file.
//
// They are plain constants rather than console parameters on purpose: the console's
// script.parameters mechanism does not reach the script's binding (measured -- only `log` and
// `logger` are bound), and no shipped module uses it, so there is nothing to copy and nothing to
// rely on.

String PAGE_PATH = ''          // e.g. '/sites/digitall/home/maintenance-and-support-policy'
String LANGUAGE  = 'en'
boolean DRY_RUN  = true        // false actually writes snapshots

// Reconstruction renders the DEFAULT workspace over this node's own HTTP connector, so it needs
// an account that may read it. There is deliberately NO default: a hardcoded pair would ship a
// working credential inside the module jar, and -- worse -- would silently stop working on any
// instance that changed it, at which point every fetch 401s. Combined with the refusal below
// that is merely noisy; without it, it wrote empty snapshots as authoritative history.
String RENDER_USER   = ''
String RENDER_SECRET = ''      // that account's password

// This node's own HTTP connector -- NOT the site's public address.
//
// It has to bypass whatever sits in front of Jahia. A public host normally has SEO URL rewriting
// enabled (urlRewriteSeoRulesEnabled, urlRewriteRemoveCmsPrefix) and often a reverse proxy in
// addition, and those rewrite or refuse the /cms/render/... paths this script asks for. The symptom
// is a flat HTTP 404 on every node, in both workspaces, whatever its type, version count or the
// rights of the account -- which looks exactly like a broken node and is not one.
//
// Change it if Jahia is not on 8080, or if the loopback interface is not the one serving plain HTTP.
String BASE_URL = 'http://127.0.0.1:8080'

// Proceed even if the validation below cannot explain a difference. Read the report first: an
// unexplained difference means the reconstruction produced text that was never captured, which
// is EITHER composition drift in this script OR a captured snapshot whose date and content
// disagree. The latter is real and known: a capture refused by the rate limiter is never stored,
// so the neighbouring snapshot can carry a later publication's text under an earlier instant.
boolean ALLOW_UNEXPLAINED = false

// ------------------------------------------------------------------

String pagePath = PAGE_PATH?.trim()
String language = LANGUAGE?.trim() ?: 'en'
boolean dryRun = DRY_RUN
boolean allowUnexplained = ALLOW_UNEXPLAINED
String baseUrl = BASE_URL?.trim() ?: 'http://127.0.0.1:8080'
String credentials = (RENDER_USER ?: '').trim() + ':' + (RENDER_SECRET ?: '')

def report = new StringBuilder()
if (!pagePath) {
    return 'Set PAGE_PATH at the top of this script, for example ' +
           '/sites/digitall/home/maintenance-and-support-policy'
}
if (!(RENDER_USER ?: '').trim() || !(RENDER_SECRET ?: '')) {
    return 'Set RENDER_USER and RENDER_SECRET at the top of this script. They must name an ' +
           'account that can read the default workspace of ' + pagePath + '. There is no ' +
           'default: an unauthenticated or wrongly-authenticated run cannot render the page, ' +
           'and this script must never store what it could not read.'
}

// ------------------------------------------------------------------ composition

/**
 * Types whose markdown view renders WITHOUT recursing into children. They must be fetched, never
 * walked, or the reconstruction would include content the real snapshot deliberately omits.
 */
def SELF_RENDERING = ['jnt:bigText'] as Set

/**
 * Types whose markdown view deliberately renders NOTHING. Neither fetched nor walked.
 *
 * crh:revisionHistory used to sit in SELF_RENDERING, which put two correct intentions in direct
 * collision: its view renders empty on purpose, and fetchMarkdown refuses an empty 200 body
 * because splicing '' into a record is how a page that changed gets stored as one that did not.
 * So every page carrying a Revision history component aborted the run -- and that is EVERY page
 * with captured history, which is precisely the page the README tells you to backfill first to
 * prove the composition is faithful.
 *
 * Walking it instead would be worse than the abort: the entries would be rendered by the generic
 * fallback, so each revision's own summary would land inside the snapshot it describes, and every
 * later diff would show the changelog rather than the change.
 *
 * The live views emit one line separator per child regardless of what the child rendered, so an
 * empty child still contributes that separator. Emitting it here and skipping the fetch is
 * byte-identical to what the old code would have produced had fetchMarkdown returned '' instead
 * of refusing.
 */
def RENDERS_NOTHING = ['crh:revisionHistory'] as Set

/**
 * Is this node something the markdown template type can actually render?
 *
 * The views cover jnt:page and jnt:content (the generic fallback). Anything else hanging off a
 * page has no view. jnt:vanityUrls and jnt:vanityUrl are the ones that bite: they extend nt:base,
 * not jnt:content, their names are derived from the URL and contain spaces, and asking Jahia to
 * render one answers 401. They are also mix:versionable, so without this they contribute version
 * instants to the candidate list as well -- moments when a URL mapping changed and no content did.
 *
 * The name-starts-with-j: test that used to be the only filter does not exclude them, because the
 * node is called vanityUrlMapping.
 */
def isRenderable = { JCRNodeWrapper n ->
    n.isNodeType('jnt:content') || n.isNodeType('jnt:page')
}

/**
 * Percent-encodes each path segment. A content node may legitimately carry spaces or other
 * characters that are not URL-safe, and interpolating such a path straight into a URL produces a
 * request Jahia rejects rather than the render that was intended.
 */
def encodePath = { String path ->
    path.split('/', -1).collect { seg ->
        seg ? java.net.URLEncoder.encode(seg, 'UTF-8').replace('+', '%20') : seg
    }.join('/')
}

// A public-looking BASE_URL is the single most common way this script fails, and it fails late and
// misleadingly. Say so before any work is done rather than after the first render.
//
// It also decides whether the credential is sent at all. The module's Java capture path withholds
// the Authorization header from any endpoint that is not this node's own loopback connector, and
// the README states as a critical security note that a capture credential goes only to loopback
// addresses -- while this script, shipped in the same module, used to send it wherever BASE_URL
// pointed. An operator following the natural instinct and setting BASE_URL to the address they know
// ('https://www.example.com') base64-encoded a real Jahia account's password to a public host and
// whatever reverse proxy, CDN or WAF logs sit in front of it. Warning about the value while still
// sending the password to it made the warning worse than useless.
def reachesJahiaDirectly = (baseUrl ==~ /(?i)https?:\/\/(127\.0\.0\.1|localhost|\[::1\])(:\d+)?/)
if (!reachesJahiaDirectly) {
    report << "WARNING: BASE_URL is ${baseUrl}, which is not this node's loopback connector.\n"
    report << "  It must reach Jahia directly. A public host rewrites or refuses /cms/render/...\n"
    report << "  paths (SEO rewriting, a reverse proxy), which produces a flat 404 on every node in\n"
    report << "  both workspaces, whatever its type, version count, or the rights of the account.\n"
    report << "  The credential will NOT be sent to it: renders below are anonymous, so any page the\n"
    report << "  public cannot read will fail with 401/403/404 even though RENDER_USER is set.\n"
    report << "  Set BASE_URL to http://127.0.0.1:8080 and re-run.\n\n"
}

def fetchMarkdown = { String path, long millis ->
    def url = new URL("${baseUrl}/cms/render/default/${language}${encodePath(path)}.markdown?v=${millis}")
    def conn = url.openConnection()
    // Only to this node itself. See the BASE_URL check above for why this is a refusal and not
    // another warning.
    if (reachesJahiaDirectly) {
        conn.setRequestProperty('Authorization', 'Basic ' + credentials.bytes.encodeBase64().toString())
    }
    conn.connectTimeout = 10000
    conn.readTimeout = 30000
    int code = conn.responseCode
    if (code != 200) {
        // Returning '' here spliced a hole into a reconstruction that is about to be written as
        // an authoritative historical record. The self-validation gate cannot catch it, because
        // the gate only runs on instants that already have a captured snapshot -- and a page
        // with no captured history is exactly the page this script exists for. So the only safe
        // answer to a failed render is to refuse the whole run.
        throw new IllegalStateException(
            "Render of ${path} at ${millis} returned HTTP ${code}. Refusing to continue: a " +
            "partial reconstruction would be stored as evidence.\n" +
            // The exact request, so it can be replayed by hand without reconstructing it from the
            // path and the instant. Credentials are NOT included: the header is built from
            // RENDER_USER / RENDER_SECRET, and an exception message ends up in logs.
            "  URL  : ${url}\n" +
            "  curl : curl -i -u '<RENDER_USER>:<RENDER_SECRET>' '${url}'\n" +
            ((code == 401 || code == 403)
                ? "Check RENDER_USER / RENDER_SECRET -- that account must be able to read the " +
                  "default workspace of ${path}."
                : (code == 404
                    ? "Check BASE_URL first: it is ${baseUrl}. It must be THIS NODE'S OWN HTTP " +
                      "connector, not the site's public address. A public host rewrites or refuses " +
                      "/cms/render/... paths (SEO rewriting, a reverse proxy), and the symptom is a " +
                      "flat 404 on every node in both workspaces whatever its type or rights. Try " +
                      "the URL above against http://127.0.0.1:8080 and compare. If the loopback " +
                      "answers 200, that is the whole problem. Only if BOTH answer 404 is this " +
                      "about the node itself."
                    : "Check BASE_URL (${baseUrl}) and that ${path} renders in the default workspace.")))
    }
    String body = conn.inputStream.getText('UTF-8')
    // An empty 200 is the same hole as a 404, and it was NOT guarded. The comment above understood
    // the danger of splicing '' into a record and then only checked the status code, so a host that
    // answers 200 with nothing sailed straight through. Measured on a real page: every leaf returned
    // an empty body, composition produced the page heading and nothing else, 32 instants across 5
    // publication moments all composed to the same 27 characters, and the run stored ONE snapshot
    // and called the other 31 "unchanged". The page had demonstrably changed.
    if (body == null || body.trim().isEmpty()) {
        throw new IllegalStateException(
            "Render of ${path} at ${millis} returned HTTP 200 but an EMPTY body. Refusing to " +
            "continue: composing '' for a node produces a snapshot that holds only the page " +
            "heading, which would be stored as evidence and would look like a page that never " +
            "changed.\n" +
            "  URL  : ${url}\n" +
            "  curl : curl -i -u '<RENDER_USER>:<RENDER_SECRET>' '${url}'\n" +
            "BASE_URL is ${baseUrl}. If that is not this node's own HTTP connector, it is the " +
            "cause: a public host does not always answer 404 for /cms/render/... paths, it can " +
            "answer 200 with nothing, and an empty render is indistinguishable from a node with " +
            "no content unless it is refused here. Try the URL above against " +
            "http://127.0.0.1:8080 and compare.")
    }
    return body
}

// Checkpoints per node, filled by the gather pass below and read by compose and unresolvableAt.
// Declared here rather than beside the rest of the gather state because a Groovy closure captures
// script variables lexically: compose is defined before that block and could not see them there.
def nodeVersions  = [:]  // path -> sorted checkpoint millis of the node itself
def transVersions = [:]  // path -> sorted checkpoint millis of its j:translation_<lang>

/**
 * Did this node exist at that instant?
 *
 * compose walks the CURRENT subtree -- setVersionDate on a system session does not produce frozen
 * wrappers, so there is no historical tree to walk -- and renders each child at the instant. A node
 * added AFTER the instant has no version at or before it, Jahia has nothing to resolve, and the
 * render answers 404. Reported from a real run:
 *
 *   Render of .../jsa-2026-0013/document-area/github-content at 1786972166934 returned HTTP 404
 *
 * A node with no version history at all is still rendered: it is not versionable, so a pinned
 * session resolves it to its current state rather than refusing.
 */
def existedAt = { String path, long millis ->
    def own = nodeVersions[path]
    if (own == null) {
        // Not versionable at all: a pinned session resolves it to its current state, so there is
        // nothing to decide.
        return true
    }
    // Versionable: it was on the published page at that instant only if it has a checkpoint at or
    // before it. An EMPTY history means it was never published, so it was never part of any
    // historical state and must not appear in a reconstruction of one.
    return own.any { it <= millis }
}

/** Mirrors jnt_content/markdown: emit a jcr:title heading, then recurse; leaves render themselves. */
def compose
compose = { JCRNodeWrapper node, long millis, StringBuilder sb ->
    node.nodes.each { child ->
        if (child.name.startsWith('j:')) return
        if (!isRenderable(child)) return

        // existedAt gates FETCHING, never recursion. Containers are routinely versionable with an
        // empty history -- measured on a real page, both jnt:contentList areas and the page itself
        // had zero checkpoints while their children had two each -- so gating the walk on it
        // skipped every container and reconstructed pages as nothing but their title.
        if (RENDERS_NOTHING.contains(child.primaryNodeTypeName)) {
            if (existedAt(child.path, millis)) {
                sb << '\n'
            }
            return
        }
        if (SELF_RENDERING.contains(child.primaryNodeTypeName)) {
            if (existedAt(child.path, millis)) {
                sb << fetchMarkdown(child.path, millis) << '\n'
            }
            return
        }
        def grandChildren = child.nodes.findAll { !it.name.startsWith('j:') }
        if (grandChildren.isEmpty()) {
            // A leaf that has no checkpoint at or before this instant was not on the page then.
            // Asking Jahia to render one answers 404 -- reported from a real run against a
            // jnt:bigText that was never published, so its history was empty at every instant.
            if (existedAt(child.path, millis)) {
                sb << fetchMarkdown(child.path, millis) << '\n'
            }
        } else {
            def title = child.getPropertyAsString('jcr:title')
            if (title) sb << '## ' << title << '\n\n'
            // content.jsp emits the container's OWN text properties between its title and its
            // children. Omitting them here was a mirror drift introduced when the fallback view
            // stopped emitting jcr:title alone (generator 5): a container carrying, say, a
            // subtitle rendered that text live and lost it in reconstruction, so the
            // byte-for-byte gate reported an unexplained MISMATCH and aborted -- or, forced past
            // with ALLOW_UNEXPLAINED, stored snapshots permanently missing text live capture records.
            textProperties(child).each { sb << it << '\n\n' }
            compose(child, millis, sb)
            // NOTE, deliberately not acted on. Reading the views, the parent emits one line
            // separator after EVERY child module including a container, while this branch emits
            // none after recursing -- which would put a container and the sibling after it on
            // adjacent lines where live capture leaves a blank one, and BLANK_RUN only collapses
            // runs of three or more newlines, so it would survive normalisation. But content.jsp
            // also emits literal template newlines between its own top-level lines, which this
            // mirror does not reproduce anywhere, so the true delta is not derivable by reading:
            // it needs a real run. The byte-for-byte gate is exactly that measurement, and it
            // aborts rather than storing a mismatch, so if this is wrong it announces itself
            // safely on the first validated page. Changing it on an incomplete trace could
            // instead break a composition that currently agrees.
        }
    }
}

/** Mirrors jnt_page/markdown: the page heading, then its areas. */
def reconstruct = { long millis ->
    String raw = null
    JCRTemplate.instance.doExecuteWithSystemSession(null, 'default', java.util.Locale.forLanguageTag(language), { s ->
        s.setVersionDate(new Date(millis))
        def page = s.getNode(pagePath)
        def sb = new StringBuilder()
        sb << '# ' << page.displayableName << '\n\n'
        compose(page, millis, sb)
        raw = sb.toString()
        return null
    } as JCRCallback)
    return normalize(raw, language)
}

/** The one translation subnode that the render of this language will actually dereference. */
def wantedTranslation = 'j:translation_' + java.util.Locale.forLanguageTag(language).toString()

def pageUuid = null, siteKey = null, folderPath = null
def existing = [:]      // instant millis -> [name, markdown, capturedBy, generatorVersion]
def candidates = [] as SortedSet

JCRTemplate.instance.doExecuteWithSystemSession(null, 'default', java.util.Locale.forLanguageTag(language), { s ->
    def page = s.getNode(pagePath)
    pageUuid = page.identifier
    siteKey = page.resolveSite.siteKey
    folderPath = "/sites/${siteKey}/contents/revision-history/${pageUuid}/${language}"

    // Every checkpoint of every versionable node under the page is a moment the page may have
    // changed. Their union is the candidate set; dedupe by content hash discards the rest.
    def collect
    collect = { JCRNodeWrapper n ->
        if (n.isVersioned()) {
            def own = n.versionsAsVersion.collect { it.created.timeInMillis }.sort()
            candidates.addAll(own)
            // Only a versionable node is ever wrapped as JCRFrozenNodeAsRegular, and only that
            // wrapper can return null from getI18N. A plain node reads its live translation and
            // cannot trip the interceptor, so it is not worth recording.
            nodeVersions[n.path] = own
        }
        // The translation subnode keeps its OWN version history, and a save checkpoints it a few
        // milliseconds after its parent. Without its instants the candidate set holds only the
        // moment before the translation was checkpointed, which is precisely the instant that
        // cannot be rendered at all. Only the language being rendered is collected: on digitall
        // j:translation_de_DE has an EMPTY history, so treating every translation as required
        // would leave no usable instant at all.
        def i18ns = n.getI18Ns()
        while (i18ns.hasNext()) {
            def t = i18ns.nextNode()
            if (t.name != wantedTranslation || !t.hasProperty('jcr:versionHistory')) continue
            def vh = s.getProviderSession(n.provider)
                      .getNodeByIdentifier(t.getProperty('jcr:versionHistory').string)
            def own = []
            def vit = vh.allVersions
            while (vit.hasNext()) {
                def v = vit.nextVersion()
                if (v.name != 'jcr:rootVersion') own << v.created.timeInMillis
            }
            own.sort()
            candidates.addAll(own)
            transVersions[n.path] = own
        }
        n.nodes.each { if (!it.name.startsWith('j:') && isRenderable(it)) collect(it) }
    }
    collect(page)

    try {
        s.getNode(folderPath).nodes.each { snap ->
            if (!snap.isNodeType('crh:revisionSnapshot')) return
            existing[snap.getProperty('crh:snapshotDate').date.timeInMillis] =
                [snap.name, snap.getProperty('crh:markdown').string,
                 snap.getPropertyAsString('crh:capturedBy'),
                 snap.getPropertyAsString('crh:generatorVersion')]
        }
    } catch (Exception ignored) { /* no history captured yet */ }
    return null
} as JCRCallback)

/**
 * Which nodes cannot be read at all at this instant?
 *
 * JCRFrozenNodeAsRegular.getI18N breaks a contract the platform itself depends on. hasI18N(locale)
 * answers true, because the frozen node really does carry a j:translation_<lang> child, while
 * getI18N(locale) answers null, because that translation's OWN version history holds no version at
 * or before the instant. LastModifiedInterceptor.afterGetValue guards with the first and
 * dereferences the second, so reading ANY property of such a node throws NullPointerException and
 * the render answers 500. Reading a single property directly fails the same way at a different
 * line, so no change to the markdown views can avoid it.
 *
 * It happens because a save checkpoints a node a few milliseconds BEFORE its translation subnode.
 * A candidate instant taken from the parent's own version lands inside that gap, where the page is
 * genuinely unresolvable -- a state no reader ever saw. The settled state of the same publication
 * is the translation's checkpoint, which collect() now gathers too, so declining these instants
 * loses no content.
 *
 * Measured on 8.2.3.2, /sites/digitall/home/about/history/landing/banner:
 *   node             checkpoints 16:06:17.4xx, 16:06:18  -> 1 at or before 16:06:17.498
 *   j:translation_en checkpoints 16:06:17.5xx, 16:06:18  -> 0 at or before 16:06:17.498
 *
 * This is arithmetic over the histories gathered above rather than a walk of a pinned session,
 * because setVersionDate on a system session does NOT produce frozen wrappers -- a walk there
 * reads HEAD and the predicate never fires. It also refuses to be set twice on one thread.
 *
 * Known limit: the walk above is of the CURRENT subtree, so a node deleted since the instant is
 * not considered. Nodes ADDED since are skipped by compose, which asks existedAt before rendering
 * one. An earlier version of this note said they were "handled" HERE, which was wrong: this
 * predicate merely declines to judge them, and compose went on to render one and got a 404.
 */
def unresolvableAt = { long millis ->
    def bad = []
    nodeVersions.each { path, own ->
        def trans = transVersions[path]
        if (trans == null) return                       // no translation in this language
        if (!own.any { it <= millis }) return            // the node itself is not there yet
        if (!trans.any { it <= millis }) bad << path     // it is, but its translation is not
    }
    return bad.sort()
}

report << "page      : ${pagePath}\n"
report << "site/lang : ${siteKey} / ${language}\n"
report << "candidates: ${candidates.size()} version instants across the subtree\n"

// A single publish checkpoints every versioned node in the subtree a few milliseconds apart, so
// 40 instants is routinely ONE editorial event. The number of snapshots that SHOULD appear tracks
// the number of events, not the number of instants, and without this grouping a correct run and a
// broken one both just print "1 stored, 32 unchanged".
final long SAME_EVENT_GAP_MS = 10000L
def moments = []
candidates.each { m ->
    if (moments.isEmpty() || (m - moments[-1][-1]) > SAME_EVENT_GAP_MS) {
        moments << [m]
    } else {
        moments[-1] << m
    }
}
if (!candidates.isEmpty()) {
    report << "            spanning ${new Date(candidates.first()).format('yyyy-MM-dd HH:mm:ss')}"
    report << " to ${new Date(candidates.last()).format('yyyy-MM-dd HH:mm:ss')}\n"
    report << "            grouped into ${moments.size()} publication moment(s)"
    report << " (instants within ${(int) (SAME_EVENT_GAP_MS / 1000)}s treated as one)\n"
    if (moments.size() == 1) {
        report << "            ALL instants fall in one moment, so this page has a single publication in\n"
        report << "            its surviving version history and ONE snapshot is the correct outcome.\n"
    } else {
        moments.take(8).each { g ->
            report << "              ${new Date(g.first()).format('yyyy-MM-dd HH:mm:ss')}  ${g.size()} instant(s)\n"
        }
        if (moments.size() > 8) {
            report << "              ... and ${moments.size() - 8} more\n"
        }
    }
}
report << "existing  : ${existing.size()} snapshot(s) already stored\n\n"

// ------------------------------------------------------------------ the gate

// Only a snapshot made by the CURRENT generator can validate the reconstruction. The
// generator stamp exists precisely because a change to the views or to MarkdownNormalizer alters
// the text for unchanged content, so comparing across versions measures the upgrade rather than
// the composition -- and the abort below would blame composition drift for it, which is wrong.
def currentGenerator = normalizerClass.getField('GENERATOR_VERSION').get(null) as String
def staleGenerator = existing.findAll { k, v -> v[3] != currentGenerator }
if (staleGenerator) {
    report << "note: ${staleGenerator.size()} of ${existing.size()} stored snapshot(s) were made by "
    report << "an older generator and are not used to validate (current is ${currentGenerator}, "
    report << "found ${staleGenerator.values().collect { it[3] }.unique().sort()}).\n"
    report << "  They are left exactly as they are. A comparison that spans the change already\n"
    report << "  tells the reader some differences may be formatting rather than content.\n\n"
}
def guestSnapshots = existing.findAll { k, v -> v[2] == 'guest' && v[3] == currentGenerator }
int checked = 0, exact = 0, skewed = 0, mismatched = 0, unverifiable = 0
guestSnapshots.each { millis, info ->
    def blocked = unresolvableAt(millis)
    if (blocked) {
        unverifiable++
        report << "UNVERIFIABLE at ${info[0]}: ${blocked.size()} node(s) have no resolvable "
        report << "${wantedTranslation} at this instant, e.g. ${blocked[0]}.\n"
        return
    }
    def rebuilt = reconstruct(millis)
    checked++
    if (rebuilt == info[1]) {
        exact++
        return
    }
    // A mismatch is only composition drift if the rebuilt text matches NOTHING that was ever
    // captured. If it matches a different snapshot, the composition is faithful and the recorded
    // DATE is skewed -- capture is asynchronous, so a snapshot carries the publication instant
    // that triggered it while its content is whatever the guest render returned a moment later.
    // Two publications inside the rate-limit window collapse exactly this way, and the module
    // documents it as known and accepted.
    def elsewhere = existing.find { k, v -> v[1] == rebuilt }
    if (elsewhere) {
        skewed++
        report << "SKEW at ${info[0]}: rebuilt text matches ${elsewhere.value[0]} instead.\n"
        report << "  Expected if two publications landed within the capture rate-limit window.\n"
    } else {
        mismatched++
        report << "MISMATCH at ${info[0]} -- rebuilt text matches no captured snapshot:\n"
        report << "  captured ${info[1].readLines().take(3)}\n  rebuilt  ${rebuilt.readLines().take(3)}\n"
    }
}
report << "validation: ${checked} checked, ${exact} exact, ${skewed} date-skewed, "
report << "${mismatched} unexplained, ${unverifiable} unverifiable\n"

if (mismatched > 0 && !allowUnexplained) {
    report << "\nABORTED. The reconstruction produced text that was never captured at any instant.\n"
    report << "That has two possible causes, and they need different responses:\n\n"
    report << "  1. Composition drift in this script -- the recomposed page does not match what\n"
    report << "     the markdown views actually render. Fix the rules above before writing.\n"
    report << "  2. A captured snapshot whose date and content disagree. Capture is asynchronous\n"
    report << "     and rate limited: a refused capture is never stored, so a neighbouring\n"
    report << "     snapshot can carry a later publication's text under an earlier instant. Here\n"
    report << "     the reconstruction is right and the stored record is the inexact one.\n\n"
    report << "If the exact count above is high and the failures cluster around publications made\n"
    report << "seconds apart, it is almost certainly (2). Set ALLOW_UNEXPLAINED = true to proceed.\n"
    return report.toString()
}
if (mismatched > 0) {
    report << "\nPROCEEDING with ${mismatched} unexplained difference(s) because ALLOW_UNEXPLAINED is set.\n"
}
if (checked == 0) {
    report << "\nNOTE: nothing here could validate the reconstruction, so it is UNVERIFIED.\n"
    if (staleGenerator) {
        report << "This page HAS captured history, but every snapshot predates generator "
        report << "${currentGenerator}, so none of it can say whether the composition is faithful\n"
        report << "now. Capture one snapshot on this generator -- publish the page once -- and run\n"
        report << "this script again to get a real check.\n"
    } else {
        report << "Run this script first on a page that does have captured history, to establish\n"
        report << "that the composition is faithful for your content types.\n"
    }
}

/**
 * Every snapshot currently in the folder, with its date.
 *
 * <p>The counts alone ("28 already stored") do not tell you what you have, and the next thing to
 * do after a backfill is to describe these revisions by hand -- which means choosing them by date
 * in the editor. The NAME is printed beside the date because that is the value the picker stores
 * in crh:snapshotRef, so a listing here can be matched against what the edit form offers.
 */
/** crh:markdown is stored as a binary, so it has to be streamed rather than read as a string. */
def readMarkdown = { JCRNodeWrapper n ->
    if (!n.hasProperty('crh:markdown')) {
        return ''
    }
    def binary = n.getProperty('crh:markdown').binary
    try {
        return binary.stream.getText('UTF-8')
    } catch (Exception unreadable) {
        return ''
    } finally {
        try { binary.dispose() } catch (Exception ignored) { }
    }
}

def listSnapshots = { String heading, Set<Long> writtenNow ->
    def rows = []
    JCRTemplate.instance.doExecuteWithSystemSession(null, 'default', null, { s ->
        try {
            s.getNode(folderPath).nodes.each { n ->
                if (!n.isNodeType('crh:revisionSnapshot')) return
                rows << [
                    millis: n.getProperty('crh:snapshotDate').date.timeInMillis,
                    name  : n.name,
                    by    : n.getPropertyAsString('crh:capturedBy') ?: '?',
                    gen   : n.getPropertyAsString('crh:generatorVersion') ?: '?',
                    // crh:markdown is a BINARY property. getPropertyAsString returns nothing for
                    // one, so reading it that way reported 0 chars for every snapshot however full
                    // it was, and made an empty capture indistinguishable from a healthy one.
                    md    : readMarkdown(n)
                ]
            }
        } catch (Exception noFolderYet) { /* nothing stored for this page and language */ }
        return null
    } as JCRCallback)
    if (rows.isEmpty()) {
        report << "\n${heading}: none\n"
        return
    }
    rows.sort { it.millis }
    report << "\n${heading} (${rows.size()}), oldest first:\n"
    rows.each { r ->
        def mark = writtenNow.contains(r.millis) ? '  <- written by this run' : ''
        def firstHeading = r.md.readLines().find { it.startsWith('#') } ?: '(no heading)'
        report << "  ${new Date(r.millis).format('yyyy-MM-dd HH:mm:ss.SSS')}  ${r.name}"
        report << "  by ${r.by}  gen ${r.gen}${mark}\n"
        report << "      ${r.md.length()} chars, opens with: ${firstHeading.take(70)}\n"
    }
}

// ------------------------------------------------------------------ write

def unresolvable = [:]
def toWrite = candidates.findAll { !existing.containsKey(it) }.sort().findAll { millis ->
    def blocked = unresolvableAt(millis)
    if (blocked) {
        unresolvable[millis] = blocked
        return false
    }
    return true
}
report << "\nto reconstruct: ${toWrite.size()} instant(s)\n"
if (unresolvable) {
    report << "skipped ${unresolvable.size()} instant(s) that cannot be rendered at any credential:\n"
    report << "  a save checkpoints a node a few ms before its ${wantedTranslation}, and inside that\n"
    report << "  gap JCRFrozenNodeAsRegular.getI18N returns null while hasI18N answers true, so\n"
    report << "  LastModifiedInterceptor throws NullPointerException and the render answers 500.\n"
    report << "  The settled state of the same publication is the translation's own checkpoint,\n"
    report << "  which is in the candidate set, so no content is lost by skipping these. First few:\n"
    unresolvable.keySet().sort().take(3).each {
        report << "    ${new Date(it).format('yyyy-MM-dd HH:mm:ss.SSS')} -> ${unresolvable[it][0]}\n"
    }
}

if (dryRun) {
    listSnapshots('snapshots already stored', [] as Set)
    // A dry run now composes each instant and reports its length. Rendering is read-only, and
    // without this the safe mode could not answer the only question that matters when a run
    // collapses: did the instants actually compose to different text? Printing the instants alone
    // and adding "many of these collapse once rendered" explained nothing.
    report << "\nDRY RUN - nothing written. Composing each instant to measure it:\n"
    def dryLengths = [:]
    toWrite.each { millis ->
        def md = reconstruct(millis)
        dryLengths[millis] = (md ?: '').length()
        report << "  ${new Date(millis).format('yyyy-MM-dd HH:mm:ss.SSS')}  ${dryLengths[millis]} chars\n"
    }
    def distinctDry = dryLengths.values() as Set
    report << "\n${distinctDry.size()} distinct composed length(s) across ${dryLengths.size()} instant(s)"
    report << (distinctDry.isEmpty() ? "\n"
        : (distinctDry.size() == 1 ? " (${distinctDry.first()} chars every time)\n"
            : ", ${distinctDry.min()} to ${distinctDry.max()} chars\n"))
    if (distinctDry.size() == 1 && moments.size() > 1) {
        report << "\nEvery instant composed to the SAME length across ${moments.size()} publication moments.\n"
        report << "A real run would therefore store ONE snapshot. If the repository holds different\n"
        report << "text at those instants, composition is dropping content rather than the page being\n"
        report << "unchanged, and a length near the page title alone means the walk never reached the\n"
        report << "leaves at all.\n"
    }
    return report.toString()
}

// Oldest first, so the store's dedupe chain reads chronologically, exactly as live capture builds
// it. The folder's latest* pointers are restored afterwards: captureIfChanged assumes it is always
// storing the NEWEST snapshot, and leaving them aimed at a backfilled one would make the next live
// capture compare against the wrong baseline.
def service = serviceClass.newInstance()
def before = [:]
JCRTemplate.instance.doExecuteWithSystemSession(null, 'default', null, { s ->
    // The folder is created by the first captureIfChanged below, so on a page that has never been
    // captured there is nothing here yet and no baseline to preserve. That is the case this
    // script exists for, and this read used to throw PathNotFoundException on it before a single
    // snapshot was written. The read further up already tolerated a missing folder ("no history
    // captured yet"); this one did not.
    //
    // Nothing is lost by skipping: with no prior capture there are no live-capture pointers to
    // restore, and the backfill's newest snapshot IS the correct baseline for the next live
    // capture, because writes go oldest first.
    if (!s.nodeExists(folderPath)) {
        return null
    }
    def f = s.getNode(folderPath)
    ['crh:latestHash', 'crh:latestSnapshot'].each { p -> if (f.hasProperty(p)) before[p] = f.getPropertyAsString(p) }
    return null
} as JCRCallback)

int stored = 0, unchanged = 0
// captureIfChanged answers with a status, and STORED is only one of them: UNCHANGED, EMPTY,
// OVERSIZE, NOT_PUBLIC, RATE_LIMITED and FAILED are all distinct outcomes. Counting everything
// that is not STORED as "unchanged" was how a run could report a tidy "1 stored, 32 skipped as
// unchanged" while 32 instants had in fact produced empty markdown, or failed outright. The
// breakdown below is printed verbatim, so the next run says which it was.
def statusCounts = [:]
// The composed length per instant, because identical lengths across instants that are KNOWN to
// differ in the repository is the signature of composition dropping content rather than of a page
// that did not change.
def composedLengths = [:]
// Which instants this run actually wrote, so the listing below can mark them. An instant that
// deduped is NOT in here: nothing new was stored for it, and saying otherwise would misreport
// what happened.
def writtenInstants = [] as Set
toWrite.each { millis ->
    def md = reconstruct(millis)
    composedLengths[millis] = (md ?: '').length()
    def status = captureMethod.invoke(service, siteKey, pageUuid, language, md,
            java.time.Instant.ofEpochMilli(millis), 'reconstructed', null)
    def name = String.valueOf(status)
    statusCounts[name] = (statusCounts[name] ?: 0) + 1
    if (name == 'STORED') {
        stored++
        writtenInstants << millis
    } else {
        unchanged++
    }
}

if (!before.isEmpty()) {
    JCRTemplate.instance.doExecuteWithSystemSession(null, 'default', null, { s ->
        def f = s.getNode(folderPath)
        before.each { p, v -> f.setProperty(p, v) }
        s.save()
        return null
    } as JCRCallback)
    report << "\nrestored the folder's latest-snapshot pointers to the live-capture baseline\n"
}

report << "\nstored ${stored} reconstructed snapshot(s), ${toWrite.size() - stored} not stored\n"
report << "outcome by status, as captureIfChanged reported it:\n"
statusCounts.sort { -it.value }.each { name, count ->
    report << "  ${name.padRight(14)} ${count}\n"
}
// EMPTY and FAILED are not "nothing changed", they are the capture not happening. Saying so here
// rather than folding them into a benign-looking count is the whole point of the breakdown.
if (statusCounts['EMPTY']) {
    report << "\n  EMPTY means the composition produced no text for that instant, so nothing could be\n"
    report << "  stored. That is composition failing, not a page that did not change.\n"
}
if (statusCounts['FAILED']) {
    report << "\n  FAILED means the write itself did not happen. Check the log for the cause.\n"
}

def lengths = composedLengths.values() as Set
report << "\ncomposed markdown length across ${composedLengths.size()} instant(s): "
report << "${lengths.size()} distinct value(s)"
report << (lengths.size() == 1 ? " (${lengths.first()} chars every time)\n" : ", ${lengths.min()} to ${lengths.max()} chars\n")
if (lengths.size() == 1 && moments.size() > 1) {
    report << "  Every instant composed to the SAME length across ${moments.size()} publication moments.\n"
    report << "  If the repository shows different text at those instants, composition is dropping\n"
    report << "  content: compare this length against the page's own textContent lengths. A length\n"
    report << "  close to just the page title means the walk never reached the leaves.\n"
}

// The failure this catches: a BASE_URL that does not reach Jahia directly can answer 200 with the
// same "not found" or login page for every /cms/render path. Every render then hashes identically,
// dedupe collapses the lot, and the run reports a tidy "1 stored, N unchanged" that looks like a
// page which simply never changed. Distinguishing the two needs the moment count, not the hash.
if (stored <= 1 && moments.size() > 1) {
    report << "\nWARNING: ${moments.size()} distinct publication moments produced only ${stored} snapshot(s).\n"
    report << "  Content published ${moments.size()} times that renders byte-identical every time is far more\n"
    report << "  likely a fetch problem than real history. Check that the stored snapshot's\n"
    report << "  crh:markdown holds the actual page and not an error or login page, and if BASE_URL\n"
    report << "  is not this node's loopback connector, set it to http://127.0.0.1:8080 and re-run.\n"
}

listSnapshots('snapshots now stored for this page and language', writtenInstants)
report << "\nTo publish any of these as a revision, add a Revision to the page's revision list and\n"
report << "set 'Snapshot this revision describes' to the matching date. Leaving it empty attaches\n"
report << "the entry to the newest snapshot, which is not what reconstructed history needs.\n"

report.toString()
