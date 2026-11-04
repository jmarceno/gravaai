// Element runtime prelude: evaluated once per element context, before the component module.
// It defines the documented globals (interpolate, spring, Easing, random, sequence, series,
// measureSpring), freezes the clock and seeds Math.random so a component is a pure function of
// its frame. The function returns the per-frame entry point the host calls.
(function (k, commands, host) {
  'use strict';
  const g = globalThis;

  const named = (name) => {
    const fn = (t) => k.ease(name, t);
    Object.defineProperty(fn, 'name', { value: name });
    return fn;
  };
  const Easing = Object.freeze({
    linear: named('linear'),
    ease: named('ease'),
    quad: named('ease-in-quad'),
    cubic: named('ease-in-cubic'),
    sin: named('ease-in-sine'),
    circle: named('ease-in-circ'),
    exp: named('ease-in-expo'),
    bounce: named('bounce'),
    poly: (n) => (t) => Math.pow(t, n),
    back: (s = 1.70158) => (t) => t * t * ((s + 1) * t - s),
    elastic: (bounciness = 1) => (t) => k.elastic(bounciness, t),
    bezier: (x1, y1, x2, y2) => (t) => k.bezier(x1, y1, x2, y2, t),
    steps: (n) => (t) => k.steps(n, t),
    in: (f) => f,
    out: (f) => (t) => 1 - f(1 - t),
    inOut: (f) => (t) => (t < 0.5 ? f(t * 2) / 2 : 1 - f((1 - t) * 2) / 2),
  });

  const interpolate = (input, inputRange, outputRange, options) => {
    const o = options || {};
    return k.interpolate(Number(input), inputRange, outputRange,
      o.extrapolateLeft || 'extend', o.extrapolateRight || 'extend', o.easing || null);
  };

  const spring = (options) => {
    const o = options || {};
    if (typeof o.frame !== 'number' || typeof o.fps !== 'number') {
      throw new TypeError('spring({frame, fps, config?}) needs numeric frame and fps');
    }
    const c = o.config || {};
    return k.spring(o.frame, o.fps, c.mass ?? 1, c.damping ?? 10, c.stiffness ?? 100,
      !!c.overshootClamping, o.from ?? 0, o.to ?? 1, o.delay ?? 0,
      o.durationInFrames ?? 0, !!o.reverse);
  };

  const measureSpring = (options) => {
    const o = options || {};
    const c = o.config || {};
    return k.springDuration(o.fps ?? 30, c.mass ?? 1, c.damping ?? 10, c.stiffness ?? 100,
      !!c.overshootClamping, o.threshold ?? 0.005);
  };

  // random() is a pure function of its seed: memoize per context (bounded) to skip the host call.
  const randomCache = new Map();
  const cachedRandom = (key) => {
    let value = randomCache.get(key);
    if (value === undefined) {
      if (randomCache.size >= 65536) {
        randomCache.clear();
      }
      value = k.random(key);
      randomCache.set(key, value);
    }
    return value;
  };
  const random = (seed) => cachedRandom(String(seed));

  // sequence(frame, from, durationInFrames, fn): fn(localFrame) only inside the window.
  const sequence = (frame, from, durationInFrames, fn) => {
    if (frame < from || frame >= from + durationInFrames) {
      return undefined;
    }
    return fn(frame - from);
  };

  // series(frame, [{durationInFrames, render(localFrame)}...]): back-to-back sequences.
  const series = (frame, items) => {
    let start = 0;
    for (const item of items) {
      const length = item.durationInFrames;
      if (frame >= start && frame < start + length) {
        return item.render(frame - start);
      }
      start += length;
    }
    return undefined;
  };

  // Frozen clock: Date.now(), new Date() and performance.now() report the frame time.
  let now = 0;
  const RealDate = Date;
  class FrozenDate extends RealDate {
    constructor(...args) {
      if (args.length === 0) {
        super(now);
      } else {
        super(...args);
      }
    }
    static now() {
      return now;
    }
  }
  g.Date = FrozenDate;
  g.performance = Object.freeze({ now: () => now });

  // Seeded Math.random (mulberry32), reseeded for every frame from (element id, frame).
  let rngState = 1;
  const reseed = (seed) => {
    rngState = Math.floor(k.random(seed) * 4294967296) >>> 0;
  };
  Math.random = () => {
    rngState = (rngState + 0x6D2B79F5) >>> 0;
    let t = rngState;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };

  // Project assets (plan §5.2). Host-drawn content flushes the batched canvas first so it lands
  // in draw order.
  const image = (name) => host.image(String(name));
  const shader = (source, uniforms) => host.shader(String(source), uniforms || {});
  const assets = (kind) => Array.from(host.assetNames(String(kind || 'image')));
  const lottie = (name) => {
    const key = String(name);
    const info = host.lottieInfo(key);
    return Object.freeze({
      duration: info[0], fps: info[1], width: info[2], height: info[3],
      draw(ctx, seconds, x, y, w, h) {
        flush();
        const t = ctx.$;
        host.drawLottie(t, key, Number(seconds) || 0, x ?? 0, y ?? 0, w ?? t.getCanvasWidth(),
          h ?? t.getCanvasHeight());
      },
    });
  };
  const scene3d = (ctx, options) => {
    const o = options || {};
    if (typeof o.model !== 'string') {
      throw new TypeError('scene3d(ctx, {model: "asset-name", rotation: [x, y, z], …}) needs a model asset name');
    }
    flush();
    const t = ctx.$;
    host.drawScene(t, o.model, JSON.stringify(o), o.x ?? 0, o.y ?? 0, o.width ?? t.getCanvasWidth(),
      o.height ?? t.getCanvasHeight());
  };

  for (const [name, value] of Object.entries({ Easing, interpolate, spring, measureSpring,
    random, sequence, series, image, shader, assets, lottie, scene3d })) {
    Object.defineProperty(g, name, { value, writable: false, enumerable: false });
  }
  for (const name of ['setTimeout', 'setInterval', 'setImmediate', 'queueMicrotask']) {
    g[name] = () => {
      throw new Error(name + ' is not available in components: render must draw synchronously');
    };
  }

  // Batched canvas: numeric draw calls and simple setters are written into a Float64Array over
  // a host buffer (CanvasCommands) and replayed in one host call. Without the Graal JIT each
  // JS-to-host call costs microseconds, so a particle loop would otherwise blow the frame budget.
  // Anything that reads state or passes objects flushes first and calls the host directly.
  // Opcodes mirror CanvasCommands.java.
  const OP = {
    save: 1, restore: 2, translate: 3, rotate: 4, scale: 5, transform: 6, setTransform: 7,
    resetTransform: 8, beginPath: 9, closePath: 10, moveTo: 11, lineTo: 12, quadraticCurveTo: 13,
    bezierCurveTo: 14, arc: 15, arcCcw: 16, rect: 17, fill: 18, stroke: 19, clip: 20, fillRect: 21,
    strokeRect: 22, clearRect: 23, fillText: 24, strokeText: 25, ellipse: 26, ellipseCcw: 27,
    roundRect: 28,
  };
  const NUMBER_SETTERS = { lineWidth: 40, globalAlpha: 41, shadowBlur: 42, shadowOffsetX: 43,
    shadowOffsetY: 44, miterLimit: 45, lineDashOffset: 46 };
  const STRING_SETTERS = { fillStyle: 50, strokeStyle: 51, font: 52, textAlign: 53,
    textBaseline: 54, lineCap: 55, lineJoin: 56, globalCompositeOperation: 57, shadowColor: 58,
    filter: 59 };
  const capacity = commands.capacity();
  const buf = new Float64Array(new ArrayBuffer(commands.buffer()));
  const strs = [];
  let n = 0;
  let target = null;
  const flush = () => {
    if (n > 0) {
      const length = n;
      n = 0;
      commands.run(target, length, strs);
      strs.length = 0;
    }
  };
  const room = (count) => {
    if (n + count >= capacity) {
      flush();
    }
  };

  const facade = (names, special, flushFirst) => {
    const proto = {};
    const has = new Set(names);
    for (const name of names) {
      if (special[name]) {
        proto[name] = special[name];
        continue;
      }
      // Fixed-arity dispatch keeps host overload resolution exact (no trailing undefined).
      proto[name] = new Function('flush', 'return function (a, b, c, d, e, f, g, h, i) {'
        + (flushFirst ? 'flush();' : '')
        + 'const t = this.$; switch (arguments.length) {'
        + 'case 0: return t.' + name + '();'
        + 'case 1: return t.' + name + '(a);'
        + 'case 2: return t.' + name + '(a, b);'
        + 'case 3: return t.' + name + '(a, b, c);'
        + 'case 4: return t.' + name + '(a, b, c, d);'
        + 'case 5: return t.' + name + '(a, b, c, d, e);'
        + 'case 6: return t.' + name + '(a, b, c, d, e, f);'
        + 'case 7: return t.' + name + '(a, b, c, d, e, f, g);'
        + 'case 8: return t.' + name + '(a, b, c, d, e, f, g, h);'
        + 'default: return t.' + name + '(a, b, c, d, e, f, g, h, i); } };')(flush);
    }
    for (const name of names) {
      if (!/^get[A-Z]/.test(name)) {
        continue;
      }
      const base = name.slice(3);
      const property = base.charAt(0).toLowerCase() + base.slice(1);
      if (property in proto) {
        continue;
      }
      const setter = 'set' + base;
      const numberOp = flushFirst ? NUMBER_SETTERS[property] : undefined;
      const stringOp = flushFirst ? STRING_SETTERS[property] : undefined;
      let set = function () {};
      if (numberOp !== undefined) {
        set = function (v) {
          room(2);
          buf[n++] = numberOp;
          buf[n++] = v;
        };
      } else if (stringOp !== undefined) {
        set = function (v) {
          if (typeof v !== 'string') {
            flush();
            this.$[setter](v);
            return;
          }
          room(2);
          buf[n++] = stringOp;
          buf[n++] = strs.length;
          strs.push(v);
        };
      } else if (has.has(setter)) {
        set = function (v) {
          if (flushFirst) {
            flush();
          }
          this.$[setter](v);
        };
      }
      Object.defineProperty(proto, property, {
        get() {
          if (flushFirst) {
            flush();
          }
          return this.$[name]();
        },
        set,
      });
    }
    return proto;
  };

  const metricsProto = facade(k.memberNames('metrics'), {}, false);
  const buffered = {
    save() { room(1); buf[n++] = 1; },
    restore() { room(1); buf[n++] = 2; },
    translate(x, y) { room(3); buf[n++] = 3; buf[n++] = x; buf[n++] = y; },
    rotate(a) { room(2); buf[n++] = 4; buf[n++] = a; },
    scale(x, y) { room(3); buf[n++] = 5; buf[n++] = x; buf[n++] = y; },
    transform(a, b, c, d, e, f) {
      room(7); buf[n++] = 6; buf[n++] = a; buf[n++] = b; buf[n++] = c; buf[n++] = d; buf[n++] = e; buf[n++] = f;
    },
    setTransform(a, b, c, d, e, f) {
      room(7); buf[n++] = 7; buf[n++] = a; buf[n++] = b; buf[n++] = c; buf[n++] = d; buf[n++] = e; buf[n++] = f;
    },
    resetTransform() { room(1); buf[n++] = 8; },
    beginPath() { room(1); buf[n++] = 9; },
    closePath() { room(1); buf[n++] = 10; },
    moveTo(x, y) { room(3); buf[n++] = 11; buf[n++] = x; buf[n++] = y; },
    lineTo(x, y) { room(3); buf[n++] = 12; buf[n++] = x; buf[n++] = y; },
    quadraticCurveTo(a, b, x, y) { room(5); buf[n++] = 13; buf[n++] = a; buf[n++] = b; buf[n++] = x; buf[n++] = y; },
    bezierCurveTo(a, b, c, d, x, y) {
      room(7); buf[n++] = 14; buf[n++] = a; buf[n++] = b; buf[n++] = c; buf[n++] = d; buf[n++] = x; buf[n++] = y;
    },
    rect(x, y, w, h) { room(5); buf[n++] = 17; buf[n++] = x; buf[n++] = y; buf[n++] = w; buf[n++] = h; },
    fill() { room(1); buf[n++] = 18; },
    stroke() { room(1); buf[n++] = 19; },
    clip() { room(1); buf[n++] = 20; },
    fillRect(x, y, w, h) { room(5); buf[n++] = 21; buf[n++] = x; buf[n++] = y; buf[n++] = w; buf[n++] = h; },
    strokeRect(x, y, w, h) { room(5); buf[n++] = 22; buf[n++] = x; buf[n++] = y; buf[n++] = w; buf[n++] = h; },
    clearRect(x, y, w, h) { room(5); buf[n++] = 23; buf[n++] = x; buf[n++] = y; buf[n++] = w; buf[n++] = h; },
  };
  Object.assign(buffered, {
    arc(x, y, r, start, end, ccw) {
      room(7);
      buf[n++] = arguments.length > 5 ? OP.arcCcw : OP.arc;
      buf[n++] = x; buf[n++] = y; buf[n++] = r; buf[n++] = start; buf[n++] = end;
      if (arguments.length > 5) {
        buf[n++] = ccw ? 1 : 0;
      }
    },
    ellipse(x, y, rx, ry, rotation, start, end, ccw) {
      room(9);
      buf[n++] = arguments.length > 7 ? OP.ellipseCcw : OP.ellipse;
      buf[n++] = x; buf[n++] = y; buf[n++] = rx; buf[n++] = ry; buf[n++] = rotation;
      buf[n++] = start; buf[n++] = end;
      if (arguments.length > 7) {
        buf[n++] = ccw ? 1 : 0;
      }
    },
    roundRect(x, y, w, h, radii) {
      if (radii !== undefined && typeof radii !== 'number') {
        flush();
        return this.$.roundRect(x, y, w, h, radii);
      }
      room(6);
      buf[n++] = OP.roundRect;
      buf[n++] = x; buf[n++] = y; buf[n++] = w; buf[n++] = h; buf[n++] = radii || 0;
    },
    fillText(text, x, y) {
      room(4);
      buf[n++] = OP.fillText; buf[n++] = strs.length; buf[n++] = x; buf[n++] = y;
      strs.push(String(text));
    },
    strokeText(text, x, y) {
      room(4);
      buf[n++] = OP.strokeText; buf[n++] = strs.length; buf[n++] = x; buf[n++] = y;
      strs.push(String(text));
    },
    measureText(text) {
      flush();
      const metrics = Object.create(metricsProto);
      metrics.$ = this.$.measureText(String(text));
      return metrics;
    },
  });
  const canvasProto = facade(k.memberNames('canvas'), buffered, true);
  const canvasFacade = (ctx) => {
    flush();
    target = ctx;
    const object = Object.create(canvasProto);
    object.$ = ctx;
    return object;
  };

  // The host calls this once per element frame.
  return function (mod, ctx, frame, fps, durationInFrames, timeMs, width, height, propsJson, seed) {
    now = timeMs;
    reseed(seed + '@' + frame);
    const defaults = mod.defaultProps && typeof mod.defaultProps === 'object' ? mod.defaultProps : {};
    const props = Object.assign({}, defaults, JSON.parse(propsJson));
    const facadeCtx = canvasFacade(ctx);
    try {
      mod.render(facadeCtx, {
        frame, fps, durationInFrames, timeMs, width, height, props,
        random: (s) => cachedRandom(seed + ':' + String(s)),
      });
    } finally {
      try {
        flush();
      } finally {
        n = 0;
        strs.length = 0;
        target = null;
      }
    }
  };
})
