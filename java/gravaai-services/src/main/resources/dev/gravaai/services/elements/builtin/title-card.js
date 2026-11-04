// gravaai/title-card: a centred title and subtitle on a soft panel.
// Pops in on a spring (scale + opacity), holds, then fades and rises out.
export const description = 'Centred title + subtitle panel: spring pop in, fade and rise out';
export const fps = 30;
export const durationInFrames = 90;
export const defaultProps = {
  title: 'Hello',
  subtitle: '',
  color: '#ffffff',
  accent: '#7c5cff',
  background: 'rgba(12, 12, 20, 0.78)',
  fontSize: 96,
};
export const propsSchema = {
  title: 'string',
  subtitle: 'string',
  color: 'color',
  accent: 'color',
  background: 'color',
  fontSize: { type: 'number', min: 12, max: 300 },
};

export function render(ctx, { frame, fps, durationInFrames, width, height, props }) {
  const pop = spring({ frame, fps, config: { damping: 12, stiffness: 120 } });
  const exit = interpolate(frame, [durationInFrames - 12, durationInFrames], [0, 1],
    { extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: Easing.inOut(Easing.cubic) });
  const opacity = Math.min(1, pop) * (1 - exit);
  if (opacity <= 0) {
    return;
  }
  const size = props.fontSize;
  ctx.font = 'bold ' + size + 'px sans-serif';
  const titleWidth = ctx.measureText(props.title).width;
  const subSize = Math.round(size * 0.38);
  ctx.font = subSize + 'px sans-serif';
  const subWidth = props.subtitle ? ctx.measureText(props.subtitle).width : 0;
  const panelW = Math.max(titleWidth, subWidth) + size * 1.2;
  const panelH = size * 1.5 + (props.subtitle ? subSize * 1.6 : 0);

  ctx.globalAlpha = opacity;
  ctx.translate(width / 2, height / 2 - exit * size * 0.6);
  ctx.scale(0.6 + 0.4 * pop, 0.6 + 0.4 * pop);

  ctx.shadowColor = 'rgba(0,0,0,0.45)';
  ctx.shadowBlur = 40;
  ctx.fillStyle = props.background;
  ctx.beginPath();
  ctx.roundRect(-panelW / 2, -panelH / 2, panelW, panelH, size * 0.28);
  ctx.fill();
  ctx.shadowColor = 'rgba(0,0,0,0)';

  // Accent bar grows from the centre once the panel has popped.
  const bar = interpolate(frame, [6, 22], [0, 1],
    { extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: Easing.out(Easing.cubic) });
  ctx.fillStyle = props.accent;
  const barW = panelW * 0.5 * bar;
  ctx.fillRect(-barW / 2, panelH / 2 - size * 0.16, barW, size * 0.07);

  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillStyle = props.color;
  ctx.font = 'bold ' + size + 'px sans-serif';
  const titleY = props.subtitle ? -subSize * 0.7 : 0;
  ctx.fillText(props.title, 0, titleY);
  if (props.subtitle) {
    const subIn = interpolate(frame, [8, 20], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
    ctx.globalAlpha = opacity * subIn;
    ctx.font = subSize + 'px sans-serif';
    ctx.fillText(props.subtitle, 0, titleY + size * 0.55 + subSize * 0.6 + (1 - subIn) * 12);
  }
}
