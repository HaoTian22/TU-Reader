package com.example.nfctransit

import android.nfc.tech.IsoDep

/** 把卡通信与解析分开，以便验证断卡、超时及部分读取。 */
internal interface CardChannel {
    var timeout: Int
    fun connect()
    fun transceive(command: ByteArray): ByteArray
    fun close()
}

internal class IsoDepCardChannel(private val isoDep: IsoDep) : CardChannel {
    override var timeout: Int
        get() = isoDep.timeout
        set(value) { isoDep.timeout = value }
    override fun connect() = isoDep.connect()
    override fun transceive(command: ByteArray): ByteArray = isoDep.transceive(command)
    override fun close() = isoDep.close()
}
