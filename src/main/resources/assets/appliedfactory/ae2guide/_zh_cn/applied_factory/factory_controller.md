---
navigation:
  parent: applied_factory/applied_factory-index.md
  title: 工厂控制器
  icon: appliedfactory:factory_controller
categories:
- applied factory devices
item_ids:
- appliedfactory:factory_controller
---

# 工厂控制器

<BlockImage id="appliedfactory:factory_controller" scale="8" />

工厂控制器是可编程的 AE2 加工供应器。每个面都连接一条独立的 AE2 网络；在面上安装工厂总线并让其指向外部机器后，便可用控制器程序搬运资源、注册加工样板。

打开控制器即可编辑 Rhino JavaScript 程序。生成在 `appliedscripts/` 工作区的 API 参考、类型声明、示例和 MCP 说明可作为起点。

## MCP

控制器可绑定到本机 MCP 服务。用 `appliedfactory_execute` 运行探针程序，并只在验证生产程序后使用 `appliedfactory_upload`。

`include("file")` 与 C++ `#include` 一样是文本替换，文件扩展名不会改变其行为。GUI 源码会视为 `appliedscripts/` 根目录中的虚拟文件，因此也能使用相对路径的 `include()` 和 `require_recipes()`。控制器程序最多可有 128k 字符，源码保存于世界级数据而非控制器区块 NBT。
