package com.alexdeww.niosockettcpclientlib.core

enum class NIOSocketWorkerState {
    CONNECTING,
    DISCONNECTING,
    SENDING,
    RECEIVING
}
