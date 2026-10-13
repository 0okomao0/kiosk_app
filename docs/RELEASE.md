# リリース手順メモ

端末は GitHub Releases の最新リリースを6時間ごとに確認し、新しければ自動更新する。
管理画面「アプリ更新」の「保存して今すぐ確認」で即時確認もできる。

## 更新手順

1. `app/build.gradle.kts` の `versionCode` を +1、`versionName` を上げる（例: 1.0.5 → 1.0.6）。
2. APKをビルドする。
   ```
   JAVA_HOME=$HOME/android-studio/jbr ./gradlew :app:assembleRelease --no-configuration-cache
   ```
   出力: `app/build/outputs/apk/release/app-release.apk`（`keystore.properties` のリリース鍵で署名される）
   版が古いままになることがあるので、`aapt dump badging` で versionName を確認する。
   古ければ `rm -rf app/build` してから再ビルドする。
3. commit して push する。
4. GitHub で新規リリースを作る。
   - タグ: `v` + versionName（例: `v1.0.6`）
   - APK（`.apk`）を1つ添付する
   - 下書き・プレリリースにしない（`/releases/latest` に出なくなる）
5. 端末が自動更新する。更新時は画面が一度再起動し、キオスク固定に戻る。

## 注意

- タグの版 > 端末の versionName でないと更新されない。
- 署名が違うAPKは Android が拒否する。必ず同じリリース鍵で署名すること。鍵を失くすと上書き更新できない。
- 鍵ファイルと `keystore.properties` はGitに入れない（`.gitignore` 済み）。
- R8（難読化）は起動時にクラッシュしたため無効にしている。
- リポジトリは公開なので、APKは誰でも取得できる。秘密情報を入れない。
