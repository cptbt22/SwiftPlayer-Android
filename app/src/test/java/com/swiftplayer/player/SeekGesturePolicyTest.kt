package com.swiftplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekGesturePolicyTest {

    // 2h 电影：perScreen = 6% × 7200s = 432s（不再被 180s 上限压制）

    @Test
    fun fullScreenOnTwoHourVideoMovesSevenMinutes() {
        val target = SeekGesturePolicy.calculateTarget(
            durationMs = 7_200_000L,
            anchorMs = 3_600_000L,
            deltaPx = 1000f,
            widthPx = 1000f
        )
        assertEquals(4_032_000L, target)
    }

    @Test
    fun shortVideoUsesMinimumTwentySecondsPerScreen() {
        val target = SeekGesturePolicy.calculateTarget(
            durationMs = 100_000L,
            anchorMs = 10_000L,
            deltaPx = 1000f,
            widthPx = 1000f
        )
        assertEquals(30_000L, target)
    }

    @Test
    fun targetIsClampedAtStartAndEnd() {
        assertEquals(
            0L,
            SeekGesturePolicy.calculateTarget(600_000L, 10_000L, -1000f, 1000f)
        )
        assertEquals(
            600_000L,
            SeekGesturePolicy.calculateTarget(600_000L, 590_000L, 1000f, 1000f)
        )
    }

    @Test
    fun zeroDeltaKeepsAnchor() {
        assertEquals(
            120_000L,
            SeekGesturePolicy.calculateTarget(600_000L, 120_000L, 0f, 1080f)
        )
    }

    // ---- v2.5.0 渐进式加速：拖得越远，单位位移跳转的时长越大 ----

    @Test
    fun smallDragIsFinerThanLinear() {
        // 0.1 屏：幂曲线 0.1^1.7 ≈ 0.02，比线性 0.1 更精细
        val delta = SeekGesturePolicy.calculateTarget(
            7_200_000L, 3_600_000L, 100f, 1000f
        ) - 3_600_000L
        assertTrue(delta in 1L..43_200L) // < 线性映射的 43.2s
    }

    @Test
    fun largeDragAcceleratesBeyondLinear() {
        val anchor = 3_600_000L
        val halfScreen = SeekGesturePolicy.calculateTarget(7_200_000L, anchor, 500f, 1000f) - anchor
        val oneScreen = SeekGesturePolicy.calculateTarget(7_200_000L, anchor, 1000f, 1000f) - anchor
        val twoScreens = SeekGesturePolicy.calculateTarget(7_200_000L, anchor, 2000f, 1000f) - anchor

        // 后半屏比前半屏跳得远（幂曲线下 0.5 屏 << 1 屏的一半）
        assertTrue(oneScreen > halfScreen * 2)
        // 第 2 屏的幅度 ≈ 第 1 屏的 3.25 倍（2^1.7）
        assertTrue(twoScreens > oneScreen * 3.2)
        // 1 屏 = 7.2 分钟（2h 片长 6%）
        assertEquals(432_000L, oneScreen)
    }

    @Test
    fun dragBeyondMaxScreensIsClamped() {
        val anchor = 3_600_000L
        val atLimit = SeekGesturePolicy.calculateTarget(7_200_000L, anchor, 2200f, 1000f)
        val beyond = SeekGesturePolicy.calculateTarget(7_200_000L, anchor, 3000f, 1000f)
        assertEquals(atLimit, beyond)
    }

    @Test
    fun accelerationIsSymmetric() {
        val forward = SeekGesturePolicy.calculateTarget(7_200_000L, 3_600_000L, 700f, 1000f)
        val backward = SeekGesturePolicy.calculateTarget(7_200_000L, 3_600_000L, -700f, 1000f)
        val forwardDelta = forward - 3_600_000L
        val backwardDelta = 3_600_000L - backward
        // Float→Long 截断方向不同，允许 1ms 级误差
        assertTrue(kotlin.math.abs(forwardDelta - backwardDelta) <= 1L)
    }
}
