#!/usr/bin/env node
/**
 * Golden-frame capture for compositor parity (plan 6.2, P2.1).
 *
 * The Electron app must already be running with AI control enabled and the editor window open
 * (desktop UI, not the --mcp bridge); this script starts the stdio MCP bridge and drives the
 * committed project and frames tools.
 *
 *   node java/tools/parity/capture-golden.mjs \
 *     --project java/parity/fixtures/projects/zoom-motion-blur.gravaai \
 *     --times 0,1.5,3.25 --name zoom-motion-blur --width 1280
 *
 * Writes java/parity/golden/<name>/<ms>.png plus metadata.json. Frames are captured through
 * Electron's software path (electron/gpuSwitches.ts disables VaapiVideoDecoder/VaapiVideoEncoder on
 * Linux; verified no libva/vulkan is mapped - see java/parity/README.md), and the JPEG-to-PNG
 * conversion uses our own bundled ffmpeg CLI with its software PNG encoder.
 */
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '../../..');
const defaultFfmpeg = path.join(repo, 'java/native/build/ffmpeg/bin/ffmpeg');

function usage(message) {
	if (message) console.error('capture-golden: ' + message);
	console.error('usage: node java/tools/parity/capture-golden.mjs --project <file.gravaai> --times 0,1.5 --name <name> [--width 1280] [--mcp-cmd "npx electron . --mcp"] [--ffmpeg <path>] [--out java/parity/golden]');
	process.exit(64);
}

const args = process.argv.slice(2);
const options = {
	project: null,
	times: [0],
	name: null,
	width: 1280,
	mcpCommand: null,
	ffmpeg: defaultFfmpeg,
	out: path.join(repo, 'java/parity/golden'),
	// Chromium remote-debugging port of the running app. The Wayland window can be created without
	// being presented (the editor renderer then gets no animation frames and the frames tool hangs
	// in its seek/paint wait); Page.bringToFront fixes that. Optional: without it the capture only
	// works when the editor window already got shown by the app.
	cdpPort: Number(process.env['GRAVAAI_ELECTRON_CDP_PORT'] || 0),
};
for (let index = 0; index < args.length; index++) {
	const arg = args[index];
	switch (arg) {
		case '--project': options.project = args[++index]; break;
		case '--times': options.times = String(args[++index]).split(',').map(Number); break;
		case '--name': options.name = args[++index]; break;
		case '--width': options.width = Number(args[++index]); break;
		case '--mcp-cmd': options.mcpCommand = args[++index]; break;
		case '--ffmpeg': options.ffmpeg = args[++index]; break;
		case '--out': options.out = path.resolve(args[++index]); break;
		case '--cdp-port': options.cdpPort = Number(args[++index]); break;
		default: usage('unknown argument ' + arg);
	}
}
if (!options.project || !options.name) usage('--project and --name are required');
if (!options.times.length || options.times.some(Number.isNaN)) usage('--times must be a comma separated list of seconds');
if (!existsSync(options.project)) usage('project not found: ' + options.project);
if (!existsSync(options.ffmpeg)) usage('bundled ffmpeg not found (run java/native/build-ffmpeg.sh): ' + options.ffmpeg);

const mcpCommand = options.mcpCommand ? options.mcpCommand.split(' ') : ['npx', 'electron', '.', '--mcp'];
const child = spawn(mcpCommand[0], mcpCommand.slice(1), {
	cwd: repo,
	stdio: ['pipe', 'pipe', 'pipe'],
	env: { ...process.env, ELECTRON_ENABLE_LOGGING: '0' },
});

let nextId = 1;
const pending = new Map();
let buffer = '';

child.stderr.on('data', (chunk) => process.stderr.write('[mcp] ' + chunk));
child.on('exit', (code) => {
	for (const [, entry] of pending) entry.reject(new Error('MCP bridge exited with ' + code));
});

