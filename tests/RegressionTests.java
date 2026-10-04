package mind8dispacher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RegressionTests {
    private static final Pattern C_FUNCTION_LINE =
            Pattern.compile("^/\\*([0-9A-Fa-f]{4})\\*/\\s*([^,]+),$");
    private static final TestCase[] TESTS = {
            new TestCase("function table registration and comparison", RegressionTests::testFunctionTable),
            new TestCase("Java registrations match the complete C function table",
                    RegressionTests::testFunctionTableAgainstKernelFixture),
            new TestCase("MCO path resolution and raw-byte isolation", RegressionTests::testMcoPathAndIsolation),
            new TestCase("short MCO is rejected", RegressionTests::testShortMco),
            new TestCase("MCO loader decodes complete files in both byte orders",
                    RegressionTests::testMcoLoadBothByteOrders),
            new TestCase("MCO loader rejects invalid metadata and truncated EDI",
                    RegressionTests::testMcoRejectsInvalidMetadata),
            new TestCase("M-code reads both byte orders and checks bounds", RegressionTests::testMcodePointer),
            new TestCase("LOC reads both byte orders and checks alignment", RegressionTests::testLocTablePointer),
            new TestCase("data and stack pointers enforce bounds and reset", RegressionTests::testMemoryAndStackPointers),
            new TestCase("dispatcher rejects invalid MCO metadata before execution", RegressionTests::testDispatcherRejectsInvalidMco),
            new TestCase("dispatcher handles Mind-word dispatch, branches, restart, and exits",
                    RegressionTests::testDispatcherControlFlow),
            new TestCase("Windows text mode translates newlines without duplicating CR",
                    RegressionTests::testTextModeNewlineConversion),
            new TestCase("hello MCO produces the expected output", RegressionTests::testHelloMco),
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

    private static void testFunctionTableAgainstKernelFixture() throws Exception {
        List<CFunctionTable.CEntry> cEntries = readCFunctionTable();
        assertEquals(743, cEntries.size(), "C function table rows");
        Set<Integer> uniqueNumbers = new HashSet<>();
        for (CFunctionTable.CEntry entry : cEntries) {
            if (!uniqueNumbers.add(entry.number())) {
                throw new AssertionError(String.format("duplicate C function number 0x%04X", entry.number()));
            }
        }

        List<CFunctionTable.Difference> differences = new Dispatcher().compareCFunctions(cEntries);
        long matches = differences.stream()
                .filter(difference -> difference.comparison() == CFunctionTable.Comparison.MATCH).count();
        long cOnly = differences.stream()
                .filter(difference -> difference.comparison() == CFunctionTable.Comparison.C_ONLY).count();
        long javaOnly = differences.stream()
                .filter(difference -> difference.comparison() == CFunctionTable.Comparison.JAVA_ONLY).count();
        long nameMismatches = differences.stream()
                .filter(difference -> difference.comparison() == CFunctionTable.Comparison.NAME_MISMATCH).count();
        long aliases = differences.stream()
                .filter(difference -> difference.comparison() == CFunctionTable.Comparison.INTENTIONAL_ALIAS).count();

        assertEquals(743, differences.size(), "compared function numbers");
        assertEquals(85L, matches, "matching Java registrations");
        assertEquals(658L, cOnly, "C functions without Java handlers");
        assertEquals(0L, javaOnly, "Java-only registrations");
        assertEquals(0L, nameMismatches, "registration name mismatches");
        assertEquals(0L, aliases, "registered aliases");
        System.out.println("C function table: 743 entries; 85 named registrations; "
                + "658 unimplemented; 0 name mismatches; 0 Java-only registrations");
    }

    private static List<CFunctionTable.CEntry> readCFunctionTable() throws IOException {
        List<CFunctionTable.CEntry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("tests", "fixtures", "c_words.tbl"),
                StandardCharsets.UTF_8)) {
            Matcher matcher = C_FUNCTION_LINE.matcher(line);
            if (matcher.matches()) {
                entries.add(new CFunctionTable.CEntry(
                        Integer.parseInt(matcher.group(1), 16), matcher.group(2).trim()));
            }
        }
        return List.copyOf(entries);
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

    private static void testMcoLoadBothByteOrders() throws Exception {
        byte[] expectedMcode = new byte[0x78];
        writeUnsignedShort(expectedMcode, 0x70, 0x0017, ByteOrder.nativeOrder());
        writeUnsignedInt(expectedMcode, 0x72, 0x12345678L, ByteOrder.nativeOrder());
        byte[] expectedLocTable = new byte[Integer.BYTES];
        writeUnsignedInt(expectedLocTable, 0, 0x70, ByteOrder.nativeOrder());

        for (ByteOrder fileOrder : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            byte[] fixtureBytes = createEndianMco(fileOrder, expectedMcode, expectedLocTable);
            try (TempMco fixture = new TempMco(fixtureBytes)) {
                McoLoader.LoadedMco loaded = McoLoader.load(fixture.path());
                assertEquals(fileOrder, loaded.fileByteOrder(), fileOrder + " footer byte order");
                assertEquals(fileOrder != ByteOrder.nativeOrder(), loaded.byteSwapped(),
                        fileOrder + " endian conversion flag");
                assertArrayEquals(expectedMcode, loaded.mcode(), fileOrder + " M-code conversion");
                assertArrayEquals(expectedLocTable, loaded.locTable(), fileOrder + " LOC conversion");
                assertEquals(0x12345678L, new MCodePointer(loaded.mcode(), 0x72)
                        .readUnsignedInt(ByteOrder.nativeOrder()), fileOrder + " 32-bit EDI conversion");
            }
        }
    }

    private static byte[] createEndianMco(ByteOrder fileOrder, byte[] expectedMcode, byte[] expectedLocTable) {
        boolean needsEdi = fileOrder != ByteOrder.nativeOrder();
        byte[] fileMcode = expectedMcode.clone();
        byte[] fileLocTable = expectedLocTable.clone();
        byte[] endianInfo = needsEdi ? new byte[expectedMcode.length / 8] : new byte[0];
        if (needsEdi) {
            for (int wordOffset = 0; wordOffset < expectedMcode.length; wordOffset += Short.BYTES) {
                int code = wordOffset == 0x72 ? 2 : wordOffset == 0x74 ? 0 : 1;
                if (wordOffset != 0x74) {
                    reverse(fileMcode, wordOffset, code == 2 ? Integer.BYTES : Short.BYTES);
                }
                int wordIndex = wordOffset / Short.BYTES;
                endianInfo[wordIndex / 4] |= (byte) (code << (6 - 2 * (wordIndex % 4)));
            }
            reverse(fileLocTable, 0, Integer.BYTES);
        }

        int payloadLength = fileMcode.length + fileLocTable.length + endianInfo.length;
        byte[] result = new byte[payloadLength + McoLoader.FOOTER_SIZE];
        System.arraycopy(fileMcode, 0, result, 0, fileMcode.length);
        System.arraycopy(fileLocTable, 0, result, fileMcode.length, fileLocTable.length);
        System.arraycopy(endianInfo, 0, result, fileMcode.length + fileLocTable.length, endianInfo.length);
        writeFooter(result, fileOrder, fileMcode.length, fileLocTable.length);
        return result;
    }

    private static void testMcoRejectsInvalidMetadata() throws Exception {
        byte[] valid = createMco(ByteOrder.nativeOrder(), 0x0017);
        int footer = valid.length - McoLoader.FOOTER_SIZE;
        List<McoMutation> invalidCases = List.of(
                new McoMutation("mark", bytes -> bytes[footer] = 'X'),
                new McoMutation("version", bytes -> bytes[footer + 3] = 0x70),
                new McoMutation("MAIN", bytes -> writeUnsignedShort(bytes, footer + 4, 0,
                        ByteOrder.nativeOrder())),
                new McoMutation("runtime", bytes -> bytes[footer + 20] = 9),
                new McoMutation("serial", bytes -> writeUnsignedShort(bytes, footer + 22,
                        McoLoader.SERIAL_NUMBER + 1, ByteOrder.nativeOrder())),
                new McoMutation("M-code size", bytes -> writeUnsignedInt(bytes, footer + 8, 0x71,
                        ByteOrder.nativeOrder())),
                new McoMutation("LOC size", bytes -> writeUnsignedInt(bytes, footer + 12, 0,
                        ByteOrder.nativeOrder())),
                new McoMutation("data size", bytes -> writeUnsignedInt(bytes, footer + 16, 0x80000000L,
                        ByteOrder.nativeOrder())),
                new McoMutation("data stack size", bytes -> writeUnsignedInt(bytes, footer + 24, 0,
                        ByteOrder.nativeOrder())),
                new McoMutation("return stack size", bytes -> writeUnsignedInt(bytes, footer + 28, 0,
                        ByteOrder.nativeOrder())),
                new McoMutation("payload boundary", bytes -> writeUnsignedInt(bytes, footer + 8, 0x80,
                        ByteOrder.nativeOrder())));

        for (McoMutation invalidCase : invalidCases) {
            byte[] mutated = valid.clone();
            invalidCase.mutation().accept(mutated);
            try (TempMco fixture = new TempMco(mutated)) {
                expectThrows(IOException.class, () -> McoLoader.load(fixture.path()));
            }
        }

        ByteOrder oppositeOrder = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                ? ByteOrder.BIG_ENDIAN
                : ByteOrder.LITTLE_ENDIAN;
        byte[] missingEdi = createMco(oppositeOrder, false, 0x0017);
        try (TempMco fixture = new TempMco(missingEdi)) {
            expectThrows(IOException.class, () -> McoLoader.load(fixture.path()));
        }
    }

    private static void testDispatcherControlFlow() throws Exception {
        CapturedRun mindWord = runSyntheticMco(0x72, 0x8000, 0x0017);
        assertEquals(0, mindWord.exitCode(), "Mind-word dispatch exit code");
        assertEquals("", mindWord.stdout(), "Mind-word dispatch stdout");

        CapturedRun restarted = runSyntheticMco(0x70, 0x0016, 0x0015, 0x0017);
        assertEquals(0, restarted.exitCode(), "stack-reset resume exit code");
        assertEquals("", restarted.stderr(), "stack-reset resume stderr");

        CapturedRun trueBranch = runSyntheticMco(0x70,
                0x00a0, 1, 0x00ec, 8,
                0x00a0, 0, 0x0014,
                0x00a0, 5, 0x0014);
        assertEquals(0, trueBranch.exitCode(), "true branch process exit code");
        CapturedRun falseBranch = runSyntheticMco(0x70,
                0x00a0, 0, 0x00ec, 8,
                0x00a0, 0, 0x0014,
                0x00a0, 5, 0x0014);
        assertEquals(5, falseBranch.exitCode(), "false branch process exit code");

        CapturedRun emergencyExit = runSyntheticMco(0x70, 0x0013);
        assertEquals(1, emergencyExit.exitCode(), "emergency exit code");
    }

    private static void testTextModeNewlineConversion() {
        byte[] input = { 'a', '\n', 'b', '\r', '\n' };
        byte[] expected = { 'a', '\r', '\n', 'b', '\r', '\n' };
        assertArrayEquals(expected, Dispatcher.translateTextNewlines(input), "text-mode newline conversion");
        assertArrayEquals(new byte[] { 'a', '\n', 'b', '\r', '\n' }, input,
                "caller-owned input remains unchanged");
    }

    private static CapturedRun runSyntheticMco(int locTarget, int... instructions) throws Exception {
        try (TempMco fixture = new TempMco(createMcoWithLoc(ByteOrder.nativeOrder(), locTarget, instructions))) {
            return runDispatcher(fixture.path().toString());
        }
    }

    private static byte[] createMco(ByteOrder fileOrder, int... instructions) {
        return createMco(fileOrder, true, instructions);
    }

    private static byte[] createMco(ByteOrder fileOrder, boolean includeEdi, int... instructions) {
        return createMcoWithLoc(fileOrder, 0x70, includeEdi, instructions);
    }

    private static byte[] createMcoWithLoc(ByteOrder fileOrder, int locTarget, int... instructions) {
        return createMcoWithLoc(fileOrder, locTarget, true, instructions);
    }

    private static byte[] createMcoWithLoc(ByteOrder fileOrder, int locTarget, boolean includeEdi,
            int... instructions) {
        int requiredSize = 0x70 + instructions.length * Short.BYTES;
        int mcodeSize = Math.max(0x78, (requiredSize + 7) & ~7);
        byte[] mcode = new byte[mcodeSize];
        for (int index = 0; index < instructions.length; index++) {
            writeUnsignedShort(mcode, 0x70 + index * Short.BYTES, instructions[index],
                    ByteOrder.nativeOrder());
        }
        byte[] locTable = new byte[Integer.BYTES];
        writeUnsignedInt(locTable, 0, locTarget, ByteOrder.nativeOrder());
        return encodeMco(fileOrder, mcode, locTable, Set.of(), includeEdi);
    }

    private static byte[] encodeMco(ByteOrder fileOrder, byte[] nativeMcode, byte[] nativeLocTable,
            Set<Integer> longValueOffsets, boolean includeEdi) {
        boolean needsEdi = fileOrder != ByteOrder.nativeOrder();
        byte[] fileMcode = nativeMcode.clone();
        byte[] fileLocTable = nativeLocTable.clone();
        byte[] endianInfo = needsEdi && includeEdi ? new byte[nativeMcode.length / 8] : new byte[0];
        if (needsEdi) {
            for (int wordOffset = 0; wordOffset < nativeMcode.length; wordOffset += Short.BYTES) {
                int code = longValueOffsets.contains(wordOffset) ? 2 : 1;
                boolean continuation = wordOffset > 0 && longValueOffsets.contains(wordOffset - Short.BYTES);
                if (continuation) {
                    code = 0;
                } else {
                    reverse(fileMcode, wordOffset, code == 2 ? Integer.BYTES : Short.BYTES);
                }
                if (includeEdi) {
                    int wordIndex = wordOffset / Short.BYTES;
                    endianInfo[wordIndex / 4] |= (byte) (code << (6 - 2 * (wordIndex % 4)));
                }
            }
            for (int offset = 0; offset < fileLocTable.length; offset += Integer.BYTES) {
                reverse(fileLocTable, offset, Integer.BYTES);
            }
        }

        int payloadLength = fileMcode.length + fileLocTable.length + endianInfo.length;
        byte[] result = new byte[payloadLength + McoLoader.FOOTER_SIZE];
        System.arraycopy(fileMcode, 0, result, 0, fileMcode.length);
        System.arraycopy(fileLocTable, 0, result, fileMcode.length, fileLocTable.length);
        System.arraycopy(endianInfo, 0, result, fileMcode.length + fileLocTable.length, endianInfo.length);
        writeFooter(result, fileOrder, fileMcode.length, fileLocTable.length);
        return result;
    }

    private static void writeFooter(byte[] file, ByteOrder order, int mcodeSize, int locTableSize) {
        int footer = file.length - McoLoader.FOOTER_SIZE;
        file[footer] = 'M';
        file[footer + 1] = 'C';
        file[footer + 2] = (byte) (order == ByteOrder.BIG_ENDIAN ? 0x20 : 0);
        file[footer + 3] = (byte) (McoLoader.MAJOR_VERSION << 4);
        ByteBuffer info = ByteBuffer.wrap(file, footer + 4, McoLoader.FOOTER_SIZE - 4).order(order);
        info.putShort((short) 1);
        info.putShort((short) 0);
        info.putInt(mcodeSize);
        info.putInt(locTableSize);
        info.putInt(16);
        info.put((byte) McoLoader.RUNTIME_NUMBER);
        info.put((byte) 0);
        info.putShort((short) McoLoader.SERIAL_NUMBER);
        info.putInt(16);
        info.putInt(16);
    }

    private static void writeUnsignedShort(byte[] bytes, int offset, int value, ByteOrder order) {
        ByteBuffer.wrap(bytes, offset, Short.BYTES).order(order).putShort((short) value);
    }

    private static void writeUnsignedInt(byte[] bytes, int offset, long value, ByteOrder order) {
        ByteBuffer.wrap(bytes, offset, Integer.BYTES).order(order).putInt((int) value);
    }

    private static void reverse(byte[] bytes, int offset, int length) {
        for (int left = offset, right = offset + length - 1; left < right; left++, right--) {
            byte value = bytes[left];
            bytes[left] = bytes[right];
            bytes[right] = value;
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

        byte[] writableStackBytes = new byte[2 * Integer.BYTES];
        StackPointer writableStack = new StackPointer(writableStackBytes, 0);
        writableStack.pushInt32(0x12345678, ByteOrder.LITTLE_ENDIAN);
        writableStack.pushInt32(0, ByteOrder.LITTLE_ENDIAN);
        writableStack.setInt32(1, 0x87654321, ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x87654321, writableStack.peekInt32(1, ByteOrder.LITTLE_ENDIAN),
                "updated stack slot");
    }

    private static void testDispatcherRejectsInvalidMco() throws Exception {
        try (TempMco fixture = new TempMco(new byte[McoLoader.FOOTER_SIZE])) {
            CapturedRun result = runDispatcher(fixture.path().toString());
            assertEquals(1, result.exitCode(), "invalid MCO exit code");
            assertEquals("", result.stdout(), "invalid MCO stdout");
            assertContains(result.stderr(), "Illegal M-code mark", "diagnostic");
        }
    }

    private static void testHelloMco() throws Exception {
        Path fixture = Path.of("tests", "fixtures", "hello.mco").toAbsolutePath().normalize();
        CapturedRun result = runDispatcher(fixture.toString());
        assertEquals(0, result.exitCode(), "hello MCO exit code");
        assertEquals("Hello by mind8", result.stdout(), "hello MCO stdout");
        assertEquals("", result.stderr(), "hello MCO stderr");
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

    private static void assertArrayEquals(byte[] expected, byte[] actual, String description) {
        if (!java.util.Arrays.equals(expected, actual)) {
            throw new AssertionError(description + ": byte arrays differ");
        }
    }

    private record TestCase(String name, TestAction action) {
    }

    private record McoMutation(String name, java.util.function.Consumer<byte[]> mutation) {
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
