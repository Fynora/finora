import { useEffect, useRef } from 'react';

/**
 * Fires once when a referral grant activates (design spec section 6.4) -- Premium gets the full
 * sequence (sidebar-edge glow sweep, badge pop/shine/ring, confetti burst); Plus gets pop + one
 * shine sweep only, deliberately smaller so reaching Premium later still feels like the bigger
 * moment. Colors and timings were validated live in a browser mockup during design, not re-derived
 * here.
 */
export function UpgradeCelebration({ tier }: { tier: 'PLUS' | 'PREMIUM' }) {
  const stageRef = useRef<HTMLDivElement>(null);
  const badgeRef = useRef<HTMLSpanElement>(null);
  const shineRef = useRef<HTMLSpanElement>(null);
  const ringRef = useRef<HTMLSpanElement>(null);
  const glowRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    function reset(el: HTMLElement | null) {
      if (!el) return;
      el.classList.remove('playing', 'referral-badge-pop');
      void el.offsetWidth;
    }

    function spawnConfetti(container: HTMLElement, colors: string[]) {
      const rect = container.getBoundingClientRect();
      for (let i = 0; i < 28; i++) {
        const p = document.createElement('div');
        p.style.position = 'absolute';
        p.style.width = '6px';
        p.style.height = '9px';
        p.style.left = `${rect.width / 2}px`;
        p.style.top = '38px';
        p.style.background = colors[i % colors.length];
        p.style.borderRadius = Math.random() > 0.5 ? '50%' : '2px';
        p.style.pointerEvents = 'none';
        container.appendChild(p);
        const angle = Math.random() * Math.PI - Math.PI - Math.PI / 2;
        const dist = 60 + Math.random() * 100;
        const dx = Math.cos(angle) * dist;
        const dy = Math.sin(angle) * dist - 20;
        const rot = Math.random() * 720 - 360;
        p.animate(
          [
            { transform: 'translate(0,0) rotate(0deg)', opacity: 1 },
            { transform: `translate(${dx}px, ${dy + 90}px) rotate(${rot}deg)`, opacity: 0 },
          ],
          { duration: 900 + Math.random() * 500, easing: 'cubic-bezier(.25,.8,.25,1)', fill: 'forwards' },
        );
        setTimeout(() => p.remove(), 1500);
      }
    }

    if (reduceMotion) return;

    if (tier === 'PREMIUM') {
      reset(glowRef.current);
      requestAnimationFrame(() => glowRef.current?.classList.add('playing'));
      const timer = setTimeout(() => {
        [badgeRef.current, shineRef.current, ringRef.current].forEach(reset);
        badgeRef.current?.classList.add('referral-badge-pop');
        shineRef.current?.classList.add('playing');
        ringRef.current?.classList.add('playing');
        if (stageRef.current) spawnConfetti(stageRef.current, ['#F4F1EC', '#34A788', '#98968F', '#0F4C3F']);
      }, 350);
      return () => clearTimeout(timer);
    }

    reset(badgeRef.current);
    reset(shineRef.current);
    badgeRef.current?.classList.add('referral-badge-pop');
    shineRef.current?.classList.add('playing');
  }, [tier]);

  const badgeColors =
    tier === 'PREMIUM'
      ? { background: 'var(--color-premium-bg)', color: 'var(--color-premium)', border: 'none' }
      : { background: '#2E2D2A', color: '#F4F1EC', border: '1px solid #D9D5CB' };

  return (
    <div ref={stageRef} className="relative inline-flex items-center" data-testid="upgrade-celebration">
      {tier === 'PREMIUM' && <div ref={glowRef} className="referral-glow-border" />}
      <span
        ref={badgeRef}
        className="relative inline-flex items-center gap-1 text-[11px] font-bold px-2.5 py-1 rounded-full"
        style={badgeColors}
      >
        ✦ {tier}
        <span ref={shineRef} className="referral-shine-layer" />
        {tier === 'PREMIUM' && <span ref={ringRef} className="referral-glow-ring" />}
      </span>
    </div>
  );
}
