package io.github.mhub.core

import java.io.File

/** The shared contract folder (`contract/`), the same files the iOS tests read. */
object Contract {
    val root = File(System.getProperty("mhub.contract") ?: "../contract")
    fun fixture(name: String) = File(root, "fixtures/$name").readText()
}
