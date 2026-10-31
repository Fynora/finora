import { Link } from 'react-router-dom';

/**
 * The two boxes, neither ticked to start with (V302): the required "18 or older and agree" one --
 * left out when this user has already accepted the current version -- and the optional AI one.
 */
export function ConsentChoices({
  showTermsBox, termsAccepted, onTermsAcceptedChange, aiEnabled, onAiEnabledChange, idPrefix,
}: {
  showTermsBox: boolean;
  termsAccepted: boolean;
  onTermsAcceptedChange: (value: boolean) => void;
  aiEnabled: boolean;
  onAiEnabledChange: (value: boolean) => void;
  /** Keeps the ids unique when two of these could be on one page. */
  idPrefix: string;
}) {
  return (
    <div className="space-y-3 text-left">
      {showTermsBox && (
        <label htmlFor={`${idPrefix}-terms`} className="flex items-start gap-2.5 text-sm text-ink cursor-pointer">
          <input
            id={`${idPrefix}-terms`}
            type="checkbox"
            checked={termsAccepted}
            onChange={(e) => onTermsAcceptedChange(e.target.checked)}
            className="mt-0.5 h-4 w-4 flex-shrink-0 accent-primary"
          />
          <span>
            I am 18 or older and agree to the{' '}
            <Link to="/terms" target="_blank" rel="noopener noreferrer" className="text-primary font-medium">Terms of Service</Link>{' '}
            and{' '}
            <Link to="/privacy" target="_blank" rel="noopener noreferrer" className="text-primary font-medium">Privacy Policy</Link>
            <span className="text-muted"> (required)</span>
          </span>
        </label>
      )}
      <label htmlFor={`${idPrefix}-ai`} className="flex items-start gap-2.5 text-sm text-ink cursor-pointer">
        <input
          id={`${idPrefix}-ai`}
          type="checkbox"
          checked={aiEnabled}
          onChange={(e) => onAiEnabledChange(e.target.checked)}
          className="mt-0.5 h-4 w-4 flex-shrink-0 accent-primary"
        />
        <span>
          Use AI features: Ask Fyn, AI insights and AI category suggestions
          <span className="text-muted"> (optional)</span>
        </span>
      </label>
    </div>
  );
}
