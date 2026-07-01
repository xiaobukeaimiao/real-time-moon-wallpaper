package com.example.moonwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.sqrt
import kotlin.math.pow

class MoonTerrainManager private constructor(
    private val context: Context,
    val targetSize: Int
) {
    // === 公开数据 ===
    // 缓存的底图 (Diffuse)，长期持有
    var bitmapDiffuse: Bitmap? = null
        private set

    // 法线数据缓存
    val cachedNx = FloatArray(targetSize * targetSize)
    val cachedNy = FloatArray(targetSize * targetSize)
    val cachedNz = FloatArray(targetSize * targetSize)

    // 遮罩缓存
    val validMask = ByteArray(targetSize * targetSize)

    // 状态标记
    @Volatile
    var isReady = false
        private set



    // ==========================================
    //          2. 光照 LUT (新增部分)
    // ==========================================

    // LUT 大小：512 足够平滑，且内存占用极小
    val LUT_SIZE = 512

    // 数组大小 +1 是为了处理 dot=1.0 的边界情况，防止数组越界
    val lightLut = FloatArray(LUT_SIZE + 1)

    companion object {
        @Volatile
        private var instance: MoonTerrainManager? = null

        /**
         * 获取单例实例
         * 第一次调用时会初始化，之后直接返回已有的实例
         */
        fun getInstance(context: Context, targetSize: Int): MoonTerrainManager {
            // 检查：如果实例存在，但尺寸不对，说明是旧的垃圾实例，销毁它！
            if (instance != null && instance!!.targetSize != targetSize) {
                instance!!.release() // 释放旧内存
                instance = null
            }

            return instance ?: synchronized(this) {
                instance ?: MoonTerrainManager(context.applicationContext, targetSize).also {
                    instance = it
                }
            }}

        // 仅仅获取，不初始化（如果确定之前已经初始化过）
        fun getInstanceOrNull(): MoonTerrainManager? {
            return instance
        }
    }



    // 默认的光照指数1
    // 屏幕伽马值为2.2
    private var currentGamma = 1.2f / 2.2f

    init {
        // 初始化时生成默认 LUT
        updateLightLut(currentGamma)
    }

    /**
     * 更新光照查找表
     * @param exponent 指数 (gamma)。
     * 0.7 为默认。
     * 1.0 为线性光照 (标准 Lambert)。
     * < 1.0 暗部更亮（月球看起来更柔和）。
     * > 1.0 暗部更暗（对比度更高）。
     */
    fun updateLightLut(exponent: Float) {
        this.currentGamma = exponent
        for (i in 0..LUT_SIZE) {
            // x 归一化到 0.0 ~ 1.0
            val x = i.toFloat() / LUT_SIZE
            // 应用公式 y = x ^ exponent
            val intensity = x.pow(exponent)
            lightLut[i] = intensity
        }
    }


    /**
     * 初始化数据
     * @param diffuseResId 月球底图资源ID
     * @param heightMapResId 高度图资源ID
     * @param onComplete 加载完成后的回调 (在子线程执行，如果需要更新UI请切回主线程)
     */
    fun loadAndCompute(diffuseResId: Int, heightMapResId: Int, onComplete: () -> Unit) {
        // 如果已经准备好了，直接回调并返回
        if (isReady) {
            onComplete()
            return
        }

        Thread {
            try {
                // 1. 加载底图 (只加载一次，缓存下来)
                if (bitmapDiffuse == null) {
                    bitmapDiffuse = loadScaledBitmap(diffuseResId, targetSize, targetSize)
                }

                // 2. 加载高度图 (原图分辨率，不缩放)
                // 这是一个临时的大对象
                val rawHeightMap = loadOriginalBitmap(heightMapResId)

                // 3. 计算法线数据 (耗时操作)
                precomputeAndBake3DDisplacement(rawHeightMap)

                // 4. 关键：释放高度图内存！
                // 数据已经提取到 cachedNx/Ny/Nz 数组中了，Bitmap 不再需要
                rawHeightMap.recycle()

                // 5. 标记完成
                isReady = true
                onComplete()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    /**
     * 当不需要月球功能时（比如APP退出），调用此方法彻底释放底图
     */
    fun release() {
        bitmapDiffuse?.recycle()
        bitmapDiffuse = null
        isReady = false
    }



    private fun precomputeAndBake3DDisplacement(map: Bitmap) {
        val mapWidth = map.width
        val mapHeight = map.height
        val pixels = IntArray(mapWidth * mapHeight)
        map.getPixels(pixels, 0, mapWidth, 0, 0, mapWidth, mapHeight)

        // 几何参数
        val radius = targetSize / 2f
        val radiusSq = radius * radius
        val centerX = targetSize / 2f
        val centerY = targetSize / 2f

        // 高度缩放因子：决定陨石坑的深浅
        // 这里的数值代表：最大灰度(255)对应的实际隆起高度是半径的多少倍
        // 0.05f 意味着最黑的地方会隆起半径的 5%
        val heightScale = 0.06f

        // 坐标映射比例
        val scaleX = mapWidth.toFloat() / targetSize.toFloat()
        val scaleY = mapHeight.toFloat() / targetSize.toFloat()

        // 辅助函数：获取某一个输出像素位置对应的 "位移后 3D 坐标"
        // 返回 float[3] {x, y, z}，如果该点在球外，返回 null
        fun getDisplacedPoint(outX: Int, outY: Int): FloatArray? {
            // 1. 计算基底球面的几何信息
            val px = outX - centerX
            val py = outY - centerY
            val distSq = px * px + py * py

            if (distSq >= radiusSq - 1) return null // 稍微向内缩一点防止边缘浮点误差

            // 球面 Z 坐标 (正对我们的方向)
            val pz = sqrt((radiusSq - distSq).toDouble()).toFloat()

            // 基底球面的单位法向量 (对于球心在原点的球，法线就是位置向量归一化，即除以半径)
            // baseN = (px/R, py/R, pz/R)
            // 原始球面点 P = (px, py, pz)

            // 2. 获取高度图高度
            val srcX = (outX * scaleX).toInt().coerceIn(0, mapWidth - 1)
            val srcY = (outY * scaleY).toInt().coerceIn(0, mapHeight - 1)
            val gray = (pixels[srcY * mapWidth + srcX] shr 16) and 0xFF

            // 归一化高度 (0.0 ~ 1.0)
            val hNorm = gray / 255f

            // 3. 计算物理位移 (Displacement)
            // 关键点：高度差是在“理想球体的法线方向”上的
            // P_new = P_old + Height * N_old
            // 也就是 P_new = P_old * (1 + Height / Radius)

            val displacementFactor = 1.0f + (hNorm * heightScale)

            return floatArrayOf(
                px * displacementFactor,
                py * displacementFactor,
                pz * displacementFactor
            )
        }

        for (y in 0 until targetSize) {
            val rowOffset = y * targetSize

            for (x in 0 until targetSize) {
                val idx = rowOffset + x

                // 获取当前点 P (Center)
                val pC = getDisplacedPoint(x, y)

                if (pC == null) {
                    validMask[idx] = 0
                    continue
                }
                validMask[idx] = 1

                // === 差分法计算切向量 ===
                // 获取相邻点 (上下左右)，构建 3D 表面网格
                // 如果邻居在圆外(null)，则退化为使用中心点(pC)，这能保证边缘法线平滑过渡而不突变
                val pL = getDisplacedPoint(x - 1, y) ?: pC
                val pR = getDisplacedPoint(x + 1, y) ?: pC
                val pT = getDisplacedPoint(x, y - 1) ?: pC
                val pB = getDisplacedPoint(x, y + 1) ?: pC

                // 计算两个切线向量 (Tangent Vectors)
                // 这里的向量是真实的 3D 空间向量
                // tx = 右 - 左
                val txX = pR[0] - pL[0]
                val txY = pR[1] - pL[1]
                val txZ = pR[2] - pL[2]

                // ty = 下 - 上 (注意屏幕坐标系 Y 是向下的)
                val tyX = pB[0] - pT[0]
                val tyY = pB[1] - pT[1]
                val tyZ = pB[2] - pT[2]

                // === 叉乘计算法线 ===
                // Normal = Tx × Ty
                // 坐标系：X向右，Y向下，Z向屏幕外(对于右手系)
                // 叉乘公式:
                // Nx = ty * sz - tz * sy
                // Ny = tz * sx - tx * sz
                // Nz = tx * sy - ty * sx

                val finalNx = txY * tyZ - txZ * tyY
                val finalNy = txZ * tyX - txX * tyZ
                val finalNz = txX * tyY - txY * tyX

                // === 归一化 ===
                val len = sqrt(finalNx * finalNx + finalNy * finalNy + finalNz * finalNz)

                if (len > 0.00001f) {
                    cachedNx[idx] = finalNx / len
                    cachedNy[idx] = finalNy / len
                    cachedNz[idx] = finalNz / len
                } else {
                    // 极端情况回退到几何法线
                    cachedNx[idx] = pC[0] / radius
                    cachedNy[idx] = pC[1] / radius
                    cachedNz[idx] = pC[2] / radius
                }
            }
        }
        // 释放内存
    }

    /**
     * 函数1: 读取原图
     * 不缩放，不压缩，保持原始像素，用于 HeightMap 计算
     */
    private fun loadOriginalBitmap(resId: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            // 关键: 设置为 false，防止 Android 根据屏幕密度(DPI)自动缩放图片
            // 保证读出来的就是图片文件里的原始像素
            inScaled = false
            // 显式指定配置，ARGB_8888 精度最高
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeResource(context.resources, resId, options)
    }

    /**
     * 函数2: 读取并缩放 (原 decodeSampledBitmap)
     * 按照 targetSize 读取，节省内存，用于 Diffuse 底图显示
     */
    private fun loadScaledBitmap(resId: Int, reqWidth: Int, reqHeight: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, resId, options)

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false

        return BitmapFactory.decodeResource(context.resources, resId, options)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

}