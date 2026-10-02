package mind8dispacher;

import java.nio.ByteOrder;
import java.util.Objects;

public class MCodePointer {
    private final byte[] data;
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