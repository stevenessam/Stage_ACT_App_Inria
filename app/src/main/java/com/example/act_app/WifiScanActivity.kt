package com.example.act_app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import androidx.appcompat.app.ActionBarDrawerToggle

class WifiScanActivity : AppCompatActivity() {

    private lateinit var ssidPrefixInput: EditText
    private lateinit var allNetworksListView: ListView
    private lateinit var cachedNetworksListView: ListView
    private lateinit var progressBar: ProgressBar
    private lateinit var scanningMessage: TextView
    private lateinit var wifiUpdateReceiver: BroadcastReceiver
    private lateinit var signalStrengthSeekBar: SeekBar
    private lateinit var signalStrengthValue: TextView

    private val REQUEST_CODE_PERMISSIONS = 1001
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wifi_scan)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        val drawerLayout: DrawerLayout = findViewById(R.id.drawerLayout)
        val navView: NavigationView = findViewById(R.id.navView)

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_act_map -> {
                    startActivity(Intent(this, ACTMapActivity::class.java))
                }
                R.id.nav_wifi_scan -> {
                    // Already in WifiScanActivity, do nothing
                }
                R.id.nav_about -> {
                    startActivity(Intent(this, AboutActivity::class.java))
                }
            }
            drawerLayout.closeDrawers()
            true
        }

        ssidPrefixInput = findViewById(R.id.ssidPrefixInput)
        allNetworksListView = findViewById(R.id.allNetworksListView)
        cachedNetworksListView = findViewById(R.id.cachedNetworksListView)
        progressBar = findViewById(R.id.progressBar)
        scanningMessage = findViewById(R.id.scanningMessage)
        signalStrengthSeekBar = findViewById(R.id.signalStrengthSeekBar)
        signalStrengthValue = findViewById(R.id.signalStrengthValue)

        val clearCacheButton: Button = findViewById(R.id.clearCacheButton)
        clearCacheButton.setOnClickListener {
            clearWifiCache()
            updateCachedNetworksListView()
            Toast.makeText(this, "Cache Cleared", Toast.LENGTH_SHORT).show()
        }

        // Load cached networks and SSID prefix when the activity is created
        updateCachedNetworksListView()
        loadSsidPrefix()

        // Save SSID prefix whenever it changes
        ssidPrefixInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                saveSsidPrefix(s.toString())
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Set initial value for the SeekBar
        val initialThreshold = getSignalStrengthThreshold()
        signalStrengthSeekBar.progress = initialThreshold + 100
        signalStrengthValue.text = "$initialThreshold dBm"

        signalStrengthSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = progress - 100
                signalStrengthValue.text = "$threshold dBm"
                saveSignalStrengthThreshold(threshold)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Register the BroadcastReceiver to listen for updates from the service
        wifiUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Update the UI with the latest scanned networks
                updateScannedNetworksListView()
                updateCachedNetworksListView()
            }
        }

        val intentFilter = IntentFilter("com.example.act_app.WIFI_UPDATE")
        registerReceiver(wifiUpdateReceiver, intentFilter, RECEIVER_EXPORTED)

        // Check and request permissions
        if (!hasPermissions()) {
            requestPermissions()
        } else {
            // Start the Wi-Fi scan service
            startWifiScanService()
        }
    }

    override fun onResume() {
        super.onResume()
        // Update the UI with the latest scanned networks
        updateScannedNetworksListView()
        updateCachedNetworksListView()
    }

    override fun onPause() {
        super.onPause()
        // No need to stop the service here as it should run in the background
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister the BroadcastReceiver
        unregisterReceiver(wifiUpdateReceiver)
        // Stop the service when the activity is destroyed
        stopWifiScanService()
    }

    private fun updateScannedNetworksListView() {
        val sharedPreferences = getSharedPreferences("wifi_cache", Context.MODE_PRIVATE)
        val scannedNetworks = sharedPreferences.getStringSet("scanned_networks", emptySet()) ?: emptySet()
        val sortedScannedNetworks = scannedNetworks.toList().sortedBy { it.lowercase() }
        val adapterAllNetworks = ArrayAdapter(this, R.layout.list_item_custom, sortedScannedNetworks)
        allNetworksListView.adapter = adapterAllNetworks
    }

    private fun updateCachedNetworksListView() {
        val sharedPreferences = getSharedPreferences("wifi_cache", Context.MODE_PRIVATE)
        val savedSsids = sharedPreferences.getStringSet("saved_ssids", emptySet()) ?: emptySet()
        val sortedSavedSsids = savedSsids.toList().sortedBy { it.lowercase() }
        val adapterCachedNetworks = ArrayAdapter(this, R.layout.list_item_custom, sortedSavedSsids)
        cachedNetworksListView.adapter = adapterCachedNetworks
    }

    private fun clearWifiCache() {
        val sharedPreferences = getSharedPreferences("wifi_cache", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.remove("saved_ssids") // Only remove the cached SSIDs
        editor.apply()
    }

    private fun saveSsidPrefix(prefix: String) {
        val sharedPreferences = getSharedPreferences("wifi_cache", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putString("ssid_prefix", prefix)
        editor.apply()
    }

    private fun loadSsidPrefix() {
        val sharedPreferences = getSharedPreferences("wifi_cache", Context.MODE_PRIVATE)
        val prefix = sharedPreferences.getString("ssid_prefix", "") ?: ""
        ssidPrefixInput.setText(prefix)
    }

    private fun startWifiScanService() {
        val intent = Intent(this, WifiScanService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopWifiScanService() {
        val intent = Intent(this, WifiScanService::class.java)
        stopService(intent)
    }

    private fun hasPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // Permissions granted, start the Wi-Fi scan service
                startWifiScanService()
            } else {
                // Permissions denied, show a message to the user
                Toast.makeText(this, "Permissions are required to scan Wi-Fi networks", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getSignalStrengthThreshold(): Int {
        val sharedPreferences = getSharedPreferences("wifi_cache", Context.MODE_PRIVATE)
        return sharedPreferences.getInt("signal_strength_threshold", -100)
    }

    private fun saveSignalStrengthThreshold(threshold: Int) {
        val sharedPreferences = getSharedPreferences("wifi_cache", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putInt("signal_strength_threshold", threshold)
        editor.apply()
    }
}
