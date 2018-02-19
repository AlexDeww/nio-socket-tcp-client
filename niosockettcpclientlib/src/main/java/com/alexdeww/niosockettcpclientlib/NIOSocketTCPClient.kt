package com.alexdeww.niosockettcpclientlib

import com.alexdeww.niosockettcpclientlib.exception.AlreadyConnected

class NIOSocketTCPClient<in PACKET>(
        val host: String,
        val port: Int,
        val keepAlive: Boolean,
        private val packetProtocol: PacketProtocol<PACKET>,
        private val callbackEvents: CallbackEvents<PACKET>
) {

    private val clearLock = Object()
    private var mWorkThread: Thread? = null
    private var mWorkRunnable: NIOSocketTCPClientRunnable<PACKET>? = null
    private val mClientCallback = object : NIOSocketTCPClientRunnable.Callback<PACKET> {
        override fun onConnected() {
            callbackEvents.onConnected(this@NIOSocketTCPClient)
        }

        override fun onDisconnected() {
            synchronized(clearLock) {
                mWorkThread = null
                mWorkRunnable = null
            }
            callbackEvents.onDisconnected(this@NIOSocketTCPClient)
        }

        override fun onPacketSent(packet: PACKET) {
            callbackEvents.onPacketSent(this@NIOSocketTCPClient, packet)
        }

        override fun onPacketReceived(packet: PACKET) {
            callbackEvents.onPacketReceived(this@NIOSocketTCPClient, packet)
        }

        override fun onError(state: ClientState, packet: PACKET?, error: Throwable?) {
            callbackEvents.onError(this@NIOSocketTCPClient, state, packet, error)
        }
    }

    val isConnected: Boolean get() = mWorkRunnable?.isConnected?.get() ?: false

    fun connect() {
        if (mWorkThread != null) throw AlreadyConnected()

        synchronized(clearLock) {
            try {
                mWorkRunnable = NIOSocketTCPClientRunnable(host, port, keepAlive, packetProtocol)
                mWorkRunnable?.registrateCallback(mClientCallback)
                mWorkThread = Thread(mWorkRunnable)
                mWorkThread?.start()
            } catch (e: Throwable) {
                mWorkRunnable?.removeCallback()
                mWorkThread = null
                mWorkRunnable = null
                throw e
            }
        }
    }

    fun disconnect() {
        synchronized(clearLock) {
            mWorkThread?.interrupt()
            mWorkRunnable?.wakeupSelector()
        }
    }

    fun forceDisconnect() {
        synchronized(clearLock) {
            if (mWorkRunnable != null && mWorkThread != null) {
                mWorkRunnable?.removeCallback()
                mWorkThread?.interrupt()
                mWorkRunnable?.wakeupSelector()
                callbackEvents.onDisconnected(this@NIOSocketTCPClient)
            }
            mWorkThread = null
            mWorkRunnable = null
        }
    }

    fun sendPacket(packet: PACKET): Boolean {
        return if (isConnected) {
            mWorkRunnable?.addPacketToSendQueue(packet)
            true
        } else false
    }

}