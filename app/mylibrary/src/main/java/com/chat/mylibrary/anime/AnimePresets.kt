package com.chat.mylibrary.anime

import android.view.ViewGroup

// —— 基础预设 ——

/** 淡入：alpha 0 → 1。 */
fun Anime.fadeIn() = alpha(to = 1f, from = 0f)

/** 淡出：alpha 1 → 0。 */
fun Anime.fadeOut() = alpha(to = 0f, from = 1f)

/** 水平抖动；[distance] 为最大振幅 (px)。 */
fun Anime.shake(distance: Float = 25f) = keyframes(
    "translationX",
    0f, distance, -distance, distance, -distance,
    distance * .6f, -distance * .6f, distance * .24f, -distance * .24f, 0f
)

/** 心跳脉冲；[peak] 为放大峰值。 */
fun Anime.pulse(peak: Float = 1.1f) =
    keyframes("scaleX", 1f, peak, 1f).keyframes("scaleY", 1f, peak, 1f)

// —— Attention ——

/** 竖向小弹跳，吸引注意；建议 duration 700~1000ms。 */
fun Anime.bounce() = keyframes("translationY", 0f, 0f, -30f, 0f, -15f, 0f, 0f)

/** 闪烁：alpha 在 0/1 之间交替两次。 */
fun Anime.flash() = keyframes("alpha", 1f, 0f, 1f, 0f, 1f)

/** 橡皮筋：横向拉伸 + 竖向回弹的弹性效果。 */
fun Anime.rubberBand() =
    keyframes("scaleX", 1f, 1.25f, .75f, 1.15f, 1f)
        .keyframes("scaleY", 1f, .75f, 1.25f, .85f, 1f)

/** 钟摆式左右晃动 rotation。 */
fun Anime.swing() = keyframes("rotation", 0f, 10f, -10f, 6f, -6f, 3f, -3f, 0f)

/** Tada：缩放 + 抖动 rotation 的庆祝效果。 */
fun Anime.tada() =
    keyframes("scaleX", 1f, .9f, .9f, 1.1f, 1.1f, 1.1f, 1.1f, 1.1f, 1.1f, 1f)
        .keyframes("scaleY", 1f, .9f, .9f, 1.1f, 1.1f, 1.1f, 1.1f, 1.1f, 1.1f, 1f)
        .keyframes("rotation", 0f, -3f, -3f, 3f, -3f, 3f, -3f, 3f, -3f, 0f)

/** 摇晃：横向位移按 view.width 比例 + rotation 摆动。 */
fun Anime.wobble() =
    dynamic("translationX") { v ->
        val one = v.width / 100f
        floatArrayOf(0f, -25f * one, 20f * one, -15f * one, 10f * one, -5f * one, 0f, 0f)
    }.keyframes("rotation", 0f, -5f, 3f, -3f, 2f, -1f, 0f)

/** 站立：以底部中点为枢轴的前后摆动 rotationX。 */
fun Anime.standUp() = pivot(
    { v -> (v.width - v.paddingLeft - v.paddingRight) / 2f + v.paddingLeft },
    { v -> (v.height - v.paddingBottom).toFloat() }
).keyframes("rotationX", 55f, -30f, 15f, -15f, 0f)

/** 招手：以底部中点为枢轴的 rotation 摆动。 */
fun Anime.wave() = pivot(
    { v -> (v.width - v.paddingLeft - v.paddingRight) / 2f + v.paddingLeft },
    { v -> (v.height - v.paddingBottom).toFloat() }
).keyframes("rotation", 12f, -12f, 3f, -3f, 0f)

// —— Bouncing entrances ——

/** 弹性入场：小→稍大→收回到正常。 */
fun Anime.bounceIn() = keyframes("alpha", 0f, 1f, 1f, 1f)
    .keyframes("scaleX", .3f, 1.05f, .9f, 1f)
    .keyframes("scaleY", .3f, 1.05f, .9f, 1f)

