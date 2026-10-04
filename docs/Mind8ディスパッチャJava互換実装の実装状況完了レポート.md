# Mind8ディスパッチャ Java互換実装の実装状況完了レポート

## 結果

**状態: 基本実行経路と優先項目4の検証環境を整備し、Java回帰テスト14件が成功しました。通常ユーザーカーネルのhello MCOについて、既存C実行ファイルとJavaの標準出力・終了コードが一致しました。ディスパッチャ全体のC互換は未完了です。**

次の優先項目4 v2に記載された残課題のうち、C関数テーブルの機械照合、両エンディアンのMCOロード、メタデータ異常系、主要なディスパッチ制御経路、Windows標準入出力の基本モード処理、継続的なJava回帰テストを実装・検証しました。未実装C関数の意味的互換性や、hello以外のC/Java実行比較は未完了であり、全体完了とは扱いません。

## 実施内容

### 項目1: C関数番号テーブル

- `tests/fixtures/c_words.tbl` の743番号をJava回帰テストから読み込み、重複番号を検査したうえで `CFunctionTable` の登録と照合します。
- 現在の照合結果は、C名と一致するJava登録85件、Java未実装658件、Javaのみの番号0件、登録名の不一致0件です。
- 照合で判明した7件の大文字・小文字または名前表記の差をCテーブルに合わせて修正しました。
- Cテーブルには同名の番号別名が11組あります。Java側の別名登録機能は単体テスト済みですが、これら11組を実ハンドラーへ登録したものではありません。別名の機能実装・動作比較も未実施です。

この照合は番号と名前の整合確認です。登録済み85関数すべてのC側スタック効果・副作用・戻り値が一致することを示すものではありません。

### 項目2: MCOロード

- 有効なリトルエンディアン／ビッグエンディアンMCOを生成する回帰テストを追加しました。ビッグエンディアン試験ではEDIによる16ビット値、32ビット値、LOC値の変換も検査します。
- フッターのマーク、Version、MAIN、Runtime、Serial、Mコード・LOC・データ・スタック各サイズ、およびペイロード境界の不正入力11ケースを拒否することを確認します。
- 反対エンディアンMCOのEDI切詰めも、命令実行前に拒否されることを確認します。

### 項目3: 命令・再開・終了

- 合成MCOの統合テストで、Mind単語によるLOC遷移、条件分岐の真偽両経路、スタックリセット後の次命令からの再開、ディスパッチ終了、緊急終了、指定終了コードを検査します。
- false分岐から終了コード5を指定する合成MCOを、既存C実行ファイルとJavaへ同一入力で渡しました。両方とも標準出力・標準エラーなし、終了コード5で一致しました。入力SHA-256は `CDAF2110D6FD18D51CE89D66E6163F7C880895F3A0AFA66A5B351056D7447154` です。
- スタックリセット後の再開、Mind単語遷移、分岐の真経路、緊急終了など、その他の制御結果はJava側の決定的テストです。C実行ファイルとの同一MCO比較はhelloと上記の合成分岐シナリオに限られます。

### 項目4: 回帰テストとC/Java比較

- `c_setmode` は標準ファイル記述子0～2と `O_BINARY` / `O_TEXT` を受け付け、設定モードを保持します。`outputDevice` は標準出力・標準エラーへバイト列を書き出し、テキストモード時のWindows改行変換を行います。未対応のファイル記述子・モード・書込みエラーは診断します。
- `tests/fixtures/hello.mco` をC版とJava版へ同一入力として渡しました。入力SHA-256は `13E60DD76FB2C38D2CD97057E7300645D209FEFA3BA87294499E74E947A1599C` です。

| 実装 | 標準出力 | 終了コード | 標準エラー |
|---|---|---:|---|
| C (`mind8kernel/obj/kernel.exe`) | `Hello by mind8` | 0 | なし |
| Java (`mind8dispacher.Main`) | `Hello by mind8` | 0 | なし |

C実行ファイルは既存のビルドを使用し、この作業では再ビルドしていません。したがって、結果はそのバイナリとの一致であり、現在のCソース全体との一致を保証しません。

追加で、分岐後に終了コード5を返す合成MCOでもC版・Java版の終了コードが一致しました。同一入力のSHA-256は `CDAF2110D6FD18D51CE89D66E6163F7C880895F3A0AFA66A5B351056D7447154` で、両実装とも標準出力・標準エラーは空でした。このfixtureは分岐・プロセス終了経路を検査する合成入力であり、コンパイラ生成プログラムの代表例ではありません。

### 項目5: 継続実行

- `.github/workflows/java-regression.yml` を追加し、push、pull request、手動実行でTemurin Java 20によるコンパイルと回帰テストを実行するようにしました。
- CIはJava側テストを実行します。Cカーネル実行ファイルをCIへ含めていないため、CI上でC/Java差分比較は行いません。

## 検証

- コンパイラ／実行環境: Eclipse Temurin `javac 20.0.2` / Java 20。
- アプリケーションソースと `tests/RegressionTests.java` のUTF-8コンパイル: 成功、終了コード `0`。
- 実行コマンド: `java -cp build mind8dispacher.RegressionTests`
- 結果: **14/14テスト成功**。
- テスト内容: 関数テーブル登録API・全743番号照合、MCOパス解決と生データ分離、短いMCO拒否、両エンディアンMCO/EDI変換、メタデータ異常系、Mコード・LOC読取り、データ領域・スタック境界、Mind単語遷移、分岐、再開、終了コード、テキスト改行変換、hello出力、引数不足診断。
- VS CodeのJava診断: 変更した `Dispatcher.java`、`CFunctionTable.java`、`McoLoader.java`、`RegressionTests.java` にエラーなし。

ワークスペースルートからの再実行例:

```powershell
$javac = 'C:\Program Files\Eclipse Adoptium\jdk-20.0.2.9-hotspot\bin\javac.exe'
$java = 'C:\Program Files\Eclipse Adoptium\jdk-20.0.2.9-hotspot\bin\java.exe'
& $javac -encoding UTF-8 -d build src\mind8dispacher\AccessPointer.java src\mind8dispacher\CFunctionTable.java src\mind8dispacher\Dispatcher.java src\mind8dispacher\LocTablePointer.java src\mind8dispacher\Main.java src\mind8dispacher\MCodePointer.java src\mind8dispacher\McoLoader.java src\mind8dispacher\StackPointer.java tests\RegressionTests.java
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $java -cp build mind8dispacher.RegressionTests
exit $LASTEXITCODE
```

## 未完了範囲

- C関数テーブルの658番号にJavaハンドラーがありません。登録済み85件についても、hello実行経路以外を含むC実装との網羅的な意味・スタック効果比較は未実施です。
- 11組の同名エイリアスはCテーブル上で確認しましたが、Java実ハンドラーへのエイリアス登録と動作検証は未完了です。
- 全Mind命令、全C関数、全分岐・再開経路、全スタック境界のC版との比較fixture、およびコンパイラ生成のhello以外の代表MCOを使ったC/Java実行比較はありません。
- `enable_ctrlC_signal` のCランタイム固有シグナルハンドラーはJava側で再現していません。`c_setmode` と出力先の互換も標準ファイル記述子に限定され、任意ファイル・OS固有APIの完全互換ではありません。
- CIではJava回帰テストのみを実行します。Cカーネルとの継続的な差分比較は未設定です。

以上により、項目4のhello同一入力比較とJava 20回帰検証、および主要なローダー・制御経路の回帰基盤を完了しました。一方、ディスパッチャ全体の互換性は未証明であり、残件を含む全面完了とは報告しません。
