package com.alexdeww.niosockettcpclientlib.core

interface NIOSocketWorkerListener {

    fun onConnected(socket: NIOTcpSocketWorker)

    fun onDisconnected(socket: NIOTcpSocketWorker)

    fun onDataSent(socket: NIOTcpSocketWorker, data: ByteArray)

    fun onDataReceived(socket: NIOTcpSocketWorker, data: ByteArray)

    fun onError(socket: NIOTcpSocketWorker, state: NIOSocketWorkerState, error: Throwable, data: ByteArray?)

}