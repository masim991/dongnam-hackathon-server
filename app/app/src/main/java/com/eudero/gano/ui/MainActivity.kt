package com.eudero.gano.ui

import android.os.Build
import android.content.pm.PackageManager
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.eudero.gano.health.HeartRate
import android.Manifest
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.eudero.gano.R
import com.eudero.gano.update.Updater
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {
    private lateinit var nav: BottomNavigationView
    private lateinit var captionView: android.widget.TextView
    private var demo: DemoMode? = null
    private var mapReady = false
    private var demoRequested = false
    private var navRequested = true
    fun setNavVisible(v: Boolean) {
        navRequested = v
        nav.visibility = when { !v -> View.GONE; mapReady -> View.VISIBLE; else -> View.INVISIBLE }
    }
    fun setMapReady(ready: Boolean) {
        mapReady = ready
        setNavVisible(navRequested)
        if (ready && demoRequested) { demoRequested = false; startDemo() }
    }
    fun showTab(id: Int) { nav.selectedItemId = id }
    fun mapHost(): MapHostFragment? = supportFragmentManager.findFragmentByTag("map") as? MapHostFragment
    /** 시연 자막(영상 녹화용): 위쪽에 잠깐 보여준다 */
    fun caption(t: String?) {
        if (t == null) { captionView.animate().alpha(0f).setDuration(200).start(); return }
        captionView.text = t; captionView.animate().cancel(); captionView.alpha = 0f; captionView.visibility = View.VISIBLE; captionView.animate().alpha(1f).setDuration(250).start()
    }
    fun startDemo() { if (!mapReady) { demoRequested = true; return }; if (demo == null) demo = DemoMode(this); demo!!.start() }
    private fun postDemoNotification() {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("demo", "시연", android.app.NotificationManager.IMPORTANCE_LOW))
        val open = android.app.PendingIntent.getActivity(this, 20, android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP), android.app.PendingIntent.FLAG_IMMUTABLE)
        val go = android.app.PendingIntent.getActivity(this, 21, android.content.Intent(this, MainActivity::class.java).putExtra("demo", true).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP), android.app.PendingIntent.FLAG_IMMUTABLE)
        nm.notify(22, android.app.Notification.Builder(this, "demo").setSmallIcon(R.drawable.ic_stat_run).setContentTitle("뛰고보자").setContentText("시연을 누르면 모든 기능을 차례로 보여줘요")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(android.app.Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_run), "시연", go).build()).build())
    }
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (com.eudero.gano.run.RunState.demo) return true
        return super.dispatchTouchEvent(event)
    }
    override fun onNewIntent(i: android.content.Intent) { super.onNewIntent(i); setIntent(i); if (i.getBooleanExtra("demo", false)) { i.removeExtra("demo"); startDemo() } }
    private val frags = linkedMapOf<Int, Fragment>()
    private val motion = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val health = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { grants ->
        HeartRate.connect(this, grants)
        (frags[R.id.nav_me] as? ProfileFragment)?.refreshHealth()
        if (HeartRate.writeSteps in grants && Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED &&
            getSystemService(android.hardware.SensorManager::class.java).getDefaultSensor(android.hardware.Sensor.TYPE_STEP_DETECTOR) != null)
            motion.launch(Manifest.permission.ACTIVITY_RECOGNITION)
    }
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startHealthOnboarding() }
    private fun startHealthOnboarding() {
        val prefs = getSharedPreferences("permissions", MODE_PRIVATE)
        if (prefs.getBoolean("healthAsked", false) || HeartRate.status(this) != HealthConnectClient.SDK_AVAILABLE) return
        prefs.edit().putBoolean("healthAsked", true).apply()
        requestHealthConnection()
    }
    fun requestHealthConnection() {
        if (HeartRate.status(this) != HealthConnectClient.SDK_AVAILABLE) {
            android.widget.Toast.makeText(this, "이 폰에서는 건강 앱을 연결할 수 없어요", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val client = HealthConnectClient.getOrCreate(this@MainActivity)
            val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
            if (granted.containsAll(HeartRate.permissions)) {
                HeartRate.connect(this@MainActivity, granted)
                (frags[R.id.nav_me] as? ProfileFragment)?.refreshHealth()
            } else health.launch(HeartRate.permissions)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        // 상태바 뒤까지 꽉 채우는 화면(투명 상태바): 지도가 맨 위까지 보인다
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val container = FrameLayout(this).apply { id = R.id.container }
        nav = BottomNavigationView(this).apply { inflateMenu(R.menu.bottom_nav); setBackgroundColor(0xFF14171C.toInt()); itemIconTintList = null; itemIconTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0xFFB9FA03.toInt(), 0xFF8A8F98.toInt())); itemTextColor = itemIconTintList; itemActiveIndicatorColor = android.content.res.ColorStateList.valueOf(0x22B9FA03) }
        setNavVisible(true)
        root.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            v.setPadding(0, 0, 0, ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom); ins
        }
        captionView = android.widget.TextView(this).apply {
            textSize = 16f; setTextColor(android.graphics.Color.WHITE); typeface = Fonts.bold; gravity = android.view.Gravity.CENTER; visibility = View.GONE
            val dd = resources.displayMetrics.density; setPadding((18 * dd).toInt(), (10 * dd).toInt(), (18 * dd).toInt(), (10 * dd).toInt()); Glass.apply(this, 18f, 0xEE); isClickable = false
        }
        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        frame.addView(captionView, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL).also { it.topMargin = (190 * resources.displayMetrics.density).toInt() })
        setContentView(frame)

        frags[R.id.nav_map] = supportFragmentManager.findFragmentByTag("map") ?: MapHostFragment()
        frags[R.id.nav_record] = supportFragmentManager.findFragmentByTag("record") ?: RecordFragment()
        frags[R.id.nav_me] = supportFragmentManager.findFragmentByTag("me") ?: ProfileFragment()
        val tags = mapOf(R.id.nav_map to "map", R.id.nav_record to "record", R.id.nav_me to "me")
        if (s == null) supportFragmentManager.beginTransaction().apply {
            frags.forEach { (id, f) -> add(R.id.container, f, tags[id]); if (id != R.id.nav_map) hide(f) }
        }.commit()
        nav.setOnItemSelectedListener { item ->
            if (!mapReady && item.itemId != R.id.nav_map) return@setOnItemSelectedListener false
            supportFragmentManager.beginTransaction().setCustomAnimations(R.anim.fade_in, R.anim.fade_out).apply {
                frags.forEach { (id, f) -> if (id == item.itemId) show(f) else hide(f) }
            }.commitAllowingStateLoss()
            true
        }

        if (s != null) showTab(R.id.nav_map)
        if (s == null) {
            val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
            if (needed.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) perms.launch(needed.toTypedArray())
            else startHealthOnboarding()
        }
        Updater.check(this)
        postDemoNotification()
        if (intent?.getBooleanExtra("demo", false) == true) { intent.removeExtra("demo"); window.decorView.postDelayed({ startDemo() }, 1500) }
    }

    override fun onResume() { super.onResume(); com.eudero.gano.health.HeartRate.sync(this) }

    override fun onDestroy() { demo?.cancel(); if (isFinishing) getSystemService(android.app.NotificationManager::class.java).cancel(22); super.onDestroy() }
}
