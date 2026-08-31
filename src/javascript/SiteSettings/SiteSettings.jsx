import React, {useState} from 'react';
import {useQuery, useMutation} from '@apollo/client';
import {useTranslation} from 'react-i18next';
import {
    Banner, Button, Field, Header, Input, LayoutContent, Separator, Switch
} from '@jahia/moonstone';

import {DELETE_SITE_SETTINGS, GET_SITE_SETTINGS, SAVE_SITE_SETTINGS} from './SiteSettings.gql';
import styles from './SiteSettings.css';

/**
 * Per-site revision capture settings.
 *
 * <p>Built from moonstone's own layout and field components rather than plain elements. LayoutContent
 * is what supplies the page padding: without it the panel renders flush against the top left corner
 * of the frame, which is what a hand-rolled div did here before.
 *
 * <p>One gap worth knowing about: moonstone's Field puts its id on the WRAPPER div and renders its
 * label as a Typography with component="label" and NO htmlFor, so it is a visible label that names
 * nothing programmatically. Only FieldBoolean wires htmlFor, and it does so to a hardcoded id that
 * would collide if used twice. So each control below carries its own aria-label with the same text
 * as the visible label; without it a screen reader announces an unnamed edit field.
 *
 * <p>The secret is deliberately absent. It is resolved from a file whose permissions an administrator
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

    const settings = data?.contentRevisionHistory?.siteSettings;
    const current = draft || settings;
    const busy = saving || removing;

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

    const body = () => {
        if (!siteKey) {
            return <Banner variant="warning" title={t('settings.noSite')}>{''}</Banner>;
        }

        if (error) {
            // The server refuses with a message naming the permission and the path, so showing it is
            // more useful than replacing it with a generic failure.
            return (
                <Banner data-sel-role="crh-settings-error" variant="danger"
                        title={t('settings.label')}>
                    {error.message}
                </Banner>
            );
        }

        // LayoutContent evaluates its content prop eagerly, so this runs even while isLoading is
        // replacing it on screen. Without this guard the first render reads current.configured off
        // an undefined settings object and the whole panel throws.
        if (!current) {
            return null;
        }

        return (
            <div className={styles.form}>
                <Banner
                    variant={current.configured ? 'info' : 'neutral'}
                    title={current.configured ? t('settings.configured') : t('settings.usingDefaults')}
                >{''}</Banner>

                <Separator spacing="medium" invisible="firstOrLastChild"/>

                <Field id="crh-field-capture-enabled" label={t('settings.captureEnabled')}>
                    <Switch
                        data-sel-role="crh-capture-enabled"
                        aria-label={t('settings.captureEnabled')}
                        checked={current.captureEnabled}
                        onChange={(e, value, checked) => update({captureEnabled: checked})}
                    />
                </Field>

                <Field id="crh-field-max-snapshots" label={t('settings.maxSnapshots')}>
                    <Input
                        data-sel-role="crh-max-snapshots"
                        aria-label={t('settings.maxSnapshots')}
                        type="number"
                        min={1}
                        value={String(current.maxSnapshots)}
                        onChange={e => update({maxSnapshots: e.target.value})}
                    />
                </Field>

                <Field
                    id="crh-field-capture-user"
                    label={t('settings.captureUser')}
                    helper={current.credentialResolved
                        ? t('settings.credentialResolved')
                        : t('settings.credentialMissing')}
                >
                    <Input
                        data-sel-role="crh-capture-user"
                        aria-label={t('settings.captureUser')}
                        value={current.captureUser || ''}
                        placeholder={t('settings.captureUserPlaceholder')}
                        onChange={e => update({captureUser: e.target.value})}
                    />
                </Field>

                <Field
                    id="crh-field-base-url"
                    label={t('settings.baseUrl')}
                    helper={t('settings.baseUrlHint')}
                >
                    <Input
                        data-sel-role="crh-base-url"
                        aria-label={t('settings.baseUrl')}
                        value={current.baseUrl || ''}
                        placeholder={t('settings.baseUrlPlaceholder')}
                        onChange={e => update({baseUrl: e.target.value})}
                    />
                </Field>
            </div>
        );
    };

    return (
        <LayoutContent
            data-sel-role="crh-site-settings"
            hasPadding
            isLoading={Boolean(siteKey) && loading}
            header={
                <Header
                    title={t('settings.title', {site: siteKey})}
                    mainActions={settings && !error ? (
                        <>
                            <Button
                                data-sel-role="crh-reset"
                                size="big"
                                variant="ghost"
                                label={t('settings.reset')}
                                isDisabled={busy || !current?.configured}
                                onClick={onReset}
                            />
                            <Button
                                data-sel-role="crh-save"
                                size="big"
                                label={t('settings.save')}
                                isDisabled={busy || !draft}
                                onClick={onSave}
                            />
                        </>
                    ) : null}
                />
            }
            content={body()}
        />
    );
};
