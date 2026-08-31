import React, {useState} from 'react';
import {useQuery, useMutation} from '@apollo/client';
import {useTranslation} from 'react-i18next';
// Checkbox, not Toggle: moonstone 1.5.3 has no Toggle component. Checked against the installed
// package rather than assumed -- a missing export builds to a runtime undefined, not a build error.
import {
    Button, Checkbox, Header, Input, Loader, Typography
} from '@jahia/moonstone';

import {DELETE_SITE_SETTINGS, GET_SITE_SETTINGS, SAVE_SITE_SETTINGS} from './SiteSettings.gql';

/**
 * Per-site revision capture settings.
 *
 * The secret is deliberately absent. It is resolved from a file whose permissions an administrator
 * controls, and it is neither readable nor writable through the API: a value typed here would travel
 * in a GraphQL request, which is logged, cached and pasted into bug reports. The panel reports only
 * whether a credential resolved.
 */
export const SiteSettings = () => {
    const {t} = useTranslation('content-revision-history');
    // The site key comes from the shell's own context. @jahia/data-helper would give the display
    // name too, but it peers on react ^16.12 and buys one label, so it is not worth the dependency.
    const siteKey = window.contextJsParameters?.siteKey;

    const {data, loading, error, refetch} = useQuery(GET_SITE_SETTINGS, {
        variables: {siteKey},
        skip: !siteKey,
        fetchPolicy: 'network-only'
    });

    const [save, {loading: saving}] = useMutation(SAVE_SITE_SETTINGS);
    const [remove, {loading: removing}] = useMutation(DELETE_SITE_SETTINGS);
    const [draft, setDraft] = useState(null);

    if (!siteKey) {
        return <Typography>{t('settings.noSite')}</Typography>;
    }

    if (loading) {
        return <Loader size="big"/>;
    }

    if (error) {
        // The server refuses with a message naming the permission and the path, so showing it is
        // more useful than replacing it with a generic failure.
        return <Typography data-sel-role="crh-settings-error">{error.message}</Typography>;
    }

    const settings = data?.contentRevisionHistory?.siteSettings;
    const current = draft || settings;

    const onSave = () => save({
        variables: {
            siteKey,
            captureEnabled: current.captureEnabled,
            maxSnapshots: Number(current.maxSnapshots),
            captureUser: current.captureUser || null,
            baseUrl: current.baseUrl || null
        }
    }).then(() => {
        setDraft(null);
        return refetch();
    });

    const onReset = () => remove({variables: {siteKey}}).then(() => {
        setDraft(null);
        return refetch();
    });

    const update = changes => setDraft({...current, ...changes});

    return (
        <div data-sel-role="crh-site-settings">
            <Header title={t('settings.title', {site: siteKey})}/>

            <Typography variant="caption">
                {current.configured ? t('settings.configured') : t('settings.usingDefaults')}
            </Typography>

            <div>
                <Checkbox
                    data-sel-role="crh-capture-enabled"
                    checked={current.captureEnabled}
                    onChange={() => update({captureEnabled: !current.captureEnabled})}
                />
                <Typography>{t('settings.captureEnabled')}</Typography>
            </div>

            <div>
                <Typography>{t('settings.maxSnapshots')}</Typography>
                <Input
                    data-sel-role="crh-max-snapshots"
                    type="number"
                    min={1}
                    value={String(current.maxSnapshots)}
                    onChange={e => update({maxSnapshots: e.target.value})}
                />
            </div>

            <div>
                <Typography>{t('settings.captureUser')}</Typography>
                <Input
                    data-sel-role="crh-capture-user"
                    value={current.captureUser || ''}
                    placeholder={t('settings.captureUserPlaceholder')}
                    onChange={e => update({captureUser: e.target.value})}
                />
                <Typography variant="caption">
                    {current.credentialResolved
                        ? t('settings.credentialResolved')
                        : t('settings.credentialMissing')}
                </Typography>
            </div>

            <div>
                <Typography>{t('settings.baseUrl')}</Typography>
                <Input
                    data-sel-role="crh-base-url"
                    value={current.baseUrl || ''}
                    placeholder={t('settings.baseUrlPlaceholder')}
                    onChange={e => update({baseUrl: e.target.value})}
                />
                <Typography variant="caption">{t('settings.baseUrlHint')}</Typography>
            </div>

            <div>
                <Button
                    data-sel-role="crh-save"
                    size="big"
                    label={t('settings.save')}
                    isDisabled={saving || removing || !draft}
                    onClick={onSave}
                />
                <Button
                    data-sel-role="crh-reset"
                    size="big"
                    variant="ghost"
                    label={t('settings.reset')}
                    isDisabled={saving || removing || !current.configured}
                    onClick={onReset}
                />
            </div>
        </div>
    );
};
