package tk.zwander.widgetdrawer.views

import android.animation.Animator
import android.animation.ValueAnimator
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.View.OnClickListener
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlinx.android.synthetic.main.drawer_layout.view.*
import tk.zwander.widgetdrawer.R
import tk.zwander.widgetdrawer.activities.PermConfigActivity
import tk.zwander.widgetdrawer.activities.PermConfigActivity.Companion.CONFIG_CODE
import tk.zwander.widgetdrawer.activities.PermConfigActivity.Companion.PERM_CODE
import tk.zwander.widgetdrawer.activities.WidgetSelectActivity
import tk.zwander.widgetdrawer.activities.WidgetSelectActivity.Companion.PICK_CODE
import tk.zwander.widgetdrawer.adapters.DrawerAdapter
import tk.zwander.widgetdrawer.misc.DrawerHost
import tk.zwander.widgetdrawer.misc.OverrideWidgetInfo
import tk.zwander.widgetdrawer.utils.PrefsManager
import tk.zwander.widgetdrawer.utils.screenSize
import tk.zwander.widgetdrawer.utils.statusBarHeight

class Drawer : ConstraintLayout {
    companion object {
        const val ACTION_PERM = "PERMISSION"
        const val ACTION_CONFIG = "CONFIGURATION"

        const val ACTION_RESULT = "PICK_WIDGET"

        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_APPWIDGET_CONFIGURE = "configure"

        fun onResult(context: Context, result: Int, code: Int, data: Intent?) {
            val intent = Intent(ACTION_RESULT)
            intent.putExtra(Intent.EXTRA_RETURN_RESULT, result)
            intent.putExtra(EXTRA_CODE, code)
            intent.putExtra(EXTRA_DATA, data)
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        }
    }

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    val params: WindowManager.LayoutParams
        get() = WindowManager.LayoutParams().apply {
            val displaySize = context.screenSize()
            type = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_PRIORITY_PHONE
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = displaySize.y
            format = PixelFormat.RGBA_8888
            gravity = Gravity.TOP
        }

