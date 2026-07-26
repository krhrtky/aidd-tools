# ADR 0001: LLMとHarnessの判定境界

- Status: Accepted
- Date: 2026-07-26

## Context

自然言語の解釈にはLLMが有効だが、同一入力に対する受理判定をLLMへ委ねると再現性と監査性を失う。

## Decision

LLMはAgent Skill内で候補生成、曖昧性抽出、反例説明だけを行う。HarnessはLLMを呼び出さず、構文・型・根拠・ハッシュ・Alloy結果だけで判定する。LLMが生成した主張はHumanDecisionなしに`accepted`へ昇格できない。

## Consequences

自然言語から形式仕様への意味対応は人間承認を要する。CIはHarnessのみで再実行できる。

