package com.alexdeww.niosockettcpclientlib.additional

interface NIOSocketSerializer<PACKET> {

    fun serialize(packet: PACKET): ByteArray

    fun deSerialize(packetData: ByteArray): PACKET

}
