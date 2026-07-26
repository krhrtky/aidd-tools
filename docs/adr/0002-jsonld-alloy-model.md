# ADR 0002: JSON-LD Canonical ModelとAlloy 6

- Status: Accepted
- Date: 2026-07-26

## Context

要求、形式制約、コード、テスト、根拠をGit上で追跡し、仕様矛盾と状態遷移を機械検査する必要がある。

## Decision

固定ローカルコンテキストを持つJSON-LD v1を唯一の意味モデルとし、型付き制約ASTからAlloy 6.2へ決定的に変換する。外部JSON-LDコンテキストは取得しない。

Alloy結果は有限スコープの結果としてのみ表示する。探索境界が未承認なら、結果にかかわらず`PROVISIONAL`とする。
承認済み境界には`approvedBy`と`urn:aidd:`形式の`decisionId`を必須とする。

基礎制約はAlloyの`fact`として`run`で充足可能性を検査し、`Invariant`は`assert`として`check`で反例を探索する。`check`が存在しないSAT結果は`NO_COUNTEREXAMPLE_WITHIN_SCOPE`へ昇格させない。

状態遷移があるモデルには、現在状態、初期候補、明示遷移またはstutterからなる時間トレースを生成する。ASTの`current`で現在状態を参照し、`always`、`eventually`等と組み合わせる。

## Consequences

グラフDBなしで差分と監査が可能になる。有限境界を超えた一般的正しさは保証しない。
