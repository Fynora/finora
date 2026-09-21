/** The most characters of an uploaded email accepted; matches the server's limit, and is checked
 *  here too so a wrong file is refused before it is sent. */
export const MAX_EMAIL_CHARS = 5_000_000;

/** The refusal shown for a file over {@link MAX_EMAIL_CHARS}. */
export const EMAIL_TOO_LARGE =
  'That file is too large to be a single email (over 5 MB). Download the original message, not one with large attachments.';

/** FileReader rather than File.text(): same result, and it is what every browser and the test
 *  environment both implement. */
export function readFileText(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ''));
    reader.onerror = () => reject(reader.error ?? new Error('Could not read the file.'));
    reader.readAsText(file);
  });
}