    private val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val host = DrawerHost(context.applicationContext, 1003)
    private val manager = AppWidgetManager.getInstance(context.applicationContext)
    private val prefs = PrefsManager(context)
    private val adapter = DrawerAdapter(manager, host, prefs)

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                ACTION_RESULT -> onActivityResult(
                    intent.getIntExtra(EXTRA_CODE, -1000),
                    intent.getIntExtra(Intent.EXTRA_RETURN_RESULT, -1000),
                    intent.getParcelableExtra(EXTRA_DATA)
                )
            }
        }
    }

    init {
        setBackgroundColor(Color.argb(100, 0, 0, 0))
        alpha = 0.0f
    }

    override fun onFinishInflate() {
        super.onFinishInflate()

        add_widget.setOnClickListener { pickWidget() }
        close_drawer.setOnClickListener { hideDrawer() }

        adapter.setHasStableIds(true)

        widget_grid.onMoveListener = { _, viewHolder, target ->
            val oldPos = viewHolder.adapterPosition
            val newPos = target.adapterPosition

            val widget = adapter.widgets.removeAt(oldPos)
            adapter.widgets.add(newPos, widget)

            adapter.notifyItemMoved(oldPos, newPos)

            prefs.currentWidgets = adapter.widgets
            true
        }

        widget_grid.onSwipeListener = { viewHolder, _ ->
            removeWidget(viewHolder.adapterPosition)
        }

        toggle_reorder.setOnCheckedChangeListener { _, isChecked ->
            widget_grid.allowReorder = isChecked
            if (isChecked) adapter.showEdit() else adapter.hideEdit()
            val anim = if (isChecked) ValueAnimator.ofInt(action_bar_wrapper.measuredHeight, action_bar_wrapper.measuredHeight * 2)
                            else ValueAnimator.ofInt(action_bar_wrapper.measuredHeight, (action_bar_wrapper.measuredHeight / 2f).toInt())

            anim.addUpdateListener {
                action_bar_wrapper.layoutParams = action_bar_wrapper.layoutParams.apply {
                    height = it.animatedValue.toString().toInt()
                }
            }
            anim.interpolator = if (isChecked) DecelerateInterpolator() else AccelerateInterpolator()
            anim.start()
        }

        val listener = OnClickListener { view ->

            adapter.widgets.filter { it.isSelected }.forEach { widget ->
                if (widget.isSelected) {
                    var changed = false
                    val index = adapter.widgets.indexOf(widget)

                    when (view.id) {
                        R.id.expand_horiz -> {
                            changed = widget.isFullWidth != true
                            widget.isFullWidth = true
                        }
                        R.id.collapse_horiz -> {
                            changed = widget.isFullWidth != false
                            widget.isFullWidth = false
                        }
                        R.id.expand_vert -> if (widget.forcedHeight < DrawerAdapter.SIZE_MAX) {
                            changed = true
                            widget.forcedHeight++
                        }
                        R.id.collapse_vert -> if (widget.forcedHeight > DrawerAdapter.SIZE_MIN) {
                            changed = true
                            widget.forcedHeight--
                        }
                    }

                    if (changed) {
                        prefs.currentWidgets = adapter.widgets
                        adapter.notifyItemChanged(index)
                    }
                }
            }
        }

        expand_horiz.setOnClickListener(listener)
        expand_vert.setOnClickListener(listener)
        collapse_horiz.setOnClickListener(listener)
        collapse_vert.setOnClickListener(listener)

        setPadding(0, context.statusBarHeight(), 0, 0)
    }

    fun onCreate() {
        host.startListening()
        widget_grid.adapter = adapter
        widget_grid.isNestedScrollingEnabled = true
        widget_grid.setHasFixedSize(true)
        (widget_grid.layoutManager as StaggeredGridLayoutManager).apply {
            spanCount = 2
        }

        LocalBroadcastManager.getInstance(context).registerReceiver(resultReceiver, IntentFilter(ACTION_RESULT))

        adapter.addAll(prefs.currentWidgets)
    }

    fun onDestroy() {
        hideDrawer()
        host.stopListening()
        prefs.currentWidgets = adapter.widgets

        LocalBroadcastManager.getInstance(context).unregisterReceiver(resultReceiver)
    }

    fun showDrawer() {
        alpha = 0f
        try {
            wm.addView(this, params)
        } catch (e: Exception) {}

        animate()
            .alpha(1.0f)
            .setListener(object : Animator.AnimatorListener {
                override fun onAnimationEnd(animation: Animator?) {
                    alpha = 1.0f
                }

                override fun onAnimationCancel(animation: Animator?) {}
                override fun onAnimationRepeat(animation: Animator?) {}
                override fun onAnimationStart(animation: Animator?) {}
            })
            .start()
    }

    fun hideDrawer() {
        animate()
            .alpha(0.0f)
            .setListener(object : Animator.AnimatorListener {
                override fun onAnimationEnd(animation: Animator?) {
                    alpha = 0.0f
                    try {
                        wm.removeView(this@Drawer)
                    } catch (e: Exception) {}
                }

                override fun onAnimationCancel(animation: Animator?) {}
                override fun onAnimationRepeat(animation: Animator?) {}
                override fun onAnimationStart(animation: Animator?) {}
            })
            .start()
    }

    private fun getWidgetPermission(id: Int, componentName: ComponentName, options: Bundle? = null) {
        val intent = Intent(ACTION_PERM)
        intent.component = ComponentName(context, PermConfigActivity::class.java)
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, componentName)
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options)

        context.startActivity(intent)
    }

    private fun configureWidget(
        id: Int,
        configure: ComponentName
    ) {
        val intent = Intent(ACTION_CONFIG)
        intent.putExtra(EXTRA_APPWIDGET_CONFIGURE, configure)
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        intent.component = ComponentName(context, PermConfigActivity::class.java)

        context.startActivity(intent)
    }

    private fun pickWidget() {
        hideDrawer()
        val intent = Intent(context, WidgetSelectActivity::class.java)
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, host.allocateAppWidgetId())
        context.startActivity(intent)
    }

    private fun tryBindWidget(info: AppWidgetProviderInfo, id: Int = host.allocateAppWidgetId()) {
        val canBind = manager.bindAppWidgetIdIfAllowed(id, info.provider)

        if (!canBind) getWidgetPermission(id, info.provider)
        else {
            if (info.configure != null && !adapter.widgets.map { it.id }.contains(id)) {
                configureWidget(id, info.configure)
            } else {
                addNewWidget(id)
            }
        }
    }

    private fun addNewWidget(id: Int) {
        val info = createSavedWidget(id)
        adapter.addItem(info)
        prefs.currentWidgets = adapter.widgets
        showDrawer()
    }

    private fun createSavedWidget(id: Int): OverrideWidgetInfo {
        return OverrideWidgetInfo(id, DrawerAdapter.SIZE_DEF, false)
    }

    private fun removeWidget(position: Int) {
        val info = adapter.removeAt(position)
        host.deleteAppWidgetId(info.id)
        prefs.currentWidgets = adapter.widgets
    }

    private fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            PERM_CODE -> {
                if (resultCode == Activity.RESULT_OK) {
                    tryBindWidget(
                        manager.getAppWidgetInfo(
                            data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: return
                        )
                    )
                }
            }
            CONFIG_CODE -> {
                val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: return
                if (id == -1) return
                addNewWidget(id)
            }
            PICK_CODE -> {
                tryBindWidget(data?.getParcelableExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER) ?: return)
            }
        }
    }
}