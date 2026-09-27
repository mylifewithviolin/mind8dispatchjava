package mind8dispacher;

public class LocTablePointer {
    private final byte[] data;
    private int index;

    public LocTablePointer(byte[] data, int index) {
        this.data = data;
        this.index = index;
    }
}