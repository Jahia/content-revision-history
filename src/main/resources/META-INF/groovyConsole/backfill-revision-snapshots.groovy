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
def normalizeMethod = normalizerClass.getMethod('normalize', String)
def normalize = { String raw -> normalizeMethod.invoke(null, raw) as String }

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

// This node's own HTTP connector. Change it if Jahia is not on 8080, or if the loopback
// interface is not the one serving plain HTTP.
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
 * walked, or the reconstruction would include content the real snapshot deliberately omits --
 * crh:revisionHistory renders empty on purpose, so that a page's own changelog never lands inside
 * the record it describes.
 */
def SELF_RENDERING = ['jnt:bigText', 'crh:revisionHistory'] as Set

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

def fetchMarkdown = { String path, long millis ->
    def url = new URL("${baseUrl}/cms/render/default/${language}${encodePath(path)}.markdown?v=${millis}")
    def conn = url.openConnection()
    conn.setRequestProperty('Authorization', 'Basic ' + credentials.bytes.encodeBase64().toString())
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
            "partial reconstruction would be stored as evidence. " +
            ((code == 401 || code == 403)
                ? "Check RENDER_USER / RENDER_SECRET -- that account must be able to read the " +
                  "default workspace of ${path}."
                : "Check BASE_URL (${baseUrl}) and that ${path} renders in the default workspace."))
    }
    return conn.inputStream.getText('UTF-8')
}

/** Mirrors jnt_content/markdown: emit a jcr:title heading, then recurse; leaves render themselves. */
def compose
compose = { JCRNodeWrapper node, long millis, StringBuilder sb ->
    node.nodes.each { child ->
        if (child.name.startsWith('j:')) return
        if (!isRenderable(child)) return

        if (SELF_RENDERING.contains(child.primaryNodeTypeName)) {
            sb << fetchMarkdown(child.path, millis) << '\n'
            return
        }
        def grandChildren = child.nodes.findAll { !it.name.startsWith('j:') }
        if (grandChildren.isEmpty()) {
            sb << fetchMarkdown(child.path, millis) << '\n'
        } else {
            def title = child.getPropertyAsString('jcr:title')
            if (title) sb << '## ' << title << '\n\n'
            compose(child, millis, sb)
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
    return normalize(raw)
}

/** The one translation subnode that the render of this language will actually dereference. */
def wantedTranslation = 'j:translation_' + java.util.Locale.forLanguageTag(language).toString()

def pageUuid = null, siteKey = null, folderPath = null
def existing = [:]      // instant millis -> [name, markdown, capturedBy]
def candidates = [] as SortedSet
def nodeVersions  = [:]  // path -> sorted checkpoint millis of the node itself
def transVersions = [:]  // path -> sorted checkpoint millis of its j:translation_<lang>

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
                [snap.name, snap.getProperty('crh:markdown').string, snap.getPropertyAsString('crh:capturedBy')]
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
 * not considered. Nodes ADDED since are handled -- their own history has nothing at or before the
 * instant, so they are skipped by the first test.
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
report << "existing  : ${existing.size()} snapshot(s) already stored\n\n"

// ------------------------------------------------------------------ the gate

def guestSnapshots = existing.findAll { k, v -> v[2] == 'guest' }
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
    report << "\nNOTE: this page has no captured snapshots, so the reconstruction is UNVERIFIED.\n"
    report << "Run this script first on a page that does have captured history, to establish that\n"
    report << "the composition is faithful for your content types.\n"
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
    report << "\nDRY RUN - nothing written. First few instants:\n"
    toWrite.take(5).each { report << "  ${new Date(it).format('yyyy-MM-dd HH:mm:ss.SSS')}\n" }
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
toWrite.each { millis ->
    def md = reconstruct(millis)
    def status = captureMethod.invoke(service, siteKey, pageUuid, language, md,
            java.time.Instant.ofEpochMilli(millis), 'reconstructed', null)
    if (String.valueOf(status) == 'STORED') stored++ else unchanged++
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

report << "\nstored ${stored} reconstructed snapshot(s), ${unchanged} skipped as unchanged\n"
report.toString()
