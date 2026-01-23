package com.example.classification_0623.ppg

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
/**
 * 채널별 (timestamp,value) 버퍼. 스냅샷 시 12초 창에 해당하는 **값 리스트**만 반환.
 */
class BatchBuffer {
    private val lock = ReentrantLock()

    private val green = ArrayList<Pair<Long, Float>>()
    private val ir = ArrayList<Pair<Long, Float>>()
    private val red = ArrayList<Pair<Long, Float>>()

    fun addGreen(tsMillis: Long, v: Float) = lock.withLock { green.add(tsMillis to v) }
    fun addIr(tsMillis: Long, v: Float) = lock.withLock { ir.add(tsMillis to v) }
    fun addRed(tsMillis: Long, v: Float) = lock.withLock { red.add(tsMillis to v) }
    fun clear() = lock.withLock { green.clear(); ir.clear(); red.clear() }
    /**
     * [windowMs] 동안의 데이터를 [nowMs] 기준으로 (nowMs - windowMs, nowMs] 구간에서 추출.
     * 반환: Triple(ppg_green, ppg_ir, ppg_red) 값 리스트 + 창 시작·끝 시간
     */
    /**==>>> 아래와같이 수정됨!!
     * [startMs, endMs) 구간의 값들만 추출. 오래된 값(startMs 미만)은 메모리 관리 차 제거.
     */
    fun snapshotRange(startMs: Long, endMs: Long): Snapshot {
        require(endMs > startMs) { "endMs must be greater than startMs" }
        val gVals = ArrayList<Float>()
        val iVals = ArrayList<Float>()
        val rVals = ArrayList<Float>()
        lock.withLock {
            for ((ts, v) in green) if (ts in startMs until endMs) gVals.add(v)
            for ((ts, v) in ir)    if (ts in startMs until endMs) iVals.add(v)
            for ((ts, v) in red)   if (ts in startMs until endMs) rVals.add(v)
            // 오래된 데이터 정리
            green.removeIf { it.first < startMs }
            ir.removeIf { it.first < startMs }
            red.removeIf { it.first < startMs }
        }
        return Snapshot(gVals, iVals, rVals, startMs, endMs)
    }

    data class Snapshot(
        val greenValues: List<Float>,
        val irValues: List<Float>,
        val redValues: List<Float>,
        val windowStartMs: Long,
        val windowEndMs: Long
    )
}