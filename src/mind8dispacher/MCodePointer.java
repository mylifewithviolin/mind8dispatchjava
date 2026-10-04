package mind8dispacher;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

public class MCodePointer {
    private byte[] data;
    private int index;

    public MCodePointer(byte[] data, int index) {
        this.data = Objects.requireNonNull(data, "data");
        seek(index);
    }

    public int readUnsignedWord(ByteOrder byteOrder) {
        Objects.requireNonNull(byteOrder, "byteOrder");
        requireRange(index, Short.BYTES);
        int first = Byte.toUnsignedInt(data[index]);
        int second = Byte.toUnsignedInt(data[index + 1]);
        index += Short.BYTES;
        return byteOrder == ByteOrder.BIG_ENDIAN
                ? (first << 8) | second
                : (second << 8) | first;
    }

    public int peekUnsignedWord(ByteOrder byteOrder) {
        Objects.requireNonNull(byteOrder, "byteOrder");
        requireRange(index, Short.BYTES);
        int first = Byte.toUnsignedInt(data[index]);
        int second = Byte.toUnsignedInt(data[index + 1]);
        return byteOrder == ByteOrder.BIG_ENDIAN
                ? (first << 8) | second
                : (second << 8) | first;
    }

    public short peekSignedWord(ByteOrder byteOrder) {
        return (short) peekUnsignedWord(byteOrder);
    }

    public long readUnsignedInt(ByteOrder byteOrder) {
        Objects.requireNonNull(byteOrder, "byteOrder");
        requireRange(index, Integer.BYTES);
        long value = 0;
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            for (int offset = 0; offset < Integer.BYTES; offset++) {
                value = (value << Byte.SIZE) | Byte.toUnsignedLong(data[index + offset]);
            }
        } else {
            for (int offset = Integer.BYTES - 1; offset >= 0; offset--) {
                value = (value << Byte.SIZE) | Byte.toUnsignedLong(data[index + offset]);
            }
        }
        index += Integer.BYTES;
        return value;
    }

    public byte[] readBytes(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Byte count cannot be negative: " + count);
        }
        requireRange(index, count);
        byte[] result = Arrays.copyOfRange(data, index, index + count);
        index += count;
        return result;
    }

    public byte[] bytesAt(int offset, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Byte count cannot be negative: " + count);
        }
        requireRange(offset, count);
        return Arrays.copyOfRange(data, offset, offset + count);
    }

    public void appendBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        int previousLength = data.length;
        data = Arrays.copyOf(data, Math.addExact(previousLength, bytes.length));
        System.arraycopy(bytes, 0, data, previousLength, bytes.length);
    }

    public void seek(int byteOffset) {
        if (byteOffset < 0 || byteOffset > data.length || (byteOffset & 1) != 0) {
            throw new IndexOutOfBoundsException("Invalid M-code byte offset: " + byteOffset);
        }
        index = byteOffset;
    }

    public int position() {
        return index;
    }

    public int length() {
        return data.length;
    }

    private void requireRange(int offset, int length) {
        if (offset < 0 || length > data.length - offset) {
            throw new IndexOutOfBoundsException("M-code read exceeds the available data at byte " + offset);
        }
    }
}