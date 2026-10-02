package mind8dispacher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

public final class McoLoader {
    public static final int FOOTER_SIZE = 32;

    private McoLoader() {
    }

    public static Path resolvePath(String[] args) throws IOException {
        Objects.requireNonNull(args, "args");
        if (args.length != 1 || args[0] == null || args[0].isBlank()) {
            throw new IOException("Specify exactly one MCO file path; no verified default name is available");
        }

        Path suppliedPath = Path.of(args[0]);
        String filename = suppliedPath.getFileName().toString();
        int extensionStart = filename.lastIndexOf('.');
        boolean hasMcoExtension = filename.toLowerCase(Locale.ROOT).endsWith(".mco");
        if (!hasMcoExtension && extensionStart <= 0) {
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
            throw new IOException("MCO is shorter than its " + FOOTER_SIZE + "-byte information footer: "
                    + resolvedPath);
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