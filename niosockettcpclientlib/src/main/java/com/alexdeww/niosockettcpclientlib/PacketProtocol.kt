package com.alexdeww.niosockettcpclientlib

interface PacketProtocol<PACKET> {
    fun encode(packet: PACKET): ByteArray
    fun decode(rawData: ByteArray): List<PACKET>
    fun clearBuffers()
}