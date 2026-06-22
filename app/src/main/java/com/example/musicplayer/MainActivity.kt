package com.example.musicplayer

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class Song(val id: Long, val title: String, val artist: String, val uri: Uri)

class MainActivity : AppCompatActivity() {

    private lateinit var mediaPlayer: MediaPlayer
    private var songList = mutableListOf<Song>()
    private var currentSongIndex = -1
    private lateinit var adapter: SongAdapter
    private lateinit var locationManager: LocationManager
    private var currentSpeed = 0f

    // ذخیره Listener برای حذف در onDestroy
    private lateinit var locationListener: LocationListener

    // Handler برای به‌روزرسانی SeekBar
    private val handler = Handler(Looper.getMainLooper())
    private val updateSeekBarRunnable = object : Runnable {
        override fun run() {
            if (::mediaPlayer.isInitialized && mediaPlayer.isPlaying) {
                findViewById<SeekBar>(R.id.seekBar)?.apply {
                    max = mediaPlayer.duration
                    progress = mediaPlayer.currentPosition
                }
            }
            handler.postDelayed(this, 500) // هر ۵۰۰ میلی‌ثانیه
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaPlayer = MediaPlayer()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        val tvSpeed = findViewById<TextView>(R.id.tvSpeed)
        val tvNowPlaying = findViewById<TextView>(R.id.tvNowPlaying)
        val seekBar = findViewById<SeekBar>(R.id.seekBar)
        val btnPlayPause = findViewById<Button>(R.id.btnPlayPause)
        val btnNext = findViewById<Button>(R.id.btnNext)
        val btnPrev = findViewById<Button>(R.id.btnPrev)

        recyclerView.layoutManager = LinearLayoutManager(this)
        loadSongs()
        adapter = SongAdapter(songList) { _, index ->
            playSong(index)
        }
        recyclerView.adapter = adapter

        // تعریف LocationListener
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.hasSpeed()) {
                    currentSpeed = location.speed * 3.6f // m/s → km/h
                    tvSpeed.text = "سرعت: ${currentSpeed.toInt()} km/h"
                    adjustVolumeBasedOnSpeed()
                }
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        // اگر مجوز لوکیشن قبلاً داده شده باشد، مستقیم راه‌اندازی کن
        if (checkLocationPermission()) {
            startLocationUpdates()
        } else {
            // درخواست همه مجوزها (فضای ذخیره‌سازی و لوکیشن)
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ), 100
            )
        }

        // کنترل‌ها
        btnPlayPause.setOnClickListener {if (::mediaPlayer.isInitialized) {
                if (mediaPlayer.isPlaying) {
                    mediaPlayer.pause()
                    btnPlayPause.text = "پخش"
                } else if (currentSongIndex != -1) {
                    mediaPlayer.start()
                    btnPlayPause.text = "توقف"
                }
            }
        }

        btnNext.setOnClickListener { playNext() }
        btnPrev.setOnClickListener { playPrevious() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && ::mediaPlayer.isInitialized) {
                    mediaPlayer.seekTo(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // شروع به‌روزرسانی SeekBar
        handler.post(updateSeekBarRunnable)
    }

    private fun loadSongs() {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "ناشناس"
                val artist = cursor.getString(artistCol) ?: "ناشناس"
                val contentUri: Uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                songList.add(Song(id, title, artist, contentUri))
            }
        }
    }

    private fun playSong(index: Int) {
        if (index < 0 || index >= songList.size) return
        currentSongIndex = index
        val song = songList[index]

        mediaPlayer.reset()
        try {
            mediaPlayer.setDataSource(applicationContext, song.uri)
            mediaPlayer.setOnPreparedListener { mp ->
                mp.start()
                findViewById<SeekBar>(R.id.seekBar).max = mp.duration
                findViewById<Button>(R.id.btnPlayPause).text = "توقف"
            }
            mediaPlayer.prepareAsync()
            findViewById<TextView>(R.id.tvNowPlaying).text = "در حال پخش: ${song.title}"
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در پخش فایل", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playNext() {
        if (currentSongIndex < songList.size - 1) playSong(currentSongIndex + 1)
    }

    private fun playPrevious() {
        if (currentSongIndex > 0) playSong(currentSongIndex - 1)
    }

    private fun adjustVolumeBasedOnSpeed() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        // درصد حجم صدا بر اساس سرعت (0% تا 100%)
        val factor = when {
            currentSpeed < 20 -> 0.4f
            currentSpeed < 40 -> 0.55f
            currentSpeed < 60 -> 0.7f
            currentSpeed < 80 -> 0.85f
            else -> 1.0f
        }
        val targetVolume = (factor * maxVolume).toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
    }

    private fun checkLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }private fun startLocationUpdates() {
        if (checkLocationPermission()) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2000, 0f, locationListener
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED // فضای ذخیره‌سازی (در صورت نیاز)
            ) {
                // برای اندروید قدیمی
            }
            if (grantResults.size > 1 &&
                grantResults[1] == PackageManager.PERMISSION_GRANTED // لوکیشن
            ) {
                startLocationUpdates()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateSeekBarRunnable)
        if (::locationManager.isInitialized && ::locationListener.isInitialized) {
            locationManager.removeUpdates(locationListener)
        }
        if (::mediaPlayer.isInitialized) {
            mediaPlayer.release()
        }
    }
}

// آداپتور ساده برای لیست آهنگ‌ها
class SongAdapter(
    private val songs: List<Song>,
    private val onClick: (Song, Int) -> Unit
) : RecyclerView.Adapter<SongAdapter.ViewHolder>() {

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        holder.title.text = "${song.title} - ${song.artist}"
        holder.itemView.setOnClickListener { onClick(song, position) }
    }

    override fun getItemCount() = songs.size
}
