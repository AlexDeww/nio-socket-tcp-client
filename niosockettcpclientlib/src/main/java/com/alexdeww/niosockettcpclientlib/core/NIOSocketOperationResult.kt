package com.alexdeww.niosockettcpclientlib.core

interface NIOSocketOperationResult {

    fun onComplete()

    fun onError(error: Throwable)

}