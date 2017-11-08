package com.alexdeww.niosockettcpclientlib

interface PacketSerializer {
    fun serialize(packet: Packet): ByteArray
    fun deSerialize(buffer: ByteArray): Packet
}