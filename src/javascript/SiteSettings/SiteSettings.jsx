import React, {useEffect, useRef, useState} from 'react';
import {useQuery, useMutation} from '@apollo/client';
import {useTranslation} from 'react-i18next';
import {
    Banner, Button, Field, Header, Input, LayoutContent, Separator, Switch, Typography
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
    // What the last write failed with, if it did. Held separately from the query's own error so a
    // failed save does not replace the form with an error page and lose what was typed into it.
    const [writeError, setWriteError] = useState(null);

    const settings = data?.contentRevisionHistory?.siteSettings;
    const current = draft || settings;
    const busy = saving || removing;

    /**
     * Applies a write, or reports why it did not apply.
     *
     * <p>Both paths matter. Apollo resolves a mutation whose response carries a GraphQL errors array
     * rather than rejecting it, so a plain .then() runs on failure exactly as it does on success:
     * the earlier version cleared the draft and refetched, which threw away what the administrator
     * had typed, greyed out Save and displayed nothing. The write appeared to succeed and silently
     * had not. A rejected promise is also possible, for a transport failure, so both are handled.
     *
     * <p>The draft is deliberately kept on failure. The values in it are the user's work, and a
     * failed write is precisely when they must not be discarded.
     */
    const applyWrite = run => {
        setWriteError(null);
        return run()
            .then(result => {
                const failure = result?.errors?.[0]?.message;
                if (failure) {
                    setWriteError(failure);
                    return undefined;
                }

                setDraft(null);
                return refetch();
            })
            .catch(rejected => setWriteError(rejected?.message || String(rejected)));
    };

    const onSave = () => applyWrite(() => save({
        variables: {
            siteKey,
            captureEnabled: current.captureEnabled,
            maxSnapshots: Number(current.maxSnapshots),
            // '' means "clear it", null means "leave it alone" -- see the note on the
            // saveSiteSettings mutation. Sending `|| null` for an emptied field meant a field
            // could never be cleared once set: the server read the null as an omission and wrote
            // the old value straight back, so an administrator who mistyped the capture endpoint
            // had no way to empty it. ?? '' keeps a deliberately emptied field distinguishable
            // from one this panel never loaded.
            captureUser: current.captureUser ?? '',
            baseUrl: current.baseUrl ?? ''
        }
    }));

    const onReset = () => applyWrite(() => remove({variables: {siteKey}}));

    const update = changes => setDraft({...current, ...changes});

    // One decision, used by both the button and the keyboard shortcut. Computing it twice is how
    // they drift, and a shortcut that saves while the button says it cannot is worse than no
    // shortcut: it writes when the user has been told nothing will be written.
    const canSave = Boolean(draft) && !busy;

    // Ctrl+Enter, or Cmd+Enter on a Mac, saves.
    //
    // Held in a ref rather than closed over, so the listener is attached once for the life of the
    // panel instead of being torn down and re-added on every keystroke, and still never fires a
    // stale version of onSave.
    //
    // The listener is on window because the shortcut has to work while focus is inside a field,
    // which is where it will be used: type a value, then commit it without reaching for the mouse.
    // Unmounting removes it, so it is scoped to this route and cannot leak into the rest of the
    // shell. A modifier combination is also exempt from WCAG 2.1.4, which governs single-character
    // shortcuts; it needs no remapping control.
    const saveNow = useRef(null);
    saveNow.current = canSave ? onSave : null;

    useEffect(() => {
        const onKeyDown = event => {
            if (event.key !== 'Enter' || !(event.ctrlKey || event.metaKey)) {
                return;
            }

            if (!saveNow.current) {
                return;
            }

            event.preventDefault();
            saveNow.current();
        };

        window.addEventListener('keydown', onKeyDown);
        return () => window.removeEventListener('keydown', onKeyDown);
    }, []);

    const body = () => {
        if (!siteKey) {
            return <Banner variant="warning" title={t('settings.noSite')}>{''}</Banner>;
        }

        if (error) {
            // The server refuses with a message naming the permission and the path, so showing it is
            // more useful than replacing it with a generic failure.
            return (
                <Banner
                    data-sel-role="crh-settings-error"
                    role="alert"
                    // Banner sets aria-label from its title prop on the region's own root div. On a
                    // live region that name can be announced in place of the changed children, and
                    // the children are where the reason lives -- so the reason goes into the name
                    // too. Without this the alert fires and says only the panel's name.
                    aria-label={`${t('settings.label')}: ${error.message}`}
                    variant="danger"
                    title={t('settings.label')}
                >
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
                {writeError && (
                    <Banner
                        data-sel-role="crh-write-error"
                        // moonstone's Banner renders a plain div: aria-label only, no role and no
                        // aria-live. A failed save was therefore shown but never announced, so a
                        // screen reader user had to re-explore the panel to discover whether their
                        // change applied. role="alert" is assertive because this reports that the
                        // write did NOT happen and the draft is still unsaved.
                        role="alert"
                        // And the reason is put into the accessible name as well as the children.
                        // Banner derives aria-label from title, which on a live region can be
                        // announced instead of the changed children, leaving the user told that
                        // saving failed but never why.
                        aria-label={`${t('settings.saveFailed')}: ${writeError}`}
                        variant="danger"
                        title={t('settings.saveFailed')}
                    >{writeError}</Banner>
                )}

                <Banner
                    // Polite, not assertive: this reports which settings are in force and changes
                    // as a side effect of saving, so it must not interrupt.
                    role="status"
                    variant={current.configured ? 'info' : 'neutral'}
                    // The message is the CHILDREN, and the title is a fixed heading. It used to be
                    // the other way round, with `{''}` for children: Banner surfaces title as the
                    // region's aria-label, so the only thing that changed when this flipped after a
                    // Save was an attribute the region draws its own name from. A live region
                    // announces changed CONTENT, so most screen readers said nothing at all -- the
                    // same defect already fixed on the two Banners above this one.
                    title={t('settings.settingsSource')}
                >{current.configured ? t('settings.configured') : t('settings.usingDefaults')}</Banner>

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

                {/*
                    The helper is rendered here rather than through Field's `helper` prop. Field
                    renders that prop as a caption with no id and never wires aria-describedby, so
                    whether a credential resolved -- which decides whether restricted pages can be
                    captured at all -- reached sighted users only. aria-describedby needs an element
                    with an id, so the element is ours.
                */}
                <Field id="crh-field-capture-user" label={t('settings.captureUser')}>
                    <Input
                        data-sel-role="crh-capture-user"
                        aria-label={t('settings.captureUser')}
                        aria-describedby="crh-capture-user-help"
                        value={current.captureUser || ''}
                        placeholder={t('settings.captureUserPlaceholder')}
                        onChange={e => update({captureUser: e.target.value})}
                    />
                    <Typography id="crh-capture-user-help" variant="caption">
                        {current.credentialResolved
                            ? t('settings.credentialResolved')
                            : t('settings.credentialMissing')}
                    </Typography>
                </Field>

                <Field id="crh-field-base-url" label={t('settings.baseUrl')}>
                    <Input
                        data-sel-role="crh-base-url"
                        aria-label={t('settings.baseUrl')}
                        aria-describedby="crh-base-url-help"
                        value={current.baseUrl || ''}
                        placeholder={t('settings.baseUrlPlaceholder')}
                        onChange={e => update({baseUrl: e.target.value})}
                    />
                    {/* The loopback-versus-public-host warning explains why capture silently 404s
                        when this is set wrong; it must not be sighted-only. */}
                    <Typography id="crh-base-url-help" variant="caption">
                        {t('settings.baseUrlHint')}
                    </Typography>
                </Field>

                <Typography variant="caption" data-sel-role="crh-shortcut-hint">
                    {t('settings.saveShortcut')}
                </Typography>
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
                                title={t('settings.saveShortcut')}
                                isDisabled={!canSave}
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
