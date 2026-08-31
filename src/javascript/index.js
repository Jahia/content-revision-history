// Only used when jahia-ui-root is the host rather than the app shell. Kept because the federation
// config declares '@jahia/app-shell' as a remote, so this resolves at runtime, never at build time.
import('@jahia/app-shell/bootstrap').then(res => {
    window.jahia = res;
    res.startAppShell(window.appShell.remotes, window.appShell.targetId);
});
