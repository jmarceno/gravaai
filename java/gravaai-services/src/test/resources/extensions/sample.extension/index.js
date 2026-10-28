// Test fixture for the Gravaai extension host (P3.3): draws an overlay through
// api.registerRenderHook and exercises the documented Canvas2D subset from ES module code.
export function activate(api) {
  api.log("sample extension activated", { test: true });

  api.registerRenderHook("final", (ctx) => {
    const c = ctx.ctx;
    c.save();
    c.fillStyle = "#ff0000";
    c.fillRect(4, 4, 24, 8);
    c.strokeStyle = "rgba(0, 255, 0, 1)";
    c.lineWidth = 2;
    c.beginPath();
    c.moveTo(40, 4);
    c.lineTo(40, 20);
    c.stroke();
    c.restore();
  });

  api.registerRenderHook("post-video", (ctx) => {
    ctx.ctx.fillStyle = "#ffffff";
    ctx.ctx.fillRect(0, 0, 1, 1);
  });

  const dispose = api.registerRenderHook("post-zoom", (ctx) => {
    ctx.ctx.fillRect(0, 0, 64, 32);
  });
  dispose();
}

export function deactivate() {}
