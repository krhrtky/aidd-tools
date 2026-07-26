# Getting started

## 対応環境

- macOSまたはLinux
- JDK 21
- Node.js 20
- Node.js付属のCorepack
- Python 3.10以降
- Codex Skillsを利用する場合はCodex

pnpm 10.13.1はCorepackがプロジェクトの固定バージョンを選択する。初回取得時はネットワーク接続が必要になる。

## インストール

ソースチェックアウトのルートで実行する。

```sh
./install.sh
```

CLIとSkillはチェックアウトへのsymbolic linkとして登録されるため、インストール後もチェックアウトを移動・削除しない。更新時は同じ場所でGit更新後に`./install.sh`を再実行する。

インストーラは次を行う。

1. Java、Node.js、pnpmのバージョンを確認する。
2. 共通Harness、Kotlin抽出器、TypeScript抽出器をビルドする。
3. 形式仕様のサンプルを使ってCLIをスモーク検証する。
4. `~/.local/bin/`へ2つのCLIを安全なsymbolic linkとして登録する。
5. `~/.codex/skills/`へ2つのAgent Skillを登録する。

既存のファイルや別の場所を指すsymbolic linkは上書きせず、インストールを中止する。同じチェックアウトに対する再実行は安全である。

`~/.local/bin`が`PATH`にない場合は、インストーラが追加すべき設定を表示する。設定後、新しいshellと新しいCodexタスクを開始する。

CLIだけを導入する場合:

```sh
./install.sh --no-skills
```

保存先を変更する場合:

```sh
./install.sh \
  --prefix /opt/aidd \
  --codex-home "$HOME/.codex"
```

すでにビルド済みでlinkだけを修復する場合:

```sh
./install.sh --skip-build
```

## 対象リポジトリで利用する

```sh
cd /path/to/target-repository
mkdir -p .aidd/specs
```

自然言語要求を形式化する場合は、Codexで次のように依頼する。

```text
$aidd-formalize-spec を使い、
docs/requirements.md から .aidd/specs/order/model.jsonld を作成してください。
意味解釈はcandidateのままにしてください。
```

候補モデルをHarnessで検証する。

```sh
aidd-formalize run \
  --model .aidd/specs/order/model.jsonld \
  --out .aidd/specs/order
```

TypeScript実装をバックポートする場合:

```sh
aidd-backport run \
  --repo "$PWD" \
  --language typescript \
  --out .aidd/specs/as-built
```

Kotlin実装をバックポートする場合:

```sh
aidd-backport run \
  --repo "$PWD" \
  --language kotlin \
  --out .aidd/specs/as-built
```

生成された決定的事実を自然言語候補へ変換する場合:

```text
$aidd-backport-spec を使い、
.aidd/specs/as-built/code-facts.json から
model.jsonld と candidate-prose.md を作成してください。
```

既定の探索境界は未承認なので、検証結果が正常でも`PROVISIONAL`、終了コード`3`になる。受理可能なbounded resultにするには、レビュー済みの`bounds.json`を明示する。
