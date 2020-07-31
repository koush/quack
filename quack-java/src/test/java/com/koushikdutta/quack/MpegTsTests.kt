package com.koushikdutta.quack

import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.experimental.and
import kotlin.experimental.or


class MpegTsStream(val streamType: Byte) {
    var sourceIndex = -1
    var mContinuityCounter = 0

    fun incrementContinuityCounter(): Int {
        if (++mContinuityCounter == 16) {
            mContinuityCounter = 0;
        }
        return mContinuityCounter;
    }
}

class MpegTsMuxer(val channel: FileChannel) {
    companion object {
        val MEDIA_MIMETYPE_VIDEO_AVC = 0x1b
        val MEDIA_MIMETYPE_AUDIO_AAC = 0x0f
    }

    fun internalWrite(buffer: ByteBuffer) {

    }

    fun writeAccessUnit(source: MpegTsStream, accessUnit: ByteBuffer, timeUs: Long, fout: FileOutputStream) {
        // 0x47
        // transport_error_indicator = b0
        // payload_unit_start_indicator = b1
        // transport_priority = b0
        // PID = b0 0001 1110 ???? (13 bits) [0x1e0 + 1 + sourceIndex]
        // transport_scrambling_control = b00
        // adaptation_field_control = b??
        // continuity_counter = b????
        // -- payload follows
        // packet_startcode_prefix = 0x000001
        // stream_id = 0x?? (0xe0 for avc video, 0xc0 for aac audio)
        // PES_packet_length = 0x????
        // reserved = b10
        // PES_scrambling_control = b00
        // PES_priority = b0
        // data_alignment_indicator = b1
        // copyright = b0
        // original_or_copy = b0
        // PTS_DTS_flags = b10  (PTS only)
        // ESCR_flag = b0
        // ES_rate_flag = b0
        // DSM_trick_mode_flag = b0
        // additional_copy_info_flag = b0
        // PES_CRC_flag = b0
        // PES_extension_flag = b0
        // PES_header_data_length = 0x05
        // reserved = b0010 (PTS)
        // PTS[32..30] = b???
        // reserved = b1
        // PTS[29..15] = b??? ???? ???? ???? (15 bits)
        // reserved = b1
        // PTS[14..0] = b??? ???? ???? ???? (15 bits)
        // reserved = b1
        // the first fragment of "buffer" follows
        val buffer = ByteBuffer.allocate(188)
        buffer.array().fill(0xff.toByte())
        val PID = 0x1e0 + source.sourceIndex + 1
        val continuity_counter = source.incrementContinuityCounter()

        // XXX if there are multiple streams of a kind (more than 1 audio or
        // more than 1 video) they need distinct stream_ids.
        val stream_id = if (source.streamType == 0x0f.toByte()) 0xc0 else 0xe0
        val PTS = (timeUs * 9) / 100
        var PES_packet_length = accessUnit.remaining() + 8
        val padding = accessUnit.remaining() < (188 - 18)
        if (PES_packet_length >= 65536) {
            // This really should only happen for video.
//            CHECK_EQ(stream_id, 0xe0u);
            if (stream_id != 0xe0)
                throw IllegalStateException("expected video big packet")
            // It's valid to set this to 0 for video according to the specs.
            PES_packet_length = 0;
        }

        buffer.put(0x47)
        buffer.put((0x40 or (PID shr 8)).toByte())
        buffer.put((PID and 0xff).toByte())
        val paddingVal = if (padding) 0x30 else 0x10 
        buffer.put((paddingVal or continuity_counter).toByte())
        if (padding) {
            val paddingSize = 188 - accessUnit.remaining() - 18
            buffer.put((paddingSize - 1).toByte())
            if (paddingSize >= 2) {
                buffer.put(0)
                buffer.position(buffer.position() + paddingSize - 2)
            }
        }
        buffer.put(0x00)
        buffer.put(0x00)
        buffer.put(0x01)
        buffer.put(stream_id.toByte())
        buffer.put((PES_packet_length shr 8).toByte())
        buffer.put((PES_packet_length and 0xff).toByte())
        buffer.put(0x84.toByte())
        buffer.put(0x80.toByte())
        buffer.put(0x05)
        buffer.put((0x20 or (((PTS shr 30).toInt() and 7) shl 1) or 1).toByte())
        buffer.put(((PTS shr 22) and 0xff).toByte())
        buffer.put(((((PTS shr 15) and 0x7f) shl 1) or 1).toByte())
        buffer.put(((PTS shr 7) and 0xff).toByte())
        buffer.put((((PTS and 0x7f).toInt() shl 1) or 1).toByte())
        val sizeLeft = buffer.remaining()
        var copy = accessUnit.remaining()
        if (copy > sizeLeft) {
            copy = sizeLeft;
        }

        val accessUnitSize = accessUnit.remaining()
        accessUnit.limit(copy)
        buffer.put(accessUnit)

        buffer.clear()
        internalWrite(buffer)

        var offset = copy
        while (offset < accessUnitSize) {
            val lastAccessUnit = ((accessUnitSize - offset) < 184);
            // for subsequent fragments of "buffer":
            // 0x47
            // transport_error_indicator = b0
            // payload_unit_start_indicator = b0
            // transport_priority = b0
            // PID = b0 0001 1110 ???? (13 bits) [0x1e0 + 1 + sourceIndex]
            // transport_scrambling_control = b00
            // adaptation_field_control = b??
            // continuity_counter = b????
            // the fragment of "buffer" follows.
            buffer.clear()
            buffer.array().fill(0xff.toByte())
            val continuity_counter = source.incrementContinuityCounter()

            buffer.put(0x47)
            buffer.put((0x00 or (PID shr 8)).toByte())
            buffer.put((PID and 0xff).toByte())
            val lastAccessUnitVal = if (lastAccessUnit) 0x30 else 0x10
            buffer.put((lastAccessUnitVal or continuity_counter).toByte())
            if (lastAccessUnit) {
                // Pad packet using an adaptation field
                // Adaptation header all to 0 execpt size
                val paddingSize = 184 - (accessUnitSize - offset);
                buffer.put((paddingSize - 1).toByte())
                if (paddingSize >= 2) {
                    buffer.put(0x00)
                    buffer.position(buffer.position() + paddingSize - 2)
                }
            }
            val sizeLeft = buffer.remaining()
            var copy = accessUnitSize - offset;
            if (copy > sizeLeft) {
                copy = sizeLeft;
            }
            accessUnit.position(offset)
            accessUnit.limit(offset + copy)
            buffer.put(accessUnit)

            buffer.clear()
            internalWrite(buffer)
            offset += copy;
        }
    }


