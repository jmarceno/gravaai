# AI control UI (P3.2, plan §5.16)

UI for the local MCP agent: Settings -> AI control, the editor header chip and the transient
timeline highlights. The platform's `dev.gravaai.services.agent` owns the control socket, token,
jobs and MCP bridge; this package only renders state and forwards user actions.

## Port and adapters

| Type | Role |
|---|---|
| `AgentUiService` | The only API the UI knows: `Snapshot` (enabled/listening/editorReady/clients/token/socketPath/mcpCommand/claudeCodeCommand/jsonConfig/lastSummary/recentActivity + derived `Status`), `subscribe`, `setEnabled`, `regenerateToken`, `revertLastAiChange`, `applyHighlights`/`onHighlights` |
| `ServiceAgentUiService` | **Production adapter** over `services.agent.AgentService` (started/stopped by AppContext); maps snapshots and `Highlight` records one-to-one; does not own/close the service |
| `PlaceholderAgentUiService` | Stand-in for snapshots/tests and for the app before services.agent is wired: `new PlaceholderAgentUiService()` = demo connected state, `disabled()` = off with no activity |
| `AgentSettingsView` | Settings -> AI control page (enable switch, status + socket/editor-ready pills, copy Claude Code command/JSON config/regenerate token, MCP command, Claude Code command, JSON config, masked token with reveal/copy/regenerate, recent activity). AutoCloseable, installs `/ui/agent/agent.css` when attached. |
| `AgentStatusChip` | Editor header chip: purple dot + ping when connected, "AI editing", Revert last change, X turns AI control off; hides itself when off and nothing to revert. Owns its subscription + `dispose()`. |
| `AgentHighlightsController` + `AgentTimelineHighlights` | Transient 2.5 s timeline overlay (cut glowing line + "✦ AI cut" pill; added/changed coloured bands); controller subscribes to the service highlights, overlay takes the timeline's `TimeAxis`. |

## AppContext wiring (final, after dashboard/core-storage seams)

```java
// AppContext owns the platform service.
AgentService agentService = new AgentService(socketPath, tokenFile, dispatcherToolHandler,
        jobRegistry, settingsPort, revertPort, appExecutablePath, List.of("--mcp"));
agentService.startIfEnabled();
AgentUiService agentUiService = new ServiceAgentUiService(agentService);

// Editor window:
shell.setAgentUiService(agentUiService);                     // core-storage method (chip + controller)
if (shell.agentHighlights() != null) {
    timelineView.setAgentHighlights(shell.agentHighlights()); // dashboard's TimelineView setter
}
// Dashboard settings (11th DashboardDependencies component):
new DashboardDependencies(..., new ServiceAgentUiService(agentService));
// Dispose: shell.dispose() (closes chip/controller), dashboard.dispose() (page), then agentService.close().
```

## Tests

`PlaceholderAgentUiServiceTest`, `ServiceAgentUiServiceTest` (real AgentService over a temp unix
socket: enable/disable, subscription, token rotate, revert, highlight round-trip),
`AgentHighlightsControllerTest`, `AgentStatusChipTest`, `AgentSettingsViewTest` (FX + snapshot
`java/parity/ui-java/agent-settings.png`). Scoped run: 26 tests, 0 failures.
