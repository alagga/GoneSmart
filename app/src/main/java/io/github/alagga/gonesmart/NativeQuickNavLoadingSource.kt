package io.github.alagga.gonesmart

import android.content.Context
import android.content.res.XmlResourceParser
import android.graphics.drawable.Animatable
import android.util.AttributeSet
import android.util.Log
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.recyclerview.widget.RecyclerView
import org.xmlpull.v1.XmlPullParser
import java.util.WeakHashMap

/**
 * Verified original GMMP 4.2.1 frag_file_browser XML:
 * header RecyclerView + MaterialProgressBar inside one FrameLayout.
 * Read the installed resource attributes directly; do not inflate a
 * second Files fragment, move its real views or copy proprietary XML.
 */
internal class NativeQuickNavLoadingSource {
    companion object {
        private const val ANDROID = "http://schemas.android.com/apk/res/android"
        private const val BAR = "me.zhanghai.android.materialprogressbar.MaterialProgressBar"
        private const val AESTHETIC_BAR = "com.afollestad.aesthetic.views.AestheticProgressBar"
        private const val DRAWABLE =
            "me.zhanghai.android.materialprogressbar.IndeterminateHorizontalProgressDrawable"

        private fun <T> withElement(
            host: View, name: String, className: String,
            block: (XmlResourceParser, AttributeSet) -> T
        ): T? {
            val res = host.resources
            val pkg = host.context.packageName
            val layout = res.getIdentifier("frag_file_browser", "layout", pkg)
            val id = res.getIdentifier(name, "id", pkg)
            if (layout == 0 || id == 0) return null
            val parser = res.getLayout(layout)
            try {
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == className &&
                        parser.getAttributeResourceValue(ANDROID, "id", 0) == id
                    ) {
                        return block(parser, Xml.asAttributeSet(parser))
                    }
                    event = parser.next()
                }
            } finally {
                parser.close()
            }
            return null
        }

        fun applyHeaderXml(header: RecyclerView) {
            runCatching {
                withElement(header, "quickNavRecyclerView", "androidx.recyclerview.widget.RecyclerView") { _, attrs ->
                    val values = header.context.obtainStyledAttributes(attrs, intArrayOf(
                        android.R.attr.paddingLeft, android.R.attr.paddingRight,
                        android.R.attr.background
                    ))
                    try {
                        header.setPadding(
                            values.getDimensionPixelSize(0, header.paddingLeft),
                            header.paddingTop,
                            values.getDimensionPixelSize(1, header.paddingRight),
                            header.paddingBottom
                        )
                        values.getDrawable(2)?.let { header.background = it }
                    } finally {
                        values.recycle()
                    }
                }
            }
        }
    }

    private val failed = WeakHashMap<ClassLoader, Boolean>()

    fun create(host: View): ProgressBar? {
        val loader = host.context.classLoader ?: host.javaClass.classLoader ?: return null
        if (failed[loader] == true) return null
        return runCatching {
            withElement(host, "mainProgressBar", BAR) { _, attrs ->
                // GMMP's h52 LayoutInflater factory maps the declared
                // MaterialProgressBar tag to AestheticProgressBar. Use the
                // same independently constructed native class; its own
                // attach/detach lifecycle applies !mainColorAccent.
                val declared = loader.loadClass(BAR)
                val klass = loader.loadClass(AESTHETIC_BAR)
                require(ProgressBar::class.java.isAssignableFrom(declared) &&
                    declared.isAssignableFrom(klass))
                val params = FrameLayout.LayoutParams(host.context, attrs)
                require(params.height > 0 && params.width == -1 &&
                    (params.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM)
                // The two-argument constructor resolves the actual XML style,
                // mpb_progressStyle, mpb_setBothDrawables, intrinsic padding.
                val bar = klass.getConstructor(Context::class.java, AttributeSet::class.java)
                    .newInstance(host.context, attrs) as ProgressBar
                require(bar.indeterminateDrawable?.javaClass?.name == DRAWABLE &&
                    bar.indeterminateDrawable is Animatable)
                bar.layoutParams = params
                // Resource attributes initialize the independent native
                // view, but the actual Files fragment owns its resource ID.
                bar.id = View.NO_ID
                bar.visibility = View.GONE
                bar.isClickable = false
                bar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                if (BuildConfig.DEBUG) Log.i("GoneSmartPlaylist",
                    "FOLDER NATIVE QUICKNAV LOADING | source=installed-xml | height=" +
                        params.height + " | gravity=" + params.gravity +
                        " | view=" + bar.javaClass.name +
                        " | drawable=" + bar.indeterminateDrawable.javaClass.simpleName)
                bar
            } ?: error("Verified Files progress element not found")
        }.onFailure {
            failed[loader] = true
            if (BuildConfig.DEBUG) Log.w("GoneSmartPlaylist",
                "FOLDER NATIVE QUICKNAV LOADING | native XML binding unavailable; omitted", it)
        }.getOrNull()
    }

}
