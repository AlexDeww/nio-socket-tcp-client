package com.alexdeww.niosockettcpclientlib

import com.alexdeww.niosockettcpclientlib.exception.AlreadyConnected

class NIOSocketTCPClient<DATA>(
        val host: String,
        val port: Int,
        val keepAlive: Boolean,
        private val socketCallbackEvents: NIOSocketCallbackEvents<DATA>
) {

    private val clearLock = Object()
    private var workThread: Thread? = null
    private var socketRunnable: NIOSocketTCPClientRunnable<DATA>? = null
    private val socketClientCallback = object : NIOSocketTCPClientRunnable.Callback<DATA> {
        override fun onConnected() {
            socketCallbackEvents.onConnected(this@NIOSocketTCPClient)
        }

        override fun onDisconnected() {
            synchronized(clearLock) {
                workThread = null
                socketRunnable = null
            }
            socketCallbackEvents.onDisconnected(this@NIOSocketTCPClient)
        }

        override fun onPrepareDataSend(data: DATA): ByteArray =
                socketCallbackEvents.onPrepareDataSend(this@NIOSocketTCPClient, data)

        override fun onDataSent(data: DATA) {
            socketCallbackEvents.onDataSent(this@NIOSocketTCPClient, data)
        }

        override fun onSrcDataReceived(srcData: ByteArray): List<DATA> =
                socketCallbackEvents.onSrcDataReceived(this@NIOSocketTCPClient, srcData)

        override fun onDataReceived(data: DATA) {
            socketCallbackEvents.onDataReceived(this@NIOSocketTCPClient, data)
        }

        override fun onError(state: NIOSocketClientState, data: DATA?, error: Throwable?) {
            socketCallbackEvents.onError(this@NIOSocketTCPClient, state, data, error)
        }
    }

    val isConnected: Boolean get() = socketRunnable?.isConnected?.get() ?: false

    fun connect() {
        if (workThread != null) throw AlreadyConnected()

        synchronized(clearLock) {
            try {
                socketRunnable = NIOSocketTCPClientRunnable(host, port, keepAlive)
                socketRunnable?.registrateCallback(socketClientCallback)
                workThread = Thread(socketRunnable)
                workThread?.start()
            } catch (e: Throwable) {
                socketRunnable?.removeCallback()
                workThread = null
                socketRunnable = null
                throw e
            }
        }
    }

    fun disconnect() {
        synchronized(clearLock) {
            workThread?.interrupt()
            socketRunnable?.wakeupSelector()
        }
    }

    fun forceDisconnect() {
        synchronized(clearLock) {
            if (socketRunnable != null && workThread != null) {
                socketRunnable?.removeCallback()
                workThread?.interrupt()
                socketRunnable?.wakeupSelector()
                socketCallbackEvents.onDisconnected(this@NIOSocketTCPClient)
            }
            workThread = null
            socketRunnable = null
        }
    }

    fun sendData(data: DATA): Boolean = if (isConnected) {
        socketRunnable?.addToSendQueue(data)
        true
    } else false

}