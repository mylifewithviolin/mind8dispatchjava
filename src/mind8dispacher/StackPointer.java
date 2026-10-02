package mind8dispacher;

import java.nio.ByteOrder;
import java.util.Objects;

public class StackPointer {
    private final byte[] data;
    private final int baseIndex;
    private int index;

    public StackPointer(byte[] data, int index) {
        this.data = Objects.requireNonNull(data, "data");
        if (index < 0 || index > data.length) {
            throw new IndexOutOfBoundsException("Invalid stack byte offset: " + index);
        }
        this.baseIndex = index;
        this.index = index;
    }

    public void pushByte(int value) {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException("Byte value is outside 0..255: " + value);
        }
        requirePush(1);
        data[index++] = (byte) value;
    }

    public int popUnsignedByte() {
        requirePop(1);
        return Byte.toUnsignedInt(data[--index]);
    }

    public void pushUnsignedWord(int value, ByteOrder byteOrder) {
        Objects.requireNonNull(byteOrder, "byteOrder");
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException("Word value is outside 0..65535: " + value);
        }
        requirePush(Short.BYTES);
        int first = byteOrder == ByteOrder.BIG_ENDIAN ? value >>> 8 : value;
        int second = byteOrder == ByteOrder.BIG_ENDIAN ? value : value >>> 8;
        data[index++] = (byte) first;
        data[index++] = (byte) second;
    }

    public int popUnsignedWord(ByteOrder byteOrder) {
        Objects.requireNonNull(byteOrder, "byteOrder");
        requirePop(Short.BYTES);
        int first = Byte.toUnsignedInt(data[index - 2]);
        int second = Byte.toUnsignedInt(data[index - 1]);
        index -= Short.BYTES;
        return byteOrder == ByteOrder.BIG_ENDIAN
                ? (first << 8) | second
                : (second << 8) | first;
    }

    public void reset() {
        index = baseIndex;
    }

    public int position() {
        return index;
    }

    private void requirePush(int length) {
        if (length > data.length - index) {
            throw new IndexOutOfBoundsException("Stack capacity exceeded at byte " + index);
        }
    }

    private void requirePop(int length) {
        if (index - baseIndex < length) {
            throw new IndexOutOfBoundsException("Stack underflow at byte " + index);
        }
    }
}