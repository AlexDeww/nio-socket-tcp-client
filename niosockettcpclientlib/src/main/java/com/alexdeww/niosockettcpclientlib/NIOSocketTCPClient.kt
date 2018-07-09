package com.alexdeww.niosockettcpclientlib

import com.alexdeww.niosockettcpclientlib.additional.NIOSocketPacketProtocol
import com.alexdeww.niosockettcpclientlib.additional.NIOSocketSerializer
import com.alexdeww.niosockettcpclientlib.core.NIOSocketOperationResult
import com.alexdeww.niosockettcpclientlib.core.NIOSocketWorkerState
import com.alexdeww.niosockettcpclientlib.core.NIOTcpSocketWorker
import com.alexdeww.niosockettcpclientlib.core.safeCall

open class NIOSocketTCPClient<PACKET>(
        host: String,
        port: Int,
        keepAlive: Boolean,
        bufferSize: Int,
        connectionTimeout: Int,
        protected val protocol: NIOSocketPacketProtocol,
        protected val serializer: NIOSocketSerializer<PACKET>,
        protected val clientListener: NIOSocketTcpClientListener<PACKET>
) : NIOSocketTCPClientCommon(host, port, keepAlive, bufferSize, connectionTimeout) {

    override fun onConnected(socket: NIOTcpSocketWorker) {
        safeCall { protocol.clearBuffers() }
        super.onConnected(socket)
        clientListener.onConnected(this)
    }

    override fun onDisconnected(socket: NIOTcpSocketWorker) {
        safeCall { protocol.clearBuffers() }
        super.onDisconnected(socket)
        clientListener.onDisconnected(this)
    }

    override fun onDataReceived(socket: NIOTcpSocketWorker, data: ByteArray) {
        super.onDataReceived(socket, data)
         try {
             processData(data)
         } catch (e: Throwable) {
             onError(socket, NIOSocketWorkerState.RECEIVING, e, data)
         }
    }

    override fun onError(socket: NIOTcpSocketWorker, state: NIOSocketWorkerState, error: Throwable, data: ByteArray?) {
        super.onError(socket, state, error, data)
        clientListener.onError(this, state, error)
    }

    open fun sendPacket(packet: PACKET, operationResult: NIOSocketOperationResult): Boolean =
            write(protocol.encode(serializer.serialize(packet)), operationResult)

    protected open fun doOnPacketReceived(packet: PACKET) {
        clientListener.onPacketReceived(this, packet)
    }

    protected open fun processData(data: ByteArray) {
        protocol.decode(data)
                .map { serializer.deSerialize(it) }
                .forEach { doOnPacketReceived(it) }
    }

}