package mind8dispacher;

import java.nio.ByteOrder;
import java.util.Objects;

public class AccessPointer {
    private final byte[] data;
    private int index;

    public AccessPointer(byte[] data, int index) {
        this.data = Objects.requireNonNull(data, "data");
        seek(index);
    }

    public int readUnsignedByte() {
        requireRange(index, 1);
        return Byte.toUnsignedInt(data[index++]);
    }

    public void writeByte(int value) {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException("Byte value is outside 0..255: " + value);
        }
        requireRange(index, 1);
        data[index++] = (byte) value;
    }

    public int readUnsignedShort(ByteOrder byteOrder) {
        Objects.requireNonNull(byteOrder, "byteOrder");
        requireRange(index, Short.BYTES);
        int first = Byte.toUnsignedInt(data[index]);
        int second = Byte.toUnsignedInt(data[index + 1]);
        return byteOrder == ByteOrder.BIG_ENDIAN
                ? (first << 8) | second
                : (second << 8) | first;
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
        return value;
    }

    public long readUnsignedIntAt(int offset, ByteOrder byteOrder) {
        if (offset < 0 || offset > data.length - Integer.BYTES) {
            throw new IndexOutOfBoundsException("Invalid data byte offset: " + offset);
        }
        int savedIndex = index;
        index = offset;
        try {
            return readUnsignedInt(byteOrder);
        } finally {
            index = savedIndex;
        }
    }

    public void writeUnsignedShort(int value, ByteOrder byteOrder) {
        Objects.requireNonNull(byteOrder, "byteOrder");
        if (value < 0 || value > 0xffff) {
            throw new IllegalArgumentException("Short value is outside 0..65535: " + value);
        }
        requireRange(index, Short.BYTES);
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            data[index] = (byte) (value >>> 8);
            data[index + 1] = (byte) value;
        } else {
            data[index] = (byte) value;
            data[index + 1] = (byte) (value >>> 8);
        }
    }

    public void writeUnsignedInt(long value, ByteOrder byteOrder) {
        Objects.requireNonNull(byteOrder, "byteOrder");
        if (value < 0 || value > 0xffff_ffffL) {
            throw new IllegalArgumentException("Int value is outside 0..4294967295: " + value);
        }
        requireRange(index, Integer.BYTES);
        for (int offset = 0; offset < Integer.BYTES; offset++) {
            int shift = (byteOrder == ByteOrder.BIG_ENDIAN ? Integer.BYTES - 1 - offset : offset)
                    * Byte.SIZE;
            data[index + offset] = (byte) (value >>> shift);
        }
    }

    public void writeUnsignedLongPair(long first, long second, ByteOrder byteOrder) {
        writeUnsignedInt(first, byteOrder);
        int originalIndex = index;
        index = Math.addExact(index, Integer.BYTES);
        try {
            writeUnsignedInt(second, byteOrder);
        } finally {
            index = originalIndex;
        }
    }

    public void fill(int value, int length) {
        if (value < 0 || value > 0xff || length < 0) {
            throw new IllegalArgumentException("Invalid fill value or length");
        }
        requireRange(index, length);
        java.util.Arrays.fill(data, index, index + length, (byte) value);
    }

    public void seek(int offset) {
        if (offset < 0 || offset > data.length) {
            throw new IndexOutOfBoundsException("Invalid data byte offset: " + offset);
        }
        index = offset;
    }

    public int position() {
        return index;
    }

    public int length() {
        return data.length;
    }

    private void requireRange(int offset, int length) {
        if (offset < 0 || length > data.length - offset) {
            throw new IndexOutOfBoundsException("Data access exceeds the available region at byte " + offset);
        }
    }
}