/** 从上方弹入到原位。 */
fun Anime.bounceInDown() = keyframes("alpha", 0f, 1f, 1f, 1f)
    .dynamic("translationY") { v -> floatArrayOf(-v.height.toFloat(), 30f, -10f, 0f) }

/** 从左方弹入到原位。 */
fun Anime.bounceInLeft() = keyframes("alpha", 0f, 1f, 1f, 1f)
    .dynamic("translationX") { v -> floatArrayOf(-v.width.toFloat(), 30f, -10f, 0f) }

/** 从右方弹入到原位。 */
fun Anime.bounceInRight() = keyframes("alpha", 0f, 1f, 1f, 1f)
    .dynamic("translationX") { v -> floatArrayOf((v.measuredWidth + v.width).toFloat(), -30f, 10f, 0f) }

/** 从下方弹入到原位。 */
fun Anime.bounceInUp() = keyframes("alpha", 0f, 1f, 1f, 1f)
    .dynamic("translationY") { v -> floatArrayOf(v.measuredHeight.toFloat(), -30f, 10f, 0f) }

// —— Fading entrances ——

/** 自上方淡入：alpha 0→1，从 -height/4 处下滑。 */
fun Anime.fadeInDown() = keyframes("alpha", 0f, 1f)
    .dynamic("translationY") { v -> floatArrayOf(-v.height / 4f, 0f) }

/** 自左方淡入：alpha 0→1，从 -width/4 处右移。 */
fun Anime.fadeInLeft() = keyframes("alpha", 0f, 1f)
    .dynamic("translationX") { v -> floatArrayOf(-v.width / 4f, 0f) }

/** 自右方淡入：alpha 0→1，从 +width/4 处左移。 */
fun Anime.fadeInRight() = keyframes("alpha", 0f, 1f)
    .dynamic("translationX") { v -> floatArrayOf(v.width / 4f, 0f) }

/** 自下方淡入：alpha 0→1，从 +height/4 处上移。 */
fun Anime.fadeInUp() = keyframes("alpha", 0f, 1f)
    .dynamic("translationY") { v -> floatArrayOf(v.height / 4f, 0f) }

// —— Fading exits ——

/** 向下方淡出：alpha 1→0，下移 height/4。 */
fun Anime.fadeOutDown() = keyframes("alpha", 1f, 0f)
    .dynamic("translationY") { v -> floatArrayOf(0f, v.height / 4f) }

/** 向左方淡出：alpha 1→0，左移 width/4。 */
fun Anime.fadeOutLeft() = keyframes("alpha", 1f, 0f)
    .dynamic("translationX") { v -> floatArrayOf(0f, -v.width / 4f) }

/** 向右方淡出：alpha 1→0，右移 width/4。 */
fun Anime.fadeOutRight() = keyframes("alpha", 1f, 0f)
    .dynamic("translationX") { v -> floatArrayOf(0f, v.width / 4f) }

/** 向上方淡出：alpha 1→0，上移 height/4。 */
fun Anime.fadeOutUp() = keyframes("alpha", 1f, 0f)
    .dynamic("translationY") { v -> floatArrayOf(0f, -v.height / 4f) }

// —— Flippers ——

/** 沿 X 轴 3D 翻入：rotationX 90→0，alpha 渐入。 */
fun Anime.flipInX() = keyframes("rotationX", 90f, -15f, 15f, 0f)
    .keyframes("alpha", .25f, .5f, .75f, 1f)

/** 沿 Y 轴 3D 翻入：rotationY 90→0，alpha 渐入。 */
fun Anime.flipInY() = keyframes("rotationY", 90f, -15f, 15f, 0f)
    .keyframes("alpha", .25f, .5f, .75f, 1f)

/** 沿 X 轴 3D 翻出：rotationX 0→90，alpha 渐出。 */
fun Anime.flipOutX() = keyframes("rotationX", 0f, 90f).keyframes("alpha", 1f, 0f)

