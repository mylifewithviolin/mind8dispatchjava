package mind8dispacher;

import java.nio.ByteOrder;
import java.util.Objects;

public class LocTablePointer {
    private final byte[] data;
    private int index;

    public LocTablePointer(byte[] data, int index) {
        this.data = Objects.requireNonNull(data, "data");
        seek(index);
    }

    public long readUnsignedInt(ByteOrder byteOrder) {
        Objects.requireNonNull(byteOrder, "byteOrder");
        if ((index & (Integer.BYTES - 1)) != 0) {
            throw new IllegalStateException("LOC byte offset is not 32-bit aligned: " + index);
        }
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

    public void seek(int byteOffset) {
        if (byteOffset < 0 || byteOffset > data.length
                || (byteOffset & (Integer.BYTES - 1)) != 0) {
            throw new IndexOutOfBoundsException("Invalid LOC byte offset: " + byteOffset);
        }
        index = byteOffset;
    }

    public int position() {
        return index;
    }

    private void requireRange(int offset, int length) {
        if (offset < 0 || length > data.length - offset) {
            throw new IndexOutOfBoundsException("LOC read exceeds the available data at byte " + offset);
        }
    }
}