package com.alexdeww.niosockettcpclientlib

import com.alexdeww.niosockettcpclientlib.core.NIOSocketOperationResult
import com.alexdeww.niosockettcpclientlib.core.NIOSocketWorkerListener
import com.alexdeww.niosockettcpclientlib.core.NIOSocketWorkerState
import com.alexdeww.niosockettcpclientlib.core.NIOTcpSocketWorker
import com.alexdeww.niosockettcpclientlib.exception.AlreadyConnected

open class NIOSocketTCPClientCommon(
    val host: String,
    val port: Int,
    val keepAlive: Boolean,
    private val bufferSize: Int = 8192,
    private val connectionTimeout: Int = 5000
) : NIOSocketWorkerListener {

    private val clearLock = Object()
    private var socketWorker: NIOTcpSocketWorker? = null
    private var workThread: Thread? = null

    val isConnected: Boolean get() = socketWorker?.isConnected?.get() ?: false

    override fun onConnected(socket: NIOTcpSocketWorker) {
        //empty
    }

    override fun onDisconnected(socket: NIOTcpSocketWorker) {
        synchronized(clearLock) {
            workThread = null
            socketWorker = null
        }
    }

    override fun onDataSent(socket: NIOTcpSocketWorker, data: ByteArray) {
        //empty
    }

    override fun onDataReceived(socket: NIOTcpSocketWorker, data: ByteArray) {
        //empty
    }

    override fun onError(
        socket: NIOTcpSocketWorker,
        state: NIOSocketWorkerState,
        error: Throwable,
        data: ByteArray?
    ) {
        //empty
    }

    fun connect() {
        if (workThread != null) throw AlreadyConnected()

        synchronized(clearLock) {
            try {
                socketWorker = NIOTcpSocketWorker(
                    host,
                    port,
                    keepAlive,
                    bufferSize,
                    connectionTimeout
                )
                socketWorker?.registerListener(this)
                workThread = Thread(socketWorker)
                workThread?.start()
            } catch (e: Throwable) {
                socketWorker?.removeListener()
                workThread = null
                socketWorker = null
                throw e
            }
        }
    }

    fun disconnect() {
        synchronized(clearLock) {
            workThread?.interrupt()
            socketWorker?.wakeupSelector()
        }
    }

    fun forceDisconnect() {
        synchronized(clearLock) {
            if (socketWorker != null && workThread != null) {
                socketWorker?.removeListener()
                workThread?.interrupt()
                socketWorker?.wakeupSelector()
                onDisconnected(socketWorker!!)
            }
            workThread = null
            socketWorker = null
        }
    }

    open fun write(data: ByteArray, operationResult: NIOSocketOperationResult? = null): Boolean =
        socketWorker?.write(data, operationResult) ?: false

}
