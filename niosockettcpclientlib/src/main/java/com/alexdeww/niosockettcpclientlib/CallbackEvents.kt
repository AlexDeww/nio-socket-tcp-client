package com.alexdeww.niosockettcpclientlib

interface CallbackEvents {

    fun onConnected(client: NIOSocketTCPClient)

    fun onDisconnected(client: NIOSocketTCPClient)

    fun onPacketSent(client: NIOSocketTCPClient, packet: Packet)

    fun onPacketReceived(client: NIOSocketTCPClient, packet: Packet)

    fun onError(client: NIOSocketTCPClient, clientState: ClientState, packet: Packet?, error: Throwable?)

}