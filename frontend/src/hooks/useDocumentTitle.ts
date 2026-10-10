import { useEffect } from 'react';

/**
 * Sets the browser-tab title while the component is mounted and puts the previous one back on
 * unmount. The same contract PublicLayout follows for the public pages.
 *
 * Why the app needs it: index.html's <title> is written for a search result ("Bank statement
 * analyzer for Indian banks and cards — Fynora"), and nothing inside the signed-in app or the auth
 * flow set its own title, so every dashboard and sign-in tab would have carried that sentence.
 * A tab is not a search result; a signed-in user should see the product's name.
 */
export function useDocumentTitle(title: string): void {
  useEffect(() => {
    const previous = document.title;
    document.title = title;
    return () => {
      document.title = previous;
    };
  }, [title]);
}
