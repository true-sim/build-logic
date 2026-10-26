package com.truesim.build.strict

import java.util.Properties

/** Coordinates of the build-logic release these plugins came from. */
internal object BuildLogic {
    private val properties = Properties().apply {
        BuildLogic::class.java.getResourceAsStream("/truesim/build-logic.properties")!!.use(::load)
    }

    val group: String = properties.getProperty("group")
    val version: String = properties.getProperty("version")

    fun library(name: String) = "$group:$name:$version"
}
