// Only used when jahia-ui-root is the host rather than the app shell. Kept because the federation
// config declares '@jahia/app-shell' as a remote, so this resolves at runtime, never at build time.
import('@jahia/app-shell/bootstrap').then(res => {
    window.jahia = res;
    res.startAppShell(window.appShell.remotes, window.appShell.targetId);
}).catch(error => {
    // Without this the rejection is unhandled: the shell never starts, the page stays blank, and
    // the only trace is an unhandled-rejection entry that names neither this module nor what was
    // being loaded. Say which import failed, so a blank screen is one console line from diagnosed.
    // eslint-disable-next-line no-console
    console.error('[content-revision-history] could not load @jahia/app-shell/bootstrap;'
        + ' the admin shell will not start', error);
});
