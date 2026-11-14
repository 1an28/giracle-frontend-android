package com.giracle.android.data

import android.os.SystemClock
import android.util.Log

/**
 * 自分の送信が画面に見えるまでの区切りを記録する (#70、D-056)。
 * e2e (`uxTasks` の reply) がこの記録を読み、送信を押してから見えるまでの内訳を出す。
 * 時刻は端末全体で共通の `SystemClock.uptimeMillis()` なので、e2e のプロセスの時刻と引き算できる。
 * 1 回の送信で数行しか出さないので、配布版でも残す。
 */
object SendTrace {
    const val TAG = "GiraclePerf"

    fun mark(phase: String, detail: String = "") {
        Log.i(TAG, "send phase=$phase t=${SystemClock.uptimeMillis()}" + if (detail.isEmpty()) "" else " $detail")
    }
}
