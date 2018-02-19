package com.alexdeww.niosockettcpclient

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import com.alexdeww.niosockettcpclientlib.*
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {

    private val mTcpClient = NIOSocketTCPClient("192.168.0.3", 43567, false,
            object : PacketProtocol<String> {
                override fun encode(packet: String): ByteArray = packet.toByteArray()

                override fun decode(rawData: ByteArray): List<String> = arrayListOf(rawData.toString())

                override fun clearBuffers() {}
            },
            object : CallbackEvents<String> {
                override fun onConnected(client: NIOSocketTCPClient<String>) {
                    Log.i("MainActivity", "onConnected")
                }

                override fun onDisconnected(client: NIOSocketTCPClient<String>) {
                    Log.i("MainActivity", "onDisconnected")
                }

                override fun onPacketSent(client: NIOSocketTCPClient<String>, packet: String) {
                    Log.i("MainActivity", "onPacketSent: $packet")
                }

                override fun onPacketReceived(client: NIOSocketTCPClient<String>, packet: String) {
                    Log.i("MainActivity", "onPacketReceived: $packet")
                }

                override fun onError(client: NIOSocketTCPClient<String>, clientState: ClientState, packet: String?, error: Throwable?) {
                    Log.e("MainActivity", "onError, $error")
                    error?.printStackTrace()
                }
            })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        button.setOnClickListener {
            mTcpClient.connect()
        }

        button2.setOnClickListener {
            mTcpClient.disconnect()
        }

        button3.setOnClickListener {
            mTcpClient.sendPacket("test!!!$%%#$3")
        }
    }
}
