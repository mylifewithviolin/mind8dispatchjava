# Mind8ディスパッチャ Java実装クラス構成

## 目的と前提

本書は、Mind8ディスパッチャC実装の構造と「Mind8ディスパッチャC#互換実装の実装状況」に記載されたクラス構成を参考に、Java版のソースファイル構成と枠を定義します。C#版と同様にMCO読込み、16ビット中間コードのディスパッチ、データ領域・スタック領域へのアクセスを分担します。

Javaプロジェクトの既存エントリーポイントに合わせ、パッケージ名は `mind8dispacher` とします。以下はクラス構成の骨組みであり、MCO形式検査、エンディアン変換、C関数番号ごとの処理、スタック制御などの実装は含みません。C版との互換性を満たすには、これらの処理をC実装およびC#実装と照合して追加する必要があります。

## クラス構成

| Javaソース | C#版との対応・責務 |
| --- | --- |
| `Main.java` | アプリケーションのエントリーポイント。`Dispatcher` に実行を委譲する |
| `Dispatcher.java` | MCO準備、領域と関数テーブルの初期化、中間コードのディスパッチを統括する |
| `MCodePointer.java` | Mコード領域と現在位置を保持し、命令読込み・位置変更を担当する |
| `LocTablePointer.java` | LOCテーブルと現在位置を保持し、Mind単語の分岐先を参照する |
| `AccessPointer.java` | データ領域と現在位置を保持し、データへのアクセスを担当する |
| `StackPointer.java` | スタック領域と現在位置を保持し、スタック操作を担当する |

各ポインタークラスは、C#版と同じく配列とインデックスを使って領域を表す枠とします。ワードの符号、バイト順、位置の単位、境界検査などはC版のポインターマクロに照合して確定してください。

## Javaソースの枠

### `src/mind8dispacher/Main.java`

```java
package mind8dispacher;

public class Main {
    public static void main(String[] args) {
        Dispatcher.main(args);
    }
}
```

### `src/mind8dispacher/Dispatcher.java`

```java
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
```

`Runnable[]` はC#版の `Action[]` に相当する関数テーブルの枠です。実際のハンドラーからディスパッチャ状態を操作する方法や、未登録番号・終了・再開の扱いは、C版の `C_Words_Addr` と `setjmp` / `longjmp` の動作に合わせて設計します。

### `src/mind8dispacher/MCodePointer.java`

```java
package mind8dispacher;

public class MCodePointer {
    private final byte[] data;
    private int index;

    public MCodePointer(byte[] data, int index) {
        this.data = data;
        this.index = index;
    }
}
```

### `src/mind8dispacher/LocTablePointer.java`

```java
package mind8dispacher;

public class LocTablePointer {
    private final byte[] data;
    private int index;

    public LocTablePointer(byte[] data, int index) {
        this.data = data;
        this.index = index;
    }
}
```

### `src/mind8dispacher/AccessPointer.java`

```java
package mind8dispacher;

public class AccessPointer {
    private final byte[] data;
    private int index;

    public AccessPointer(byte[] data, int index) {
        this.data = data;
        this.index = index;
    }
}
```

### `src/mind8dispacher/StackPointer.java`

```java
package mind8dispacher;

public class StackPointer {
    private final byte[] data;
    private int index;

    public StackPointer(byte[] data, int index) {
        this.data = data;
        this.index = index;
    }
}
```

## 実装時の対応事項

- MCOローダーでは、C版の末尾情報、形式・バージョン・ランタイム・シリアル番号、領域サイズ、エンディアン処理を照合する。
- ディスパッチでは、16ビット命令の最上位ビットによるC関数呼出しとMind単語への分岐を実装する。LOC値とMコード位置の換算はC版の定義に合わせる。
- 関数テーブルは通常カーネルの `c_words.tbl` を基準に番号を維持する。未実装番号の扱いを明示し、番号の重複や誤割当てを検査する。
- データスタック、リターンスタック、領域ポインターに対し、符号・バイト順・境界条件を含む操作を追加する。
- C#版で未完了とされるディスパッチ再開、終了条件、文字列実体格納、任意出力先の扱いも、Java版で互換範囲を決めて実装・検証する。
- C版と同一のMCOを入力し、メモリー、スタック、出力、終了状態を比較するテストを整備する。