package com.alexdeww.niosockettcpclientlib.core

import java.util.concurrent.atomic.AtomicBoolean

abstract class NIOSocketOperationResult {

    protected val _isCanceled = AtomicBoolean(false)

    val isCanceled: Boolean
        get() = _isCanceled.get()

    abstract fun onComplete()

    abstract fun onError(error: Throwable)

    open fun cancel() {
        _isCanceled.set(true)
    }

}