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

Open the controller to edit its Rhino JavaScript program. The `appliedscripts/` workspace contains the generated API reference, type declarations, examples, and MCP instructions.

## MCP

The controller can be linked to the local MCP server. Use `appliedfactory_execute` for probe programs and `appliedfactory_upload` only after validating the production program.

`include("file")` is a textual replacement, like C++ `#include`; file extensions do not change this behavior. GUI source is treated as a virtual file in the `appliedscripts/` root, so it can use relative `include()` paths and `require_recipes()` too. Controller sources may contain up to 128k characters and are stored in world-level data, not in the controller chunk NBT.
