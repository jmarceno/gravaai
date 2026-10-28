import { defineConfig } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));

/**
 * React reference-screenshot harness (plan §6.3).
 *
 * Starts the browser harness that the existing UI specs use (vite --mode ui with
 * GRAVAAI_RENDERER_ONLY=1, tests/ui/bridge.ts stubs electronAPI) and captures every screen/state
 * at a fixed size into java/parity/ui/. The JavaFX side captures the same list from the packaged
 * app into java/parity/ui-java/ (see capture-java-ui.sh), and gallery.mjs puts them side by side.
 *
 * Run from the repository root:
 *   npx playwright test -c java/tools/parity/playwright.config.ts
 */
export default defineConfig({
	testDir: here,
	testMatch: "capture-react-ui.spec.ts",
	timeout: 120_000,
	fullyParallel: false,
	workers: 1,
	outputDir: "/media/jmarceno/scratch/java-targets/parity-test-results",
	reporter: [["list"]],
	use: {
		baseURL: "http://127.0.0.1:5178",
		viewport: { width: 1440, height: 1000 },
		deviceScaleFactor: 1,
	},
	webServer: {
		cwd: path.resolve(here, "../../.."),
		command: "npx vite --host 127.0.0.1 --port 5178 --strictPort",
		url: "http://127.0.0.1:5178",
		env: { GRAVAAI_RENDERER_ONLY: "1" },
		reuseExistingServer: true,
		timeout: 120_000,
	},
});
