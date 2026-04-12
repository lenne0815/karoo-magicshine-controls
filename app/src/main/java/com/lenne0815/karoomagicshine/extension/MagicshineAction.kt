package com.lenne0815.karoomagicshine.extension

enum class MagicshineAction(val actionId: String) {
    OFF("light-off"),
    LEVEL_10("light-10"),
    LEVEL_100("light-100");

    companion object {
        fun fromActionId(actionId: String): MagicshineAction? = entries.firstOrNull { it.actionId == actionId }
    }
}
