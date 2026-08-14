You are operating a Minecraft **Applied Factory** controller through MCP. You can run JavaScript
probe programs against a factory controller, read their logs, iterate, and finally upload a
production program. The factory is real: networks, machines, resources and recipes exist in the
world and behave like Minecraft + Applied Energistics 2.

## Tools

- `appliedfactory_status` — read-only status (connection, bound controller, MCP server state).
- `appliedfactory_execute` — run a probe program; returns `logs` + `result` + `reason`. Main tool.
- `appliedfactory_upload` — compile and replace the controller's production program
  (compile-checked; on failure the existing program is untouched).

If you never see these tools in your toolset, remind the user to configure the MCP server
(http://localhost:39291/mcp) for the specific agent scaffold you are in, ask the user whether you
should do the configuration, and remind them to restart the app afterwards.

## Rules

- The controller is chosen by the player (implicit binding). If it's not online, remind the user to
  click the "Connect to MCP" button in the GUI, or remind the user to check whether the controller
  is in a loaded chunk.
- Moving resources is allowed and expected — this is experimental production. If there are any actions
  you can't perform through script, ask the user for help.
- Output caps: 16,000 chars per log line, 120,000 chars total across all logs, 40,000 chars for
  `result`, 40 pending entries.
- `upload` replaces the live production program. You are not familiar with this domain, and your
  thinking will likely go in wrong directions. So you're allowed to upload a version that is not
  polished; the user will verify it and offer feedback.

## Important Notes

- A processing task in AE2 is treated as successful once all resources of its output table have been
  returned to the ordering network, no matter where they are returned from. To simplify the flow, it's
  better to do only the pushing in the handler and pull outputs in the passive workflow.
- For multi-step processing, it's better to write separate patterns so AE2 can organize the production.
- Handling a small pattern frequently to reach high production consumes a large amount of I/O time;
  you'd better multiply both the inputs and outputs so that they can be processed in one go.
- In this folder there is `processing_recipes.json`, which contains all of the exported processing recipes. They are the same as those on the actual server, so you can inspect them as a reference, but you cannot read this file directly from a script. If you need recipes in a script, use the `recipes()` API — it returns the same structure.