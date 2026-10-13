# リリース手順メモ

端末は GitHub Releases の最新リリースを6時間ごとに確認し、新しければ自動更新する。
管理画面「アプリ更新」の「保存して今すぐ確認」で即時確認もできる。

## 通常の更新手順

1. `app/build.gradle.kts` の `versionCode` を +1、`versionName` を上げる（例: 1.0.5 → 1.0.6）。
2. APKをビルドする。
   ```
   JAVA_HOME=$HOME/android-studio/jbr ./gradlew clean :app:assembleDebug --no-configuration-cache
   ```
   出力: `app/build/outputs/apk/debug/app-debug.apk`
   （版が古いままになることがあるので `aapt dump badging` で versionName を確認する）
3. commit して push する。
4. GitHub で新規リリースを作る。
   - タグ: `v` + versionName（例: `v1.0.6`）
   - APK（`.apk`）を1つ添付する
   - 下書き・プレリリースにしない（`/releases/latest` に出なくなる）
5. 端末が自動更新する。更新時は画面が一度再起動し、キオスク固定に戻る。

注意:
- タグの版 > 端末の versionName でないと更新されない。
- 署名が違うAPKは Android が拒否する。ビルドPCを変えるときは同じ鍵を使うこと。
- リポジトリは公開なので、APKは誰でも取得できる。秘密情報を入れない。

## リリース用の署名鍵を作る

鍵は一度作って、紛失しないよう別の場所にバックアップする（紛失すると上書き更新できない）。
鍵ファイルとパスワードはGitに入れない（`.gitignore` 済み: `*.jks`, `keystore.properties`）。

1. 鍵を作る（リポジトリの外に置く）。
   ```
   mkdir -p ~/keys
   $HOME/android-studio/jbr/bin/keytool -genkeypair -v \
     -keystore ~/keys/kioskapp-release.jks \
     -alias kioskapp -keyalg RSA -keysize 2048 -validity 10000
   ```
2. `keystore.properties` をプロジェクト直下に作る（Git管理外）。
   ```
   storeFile=/home/<ユーザー>/keys/kioskapp-release.jks
   storePassword=...
   keyAlias=kioskapp
   keyPassword=...
   ```
3. `app/build.gradle.kts` に signingConfig を追加し、release ビルドで使う。
4. `./gradlew :app:assembleRelease` でビルドする。

### 鍵を切り替えるときの注意（重要）

今の端末のアプリはデバッグ鍵で署名されている。別の鍵のAPKは上書きできないため、
切り替えるには一度アンインストールが必要で、Device Owner も外れる。
その場合は USB で再設定する。
```
adb shell dpm set-device-owner com.nagifans.kioskapp/.AdminReceiver
```
切り替えるなら、早いうちに1回だけ行う。
