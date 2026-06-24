package me.rerere.common.audio

/** Wrap raw 16-bit PCM samples into a little-endian RIFF/WAVE container. */
fun pcm16ToWav(
    pcm: ByteArray,
    sampleRate: Int,
    channels: Int,
    bitsPerSample: Int = 16
): ByteArray {
    val blockAlign = channels * bitsPerSample / 8
    val byteRate = sampleRate * blockAlign
    val audioDataSize = pcm.size
    // 36 = 44-byte header minus the 8-byte "RIFF" + size front matter.
    val chunkSize = 36 + audioDataSize
    val out = ByteArray(44 + audioDataSize)

    // RIFF header
    out[0] = 'R'.code.toByte()
    out[1] = 'I'.code.toByte()
    out[2] = 'F'.code.toByte()
    out[3] = 'F'.code.toByte()
    writeIntLE(out, 4, chunkSize)
    out[8] = 'W'.code.toByte()
    out[9] = 'A'.code.toByte()
    out[10] = 'V'.code.toByte()
    out[11] = 'E'.code.toByte()

    // fmt subchunk (PCM, 16 bytes)
    out[12] = 'f'.code.toByte()
    out[13] = 'm'.code.toByte()
    out[14] = 't'.code.toByte()
    out[15] = ' '.code.toByte()
    writeIntLE(out, 16, 16)            // subchunk1 size
    writeShortLE(out, 20, 1)           // PCM audio format
    writeShortLE(out, 22, channels)
    writeIntLE(out, 24, sampleRate)
    writeIntLE(out, 28, byteRate)
    writeShortLE(out, 32, blockAlign)
    writeShortLE(out, 34, bitsPerSample)

    // data subchunk
    out[36] = 'd'.code.toByte()
    out[37] = 'a'.code.toByte()
    out[38] = 't'.code.toByte()
    out[39] = 'a'.code.toByte()
    writeIntLE(out, 40, audioDataSize)

    // PCM payload
    pcm.copyInto(out, 44)
    return out
}

private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = value.toByte()
    buf[offset + 1] = (value shr 8).toByte()
    buf[offset + 2] = (value shr 16).toByte()
    buf[offset + 3] = (value shr 24).toByte()
}

private fun writeShortLE(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = value.toByte()
    buf[offset + 1] = (value shr 8).toByte()
}