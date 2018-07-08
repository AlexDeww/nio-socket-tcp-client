package com.alexdeww.niosockettcpclientlib.additional

import com.alexdeww.niosockettcpclientlib.NIOSocketCallbackEvents
import com.alexdeww.niosockettcpclientlib.NIOSocketTCPClient

abstract class NIOSocketPacketHandler<PACKET>(
        protected val protocol: NIOSocketPacketProtocol,
        protected val serializer: NIOSocketSerializer<PACKET>
) : NIOSocketCallbackEvents<PACKET> {

    override fun onConnected(client: NIOSocketTCPClient<PACKET>) {
        clearBuffers()
    }

    override fun onDisconnected(client: NIOSocketTCPClient<PACKET>) {
        clearBuffers()
    }

    override fun onPrepareDataSend(client: NIOSocketTCPClient<PACKET>, data: PACKET): ByteArray =
            protocol.encode(serializer.serialize(data))

    override fun onSrcDataReceived(client: NIOSocketTCPClient<PACKET>, srcData: ByteArray): List<PACKET> =
            protocol.decode(srcData).map { serializer.deSerialize(it) }

    protected fun clearBuffers() {
        protocol.clearBuffers()
    }
}