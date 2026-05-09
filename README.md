# MDT 玩家等级系统

根据玩家已存储的经验值计算等级，并将等级结果同步回 `player_profile` 列表数据。

## 依赖

- `mdt-list-data-system`
- 可选联动：`go---mdt---Jump-Plugin`，用于把 UUID 映射到 COMID

## 配置文件

首次启动后会生成：

```text
config/mods/config/mdt-player-level-system/player-level-system.properties
```

关键配置项：

- `level.min` / `level.max`：等级范围
- `level.requirement.<level>`：升级到下一等级所需经验
- `data.*`：`player_profile` 中各字段名
- `message.broadcastOnJoin`：玩家进服时是否广播等级信息
- `message.joinTemplate`：进服提示模板

## 写入字段

插件会向 `player_profile.<key>` 写入这些字段：

- `uuid`
- `comid`
- `lastName`
- `level`
- `experience`
- `experienceIntoLevel`
- `experienceToNext`
- `updatedAt`

其中 `<key>` 优先使用 COMID，没有 COMID 时退回 UUID。

## 命令

- `level-check [playerOrComid]`：查看等级与经验
- `level-sync [playerOrComid]`：按当前经验重新计算等级
- `level-add-exp <playerOrComid> <amount>`：增减经验
- `level-set <playerOrComid> <level> [experience]`：直接设置等级和经验
- `level-reload`：重载配置
- `/level [playerOrComid]`：客户端查看等级信息

## 插件入口

```text
com.mdt.level.PlayerLevelSystemPlugin
```
