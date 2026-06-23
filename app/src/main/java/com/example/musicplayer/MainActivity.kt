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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class Song(val id: Long, val title: String, val artist: String, val uri: Uri)

class MainActivity : AppCompatActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private val songList = mutableListOf<Song>()
    private var currentSongIndex = -1
    private lateinit var adapter: SongAdapter
    private var currentSpeed = 0f
    private var locationListener: LocationListener? = null
    private var locationManager: LocationManager? = null

    private val handler = Handler(Looper.getMainLooper())
    private val updateSeekBarRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    findViewById<SeekBar>(R.id.seekBar)?.apply {
                        max = mp.duration
                        progress = mp.currentPosition
                    }
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaPlayer = MediaPlayer()

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        val tvSpeed = findViewById<TextView>(R.id.tvSpeed)
        val tvNowPlaying = findViewById<TextView>(R.id.tvNowPlaying)
        val seekBar = findViewById<SeekBar>(R.id.seekBar)
        val btnPlayPause = findViewById<Button>(R.id.btnPlayPause)
        val btnNext = findViewById<Button>(R.id.btnNext)
        val btnPrev = findViewById<Button>(R.id.btnPrev)
        val tvEmpty = findViewById<TextView>(R.id.tvEmpty) // اضافه‌شده

        recyclerView.layoutManager = LinearLayoutManager(this)
        loadSongs()
        adapter = SongAdapter(songList) { _, index -> playSong(index) }
        recyclerView.adapter = adapter

        // اگر لیست آهنگ خالی است، پیام "خالی" را نشان بده
        if (songList.isEmpty()) {
            recyclerView.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
        }

        requestAllPermissions()

        // راه‌اندازی GPS بدون کرش
        try {
            locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager
            if (locationManager != null && hasLocationPermission()) {
                startLocationUpdates()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "سنسور سرعت فعال نیست", Toast.LENGTH_SHORT).show()
        }

        btnPlayPause.setOnClickListener {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.pause()
                    btnPlayPause.text = "پخش"
                } else if (currentSongIndex != -1) {
                    mp.start()
                    btnPlayPause.text = "توقف"
                }
            }
        }

        btnNext.setOnClickListener { playNext() }
        btnPrev.setOnClickListener { playPrevious() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) mediaPlayer?.seekTo(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        handler.post(updateSeekBarRunnable)
    }

    private fun loadSongs() {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )
        try {
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
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                    )
                    songList.add(Song(id, title, artist, contentUri))
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در خواندن فایل‌های صوتی", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playSong(index: Int) {
        if (index < 0 || index >= songList.size) return
        currentSongIndex = index
        val song = songList[index]

        try {
            mediaPlayer?.reset()
            mediaPlayer?.setDataSource(applicationContext, song.uri)
            mediaPlayer?.setOnPreparedListener { mp ->
                mp.start()
                findViewById<SeekBar>(R.id.seekBar)?.max = mp.duration
                findViewById<Button>(R.id.btnPlayPause)?.text = "توقف"
            }
            mediaPlayer?.prepareAsync()
            findViewById<TextView>(R.id.tvNowPlaying)?.text = "در حال پخش: ${song.title}"
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
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val factor = when {
                currentSpeed < 20 -> 0.4f
                currentSpeed < 40 -> 0.55f
                currentSpeed < 60 -> 0.7f
                currentSpeed < 80 -> 0.85f
                else -> 1.0f
            }
            val targetVolume = (factor * maxVolume).toInt().coerceIn(0, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        } catch (e: Exception) {
            // بی‌صدا عبور کن
        }
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (!hasLocationPermission())
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (permissions.isNotEmpty())
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startLocationUpdates() {
        try {
            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (location.hasSpeed()) {
                        currentSpeed = location.speed * 3.6f
                        findViewById<TextView>(R.id.tvSpeed)?.text = "سرعت: ${currentSpeed.toInt()} km/h"
                        adjustVolumeBasedOnSpeed()
                    }
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2000, 0f, locationListener!!
            )
        } catch (e: Exception) {
            Toast.makeText(this, "GPS فعال نیست", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION) &&
                grantResults.getOrNull(permissions.indexOf(Manifest.permission.ACCESS_FINE_LOCATION)) == PackageManager.PERMISSION_GRANTED
            ) {
                startLocationUpdates()
            }
            // بازخوانی لیست اگر مجوز رسانه صادر شد و لیست خالی است
            if (songList.isEmpty()) {
                loadSongs()
                adapter.notifyDataSetChanged()
                if (songList.isNotEmpty()) {
                    findViewById<RecyclerView>(R.id.recyclerView).visibility = View.VISIBLE
                    findViewById<TextView>(R.id.tvEmpty).visibility = View.GONE
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateSeekBarRunnable)
        locationListener?.let { locationManager?.removeUpdates(it) }
        mediaPlayer?.release()
        mediaPlayer = null
    }
}

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