/** 沿 Y 轴 3D 翻出：rotationY 0→90，alpha 渐出。 */
fun Anime.flipOutY() = keyframes("rotationY", 0f, 90f).keyframes("alpha", 1f, 0f)

// —— Rotating entrances ——

/** 旋入：rotation -200→0，alpha 渐入，绕中心。 */
fun Anime.rotateIn() = keyframes("rotation", -200f, 0f).keyframes("alpha", 0f, 1f)

/** 旋入：以左下为枢轴，rotation -90→0。 */
fun Anime.rotateInDownLeft() = pivot(
    { v -> v.paddingLeft.toFloat() },
    { v -> (v.height - v.paddingBottom).toFloat() }
).keyframes("rotation", -90f, 0f).keyframes("alpha", 0f, 1f)

/** 旋入：以右下为枢轴，rotation 90→0。 */
fun Anime.rotateInDownRight() = pivot(
    { v -> (v.width - v.paddingRight).toFloat() },
    { v -> (v.height - v.paddingBottom).toFloat() }
).keyframes("rotation", 90f, 0f).keyframes("alpha", 0f, 1f)

/** 旋入:以左下为枢轴，rotation 90→0。 */
fun Anime.rotateInUpLeft() = pivot(
    { v -> v.paddingLeft.toFloat() },
    { v -> (v.height - v.paddingBottom).toFloat() }
).keyframes("rotation", 90f, 0f).keyframes("alpha", 0f, 1f)

/** 旋入：以右下为枢轴，rotation -90→0。 */
fun Anime.rotateInUpRight() = pivot(
    { v -> (v.width - v.paddingRight).toFloat() },
    { v -> (v.height - v.paddingBottom).toFloat() }
).keyframes("rotation", -90f, 0f).keyframes("alpha", 0f, 1f)

// —— Rotating exits ——

/** 旋出：rotation 0→200，alpha 渐出，绕中心。 */
fun Anime.rotateOut() = keyframes("alpha", 1f, 0f).keyframes("rotation", 0f, 200f)

/** 旋出：以左下为枢轴，rotation 0→90。 */
fun Anime.rotateOutDownLeft() = pivot(
    { v -> v.paddingLeft.toFloat() },
    { v -> (v.height - v.paddingBottom).toFloat() }
).keyframes("alpha", 1f, 0f).keyframes("rotation", 0f, 90f)

/** 旋出：以右下为枢轴，rotation 0→-90。 */
fun Anime.rotateOutDownRight() = pivot(
    { v -> (v.width - v.paddingRight).toFloat() },
    { v -> (v.height - v.paddingBottom).toFloat() }
).keyframes("alpha", 1f, 0f).keyframes("rotation", 0f, -90f)

/** 旋出：以左下为枢轴，rotation 0→-90。 */
fun Anime.rotateOutUpLeft() = pivot(
    { v -> v.paddingLeft.toFloat() },
    { v -> (v.height - v.paddingBottom).toFloat() }
).keyframes("alpha", 1f, 0f).keyframes("rotation", 0f, -90f)

/** 旋出：以右下为枢轴，rotation 0→90。 */
fun Anime.rotateOutUpRight() = pivot(
    { v -> (v.width - v.paddingRight).toFloat() },
    { v -> (v.height - v.paddingBottom).toFloat() }
).keyframes("alpha", 1f, 0f).keyframes("rotation", 0f, 90f)

// —— Sliders ——

/** 从屏幕左侧滑入到原位。 */
fun Anime.slideInLeft() = keyframes("alpha", 0f, 1f)
    .dynamic("translationX") { v ->
        val parent = v.parent as? ViewGroup ?: return@dynamic floatArrayOf(-v.width.toFloat(), 0f)
        floatArrayOf(-(parent.width - v.left).toFloat(), 0f)
    }

/** 从屏幕右侧滑入到原位。 */
fun Anime.slideInRight() = keyframes("alpha", 0f, 1f)
    .dynamic("translationX") { v ->
        val parent = v.parent as? ViewGroup ?: return@dynamic floatArrayOf(v.width.toFloat(), 0f)
        floatArrayOf((parent.width - v.left).toFloat(), 0f)
    }

