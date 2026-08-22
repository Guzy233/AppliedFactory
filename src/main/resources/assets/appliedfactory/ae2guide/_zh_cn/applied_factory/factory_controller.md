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

工厂控制器通过工厂总线操作机器，并向 AE2 提供由脚本定义的加工样板。你可以用它为熔炉、粉碎机、灌装机等设备编写自动化流程。

## 搭建

控制器的每个面都可以连接 AE2 线缆。脚本可以按方向选择这些连接：

- `north/south/east/west/up/down`：世界方向；
- `front/back/left/right`：以控制器正面为基准的方向。

把工厂总线安装在 AE2 线缆上，并让它正对需要操作的机器。总线和控制器对应的面需要连接到同一个 AE2 网络。

最简单的搭法是让控制器、存储和所有工厂总线共用一个 AE2 网络。需要分开原料网络与机器网络时，再把它们分别接到控制器的不同面。

## 准备脚本工作区

在有权限的世界中执行：

```text
/appliedfactory setupworkspace
```

脚本文件位于游戏目录的 `appliedscripts/` 中。这里包含示例、API 文档、类型声明以及当前整合包导出的配方数据。

整合包配方发生变化后，执行下面的命令刷新配方数据：

```text
/appliedfactory export
```

## 编辑与上传

打开控制器后：

1. 在左侧文件列表选择一个 `.ts` 文件；
2. 在右侧编辑代码；
3. 点击右上角的 `↑` 上传；
4. 打开日志订阅，检查脚本是否成功加载；
5. 在 AE2 终端中查看脚本注册的加工样板。

如果控制器中已有程序，但本地没有对应文件，先点击 `↓` 把程序保存到本地，再继续编辑。

### 按钮

- `○` / `●`：开启或关闭控制器日志；
- `↓`：把控制器中的程序拉取到本地；
- `M`：连接或断开 MCP；
- `↑`：保存并上传当前脚本；
- `<` / `>`：翻动文件列表；
- 刷新：重新读取 `appliedscripts/` 中的文件。

## 编写控制器程序

一个加工程序通常需要完成以下事情：

1. 用 `network(...)` 选择 AE2 网络；
2. 从 `network.buses` 中找到目标机器的工厂总线；
3. 用 `registerProcessingPattern(...)` 注册输入和输出；
4. 收到订单后，把输入送入机器；
5. 把加工产物送回订单网络。

下面的示例注册一条铁矿石烧炼样板：

```ts
const machines = network("front");

registerProcessingPattern(
  [{
    orderNetwork: "back",
    inputs: [item("minecraft:iron_ore", 1)],
    outputs: [item("minecraft:iron_ingot", 1)]
  }],
  function* (order) {
    const furnace = machines.buses.find(bus =>
      bus.target.id === "minecraft:furnace"
    );
    if (furnace === undefined) return;

    yield order.input.pushExactlyInto(furnace);
    yield sleep(200);
    yield furnace.extract().to(order.network);
  }
);
```

所有函数、对象和更多示例见[脚本 API](script_api.md)。

## 使用导出的配方

`require_recipes()` 可以从导出的整合包配方中筛选内容。例如：

```ts
const recipes = require_recipes({
  machine: "minecraft:furnace",
  output: "minecraft:iron_ingot"
});
```

可以按配方 ID、配方类型、机器、输入或输出筛选。整合包可能修改配方，刷新导出后应以工作区中的数据为准。

脚本也可以导入同一工作区内的 JSON：

```ts
import settings from "./settings.json";
```

## 使用 MCP

MCP 可以让编码助手查看控制器状态、运行临时脚本并上传程序。

1. 用编码工具打开 `appliedscripts/` 文件夹；
2. 进入世界并打开目标控制器；
3. 点击 `M` 连接控制器；
4. 先运行临时脚本检查网络、总线和机器；
5. 确认结果后再上传正式程序。

## 常见问题

### 文件没有出现在左侧

确认文件位于 `appliedscripts/` 中，然后点击刷新。控制器程序应使用 `.ts` 扩展名。

### 无法编辑或上传现有程序

点击 `↓` 将控制器中的程序拉取到本地，然后选择拉取后的文件。

### 脚本找不到工厂总线

检查工厂总线是否正对机器，并确认总线与脚本所选的控制器面连接到同一个 AE2 网络。

### AE2 中没有出现加工样板

开启日志并重新上传脚本，检查方向、输入输出和脚本错误。还要确认 `orderNetwork` 指向需要使用这些样板的 AE2 网络。

### 配方筛选结果不正确

重新执行 `/appliedfactory export`，然后再次上传脚本。

### MCP 无法连接

确认游戏仍在运行、控制器所在区块已加载，并在控制器界面重新点击 `M`。
