package mind8dispacher;

import java.io.IOException;
import java.nio.ByteOrder;

public class Dispatcher {
    private MCodePointer mcodePointer;
    private LocTablePointer locTablePointer;
    private AccessPointer dataPointer;
    private StackPointer dataStackPointer;
    private StackPointer returnStackPointer;
    private CFunctionTable cFunctions;
    private ByteOrder mcodeByteOrder;

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
        McoLoader.RawMco rawMco = McoLoader.readRaw(McoLoader.resolvePath(args));
        throw new IOException("MCO information layout and target-kernel metadata have not been verified; "
                + "refusing to execute " + rawMco.path());
    }

    private void setupFunctions() {
        cFunctions = new CFunctionTable();
    }

    private void dispatch() {
        if (mcodePointer == null || locTablePointer == null
                || dataStackPointer == null || returnStackPointer == null || cFunctions == null
                || mcodeByteOrder == null) {
            throw new IllegalStateException("Dispatcher state is not initialized");
        }

        while (true) {
            int instruction = mcodePointer.readUnsignedWord(mcodeByteOrder);
            try {
                if ((instruction & 0x8000) != 0) {
                    cFunctions.invoke(instruction & 0x7FFF);
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
        throw new IllegalStateException("Mind-word LOC-to-M-code mapping is not verified; LOC index "
                + locIndex + " cannot be executed safely");
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