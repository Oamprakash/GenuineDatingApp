package com.genuine.app.ui.common

import kotlin.math.roundToInt

/** key: stored in the database · edit: chip label in the editor · show: how others see it */
data class Opt(val key: String, val edit: String, val show: String = edit)

object ProfileOptions {
    val goals = listOf(
        Opt("long_term", "Long-term partner"),
        Opt("long_open_short", "Long-term, open to short"),
        Opt("short_open_long", "Short-term, open to long"),
        Opt("figuring_out", "Still figuring it out"),
        Opt("friends", "New friends"),
    )
    val kids = listOf(
        Opt("want", "Want someday", "Wants kids someday"),
        Opt("dont_want", "Don't want", "Doesn't want kids"),
        Opt("open", "Open to it", "Open to kids"),
        Opt("have", "Have kids", "Has kids"),
    )
    val education = listOf(
        Opt("high_school", "High school"), Opt("diploma", "Diploma"),
        Opt("bachelors", "Bachelor's"), Opt("masters", "Master's"),
        Opt("doctorate", "Doctorate"), Opt("other", "Other"),
    )
    val drinking = listOf(
        Opt("never", "Never", "Doesn't drink"),
        Opt("socially", "Socially", "Drinks socially"),
        Opt("regularly", "Regularly", "Drinks regularly"),
    )
    val smoking = listOf(
        Opt("never", "Never", "Doesn't smoke"),
        Opt("sometimes", "Sometimes", "Smokes sometimes"),
        Opt("regularly", "Regularly", "Smokes regularly"),
        Opt("quitting", "Trying to quit", "Trying to quit smoking"),
    )
    val exercise = listOf(
        Opt("often", "Often", "Exercises often"),
        Opt("sometimes", "Sometimes", "Exercises sometimes"),
        Opt("rarely", "Rarely", "Rarely exercises"),
    )
    val diet = listOf(
        Opt("veg", "Vegetarian"), Opt("egg", "Eggetarian"), Opt("nonveg", "Non-vegetarian"),
        Opt("vegan", "Vegan"), Opt("jain", "Jain"),
    )
    val prompts = listOf(
        Opt("first_meetup", "A perfect first meetup would be…"),
        Opt("get_along", "You'll know we'll get along if…"),
        Opt("sunday", "My ideal Sunday in Chennai…"),
        Opt("green_flag", "A green flag I look for…"),
        Opt("cant_stop", "I can't stop talking about…"),
        Opt("simple_pleasure", "My simple pleasure…"),
    )
    val languages = listOf(
        "Tamil", "English", "Hindi", "Telugu", "Malayalam", "Kannada",
        "Marathi", "Bengali", "Gujarati", "Urdu", "Punjabi", "Other",
    )

    const val MAX_LANGUAGES = 5
    const val MAX_PROMPTS = 3
    const val PROMPT_MAX_CHARS = 150

    fun show(list: List<Opt>, key: String?): String? = list.firstOrNull { it.key == key }?.show
}

/** 170 → 5'7" */
fun cmToFeet(cm: Int): String {
    val inches = (cm / 2.54).roundToInt()
    return "${inches / 12}'${inches % 12}\""
}