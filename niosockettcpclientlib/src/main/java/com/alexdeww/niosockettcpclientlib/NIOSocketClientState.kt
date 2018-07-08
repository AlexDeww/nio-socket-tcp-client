package com.alexdeww.niosockettcpclientlib

enum class NIOSocketClientState {
    CONNECTING,
    DISCONNECTING,
    SENDING,
    RECEIVING
}