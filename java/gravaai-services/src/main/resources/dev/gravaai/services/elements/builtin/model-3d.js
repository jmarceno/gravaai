// gravaai/model-3d: a 3D model (glTF asset or a built-in shape) on a turntable with a spring entrance.
export const description = '3D model (props.model: a glTF asset or gravaai:cube|sphere|torus|torus-knot) spinning on a turntable';
export const fps = 30;
export const durationInFrames = 150;
export const defaultProps = {
  model: 'gravaai:torus-knot', spin: 40, tilt: 18, scale: 1, cameraDistance: 3.2,
  tint: '#ffffff', light: '#ffffff', ambient: 0.32, pop: true,
};
export const propsSchema = {
  model: 'string',
  spin: { type: 'number', min: -720, max: 720 },
  tilt: { type: 'number', min: -90, max: 90 },
  scale: { type: 'number', min: 0.05, max: 10 },
  cameraDistance: { type: 'number', min: 0.5, max: 50 },
  tint: 'color',
  light: 'color',
  ambient: { type: 'number', min: 0, max: 1 },
  pop: 'boolean',
};

export function render(ctx, { frame, fps, durationInFrames, width, height, props }) {
  const enter = props.pop ? spring({ frame, fps, config: { damping: 12, stiffness: 120 } }) : 1;
  const exit = interpolate(frame, [durationInFrames - 12, durationInFrames], [1, 0],
    { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  const seconds = frame / fps;
  ctx.globalAlpha = exit;
  scene3d(ctx, {
    model: props.model,
    rotation: [props.tilt, seconds * props.spin, 0],
    scale: props.scale * enter,
    cameraDistance: props.cameraDistance,
    tint: props.tint,
    ambient: props.ambient,
    light: { color: props.light, direction: [-0.5, 0.8, 0.6] },
  });
}
