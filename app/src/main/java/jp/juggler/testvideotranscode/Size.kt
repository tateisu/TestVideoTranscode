package jp.juggler.testvideotranscode

import kotlin.math.min

// Android APIのSizeはsetterがないので雑に用意する
// equalsのためにデータクラスにする
data class Size(var w: Int, var h: Int) {
    override fun toString() = "[$w,$h]"

    private val aspect: Float get() = w.toFloat() / h.toFloat()

    /**
     * アスペクト比を維持しつつ上限に合わせた解像度を提案する
     * - 拡大はしない
     */
    fun scaleTo(limitLonger: Int, limitShorter: Int): Size {
        val inSize = this
        // ゼロ除算対策
        if (inSize.w < 1 || inSize.h < 1) {
            return Size(limitLonger, limitShorter)
        }
        val inAspect = inSize.aspect
        // 入力の縦横に合わせて上限を決める
        val outSize = if (inAspect >= 1f) {
            Size(limitLonger, limitShorter)
        } else {
            Size(limitShorter, limitLonger)
        }
        // 縦横比を比較する
        return if (inAspect >= outSize.aspect) {
            // 入力のほうが横長なら横幅基準でスケーリングする
            // 拡大はしない
            val scale = outSize.w.toFloat() / inSize.w.toFloat()
            if (scale >= 1f) inSize else outSize.apply {
                h = min(h, (scale * inSize.h + 0.5f).toInt())
            }
        } else {
            // 入力のほうが縦長なら縦幅基準でスケーリングする
            // 拡大はしない
            val scale = outSize.h.toFloat() / inSize.h.toFloat()
            if (scale >= 1f) inSize else outSize.apply {
                w = min(w, (scale * inSize.w + 0.5f).toInt())
            }
        }
    }
}
