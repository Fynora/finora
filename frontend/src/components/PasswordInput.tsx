import { forwardRef, useState } from 'react';
import { Eye, EyeOff } from 'lucide-react';

interface PasswordInputProps {
  value: string;
  onChange: (value: string) => void;
  onBlur?: () => void;
  required?: boolean;
  minLength?: number;
  maxLength?: number;
  className?: string;
  placeholder?: string;
  autoComplete?: string;
  // Bug fix: this component rendered no <label> of its own and had no way for a caller to give
  // its <input> an id to pair one with -- every page using it (Login/Register/ResetPassword) had
  // a <label> that was just a visual sibling, never programmatically associated via htmlFor/id.
  // Optional so existing callers that render their own unlabeled input (rare) keep compiling.
  id?: string;
  // The three the statement-password fields (Import's upload panel, StatementHistory's re-import
  // prompt) need to adopt this component instead of a bare <input type="password">: both disable
  // the field while a request is in flight, both point it at a help/error line, and the re-import
  // prompt focuses it on open. A bank's statement password is exactly the kind of value a user
  // cannot type twice to confirm -- the only feedback is the upload failing -- so the show/hide
  // toggle matters more there than on a login form.
  disabled?: boolean;
  'aria-describedby'?: string;
  autoFocus?: boolean;
}

export const PasswordInput = forwardRef<HTMLInputElement, PasswordInputProps>(function PasswordInput({
  value, onChange, onBlur, required, minLength, maxLength, className, placeholder, autoComplete, id,
  disabled, 'aria-describedby': ariaDescribedBy, autoFocus,
}, ref) {
  const [visible, setVisible] = useState(false);

  return (
    <div className="relative">
      <input
        ref={ref}
        id={id}
        type={visible ? 'text' : 'password'}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
        required={required}
        minLength={minLength}
        maxLength={maxLength}
        placeholder={placeholder}
        autoComplete={autoComplete}
        disabled={disabled}
        aria-describedby={ariaDescribedBy}
        autoFocus={autoFocus}
        className={className ?? 'w-full border border-border rounded-lg px-3 py-2.5 pr-10 text-sm bg-card text-ink focus:outline-none focus:ring-2 focus:ring-primary/30'}
      />
      <button
        type="button"
        onClick={() => setVisible((v) => !v)}
        tabIndex={-1}
        disabled={disabled}
        className="absolute right-3 top-1/2 -translate-y-1/2 text-muted hover:text-ink disabled:opacity-50"
        aria-label={visible ? 'Hide password' : 'Show password'}
      >
        {visible ? <EyeOff size={16} /> : <Eye size={16} />}
      </button>
    </div>
  );
});