/** 从屏幕下方滑入到原位。 */
fun Anime.slideInUp() = keyframes("alpha", 0f, 1f)
    .dynamic("translationY") { v ->
        val parent = v.parent as? ViewGroup ?: return@dynamic floatArrayOf(v.height.toFloat(), 0f)
        floatArrayOf((parent.height - v.top).toFloat(), 0f)
    }

/** 从屏幕上方滑入到原位。 */
fun Anime.slideInDown() = keyframes("alpha", 0f, 1f)
    .dynamic("translationY") { v -> floatArrayOf(-(v.top + v.height).toFloat(), 0f) }

/** 向左滑出屏幕。 */
fun Anime.slideOutLeft() = keyframes("alpha", 1f, 0f)
    .dynamic("translationX") { v -> floatArrayOf(0f, -v.right.toFloat()) }

/** 向右滑出屏幕。 */
fun Anime.slideOutRight() = keyframes("alpha", 1f, 0f)
    .dynamic("translationX") { v ->
        val parent = v.parent as? ViewGroup ?: return@dynamic floatArrayOf(0f, v.width.toFloat())
        floatArrayOf(0f, (parent.width - v.left).toFloat())
    }

/** 向上滑出屏幕。 */
fun Anime.slideOutUp() = keyframes("alpha", 1f, 0f)
    .dynamic("translationY") { v -> floatArrayOf(0f, -v.bottom.toFloat()) }

/** 向下滑出屏幕。 */
fun Anime.slideOutDown() = keyframes("alpha", 1f, 0f)
    .dynamic("translationY") { v ->
        val parent = v.parent as? ViewGroup ?: return@dynamic floatArrayOf(0f, v.height.toFloat())
        floatArrayOf(0f, (parent.height - v.top).toFloat())
    }

// —— Specials ——

/** 自上方坠入；配合 [Interpolators.BOUNCE]，duration 建议 ≥ 700ms。 */
fun Anime.dropOut() = keyframes("alpha", 0f, 1f)
    .dynamic("translationY") { v -> floatArrayOf(-(v.top + v.height).toFloat(), 0f) }

/** 从大缩到正常并淡入；配合 [Interpolators.DECELERATE] 近似 QuintEaseOut。 */
fun Anime.landing() = keyframes("scaleX", 1.5f, 1f).keyframes("scaleY", 1.5f, 1f).keyframes("alpha", 0f, 1f)

/** 从正常放大并淡出；配合 [Interpolators.ACCELERATE]。 */
fun Anime.takingOff() = keyframes("scaleX", 1f, 1.5f).keyframes("scaleY", 1f, 1.5f).keyframes("alpha", 1f, 0f)

/** 合页脱落：以左上为枢轴反复倾斜后掉落淡出；duration 建议 ≥ 1300ms。 */
fun Anime.hinge() = pivot({ v -> v.paddingLeft.toFloat() }, { v -> v.paddingTop.toFloat() })
    .keyframes("rotation", 0f, 80f, 60f, 80f, 60f, 60f)
    .keyframes("translationY", 0f, 0f, 0f, 0f, 0f, 700f)
    .keyframes("alpha", 1f, 1f, 1f, 1f, 1f, 0f)

/** 滚入：自左侧滚动入场并旋转 120 度。 */
fun Anime.rollIn() = keyframes("alpha", 0f, 1f)
    .dynamic("translationX") { v -> floatArrayOf(-(v.width - v.paddingLeft - v.paddingRight).toFloat(), 0f) }
    .keyframes("rotation", -120f, 0f)

/** 滚出：向右滚动出场并旋转 120 度。 */
fun Anime.rollOut() = keyframes("alpha", 1f, 0f)
    .dynamic("translationX") { v -> floatArrayOf(0f, v.width.toFloat()) }
    .keyframes("rotation", 0f, 120f)

// —— Zooming entrances ——

