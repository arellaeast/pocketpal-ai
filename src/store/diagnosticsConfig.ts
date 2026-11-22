/**
 * The diagnostics upload token is intentionally NOT committed: this
 * fork's repository is public, and a committed token would be public
 * too. The release build overwrites this file with the real token
 * before bundling. With an empty token the app never uploads;
 * manual sharing still works.
 */
export const DIAGNOSTICS_UPLOAD_TOKEN = '';
