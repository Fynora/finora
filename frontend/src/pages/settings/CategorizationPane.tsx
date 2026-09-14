import { useEffect, useRef, useState } from 'react';
import { workspaceApi } from '../../api/endpoints';
import { SaveStatus } from '../../components/AccountUI';
import { Skeleton } from '../../design-system/Skeleton';
import { Button } from '../../design-system/Button';
import { FinoraCard } from '../../design-system/FinoraCard';
import { SectionHeader } from '../../design-system/SectionHeader';

export function CategorizationPane() {
  const [confidenceThreshold, setConfidenceThreshold] = useState(90);
  const [savedConfidenceThreshold, setSavedConfidenceThreshold] = useState(90);
  const [intelLoading, setIntelLoading] = useState(true);
  // Bug found in a fresh review pass: this file's predecessor (Settings.tsx's inline
  // Categorization section) never distinguished "failed to load" from "loaded successfully" --
  // a failed workspaceApi.getSettings() silently left confidenceThreshold at its 90 default with
  // no indication anything was wrong, and a save from that state would have overwritten the
  // user's real saved threshold with 90 (or whatever they'd nudged it to from there) the moment
  // their transient network error happened to clear on retry.
  const [intelLoadError, setIntelLoadError] = useState(false);
  const [intelSaving, setIntelSaving] = useState(false);
  const [intelJustSaved, setIntelJustSaved] = useState(false);
  const [intelError, setIntelError] = useState(false);
  const intelJustSavedTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);

  const intelDirty = confidenceThreshold !== savedConfidenceThreshold;

  useEffect(() => {
    workspaceApi.getSettings().then((s) => {
      setConfidenceThreshold(s.autoApplyConfidenceThreshold);
      setSavedConfidenceThreshold(s.autoApplyConfidenceThreshold);
      setIntelLoading(false);
    }).catch(() => {
      setIntelLoadError(true);
      setIntelLoading(false);
    });
    return () => {
      if (intelJustSavedTimeout.current) clearTimeout(intelJustSavedTimeout.current);
    };
  }, []);

  async function saveIntelligencePreferences() {
    setIntelSaving(true);
    setIntelError(false);
    try {
      const s = await workspaceApi.updateSettings({ autoApplyConfidenceThreshold: confidenceThreshold });
      setConfidenceThreshold(s.autoApplyConfidenceThreshold);
      setSavedConfidenceThreshold(s.autoApplyConfidenceThreshold);
      setIntelJustSaved(true);
      if (intelJustSavedTimeout.current) clearTimeout(intelJustSavedTimeout.current);
      intelJustSavedTimeout.current = setTimeout(() => setIntelJustSaved(false), 2000);
    } catch {
      setIntelError(true);
    } finally {
      setIntelSaving(false);
    }
  }

  return (
    <FinoraCard>
      <SectionHeader title="Categorization" />
      <p className="text-sm text-muted -mt-3 mb-5">Control how Fynora reviews and understands your financial documents</p>
      {intelLoading ? (
        <Skeleton.Region label="Loading your AI settings">
          <AISkeletonFields />
        </Skeleton.Region>
      ) : intelLoadError ? (
        <p className="text-muted text-sm">Couldn't load your settings — please try again later.</p>
      ) : (
        <>
          <div className="max-w-md">
            <label htmlFor="settings-confidence-threshold" className="block text-xs uppercase text-muted mb-1">
              Confidence threshold — {confidenceThreshold}%
            </label>
            <input
              id="settings-confidence-threshold"
              type="range"
              min={0}
              max={100}
              value={confidenceThreshold}
              onChange={(e) => setConfidenceThreshold(Number(e.target.value))}
              className="w-full"
            />
            <p className="text-xs text-muted mt-1">
              How confident a categorization suggestion needs to be before it's applied automatically.
            </p>
          </div>
          <div className="flex items-center gap-3 mt-4 pt-4 border-t border-border">
            <Button onClick={saveIntelligencePreferences} disabled={!intelDirty} loading={intelSaving}>Save setting</Button>
            <SaveStatus dirty={intelDirty} saving={intelSaving} justSaved={intelJustSaved} error={intelError} />
          </div>
        </>
      )}
    </FinoraCard>
  );
}

function AISkeletonFields() {
  return (
    <div className="max-w-md">
      <Skeleton.Text width="w-56" className="h-2.5 mb-2" />
      <Skeleton.Block className="h-2 w-full rounded-full" />
      <Skeleton.Text width="w-72" className="h-2.5 mt-2" />
      <div className="mt-4 pt-4 border-t border-border">
        <Skeleton.Block className="h-8 w-28" />
      </div>
    </div>
  );
}
