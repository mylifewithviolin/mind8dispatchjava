# Mind8ディスパッチャ Java互換実装 次の優先項目4 完了レポート v2

## 結果

**状態: hello MCOを用いたC/Java実行比較とJava 20での回帰検証を完了。ディスパッチャ全体の互換性は未完了です。**

先行するJava項目1～4の完了レポートに記載された主要な実行阻害要因を解消し、通常ユーザーカーネル向けMCOのロードとディスパッチを実装しました。C版とJava版へ同一の `hello.mco` を渡した結果、標準出力 `Hello by mind8` と終了コード `0` が一致しました。Java 20でアプリケーションと回帰テストをコンパイルし、9件すべて成功しました。

一方で、成功した統合比較はhelloプログラムの実行経路に限られます。C関数テーブル743件すべてのハンドラー実装・照合、MCOの全メタデータ異常系と両エンディアン、全命令・再開・終了経路のC版比較は完了していません。したがってC#版項目4レポートと同等の「21件のテスト一式を通した全面的な完了」とは扱わず、確認範囲と残件を以下に明記します。

## 実施内容

### 項目1: C関数番号テーブル

- `CFunctionTable` の番号範囲 `0x0010`～`0x02F6`、重複・範囲外拒否、別名登録と比較分類を維持しました。
- 通常カーネルのCテーブル `tests/fixtures/c_words.tbl`（743行）を再実行可能な資料として追加しました。
- helloの実行経路で必要になったC関数と定数のハンドラーを追加しました。Cテーブルの全番号についてJava登録先・意味実装を照合する自動テストは未実装です。登録済みの一部ハンドラーがあることを全関数対応済みとは解釈しません。

### 項目2: MCOロード

- 32バイト情報フッターの `MC` マーク、Version、Runtime、Serial、MAIN、Mコード・LOC・データ・スタック各サイズを読み取り、通常ユーザーカーネル向け条件を検証します。
- ペイロード境界を確認し、必要な場合はEDI情報を使ってMコードとLOCテーブルをネイティブバイト順へ変換します。
- 正常なhello MCOのロード・実行と、不正なマークおよび短いファイルの拒否を検査しました。
- 有効なビッグエンディアンMCO、各メタデータ不一致、EDI切詰め等を含むローダー統合テストは残っています。低レベルのMコード・LOCポインターについて両バイト順のテストはありますが、MCO全体の両エンディアン互換を証明するものではありません。

### 項目3: 命令とディスパッチ

- C関数番号とMind単語の振分け、MCO領域・スタックの初期化、LOCを介するMind単語への遷移、主要な制御移動、変数・文字列・出力のhello実行経路を実装しました。
- スタックスロットの更新処理と、hello実行中に必要な演算、CRT定数・呼出し処理を追加しました。
- `enable_ctrlC_signal` はJava側でシグナルハンドラーを登録せず、`c_setmode` は引数を消費するのみです。hello出力に不要なOS固有副作用を完全再現した実装ではありません。`O_BINARY` / `O_TEXT` の値もWindows CRT値を前提にしています。
- 全Mind命令、全C関数、スタック境界・再開位置・アプリケーション終了コードをC版と比較する試験は未実施です。

### 項目4: 回帰テストと同一入力比較

- `tests/RegressionTests.java` を現行のロード動作に合わせ、破損フッターを検証前拒否するテストへ更新しました。
- C#側の `hello.mco` を基にした固定fixtureを用意し、Serialを通常ユーザーカーネルの42に合わせました。fixtureのSHA-256は `13E60DD76FB2C38D2CD97057E7300645D209FEFA3BA87294499E74E947A1599C` です。
- 同じfixtureを既存のC実行ファイル `mind8kernel/obj/kernel.exe` とJavaへ渡しました。

| 実装 | 標準出力 | 終了コード |
|---|---|---:|
| C (`mind8kernel/obj/kernel.exe`) | `Hello by mind8` | 0 |
| Java (`mind8dispacher.Main`) | `Hello by mind8` | 0 |

C実行ファイルは既存ビルドを使用しており、この作業では再ビルドしていません。したがって、比較はその実行ファイルとの一致を示すものであり、現在のCソース全体との一致を示すものではありません。

## 検証

- コンパイラ: Eclipse Temurin `javac 20.0.2`。
- アプリケーションソースと `tests/RegressionTests.java` のUTF-8コンパイル: 成功、終了コード `0`。
- 実行コマンド: `java -cp build mind8dispacher.RegressionTests`。
- 結果: **9/9テスト成功**。
- テスト対象: 関数テーブルの登録・比較、MCOパスと生データ分離、短いMCO拒否、MコードとLOCの両バイト順・境界、データ領域とスタック境界・reset・スロット更新、不正MCOメタデータ拒否、hello MCOの標準出力・終了コード、入力不足の診断。
- C#版の21件のテスト成功は、C#版項目4レポートに記録された結果です。このJava作業中にC#テストを再実行したものではありません。

再実行例（ワークスペースルートから実行）:

```powershell
$javac = 'C:\Program Files\Eclipse Adoptium\jdk-20.0.2.9-hotspot\bin\javac.exe'
$java = 'C:\Program Files\Eclipse Adoptium\jdk-20.0.2.9-hotspot\bin\java.exe'
& $javac -encoding UTF-8 -d build src\mind8dispacher\AccessPointer.java src\mind8dispacher\CFunctionTable.java src\mind8dispacher\Dispatcher.java src\mind8dispacher\LocTablePointer.java src\mind8dispacher\Main.java src\mind8dispacher\MCodePointer.java src\mind8dispacher\McoLoader.java src\mind8dispacher\StackPointer.java tests\RegressionTests.java
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $java -cp build mind8dispacher.RegressionTests
exit $LASTEXITCODE
```

## 残課題

完了範囲をディスパッチャ全体へ拡張するには、少なくとも次が必要です。

1. `c_words.tbl` 全743番号とJava登録を機械的に照合し、未実装・意図的alias・名前差・OS依存関数を分類する。各実装のC側意味とスタック効果も個別に確認する。
2. 有効な両エンディアンMCO、EDI変換、MAIN/領域境界、各メタデータ不一致・切詰めを含むローダー統合テストを追加する。
3. Mind分岐・復帰、再開、ディスパッチ終了、アプリケーション終了、データ/リターンスタックの各経路についてC版との比較fixtureを追加する。
4. Windows CRTのシグナル登録・`setmode` 等、Java標準出力・ファイルAPIと異なるOS依存副作用を必要な互換範囲に合わせて実装・検証する。
5. CI実行設定を追加し、hello以外の代表プログラムでもC/Java比較を継続的に実行する。

以上により、以前の状態にあった「MCOを解釈せず拒否するため同一入力実行比較が成立しない」という阻害要因は解消しました。Java 20コンパイルとhelloのC/Java出力比較は完了していますが、全関数・全制御経路の互換性は未証明であり、全体完了とは報告しません。
