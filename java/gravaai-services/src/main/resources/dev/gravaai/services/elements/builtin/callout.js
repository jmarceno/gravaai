// gravaai/callout: a highlight shape (box or ellipse) drawn around the element box, with a label
// that follows a few frames later. Place the element box over what you want to point at.
export const description = 'Highlight box/ellipse that draws itself around the element box, with a delayed label';
export const fps = 30;
export const durationInFrames = 90;
export const defaultProps = {
  shape: 'box',
  label: 'Look here',
  color: '#ffd23f',
  lineWidth: 8,
  labelPosition: 'top',
};
export const propsSchema = {
  shape: { type: 'enum', values: ['box', 'ellipse'] },
  label: 'string',
  color: 'color',
  lineWidth: { type: 'number', min: 1, max: 40 },
  labelPosition: { type: 'enum', values: ['top', 'bottom'] },
};

export function render(ctx, { frame, durationInFrames, width, height, props }) {
  const draw = interpolate(frame, [0, 18], [0, 1],
    { extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: Easing.inOut(Easing.cubic) });
  const out = interpolate(frame, [durationInFrames - 10, durationInFrames], [1, 0],
    { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  const pad = props.lineWidth;
  const w = width - pad * 2;
  const h = height - pad * 2;
  const perimeter = props.shape === 'ellipse'
    ? Math.PI * (3 * (w + h) / 2 - Math.sqrt((3 * w / 2 + h / 2) * (w / 2 + 3 * h / 2)))
    : 2 * (w + h);

  ctx.globalAlpha = out;
  ctx.strokeStyle = props.color;
  ctx.lineWidth = props.lineWidth;
  ctx.lineCap = 'round';
  ctx.setLineDash([perimeter * draw, perimeter]);
  ctx.beginPath();
  if (props.shape === 'ellipse') {
    ctx.ellipse(width / 2, height / 2, w / 2, h / 2, 0, -Math.PI / 2, Math.PI * 1.5);
  } else {
    ctx.roundRect(pad, pad, w, h, Math.min(w, h) * 0.08);
  }
  ctx.stroke();
  ctx.setLineDash([]);

  if (!props.label) {
    return;
  }
  const pop = spring({ frame: frame - 12, fps: 30, config: { damping: 11 } });
  if (pop <= 0) {
    return;
  }
  const size = 34;
  ctx.font = 'bold ' + size + 'px sans-serif';
  const tw = ctx.measureText(props.label).width;
  const bw = tw + size;
  const bh = size * 1.5;
  const atBottom = props.labelPosition === 'bottom';
  const by = atBottom ? height - bh - pad * 3 : pad * 3;
  ctx.save();
  ctx.translate(width / 2, by + bh / 2);
  ctx.scale(pop, pop);
  ctx.fillStyle = props.color;
  ctx.beginPath();
  ctx.roundRect(-bw / 2, -bh / 2, bw, bh, bh / 2);
  ctx.fill();
  ctx.fillStyle = '#111111';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(props.label, 0, 2);
  ctx.restore();
}
