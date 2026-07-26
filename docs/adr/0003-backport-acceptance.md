# ADR 0003: 仕様バックポートの自動承認

- Status: Accepted
- Date: 2026-07-26

## Context

LLMの確度や自然言語推論によってコード上のバグを規範仕様へ昇格させてはならない。

## Decision

コンパイラ、構文解析器、OpenAPI/JSON Schemaパーサが直接抽出した事実だけを`accepted/observed`とする。LLMによる業務目的、因果、例外の推論は`candidate`とする。

Kotlin v1はcompiler PSIから直接読める構文事実のみを自動承認する。classpath付きK2 Analysis API意味解決が必要な推論型は`UNSUPPORTED`とし、`--allow-build-tool`指定時も対象Gradleを実行しない。TypeScriptはCompiler APIの構文・型診断にerrorがあれば抽出全体を非成功とする。

Harnessは承認済み事実から`as-built.md`を定型生成する。LLMが生成する読みやすい文章は`candidate-prose.md`へ分離する。

## Consequences

正式なas-built仕様は再現可能になる。豊かな説明文には引き続き人間レビューが必要になる。
