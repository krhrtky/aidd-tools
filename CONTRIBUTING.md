# Contributing

## 情報の置き場所

変更時の情報は、読み手が必要とする場所へ分離する。

| 場所 | 記録する内容 | 検証方法 |
|---|---|---|
| プロダクションコード | How: 処理をどのように実現するか | コンパイル、静的検証、コードレビュー |
| テストコード | What: 外部から観測できる振る舞い | 振る舞いを表すテスト名、assertion |
| コミットログ | Why: なぜ変更が必要か | `Why:`段落をcommit-msg hookで検査 |
| コードコメント | Why not: 採用しなかった自然な選択肢と理由 | `WHY-NOT:`接頭辞をpre-commitで検査 |

プロダクションコードは、名前、型、関数分割、データ構造によってHowを表現する。要求や期待結果はテストへ置き、変更理由はコミットログへ置く。同じ説明を複数箇所へ複製しない。

### テスト

テスト名は入力、操作、観測結果のうち必要な要素を含め、失敗時に壊れた振る舞いが分かる名前にする。

```kotlin
@Test
fun `unapproved bounds keep verification provisional`() = ...
```

実装関数名だけの`test("charge")`や`fun testParser()`は使用しない。

### コードコメント

コメントはコードから読み取れない制約、特に自然に見える代替案を採用しなかった理由に限定する。

```kotlin
// WHY-NOT: CWDからの探索は未信頼リポジトリのコードを実行し得るため使用しない。
```

処理内容を言い換えるコメントは、名前または関数分割へ置き換える。公開APIの利用者向け契約は仕様・型・テストへ置く。

### コミットログ

subjectは変更を短く表し、本文に1行以上の`Why:`段落を必須とする。

```text
fix: reject unresolved extractor types

Why: incomplete semantic information must not be promoted to accepted facts.
```

## コミット手順

初回だけhookを有効化する。

```sh
scripts/install-git-hooks.sh
```

通常のコミットでは次の順序が自動実行される。

1. `scripts/verify-before-commit.sh`がコミットログ以外を検証する。
2. 検証が1件でも失敗した場合はコミットを中止する。
3. `commit-msg` hookが`Why:`段落を検証する。
4. 両方が成功した場合だけコミットを作成する。

