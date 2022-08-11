package org.wikipedia.edit

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.ContentInfo
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.core.view.ViewCompat
import org.wikipedia.R
import org.wikipedia.util.DimenUtil
import org.wikipedia.util.ResourceUtil
import org.wikipedia.util.log.L

/**
 *
 */
@SuppressLint("AppCompatCustomView")
open class SyntaxHighlightableEditText : EditText {

    private var prevLineCount = -1
    private var prevLineHeight = 0
    private val lineNumberPaint = TextPaint()
    private val isRtl: Boolean
    private val paddingWithoutLineNumbers = DimenUtil.roundedDpToPx(8f)
    private val paddingWithLineNumbers = DimenUtil.roundedDpToPx(32f)
    private val lineNumberGapWidth = DimenUtil.roundedDpToPx(6f)
    private var allowScrollToCursor = true

    lateinit var scrollView: View
    private lateinit var actualLineFromRenderedLine: IntArray

    var inputConnection: InputConnection? = null

    var showLineNumbers = true
        set(value) {
            field = value
            applyPaddingForLineNumbers()
        }

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle)

    init {
        // The MIME type(s) need to be set for onReceiveContent() to be called.
        ViewCompat.setOnReceiveContentListener(this, arrayOf("text/*"), null)
        isRtl = resources.configuration.layoutDirection == LAYOUT_DIRECTION_RTL
        applyPaddingForLineNumbers()

        lineNumberPaint.isAntiAlias = true
        lineNumberPaint.textAlign = if (isRtl) Paint.Align.LEFT else Paint.Align.RIGHT
        lineNumberPaint.textSize = this.textSize * 0.8f
        lineNumberPaint.color = ResourceUtil.getThemedColor(context, R.attr.material_theme_de_emphasised_color)
    }

    fun enqueueNoScrollingLayoutChange() {
        allowScrollToCursor = false
        postDelayed({
            if (isAttachedToWindow) {
                allowScrollToCursor = true
            }
        }, 1000)
    }

    override fun bringPointIntoView(offset: Int): Boolean {
        if (!allowScrollToCursor) {
            return false
        }
        return super.bringPointIntoView(offset)
    }

    override fun onDraw(canvas: Canvas?) {
        if (prevLineCount != lineCount) {
            prevLineHeight = lineHeight
            prevLineCount = lineCount
            computeLineNumbers(prevLineCount, layout, text.toString())
        }

        if (showLineNumbers && layout != null) {
            val wrapContent = true // TODO: make wrap content optional?

            val firstLine = layout.getLineForVertical(scrollView.scrollY)
            val lastLine = layout.getLineForVertical(scrollView.scrollY + scrollView.height)

            var prevNum = -1
            for (i in firstLine..lastLine) {
                val num = actualLineFromRenderedLine[i]
                if (!wrapContent || prevNum != num) {
                    prevNum = num
                    canvas?.drawText(num.toString(),
                            (if (isRtl) width - paddingWithLineNumbers + lineNumberGapWidth else paddingWithLineNumbers - lineNumberGapWidth).toFloat(),
                            layout.getLineBottom(i).toFloat(),
                            lineNumberPaint)
                }
            }
        }
        super.onDraw(canvas)
    }

    private fun computeLineNumbers(lineCount: Int, layout: Layout, text: String?) {
        val lineContainsNewlineChar = BooleanArray(lineCount)

        if (text.isNullOrEmpty() || lineCount == 0) {
            actualLineFromRenderedLine = IntArray(1)
            actualLineFromRenderedLine[0] = 1
            return
        }

        val renderedLineBeginsActualLine = BooleanArray(lineCount)
        actualLineFromRenderedLine = IntArray(lineCount)

        for (i in 0 until lineCount) {
            lineContainsNewlineChar[i] = text[layout.getLineEnd(i) - 1] == '\n'
            if (lineContainsNewlineChar[i]) {
                var j = i - 1
                while (j >= 0 && !lineContainsNewlineChar[j]) { j-- }
                renderedLineBeginsActualLine[j + 1] = true
            }
        }

        var j = lineCount - 1
        while (j >= 0 && !lineContainsNewlineChar[j]) { j-- }
        renderedLineBeginsActualLine[j + 1] = true

        var actualLine = 0
        for (i in renderedLineBeginsActualLine.indices) {
            if (renderedLineBeginsActualLine[i]) {
                actualLine++
            }
            actualLineFromRenderedLine[i] = actualLine
        }
    }

    private fun applyPaddingForLineNumbers() {
        setPaddingRelative(if (showLineNumbers) paddingWithLineNumbers else paddingWithoutLineNumbers, paddingTop, paddingEnd, paddingBottom)
    }

    override fun getText(): Editable {
        // A bug pre-P makes getText() crash if called before the first setText due to a cast, so
        // retrieve the editable text.
        return (if (Build.VERSION.SDK_INT >= 28) {
            super.getText()
        } else super.getEditableText()) ?: Editable.Factory.getInstance().newEditable("")
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        inputConnection = super.onCreateInputConnection(outAttrs)

        // For multiline EditTexts that specify a done keyboard action, unset the no carriage return
        // flag which otherwise limits the EditText to a single line
        val multilineInput = inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE == InputType.TYPE_TEXT_FLAG_MULTI_LINE
        val actionDone = outAttrs.imeOptions and EditorInfo.IME_ACTION_DONE == EditorInfo.IME_ACTION_DONE
        if (actionDone && multilineInput) {
            outAttrs.imeOptions = outAttrs.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION.inv()
        }
        return inputConnection
    }

    override fun onReceiveContent(payload: ContentInfo): ContentInfo? {
        var newPayload = payload
        try {
            // Do not allow pasting of formatted text! We do this by replacing the contents of the clip
            // with plain text.
            val clip = payload.clip
            val lastClipText = clip.getItemAt(clip.itemCount - 1).coerceToText(context).toString()

            newPayload = ContentInfo.Builder(payload)
                    .setClip(ClipData.newPlainText(null, lastClipText))
                    .build()
        } catch (e: Exception) {
            L.e(e)
        }
        return super.onReceiveContent(newPayload)
    }

    fun undo() {
        inputConnection?.let {
            it.sendKeyEvent(KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON))
            it.sendKeyEvent(KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON))
        }
    }

    fun redo() {
        inputConnection?.let {
            it.sendKeyEvent(KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON))
            it.sendKeyEvent(KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON))
        }
    }
}
