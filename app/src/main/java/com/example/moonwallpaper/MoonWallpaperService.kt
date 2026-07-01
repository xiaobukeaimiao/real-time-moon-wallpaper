package com.example.moonwallpaper

import CraterShadowRenderer
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.graphics.Bitmap
import android.os.Build
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.view.SurfaceHolder
import org.shredzone.commons.suncalc.MoonIllumination
import org.shredzone.commons.suncalc.MoonPosition
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import org.shredzone.commons.suncalc.util.Sun
import org.shredzone.commons.suncalc.util.JulianDate


class MoonWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return MoonEngine()
    }

    inner class MoonEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())

        private var lastReadyBitmap: Bitmap? = null
        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var updateWallpaperJob: Job? = null
        // 锁对象，防止多线程同时读写 bitmap
        private val bitmapLock = Any()

        // 核心组件：使用单例管理器
        private lateinit var moonManager: MoonTerrainManager
        private lateinit var locationHelper: LocationHelper

        private var isVisible = false

        private var cachedShadowLayer: Bitmap? = null
        private var shadowRenderer: CraterShadowRenderer? = null

        // 状态变量
        private var currentRotation: Float = 0f
        private var lastUpdateFiveMinuteBucket = -1
        private var targetSize: Int = 0 // 保存计算出的目标大小


        // 屏幕大小
        private val metrics = resources.displayMetrics
        private val screenW = metrics.widthPixels
        private val screenH = metrics.heightPixels

        // 画笔
        private val paint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
        private val brightPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true
            val matrix = ColorMatrix()
            // 1.3f 表示亮度增加 30%
            matrix.setScale(1.3f, 1.3f, 1.3f, 1.0f)
            colorFilter = ColorMatrixColorFilter(matrix)
        }

        private val textPaint = Paint().apply {
            color = Color.rgb(230, 230, 230)
            textSize = screenH.toFloat() / 50 // 字号大小，可按需调整
            textAlign = Paint.Align.CENTER // 居中对齐
            isAntiAlias = true
            typeface = Typeface.DEFAULT //默认字体
            // 加个阴影，防止背景太亮看不清
        }

        private val fiveMinuteTask = object : Runnable {
            override fun run() {
                updateMoonData()
                handler.postDelayed(this, 1000 * 60 * 5)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)


            // 确定最终显示的月球大小
            targetSize = (screenW * 0.9f).toInt()

            locationHelper = LocationHelper(applicationContext)
            moonManager = MoonTerrainManager.getInstance(applicationContext, targetSize)


            moonManager.loadAndCompute(
                diffuseResId = R.drawable.moon_diffuse,
                heightMapResId = R.drawable.moon_shadow_map
            ) {
                // 回调：当数据准备好后执行

                // 切回主线程刷新界面
                handler.post {
                    drawFrame()
                    // 如果处于预览或可见状态，强制刷新锁屏
                    if (isVisible) forceUpdateLockScreen()
                }
            }

            // 3. 初始化渲染器
            // 传入需要生成的图片大小(targetSize)
            shadowRenderer = CraterShadowRenderer(targetSize)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible

            handler.removeCallbacks(delayUpdateTask)
            handler.removeCallbacks(fiveMinuteTask)

            if (visible) {
                // 【策略 A】先展示旧图，避开系统读取高峰
                // 延迟 2000ms 再更新，确保系统已经完成锁屏渲染
                // 这样可以解决"亮屏显示默认壁纸"的问题
                handler.postDelayed(delayUpdateTask, 2000)

                handler.post(fiveMinuteTask)
            } else {
                // 【策略 B】灭屏是最佳的静默更新时机
                // 用户看不见，怎么写都行，为下一次亮屏储备最新资源
                Thread { forceUpdateLockScreen() }.start()
            }
        }

        // 定义一个专门的延迟任务
        private val delayUpdateTask = Runnable {
            Thread { forceUpdateLockScreen() }.start()
        }

        private fun updateMoonData() {
            locationHelper.tryUpdateLocation()
            val lat = locationHelper.currentLat
            val lng = locationHelper.currentLng

            val now = LocalDateTime.now()

            val fiveMinuteBucket = now.minute / 5
            if (fiveMinuteBucket == lastUpdateFiveMinuteBucket && cachedShadowLayer != null) {
                drawFrame()
                return
            }
            lastUpdateFiveMinuteBucket = fiveMinuteBucket



            // 1. 获取天文数据
            // 这个库的单位是度数！
            // 旋转
            val pos = MoonPosition.compute().at(lat, lng).on(now).execute()
            currentRotation = pos.parallacticAngle.toFloat()

            // 相位
            val ill = MoonIllumination.compute().on(now).execute()
            val phaseDeg = ill.phase.toFloat()

            val altitude = pos.altitude
            val azimuth = pos.azimuth
            val sunLon = getSunEclipticLongitude(now)

            drawFrame()

            if (shadowRenderer != null) {
                Thread {
                    // 1. (耗时) 生成阴影蒙版
                    val newShadow = shadowRenderer?.generateShadowLayer(phaseDeg)

                    if (newShadow != null) {
                        // 更新阴影缓存
                        cachedShadowLayer = newShadow

                        // 直接在后台生成最终的壁纸 Bitmap
                        // 这样主线程或者熄屏时直接取用即可，不用再画了
                        try {
                            // 创建一个新的 Bitmap (全屏尺寸)
                            val bgBitmap = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
                            val bgCanvas = Canvas(bgBitmap)

                            // 复用绘制逻辑，画在这个离屏 Bitmap 上
                            drawMoonToCanvas(bgCanvas)

                            drawInfoText(bgCanvas, altitude, azimuth, phaseDeg, sunLon)

                            // 线程安全地更新缓存
                            synchronized(bitmapLock) {
                                // 回收旧图，防止 OOM (如果有旧图且不是同一张)
                                lastReadyBitmap = bgBitmap
                            }
                        } catch (e: OutOfMemoryError){}

                        // 3. 通知 UI 刷新
                        handler.post {
                            drawFrame() // 屏幕上的 SurfaceView 刷新

                            // 如果需要实时同步锁屏，可以在这里调用，现在它很快了
                            if (isVisible) {
                                forceUpdateLockScreen()
                            }
                        }
                    }
                }.start()
            }
        }


        private fun getAzimuthDescription(deg: Double): String {
            val d = (deg % 360 + 360) % 360
            val dInt = d.roundToInt()

            return when (dInt) {
                0   -> "正北"
                45  -> "东北"
                90  -> "正东"
                135 -> "东南"
                180 -> "正南"
                225 -> "西南"
                270 -> "正西"
                315 -> "西北"

                in 0..45    -> "北偏东${dInt}°"
                in 45..90   -> "东偏北${90 - dInt}°"
                in 90..135  -> "东偏南${dInt - 90}°"
                in 135..180 -> "南偏东${180 - dInt}°"
                in 180..225 -> "南偏西${dInt - 180}°"
                in 225..270 -> "西偏南${270 - dInt}°"
                in 270..315 -> "西偏北${dInt - 270}°"
                else        -> "北偏西${360 - dInt}°"
            }
        }

        private fun getMoonPhase(phaseDeg: Float): String {
            val d = ((phaseDeg % 360) + 360) % 360
            return when {
                d >= 337.5f || d < 22.5f -> "新月"
                d in 22.5f..<67.5f -> "蛾眉月"
                d in 67.5f..<112.5f -> "上弦月"
                d in 112.5f..<157.5f -> "盈凸月"
                d in 157.5f..<202.5f -> "满月"
                d in 202.5f..<247.5f -> "亏凸月"
                d in 247.5f..<292.5f -> "下弦月"
                d in 292.5f..<337.5f -> "残月"
                else -> ""
            }
        }

        private fun getSolarTerm(sunLonDeg: Double): String {
            val l = ((sunLonDeg % 360) + 360) % 360
            return when {
                l < 15  -> "春分"
                l < 30  -> "清明"
                l < 45  -> "谷雨"
                l < 60  -> "立夏"
                l < 75  -> "小满"
                l < 90  -> "芒种"
                l < 105 -> "夏至"
                l < 120 -> "小暑"
                l < 135 -> "大暑"
                l < 150 -> "立秋"
                l < 165 -> "处暑"
                l < 180 -> "白露"
                l < 195 -> "秋分"
                l < 210 -> "寒露"
                l < 225 -> "霜降"
                l < 240 -> "立冬"
                l < 255 -> "小雪"
                l < 270 -> "大雪"
                l < 285 -> "冬至"
                l < 300 -> "小寒"
                l < 315 -> "大寒"
                l < 330 -> "立春"
                l < 345 -> "雨水"
                else -> "惊蛰"
            }
        }

        private fun getSunEclipticLongitude(now: LocalDateTime): Double {
            // 使用 commons-suncalc 内部方法计算太阳黄经
            val zdt = now.atZone(ZoneId.systemDefault())
            val jd = JulianDate(zdt)
            val pos = Sun.position(jd)
            // getPhi() 返回黄道经度（弧度），转为角度
            return Math.toDegrees(pos.phi)
        }

        private fun drawInfoText(canvas: Canvas, alt: Double, azi: Double, phaseDeg: Float, sunLon: Double) {
            val cx = canvas.width / 2f
            val cy = canvas.height / 2f
            val radius = targetSize / 2f // 月球半径

            val lineSpacing = screenH.toFloat() / 20

            // 计算第一行文字位置：放在月球下方，加一点 padding
            val textY1 = cy + radius + lineSpacing

            // 格式化第一行数据
            val altInt = alt.roundToInt()
            val line1 = "高度角: ${altInt}°   方位角: ${getAzimuthDescription(azi)}"

            canvas.drawText(line1, cx, textY1, textPaint)

            // 第二行：月相和节气信息
            val textY2 = textY1 + lineSpacing
            val phaseDegplus180 = phaseDeg + 180
            val moonPhase = getMoonPhase(phaseDegplus180)
            val phaseAngleFormatted = phaseDegplus180.roundToInt()
            val solarTerm = getSolarTerm(sunLon)
            val sunLonFormatted = sunLon.roundToInt()
            val line2 = "月亮：$moonPhase ${phaseAngleFormatted}°   太阳：$solarTerm ${sunLonFormatted}°"

            canvas.drawText(line2, cx, textY2, textPaint)
        }


        private fun drawMoonToCanvas(canvas: Canvas) {
            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()
            val cx = w / 2f
            val cy = h / 2f

            canvas.drawColor(Color.BLACK)

            canvas.save()
            canvas.rotate(currentRotation, cx, cy)

            // 绘制区域基于 targetSize
            if (::moonManager.isInitialized) {
                val moonBitmap = moonManager.bitmapDiffuse

                if (moonBitmap != null) {
                    val radius = targetSize / 2f
                    val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

                    // 绘制底图
                    canvas.drawBitmap(moonBitmap, null, rect, brightPaint)

                    // 绘制阴影
                    cachedShadowLayer?.let { shadow ->
                        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                        canvas.drawBitmap(shadow, null, rect, paint)
                        paint.xfermode = null
                    }
                }
            }

            canvas.restore()
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    drawMoonToCanvas(canvas)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }
        }




        fun forceUpdateLockScreen() {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return

            // 1. 取消上一次任务，防止堆积
            updateWallpaperJob?.cancel()

            // 2. 启动协程
            updateWallpaperJob = engineScope.launch {

                // 防抖：如果短时间频繁调用，这里会挂起，新的调用会取消旧的
                delay(1000)

                var safeBitmapSnapshot: Bitmap? = null

                // 3. 【关键步骤】生成快照 (Snapshot)
                // 在主线程+锁保护下，深拷贝一份 Bitmap。
                // 虽然 copy 耗内存，但这是防止 "Can't compress a recycled bitmap" 最稳妥的方法。
                synchronized(bitmapLock) {
                    if (lastReadyBitmap != null && !lastReadyBitmap!!.isRecycled) {
                        try {
                            // config 设为 ARGB_8888 保证兼容性
                            safeBitmapSnapshot = lastReadyBitmap!!.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (e: OutOfMemoryError) {
                            // 内存不足时保命要紧，放弃这次更新
                            return@launch
                        }
                    }
                }

                if (safeBitmapSnapshot == null) return@launch

                // 4. 切换到 IO 线程执行耗时操作
                withContext(Dispatchers.IO) {
                    try {
                        if (!isActive) return@withContext //再次检查是否被取消

                        val wallpaperManager = android.app.WallpaperManager.getInstance(applicationContext)

                        // 此时 safeBitmapSnapshot 是独立的，主线程把 lastReadyBitmap 回收了也不怕
                        wallpaperManager.setBitmap(
                            safeBitmapSnapshot,
                            null,
                            true,
                            android.app.WallpaperManager.FLAG_LOCK
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        // 5. 用完必须手动回收快照，防止 OOM
                        safeBitmapSnapshot?.recycle()
                    }
                }
            }
        }


        override fun onDestroy() {
            super.onDestroy()
            // 销毁时取消所有协程，防止内存泄漏
            engineScope.cancel()
        }
    }
}