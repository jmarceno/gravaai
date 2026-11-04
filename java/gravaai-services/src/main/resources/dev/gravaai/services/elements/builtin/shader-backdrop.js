// gravaai/shader-backdrop: an animated SkSL backdrop (aurora, waves or plasma).
export const description = 'Animated GPU shader backdrop (aurora, waves, plasma) in two colours';
export const fps = 30;
export const durationInFrames = 150;
export const defaultProps = { style: 'aurora', colorA: '#3ddc97', colorB: '#6d3fd9', speed: 1, opacity: 1 };
export const propsSchema = {
  style: { type: 'enum', values: ['aurora', 'waves', 'plasma'] },
  colorA: 'color',
  colorB: 'color',
  speed: { type: 'number', min: 0, max: 10 },
  opacity: { type: 'number', min: 0, max: 1 },
};

const SOURCE = `
uniform float2 uSize;
uniform float uTime;
uniform float uStyle;
uniform float3 uA;
uniform float3 uB;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float noise(float2 p) {
  float2 i = floor(p); float2 f = fract(p);
  float2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + float2(1, 0)), u.x), mix(hash(i + float2(0, 1)), hash(i + float2(1, 1)), u.x), u.y);
}
half4 main(float2 coord) {
  float2 uv = coord / uSize;
  float t = uTime;
  float v;
  if (uStyle < 0.5) {
    float n = noise(float2(uv.x * 3.0 + t * 0.2, t * 0.15)) * 0.6 + noise(float2(uv.x * 7.0 - t * 0.3, t * 0.4)) * 0.4;
    float band = smoothstep(0.0, 0.5, 1.0 - abs(uv.y - 0.35 - 0.25 * n) * 3.0);
    v = band * (0.6 + 0.4 * n);
  } else if (uStyle < 1.5) {
    v = 0.5 + 0.5 * sin(uv.x * 10.0 + t * 1.5 + sin(uv.y * 6.0 + t) * 1.8);
    v *= 0.55 + 0.45 * sin(uv.y * 4.0 - t * 0.8);
  } else {
    v = sin(uv.x * 8.0 + t) + sin(uv.y * 9.0 - t * 1.2) + sin((uv.x + uv.y) * 7.0 + t * 0.7)
      + sin(length(uv - 0.5) * 14.0 - t * 2.0);
    v = 0.5 + 0.125 * v;
  }
  float3 c = mix(uB, uA, clamp(v, 0.0, 1.0));
  return half4(half3(c), 1.0);
}`;

function rgb(hex) {
  const value = parseInt(String(hex).replace('#', '').slice(0, 6), 16);
  return [((value >> 16) & 255) / 255, ((value >> 8) & 255) / 255, (value & 255) / 255];
}

export function render(ctx, { frame, fps, durationInFrames, width, height, props }) {
  const style = props.style === 'waves' ? 1 : props.style === 'plasma' ? 2 : 0;
  ctx.globalAlpha = props.opacity * Math.min(
    interpolate(frame, [0, 12], [0, 1], { extrapolateRight: 'clamp' }),
    interpolate(frame, [durationInFrames - 12, durationInFrames], [1, 0], { extrapolateLeft: 'clamp' }));
  ctx.fillStyle = shader(SOURCE, {
    uSize: [width, height], uTime: (frame / fps) * props.speed, uStyle: style,
    uA: rgb(props.colorA), uB: rgb(props.colorB),
  });
  ctx.fillRect(0, 0, width, height);
}
