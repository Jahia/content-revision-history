import {registry} from '@jahia/ui-extender';
import i18next from 'i18next';

import register from './SiteSettings/register';

/**
 * The one entry the app shell calls, exposed as './init' by the federation container.
 *
 * <p>It registers a CALLBACK rather than the route itself. The shell loads every remote's init
 * before its own extension points exist, so an adminRoute added here directly is written into a
 * registry the navigation has not read yet and is silently dropped: the remote loads, nothing
 * errors, and no menu entry appears. Registering on the 'jahiaApp-init' target defers the real
 * work to the phase where the shell is actually assembling its navigation.
 */
export default function () {
    registry.add('callback', 'contentRevisionHistory', {
        targets: ['jahiaApp-init:50'],
        callback: async () => {
            // The namespace has to be resolved before the route's label is read, or the menu entry
            // renders as its raw translation key.
            await i18next.loadNamespaces('content-revision-history');
            register();
        }
    });
}
