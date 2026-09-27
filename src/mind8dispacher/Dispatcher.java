package mind8dispacher;

public class Dispatcher {
    private MCodePointer mcodePointer;
    private LocTablePointer locTablePointer;
    private AccessPointer dataPointer;
    private StackPointer dataStackPointer;
    private StackPointer returnStackPointer;
    private Runnable[] cFunctions;

    public static void main(String[] args) {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setupForDispatch(args);
        dispatcher.setupFunctions();
        dispatcher.dispatch();
    }

    private void setupForDispatch(String[] args) {
        // MCO読込みと実行領域の初期化を実装する。
    }

    private void setupFunctions() {
        // C関数番号とJavaハンドラーの対応を設定する。
    }

    private void dispatch() {
        // 命令の最上位ビットに応じてC関数またはMind単語へ分岐する。
    }
}