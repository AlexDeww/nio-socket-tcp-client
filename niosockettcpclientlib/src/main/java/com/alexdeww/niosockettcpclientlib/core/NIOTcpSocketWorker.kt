package com.alexdeww.niosockettcpclientlib.core

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class NIOTcpSocketWorker(
        val host: String,
        val port: Int,
        private val keepAlive: Boolean,
        private val bufferSize: Int = 8192,
        private val connectionTimeout: Int = 5000 // 5 seconds
) : Runnable {

    private class SendingItem(
            val data: ByteArray,
            val operationResult: NIOSocketOperationResult?,
            var buffer: ByteBuffer? = null //инициализируется перед отправкой
    )

    private lateinit var socketChanel: SocketChannel
    private lateinit var selector: Selector
    private val receiveBuffer = ByteBuffer.allocate(bufferSize)
    private val sendDataQueue: Queue<SendingItem> = ConcurrentLinkedQueue()
    private val isHaveDataToSend = AtomicBoolean(false)
    private var listener: NIOSocketWorkerListener? = null
    private var isSocketInit: Boolean = false
    private var isSelectorInit: Boolean = false
    private var currentSendingItem: SendingItem? = null

    val isConnected = AtomicBoolean(false)

    fun write(data: ByteArray, operationResult: NIOSocketOperationResult? = null): Boolean {
        if (!isConnected.get()) return false

        sendDataQueue.add(SendingItem(data, operationResult))
        isHaveDataToSend.set(true)
        wakeupSelector()
        return true
    }

    fun registerListener(listener: NIOSocketWorkerListener) {
        this.listener = listener
    }

    fun removeListener() {
        this.listener = null
    }

    fun wakeupSelector() {
        if (isConnected.get()) selector.wakeup()
    }

    override fun run() {
        try {
            if (!openConnection()) return

            while (!Thread.interrupted() && socketChanel.isOpen) {

                if (isHaveDataToSend.getAndSet(false)) {
                    val key = socketChanel.keyFor(selector)
                    key.interestOps(key.interestOps() or SelectionKey.OP_WRITE)
                }

                if (selector.select() > 0 && !processKeys(selector.selectedKeys(), socketChanel)) break

            }

        } catch (e: Throwable) {
            e.printStackTrace()
        } finally {
            closeConnection()
        }
    }

    private fun clear() {
        sendDataQueue.clear()
        receiveBuffer.clear()
        currentSendingItem?.buffer?.clear()
        currentSendingItem = null
    }

    private fun doOnError(state: NIOSocketWorkerState, error: Throwable, sendingItem: SendingItem? = null) {
        if (state == NIOSocketWorkerState.SENDING) safeCall { sendingItem?.operationResult?.onError(error) }
        safeCall { listener?.onError(this, state, error, sendingItem?.data) }
    }

    private fun doOnDataReceived(data: ByteArray) {
        safeCall { listener?.onDataReceived(this, data) }
    }

    private fun doOnDataSent(sendingItem: SendingItem) {
        safeCall { sendingItem.operationResult?.onComplete() }
        safeCall { listener?.onDataSent(this, sendingItem.data) }
    }

    private fun doOnConnected() {
        safeCall { listener?.onConnected(this) }
    }

    private fun doOnDisconnected() {
        safeCall { listener?.onDisconnected(this) }
    }

    private fun openConnection(): Boolean = try {
        clear()
        isConnected.set(false)
        isSocketInit = false
        isSelectorInit = false

        selector = Selector.open()
        isSelectorInit = true

        socketChanel = SocketChannel.open()
        socketChanel.socket().keepAlive = keepAlive
        socketChanel.socket().sendBufferSize = bufferSize
        socketChanel.socket().receiveBufferSize = bufferSize
        socketChanel.socket().tcpNoDelay = true
        isSocketInit = true

        socketChanel.socket().connect(InetSocketAddress(host, port), connectionTimeout)
        socketChanel.configureBlocking(false)
        socketChanel.register(selector, SelectionKey.OP_READ)
        isConnected.set(true)

        doOnConnected()
        true
    } catch (e: Throwable) {
        doOnError(NIOSocketWorkerState.CONNECTING, e)
        false
    }

    private fun closeConnection() {
        try {
            clear()
            if (isSocketInit) safeCall { socketChanel.close() }
            if (isSelectorInit) safeCall { selector.close() }
        } catch (e: Throwable) {
            doOnError(NIOSocketWorkerState.DISCONNECTING, e)
        }
        isSocketInit = false
        isSelectorInit = false
        isConnected.set(false)
        doOnDisconnected()
    }

    private fun processWrite(key: SelectionKey, socketChanel: SocketChannel): Boolean {
        var sendingItem = currentSendingItem
        if (sendingItem == null) {
            sendingItem = sendDataQueue.poll()
            if (sendingItem == null) {
                key.interestOps(key.interestOps() xor SelectionKey.OP_WRITE)
                return true
            }
            try {
                sendingItem.buffer = ByteBuffer.wrap(sendingItem.data)
                currentSendingItem = sendingItem
            } catch (e: Throwable) {
                doOnError(NIOSocketWorkerState.SENDING, e, sendingItem)
                return true
            }
        }

        if (socketChanel.write(sendingItem.buffer) == -1) return false
        val hasRemaining = sendingItem.buffer?.hasRemaining() ?: false
        if (!hasRemaining) {
            sendingItem.buffer?.clear()
            currentSendingItem = null
            doOnDataSent(sendingItem)
        }
        return true
    }

    private fun processRead(@Suppress("UNUSED_PARAMETER") key: SelectionKey, socketChanel: SocketChannel): Boolean {
        val numberOfBytesRead = socketChanel.read(receiveBuffer)
        when (numberOfBytesRead) {
            -1 -> return false
            0 -> return true
            else -> {
                receiveBuffer.flip()
                try {
                    val ba = ByteArray(receiveBuffer.remaining())
                    receiveBuffer.get(ba)
                    doOnDataReceived(ba)
                } catch (e: Throwable) {
                    doOnError(NIOSocketWorkerState.RECEIVING, e)
                }
                receiveBuffer.clear()
                return true
            }
        }
    }

    private fun processKeys(keys: MutableSet<SelectionKey>, socketChanel: SocketChannel): Boolean {
        val keysIterator = keys.iterator()
        while (keysIterator.hasNext()) {
            if (Thread.interrupted()) return false

            val key = keysIterator.next()
            keysIterator.remove()
            if (key.isValid) {
                if (key.isReadable && !processRead(key, socketChanel)) return false
                if (key.isWritable && !processWrite(key, socketChanel)) return false
            }
        }
        return true
    }
}