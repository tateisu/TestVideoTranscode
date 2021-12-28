# TestVideoTranscode
既存の動画トランスコーダを使ってみる単純なサンプル

## 使ってみたもの

### com.linkedin.android.litr:litr:1.4.16
- https://github.com/linkedin/LiTr
- LinkedIn で使われている
- 動作は安定している。
- 回転情報はそのまま残る。
- フレームレート変更機能なし。
### com.otaliastudios:transcoder:0.10.4
- https://github.com/natario1/Transcoder
- ShareChat (インド圏のSNSらしい)で使われている
- 回転情報をトランスコード時に解決する。
- フレームレート変更機能あり。
- 進捗コールバックの頻度がやや多いかな…？受け取り側が適当に間引く必要あり
- 入力動画によって安定性はイマイチかな…
- バグを踏んだので投げつける https://github.com/natario1/Transcoder/pull/160
### com.iceteck.silicompressorr:silicompressor:2.2.4
- https://github.com/Tourenathan-G5organisation/SiliCompressor
- 採用例がよくわからない。
- 回転情報をトランスコード時に解決する。
- フレームレート変更機能なし。
- 進捗コールバックが一切ない。
- 中断APIがない。気休めにThread.interrupt() するしかない。
- 出力先をディレクトリまでしか指定できない。中断時の削除を確実に行うには一時フォルダを作る必要がある。
- バグを踏んだので投げつける https://github.com/Tourenathan-G5organisation/SiliCompressor/pull/174
