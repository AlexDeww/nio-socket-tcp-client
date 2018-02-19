package com.alexdeww.niosockettcpclientlib

interface CallbackEvents<PACKET> {

    fun onConnected(client: NIOSocketTCPClient<PACKET>)

    fun onDisconnected(client: NIOSocketTCPClient<PACKET>)

    fun onPacketSent(client: NIOSocketTCPClient<PACKET>, packet: PACKET)

    fun onPacketReceived(client: NIOSocketTCPClient<PACKET>, packet: PACKET)

    fun onError(client: NIOSocketTCPClient<PACKET>, clientState: ClientState, packet: PACKET?, error: Throwable?)

}