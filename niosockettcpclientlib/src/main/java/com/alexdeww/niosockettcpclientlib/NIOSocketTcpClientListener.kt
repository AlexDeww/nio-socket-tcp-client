package com.alexdeww.niosockettcpclientlib

interface NIOSocketTcpClientListener<PACKET> {

    fun onConnected(client: NIOSocketTCPClient<PACKET>)

    fun onDisconnected(client: NIOSocketTCPClient<PACKET>)

    fun onPacketReceived(client: NIOSocketTCPClient<PACKET>, packet: PACKET)

    fun onError(client: NIOSocketTCPClient<PACKET>, error: Throwable)

}