// Gravaai example extension (EXTENSIONS.md "Examples"): the smallest runnable bundle that draws
// into the render pipeline. Install it by copying this directory into userData/extensions and
// enabling it; the banner is drawn in the "final" phase, so it appears above every built-in layer
// in both the editor preview and the export.
export function activate(api) {
  api.log("hello-overlay activated");

  // Registrations return a dispose function; the host also disposes everything on deactivate().
  api.registerRenderHook("final", (ctx) => {
    const ctx2d = ctx.ctx;
    const bannerWidth = Math.min(160, Math.max(48, ctx.width * 0.25));
    const bannerHeight = 24;
    const x = 12;
    const y = 12;

    ctx2d.save();
    ctx2d.fillStyle = "#2563EB";
    ctx2d.fillRect(x, y, bannerWidth, bannerHeight);
    ctx2d.fillStyle = "#ffffff";
    ctx2d.font = "13px sans-serif";
    ctx2d.fillText("Hello, Gravaai", x + 8, y + 16);
    ctx2d.restore();
  });
}

export function deactivate() {}
