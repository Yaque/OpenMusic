package com.aiocw.openmusic.music

import android.content.Context

/** 简单练习统计（SharedPreferences 持久化） */
object Stats {
    private const val PREF = "openmusic_stats"

    fun recordPianoRound(ctx: Context, score: Int) = bump(ctx, "piano", score)
    fun recordSingRound(ctx: Context, score: Int) = bump(ctx, "sing", score)
    fun recordMelodyRound(ctx: Context, score: Int) = bump(ctx, "melody", score)

    /** 重新计分：清零统计 */
    fun resetPiano(ctx: Context) = reset(ctx, "piano")
    fun resetSing(ctx: Context) = reset(ctx, "sing")
    fun resetMelody(ctx: Context) = reset(ctx, "melody")

    fun resetEar(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .remove("ear_total")
            .remove("ear_correct")
            .remove("ear_best_streak")
            .apply()
    }

    private fun reset(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .remove("${key}_rounds")
            .remove("${key}_sum")
            .remove("${key}_best")
            .apply()
    }

    fun recordEarAnswer(ctx: Context, correct: Boolean, streak: Int) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val total = p.getInt("ear_total", 0) + 1
        val correctN = p.getInt("ear_correct", 0) + (if (correct) 1 else 0)
        val best = maxOf(p.getInt("ear_best_streak", 0), streak)
        p.edit()
            .putInt("ear_total", total)
            .putInt("ear_correct", correctN)
            .putInt("ear_best_streak", best)
            .apply()
    }

    private fun bump(ctx: Context, key: String, score: Int) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val rounds = p.getInt("${key}_rounds", 0) + 1
        val sum = p.getInt("${key}_sum", 0) + score
        val best = maxOf(p.getInt("${key}_best", 0), score)
        p.edit()
            .putInt("${key}_rounds", rounds)
            .putInt("${key}_sum", sum)
            .putInt("${key}_best", best)
            .apply()
    }

    fun pianoRounds(ctx: Context) = ctx.getSharedPreferences(PREF, 0).getInt("piano_rounds", 0)
    fun pianoBest(ctx: Context) = ctx.getSharedPreferences(PREF, 0).getInt("piano_best", 0)
    fun pianoAvg(ctx: Context): Int {
        val p = ctx.getSharedPreferences(PREF, 0)
        val r = p.getInt("piano_rounds", 0)
        return if (r == 0) 0 else p.getInt("piano_sum", 0) / r
    }

    fun singRounds(ctx: Context) = ctx.getSharedPreferences(PREF, 0).getInt("sing_rounds", 0)
    fun singBest(ctx: Context) = ctx.getSharedPreferences(PREF, 0).getInt("sing_best", 0)
    fun singAvg(ctx: Context): Int {
        val p = ctx.getSharedPreferences(PREF, 0)
        val r = p.getInt("sing_rounds", 0)
        return if (r == 0) 0 else p.getInt("sing_sum", 0) / r
    }

    fun earTotal(ctx: Context) = ctx.getSharedPreferences(PREF, 0).getInt("ear_total", 0)
    fun earCorrect(ctx: Context) = ctx.getSharedPreferences(PREF, 0).getInt("ear_correct", 0)
    fun earBestStreak(ctx: Context) = ctx.getSharedPreferences(PREF, 0).getInt("ear_best_streak", 0)

    fun melodyRounds(ctx: Context) = ctx.getSharedPreferences(PREF, 0).getInt("melody_rounds", 0)
    fun melodyBest(ctx: Context) = ctx.getSharedPreferences(PREF, 0).getInt("melody_best", 0)
    fun melodyAvg(ctx: Context): Int {
        val p = ctx.getSharedPreferences(PREF, 0)
        val r = p.getInt("melody_rounds", 0)
        return if (r == 0) 0 else p.getInt("melody_sum", 0) / r
    }
}
