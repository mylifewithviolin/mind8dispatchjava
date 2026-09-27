package mind8dispacher;

public class AccessPointer {
    private final byte[] data;
    private int index;

    public AccessPointer(byte[] data, int index) {
        this.data = data;
        this.index = index;
    }
}