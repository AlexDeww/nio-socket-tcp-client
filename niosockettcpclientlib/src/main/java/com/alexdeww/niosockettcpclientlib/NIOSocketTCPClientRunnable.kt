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

internal class NIOSocketTCPClientRunnable<PACKET>(
        private val host: String,
        private val port: Int,
        private val keepAlive: Boolean,
        private val packetProtocol: PacketProtocol<PACKET>
) : Runnable {

    companion object {
        private const val TAG = "NIOSocketTCPClientR"
        private const val BUFFER_SIZE = 8192
    }

    interface Callback<in PACKET> {
        fun onConnected()
        fun onDisconnected()
        fun onPacketSent(packet: PACKET)
        fun onPacketReceived(packet: PACKET)
        fun onError(state: ClientState, packet: PACKET?, error: Throwable? = null)
    }

    private class SendingItem<out PACKET>(val packet: PACKET, val buffer: ByteBuffer)

    val isConnected = AtomicBoolean(false)
    private lateinit var mSocketChanel: SocketChannel
    private lateinit var mSelector: Selector
    private val mReceiveBuffer = ByteBuffer.allocate(BUFFER_SIZE)
    private var mSendPacketQueue: Queue<PACKET> = ConcurrentLinkedQueue()
    private var mCurrentSendingItem: SendingItem<PACKET>? = null
    private val mHavePacketToSend = AtomicBoolean(false)
    private var mIsSocketInit: Boolean = false
    private var mIsSelectorInit: Boolean = false
    private var mCallback: Callback<PACKET>? = null

    private inline fun safeCall(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun doOnError(state: ClientState, packet: PACKET? = null, error: Throwable? = null) {
        safeCall { mCallback?.onError(state, packet, error) }
    }

    private fun clearPacketQueue() {
        mSendPacketQueue.clear()
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
            doOnError(ClientState.CONNECTING, error = e)
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
            safeCall { packetProtocol.clearBuffers() }
            if (mIsSocketInit) safeCall { mSocketChanel.close() }
            if (mIsSelectorInit) safeCall { mSelector.close() }
        } catch (e: Throwable) {
            doOnError(ClientState.DISCONNECTING, error = e)
        }
        mIsSocketInit = false
        mIsSelectorInit = false
        safeCall { mCallback?.onDisconnected() }
        mCallback = null
    }

    private fun processWrite(key: SelectionKey, socketChanel: SocketChannel): Boolean {
        var sendingItem = mCurrentSendingItem
        if (sendingItem == null) {
            val packet = mSendPacketQueue.poll()
            if (packet == null) {
                key.interestOps(key.interestOps() xor SelectionKey.OP_WRITE)
                return true
            }
            try {
                sendingItem = SendingItem(packet, ByteBuffer.wrap(packetProtocol.encode(packet)))
                mCurrentSendingItem = sendingItem
            } catch (e: Throwable) {
                doOnError(ClientState.SENDING, packet, e)
                return true
            }
        }

        if (socketChanel.write(sendingItem.buffer) == -1) return false
        if (!sendingItem.buffer.hasRemaining()) {
            sendingItem.buffer.clear()
            mCurrentSendingItem = null
            val packet = sendingItem.packet
            safeCall { mCallback?.onPacketSent(packet) }
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
                    val packets = packetProtocol.decode(ba)
                    packets.forEach { safeCall { mCallback?.onPacketReceived(it) } }
                } catch (e: Throwable) {
                    doOnError(ClientState.RECEIVING, error = e)
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

    fun registrateCallback(callback: Callback<PACKET>) {
        mCallback = callback
    }

    fun removeCallback() {
        mCallback = null
    }

    fun wakeupSelector() {
        if (isConnected.get()) mSelector.wakeup()
    }

    fun addPacketToSendQueue(packet: PACKET) {
        mSendPacketQueue.add(packet)
        mHavePacketToSend.set(true)
        wakeupSelector()
    }

    override fun run() {
        Log.i(TAG, "$this started")
        try {
            if (!openConnection()) return

            while (!Thread.interrupted() && mSocketChanel.isOpen) {

                if (mHavePacketToSend.getAndSet(false)) {
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
}