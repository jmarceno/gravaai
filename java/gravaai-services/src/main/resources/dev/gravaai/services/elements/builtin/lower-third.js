// gravaai/lower-third: name + role panel at the bottom left.
// The accent bar wipes in, then the text slides in on a spring; everything reverses on exit.
export const description = 'Lower third: accent bar wipes in, name and role slide in on a spring, reverse on exit';
export const fps = 30;
export const durationInFrames = 120;
export const defaultProps = {
  name: 'Your Name',
  role: 'Title or role',
  color: '#ffffff',
  accent: '#ff6b4a',
  background: 'rgba(10, 10, 16, 0.82)',
};
export const propsSchema = {
  name: 'string',
  role: 'string',
  color: 'color',
  accent: 'color',
  background: 'color',
};

export function render(ctx, { frame, fps, durationInFrames, width, height, props }) {
  // Exit mirrors the entrance: evaluate the same curves on the frames left.
  const t = Math.min(frame, durationInFrames - frame);
  const wipe = interpolate(t, [0, 10], [0, 1],
    { extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: Easing.out(Easing.cubic) });
  const slide = spring({ frame: t - 6, fps, config: { damping: 14, stiffness: 140 } });
  if (wipe <= 0) {
    return;
  }
  const nameSize = Math.min(64, height * 0.3);
  const roleSize = nameSize * 0.55;
  ctx.font = 'bold ' + nameSize + 'px sans-serif';
  const nameW = ctx.measureText(props.name).width;
  ctx.font = roleSize + 'px sans-serif';
  const roleW = ctx.measureText(props.role).width;
  const panelW = Math.min(width, Math.max(nameW, roleW) + nameSize * 1.4);
  const panelH = nameSize * 1.25 + roleSize * 1.6;
  const x = 0;
  const y = height - panelH;

  ctx.save();
  ctx.beginPath();
  ctx.rect(x, y, panelW * wipe + 12, panelH);
  ctx.clip();
  ctx.fillStyle = props.background;
  ctx.fillRect(x, y, panelW, panelH);
  ctx.restore();

  ctx.fillStyle = props.accent;
  ctx.fillRect(x, y, 10, panelH * wipe);

  ctx.save();
  ctx.beginPath();
  ctx.rect(x + 10, y, panelW, panelH);
  ctx.clip();
  ctx.globalAlpha = Math.max(0, Math.min(1, slide));
  const dx = (1 - slide) * -panelW * 0.4;
  ctx.textBaseline = 'top';
  ctx.fillStyle = props.color;
  ctx.font = 'bold ' + nameSize + 'px sans-serif';
  ctx.fillText(props.name, x + nameSize * 0.6 + dx, y + nameSize * 0.25);
  ctx.globalAlpha = Math.max(0, Math.min(1, slide)) * 0.8;
  ctx.font = roleSize + 'px sans-serif';
  ctx.fillText(props.role, x + nameSize * 0.6 + dx * 1.3, y + nameSize * 1.35);
  ctx.restore();
}