    var mPATContinuityCounter = 0
    fun writePAT() {
        // 0x47
        // transport_error_indicator = b0
        // payload_unit_start_indicator = b1
        // transport_priority = b0
        // PID = b0000000000000 (13 bits)
        // transport_scrambling_control = b00
        // adaptation_field_control = b01 (no adaptation field, payload only)
        // continuity_counter = b????
        // skip = 0x00
        // --- payload follows
        // table_id = 0x00
        // section_syntax_indicator = b1
        // must_be_zero = b0
        // reserved = b11
        // section_length = 0x00d
        // transport_stream_id = 0x0000
        // reserved = b11
        // version_number = b00001
        // current_next_indicator = b1
        // section_number = 0x00
        // last_section_number = 0x00
        //   one program follows:
        //   program_number = 0x0001
        //   reserved = b111
        //   program_map_PID = 0x01e0 (13 bits!)
        // CRC = 0x????????

        val kData = byteArrayOf(
            0x47,
            0x40, 0x00, 0x10, 0x00,                   // b0100 0000 0000 0000 0001 ???? 0000 0000
            0x00, 0xb0.toByte(), 0x0d, 0x00,          // b0000 0000 1011 0000 0000 1101 0000 0000
            0x00, 0xc3.toByte(), 0x00, 0x00,          // b0000 0000 1100 0011 0000 0000 0000 0000
            0x00, 0x01, 0xe1.toByte(), 0xe0.toByte(), // b0000 0000 0000 0001 1110 0001 1110 0000
            0x00, 0x00, 0x00, 0x00                    // b???? ???? ???? ???? ???? ???? ???? ????
        )
        val buffer = ByteBuffer.allocate(188)
        buffer.array().fill(0xff.toByte())
        buffer.put(kData)
        buffer.clear()

        if (++mPATContinuityCounter == 16)
            mPATContinuityCounter = 0;
        buffer.array()[3] = buffer.array()[3] or mPATContinuityCounter.toByte()

        val crc = CRC32.calc(buffer.array(), 5, 12)
        buffer.putInt(17, crc)

        buffer.clear()
        internalWrite(buffer)
    }

