# 温湿度の受信サーバー

KioskAppが1分ごとにPOSTする温湿度を保持し、HomebridgeやScriptableから読めるようにする小さなサーバーです。Node.js 18以上で動き、依存パッケージはありません。

## 起動

```sh
TOKEN="$(openssl rand -hex 24)" node sensor-receiver.js
```

| 環境変数 | 既定値 | 説明 |
|---|---|---|
| `TOKEN` | 必須(16文字以上) | POSTとGETの両方で必要な Bearer トークン |
| `PORT` | 8787 | 待ち受けポート |
| `HOST` | 127.0.0.1 | 待ち受けアドレス。TLSはリバースプロキシに任せる |
| `DATA_FILE` | ./sensors.json | 最新値の保存先 |
| `MAX_AGE_SEC` | 900 | これより古い値は 503 を返す |

systemdなどで常駐させ、nginxやCaddyでHTTPS化して公開してください。アプリは `https://` のURLにしか送信しません。

## API

- `POST /ingest`: アプリからの送信先です。
- `GET /sensors`: 全センサーの最新値を返します。
- `GET /sensors/<id>`: `{"temperature":25.8,"humidity":38,...}` を返します。`<id>` はMACアドレス末尾4桁です。

いずれも `Authorization: Bearer <TOKEN>` が必要です。

## アプリ側の設定

管理画面(`http://<端末IP>:8080`)の「温湿度の外部送信」で、URLに `https://<ドメイン>/ingest`、トークンに `TOKEN` の値を入れて有効にします。

## HomeKit (Homebridge)

`homebridge-http-temperature-humidity` を入れ、`config.json` の `accessories` に追加します。

```json
{
  "accessory": "HttpTemphum",
  "name": "リビング",
  "url": "http://127.0.0.1:8787/sensors/ABCD",
  "httpMethod": "GET",
  "humidity": true,
  "cacheExpiration": 60,
  "headers": { "Authorization": "Bearer <TOKEN>" }
}
```

`ABCD` は管理画面に表示されるセンサーIDに置き換えてください。センサーごとにアクセサリを追加します。
