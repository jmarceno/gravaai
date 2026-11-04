// gravaai/lottie: plays a Lottie asset seeked by frame (Skottie), fitted into the box.
export const description = 'Plays a Lottie animation asset (props.asset) seeked by frame, optionally looping';
export const fps = 30;
export const durationInFrames = 90;
export const defaultProps = { asset: '', loop: true, speed: 1, fadeFrames: 6 };
export const propsSchema = {
  asset: 'string',
  loop: 'boolean',
  speed: { type: 'number', min: 0.05, max: 10 },
  fadeFrames: { type: 'number', min: 0, max: 60 },
};

export function render(ctx, { frame, fps, durationInFrames, width, height, props }) {
  if (!props.asset) {
    // No asset picked yet: a dashed placeholder that says what to do.
    ctx.strokeStyle = 'rgba(255,255,255,0.7)';
    ctx.lineWidth = 4;
    ctx.setLineDash([16, 12]);
    ctx.strokeRect(8, 8, width - 16, height - 16);
    ctx.setLineDash([]);
    ctx.fillStyle = 'rgba(255,255,255,0.85)';
    ctx.font = 'bold ' + Math.max(18, Math.min(width, height) * 0.08) + 'px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('Lottie: set props.asset', width / 2, height / 2);
    return;
  }
  const animation = lottie(props.asset);
  let seconds = (frame / fps) * props.speed;
  if (props.loop && animation.duration > 0) {
    seconds = seconds % animation.duration;
  }
  const fade = props.fadeFrames > 0
    ? Math.min(interpolate(frame, [0, props.fadeFrames], [0, 1], { extrapolateRight: 'clamp' }),
      interpolate(frame, [durationInFrames - props.fadeFrames, durationInFrames], [1, 0],
        { extrapolateLeft: 'clamp' }))
    : 1;
  // Contain: keep the animation's aspect ratio inside the box.
  const scale = Math.min(width / animation.width, height / animation.height);
  const w = animation.width * scale;
  const h = animation.height * scale;
  ctx.globalAlpha = fade;
  animation.draw(ctx, seconds, (width - w) / 2, (height - h) / 2, w, h);
}
