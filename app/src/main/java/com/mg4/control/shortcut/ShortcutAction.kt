package com.mg4.control.shortcut

enum class ShortcutAction(val id: Int) {
    NONE(0),
    ONE_PEDAL(1),
    AEB_CYCLE(2),
    SOUND_WARNING(3),
    OVERSPEED_ALARM(4),
    SPEED_LIMIT_TONE(5),
    ADAS_CYCLE(6),
    OPEN_APP(7),
    OPEN_CUSTOM_APP(8),
    ENERGY_SAVING_TOGGLE(9),
    TSR_TOGGLE(10),
    APPLY_PROFILE(11);

    companion object {
        fun fromId(id: Int) = values().firstOrNull { it.id == id } ?: NONE
    }
}

enum class PressType(val key: String) {
    SINGLE("single"),
    LONG("long"),
    DOUBLE("double");

    companion object {
        fun fromKey(key: String) = values().firstOrNull { it.key == key } ?: LONG
    }
}
