// ***********************************************************
// This example support/index.js is processed and
// loaded automatically before your test files.
//
// This is a great place to put global configuration and
// behavior that modifies Cypress.
//
// You can change the location of this file or turn off
// automatically serving support files with the
// 'supportFile' configuration option.
//
// You can read more here:
// https://on.cypress.io/configuration
// ***********************************************************

// Import commands.js using ES2015 syntax:

import './commands';
import addContext from 'mochawesome/addContext';
import {jsErrorsLogger} from '@jahia/cypress';

// Enable and attach JS Errors Logger
jsErrorsLogger.enable();
// Define allowed JS warnings to ignore them in the logs
jsErrorsLogger.setAllowedJsWarnings([
    'Unsatisfied version',
    'No satisfying version'
]);

// eslint-disable-next-line @typescript-eslint/no-require-imports
require('cypress-terminal-report/src/installLogsCollector')();
// eslint-disable-next-line @typescript-eslint/no-require-imports
require('@jahia/cypress/dist/support/registerSupport').registerSupport();

Cypress.on('uncaught:exception', err => {
    // Still returns false, so one stray error in Jahia's own shell does not fail an unrelated
    // spec. But it no longer does so SILENTLY: an unhandled exception used to vanish completely,
    // and a test asserting that something is ABSENT then passed because the page had crashed
    // before it could render, which is indistinguishable from the feature being correctly hidden.
    //
    // The message is logged into the command log and the terminal report, and collected so a spec
    // that asserts an absence can also assert that nothing blew up:
    //     expect(Cypress.env('uncaughtErrors') || []).to.be.empty;
    Cypress.log({name: 'uncaught', message: err.message, consoleProps: () => ({error: err})});
    const seen = Cypress.env('uncaughtErrors') || [];
    seen.push(err.message);
    Cypress.env('uncaughtErrors', seen);
    return false;
});

// Each test starts from a clean slate, or the first stray error would taint every later test.
beforeEach(() => {
    Cypress.env('uncaughtErrors', []);
});
if (Cypress.browser.family === 'chromium') {
    Cypress.automation('remote:debugger:protocol', {
        command: 'Network.enable',
        params: {}
    });
    Cypress.automation('remote:debugger:protocol', {
        command: 'Network.setCacheDisabled',
        params: {cacheDisabled: true}
    });
}

Cypress.on('test:after:run', (test, runnable) => {
    let videoName = Cypress.spec.relative;
    videoName = videoName.replace('/.cy.*', '').replace('cypress/e2e/', '');
    const videoUrl = 'videos/' + videoName + '.mp4';
    addContext({test}, videoUrl);
    if (test.state === 'failed') {
        const screenshot = `screenshots/${Cypress.spec.relative.replace('cypress/e2e/', '')}/${runnable.parent.title} -- ${test.title} (failed).png`;
        addContext({test}, screenshot);
    }
});
