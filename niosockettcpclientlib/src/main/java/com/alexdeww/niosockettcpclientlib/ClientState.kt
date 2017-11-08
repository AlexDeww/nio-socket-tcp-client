package com.alexdeww.niosockettcpclientlib

enum class ClientState {
    CONNECTING,
    DISCONNECTING,
    SENDING,
    RECEIVING
}