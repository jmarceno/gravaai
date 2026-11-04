// Element runtime prelude: evaluated once per element context, before the component module.
// It defines the documented globals (interpolate, spring, Easing, random, sequence, series,
// measureSpring), freezes the clock and seeds Math.random so a component is a pure function of
// its frame. The function returns the per-frame entry point the host calls.
(function (k) {
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

  const random = (seed) => k.random(String(seed));

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

  for (const [name, value] of Object.entries({ Easing, interpolate, spring, measureSpring,
    random, sequence, series })) {
    Object.defineProperty(g, name, { value, writable: false, enumerable: false });
  }
  for (const name of ['setTimeout', 'setInterval', 'setImmediate', 'queueMicrotask']) {
    g[name] = () => {
      throw new Error(name + ' is not available in components: render must draw synchronously');
    };
  }

  // Host objects expose Java getters/setters; this proxy maps ctx.fillStyle = … to setFillStyle
  // and wraps every returned host object (ctx.measureText(t).width) the same way.
  const upper = (name) => name.charAt(0).toUpperCase() + name.slice(1);
  const wrap = (target) => {
    if (target === null || target === undefined || typeof target !== 'object') {
      return target;
    }
    return new Proxy(target, {
      get(t, property) {
        if (typeof property === 'symbol') {
          try {
            return t[property];
          } catch (ignored) {
            return undefined;
          }
        }
        const direct = t[property];
        if (typeof direct === 'function') {
          return function () {
            return wrap(direct.apply(t, arguments));
          };
        }
        if (direct !== undefined) {
          return wrap(direct);
        }
        const getter = t['get' + upper(String(property))];
        if (typeof getter === 'function') {
          return wrap(getter.call(t));
        }
        return undefined;
      },
      set(t, property, value) {
        if (typeof property === 'symbol') {
          return false;
        }
        const setter = t['set' + upper(String(property))];
        if (typeof setter === 'function') {
          setter.call(t, value);
        }
        return true;
      },
    });
  };

  // The host calls this once per element frame.
  return function (mod, ctx, frame, fps, durationInFrames, timeMs, width, height, propsJson, seed) {
    now = timeMs;
    reseed(seed + '@' + frame);
    const defaults = mod.defaultProps && typeof mod.defaultProps === 'object' ? mod.defaultProps : {};
    const props = Object.assign({}, defaults, JSON.parse(propsJson));
    mod.render(wrap(ctx), {
      frame, fps, durationInFrames, timeMs, width, height, props,
      random: (s) => k.random(seed + ':' + String(s)),
    });
  };
})
