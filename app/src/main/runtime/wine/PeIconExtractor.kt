package com.winlator.cmod.runtime.wine
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Extracts the largest icon from a Windows PE (.exe) file.
 * Parses PE resource section to find RT_GROUP_ICON / RT_ICON entries.
 */
object PeIconExtractor {
    @JvmStatic
    fun extractIcon(exeFile: File): Bitmap? {
        if (!exeFile.exists()) return null
        return try {
            RandomAccessFile(exeFile, "r").use { raf -> extractFromPe(raf) }
        } catch (_: Exception) {
            null
        }
    }

    @JvmStatic
    fun extractAndSave(
        exeFile: File,
        outPng: File,
    ): Boolean {
        val bmp = extractIcon(exeFile) ?: return false
        return try {
            outPng.parentFile?.mkdirs()
            outPng.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun extractFromPe(raf: RandomAccessFile): Bitmap? {
        // DOS header: e_lfanew at offset 0x3C
        raf.seek(0x3C)
        val peOffset = readInt(raf)
        raf.seek(peOffset.toLong())

        // PE signature
        val sig = readInt(raf)
        if (sig != 0x00004550) return null // "PE\0\0"

        // COFF header
        raf.skipBytes(2) // machine
        val numSections = readShort(raf)
        raf.skipBytes(12) // skip to sizeOfOptionalHeader
        val optHeaderSize = readShort(raf)
        raf.skipBytes(2) // characteristics

        val optHeaderPos = raf.filePointer
        val magic = readShort(raf)
        val isPe32Plus = magic == 0x20B

        // Data directory: resource table is entry index 2
        val ddOffset = if (isPe32Plus) 112 else 96
        raf.seek(optHeaderPos + ddOffset + 2 * 8L) // skip to 3rd entry (index 2)
        val resRva = readInt(raf)
        val resSize = readInt(raf)
        if (resRva == 0 || resSize == 0) return null

        val sectionStart = optHeaderPos + optHeaderSize
        raf.seek(sectionStart)
        var resFileOffset = 0L
        var resSectionRva = 0
        var resRawSize = 0
        for (i in 0 until numSections) {
            val pos = sectionStart + i * 40L
            raf.seek(pos + 12) // virtualAddress
            val va = readInt(raf)
            val rawSize = readInt(raf)
            val rawPtr = readInt(raf)
            if (resRva >= va && resRva < va + rawSize) {
                resSectionRva = va
                resFileOffset = rawPtr.toLong() + (resRva - va)
                resRawSize = rawSize
                break
            }
        }
        if (resFileOffset == 0L) return null

        // Use the smaller of resSize (Data Directory) and rawSize (Section Header)
        // to avoid reading beyond the actual file data
        val actualResSize = minOf(resSize, resRawSize)
        raf.seek(resFileOffset)
        val resBuf = ByteArray(actualResSize)
        raf.readFully(resBuf)
        val bb = ByteBuffer.wrap(resBuf).order(ByteOrder.LITTLE_ENDIAN)

        val groupIconEntries = findResourceType(bb, 0, 14) // RT_GROUP_ICON
        val iconEntries = findResourceType(bb, 0, 3) // RT_ICON
        if (groupIconEntries.isEmpty()) return null

        val grpDataRva = resolveToDataEntry(bb, groupIconEntries[0])
        if (grpDataRva == null) return null

        val grpFileOff = grpDataRva.first - resSectionRva
        val grpSize = grpDataRva.second
        if (grpFileOff < 0 || grpFileOff + grpSize > resBuf.size) return null

        bb.position(grpFileOff)
        bb.short // reserved
        bb.short // type
        val count = bb.short.toInt() and 0xFFFF
        if (count == 0) return null

        data class GrpEntry(
            val w: Int,
            val h: Int,
            val bitCount: Int,
            val bytesInRes: Int,
            val id: Int,
        )
        val entries = mutableListOf<GrpEntry>()
        for (i in 0 until count) {
            val w = bb.get().toInt() and 0xFF
            val h = bb.get().toInt() and 0xFF
            bb.get() // colorCount
            bb.get() // reserved
            bb.short // planes
            val bitCount = bb.short.toInt() and 0xFFFF
            val bytesInRes = bb.int
            val id = bb.short.toInt() and 0xFFFF
            val realW = if (w == 0) 256 else w
            val realH = if (h == 0) 256 else h
            entries.add(GrpEntry(realW, realH, bitCount, bytesInRes, id))
        }

        // Sort by strategy: larger size preferred, then higher bit depth
        // Try large icons (>= 32) first, then fall back to small icons
        val sorted = entries.sortedWith(compareByDescending<GrpEntry> { it.w >= 32 || it.h >= 32 }
            .thenByDescending { it.w * it.h }
            .thenByDescending { it.bitCount })

        for (entry in sorted) {
            val iconDataRva = resolveIconById(bb, iconEntries, entry.id) ?: continue
            val iconFileOff = iconDataRva.first - resSectionRva
            val iconSize = iconDataRva.second
            if (iconFileOff < 0 || iconFileOff + iconSize > resBuf.size) continue

            val iconData = ByteArray(iconSize)
            System.arraycopy(resBuf, iconFileOff, iconData, 0, iconSize)

            // 1. Check if PNG-embedded icon data (modern PE files)
            if (isPNGData(iconData)) {
                val bmp = BitmapFactory.decodeByteArray(iconData, 0, iconData.size)
                if (bmp != null) return bmp
            }

            // 2. Try MSBitmap.decodeBuffer for BMP icons (supports 8/24/32 bit uncompressed)
            val decoded = decodeBmpIcon(iconData, entry.w, entry.h)
            if (decoded != null) return decoded

            // 3. Fallback: wrap in ICO format and let BitmapFactory handle it
            //    (handles edge cases that MSBitmap may not cover)
            val ico = buildIco(iconData, entry.w, entry.h, entry.bitCount)
            val bmp = BitmapFactory.decodeByteArray(ico, 0, ico.size)
            if (bmp != null) return bmp
        }

        return null
    }

    /** Check if byte array starts with PNG magic number */
    private fun isPNGData(data: ByteArray): Boolean {
        if (data.size < 8) return false
        // PNG signature: 89 50 4E 47 0D 0A 1A 0A
        return data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
            data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() &&
            data[4] == 0x0D.toByte() && data[5] == 0x0A.toByte() &&
            data[6] == 0x1A.toByte() && data[7] == 0x0A.toByte()
    }

    /**
     * Try decoding ICO BMP icon data via MSBitmap.
     * ICO bitmap data: BITMAPINFOHEADER (with optional color table) + pixel data.
     * The [bitmapOffset] field in BITMAPINFOHEADER points to where pixel data begins.
     */
    private fun decodeBmpIcon(iconData: ByteArray, grpW: Int, grpH: Int): Bitmap? {
        if (iconData.size < 40) return null

        val dibBuf = ByteBuffer.wrap(iconData).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = dibBuf.int           // size of BITMAPINFOHEADER
        val bmpWidth = dibBuf.int
        val bmpHeight = dibBuf.int
        dibBuf.short // planes
        val actualBitCount = dibBuf.short.toInt() and 0xFFFF
        val compression = dibBuf.int

        // Only support uncompressed 8/24/32 bit icons
        if (actualBitCount != 8 && actualBitCount != 24 && actualBitCount != 32) return null
        if (compression != 0) return null
        if (bmpWidth <= 0 || bmpHeight <= 0) return null

        val width = bmpWidth
        // ICO height is doubled for BMP format (bottom half is the AND mask)
        val height = bmpHeight / 2

        if (headerSize < 40 || headerSize > iconData.size) return null
        if (headerSize + height * ((actualBitCount / 8 * width + 3) and 3.inv()) > iconData.size) return null

        dibBuf.position(headerSize)
        return MSBitmap.decodeBuffer(width, height, actualBitCount, dibBuf)
    }

    /** Wrap raw DIB data into a minimal ICO file format */
    private fun buildIco(
        data: ByteArray,
        w: Int,
        h: Int,
        bitCount: Int,
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0) // reserved
        buf.putShort(1) // type = icon
        buf.putShort(1) // count
        buf.put((if (w >= 256) 0 else w).toByte())
        buf.put((if (h >= 256) 0 else h).toByte())
        buf.put(0) // color count
        buf.put(0) // reserved
        buf.putShort(1) // planes
        buf.putShort(bitCount.toShort())
        buf.putInt(data.size)
        buf.putInt(22) // offset to data
        bos.write(buf.array())
        bos.write(data)
        return bos.toByteArray()
    }

    // Resource directory parsing helpers
    private fun findResourceType(
        bb: ByteBuffer,
        dirOffset: Int,
        typeId: Int,
    ): List<Int> {
        bb.position(dirOffset + 12)
        val namedCount = bb.short.toInt() and 0xFFFF
        val idCount = bb.short.toInt() and 0xFFFF
        val results = mutableListOf<Int>()
        for (i in 0 until namedCount + idCount) {
            val id = bb.int
            val offset = bb.int
            if (id == typeId && (offset and 0x80000000.toInt()) != 0) {
                results.add(offset and 0x7FFFFFFF)
            }
        }
        return results
    }

    private fun resolveToDataEntry(
        bb: ByteBuffer,
        subdirOffset: Int,
    ): Pair<Int, Int>? {
        // Navigate through sub-directories until we reach a data entry
        bb.position(subdirOffset + 12)
        val namedCount = bb.short.toInt() and 0xFFFF
        val idCount = bb.short.toInt() and 0xFFFF
        if (namedCount + idCount == 0) return null

        bb.int // skip first entry name/id
        val offset = bb.int

        return if ((offset and 0x80000000.toInt()) != 0) {
            // Another subdirectory — go one level deeper
            resolveToDataEntry(bb, offset and 0x7FFFFFFF)
        } else {
            // Data entry
            bb.position(offset)
            val rva = bb.int
            val size = bb.int
            Pair(rva, size)
        }
    }

    private fun resolveIconById(
        bb: ByteBuffer,
        iconSubdirs: List<Int>,
        targetId: Int,
    ): Pair<Int, Int>? {
        for (subdirOff in iconSubdirs) {
            bb.position(subdirOff + 12)
            val namedCount = bb.short.toInt() and 0xFFFF
            val idCount = bb.short.toInt() and 0xFFFF
            for (i in 0 until namedCount + idCount) {
                val id = bb.int
                val offset = bb.int
                if (id == targetId) {
                    return if ((offset and 0x80000000.toInt()) != 0) {
                        resolveToDataEntry(bb, offset and 0x7FFFFFFF)
                    } else {
                        bb.position(offset)
                        val rva = bb.int
                        val size = bb.int
                        Pair(rva, size)
                    }
                }
            }
        }
        return null
    }

    private fun readInt(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readShort(raf: RandomAccessFile): Int {
        val b = ByteArray(2)
        raf.readFully(b)
        return ByteBuffer
            .wrap(b)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
            .toInt() and 0xFFFF
    }
}
