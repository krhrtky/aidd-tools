# ADR 0005: Candidate契約とAccepted仕様の検査境界

- Status: Accepted
- Date: 2026-07-26

## Context

自然言語からLLMが生成した契約候補は、人間が意味を承認する前にも矛盾、充足不能、結果の非一意性を検査したい。一方、候補をaccepted仕様と同じ結果として扱うと、未承認の解釈が保証へ混入する。

純粋関数のコード生成へ進むには、引数、結果、事前条件、事後条件、明示エラー、全域性を機械可読にし、成功入力で結果が一意になることも確認する必要がある。

## Decision

JSON-LD schema 1.1へ純粋関数契約を追加する。v1の生成可能サブセットは`Int`、`Bool`、制限付き`String`、Enum、非ネストのSet/Listとし、Operationの入力、単一結果、エラー、Contractの全域性を明示する。成功入力では事後条件を満たす結果がちょうど1つ存在し、エラー条件は相互排他的でなければならない。全域契約では成功またはエラーが全入力を被覆する。

candidate探索とaccepted検証を別コマンドにする。`explore`はacceptedを前提、candidateを探索対象とし、rejectedを除外してAlloy有限検査を行う。candidateを含む結果は、探索境界が承認済みでも常に`PROVISIONAL`とし、有限検査の実結果を`boundedOutcome`へ記録する。`check`と`run`のaccepted検証の意味は変更しない。

LLMは自然言語から候補を抽出するが、Harnessの受理判定には参加しない。必要な契約意味が曖昧、不足、矛盾している場合、Skillは`model.jsonld`を書き出す前に人間へ質問する。全LLM意味claimは`candidate`かつ`generatedBy: llm`とし、`stated`、`derived`、`assumed`および原文span/hashを記録する。

## Alternatives

1. candidateを検査せず、人間承認後に`check`する。既存コマンドを変更せずに済むが、矛盾の発見が承認後まで遅れるため採用しない。
2. candidateを`check`へ含め、成功時はacceptedと同じstatusを返す。コマンド数は増えないが、未承認の意味を保証と誤認できるため採用しない。
3. LLMに候補の整合性を判定させる。Alloy変換は不要だが、判定が再現可能な機械証拠にならず、ADR 0001の境界に反するため採用しない。

## Rationale

独立した`explore`と常時`PROVISIONAL`を組み合わせると、「承認前に反証すること」と「承認済み保証へ混入させないこと」を同時に満たす。accepted専用経路の後方互換性も維持できる。Harnessだけが有限検査を判定するため、同一モデル・境界・ツールバージョンから同じ受理結果を再生成できる。

## Consequences

肯定的な結果として、人間承認前に仕様候補を反証でき、accepted専用の検証結果と混在しない。

否定的な結果として、利用者は`PROVISIONAL`と`boundedOutcome`の両方を解釈する必要があり、CLIと成果物が1経路増える。Alloy検査は明示された有限境界に限られ、自然言語から形式契約への意味対応や業務妥当性を証明しない。

正規表現、文字列連結・長さ、除算・剰余、Collectionネスト、高階Collection操作、外部I/O、可変ヒープは初版の対象外とし、近似せず`UNSUPPORTED`で停止する。

## References

- [ADR 0001: LLMとHarnessの判定境界](0001-llm-harness-boundary.md)
- [ADR 0002: JSON-LD Canonical ModelとAlloy 6](0002-jsonld-alloy-model.md)
- [Canonical model reference](../../skills/aidd-formalize-spec/references/canonical-model.md)