    fun writeProgramMap() {
        val kData = byteArrayOf(
            0x47,
            0x41, 0xe0.toByte(), 0x10, 0x00,         // b0100 0001 1110 0000 0001 ???? 0000 0000
            0x02, 0xb0.toByte(), 0x00, 0x00,         // b0000 0010 1011 ???? ???? ???? 0000 0000
            0x01, 0xc3.toByte(), 0x00, 0x00,         // b0000 0001 1100 0011 0000 0000 0000 0000
            0xe0.toByte(), 0x00, 0xf0.toByte(), 0x00 // b111? ???? ???? ???? 1111 0000 0000 0000
        )

        val buffer = ByteBuffer.allocate(188)
        buffer.array().fill(0xff.toByte())
        buffer.put(kData)
        buffer.clear()

        if (++mPATContinuityCounter == 16)
            mPATContinuityCounter = 0;
        buffer.array()[3] = buffer.array()[3] or mPATContinuityCounter.toByte()


        val section_length = 5 * sources.size + 4 + 9;
        buffer.array()[6] = buffer.array()[6] or (section_length shr 8).toByte()
        buffer.array()[7] = (section_length and 0xff).toByte()
        val kPCR_PID = 0x1e1
        buffer.array()[13] = buffer.array()[13] or ((kPCR_PID shr 8).toByte() and 0x1f);
        buffer.array()[14] = (kPCR_PID and 0xff).toByte()

        buffer.position(15)

        for (source in sources.withIndex()) {
            buffer.put(source.value.streamType)
            val ES_PID = 0x1e0 + source.index + 1;
            buffer.put((0xe0 or (ES_PID shr 8)).toByte())
            buffer.put((ES_PID and 0xff).toByte())
            buffer.put(0xf0.toByte())
            buffer.put(0)
        }

        buffer.clear()
        internalWrite(buffer)
    }

    var sourceIndices = 0
    private val sources = arrayListOf<MpegTsStream>()
    fun addSource(source: MpegTsStream) {
        source.sourceIndex = sourceIndices++
    }
}

class MpegTsTests {
    @Test
    fun testMpegTs() {
        val f = File("/Users/koush/Downloads/sintel.ts")

        val buffer = ByteBuffer.allocate(188)

        val fin = FileChannel.open(f.toPath())

        val pids = mutableSetOf<Short>()
        var packetsRead = 0
        while (true) {
            buffer.clear()
            val read = fin.read(buffer)
            if (read <= 0)
                break
            buffer.flip()

            val g = buffer.get()
            val b23 = buffer.short
            val transportErrorIndicator = (b23 and 0x8000.toShort()) != 0.toShort()
            val payloadStartIndicator = (b23 and 0x4000.toShort()) != 0.toShort()
            val transportPriority = (b23 and 0x0020.toShort()) != 0.toShort()
            val pid = b23 and 0x1FFF.toShort()

            pids.add(pid)
            packetsRead++

            val eat = buffer.getShort()

            if (pid != 0.toShort())
                continue

            buffer.clear()
            val crc = buffer.getInt(17)

            buffer.position(5)
            buffer.limit(17)

            val result = CRC32.calc(buffer.array(),  5, 12)
            assert(result == crc)
        }

        println(packetsRead)
    }


    @Test
    fun convertAnnexBtoAVCC() {
        val f = File("/Users/koush/Downloads/test.h264")

        val buffer = ByteBuffer.allocate(f.length().toInt())

        val fin = FileChannel.open(f.toPath())
        val read = fin.read(buffer)
        buffer.flip()

        var previous = 0
        var i = 1
        while (i < buffer.remaining() - 4) {
            if (buffer.getInt(i) != 1) {
                i++
                continue
            }
            val diff = i - previous - 4
            buffer.putInt(previous, diff)
            previous = i
            i += 4
        }

        val diff = buffer.remaining() - previous - 4
        buffer.putInt(previous, diff)
        i += 4

        val fout = FileOutputStream("/Users/koush/Downloads/test2.h264")
        fout.channel.write(buffer)
        fout.close()
    }
}
