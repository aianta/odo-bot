/*
 * Removes Content Security Policy headers from every response.
 *
 * OdoX's guidance script opens a WebSocket from the page to OdoBot. On sites that enforce a CSP, such as the WebArena
 * Forum (Postmill), connect-src blocks that socket. OdoX is MV3, and Firefox ignores CSP header changes made by MV3
 * extensions, so the headers are removed here, in an MV2 add-on, instead.
 */
const CSP_HEADER = /^(x-)?(content-security-policy(-report-only)?|webkit-csp)$/i;

browser.webRequest.onHeadersReceived.addListener(
  (details) => ({
    responseHeaders: details.responseHeaders.filter((header) => !CSP_HEADER.test(header.name)),
  }),
  { urls: ["<all_urls>"] },
  ["blocking", "responseHeaders"]
);
