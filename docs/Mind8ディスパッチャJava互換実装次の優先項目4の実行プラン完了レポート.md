# Mind8ディスパッチャ Java互換実装 次の優先項目4 完了レポート

## 実施結果

**状態: 部分完了。** Java 20で再実行可能なコンポーネント回帰テストを追加し、通常ソースのコンパイルと8件のテストが成功しました。CカーネルとJavaへ同一のhello MCOバイト列を渡す比較も行いましたが、Java側はMCO形式と対象カーネル情報が未検証のため実行を拒否し、C版との出力・終了コードは一致しませんでした。項目1～3の未完了部分に依存するため、実行互換テストの完了とは扱いません。

## 実施内容

- `tests/RegressionTests.java` にJDK標準APIのみで動くテストランナーを追加しました。関数テーブル登録・alias・比較分類・重複拒否、MCOの拡張子解決と生データの複製、短い入力の拒否、Mコード・LOCの両バイト順読取り、アクセス領域・スタック境界とreset、未検証MCOの実行拒否、入力引数不足の診断を検査します。
- テスト用入力は一時ディレクトリに都度生成し、終了時に削除します。32バイトの合成入力はローダーの生読込みと未検証MCOのフェイルクローズを検査するためだけのもので、正常なMCO fixtureではありません。
- `.vscode/tasks.json` に「test mind8dispacher」タスクを追加し、テスト用コンパイルと実行を順に行うようにしました。既存のbuildタスクも全アプリケーションソースを明示してコンパイルするよう更新しました。`Main.java` だけを指定した従来コマンドでは `Dispatcher` を解決できなかったためです。

## C版との同一入力確認

- C版資材は隣接する `mind8kernel` 作業ツリーにあり、既存の `obj/kernel.exe` を使用しました。この作業ではCカーネルを再ビルドしていません。
- 入力元は隣接する `mind8dispatch/hello.mco` です。元ファイルを変更せず一時コピーを作成し、末尾32バイトのSerialフィールドを通常ユーザーカーネル向けの42に合わせてから、その同じ一時ファイルをC版とJava版へ渡しました。
- 比較入力のSHA-256: `13E60DD76FB2C38D2CD97057E7300645D209FEFA3BA87294499E74E947A1599C`
- C版: 標準出力 `Hello by mind8`、終了コード `0`、標準エラーなし。
- Java版: 標準出力なし、終了コード `1`。標準エラーにMCOレイアウトと対象カーネルメタデータが未検証である旨を出し、実行を拒否しました。

したがって同一入力を用意できたことは確認しましたが、C版とJava版の標準出力・終了コードは一致していません。Java側の拒否は現在の安全策として意図された動作であり、互換性が確認できた結果ではありません。C実行ファイルは既存ビルドのため、そのバイナリと現在のCソースの一致も未確認です。

## 検証

- コンパイラ: Eclipse Temurin `javac 20.0.2`。
- アプリケーションソース一式のUTF-8コンパイル: 成功、終了コード `0`。
- テスト用ソース一式を含むUTF-8コンパイル: 成功、終了コード `0`。
- 実行コマンド: `java -cp build mind8dispacher.RegressionTests`
- 結果: **8/8テスト成功**。
- `.vscode/tasks.json`: JSONとして読み込み可能であることを確認しました。
- VS Codeのソース診断では、既存の `Dispatcher.dataPointer` 未使用診断が報告されています。本変更ではこの既存診断を変更していません。Java 20コンパイラによるビルドは成功しています。

## 再実行手順

ワークスペースの「test mind8dispacher」タスクを実行してください。PowerShellから直接実行する場合は、JDK 20の `javac` を使い、アプリケーションの全ソースとテストソースをコンパイルしてからテストランナーを起動します。

```powershell
$javac = 'C:\Program Files\Eclipse Adoptium\jdk-20.0.2.9-hotspot\bin\javac.exe'
$java = 'C:\Program Files\Eclipse Adoptium\jdk-20.0.2.9-hotspot\bin\java.exe'
& $javac -encoding UTF-8 -d build src\mind8dispacher\AccessPointer.java src\mind8dispacher\CFunctionTable.java src\mind8dispacher\Dispatcher.java src\mind8dispacher\LocTablePointer.java src\mind8dispacher\Main.java src\mind8dispacher\MCodePointer.java src\mind8dispacher\McoLoader.java src\mind8dispacher\StackPointer.java tests\RegressionTests.java
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $java -cp build mind8dispacher.RegressionTests
exit $LASTEXITCODE
```

C版のhello比較を再現する場合は、`mind8dispatch/hello.mco` の一時コピーについて、ファイル末尾から32バイト前の情報フッターにあるSerialフィールド（フッター先頭から22バイト、リトルエンディアン）を42にしてから、同じコピーを `mind8kernel/obj/kernel.exe` とJavaの `Main` に渡します。比較する一時ファイルのハッシュも記録してください。Cカーネルを再ビルドしていない点と、Java版が現状は実行を拒否する点に留意してください。

## 未完了範囲と制約

- 関数番号テーブルはC版の実テーブル全件と照合されておらず、Javaハンドラーも未登録です。今回のテストはテーブルAPIの振舞いを検査したもので、C版の関数番号互換性の証明ではありません。
- `McoLoader` は生データを読み込む段階までで、ヘッダー・MAIN・Version/Runtime/Serial・領域配置・EDIをデコードしていません。両バイト順のテストもポインター単体の読取りであり、MCO全体の変換テストではありません。
- 命令実行、LOC分岐、再開、ディスパッチ終了、アプリケーション終了の制御テストと、正常な決定的MCOによるC/Java差分テストは未実施です。これらは項目1～3の実装とC版仕様照合が必要です。
- CIワークフローへの登録は行っていません。ローカルでの再実行には追加したVS Codeタスクを使用できます。

以上から、本項目で達成したのは再利用可能な低レベル回帰テストとJava 20ビルド手順の整備、およびC版基準出力の取得までです。C/Java実行互換の受入れ条件は未達です。