/** 中心缩放入场：scale .45→1，alpha 0→1。 */
fun Anime.zoomIn() = keyframes("scaleX", .45f, 1f).keyframes("scaleY", .45f, 1f).keyframes("alpha", 0f, 1f)

/** 自上方缩放入场。 */
fun Anime.zoomInDown() = keyframes("scaleX", .1f, .475f, 1f)
    .keyframes("scaleY", .1f, .475f, 1f)
    .dynamic("translationY") { v -> floatArrayOf(-v.bottom.toFloat(), 60f, 0f) }
    .keyframes("alpha", 0f, 1f, 1f)

/** 自左方缩放入场。 */
fun Anime.zoomInLeft() = keyframes("scaleX", .1f, .475f, 1f)
    .keyframes("scaleY", .1f, .475f, 1f)
    .dynamic("translationX") { v -> floatArrayOf(-v.right.toFloat(), 48f, 0f) }
    .keyframes("alpha", 0f, 1f, 1f)

/** 自右方缩放入场。 */
fun Anime.zoomInRight() = keyframes("scaleX", .1f, .475f, 1f)
    .keyframes("scaleY", .1f, .475f, 1f)
    .dynamic("translationX") { v -> floatArrayOf((v.width + v.paddingRight).toFloat(), -48f, 0f) }
    .keyframes("alpha", 0f, 1f, 1f)

/** 自下方缩放入场。 */
fun Anime.zoomInUp() = keyframes("alpha", 0f, 1f, 1f)
    .keyframes("scaleX", .1f, .475f, 1f)
    .keyframes("scaleY", .1f, .475f, 1f)
    .dynamic("translationY") { v ->
        val parent = v.parent as? ViewGroup ?: return@dynamic floatArrayOf(v.height.toFloat(), -60f, 0f)
        floatArrayOf((parent.height - v.top).toFloat(), -60f, 0f)
    }

// —— Zooming exits ——

/** 中心缩小出场：scale 1→.3→0，alpha 渐出。 */
fun Anime.zoomOut() = keyframes("alpha", 1f, 0f, 0f)
    .keyframes("scaleX", 1f, .3f, 0f)
    .keyframes("scaleY", 1f, .3f, 0f)

/** 向下方缩小出场。 */
fun Anime.zoomOutDown() = keyframes("alpha", 1f, 1f, 0f)
    .keyframes("scaleX", 1f, .475f, .1f)
    .keyframes("scaleY", 1f, .475f, .1f)
    .dynamic("translationY") { v ->
        val parent = v.parent as? ViewGroup ?: return@dynamic floatArrayOf(0f, -60f, v.height.toFloat())
        floatArrayOf(0f, -60f, (parent.height - v.top).toFloat())
    }

/** 向左方缩小出场。 */
fun Anime.zoomOutLeft() = keyframes("alpha", 1f, 1f, 0f)
    .keyframes("scaleX", 1f, .475f, .1f)
    .keyframes("scaleY", 1f, .475f, .1f)
    .dynamic("translationX") { v -> floatArrayOf(0f, 42f, -v.right.toFloat()) }

/** 向右方缩小出场。 */
fun Anime.zoomOutRight() = keyframes("alpha", 1f, 1f, 0f)
    .keyframes("scaleX", 1f, .475f, .1f)
    .keyframes("scaleY", 1f, .475f, .1f)
    .dynamic("translationX") { v ->
        val parent = v.parent as? ViewGroup ?: return@dynamic floatArrayOf(0f, -42f, v.width.toFloat())
        floatArrayOf(0f, -42f, (parent.width - parent.left).toFloat())
    }

/** 向上方缩小出场。 */
fun Anime.zoomOutUp() = keyframes("alpha", 1f, 1f, 0f)
    .keyframes("scaleX", 1f, .475f, .1f)
    .keyframes("scaleY", 1f, .475f, .1f)
    .dynamic("translationY") { v -> floatArrayOf(0f, 60f, -v.bottom.toFloat()) }
