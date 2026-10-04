package mind8dispacher;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Dispatcher {
    private static final int O_BINARY = 0x8000;
    private static final int O_TEXT = 0x4000;
    private MCodePointer mcodePointer;
    private LocTablePointer locTablePointer;
    private AccessPointer dataPointer;
    private StackPointer dataStackPointer;
    private StackPointer returnStackPointer;
    private CFunctionTable cFunctions;
    private ByteOrder mcodeByteOrder;
    private Path mcodePath;
    private McoLoader.McoInfo mcodeInfo;
    private final Map<Integer, Integer> fileModes = new HashMap<>();

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static int run(String[] args) {
        Dispatcher dispatcher = new Dispatcher();
        try {
            dispatcher.setupForDispatch(args);
            dispatcher.setupFunctions();
            dispatcher.dispatch();
            return 0;
        } catch (ApplicationExit exit) {
            return exit.exitCode;
        } catch (IOException | RuntimeException error) {
            System.err.println("Mind8 dispatcher: " + error.getMessage());
            return 1;
        }
    }

    private void setupForDispatch(String[] args) throws IOException {
        McoLoader.LoadedMco loaded = McoLoader.load(McoLoader.resolvePath(args));
        mcodePath = loaded.path();
        mcodeInfo = loaded.info();
        mcodeByteOrder = ByteOrder.nativeOrder();
        mcodePointer = new MCodePointer(loaded.mcode(), 0x70);
        locTablePointer = new LocTablePointer(loaded.locTable(), 0);
        dataPointer = new AccessPointer(new byte[(int) mcodeInfo.dataSize()], 0);
        dataStackPointer = new StackPointer(new byte[(int) mcodeInfo.dataStackSize()], 0);
        returnStackPointer = new StackPointer(new byte[(int) mcodeInfo.returnStackSize()], 0);
    }

    private void setupFunctions() {
        cFunctions = new CFunctionTable();
        cFunctions.register(0x0010, "zzEndExecute", this::endExecute);
        cFunctions.register(0x0011, "jikkou", this::execute);
        cFunctions.register(0x0012, "musyori", () -> { });
        cFunctions.register(0x0013, "emgcyExit", () -> requestApplicationExit(1));
        cFunctions.register(0x0014, "processOwari", this::processExit);
        cFunctions.register(0x0015, "stackKensa0", this::checkStack);
        cFunctions.register(0x0016, "stackReset", () -> requestResume(this));
        cFunctions.register(0x0017, "finish_dispatcher", Dispatcher::requestDispatchEnd);
        cFunctions.register(0x0018, "c_stack_data_no", Dispatcher::requestDispatchEnd);
        cFunctions.register(0x0019, "zzReserve1L", () -> returnStackPointer.moveSlots(-1));
        cFunctions.register(0x001a, "zzReserve2L", () -> returnStackPointer.moveSlots(-2));
        cFunctions.register(0x001b, "zzReserve3L", () -> returnStackPointer.moveSlots(-3));
        cFunctions.register(0x001c, "zzReserve4L", () -> returnStackPointer.moveSlots(-4));
        cFunctions.register(0x001d, "zzReserve5L", () -> returnStackPointer.moveSlots(-5));
        cFunctions.register(0x001e, "zzReserve6L", () -> returnStackPointer.moveSlots(-6));
        cFunctions.register(0x001f, "zzReserve", () ->
                returnStackPointer.moveSlots(-mcodePointer.readUnsignedWord(mcodeByteOrder)));
        cFunctions.register(0x0020, "zzExit1L", this::exitOneLevel);
        cFunctions.register(0x0021, "zzExit2L", () -> exitFrame(2));
        cFunctions.register(0x0022, "zzExit3L", () -> exitFrame(3));
        cFunctions.register(0x0023, "zzExit4L", () -> exitFrame(4));
        cFunctions.register(0x0024, "zzExit5L", () -> exitFrame(5));
        cFunctions.register(0x0025, "zzExit6L", () -> exitFrame(6));
        cFunctions.register(0x0026, "zzExit7L", () -> exitFrame(7));
        cFunctions.register(0x002f, "zzJmpIndexed", this::jumpIndexed);
        cFunctions.register(0x0036, "zzPointVarAddr", this::pointVariable);
        cFunctions.register(0x004d, "zzReadWvar", this::readWordVariable);
        cFunctions.register(0x004e, "zzReadDvar", this::readDoubleVariable);
        cFunctions.register(0x0052, "zzReadJvar", this::readStringVariable);
        cFunctions.register(0x0053, "zzReadBvarLocal", () -> readLocalVariable(1));
        cFunctions.register(0x0054, "zzReadWvarLocal", () -> readLocalVariable(2));
        cFunctions.register(0x0055, "zzReadDvarLocal", () -> readLocalVariable(4));
        cFunctions.register(0x0063, "zzWriteBvar", this::writeByteVariable);
        cFunctions.register(0x0064, "zzWriteWvar", this::writeWordVariable);
        cFunctions.register(0x0065, "zzWriteDvar", this::writeDoubleVariable);
        cFunctions.register(0x0066, "zzWriteLvar", this::writeLongVariable);
        cFunctions.register(0x0067, "zzWriteQvar", this::writeQuadVariable);
        cFunctions.register(0x0068, "zzWriteSvar", this::writeStringVariable);
        cFunctions.register(0x0069, "zzWriteBvarLocal", () -> writeLocalVariable(1));
        cFunctions.register(0x006a, "zzWriteWvarLocal", () -> writeLocalVariable(2));
        cFunctions.register(0x006b, "zzWriteDvarLocal", () -> writeLocalVariable(4));
        cFunctions.register(0x006c, "zzWriteLvarLocal", () -> writeLocalVariable(8));
        cFunctions.register(0x006d, "zzWriteQvarLocal", () -> writeLocalVariable(8));
        cFunctions.register(0x006e, "zzWriteSvarLocal", () -> writeLocalVariable(8));
        cFunctions.register(0x008f, "byteLoad", () -> { });
        cFunctions.register(0x0070, "zzClearWvar", () -> dataPointer.writeUnsignedShort(0, mcodeByteOrder));
        cFunctions.register(0x0071, "zzClearDvar", () -> dataPointer.writeUnsignedInt(0, mcodeByteOrder));
        cFunctions.register(0x0074, "zzClearSvar", this::clearStringVariable);
        cFunctions.register(0x0080, "zzSetWvar", () -> dataPointer.writeUnsignedShort(1, mcodeByteOrder));
        for (int number = 0x0095; number <= 0x009f; number++) {
            int value = number - 0x0095;
            cFunctions.register(number, "zz" + value, () -> pushQuad(value));
        }
        cFunctions.register(0x00a0, "zzWord", () -> pushQuad(mcodePointer.readUnsignedWord(mcodeByteOrder)));
        cFunctions.register(0x00a1, "zzMword", () ->
                pushQuad(0xffff_0000 | mcodePointer.readUnsignedWord(mcodeByteOrder)));
        cFunctions.register(0x00a2, "zzDouble", () ->
                pushQuad((int) mcodePointer.readUnsignedInt(mcodeByteOrder)));
        cFunctions.register(0x00a5, "zzSliteralEven", this::pushStringLiteral);
        cFunctions.register(0x00ec, "zzIf", this::ifBranch);
        cFunctions.register(0x00ed, "zzNotif", this::notIfBranch);
        cFunctions.register(0x00ee, "zzDO", this::doLoop);
        cFunctions.register(0x0101, "hitotuKuwae", () -> adjustStackValue(1));
        cFunctions.register(0x0102, "futatuKuwae", () -> adjustStackValue(2));
        cFunctions.register(0x0103, "hitotuHiki", () -> adjustStackValue(-1));
        cFunctions.register(0x0104, "futatuHiki", () -> adjustStackValue(-2));
        cFunctions.register(0x0105, "zzAddWimm", () ->
                adjustStackValue(mcodePointer.readUnsignedWord(mcodeByteOrder)));
        cFunctions.register(0x0106, "zzAddDimm", () ->
                adjustStackValue((int) mcodePointer.readUnsignedInt(mcodeByteOrder)));
        cFunctions.register(0x0107, "kuwae", () -> {
            dataStackPointer.popInt32(mcodeByteOrder);
            int value = dataStackPointer.popInt32(mcodeByteOrder);
            adjustStackValue(value);
        });
        cFunctions.register(0x0108, "zzSubWimm", () ->
                adjustStackValue(-mcodePointer.readUnsignedWord(mcodeByteOrder)));
        cFunctions.register(0x0109, "zzSubDimm", () ->
                adjustStackValue(-(int) mcodePointer.readUnsignedInt(mcodeByteOrder)));
        cFunctions.register(0x0165, "zzWriteJvar", this::writeStringInstance);
        cFunctions.register(0x016e, "zzClearXvar", this::clearExtendedVariable);
        cFunctions.register(0x00c1, "zzHyouji", this::displayString);
        cFunctions.register(0x0261, "c_mcodeFullFilename", this::pushMcodeFilename);
        cFunctions.register(0x0264, "get_Arg_c", this::pushArgumentCount);
        cFunctions.register(0x0268, "enable_ctrlC_signal", () -> { });
        cFunctions.register(0x026d, "outputDevice", this::outputDevice);
        cFunctions.register(0x0296, "c_O_BINARY", () -> pushQuad(0x8000));
        cFunctions.register(0x0297, "c_O_TEXT", () -> pushQuad(0x4000));
        cFunctions.register(0x0298, "c_setmode", this::setFileMode);
    }

    List<CFunctionTable.Difference> compareCFunctions(List<CFunctionTable.CEntry> cEntries) {
        if (cFunctions == null) {
            setupFunctions();
        }
        return cFunctions.compare(cEntries);
    }

    private void dispatch() {
        if (mcodePointer == null || locTablePointer == null || dataPointer == null
                || dataStackPointer == null || returnStackPointer == null || cFunctions == null
                || mcodeByteOrder == null) {
            throw new IllegalStateException("Dispatcher state is not initialized");
        }

        while (true) {
            int instruction = mcodePointer.readUnsignedWord(mcodeByteOrder);
            try {
                if ((instruction & 0x8000) == 0) {
                    cFunctions.invoke(instruction);
                } else {
                    dispatchMindWord(instruction & 0x7FFF);
                }
            } catch (DispatchTransfer transfer) {
                switch (transfer.kind) {
                    case RESUME -> {
                        // The instruction pointer already points after the fetched call.
                    }
                    case DISPATCH_END -> {
                        return;
                    }
                    case APPLICATION_EXIT -> throw new ApplicationExit(transfer.exitCode);
                }
            }
        }
    }

    private void dispatchMindWord(int locIndex) {
        returnStackPointer.pushInt32(mcodePointer.position(), mcodeByteOrder);
        mcodePointer.seek(locTablePointer.getWordOffset(locIndex, mcodeByteOrder));
    }

    private void jumpIndexed() {
        int locIndex = mcodePointer.peekUnsignedWord(mcodeByteOrder);
        mcodePointer.seek(locTablePointer.getWordOffset(locIndex, mcodeByteOrder));
    }

    private void pointVariable() {
        long offset = mcodePointer.readUnsignedInt(mcodeByteOrder);
        if (offset > Integer.MAX_VALUE) {
            throw new IllegalStateException("Data offset exceeds supported range: " + offset);
        }
        dataPointer.seek((int) offset);
    }

    private void writeByteVariable() {
        pointDataFromMcode();
        dataStackPointer.popInt32(mcodeByteOrder);
        dataPointer.writeByte(dataStackPointer.popInt32(mcodeByteOrder) & 0xff);
    }

    private void writeWordVariable() {
        pointDataFromMcode();
        dataStackPointer.popInt32(mcodeByteOrder);
        dataPointer.writeUnsignedShort(dataStackPointer.popInt32(mcodeByteOrder) & 0xffff, mcodeByteOrder);
    }

    private void writeDoubleVariable() {
        pointDataFromMcode();
        dataStackPointer.popInt32(mcodeByteOrder);
        dataPointer.writeUnsignedInt(Integer.toUnsignedLong(dataStackPointer.popInt32(mcodeByteOrder)),
                mcodeByteOrder);
    }

    private void writeLongVariable() {
        pointDataFromMcode();
        int low = dataStackPointer.popInt32(mcodeByteOrder);
        int high = dataStackPointer.popInt32(mcodeByteOrder);
        dataPointer.writeUnsignedLongPair(Integer.toUnsignedLong(high), Integer.toUnsignedLong(low),
                mcodeByteOrder);
    }

    private void writeQuadVariable() {
        pointDataFromMcode();
        int first = dataStackPointer.popInt32(mcodeByteOrder);
        int second = dataStackPointer.popInt32(mcodeByteOrder);
        dataPointer.writeUnsignedLongPair(Integer.toUnsignedLong(first), Integer.toUnsignedLong(second),
                mcodeByteOrder);
    }

    private void writeStringVariable() {
        pointDataFromMcode();
        int count = dataStackPointer.popInt32(mcodeByteOrder);
        int address = dataStackPointer.popInt32(mcodeByteOrder);
        dataPointer.writeUnsignedLongPair(Integer.toUnsignedLong(count), Integer.toUnsignedLong(address),
                mcodeByteOrder);
    }

    private void writeLocalVariable(int width) {
        int slot = mcodePointer.readUnsignedWord(mcodeByteOrder);
        dataPointer.seek(returnStackPointer.peekInt32(slot, mcodeByteOrder));
        if (width == 1) {
            dataStackPointer.popInt32(mcodeByteOrder);
            dataPointer.writeByte(dataStackPointer.popInt32(mcodeByteOrder) & 0xff);
        } else if (width == 2) {
            dataStackPointer.popInt32(mcodeByteOrder);
            dataPointer.writeUnsignedShort(dataStackPointer.popInt32(mcodeByteOrder) & 0xffff, mcodeByteOrder);
        } else if (width == 4) {
            dataStackPointer.popInt32(mcodeByteOrder);
            dataPointer.writeUnsignedInt(Integer.toUnsignedLong(dataStackPointer.popInt32(mcodeByteOrder)),
                    mcodeByteOrder);
        } else {
            int count = dataStackPointer.popInt32(mcodeByteOrder);
            int address = dataStackPointer.popInt32(mcodeByteOrder);
            dataPointer.writeUnsignedLongPair(Integer.toUnsignedLong(count), Integer.toUnsignedLong(address),
                    mcodeByteOrder);
        }
    }

    private void pointDataFromMcode() {
        long offset = mcodePointer.readUnsignedInt(mcodeByteOrder);
        if (offset > Integer.MAX_VALUE) {
            throw new IllegalStateException("Data offset exceeds supported range: " + offset);
        }
        dataPointer.seek((int) offset);
    }

    private void readWordVariable() {
        pointDataFromMcode();
        pushQuad(dataPointer.readUnsignedShort(mcodeByteOrder));
    }

    private void readDoubleVariable() {
        pointDataFromMcode();
        pushQuad((int) dataPointer.readUnsignedInt(mcodeByteOrder));
    }

    private void readStringVariable() {
        pointDataFromMcode();
        int start = dataPointer.position();
        long length = dataPointer.readUnsignedIntAt(start, mcodeByteOrder);
        long margin = dataPointer.readUnsignedIntAt(Math.addExact(start, Integer.BYTES), mcodeByteOrder);
        long address = Math.addExact(Math.addExact(start, 2L * Integer.BYTES), margin);
        if (length > Integer.MAX_VALUE || address > Integer.MAX_VALUE) {
            throw new IllegalStateException("String variable metadata exceeds supported range");
        }
        dataStackPointer.pushInt32((int) address, mcodeByteOrder);
        dataStackPointer.pushInt32((int) length, mcodeByteOrder);
    }

    private void readLocalVariable(int width) {
        int slot = mcodePointer.readUnsignedWord(mcodeByteOrder);
        int value = returnStackPointer.peekInt32(slot, mcodeByteOrder);
        if (width == 1) {
            value &= 0xff;
        } else if (width == 2) {
            value &= 0xffff;
        }
        pushQuad(value);
    }

    private void endExecute() {
        returnStackPointer.popInt32(mcodeByteOrder);
        mcodePointer.seek(returnStackPointer.popInt32(mcodeByteOrder));
    }

    private void execute() {
        returnStackPointer.pushInt32(mcodePointer.position(), mcodeByteOrder);
        returnStackPointer.pushInt32(0x0010, mcodeByteOrder);
    }

    private void exitOneLevel() {
        mcodePointer.seek(returnStackPointer.popInt32(mcodeByteOrder));
    }

    private void exitFrame(int localSlots) {
        returnStackPointer.moveSlots(localSlots);
        mcodePointer.seek(returnStackPointer.peekInt32(0, mcodeByteOrder));
    }

    private void processExit() {
        dataStackPointer.popInt32(mcodeByteOrder);
        requestApplicationExit(dataStackPointer.popInt32(mcodeByteOrder));
    }

    private void checkStack() {
        if (dataStackPointer.position() == 0) {
            dataStackPointer.pushInt32(1, mcodeByteOrder);
            dataStackPointer.pushInt32(0, mcodeByteOrder);
        } else {
            dataStackPointer.pushInt32(0, mcodeByteOrder);
            dataStackPointer.pushInt32(0, mcodeByteOrder);
        }
    }

    private void clearStringVariable() {
        dataPointer.writeUnsignedLongPair(0, 0, mcodeByteOrder);
    }

    private void clearExtendedVariable() {
        long length = mcodePointer.readUnsignedInt(mcodeByteOrder);
        if (length > Integer.MAX_VALUE) {
            throw new IllegalStateException("Variable length exceeds supported range: " + length);
        }
        dataPointer.fill(0x20, (int) length);
    }

    private void pushQuad(int value) {
        dataStackPointer.pushInt32(value, mcodeByteOrder);
        dataStackPointer.pushInt32(0, mcodeByteOrder);
    }

    private void pushStringLiteral() {
        int length = mcodePointer.readUnsignedWord(mcodeByteOrder);
        dataStackPointer.pushInt32(mcodePointer.position(), mcodeByteOrder);
        dataStackPointer.pushInt32(length, mcodeByteOrder);
        mcodePointer.seek(Math.addExact(mcodePointer.position(), length));
    }

    private void writeStringInstance() {
        mcodePointer.readUnsignedInt(mcodeByteOrder);
        mcodePointer.readUnsignedInt(mcodeByteOrder);
        dataStackPointer.popInt32(mcodeByteOrder);
        dataStackPointer.popInt32(mcodeByteOrder);
    }

    private void setFileMode() {
        dataStackPointer.popInt32(mcodeByteOrder);
        int mode = dataStackPointer.popInt32(mcodeByteOrder);
        dataStackPointer.popInt32(mcodeByteOrder);
        int fileHandle = dataStackPointer.popInt32(mcodeByteOrder);
        if (fileHandle < 0 || fileHandle > 2) {
            throw new IllegalStateException("Unsupported C file handle for setmode: "
                    + fileHandle + " (mode " + mode + ")");
        }
        if (mode != O_BINARY && mode != O_TEXT) {
            throw new IllegalStateException("Unsupported C file mode: " + mode);
        }
        fileModes.put(fileHandle, mode);
    }

    static byte[] translateTextNewlines(byte[] bytes) {
        int extra = 0;
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == '\n' && (index == 0 || bytes[index - 1] != '\r')) {
                extra++;
            }
        }
        if (extra == 0) {
            return bytes;
        }
        byte[] translated = new byte[bytes.length + extra];
        int target = 0;
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == '\n' && (index == 0 || bytes[index - 1] != '\r')) {
                translated[target++] = '\r';
            }
            translated[target++] = bytes[index];
        }
        return translated;
    }

    private void adjustStackValue(int adjustment) {
        int value = dataStackPointer.peekInt32(1, mcodeByteOrder);
        dataStackPointer.setInt32(1, value + adjustment, mcodeByteOrder);
    }

    private void ifBranch() {
        dataStackPointer.popInt32(mcodeByteOrder);
        if (dataStackPointer.popInt32(mcodeByteOrder) >= 1) {
            mcodePointer.seek(Math.addExact(mcodePointer.position(), Short.BYTES));
        } else {
            mcodePointer.seek(Math.addExact(mcodePointer.position(),
                    mcodePointer.peekSignedWord(mcodeByteOrder)));
        }
    }

    private void notIfBranch() {
        dataStackPointer.popInt32(mcodeByteOrder);
        if (dataStackPointer.popInt32(mcodeByteOrder) >= 1) {
            mcodePointer.seek(Math.addExact(mcodePointer.position(),
                    mcodePointer.peekSignedWord(mcodeByteOrder)));
        } else {
            mcodePointer.seek(Math.addExact(mcodePointer.position(), Short.BYTES));
        }
    }

    private void doLoop() {
        dataStackPointer.popInt32(mcodeByteOrder);
        int count = dataStackPointer.popInt32(mcodeByteOrder);
        if (count == 0) {
            mcodePointer.seek(Math.addExact(mcodePointer.position(),
                    mcodePointer.peekSignedWord(mcodeByteOrder)));
        } else {
            returnStackPointer.pushInt32(count, mcodeByteOrder);
            returnStackPointer.pushInt32(0, mcodeByteOrder);
            mcodePointer.seek(Math.addExact(mcodePointer.position(), Short.BYTES));
        }
    }

    private void displayString() {
        int length = dataStackPointer.popInt32(mcodeByteOrder);
        int offset = dataStackPointer.popInt32(mcodeByteOrder);
        System.out.print(decodeString(mcodePointer.bytesAt(offset, length)));
    }

    private void outputDevice() {
        dataStackPointer.popInt32(mcodeByteOrder);
        int fileHandle = dataStackPointer.popInt32(mcodeByteOrder);
        int length = dataStackPointer.popInt32(mcodeByteOrder);
        int offset = dataStackPointer.popInt32(mcodeByteOrder);
        byte[] bytes = mcodePointer.bytesAt(offset, length);
        OutputStream output = switch (fileHandle) {
            case 1 -> System.out;
            case 2 -> System.err;
            default -> throw new IllegalStateException(
                    "Unsupported C output file handle: " + fileHandle);
        };
        if (fileModes.getOrDefault(fileHandle, O_TEXT) == O_TEXT) {
            bytes = translateTextNewlines(bytes);
        }
        try {
            output.write(bytes);
            output.flush();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write C output for file handle " + fileHandle, exception);
        }
    }

    private void pushMcodeFilename() {
        byte[] filename = (mcodePath.getFileName().toString() + '\0').getBytes(Charset.forName("Shift_JIS"));
        int offset = mcodePointer.length();
        mcodePointer.appendBytes(filename);
        dataStackPointer.pushInt32(offset, mcodeByteOrder);
        dataStackPointer.pushInt32(0, mcodeByteOrder);
    }

    private void pushArgumentCount() {
        dataStackPointer.pushInt32(2, mcodeByteOrder);
        dataStackPointer.pushInt32(0, mcodeByteOrder);
    }

    private static String decodeString(byte[] bytes) {
        try {
            return Charset.forName("Shift_JIS").newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    static void requestResume(Dispatcher dispatcher) {
        dispatcher.requireStacks();
        dispatcher.dataStackPointer.reset();
        dispatcher.returnStackPointer.reset();
        throw new DispatchTransfer(TransferKind.RESUME, 0);
    }

    static void requestDispatchEnd() {
        throw new DispatchTransfer(TransferKind.DISPATCH_END, 0);
    }

    static void requestApplicationExit(int exitCode) {
        throw new DispatchTransfer(TransferKind.APPLICATION_EXIT, exitCode);
    }

    private void requireStacks() {
        if (dataStackPointer == null || returnStackPointer == null) {
            throw new IllegalStateException("Cannot resume before both stacks are initialized");
        }

    }

    private enum TransferKind {
        RESUME,
        DISPATCH_END,
        APPLICATION_EXIT
    }

    private static final class DispatchTransfer extends RuntimeException {
        private final TransferKind kind;
        private final int exitCode;

        private DispatchTransfer(TransferKind kind, int exitCode) {
            super(null, null, false, false);
            this.kind = kind;
            this.exitCode = exitCode;
        }
    }

    private static final class ApplicationExit extends RuntimeException {
        private final int exitCode;

        private ApplicationExit(int exitCode) {
            super(null, null, false, false);
            this.exitCode = exitCode;
        }
    }
}