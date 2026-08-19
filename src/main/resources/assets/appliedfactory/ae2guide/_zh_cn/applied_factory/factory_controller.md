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

打开控制器后，从左侧文件管理器选择 `appliedscripts/` 中的 Rhino JavaScript 文件。点击“预编译并上传”会先保存本地文件，再把原始源码与预处理后的执行源码一同上传；没有本地备份的远端源码必须先拉取才能修改和上传。工作区中的 API 参考、类型声明、示例和 MCP 说明可作为起点。

## MCP

控制器可绑定到本机 MCP 服务。用 `appliedfactory_execute` 运行探针程序，并只在验证生产程序后使用 `appliedfactory_upload`。

`include("file")` 与 C++ `#include` 一样是文本替换，文件扩展名不会改变其行为。相对路径从所选脚本文件所在目录解析。控制器程序最多可有 128k 字符，原始源码、执行源码和工作区相对路径保存于世界级数据而非控制器区块 NBT。
