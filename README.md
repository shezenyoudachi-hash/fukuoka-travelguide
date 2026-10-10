# 福岡たびコンシェルジュ（fukuoka-travelguide）

いくつかの質問と自由記述から好みを読み取り、福岡の旅行プランを時間割つきで提案する Web サービスです。
API は Web と（今後の）iOS アプリで共通に使えるようにしています。

## できること

- 8つの質問（日数・同行者・目的・食べたいもの・ペース・移動手段・夜の過ごし方・行動範囲）＋出発日＋自由記述
- 回答とスポットのタグを点数化し、エリアがまとまるように日ごとの時間割を作成
- 出発日から休館日を避け、見頃の季節のスポットを加点
- 自由記述の解釈（行きたい・行きたくない場所、苦手なもの、雨・体力面など）
- スポット間の移動時間を計算し、地図（OpenStreetMap）に表示

## 外部サービスについて

有料の外部 API は使っていません。APIキーや登録も不要です。

- 自由記述の解釈：キーワード辞書（`RuleBasedInterpreter`）
- 移動時間：直線距離からの推定（`EstimatedTravelTimeProvider`）

画面で読み込むのは、地図の画像（OpenStreetMap）とフォント（Google Fonts）だけで、どちらも無料です。

## 起動

Java 21 と Maven が必要です。

```bash
mvn spring-boot:run
# http://localhost:8080 を開く
```

開発時はインメモリの H2、`prod` プロファイルでは PostgreSQL を使います。

```bash
docker compose up --build   # PostgreSQL + アプリ
```

## API

| メソッド | パス | 内容 |
|---|---|---|
| GET | `/api/questions` | 質問の一覧（画面はこれを見て組み立てる） |
| POST | `/api/plans` | 回答・自由記述・出発日からプランを作る |
| GET | `/api/spots` | 登録スポットの一覧（確認用） |

```json
POST /api/plans
{
  "answers": {"days": 2, "who": "friends", "main": ["food", "history"], "eat": ["motsu", "yatai"],
              "pace": "mid", "move": "train", "night": "yatai", "range": "near"},
  "freeText": "太宰府には行きたい。雨なら屋内多めで。",
  "startDate": "2026-10-17"
}
```

## 構成

```
src/main/java/com/fukuokatabi/
  spot/        スポットのエンティティとリポジトリ
  question/    質問の定義
  plan/        プラン作成（点数づけ・日ごとの割り当て・時間割）
  preference/  自由記述の解釈（キーワード辞書）
  travel/      移動時間（距離からの推定）
  config/      設定とキーによる実装の切り替え
  web/         REST API
src/main/resources/
  db/migration/  Flyway のスキーマと初期データ
  static/        Web 画面
```

## スポットデータについて（公開前に必要な作業）

初期データ（`V2__seed_spots.sql`）の座標はおおよその位置で、営業時間・定休日は**未確認**です（`hours_verified = FALSE`）。
公開前に各公式サイトで確認し、修正は新しいマイグレーション（`V3__...sql`）として追加してください。

## 今後の予定

- スポットの追加と営業情報の確認、管理画面
- iOS アプリ（SwiftUI）から同じ API を利用
- 天気予報との連携、プランの保存・共有
