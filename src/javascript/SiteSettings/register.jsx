import React from 'react';
import {registry} from '@jahia/ui-extender';
import {Setting} from '@jahia/moonstone';

import {SiteSettings} from './SiteSettings';

/**
 * Adds the panel to a SITE's administration, not the server's: that is what the
 * 'administration-sites' target means, and the number after the colon only orders it among its
 * siblings.
 *
 * <p>requiredPermission hides the menu entry; it is not the security boundary. The GraphQL
 * resolvers re-check the permission on the site themselves, because a hidden menu entry stops
 * nobody from issuing the query by hand.
 */
export default function register() {
    registry.add('adminRoute', 'contentRevisionHistorySettings', {
        targets: ['administration-sites:80'],
        requiredPermission: 'siteAdminContentRevisionHistory',
        icon: <Setting/>,
        label: 'content-revision-history:settings.label',
        isSelectable: true,
        render: () => <SiteSettings/>
    });
}
