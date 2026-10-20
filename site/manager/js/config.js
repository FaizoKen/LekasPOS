// Google sign-in for the manager web page: an OAuth client of type "Web application" in the app's Google
// Cloud project `lekaspos` (the same project as the tills' Android clients, so it sees the same hidden app
// folder), with the authorized JavaScript origin https://faizoken.github.io. A client ID is not a secret:
// it only tells Google which app asks. Empty: the page offers backup files only (README, Google Cloud setup).
export const GOOGLE_CLIENT_ID = '';

/** The tills' own permission: the shop's hidden app folder only (D-014). */
export const DRIVE_SCOPE = 'https://www.googleapis.com/auth/drive.appdata';
