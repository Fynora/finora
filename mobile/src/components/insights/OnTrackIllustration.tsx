import Svg, { Circle, Path } from 'react-native-svg';

/** Layered hills + a sun -- the "You're on track" banner's illustration. Hand-drawn with
 *  react-native-svg primitives, same approach DonutChart.tsx uses for its own chart: no bitmap
 *  asset, nothing to keep in sync with an external design tool. */
export function OnTrackIllustration({ width = 120, height = 72 }: { width?: number; height?: number }) {
  return (
    <Svg width={width} height={height} viewBox="0 0 120 72">
      <Circle cx="96" cy="20" r="12" fill="#E8B84B" opacity={0.85} />
      <Path d="M0 56 Q20 32 40 50 T80 44 T120 52 V72 H0 Z" fill="#B7C9AE" opacity={0.55} />
      <Path d="M0 64 Q30 44 60 60 T120 58 V72 H0 Z" fill="#7C9473" opacity={0.75} />
    </Svg>
  );
}
