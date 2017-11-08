package com.alexdeww.niosockettcpclientlib.internal

import android.util.Log
import com.alexdeww.niosockettcpclientlib.common.*
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class NIOSocketTCPClientRunnable(private val host: String,
                                 private val port: Int,
                                 private val keepAlive: Boolean,
                                 private val packetProtocol: PacketProtocol,
                                 private val packetSerializer: PacketSerializer) : Runnable {

    companion object {
        private const val TAG = "NIOSocketTCPClientR"
        private const val BUFFER_SIZE = 8192
    }

    interface Callback {
        fun onConnected()
        fun onDisconnected()
        fun onPacketSent(packet: Packet)
        fun onPacketReceived(packet: Packet)
        fun onError(state: ClientState, packet: Packet?, error: Throwable? = null)
    }

    private class SendingItem(val packet: Packet, val buffer: ByteBuffer)

    val isConnected = AtomicBoolean(false)
    private lateinit var mSocketChanel: SocketChannel
    private lateinit var mSelector: Selector
    private val mReceiveBuffer = ByteBuffer.allocate(BUFFER_SIZE)
    private var mSendPacketQueue: Queue<Packet> = ConcurrentLinkedQueue()
    private var mCurrentSendingItem: SendingItem? = null
    private val mHavePacketToSend = AtomicBoolean(false)
    private var mIsSocketInit: Boolean = false
    private var mIsSelectorInit: Boolean = false
    private var mCallback: Callback? = null

    private fun safeCall(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun doOnError(state: ClientState, packet: Packet? = null, error: Throwable? = null) {
        mCallback?.onError(state, packet, error)
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
            mSocketChanel.socket().connect(InetSocketAddress(host, port), 5000)
            mSocketChanel.configureBlocking(false)
            mSocketChanel.register(mSelector, SelectionKey.OP_READ)
            isConnected.set(true)
            mCallback?.onConnected()
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
        mCallback?.onDisconnected()
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
                sendingItem = SendingItem(packet,
                        ByteBuffer.wrap(packetProtocol.encode(packetSerializer.serialize(packet))))
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
            mCallback?.onPacketSent(sendingItem.packet)
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
                    val packetsData = packetProtocol.decode(ba)
                    if (packetsData.isNotEmpty()) {
                        packetsData.forEach {
                            try {
                                val packet = packetSerializer.deSerialize(it)
                                mCallback?.onPacketReceived(packet)
                            } catch (e: Throwable) {
                                doOnError(ClientState.RECEIVING, error = e)
                            }
                        }
                    }
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

    fun registrateCallback(callback: Callback) {
        mCallback = callback
    }

    fun removeCallback() {
        mCallback = null
    }

    fun wakeupSelector() {
        if (isConnected.get()) mSelector.wakeup()
    }

    fun addPacketToSendQueue(packet: Packet) {
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