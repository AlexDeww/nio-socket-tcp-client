package com.alexdeww.niosockettcpclientlib

import com.alexdeww.niosockettcpclientlib.core.NIOSocketWorkerState

interface NIOSocketTcpClientListener<PACKET> {

    fun onConnected(client: NIOSocketTCPClient<PACKET>)

    fun onDisconnected(client: NIOSocketTCPClient<PACKET>)

    fun onPacketReceived(client: NIOSocketTCPClient<PACKET>, packet: PACKET)

    fun onError(client: NIOSocketTCPClient<PACKET>, state: NIOSocketWorkerState, error: Throwable)

}