package com.alexdeww.niosockettcpclientlib.common

interface PacketSerializer {
    fun serialize(packet: Packet): ByteArray
    fun deSerialize(buffer: ByteArray): Packet
}