function send(message) {
	child.stdin.write(JSON.stringify(message) + '\n');
}
function request(method, params) {
	const id = nextId++;
	return new Promise((resolve, reject) => {
		const timer = setTimeout(() => {
			if (pending.delete(id)) reject(new Error('timeout waiting for ' + method));
		}, 120000);
		pending.set(id, {
			resolve: (value) => {
				clearTimeout(timer);
				resolve(value);
			},
			reject: (error) => {
				clearTimeout(timer);
				reject(error);
			},
		});
		send({ jsonrpc: '2.0', id, method, params });
	});
}
child.stdout.on('data', (chunk) => {
	buffer += chunk.toString();
	let newline;
	while ((newline = buffer.indexOf('\n')) >= 0) {
		const line = buffer.slice(0, newline).trim();
		buffer = buffer.slice(newline + 1);
		if (!line.startsWith('{')) continue;
		let message;
		try { message = JSON.parse(line); } catch { continue; }
		if (message.id && pending.has(message.id)) {
			const entry = pending.get(message.id);
			pending.delete(message.id);
			if (message.error) entry.reject(new Error(message.error.message || JSON.stringify(message.error)));
			else entry.resolve(message.result);
		}
	}
});

function textResult(result) {
	const content = result && result.content ? result.content : [];
	const text = content.filter((part) => part.type === 'text').map((part) => part.text).join('\n');
	return text || JSON.stringify(result);
}
function imageParts(result) {
	const content = result && result.content ? result.content : [];
	return content.filter((part) => part.type === 'image' && part.data);
}

/**
 * Activates the editor window through the Chrome DevTools Protocol so Wayland actually presents it.
 * Without this the editor page can report visibility "visible" while never receiving animation
 * frames, which makes the `frames` tool time out in its seek/paint wait. Requires the app to be
 * started with `--remote-debugging-port=<port>`; silently skipped when no port is configured.
 */
async function bringEditorToFront(cdpPort) {
	if (!cdpPort) {
		return false;
	}
	try {
		const response = await fetch('http://127.0.0.1:' + cdpPort + '/json/list');
		const targets = await response.json();
		const editor = targets.find((target) => typeof target.url === 'string'
			&& target.url.includes('windowType=editor'))
			|| targets.find((target) => target.title === 'Gravaai Editor');
		if (!editor) {
			console.warn('capture-golden: no editor page on the CDP endpoint yet');
			return false;
		}
		await new Promise((resolve, reject) => {
			const socket = new WebSocket(editor.webSocketDebuggerUrl);
			const timer = setTimeout(() => {
				socket.close();
				reject(new Error('CDP request timed out'));
			}, 15000);
			let replies = 0;
			const settle = () => {
				replies += 1;
				if (replies < 2) {
					return;
				}
				clearTimeout(timer);
				socket.close();
				resolve();
			};
			socket.addEventListener('open', () => {
				socket.send(JSON.stringify({ id: 1, method: 'Page.bringToFront' }));
				socket.send(JSON.stringify({
					id: 2,
					method: 'Runtime.evaluate',
					params: {
						expression: 'new Promise(resolve => requestAnimationFrame(() => resolve("painted")))',
						awaitPromise: true,
						returnByValue: true,
					},
				}));
			});
			socket.addEventListener('message', (event) => {
				const message = JSON.parse(event.data);
				if (message.id === 1 || message.id === 2) {
					settle();
				}
			});
			socket.addEventListener('error', (error) => {
				clearTimeout(timer);
				reject(error);
			});
		});
		console.log('capture-golden: brought the editor window to front through CDP');
		return true;
	} catch (error) {
		console.warn('capture-golden: CDP bring-to-front unavailable: ' + error.message);
		return false;
	}
}

/**
 * Tool calls can time out when the desktop composites another window on top of the editor (Chromium
 * throttles the occluded renderer), or when a previous capture left the editor busy. Retry briefly
 * before failing: the launch flags in java/parity/golden/README.md keep the renderer unthrottled.
 */
async function callTool(name, args, attempt = 1) {
	try {
		return await request('tools/call', { name, arguments: args });
	} catch (error) {
		if (attempt >= 3) throw error;
		console.error('capture-golden: ' + name + ' failed (' + error.message + '); retry ' + attempt + '/2');
		await new Promise((resolve) => setTimeout(resolve, 2500));
		return callTool(name, args, attempt + 1);
	}
}

