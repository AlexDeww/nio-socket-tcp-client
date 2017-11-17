package com.alexdeww.niosockettcpclient

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import com.alexdeww.niosockettcpclientlib.*
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {

    private val mTcpClient = NIOSocketTCPClient("192.168.0.3", 43567, false,
            object : PacketProtocol {
                override fun encode(packetData: ByteArray): ByteArray {
                    return packetData
                }

                override fun decode(rawData: ByteArray): List<ByteArray> {
                    return arrayListOf()
                }

                override fun clearBuffers() {

                }
            },
            object : PacketSerializer {
                override fun serialize(packet: Packet): ByteArray {
                    return "1234567890".toByteArray()
                }

                override fun deSerialize(buffer: ByteArray): Packet {
                    TODO("mot impl")
                }
            },
            object : CallbackEvents {
                override fun onConnected(client: NIOSocketTCPClient) {
                    Log.i("MainActivity", "onConnected")
                }

                override fun onDisconnected(client: NIOSocketTCPClient) {
                    Log.i("MainActivity", "onDisconnected")
                }

                override fun onPacketSent(client: NIOSocketTCPClient, packet: Packet) {
                    Log.i("MainActivity", "onPacketSent")
                }

                override fun onPacketReceived(client: NIOSocketTCPClient, packet: Packet) {
                    Log.i("MainActivity", "onPacketReceived")
                }

                override fun onError(client: NIOSocketTCPClient, clientState: ClientState, packet: Packet?, error: Throwable?) {
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
            mTcpClient.sendPacket(object : Packet() {  })
        }
    }
}
