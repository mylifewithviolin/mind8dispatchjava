package mind8dispacher;

public class MCodePointer {
    private final byte[] data;
    private int index;

    public MCodePointer(byte[] data, int index) {
        this.data = data;
        this.index = index;
    }
}