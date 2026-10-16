package com.aiocw.openmusic.music

import android.content.Context

/** 全局显示/外观/音域设置（SharedPreferences 持久化，下次打开自动加载） */
object AppSettings {

    /** 键盘标签显示方式 */
    const val LABEL_JIANPU = 0   // 数字简谱 1 2 3 4 5 6 7（低音下加点、高音上加点）
    const val LABEL_SOLFEGE = 1  // 唱名 do re mi fa sol la si（带八度：do4 re4）
    const val LABEL_NAMES = 2    // 音名 C D E F G A B（带八度：C4 D4）
    const val LABEL_ALL = 3      // 三种全部显示（简谱 + 唱名 + 音名）

    /** 外观配色 */
    const val THEME_SYSTEM = 0   // 跟随系统
    const val THEME_LIGHT = 1    // 浅色
    const val THEME_DARK = 2     // 深色

    private const val PREF = "openmusic_settings"
    private const val KEY_LABEL = "label_mode"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_WIDTH = "key_width_dp"
    private const val KEY_HEIGHT = "key_height_dp"

    // 各页面音域（midi，含端点）
    private const val KEY_PIANO_RANGE = "piano_range"
    private const val KEY_EAR_RANGE = "ear_range"
    private const val KEY_SING_RANGE = "sing_range"
    private const val KEY_CURVE_RANGE = "curve_range"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun labelMode(ctx: Context): Int = p(ctx).getInt(KEY_LABEL, LABEL_SOLFEGE)
    fun setLabelMode(ctx: Context, v: Int) = p(ctx).edit().putInt(KEY_LABEL, v).apply()

    fun themeMode(ctx: Context): Int = p(ctx).getInt(KEY_THEME, THEME_SYSTEM)
    fun setThemeMode(ctx: Context, v: Int) = p(ctx).edit().putInt(KEY_THEME, v).apply()

    /** 琴键宽（dp），20–120 */
    fun keyWidthDp(ctx: Context): Float = p(ctx).getFloat(KEY_WIDTH, 34f)
    fun setKeyWidthDp(ctx: Context, v: Float) = p(ctx).edit().putFloat(KEY_WIDTH, v).apply()

    /** 琴键长（dp），20–540 */
    fun keyHeightDp(ctx: Context): Float = p(ctx).getFloat(KEY_HEIGHT, 120f)
    fun setKeyHeightDp(ctx: Context, v: Float) = p(ctx).edit().putFloat(KEY_HEIGHT, v).apply()

    /** 监听音调偏移（半音，负=降 / 正=升，0=不调整）。发声不变，只移动判音准星 */
    fun pianoShift(ctx: Context): Int = p(ctx).getInt("piano_shift", 0)
    fun setPianoShift(ctx: Context, v: Int) = p(ctx).edit().putInt("piano_shift", v).apply()

    fun singShift(ctx: Context): Int = p(ctx).getInt("sing_shift", 0)
    fun setSingShift(ctx: Context, v: Int) = p(ctx).edit().putInt("sing_shift", v).apply()

    /** 模唱出题顺序：0=随机 1=正序 2=倒序 3=正序接倒序 */
    fun singOrder(ctx: Context): Int = p(ctx).getInt("sing_order", 0)
    fun setSingOrder(ctx: Context, v: Int) = p(ctx).edit().putInt("sing_order", v).apply()

    /** 读取音域（默认 C4–C5；曲线页默认全域 A0–C8） */
    fun pianoRange(ctx: Context) = range(ctx, KEY_PIANO_RANGE, 60, 72)
    fun setPianoRange(ctx: Context, s: Int, e: Int) = saveRange(ctx, KEY_PIANO_RANGE, s, e)

    fun earRange(ctx: Context) = range(ctx, KEY_EAR_RANGE, 60, 72)
    fun setEarRange(ctx: Context, s: Int, e: Int) = saveRange(ctx, KEY_EAR_RANGE, s, e)

    fun singRange(ctx: Context) = range(ctx, KEY_SING_RANGE, 60, 72)
    fun setSingRange(ctx: Context, s: Int, e: Int) = saveRange(ctx, KEY_SING_RANGE, s, e)

    fun curveRange(ctx: Context) = range(ctx, KEY_CURVE_RANGE, 21, 108)
    fun setCurveRange(ctx: Context, s: Int, e: Int) = saveRange(ctx, KEY_CURVE_RANGE, s, e)

    /**
     * 一次性迁移：修复早期版本被写坏的音域存储，恢复默认（C4–C5）。
     * 仅首次生效，之后用户的选择照常记忆。
     */
    fun migrateOnce(ctx: Context) {
        val p = p(ctx)
        if (p.getBoolean("migrated_v2", false)) return
        p.edit()
            .remove(KEY_PIANO_RANGE)
            .remove(KEY_EAR_RANGE)
            .remove(KEY_SING_RANGE)
            .remove("piano_transpose")
            .remove("sing_transpose")
            .putBoolean("migrated_v2", true)
            .apply()
    }

    private fun range(ctx: Context, key: String, defStart: Int, defEnd: Int): IntArray {
        val parts = p(ctx).getString(key, null)?.split(",") ?: return intArrayOf(defStart, defEnd)
        val s = parts.getOrNull(0)?.toIntOrNull() ?: defStart
        val e = parts.getOrNull(1)?.toIntOrNull() ?: defEnd
        return if (s <= e) intArrayOf(s, e) else intArrayOf(e, s)
    }

    private fun saveRange(ctx: Context, key: String, s: Int, e: Int) =
        p(ctx).edit().putString(key, "$s,$e").apply()
}
