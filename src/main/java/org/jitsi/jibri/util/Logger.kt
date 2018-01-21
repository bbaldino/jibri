package org.jitsi.jibri.util

import java.util.logging.Level
import java.util.logging.Logger

fun Logger.error(msg: String) {
    this.log(Level.SEVERE, msg)
}

fun Logger.debug(msg: String) {
    this.fine(msg)
}