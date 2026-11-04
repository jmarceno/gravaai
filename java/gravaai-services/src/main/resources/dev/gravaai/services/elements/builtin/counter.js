// gravaai/counter: a number counting from `from` to `to` with a progress bar under it.
export const description = 'Animated number from `from` to `to` (prefix/suffix, decimals) with a progress bar';
export const fps = 30;
export const durationInFrames = 90;
export const defaultProps = {
  from: 0,
  to: 100,
  decimals: 0,
  prefix: '',
  suffix: '%',
  label: '',
  color: '#ffffff',
  accent: '#3ddc97',
  countFrames: 60,
};
export const propsSchema = {
  from: 'number',
  to: 'number',
  decimals: { type: 'number', min: 0, max: 4 },
  prefix: 'string',
  suffix: 'string',
  label: 'string',
  color: 'color',
  accent: 'color',
  countFrames: { type: 'number', min: 1, max: 600 },
};

export function render(ctx, { frame, durationInFrames, width, height, props }) {
  const enter = interpolate(frame, [0, 10], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  const exit = interpolate(frame, [durationInFrames - 10, durationInFrames], [1, 0],
    { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  const progress = interpolate(frame, [0, props.countFrames], [0, 1],
    { extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: Easing.out(Easing.exp) });
  const value = props.from + (props.to - props.from) * progress;
  const text = props.prefix + value.toFixed(props.decimals) + props.suffix;

  ctx.globalAlpha = enter * exit;
  // Size the number to the box, measured on the final value so it does not jump while counting.
  const widest = props.prefix + props.to.toFixed(props.decimals) + props.suffix;
  let size = height * 0.5;
  ctx.font = 'bold ' + size + 'px sans-serif';
  size = Math.min(size, size * (width * 0.9) / Math.max(1, ctx.measureText(widest).width));
  ctx.font = 'bold ' + size + 'px sans-serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillStyle = props.color;
  ctx.shadowColor = 'rgba(0,0,0,0.5)';
  ctx.shadowBlur = 18;
  const cy = props.label ? height * 0.42 : height * 0.45;
  ctx.fillText(text, width / 2, cy);
  ctx.shadowColor = 'rgba(0,0,0,0)';
  if (props.label) {
    let labelSize = size * 0.3;
    ctx.font = labelSize + 'px sans-serif';
    labelSize = Math.min(labelSize, labelSize * (width * 0.92) / Math.max(1, ctx.measureText(props.label).width));
    ctx.font = labelSize + 'px sans-serif';
    ctx.globalAlpha = enter * exit * 0.85;
    ctx.fillText(props.label, width / 2, cy + size * 0.62);
  }
  const barW = width * 0.6;
  const barY = height * 0.86;
  ctx.globalAlpha = enter * exit * 0.35;
  ctx.fillStyle = props.color;
  ctx.beginPath();
  ctx.roundRect(width / 2 - barW / 2, barY, barW, 10, 5);
  ctx.fill();
  ctx.globalAlpha = enter * exit;
  ctx.fillStyle = props.accent;
  ctx.beginPath();
  ctx.roundRect(width / 2 - barW / 2, barY, Math.max(10, barW * progress), 10, 5);
  ctx.fill();
}
