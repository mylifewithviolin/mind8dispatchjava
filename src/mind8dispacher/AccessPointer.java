package mind8dispacher;

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

    public void seek(int offset) {
        if (offset < 0 || offset > data.length) {
            throw new IndexOutOfBoundsException("Invalid data byte offset: " + offset);
        }
        index = offset;
    }

    public int position() {
        return index;
    }

    private void requireRange(int offset, int length) {
        if (offset < 0 || length > data.length - offset) {
            throw new IndexOutOfBoundsException("Data access exceeds the available region at byte " + offset);
        }
    }
}