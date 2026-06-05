package com.winlator.cmod.runtime.wine;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.winlator.cmod.shared.io.FileUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public abstract class MSBitmap {
  public static Bitmap open(File targetFile) {
    if (!targetFile.isFile()) return null;
    byte[] bytes = FileUtils.read(targetFile);
    if (bytes == null) return null;

    ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    if (data.getShort() != 0x4d42) return null;

    int fileSize = data.getInt();
    if (fileSize > targetFile.length()) return null;

    data.getInt();
    int dataOffset = data.getInt();
    int infoHeaderSize = data.getInt();
    int width = data.getInt();
    int height = data.getInt();
    short planes = data.getShort();
    short bitCount = data.getShort();
    int compression = data.getInt();
    int imageSize = data.getInt();
    int hr = data.getInt();
    int vr = data.getInt();
    int colorsUsed = data.getInt();
    int colorsImportant = data.getInt();

    if (width == 0 || height == 0) return null;

    boolean invertY = true;
    if (height < 0) {
      height *= -1;
      invertY = false;
    }

    ByteBuffer pixels = ByteBuffer.allocate(width * height * 4);
    byte r1 = 0, g1 = 0, b1 = 0, r2 = 0, g2 = 0, b2 = 0;
    boolean started = false;
    boolean blank = true;
    for (int y = height - 1, i = data.position(), j, line; y >= 0; y--) {
      line = invertY ? y : height - 1 - y;

      for (int x = 0; x < width; x++) {
        j = line * width * 4 + x * 4;
        b1 = data.get(i++);
        g1 = data.get(i++);
        r1 = data.get(i++);
        pixels.put(j + 2, b1);
        pixels.put(j + 1, g1);
        pixels.put(j + 0, r1);
        pixels.put(j + 3, (byte) 255);

        if (!started) {
          b2 = b1;
          g2 = g1;
          r2 = r1;
          started = true;
        } else if (r1 != r2 || b1 != b2 || g1 != g2) {
          blank = false;
        }
      }

      i += width % 4;
    }

    if (blank) return null;

    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    bitmap.copyPixelsFromBuffer(pixels);
    return bitmap;
  }

  /**
   * Decode raw DIB (Device Independent Bitmap) pixel data from a ByteBuffer.
   * The buffer position should already be at the pixel data start.
   * Used for extracting BMP icons from PE resources.
   *
   * @param width      icon width in pixels
   * @param height     icon height in pixels
   * @param bitCount   bits per pixel (8, 24, 32)
   * @param pixelData  ByteBuffer positioned at pixel data, little-endian
   * @return decoded Bitmap, or null on failure
   */
  public static Bitmap decodeBuffer(int width, int height, int bitCount, ByteBuffer pixelData) {
    if (width <= 0 || height <= 0 || pixelData == null) return null;
    if (bitCount != 8 && bitCount != 24 && bitCount != 32) return null;

    try {
      int bytesPerPixel = bitCount / 8;
      int rowBytes = (bytesPerPixel * width + 3) & ~3; // DWORD aligned
      int needed = rowBytes * height;
      if (pixelData.remaining() < needed) return null;

      ByteBuffer pixels = ByteBuffer.allocate(width * height * 4);
      int i = pixelData.position();

      // DIB bitmaps are stored bottom-up by default (positive height in ICO means bottom-up)
      for (int y = height - 1; y >= 0; y--) {
        int line = y;
        for (int x = 0; x < width; x++) {
          int j = line * width * 4 + x * 4;
          if (bitCount == 32) {
            byte b = pixelData.get(i++);
            byte g = pixelData.get(i++);
            byte r = pixelData.get(i++);
            i++; // skip alpha
            pixels.put(j + 2, b);
            pixels.put(j + 1, g);
            pixels.put(j + 0, r);
            pixels.put(j + 3, (byte) 255);
          } else {
            // 24-bit: BGR
            byte b = pixelData.get(i++);
            byte g = pixelData.get(i++);
            byte r = pixelData.get(i++);
            pixels.put(j + 2, b);
            pixels.put(j + 1, g);
            pixels.put(j + 0, r);
            pixels.put(j + 3, (byte) 255);
          }
        }
        // Skip padding bytes to next DWORD boundary
        i += rowBytes - bytesPerPixel * width;
      }

      Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      bitmap.copyPixelsFromBuffer(pixels);
      return bitmap;
    } catch (Exception e) {
      return null;
    }
  }

  public static boolean create(Bitmap bitmap, File outputFile) {
    int width = bitmap.getWidth();
    int height = bitmap.getHeight();

    int[] pixels = new int[width * height];
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

    int extraBytes = width % 4;
    int imageSize = height * (3 * width + extraBytes);
    int infoHeaderSize = 40;
    int dataOffset = 54;
    int bitCount = 24;
    int planes = 1;
    int compression = 0;
    int hr = 0;
    int vr = 0;
    int colorsUsed = 0;
    int colorsImportant = 0;

    ByteBuffer buffer = ByteBuffer.allocate(dataOffset + imageSize).order(ByteOrder.LITTLE_ENDIAN);

    buffer.putShort((short) 0x4d42); // "BM"
    buffer.putInt(dataOffset + imageSize);
    buffer.putInt(0);
    buffer.putInt(dataOffset);

    buffer.putInt(infoHeaderSize);
    buffer.putInt(width);
    buffer.putInt(height);
    buffer.putShort((short) planes);
    buffer.putShort((short) bitCount);
    buffer.putInt(compression);
    buffer.putInt(imageSize);
    buffer.putInt(hr);
    buffer.putInt(vr);
    buffer.putInt(colorsUsed);
    buffer.putInt(colorsImportant);

    int rowBytes = 3 * width + extraBytes;
    for (int y = height - 1, i = 0, j; y >= 0; y--) {
      for (int x = 0; x < width; x++) {
        j = dataOffset + y * rowBytes + x * 3;
        int pixel = pixels[i++];
        buffer.put(j + 0, (byte) Color.blue(pixel));
        buffer.put(j + 1, (byte) Color.green(pixel));
        buffer.put(j + 2, (byte) Color.red(pixel));
      }

      if (extraBytes > 0) {
        int fillOffset = dataOffset + y * rowBytes + width * 3;
        for (j = fillOffset; j < fillOffset + extraBytes; j++) buffer.put(j, (byte) 255);
      }
    }

    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
      fos.write(buffer.array());
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