async function main() {
	await request('initialize', {
		protocolVersion: '2024-11-05',
		capabilities: {},
		clientInfo: { name: 'gravaai-parity', version: '1.0.0' },
	});
	send({ jsonrpc: '2.0', method: 'notifications/initialized' });

	console.log('capture-golden: opening ' + options.project);
	const opened = await callTool('project', { action: 'open', path: path.resolve(options.project) });
	console.log('capture-golden: project -> ' + textResult(opened).slice(0, 400));
	await bringEditorToFront(options.cdpPort);

	// The editor mounts the compositor preview asynchronously after the project opens (wallpaper,
	// Pixi canvas, video texture). Capture one throwaway frame first so the reference frames never
	// race the first paint: without this the early frames come out as a bare video frame.
	await new Promise((resolve) => setTimeout(resolve, 1200));
	await callTool('frames', { at: [options.times[0]], width: options.width });
	await new Promise((resolve) => setTimeout(resolve, 300));

	// The frames tool tiles several times into one contact sheet, so ask for exactly one time per
	// call; each result is then a single full-size frame that becomes <ms>.png.
	const directory = path.join(options.out, options.name);
	mkdirSync(directory, { recursive: true });
	const written = [];
	for (const time of options.times) {
		const frames = await callTool('frames', { at: [time], width: options.width });
		const images = imageParts(frames);
		if (images.length === 0) {
			throw new Error('frames returned no image at ' + time + 's: ' + textResult(frames).slice(0, 600));
		}
		const png = path.join(directory, String(Math.round(time * 1000)) + '.png');
		if (images[0].mimeType === 'image/png') {
			// Lossless capture (GRAVAAI_CAPTURE_LOSSLESS=1 in the Electron app): keep the bytes as is.
			writeFileSync(png, Buffer.from(images[0].data, 'base64'));
			written.push(path.relative(repo, png));
			console.log('capture-golden: wrote ' + path.relative(repo, png) + ' (at ' + time + 's, lossless)');
			continue;
		}
		const jpeg = path.join(directory, '.frame-' + time + '.jpg');
		writeFileSync(jpeg, Buffer.from(images[0].data, 'base64'));
		const conversion = spawnSync(options.ffmpeg, ['-hide_banner', '-loglevel', 'error', '-y', '-i', jpeg, png]);
		rmSync(jpeg, { force: true });
		if (conversion.status !== 0) {
			throw new Error('ffmpeg PNG conversion failed: ' + conversion.stderr);
		}
		written.push(path.relative(repo, png));
		console.log('capture-golden: wrote ' + path.relative(repo, png) + ' (at ' + time + 's)');
	}
	writeFileSync(path.join(directory, 'metadata.json'), JSON.stringify({
		project: path.relative(repo, options.project),
		timesSeconds: options.times,
		width: options.width,
		capturedAt: new Date().toISOString(),
		frames: written,
		route: process.env.GRAVAAI_CAPTURE_LOSSLESS === '1' ? 'electron MCP frames tool (one frame per call; VA-API decode/encode disabled; lossless PNG from capturePage)' : 'electron MCP frames tool (one frame per call; VA-API decode/encode disabled; JPEG to PNG via bundled software ffmpeg)',
	}, null, 2) + '\n');
	console.log('capture-golden: wrote ' + written.length + ' frames to ' + path.relative(repo, directory));
}

main()
	.catch((error) => {
		console.error('capture-golden: FAILED: ' + error.message);
		process.exitCode = 1;
	})
	.finally(() => {
		// The bridge child does not exit with the parent and the per-request timeouts keep the node
		// event loop alive, so finish the capture process explicitly once the frames are written.
		child.stdin.end();
		setTimeout(() => {
			try {
				child.kill('SIGKILL');
			} catch {
				// already gone
			}
			process.exit(process.exitCode ?? 0);
		}, 600);
	});
