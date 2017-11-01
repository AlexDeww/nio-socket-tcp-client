package com.alexdeww.niosockettcpclientlib

import android.os.Handler
import android.os.Message
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

class NIOSocketTCPClient(val host: String,
                         val port: Int,
                         val keepAlive: Boolean,
                         private val packetProtocol: PacketProtocol,
                         private val packetSerializer: PacketSerializer,
                         private val callbackEvents: CallbackEvents) {

    val isConnected: Boolean
        get() = mConnectionState == ClientConnectionState.CONNECTED

    private val mHandler = Handler(HandlerCallback())
    private val mWorkRunnable: WorkRunnable = WorkRunnable(mHandler)
    private var mConnectionState: ClientConnectionState = ClientConnectionState.DISCONNECTED
    private var mWorkThread: Thread? = null
    private var mToSendPackets: Queue<Packet> = ConcurrentLinkedQueue()
    private var mForceDisconnect: AtomicBoolean = AtomicBoolean(false)

    fun connect(): Boolean = when (mConnectionState) {
        ClientConnectionState.CONNECTED -> true
        ClientConnectionState.CONNECTING -> false
        ClientConnectionState.DISCONNECTING -> false
        ClientConnectionState.DISCONNECTED -> {
            clearHandlerMessages()
            mForceDisconnect.set(false)
            mConnectionState = ClientConnectionState.CONNECTING
            mWorkThread = Thread(mWorkRunnable)
            mWorkThread?.start()
            false
        }
    }

    fun disconnect(): Boolean = when (mConnectionState) {
        ClientConnectionState.DISCONNECTED -> true
        ClientConnectionState.DISCONNECTING -> false
        ClientConnectionState.CONNECTING -> {
            mForceDisconnect.set(true)
            false
        }
        ClientConnectionState.CONNECTED -> {
            mConnectionState = ClientConnectionState.DISCONNECTING
            mWorkRunnable.stopWork()
            false
        }
    }

    fun sendPacket(packet: Packet): Boolean {
        if (mConnectionState == ClientConnectionState.CONNECTED) {
            mToSendPackets.add(packet)
            mWorkRunnable.interruptSend()
            return true
        }
        return false
    }

    private fun clearHandlerMessages() {
        mHandler.removeMessages(MSG_CONNECTED)
        mHandler.removeMessages(MSG_DISCONNECTED)
        mHandler.removeMessages(MSG_PACKET_SENT)
        mHandler.removeMessages(MSG_RECEIVED_PACKETS)
        mHandler.removeMessages(MSG_ERROR)
    }

    private fun clearQueues() {
        mToSendPackets.clear()
    }

    private inner class HandlerCallback : Handler.Callback {
        override fun handleMessage(msg: Message?): Boolean {
            if (msg == null) return true

            when(msg.what) {
                MSG_CONNECTED -> {
                    mConnectionState = ClientConnectionState.CONNECTED
                    callbackEvents.onConnected(this@NIOSocketTCPClient)
                }
                MSG_DISCONNECTED -> {
                    mWorkThread = null
                    mConnectionState = ClientConnectionState.DISCONNECTED
                    callbackEvents.onDisconnected(this@NIOSocketTCPClient)
                }
                MSG_PACKET_SENT -> callbackEvents.onPacketSent(this@NIOSocketTCPClient, msg.obj as Packet)
                MSG_RECEIVED_PACKETS -> {
                    val packet = msg.obj ?: return true
                    callbackEvents.onPacketReceived(this@NIOSocketTCPClient, packet as Packet)
                }
                MSG_ERROR -> {
                    val msgError = msg.obj as ErrorMessage
                    callbackEvents.onError(this@NIOSocketTCPClient, msgError.clientState, msgError.msg, msgError.packet)
                }
            }

            return true
        }
    }

    private inner class WorkRunnable(private val handler: Handler) : Runnable {

        private var mIsDoneWorkThread: AtomicBoolean = AtomicBoolean(false)
        private lateinit var mSocketChanel: SocketChannel
        private lateinit var mSelector: Selector
        private var mCurrentSendingItem: SendingItem? = null
        private val mReceiveBuffer = ByteBuffer.allocate(BUFFER_SIZE)


        fun stopWork() {
            mForceDisconnect.set(true)
            mIsDoneWorkThread.set(true)
            mSelector.wakeup()
        }

        fun interruptSend() {
            try {
                val key = mSocketChanel.keyFor(mSelector)
                key.interestOps(key.interestOps() or SelectionKey.OP_WRITE)
                mSelector.wakeup()
            } catch (e: Exception) {
                Log.e("NIOSocketTCPClient", "interruptSend", e)
            }
        }

        override fun run() {
            Log.e("NIOSocketTCPClient", "Start nio thread $this")
            try {
                mIsDoneWorkThread.set(false)
                if (!initConnection()) return

                while (!mIsDoneWorkThread.get() && !mForceDisconnect.get()) {
                    if (mSelector.select() > 0 && !processKeys(mSelector.selectedKeys())) break
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                closeConnection()
            }
            Log.e("NIOSocketTCPClient", "Stop nio thread $this")
        }

        private fun processKeys(keys: MutableSet<SelectionKey>): Boolean {
            val keysIterator = keys.iterator()
            while (keysIterator.hasNext()) {
                if (mIsDoneWorkThread.get() || mForceDisconnect.get()) return false

                val key = keysIterator.next()
                keysIterator.remove()
                if (key.isValid) {
                    if (key.isReadable && !read(key)) return false
                    if (key.isWritable && !send(key)) return false
                }
            }
            return true
        }

        private fun initConnection(): Boolean {
            return try {
                mSelector = Selector.open()
                mSocketChanel = SocketChannel.open()
                mSocketChanel.socket().keepAlive = keepAlive
                mSocketChanel.socket().sendBufferSize = BUFFER_SIZE
                mSocketChanel.socket().receiveBufferSize = BUFFER_SIZE
                mSocketChanel.socket().connect(InetSocketAddress(host, port), 5000)
                mSocketChanel.configureBlocking(false)
                clearQueues()
                mSocketChanel.register(mSelector, SelectionKey.OP_READ)
                handler.sendMessage(handler.obtainMessage(MSG_CONNECTED))
                true
            } catch (e: Exception) {
                doError(e.message, ClientState.CONNECTING)
                false
            }
        }

        private fun closeConnection() {
            try {
                clearQueues()
                mForceDisconnect.set(true)
                mIsDoneWorkThread.set(true)
                mCurrentSendingItem?.buffer?.clear()
                mCurrentSendingItem = null
                mReceiveBuffer.clear()
                packetProtocol.clearBuffers()
                mSocketChanel.close()
                mSelector.close()
            } catch (e: Exception) {
                doError(e.message, ClientState.DISCONNECTING)
            }
            handler.sendMessage(handler.obtainMessage(MSG_DISCONNECTED))
        }

        private fun send(key: SelectionKey): Boolean {
            var sendingItem = mCurrentSendingItem
            if (sendingItem == null) {
                val packet = mToSendPackets.poll()
                if (packet == null) {
                    key.interestOps(key.interestOps() xor SelectionKey.OP_WRITE)
                    return true
                }
                try {
                    sendingItem = SendingItem(packet,
                            ByteBuffer.wrap(packetProtocol.encode(packetSerializer.serialize(packet))))
                    mCurrentSendingItem = sendingItem
                } catch (e: Exception) {
                    doError(e.message, ClientState.SENDING, packet)
                    return true
                }
            }

            if (mSocketChanel.write(sendingItem.buffer) == -1) return false
            if (!sendingItem.buffer.hasRemaining()) {
                sendingItem.buffer.clear()
                handler.sendMessage(handler.obtainMessage(MSG_PACKET_SENT, sendingItem.packet))
                mCurrentSendingItem = null
            }
            return true
        }

        private fun read(@Suppress("UNUSED_PARAMETER") key: SelectionKey): Boolean {
            val numberOfBytesRead = mSocketChanel.read(mReceiveBuffer)
            when (numberOfBytesRead) {
                -1 -> return false
                0 -> return true
                else -> {
                    mReceiveBuffer.flip()
                    try {
                        val ba: ByteArray = kotlin.ByteArray(mReceiveBuffer.remaining())
                        mReceiveBuffer.get(ba)
                        val packetsData = packetProtocol.decode(ba)
                        if (packetsData.isNotEmpty()) {
                            packetsData.forEach {
                                try {
                                    val packet = packetSerializer.deSerialize(it)
                                    handler.sendMessage(handler.obtainMessage(MSG_RECEIVED_PACKETS, packet))
                                } catch (e: Exception) {
                                    doError(e.message, ClientState.RECEIVING)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        doError(e.message, ClientState.RECEIVING)
                    }
                    mReceiveBuffer.clear()
                    return true
                }
            }
        }

        private fun doError(msg: String?, clientState: ClientState, packet: Packet? = null) {
            handler.sendMessage(handler.obtainMessage(MSG_ERROR, ErrorMessage(msg ?: UNKNOWN_ERROR, clientState, packet)))
        }

    }

    private class ErrorMessage(val msg: String, val clientState: ClientState, val packet: Packet?)

    private class SendingItem(val packet: Packet, val buffer: ByteBuffer)

    private enum class ClientConnectionState {
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
        DISCONNECTED
    }

    companion object {
        private val BUFFER_SIZE = 8192

        private val MSG_CONNECTED = 1
        private val MSG_ERROR = 2
        private val MSG_PACKET_SENT = 3
        private val MSG_DISCONNECTED = 4
        private val MSG_RECEIVED_PACKETS = 5

        private val UNKNOWN_ERROR = "Unknown"
    }

}