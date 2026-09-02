import gql from 'graphql-tag';

/*
 * One namespaced field, matching the server side. Never a flat root field: two bundles registering
 * the same one make DXGraphQLProvider refuse the duplicate and the whole schema fails to build.
 */
export const GET_SITE_SETTINGS = gql`
    query crhSiteSettings($siteKey: String!) {
        contentRevisionHistory {
            siteSettings(siteKey: $siteKey) {
                siteKey
                configured
                captureEnabled
                maxSnapshots
                captureUser
                effectiveCaptureUser
                credentialResolved
                baseUrl
            }
        }
    }
`;

export const SAVE_SITE_SETTINGS = gql`
    mutation crhSaveSiteSettings(
        $siteKey: String!
        $captureEnabled: Boolean
        $maxSnapshots: Int
        $captureUser: String
        $baseUrl: String
    ) {
        contentRevisionHistory {
            saveSiteSettings(
                siteKey: $siteKey
                captureEnabled: $captureEnabled
                maxSnapshots: $maxSnapshots
                captureUser: $captureUser
                baseUrl: $baseUrl
            ) {
                siteKey
                configured
                captureEnabled
                maxSnapshots
                captureUser
                effectiveCaptureUser
                credentialResolved
                baseUrl
            }
        }
    }
`;

export const DELETE_SITE_SETTINGS = gql`
    mutation crhDeleteSiteSettings($siteKey: String!) {
        contentRevisionHistory {
            deleteSiteSettings(siteKey: $siteKey)
        }
    }
`;
