package com.mdt.level;

import arc.util.CommandHandler;
import arc.util.Log;
import mindustry.gen.Player;
import mindustry.mod.Plugin;

public final class PlayerLevelSystemPlugin extends Plugin {
    @Override
    public void init() {
        Log.info("MDT 玩家等级系统 loaded.");
        Log.info("配置目录建议: config/mods/config/mdt-player-level-system");
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register("level-check", "[playerOrComid]", "查看指定玩家或自己的等级信息。", args -> {
            Log.info("MDT 玩家等级系统 命令占位已触发: level-check");
        });

        handler.register("level-sync", "把当前经验重新同步为等级结果。", args -> {
            Log.info("MDT 玩家等级系统 命令占位已触发: level-sync");
        });

        handler.register("level-reload", "重新加载等级系统配置。", args -> {
            Log.info("MDT 玩家等级系统 命令占位已触发: level-reload");
        });

    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        handler.<Player>register("level", "[playerOrComid]", "查看等级、经验和下一等级需求。", (args, player) -> {
            player.sendMessage("[accent]MDT 玩家等级系统[] 命令占位已触发: level");
        });

    }
}
