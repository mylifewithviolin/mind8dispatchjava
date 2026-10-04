package mind8dispacher;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

public final class McoLoader {
    public static final int FOOTER_SIZE = 32;
    public static final int MAJOR_VERSION = 8;
    public static final int RUNTIME_NUMBER = 10;
    public static final int SERIAL_NUMBER = 42;
    private static final int BIG_ENDIAN_MCODE_BIT = 0x20;

    private McoLoader() {
    }

    public static Path resolvePath(String[] args) throws IOException {
        Objects.requireNonNull(args, "args");
        if (args.length != 1 || args[0] == null || args[0].isBlank()) {
            throw new IOException("Specify exactly one MCO file path");
        }

        Path suppliedPath = Path.of(args[0]);
        Path filename = suppliedPath.getFileName();
        if (filename == null) {
            throw new IOException("MCO path does not contain a file name: " + suppliedPath);
        }
        if (!filename.toString().toLowerCase(Locale.ROOT).endsWith(".mco")) {
            suppliedPath = suppliedPath.resolveSibling(filename + ".mco");
        }

        Path resolvedPath = suppliedPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(resolvedPath)) {
            throw new IOException("MCO file does not exist or is not a regular file: " + resolvedPath);
        }
        return resolvedPath;
    }

    public static RawMco readRaw(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path resolvedPath = path.toAbsolutePath().normalize();
        long expectedSize = Files.size(resolvedPath);
        if (expectedSize < FOOTER_SIZE) {
            throw new IOException("MCO file is shorter than its information table: " + resolvedPath);
        }
        if (expectedSize > Integer.MAX_VALUE - 8L) {
            throw new IOException("MCO is too large to read into a Java byte array: " + resolvedPath);
        }

        byte[] bytes = Files.readAllBytes(resolvedPath);
        if (bytes.length != expectedSize) {
            throw new IOException("MCO size changed while it was being read: " + resolvedPath);
        }
        return new RawMco(resolvedPath, bytes);
    }

    public static LoadedMco load(Path path) throws IOException {
        RawMco raw = readRaw(path);
        byte[] file = raw.bytes();
        int footerOffset = file.length - FOOTER_SIZE;
        boolean fileBigEndian = (file[footerOffset + 2] & BIG_ENDIAN_MCODE_BIT) != 0;
        ByteOrder fileOrder = fileBigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        McoInfo info = decodeInfo(file, footerOffset, fileOrder);
        validateInfo(info, raw.path());

        long payloadSize = (long) file.length - FOOTER_SIZE;
        long locEnd = (long) info.mcodeSize() + info.locTableSize();
        if (locEnd > payloadSize) {
            throw new IOException("M-code and LOC table sizes exceed the file payload: " + raw.path());
        }

        int mcodeSize = (int) info.mcodeSize();
        int locSize = (int) info.locTableSize();
        byte[] mcode = new byte[mcodeSize];
        byte[] locTable = new byte[locSize];
        System.arraycopy(file, 0, mcode, 0, mcodeSize);
        System.arraycopy(file, mcodeSize, locTable, 0, locSize);

        boolean needsByteSwap = fileOrder != ByteOrder.nativeOrder();
        if (needsByteSwap) {
            if ((mcodeSize & 7) != 0) {
                throw new IOException("M-code size is not aligned for its endian information table: "
                        + raw.path());
            }
            int ediSize = mcodeSize / 8;
            if ((long) locEnd + ediSize > payloadSize) {
                throw new IOException("M-code endian information table is truncated: " + raw.path());
            }
            byte[] endianInfo = new byte[ediSize];
            System.arraycopy(file, (int) locEnd, endianInfo, 0, ediSize);
            swapMcode(mcode, endianInfo, raw.path());
            swapLocTable(locTable);
        }
        return new LoadedMco(raw.path(), info, mcode, locTable, fileOrder, needsByteSwap);
    }

    private static McoInfo decodeInfo(byte[] file, int offset, ByteOrder order) {
        return new McoInfo(
                file[offset] & 0xff,
                file[offset + 1] & 0xff,
                file[offset + 2] & 0xff,
                file[offset + 3] & 0xff,
                readUnsignedShort(file, offset + 4, order),
                readUnsignedShort(file, offset + 6, order),
                readUnsignedInt(file, offset + 8, order),
                readUnsignedInt(file, offset + 12, order),
                readUnsignedInt(file, offset + 16, order),
                file[offset + 20] & 0xff,
                file[offset + 21] & 0xff,
                readUnsignedShort(file, offset + 22, order),
                readUnsignedInt(file, offset + 24, order),
                readUnsignedInt(file, offset + 28, order));
    }

    private static void validateInfo(McoInfo info, Path path) throws IOException {
        if (info.mark1() != 'M' || info.mark2() != 'C') {
            throw new IOException("Illegal M-code mark: " + path);
        }
        if ((info.versionFlags() >>> 4) != MAJOR_VERSION) {
            throw new IOException("M-code version does not match this runtime: " + path);
        }
        if (info.runtimeNumber() != RUNTIME_NUMBER) {
            throw new IOException("M-code runtime number does not match this runtime: " + path);
        }
        if (info.serialNumber() != SERIAL_NUMBER) {
            throw new IOException("M-code serial number does not match this runtime: " + path);
        }
        if (info.mainExists() == 0) {
            throw new IOException("MAIN is not defined: " + path);
        }
        if (info.mcodeSize() < 0x72 || (info.mcodeSize() & 1) != 0) {
            throw new IOException("M-code size is invalid: " + path);
        }
        if (info.locTableSize() == 0 || (info.locTableSize() & 3) != 0) {
            throw new IOException("LOC table size is invalid: " + path);
        }
        if (info.dataSize() > Integer.MAX_VALUE) {
            throw new IOException("Data area size exceeds the supported limit: " + path);
        }
        if (!validStackSize(info.dataStackSize())) {
            throw new IOException("Data stack size is invalid for this runtime: " + path);
        }
        if (!validStackSize(info.returnStackSize())) {
            throw new IOException("Return stack size is invalid for this runtime: " + path);
        }
    }

    private static boolean validStackSize(long size) {
        return size != 0 && size <= 0xffff && (size & 3) == 0;
    }

    private static void swapMcode(byte[] mcode, byte[] endianInfo, Path path) throws IOException {
        int wordIndex = 0;
        int pendingLongOffset = -1;
        for (byte infoByte : endianInfo) {
            for (int wordInGroup = 0; wordInGroup < 4; wordInGroup++, wordIndex++) {
                int code = (Byte.toUnsignedInt(infoByte) >>> (6 - wordInGroup * 2)) & 3;
                int byteOffset = wordIndex * Short.BYTES;
                if (pendingLongOffset >= 0) {
                    reverse(mcode, pendingLongOffset, Integer.BYTES);
                    pendingLongOffset = -1;
                } else if (code == 1) {
                    reverse(mcode, byteOffset, Short.BYTES);
                } else if (code == 2) {
                    pendingLongOffset = byteOffset;
                }
            }
        }
        if (pendingLongOffset >= 0) {
            throw new IOException("M-code endian information ends in the middle of a 32-bit value: " + path);
        }
    }

    private static void swapLocTable(byte[] locTable) {
        for (int offset = 0; offset < locTable.length; offset += Integer.BYTES) {
            reverse(locTable, offset, Integer.BYTES);
        }
    }

    private static void reverse(byte[] bytes, int offset, int length) {
        for (int left = offset, right = offset + length - 1; left < right; left++, right--) {
            byte value = bytes[left];
            bytes[left] = bytes[right];
            bytes[right] = value;
        }
    }

    private static int readUnsignedShort(byte[] data, int offset, ByteOrder order) {
        int first = Byte.toUnsignedInt(data[offset]);
        int second = Byte.toUnsignedInt(data[offset + 1]);
        return order == ByteOrder.BIG_ENDIAN ? (first << 8) | second : (second << 8) | first;
    }

    private static long readUnsignedInt(byte[] data, int offset, ByteOrder order) {
        long value = 0;
        if (order == ByteOrder.BIG_ENDIAN) {
            for (int index = 0; index < Integer.BYTES; index++) {
                value = (value << Byte.SIZE) | Byte.toUnsignedLong(data[offset + index]);
            }
        } else {
            for (int index = Integer.BYTES - 1; index >= 0; index--) {
                value = (value << Byte.SIZE) | Byte.toUnsignedLong(data[offset + index]);
            }
        }
        return value;
    }

    public record McoInfo(int mark1, int mark2, int endianFlags, int versionFlags,
            int mainExists, int limitDate, long mcodeSize, long locTableSize, long dataSize,
            int runtimeNumber, int dummy, int serialNumber, long dataStackSize, long returnStackSize) {
    }

    public static final class LoadedMco {
        private final Path path;
        private final McoInfo info;
        private final byte[] mcode;
        private final byte[] locTable;
        private final ByteOrder fileByteOrder;
        private final boolean byteSwapped;

        private LoadedMco(Path path, McoInfo info, byte[] mcode, byte[] locTable,
                ByteOrder fileByteOrder, boolean byteSwapped) {
            this.path = path;
            this.info = info;
            this.mcode = mcode.clone();
            this.locTable = locTable.clone();
            this.fileByteOrder = fileByteOrder;
            this.byteSwapped = byteSwapped;
        }

        public Path path() {
            return path;
        }

        public McoInfo info() {
            return info;
        }

        public byte[] mcode() {
            return mcode.clone();
        }

        public byte[] locTable() {
            return locTable.clone();
        }

        public ByteOrder fileByteOrder() {
            return fileByteOrder;
        }

        public boolean byteSwapped() {
            return byteSwapped;
        }
    }

    public static final class RawMco {
        private final Path path;
        private final byte[] bytes;

        private RawMco(Path path, byte[] bytes) {
            this.path = path;
            this.bytes = bytes.clone();
        }

        public Path path() {
            return path;
        }

        public byte[] bytes() {
            return bytes.clone();
        }

        public int size() {
            return bytes.length;
        }
    }
}
