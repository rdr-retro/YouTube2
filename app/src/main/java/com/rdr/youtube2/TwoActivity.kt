package com.rdr.youtube2

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class TwoActivity : AppCompatActivity() {

    private var tapCount = 0
    private var rockyVisible = false
    private var isDancing = false
    private lateinit var bigTwoText: TextView
    private lateinit var rockyImage: ImageView
    private var danceRotationAnimator: ObjectAnimator? = null
    private var danceShiftAnimator: ObjectAnimator? = null
    private var rockyTouchStartX = 0f
    private var rockyTouchStartY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_two)

        bigTwoText = findViewById(R.id.two_big_number)
        rockyImage = findViewById(R.id.two_rocky_image)

        bigTwoText.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startDance()
                }
                MotionEvent.ACTION_UP -> {
                    stopDance()
                    view.performClick()
                    if (!rockyVisible) {
                        tapCount += 1
                        if (tapCount >= ROCKY_TAP_THRESHOLD) {
                            revealRocky()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    stopDance()
                }
            }
            true
        }

        rockyImage.setOnTouchListener { _, event ->
            if (!rockyVisible) return@setOnTouchListener false
            handleRockyTouch(event)
            true
        }
    }

    override fun onStop() {
        stopDance()
        super.onStop()
    }

    private fun revealRocky() {
        rockyVisible = true
        stopDance()
        rockyImage.visibility = View.VISIBLE
        rockyImage.animate().cancel()
        rockyImage.alpha = 1f
        rockyImage.scaleX = 1f
        rockyImage.scaleY = 1f
        rockyImage.rotation = 0f
        rockyImage.translationX = 0f
        rockyImage.translationY = 0f
        bigTwoText.visibility = View.GONE
    }

    private fun handleRockyTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rockyImage.animate().cancel()
                rockyImage.alpha = 1f
                rockyTouchStartX = event.x
                rockyTouchStartY = event.y
                rockyImage.pivotX = event.x
                rockyImage.pivotY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val width = rockyImage.width.toFloat().coerceAtLeast(1f)
                val height = rockyImage.height.toFloat().coerceAtLeast(1f)
                val dx = event.x - rockyTouchStartX
                val dy = event.y - rockyTouchStartY
                val normX = (dx / (width * 0.42f)).coerceIn(-1f, 1f)
                val normY = (dy / (height * 0.42f)).coerceIn(-1f, 1f)

                val stretchX = (1f + normX * 0.36f).coerceIn(0.68f, 1.42f)
                val stretchY = (1f + normY * 0.36f).coerceIn(0.68f, 1.42f)

                rockyImage.scaleX = stretchX
                rockyImage.scaleY = stretchY
                rockyImage.rotation = (normX - normY) * 8f
                rockyImage.translationX = dx * 0.08f
                rockyImage.translationY = dy * 0.08f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetRockyDeformation()
            }
        }
    }

    private fun resetRockyDeformation() {
        rockyImage.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .rotation(0f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(150L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun startDance() {
        if (isDancing) return
        isDancing = true

        danceRotationAnimator = ObjectAnimator.ofFloat(bigTwoText, "rotation", -7f, 7f).apply {
            duration = 92L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }

        danceShiftAnimator = ObjectAnimator.ofFloat(bigTwoText, "translationX", -8f, 8f).apply {
            duration = 110L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopDance() {
        if (!isDancing) return
        isDancing = false
        danceRotationAnimator?.cancel()
        danceRotationAnimator = null
        danceShiftAnimator?.cancel()
        danceShiftAnimator = null
        bigTwoText.animate()
            .rotation(0f)
            .translationX(0f)
            .setDuration(90L)
            .start()
    }

    companion object {
        private const val ROCKY_TAP_THRESHOLD = 10
    }
}
