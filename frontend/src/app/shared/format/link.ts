/** Splits an app-internal link like `/bank?application=5` into router path and query params. */
export function splitLink(link: string): { path: string; queryParams: Record<string, string> } {
  const url = new URL(link, 'http://app.local');
  const queryParams: Record<string, string> = {};
  url.searchParams.forEach((v, k) => (queryParams[k] = v));
  return { path: url.pathname, queryParams };
}
