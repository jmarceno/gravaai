import { test } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { installDesktopBridge } from "../../../tests/ui/bridge";

const here = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.resolve(here, "../../parity/ui");

/**
 * React reference screenshots (plan §6.3, §7.1 P1.14). One entry per screen/state the plan lists
 * for the phase; the JavaFX side mirrors the same names. Add a state here, capture it on both
 * sides, and it shows up in gallery.mjs.
 *
 * The editor window is the app's main window: the dashboard opens from its Home button and the
 * settings pages live in the same window, exactly like Electron.
 */
const PROJECTS = [
	{
		path: "/projects/demo.gravaai",
		name: "App walkthrough",
		updatedAt: 1759000000000,
		thumbnailPath: null,
		isCurrent: false,
		isInProjectsDirectory: true,
	},
	{
		path: "/projects/launch.gravaai",
		name: "Launch video",
		updatedAt: 1758000000000,
		thumbnailPath: null,
		isCurrent: true,
		isInProjectsDirectory: true,
	},
	{
		path: "/projects/tutorial.gravaai",
		name: "Getting started",
		updatedAt: 1757000000000,
		thumbnailPath: null,
		isCurrent: false,
		isInProjectsDirectory: true,
	},
];

async function openEditor(page: import("@playwright/test").Page) {
	await installDesktopBridge(page);
	await page.addInitScript((entries) => {
		window.electronAPI.listProjectFiles = async () => ({
			success: true,
			projects: [],
			entries,
		});
	}, PROJECTS);
	await page.goto("/?windowType=editor");
	await page.getByRole("button", { name: "Rename project" }).waitFor({ timeout: 60_000 });
	await page.waitForTimeout(1500);
}

test("capture editor", async ({ page }) => {
	await page.setViewportSize({ width: 1440, height: 1000 });
	await openEditor(page);
	await page.evaluate(() => document.fonts.ready);
	await page.screenshot({ path: path.join(OUT, "editor.png") });
});

test("capture home-dashboard", async ({ page }) => {
	await page.setViewportSize({ width: 1440, height: 1000 });
	await openEditor(page);
	await page.getByRole("button", { name: "Home", exact: true }).click();
	const home = page.getByRole("dialog", { name: "Projects dashboard" });
	await home.waitFor({ timeout: 30_000 });
	await home.getByRole("textbox", { name: "Search projects" }).waitFor({ timeout: 30_000 });
	await page.waitForTimeout(1500);
	await page.screenshot({ path: path.join(OUT, "home-dashboard.png") });
});

test("capture dashboard-settings", async ({ page }) => {
	await page.setViewportSize({ width: 1440, height: 1000 });
	await openEditor(page);
	await page.getByRole("button", { name: "Home", exact: true }).click();
	const home = page.getByRole("dialog", { name: "Projects dashboard" });
	await home.waitFor({ timeout: 30_000 });
	await home.getByRole("textbox", { name: "Search projects" }).waitFor({ timeout: 30_000 });
	await home.getByRole("button", { name: "Settings", exact: true }).click();
	await page.getByRole("region", { name: "Dashboard settings" }).waitFor({ timeout: 15_000 });
	await page.waitForTimeout(800);
	await page.screenshot({ path: path.join(OUT, "dashboard-settings.png") });
});

test("capture editor-settings", async ({ page }) => {
	await page.setViewportSize({ width: 1440, height: 1000 });
	await openEditor(page);
	await page.locator("aside").waitFor({ timeout: 30_000 });
	await page.waitForTimeout(800);
	await page.screenshot({ path: path.join(OUT, "editor-settings.png") });
});

type SimpleScreen = {
	name: string;
	query: string;
	width: number;
	height: number;
	transparent?: boolean;
	settleMs?: number;
};

const SIMPLE_SCREENS: SimpleScreen[] = [
	{ name: "hud-overlay", query: "?windowType=hud-overlay", width: 900, height: 160, transparent: true },
	{ name: "hud-menu", query: "?windowType=hud-menu", width: 460, height: 640, transparent: true },
	{ name: "source-selector", query: "?windowType=source-selector", width: 960, height: 700, transparent: true },
	{ name: "countdown", query: "?windowType=countdown", width: 420, height: 420, transparent: true },
	{ name: "capture-picker", query: "?windowType=capture-picker", width: 1440, height: 1000, transparent: true },
];

for (const screen of SIMPLE_SCREENS) {
	test(`capture ${screen.name}`, async ({ page }) => {
		await page.setViewportSize({ width: screen.width, height: screen.height });
		await installDesktopBridge(page);
		await page.goto(`/${screen.query}`);
		await page.waitForTimeout(screen.settleMs ?? 1800);
		await page.evaluate(() => document.fonts.ready);
		await page.waitForTimeout(250);
		await page.screenshot({
			path: path.join(OUT, `${screen.name}.png`),
			omitBackground: Boolean(screen.transparent),
		});
	});
}
