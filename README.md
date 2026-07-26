# aidd-tools

自然言語と既存コードを、監査可能なJSON-LD形式仕様とAlloy有限検査へ接続するAIDD Harnessです。

開発時の情報配置とコミット前検証は[CONTRIBUTING.md](CONTRIBUTING.md)に従います。

## Quick install

macOSまたはLinuxで、JDK 21、Node.js 20とCorepackを用意して実行します。pnpm 10.13.1はCorepackが選択します。

```sh
./install.sh
```

これにより、`~/.local/bin`へ2つのCLI、`~/.codex/skills`へ2つのAgent Skillが登録されます。既存のユーザーファイルは上書きしません。詳しい利用手順は[Getting started](docs/getting-started.md)を参照してください。

## Development build

```sh
./gradlew test installDist
./gradlew -p extractors/kotlin test installDist
pnpm --dir extractors/typescript install --frozen-lockfile
pnpm --dir extractors/typescript test
pnpm --dir extractors/typescript build
```

## CLIs

```sh
bin/aidd-formalize validate --model model.jsonld
bin/aidd-formalize explore --model model.jsonld --out .aidd/specs/example
bin/aidd-formalize check --model model.jsonld --out .aidd/specs/example
bin/aidd-formalize render --model model.jsonld --out spec.md

bin/aidd-backport extract --repo . --language typescript --out .aidd/specs/code
bin/aidd-backport validate --facts .aidd/specs/code/code-facts.json --model model.jsonld --repo .
bin/aidd-backport render --facts .aidd/specs/code/code-facts.json --out as-built.md
bin/aidd-backport diff --model observed.jsonld --against intended.jsonld --out diff.json
```

終了コードは `0` が承認済み境界内で合格、`2` が反例・不整合、`3` が暫定結果、`4` が未対応・タイムアウト、`5` が人間レビュー要求です。

Alloyの結果は指定された有限スコープ内の結果であり、無条件の完全証明ではありません。
`Constraint`はモデル充足可能性を確認する`fact/run`、`Invariant`は反例を探索する`assert/check`へ変換されます。
明示的な`Invariant`がない場合、SATであっても「反例なし」とは表示せず`PROVISIONAL`になります。
`explore`はacceptedを前提、candidateを探索対象にし、rejectedを除外します。未承認の意味を含むため結果は常に`PROVISIONAL`で、有限検査の結果は`boundedOutcome`へ記録されます。

## v1 の境界

- Kotlin抽出器はKotlin 2.3.21 compiler PSIを使う構文抽出です。K2 Analysis APIによるclasspath付き意味解決は未実装で、推論型が必要な公開宣言は`UNSUPPORTED / SEMANTIC_CLASSPATH_REQUIRED`となります。
- OpenAPI/JSON Schema契約の取り込みはv1ではTypeScript抽出経路に限定され、Kotlinと`--contracts`の組み合わせは黙って無視せず終了コード`4`を返します。
- `--allow-build-tool`は許可を記録しますが、v1は対象Gradleを実行しません。
- TypeScript抽出器はCompiler API 6.0.3を使用し、構文・型エラーがあれば成果物を残して終了コード`4`を返します。
- 対象コード、テスト、Gradle、package scriptは実行しません。
- `candidate-prose.md`はAgent Skillが生成する候補であり、決定的な`as-built.md`とは混在させません。
- schema 1.1の純粋関数candidate契約は、`Int`、`Bool`、制限付き`String`、Enum、非ネストのSet/List、整数加減乗算、基本Collection演算を対象とします。正規表現、文字列連結・長さ、除算・剰余、Collectionネスト、高階操作、外部I/O、可変ヒープは`UNSUPPORTED`です。
- LLMが自然言語から生成した意味claimはすべて`candidate`かつ`generatedBy: llm`です。Skillは不足・曖昧な契約意味を補完せず、モデルを書き出す前に人間へ質問します。
