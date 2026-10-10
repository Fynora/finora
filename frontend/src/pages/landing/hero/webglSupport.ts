/**
 * Detects real WebGL support by actually creating a context, rather than trusting the UA string
 * -- some browsers report support they can't deliver (GPU blocklisted, disabled by policy), and
 * creating the context is the only reliable way to find out before React Three Fiber tries and
 * throws mid-render.
 *
 * The probe context is released as soon as it has answered. A context that is only dropped stays
 * live until garbage collection and counts toward Chrome's cap on live contexts (16), past which
 * Chrome evicts the oldest one. AmbientCanvas probes again each time a narrow window widens back to
 * desktop, so an unreleased probe left one more live context behind per resize. Measured in
 * headless Chrome: 20 narrow/wide toggles left 16 live contexts and six "Too many active WebGL
 * contexts" warnings without the release.
 */
export function isWebglAvailable(): boolean {
  let gl: WebGLRenderingContext | WebGL2RenderingContext | null;
  try {
    const canvas = document.createElement('canvas');
    gl = canvas.getContext('webgl2') || canvas.getContext('webgl');
  } catch {
    return false;
  }
  if (!gl) return false;
  // Separate from the probe above: failing to release a context that was created does not mean
  // WebGL is unavailable.
  try {
    gl.getExtension('WEBGL_lose_context')?.loseContext();
  } catch {
    // Best effort; the context is still reclaimed when garbage collected.
  }
  return true;
}
