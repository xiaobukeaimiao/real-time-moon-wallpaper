package com.example.moonwallpaper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 定位助手类
 * 职责：
 * 1. 尝试获取网络或GPS的实时定位
 * 2. 如果获取失败，读取上一次保存的坐标作为兜底
 * 3. 自动将最新的成功坐标存入 SharedPreferences
 */
class LocationHelper(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val prefs: SharedPreferences = context.getSharedPreferences("moon_prefs", Context.MODE_PRIVATE)

    // 默认坐标：北京 (如果这是第一次安装且完全没信号，只能用这个)
    private val DEFAULT_LAT = 39.9
    private val DEFAULT_LNG = 116.4

    // 缓存当前的坐标
    var currentLat: Double = DEFAULT_LAT
    var currentLng: Double = DEFAULT_LNG

    init {
        // 初始化时，先从磁盘读取上一次存的位置
        loadLastLocation()
    }

    /**
     * 核心方法：尝试更新位置
     * 建议在 Wallpaper 的 onVisibilityChanged(true) 或者每隔一段时间调用一次
     */
    fun tryUpdateLocation() {
        // 1. 检查权限 (必须有权限才能请求定位)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w("LocationHelper", "没有定位权限，使用缓存坐标")
            return
        }

        try {
            // 2. 尝试获取“最后已知位置” (Last Known Location)
            // 这是一个非阻塞的快速方法，直接拿系统缓存
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            // 谁比较新就用谁
            var bestLocation: Location? = null
            if (lastGps != null && lastNet != null) {
                bestLocation = if (lastGps.time > lastNet.time) lastGps else lastNet
            } else if (lastGps != null) {
                bestLocation = lastGps
            } else if (lastNet != null) {
                bestLocation = lastNet
            }

            // 如果拿到了有效位置，更新内存并保存
            if (bestLocation != null) {
                updateAndSave(bestLocation.latitude, bestLocation.longitude)
            }

            // 3. 请求一次单次刷新 (可选)

        } catch (e: Exception) {
            Log.e("LocationHelper", "定位出错: ${e.message}")
        }
    }

    /**
     * 更新内存变量，并写入磁盘
     */
    private fun updateAndSave(lat: Double, lng: Double) {
        // 只有当位置变化超过 0.01 度 (约1公里) 时才写入，避免频繁IO
        if (Math.abs(currentLat - lat) > 0.01 || Math.abs(currentLng - lng) > 0.01) {
            currentLat = lat
            currentLng = lng

            // 异步写入磁盘
            prefs.edit().apply {
                putString("saved_lat", lat.toString())
                putString("saved_lng", lng.toString())
                apply() // apply是异步的，commit是同步的
            }
            Log.d("LocationHelper", "位置已更新并保存: $lat, $lng")
        }
    }

    /**
     * 从 SharedPreferences 读取上次的位置
     */
    private fun loadLastLocation() {
        val latStr = prefs.getString("saved_lat", null)
        val lngStr = prefs.getString("saved_lng", null)

        if (latStr != null && lngStr != null) {
            try {
                currentLat = latStr.toDouble()
                currentLng = lngStr.toDouble()
                Log.d("LocationHelper", "已加载缓存位置: $currentLat, $currentLng")
            } catch (e: Exception) {
                // 解析失败，保持默认值
            }
        }
    }
}