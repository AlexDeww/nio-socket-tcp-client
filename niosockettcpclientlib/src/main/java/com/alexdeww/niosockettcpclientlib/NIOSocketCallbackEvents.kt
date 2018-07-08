package com.alexdeww.niosockettcpclientlib

interface NIOSocketCallbackEvents<DATA> {

    fun onConnected(client: NIOSocketTCPClient<DATA>)

    fun onDisconnected(client: NIOSocketTCPClient<DATA>)

    fun onPrepareDataSend(client: NIOSocketTCPClient<DATA>, data: DATA): ByteArray

    fun onDataSent(client: NIOSocketTCPClient<DATA>, data: DATA)

    fun onSrcDataReceived(client: NIOSocketTCPClient<DATA>, srcData: ByteArray): List<DATA>

    fun onDataReceived(client: NIOSocketTCPClient<DATA>, data: DATA)

    fun onError(client: NIOSocketTCPClient<DATA>, clientState: NIOSocketClientState, data: DATA?, error: Throwable?)

}