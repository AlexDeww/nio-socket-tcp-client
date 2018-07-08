package com.alexdeww.niosockettcpclientlib

import android.util.Log
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

internal class NIOSocketTCPClientRunnable<DATA>(
        private val host: String,
        private val port: Int,
        private val keepAlive: Boolean
) : Runnable {

    companion object {
        private const val TAG = "NIOSocketTCPClientR"
        private const val BUFFER_SIZE = 8192
    }

    interface Callback<DATA> {
        fun onConnected()
        fun onDisconnected()
        fun onPrepareDataSend(data: DATA): ByteArray
        fun onDataSent(data: DATA)
        fun onSrcDataReceived(srcData: ByteArray): List<DATA>
        fun onDataReceived(data: DATA)
        fun onError(state: NIOSocketClientState, data: DATA?, error: Throwable? = null)
    }

    private class SendingItem<DATA>(
            val data: DATA,
            val buffer: ByteBuffer
    )

    private lateinit var mSocketChanel: SocketChannel
    private lateinit var mSelector: Selector
    private val mReceiveBuffer = ByteBuffer.allocate(BUFFER_SIZE)
    private var mSendDataQueue: Queue<DATA> = ConcurrentLinkedQueue()
    private var mCurrentSendingItem: SendingItem<DATA>? = null
    private val mHaveDataToSend = AtomicBoolean(false)
    private var mIsSocketInit: Boolean = false
    private var mIsSelectorInit: Boolean = false
    private var mCallback: Callback<DATA>? = null

    val isConnected = AtomicBoolean(false)

    fun registrateCallback(callback: Callback<DATA>) {
        mCallback = callback
    }

    fun removeCallback() {
        mCallback = null
    }

    fun wakeupSelector() {
        if (isConnected.get()) mSelector.wakeup()
    }

    fun addToSendQueue(data: DATA) {
        mSendDataQueue.add(data)
        mHaveDataToSend.set(true)
        wakeupSelector()
    }

    override fun run() {
        Log.i(TAG, "$this started")
        try {
            if (!openConnection()) return

            while (!Thread.interrupted() && mSocketChanel.isOpen) {

                if (mHaveDataToSend.getAndSet(false)) {
                    val key = mSocketChanel.keyFor(mSelector)
                    key.interestOps(key.interestOps() or SelectionKey.OP_WRITE)
                }

                if (mSelector.select() > 0 && !processKeys(mSelector.selectedKeys(), mSocketChanel)) break

            }

        } catch (e: Throwable) {
            e.printStackTrace()
        } finally {
            closeConnection()
            Log.i(TAG, "$this stopped")
        }
    }

    private inline fun safeCall(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun doOnError(state: NIOSocketClientState, data: DATA? = null, error: Throwable? = null) {
        safeCall { mCallback?.onError(state, data, error) }
    }

    private fun clearPacketQueue() {
        mSendDataQueue.clear()
    }

    private fun openConnection(): Boolean {
        return try {
            clearPacketQueue()
            isConnected.set(false)
            mIsSocketInit = false
            mIsSelectorInit = false
            mSelector = Selector.open()
            mIsSelectorInit = true
            mSocketChanel = SocketChannel.open()
            mIsSocketInit = true
            mSocketChanel.socket().keepAlive = keepAlive
            mSocketChanel.socket().sendBufferSize = BUFFER_SIZE
            mSocketChanel.socket().receiveBufferSize = BUFFER_SIZE
            mSocketChanel.socket().tcpNoDelay = true
            mSocketChanel.socket().connect(InetSocketAddress(host, port), 5000)
            mSocketChanel.configureBlocking(false)
            mSocketChanel.register(mSelector, SelectionKey.OP_READ)
            isConnected.set(true)
            safeCall { mCallback?.onConnected() }
            true
        } catch (e: Throwable) {
            doOnError(NIOSocketClientState.CONNECTING, error = e)
            false
        }
    }

    private fun closeConnection() {
        try {
            clearPacketQueue()
            isConnected.set(false)
            mCurrentSendingItem?.buffer?.clear()
            mCurrentSendingItem = null
            mReceiveBuffer.clear()
            if (mIsSocketInit) safeCall { mSocketChanel.close() }
            if (mIsSelectorInit) safeCall { mSelector.close() }
        } catch (e: Throwable) {
            doOnError(NIOSocketClientState.DISCONNECTING, error = e)
        }
        mIsSocketInit = false
        mIsSelectorInit = false
        safeCall { mCallback?.onDisconnected() }
        mCallback = null
    }

    private fun processWrite(key: SelectionKey, socketChanel: SocketChannel): Boolean {
        var sendingItem = mCurrentSendingItem
        if (sendingItem == null) {
            val data = mSendDataQueue.poll()
            if (data == null) {
                key.interestOps(key.interestOps() xor SelectionKey.OP_WRITE)
                return true
            }
            try {
                val ba = mCallback?.onPrepareDataSend(data) ?: return true
                sendingItem = SendingItem(data, ByteBuffer.wrap(ba))
                mCurrentSendingItem = sendingItem
            } catch (e: Throwable) {
                doOnError(NIOSocketClientState.SENDING, data, e)
                return true
            }
        }

        if (socketChanel.write(sendingItem.buffer) == -1) return false
        if (!sendingItem.buffer.hasRemaining()) {
            sendingItem.buffer.clear()
            mCurrentSendingItem = null
            safeCall { mCallback?.onDataSent(sendingItem.data) }
        }
        return true
    }

    private fun processRead(@Suppress("UNUSED_PARAMETER") key: SelectionKey, socketChanel: SocketChannel): Boolean {
        val numberOfBytesRead = socketChanel.read(mReceiveBuffer)
        when (numberOfBytesRead) {
            -1 -> return false
            0 -> return true
            else -> {
                mReceiveBuffer.flip()
                try {
                    val ba = ByteArray(mReceiveBuffer.remaining())
                    mReceiveBuffer.get(ba)
                    safeCall { mCallback?.onSrcDataReceived(ba)?.forEach { safeCall { mCallback?.onDataReceived(it) } } }
                } catch (e: Throwable) {
                    doOnError(NIOSocketClientState.RECEIVING, error = e)
                }
                mReceiveBuffer.clear()
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