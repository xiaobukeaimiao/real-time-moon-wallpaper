import android.graphics.Bitmap
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.max
import com.example.moonwallpaper.MoonTerrainManager

class CraterShadowRenderer(
    private val targetSize: Int
) {


     // 生成符合物理光照的阴影遮罩
    fun generateShadowLayer(phaseDeg: Float): Bitmap? {
         val manager = MoonTerrainManager.getInstanceOrNull()
         // 安全检查
         if (manager == null || !manager.isReady) {
             return null
         }

         val nxArray = manager.cachedNx
         val nyArray = manager.cachedNy
         val nzArray = manager.cachedNz
         val mask = manager.validMask
         val lut = manager.lightLut
         val LUT_SIZE = manager.LUT_SIZE


         // === 1. 构建 3D 光照向量 (Light Vector) ===


        val rad = Math.toRadians(phaseDeg.toDouble())
        val lightX = -sin(rad).toFloat()
        val lightY = 0
        val lightZ = cos(rad).toFloat()

        // === 准备循环 ===
        val totalPixels = targetSize * targetSize
        val outPixels = IntArray(totalPixels)

        // 球体几何参数
        val radius = targetSize / 2f
        val cx = radius
        val cy = radius
        // 预计算倒数
        val invRadius = 1.0f / radius

        // === 晨昏线参数配置 ===
        // 1. 硬切断阈值 (Hard Cutoff):
        // 低于此值无视地形，强制全黑。

        val shadowHardCutoff = -0.04f

        // 2. 衰减区宽度 (Fade Width):
        // 从硬切断位置开始，向受光面延伸多少范围进行亮度“压制”。

        val terminatorFadeWidth = 0.06f

        // 预计算倒数
        val invFadeWidth = 1.0f / terminatorFadeWidth

         //在向阳面进行亮度补偿，0.4f 代表在阳光直射处亮度不会低于40%
         // val lightOffsetIndex = 0.4f
         //val lightOffsetBeginPoint = 0.08
         //预倒数
         //val invOneMinusLightOffsetBeginPoint = 1.0f / (1.0f - lightOffsetBeginPoint)

         //地球反照
         val earthshineFactor = ((1.0 - cos(rad)) / 2.0).pow(2.0)
         // 地球反照最大亮度
         val earthshineMaxBrightness = 0.01
         val earthshineCurrentBrightness = (earthshineMaxBrightness * earthshineFactor).pow(0.45)
         // 地球反照颜色
         val earthshineR = (255 * earthshineCurrentBrightness * 0.8).toInt().coerceIn(0, 255)
         val earthshineG = (255 * earthshineCurrentBrightness * 0.9).toInt().coerceIn(0, 255)
         val earthshineB = (255 * earthshineCurrentBrightness * 1.0).toInt().coerceIn(0, 255)
         val earthshineColor = (0xFF shl 24) or (earthshineR shl 16) or (earthshineG shl 8) or earthshineB


        for (y in 0 until targetSize) {
            // 预计算当前行的 Y 分量 (归一化到 -1.0 ~ 1.0)
            // 注意：Bitmap Y轴向下，如果要对齐3D逻辑可能需要反转，但在 LightY=0 时无所谓
            val ny = (y - cy) * invRadius
            val ny2 = ny * ny

            val rowOffset = y * targetSize

            for (x in 0 until targetSize) {
                val i = rowOffset + x

                // 1. 跳过圆外像素
                if (mask[i] == 0.toByte()) {
                    outPixels[i] = 0
                    continue
                }

                // === 2. 实时计算球体几何法线 (Geometric Normal) ===
                // 归一化 X 分量
                val nx = (x - cx) * invRadius

                // 计算 Z 分量: x^2 + y^2 + z^2 = 1  ->  z = sqrt(1 - x^2 - y^2)
                // validMask 已经保证了在圆内，但在边缘处可能会因为浮点误差出现负数，需 max(0)
                val nzSq = 1.0f - nx * nx - ny2
                val nz = sqrt(max(0f, nzSq)) // Z 轴指向屏幕外(观察者)

                // === 3. 计算基于球体几何的点积 ===
                // 这里的 dot 仅反映"该像素点在理想球体上相对于光线的角度"
                // 不受地形起伏干扰，保证晨昏线是平滑的弧形
                val geoDot = nx * lightX + ny * lightY + nz * lightZ

                // 2. 计算基础点积
                val dot = nxArray[i] * lightX + nyArray[i] * lightY + nzArray[i] * lightZ


                // === 3. 核心修正逻辑 ===

                // 基础点积查LUT得到地形光照强度
                val lutIndex = (max(0f, dot) * LUT_SIZE).toInt()
                val baseIntensity = if (lutIndex < LUT_SIZE) lut[lutIndex] else 1.0f

                // 晨昏线遮罩：基于几何法线控制月相过渡
                var terminatorMask = (geoDot - shadowHardCutoff) * invFadeWidth
                if (terminatorMask > 1.0f) terminatorMask = 1.0f
                else if (terminatorMask < 0.0f) terminatorMask = 0.0f

                // 地形凹凸阴影（环形山）
                val terrainShadow = max(0f, 1.0f - baseIntensity)

                // 晨昏线阴影
                val terminatorShadow = max(0f, 1.0f - terminatorMask)

                // 满月时地形阴影减半
                val shadowScale = 1.0f - 0.5f * max(0.0f, cos(rad).toFloat())
                val reducedTerrainShadow = terrainShadow * shadowScale

                // 合并两种阴影
                val alphaFloat = 1.0f - (1.0f - reducedTerrainShadow) * (1.0f - terminatorShadow)

                // 绝对阴影区：强制最深阴影
                val finalShadowDepth = if (geoDot <= shadowHardCutoff) 1.0f else alphaFloat

                // === 4. 亮度值 = 1 - 阴影深度，作为地球反照和透明遮罩之间的加权权重 ===
                val brightness = 1.0f - finalShadowDepth

                val finalR = (earthshineR + (255 - earthshineR) * brightness).toInt().coerceIn(0, 255)
                val finalG = (earthshineG + (255 - earthshineG) * brightness).toInt().coerceIn(0, 255)
                val finalB = (earthshineB + (255 - earthshineB) * brightness).toInt().coerceIn(0, 255)

                outPixels[i] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB

            }
        }
        return Bitmap.createBitmap(outPixels, targetSize, targetSize, Bitmap.Config.ARGB_8888)


    }
}