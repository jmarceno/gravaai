# java/parity

UI reference screenshots and fixture projects (plan §6.2, §6.3).

## UI screenshot parity

Reference screenshots come from the React UI in the browser harness; JavaFX captures come from
the packaged app. Pixel equality is not expected (font rasterisation differs) - the reviewer
compares them side by side in the generated gallery.

Repository root, in this order:

```bash
# 1. React reference screenshots (Playwright + vite dev:ui harness, tests/ui/bridge.ts stubs electronAPI)
npx playwright test -c java/tools/parity/playwright.config.ts

# 2. JavaFX screenshots (packaged app, scratch profile, cosmic-screenshot)
java/tools/parity/capture-java-ui.sh \
  --binary /media/jmarceno/scratch/java-targets/gravaai-app/dist/gravaai/bin/gravaai

# 3. Side-by-side gallery
node java/tools/parity/gallery.mjs 1        # -> docs/java-rewrite/phase-1-ui.html
```

Screens and states are listed in `java/tools/parity/capture-react-ui.spec.ts` (one entry per
screen) and `java/tools/parity/capture-java-ui.sh` (one case per screen). Add the same name on
both sides and it shows up in the gallery.

## Compositor fixtures (P2.1)

`java/parity/fixtures/projects/` holds ten generated `.gravaai` fixtures covering the compositor
feature set. They feed the Java-side rendering tests (see `ProjectFixture` in `gravaai-media`) and
are handy for manual rendering checks. See `java/parity/fixtures/README.md` for the fixture/feature
matrix.

```bash
# regenerate fixtures (uses the production project normaliser)
java/tools/parity/generate-fixtures.sh
```

The Electron reference-frame parity harness (capture scripts, golden frames, comparison contract)
was removed in Phase 3 when the Electron app was deleted; the fixture projects remain because the
Java render tests and manual checks still use them.
