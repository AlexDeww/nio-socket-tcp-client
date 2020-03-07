package com.alexdeww.niosockettcpclientlib.additional

interface NIOSocketPacketProtocol {

    fun encode(packetData: ByteArray): ByteArray

    fun decode(rawData: ByteArray): List<ByteArray>

    fun clearBuffers()

}
