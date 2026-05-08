<div align="center">
  <a href="https://github.com/MonthZifang/YUEYUEDAO-TECH">
    <img src="./md/logo.png" alt="YUEYUEDAO TECH Logo" width="720" />
  </a>

  <p><strong>YUEYUEDAO TECH 维护 MDT 玩家等级系统</strong></p>

  <p>
    <a href="https://github.com/MonthZifang/YUEYUEDAO-TECH"><strong>查看月月岛科技详情</strong></a>
  </p>
</div>

# MDT 玩家等级系统

通过读取列表数据系统中的经验值来确定玩家等级，支持自定义 1 到 1000 的等级范围与每级经验阈值，且不会消耗玩家经验。

## 市场固定识别文件

仓库根目录固定提供以下文件，供插件市场识别：

```text
market.plugin.json
plugin.json
```

## 依赖

- `mdt-list-data-system`
- 可选依赖：`mdt-chat-access`

## 配置文件

首次启动后建议维护以下配置文件：

```text
config/mods/config/mdt-player-level-system/player-level-system.properties
```

- 支持定义最低等级与最高等级。
- 支持逐级配置经验要求。
- 等级仅按经验数量排序计算，不消耗经验。
- 支持玩家进入服务器时通过聊天提示显示等级与经验。

## 功能说明

- 支持经验驱动的固定等级系统。
- 支持精确配置每一级所需经验。
- 支持进入服务器时发送聊天提示。
- 支持其他插件读取当前等级参与显示或结算。

## 数据与写入说明

- 建议经验字段使用 `experience`。
- 建议等级字段使用 `level`。
- 经验值变化时可主动同步等级字段，避免排行榜与展示延迟。

## 命令

- `level-check [playerOrComid]`：查看指定玩家或自己的等级信息。
- `level-sync`：把当前经验重新同步为等级结果。
- `level-reload`：重新加载等级系统配置。
- `/level [playerOrComid]`：查看等级、经验和下一等级需求。

## Help 注册备注

- `help mdt-player-level-system`：查看 MDT 玩家等级系统 的独立命令说明。
- 中文备注建议写为“等级查询、等级同步、等级配置重载”。

## 插件入口

```text
com.mdt.level.PlayerLevelSystemPlugin
```

## 版本规则

- 当前插件版本：`v1`
- 当前需求市场版本：`v1`
