package mind8dispacher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class RegressionTests {
    private static final TestCase[] TESTS = {
            new TestCase("function table registration and comparison", RegressionTests::testFunctionTable),
            new TestCase("MCO path resolution and raw-byte isolation", RegressionTests::testMcoPathAndIsolation),
            new TestCase("short MCO is rejected", RegressionTests::testShortMco),
            new TestCase("M-code reads both byte orders and checks bounds", RegressionTests::testMcodePointer),
            new TestCase("LOC reads both byte orders and checks alignment", RegressionTests::testLocTablePointer),
            new TestCase("data and stack pointers enforce bounds and reset", RegressionTests::testMemoryAndStackPointers),
            new TestCase("dispatcher refuses unverified MCO before execution", RegressionTests::testDispatcherFailsClosed),
            new TestCase("dispatcher rejects missing input", RegressionTests::testMissingInput)
    };

    private RegressionTests() {
    }

    public static void main(String[] args) {
        int failures = 0;
        for (TestCase test : TESTS) {
            try {
                test.action().run();
                System.out.println("PASS " + test.name());
            } catch (Exception | AssertionError failure) {
                failures++;
                System.err.println("FAIL " + test.name() + ": " + failure.getMessage());
            }
        }
        System.out.println((TESTS.length - failures) + "/" + TESTS.length + " tests passed");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void testFunctionTable() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CFunctionTable table = new CFunctionTable();
        table.register(CFunctionTable.FIRST_NUMBER, "BASE", calls::incrementAndGet);
        table.registerAlias(CFunctionTable.FIRST_NUMBER + 1, "ALIAS",
                CFunctionTable.FIRST_NUMBER);
        table.invoke(CFunctionTable.FIRST_NUMBER);
        table.invoke(CFunctionTable.FIRST_NUMBER + 1);
        assertEquals(2, calls.get(), "canonical and alias handlers");
        assertEquals(2, table.registeredCount(), "registered count");

        List<CFunctionTable.Difference> differences = table.compare(List.of(
                new CFunctionTable.CEntry(CFunctionTable.FIRST_NUMBER, "BASE"),
                new CFunctionTable.CEntry(CFunctionTable.FIRST_NUMBER + 1, "ALIAS"),
                new CFunctionTable.CEntry(CFunctionTable.FIRST_NUMBER + 2, "C_ONLY")));
        assertEquals(CFunctionTable.Comparison.MATCH, differences.get(0).comparison(), "base comparison");
        assertEquals(CFunctionTable.Comparison.INTENTIONAL_ALIAS,
                differences.get(1).comparison(), "alias comparison");
        assertEquals(CFunctionTable.Comparison.C_ONLY, differences.get(2).comparison(), "C-only comparison");
        expectThrows(IllegalArgumentException.class,
                () -> table.register(CFunctionTable.FIRST_NUMBER, "DUPLICATE", () -> { }));
        expectThrows(IllegalArgumentException.class,
                () -> table.invoke(CFunctionTable.LAST_NUMBER + 1));
        expectThrows(IllegalArgumentException.class, () -> table.compare(List.of(
                new CFunctionTable.CEntry(CFunctionTable.FIRST_NUMBER, "ONE"),
                new CFunctionTable.CEntry(CFunctionTable.FIRST_NUMBER, "TWO"))));
    }

    private static void testMcoPathAndIsolation() throws Exception {
        byte[] fixtureBytes = new byte[McoLoader.FOOTER_SIZE];
        fixtureBytes[0] = 0x4d;
        fixtureBytes[1] = 0x43;
        try (TempMco fixture = new TempMco(fixtureBytes)) {
            Path extensionless = fixture.path().resolveSibling("fixture");
            Path resolved = McoLoader.resolvePath(new String[] { extensionless.toString() });
            assertEquals(fixture.path().toAbsolutePath().normalize(), resolved, "resolved MCO path");
            try (TempMco uppercaseFixture = new TempMco(fixtureBytes, "fixture.MCO")) {
                assertEquals(uppercaseFixture.path().toAbsolutePath().normalize(),
                        McoLoader.resolvePath(new String[] { uppercaseFixture.path().toString() }),
                        "uppercase MCO extension");
            }

            McoLoader.RawMco raw = McoLoader.readRaw(resolved);
            assertEquals(fixtureBytes.length, raw.size(), "raw MCO size");
            byte[] returnedBytes = raw.bytes();
            returnedBytes[0] = 0;
            assertEquals((byte) 0x4d, raw.bytes()[0], "defensive copy");
        }
    }

    private static void testShortMco() throws Exception {
        try (TempMco fixture = new TempMco(new byte[McoLoader.FOOTER_SIZE - 1])) {
            expectThrows(IOException.class, () -> McoLoader.readRaw(fixture.path()));
        }
    }

    private static void testMcodePointer() throws Exception {
        MCodePointer bigEndian = new MCodePointer(new byte[] { 0x12, 0x34, 0x56, 0x78 }, 0);
        assertEquals(0x1234, bigEndian.readUnsignedWord(ByteOrder.BIG_ENDIAN), "big-endian word");
        assertEquals(2, bigEndian.position(), "big-endian next position");
        assertEquals(0x7856, bigEndian.readUnsignedWord(ByteOrder.LITTLE_ENDIAN), "little-endian word");
        assertEquals(4, bigEndian.position(), "little-endian next position");
        expectThrows(IndexOutOfBoundsException.class,
                () -> new MCodePointer(new byte[] { 0x01 }, 0).readUnsignedWord(ByteOrder.BIG_ENDIAN));
    }

    private static void testLocTablePointer() throws Exception {
        byte[] values = {
                0x12, 0x34, 0x56, 0x78,
                0x78, 0x56, 0x34, 0x12
        };
        LocTablePointer bigEndian = new LocTablePointer(values, 0);
        assertEquals(0x12345678L, bigEndian.readUnsignedInt(ByteOrder.BIG_ENDIAN), "big-endian LOC value");
        LocTablePointer littleEndian = new LocTablePointer(values, 4);
        assertEquals(0x12345678L, littleEndian.readUnsignedInt(ByteOrder.LITTLE_ENDIAN),
                "little-endian LOC value");
        expectThrows(IndexOutOfBoundsException.class, () -> new LocTablePointer(values, 2));
        expectThrows(IndexOutOfBoundsException.class, () -> new LocTablePointer(values, 8)
                .readUnsignedInt(ByteOrder.LITTLE_ENDIAN));
    }

    private static void testMemoryAndStackPointers() throws Exception {
        AccessPointer access = new AccessPointer(new byte[1], 0);
        access.writeByte(0xFE);
        access.seek(0);
        assertEquals(0xFE, access.readUnsignedByte(), "unsigned data byte");
        expectThrows(IndexOutOfBoundsException.class, access::readUnsignedByte);

        StackPointer stack = new StackPointer(new byte[4], 1);
        stack.pushUnsignedWord(0x1234, ByteOrder.BIG_ENDIAN);
        assertEquals(0x1234, stack.popUnsignedWord(ByteOrder.BIG_ENDIAN), "stack word");
        byte[] littleEndianBytes = new byte[Short.BYTES];
        StackPointer littleEndianStack = new StackPointer(littleEndianBytes, 0);
        littleEndianStack.pushUnsignedWord(0x1234, ByteOrder.LITTLE_ENDIAN);
        assertEquals((byte) 0x34, littleEndianBytes[0], "little-endian stack low byte");
        assertEquals((byte) 0x12, littleEndianBytes[1], "little-endian stack high byte");
        assertEquals(0x1234, littleEndianStack.popUnsignedWord(ByteOrder.LITTLE_ENDIAN),
                "little-endian stack word");
        stack.pushByte(0xFE);
        stack.reset();
        assertEquals(1, stack.position(), "stack reset position");
        expectThrows(IndexOutOfBoundsException.class, stack::popUnsignedByte);
        stack.pushUnsignedWord(0x1234, ByteOrder.BIG_ENDIAN);
        stack.pushByte(0x56);
        assertEquals(4, stack.position(), "stack position at capacity");
        expectThrows(IndexOutOfBoundsException.class, () -> stack.pushByte(0x78));
    }

    private static void testDispatcherFailsClosed() throws Exception {
        try (TempMco fixture = new TempMco(new byte[McoLoader.FOOTER_SIZE])) {
            CapturedRun result = runDispatcher(fixture.path().toString());
            assertEquals(1, result.exitCode(), "unverified MCO exit code");
            assertEquals("", result.stdout(), "unverified MCO stdout");
            assertContains(result.stderr(), "have not been verified", "diagnostic");
        }
    }

    private static void testMissingInput() throws Exception {
        CapturedRun result = runDispatcher();
        assertEquals(1, result.exitCode(), "missing input exit code");
        assertContains(result.stderr(), "Specify exactly one MCO file path", "missing-input diagnostic");
    }

    private static CapturedRun runDispatcher(String... args) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream capturedOut = new PrintStream(output, true, StandardCharsets.UTF_8);
                PrintStream capturedErr = new PrintStream(error, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            System.setErr(capturedErr);
            exitCode = Dispatcher.run(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new CapturedRun(exitCode, output.toString(StandardCharsets.UTF_8),
                error.toString(StandardCharsets.UTF_8));
    }

    private static <T extends Throwable> void expectThrows(Class<T> expected, TestAction action) throws Exception {
        try {
            action.run();
        } catch (Exception actual) {
            if (expected.isInstance(actual)) {
                return;
            }
            throw new AssertionError("expected " + expected.getSimpleName() + " but got " + actual, actual);
        }
        throw new AssertionError("expected " + expected.getSimpleName() + " but nothing was thrown");
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected " + expected + " but got " + actual);
        }
    }

    private static void assertContains(String actual, String expectedPart, String description) {
        if (!actual.contains(expectedPart)) {
            throw new AssertionError(description + " did not contain [" + expectedPart + "]: " + actual);
        }
    }

    private record TestCase(String name, TestAction action) {
    }

    private record CapturedRun(int exitCode, String stdout, String stderr) {
    }

    @FunctionalInterface
    private interface TestAction {
        void run() throws Exception;
    }

    private static final class TempMco implements AutoCloseable {
        private final Path directory;
        private final Path path;

        private TempMco(byte[] bytes) throws IOException {
            this(bytes, "fixture.mco");
        }

        private TempMco(byte[] bytes, String filename) throws IOException {
            directory = Files.createTempDirectory("mind8dispatcher-test-");
            path = directory.resolve(filename);
            Files.write(path, bytes);
        }

        private Path path() {
            return path;
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        }
    }
}
