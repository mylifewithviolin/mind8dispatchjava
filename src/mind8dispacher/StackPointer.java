package mind8dispacher;

public class StackPointer {
    private final byte[] data;
    private int index;

    public StackPointer(byte[] data, int index) {
        this.data = data;
        this.index = index;
    }
}