package com.alexdeww.niosockettcpclientlib.core

inline fun safeCall(block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        e.printStackTrace()
    }
}
