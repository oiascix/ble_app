package com.example.smartdoor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var bt: BluetoothManager
    private lateinit var prefs: LockPrefs
    private lateinit var tvStatus: TextView
    private lateinit var rvLocks: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var adapter: LockAdapter

    private var activeLock: LockEntry? = null
    private val handler = Handler(Looper.getMainLooper())
    private var btStateReceiver: BroadcastReceiver? = null

    // ── Разрешения ────────────────────────────────────────────────────────────

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) updateStatus()
        else toast("Необходимы разрешения Bluetooth для работы приложения")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bt    = BluetoothManager(this)
        prefs = LockPrefs(this)

        tvStatus    = findViewById(R.id.tvStatus)
        rvLocks     = findViewById(R.id.rvLocks)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        progressBar = findViewById(R.id.progressBar)

        setupRecyclerView()
        setupButtons()
        setupBtCallbacks()

        // Следим за состоянием Bluetooth
        btStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == BluetoothAdapter.ACTION_STATE_CHANGED) updateStatus()
            }
        }
        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshLocks()
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        bt.destroy()
        btStateReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = LockAdapter(
            onOpen = { lock -> openLock(lock) },
            onLongClick = { lock -> showLockMenu(lock) }
        )
        rvLocks.layoutManager = LinearLayoutManager(this)
        rvLocks.adapter = adapter
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnAddLock).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        findViewById<Button>(R.id.btnAddLockEmpty)?.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    private fun setupBtCallbacks() {
        bt.onDeviceFound = { name, addr ->
            runOnUiThread { setStatus("🎯 Найдено: $name\n$addr") }
        }

        bt.onConnected = {
            runOnUiThread { setStatus("🔗 Подключено, отправка TOTP..."); showProgress(true) }
            handler.postDelayed({
                val lock = activeLock ?: return@postDelayed
                if (!bt.sendTotpCommand(lock)) {
                    runOnUiThread { setStatus("❌ Ошибка отправки команды"); showProgress(false) }
                }
            }, 500)
        }

        bt.onCommandSent = { cmd ->
            runOnUiThread { setStatus("📤 Команда отправлена\n${cmd.take(8)}••••••••••"); showProgress(false) }
        }

        bt.onCommandResponse = { resp ->
            runOnUiThread {
                setStatus("📥 Ответ: $resp")
                if (resp.contains("OK", ignoreCase = true) ||
                    resp.contains("Получено", ignoreCase = true) ||
                    resp.contains("СОВПАДЕНИЕ", ignoreCase = true)) {
                    setStatus("✅ Замок открыт!")
                    toast("Замок открыт!")
                } else if (resp.contains("Ключ не найден") || resp.contains("ERROR")) {
                    setStatus("❌ Ключ не подошёл\n$resp")
                    toast("Ошибка: ключ не подошёл")
                }
            }
            handler.postDelayed({ bt.disconnect() }, 2000)
        }

        bt.onDisconnected = {
            runOnUiThread { showProgress(false) }
        }

        bt.onDiscoveryFinished = {
            runOnUiThread {
                if (bt.getDiscoveredDevices().isEmpty()) {
                    setStatus("❌ Устройство не найдено\nПроверьте что HC-05 (BLE_LOCKER) включён")
                    showProgress(false)
                }
            }
        }

        bt.onError = { msg ->
            runOnUiThread { setStatus("❌ $msg"); showProgress(false); toast(msg) }
        }
    }

    // ── Open lock ─────────────────────────────────────────────────────────────

    private fun openLock(lock: LockEntry) {
        if (!hasPermissions()) { requestPermissionsIfNeeded(); return }
        if (!bt.isEnabled()) { toast("Включите Bluetooth"); return }

        activeLock = lock
        showProgress(true)
        setStatus("🔍 Поиск ${bt.targetDeviceName}...")
        bt.startDiscovery()
    }

    // ── Lock context menu ─────────────────────────────────────────────────────

    private fun showLockMenu(lock: LockEntry) {
        AlertDialog.Builder(this)
            .setTitle(lock.name)
            .setItems(arrayOf("Открыть", "Переименовать", "Удалить")) { _, i ->
                when (i) {
                    0 -> openLock(lock)
                    1 -> showRenameDialog(lock)
                    2 -> showDeleteConfirm(lock)
                }
            }.show()
    }

    private fun showRenameDialog(lock: LockEntry) {
        val et = EditText(this).apply { setText(lock.name); setPadding(48,24,48,24) }
        AlertDialog.Builder(this)
            .setTitle("Переименовать")
            .setView(et)
            .setPositiveButton("Сохранить") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) { prefs.rename(lock.uid, n); refreshLocks() }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showDeleteConfirm(lock: LockEntry) {
        AlertDialog.Builder(this)
            .setTitle("Удалить замок?")
            .setMessage("Удалить «${lock.name}»? Это действие нельзя отменить.")
            .setPositiveButton("Удалить") { _, _ -> prefs.delete(lock.uid); refreshLocks() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun refreshLocks() {
        val locks = prefs.getAll()
        adapter.submitList(locks)
        rvLocks.visibility    = if (locks.isEmpty()) View.GONE else View.VISIBLE
        layoutEmpty.visibility = if (locks.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setStatus(text: String) { tvStatus.text = text }

    private fun showProgress(show: Boolean) { progressBar.visibility = if (show) View.VISIBLE else View.GONE }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun updateStatus() {
        val sb = StringBuilder()
        if (!bt.isSupported()) sb.append("❌ Bluetooth не поддерживается\n")
        else sb.append("✅ Bluetooth поддерживается\n")
        if (!bt.isEnabled()) sb.append("❌ Bluetooth отключён\n")
        else sb.append("✅ Bluetooth включён\n")
        if (!hasPermissions()) sb.append("⚠️ Нужны разрешения Bluetooth\n")
        else sb.append("✅ Разрешения получены\n")
        setStatus(sb.toString().trim())
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun hasPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter { !hasPermission(it) }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
        else updateStatus()
    }
}

// ── RecyclerView Adapter ───────────────────────────────────────────────────────

class LockAdapter(
    private val onOpen: (LockEntry) -> Unit,
    private val onLongClick: (LockEntry) -> Unit
) : RecyclerView.Adapter<LockAdapter.VH>() {

    private val items = mutableListOf<LockEntry>()

    fun submitList(list: List<LockEntry>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_lock, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) = h.bind(items[pos])
    override fun getItemCount() = items.size

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvName: TextView = v.findViewById(R.id.tvLockName)
        private val tvUid:  TextView = v.findViewById(R.id.tvLockUid)
        private val btnOpen: Button  = v.findViewById(R.id.btnOpenThis)

        fun bind(lock: LockEntry) {
            tvName.text = lock.name
            tvUid.text  = "UID: ${lock.uidStr}"
            btnOpen.setOnClickListener { onOpen(lock) }
            itemView.setOnLongClickListener { onLongClick(lock); true }
        }
    }
}