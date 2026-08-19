---
navigation:
  parent: applied_factory/applied_factory-index.md
  title: Factory Controller
  icon: appliedfactory:factory_controller
categories:
- applied factory devices
item_ids:
- appliedfactory:factory_controller
---

# Factory Controller

<BlockImage id="appliedfactory:factory_controller" scale="8" />

The Factory Controller is a programmable AE2 processing provider. Each face connects to an independent AE2 network. Attach a Factory Bus to a face, point it at an external machine, then use the controller program to move resources and register processing patterns.

Open the controller and choose a Rhino JavaScript file from the `appliedscripts/` browser. “Precompile & Upload” saves the local file first, then uploads both its editable source and preprocessed executable source. Remote source without a matching local backup must be pulled before it can be edited or uploaded.

## MCP

The controller can be linked to the local MCP server. Use `appliedfactory_execute` for probe programs and `appliedfactory_upload` only after validating the production program.

`include("file")` is a textual replacement, like C++ `#include`; file extensions do not change this behavior. Relative includes and recipe macros resolve from the selected script's directory. Editable source, executable source, and the workspace-relative path are stored in world-level data rather than controller chunk NBT.
