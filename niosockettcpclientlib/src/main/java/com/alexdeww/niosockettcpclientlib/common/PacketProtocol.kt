package com.alexdeww.niosockettcpclientlib.common

interface PacketProtocol {
    fun encode(packetData: ByteArray): ByteArray
    fun decode(rawData: ByteArray): List<ByteArray>
    fun clearBuffers()
}