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

    public void pushInt32(int value, ByteOrder byteOrder) {
        Objects.requireNonNull(byteOrder, "byteOrder");
        requirePush(Integer.BYTES);
        for (int offset = 0; offset < Integer.BYTES; offset++) {
            int shift = (byteOrder == ByteOrder.BIG_ENDIAN ? Integer.BYTES - 1 - offset : offset)
                    * Byte.SIZE;
            data[index++] = (byte) (value >>> shift);
        }
    }

    public int popInt32(ByteOrder byteOrder) {
        Objects.requireNonNull(byteOrder, "byteOrder");
        requirePop(Integer.BYTES);
        int value = 0;
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            for (int offset = index - Integer.BYTES; offset < index; offset++) {
                value = (value << Byte.SIZE) | Byte.toUnsignedInt(data[offset]);
            }
        } else {
            for (int offset = index - 1; offset >= index - Integer.BYTES; offset--) {
                value = (value << Byte.SIZE) | Byte.toUnsignedInt(data[offset]);
            }
        }
        index -= Integer.BYTES;
        return value;
    }

    public int peekInt32(int slotOffset, ByteOrder byteOrder) {
        Objects.requireNonNull(byteOrder, "byteOrder");
        if (slotOffset < 0) {
            throw new IllegalArgumentException("Stack slot offset cannot be negative: " + slotOffset);
        }
        int offset = Math.subtractExact(index, Math.multiplyExact(slotOffset + 1, Integer.BYTES));
        if (offset < baseIndex) {
            throw new IndexOutOfBoundsException("Stack read underflow at slot " + slotOffset);
        }
        int value = 0;
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            for (int cursor = offset; cursor < offset + Integer.BYTES; cursor++) {
                value = (value << Byte.SIZE) | Byte.toUnsignedInt(data[cursor]);
            }
        } else {
            for (int cursor = offset + Integer.BYTES - 1; cursor >= offset; cursor--) {
                value = (value << Byte.SIZE) | Byte.toUnsignedInt(data[cursor]);
            }
        }
        return value;
    }

    public void setInt32(int slotOffset, int value, ByteOrder byteOrder) {
        Objects.requireNonNull(byteOrder, "byteOrder");
        if (slotOffset < 0) {
            throw new IllegalArgumentException("Stack slot offset cannot be negative: " + slotOffset);
        }
        int offset = Math.subtractExact(index, Math.multiplyExact(slotOffset + 1, Integer.BYTES));
        if (offset < baseIndex) {
            throw new IndexOutOfBoundsException("Stack write underflow at slot " + slotOffset);
        }
        for (int byteOffset = 0; byteOffset < Integer.BYTES; byteOffset++) {
            int shift = (byteOrder == ByteOrder.BIG_ENDIAN
                    ? Integer.BYTES - 1 - byteOffset : byteOffset) * Byte.SIZE;
            data[offset + byteOffset] = (byte) (value >>> shift);
        }
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

    public void moveSlots(int slots) {
        int newIndex = Math.subtractExact(index, Math.multiplyExact(slots, Integer.BYTES));
        if (newIndex < baseIndex || newIndex > data.length) {
            throw new IndexOutOfBoundsException("Stack movement exceeds the available region");
        }
        index = newIndex;
    }

    public void clearInt32(int slotOffset, ByteOrder byteOrder) {
        Objects.requireNonNull(byteOrder, "byteOrder");
        int offset = Math.subtractExact(index, Math.multiplyExact(slotOffset + 1, Integer.BYTES));
        if (slotOffset < 0 || offset < baseIndex) {
            throw new IndexOutOfBoundsException("Stack write underflow at slot " + slotOffset);
        }
        java.util.Arrays.fill(data, offset, offset + Integer.BYTES, (byte) 0);
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