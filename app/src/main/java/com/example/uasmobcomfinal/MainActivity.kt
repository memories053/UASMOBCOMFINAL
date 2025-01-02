package com.example.uasmobcomfinal

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.media3.common.Player
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit
import retrofit2.Retrofit

data class SpotifyTrack(
    val id: String,
    val name: String,
    val artists: List<SpotifyArtist>,
    val preview_url: String?,
    val external_urls: Map<String, String>
)

data class SpotifyArtist(
    val name: String
)

data class SpotifySearchResponse(
    val tracks: SpotifyTracks
)

data class SpotifyTracks(
    val items: List<SpotifyTrack>
)

data class SpotifySearchSuggestions(
    val suggestions: List<String>
)

interface SpotifyApiService {
    @GET("v1/search")
    suspend fun searchTracks(
        @Query("q") query: String,
        @Query("type") type: String = "track,artist",
        @Query("limit") limit: Int = 10
    ): SpotifySearchResponse

    @GET("v1/search/suggestions")
    suspend fun getSearchSuggestions(
        @Query("q") query: String
    ): SpotifySearchSuggestions
}


class SpotifyRepository(private val context: Context) {
    private val clientId = "bd0cad8ed41346338d32c78a7a45f6cc"
    private val redirectUri = "myapp://callback"
    private var _accessToken: String? = null

    fun setAccessToken(token: String) {
        _accessToken = token
    }

    private val spotifyApi by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $_accessToken")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(loggingInterceptor)
            .build()

        Retrofit.Builder()
            .baseUrl("https://api.spotify.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SpotifyApiService::class.java)
    }

    suspend fun searchTracks(query: String): List<SpotifyTrack> {
        return try {
            if (_accessToken == null) {
                return emptyList()
            }
            spotifyApi.searchTracks(query).tracks.items
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun authenticateSpotify(activity: Activity) {
        val builder = AuthorizationRequest.Builder(
            clientId,
            AuthorizationResponse.Type.TOKEN,
            redirectUri
        )

        builder.setScopes(arrayOf("streaming"))
        val request = builder.build()
        AuthorizationClient.openLoginActivity(activity, REQUEST_CODE, request)
    }

    companion object {
        const val REQUEST_CODE = 1337
    }
}

@Entity(tableName = "songs")
data class Song(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val artist: String,
    val path: String,
    var isFavorite: Boolean = false
)

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val songs: List<String> = emptyList()
)

class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return value.joinToString(",")
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        return if (value.isEmpty()) emptyList() else value.split(",")
    }
}

@Database(entities = [Song::class, Playlist::class], version = 1)
@TypeConverters(Converters::class)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
}

@Dao
interface SongDao {
    @androidx.room.Query("SELECT * FROM songs")
    suspend fun getAllSongs(): List<Song>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: Song)

    @Update
    suspend fun updateSong(song: Song)

    @androidx.room.Query("SELECT * FROM songs WHERE isFavorite = 1")
    suspend fun getFavoriteSongs(): List<Song>

    @Delete
    suspend fun deleteSong(song: Song)
}

@Dao
interface PlaylistDao {
    @androidx.room.Query("SELECT * FROM playlists")
    suspend fun getAllPlaylists(): List<Playlist>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist)

    @Update
    suspend fun updatePlaylist(playlist: Playlist)
}

class MusicPlayerViewModel(
    private val context: Application,
    private val database: MusicDatabase
) : AndroidViewModel(context) {
    private val spotifyRepository = SpotifyRepository(context)
    private val _searchResults = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val searchResults: StateFlow<List<SpotifyTrack>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var exoPlayer: ExoPlayer? = null
    private var progressJob: Job? = null
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()
    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    fun setSpotifyToken(token: String) {
        spotifyRepository.setAccessToken(token)
    }

    init {
        viewModelScope.launch {
            loadSongs()
            loadPlaylists()
            initializePlayer()
        }
    }

    fun searchSpotify(query: String) {
        viewModelScope.launch {
            _isSearching.value = true
            try {
                _searchResults.value = spotifyRepository.searchTracks(query)
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun authenticateSpotify(activity: Activity) {
        spotifyRepository.authenticateSpotify(activity)
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_ENDED -> {
                            playNextSong()
                        }
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.value = playing
                    if (playing) {
                        startProgressUpdate()
                    }
                }
            })
        }
    }

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                delay(1000) // Update every second
                exoPlayer?.let { player ->
                    _currentPosition.value = player.currentPosition
                    _duration.value = player.duration
                }
            }
        }
    }

    private suspend fun loadSongs() {
        _songs.value = database.songDao().getAllSongs()
    }

    private suspend fun loadPlaylists() {
        _playlists.value = database.playlistDao().getAllPlaylists()
    }

    fun playSong(song: Song) {
        viewModelScope.launch {
            exoPlayer?.let { player ->
                val mediaItem = MediaItem.fromUri(song.path)
                player.setMediaItem(mediaItem)
                player.prepare()
                player.play()
                _currentSong.value = song
                _isPlaying.value = true
            }
        }
    }

    fun togglePlayPause() {
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                progressJob?.cancel()
            } else {
                player.play()
                startProgressUpdate()
            }
            _isPlaying.value = player.isPlaying
        }
    }

    fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
    }

    fun playNextSong() {
        val songs = _songs.value
        val currentIndex = currentSong.value?.let { current ->
            songs.indexOfFirst { it.id == current.id }
        } ?: -1

        if (currentIndex < songs.size - 1) {
            playSong(songs[currentIndex + 1])
        }
    }

    fun playPreviousSong() {
        val songs = _songs.value
        val currentIndex = currentSong.value?.let { current ->
            songs.indexOfFirst { it.id == current.id }
        } ?: -1

        if (currentIndex > 0) {
            playSong(songs[currentIndex - 1])
        }
    }

    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            val updatedSong = song.copy(isFavorite = !song.isFavorite)
            database.songDao().updateSong(updatedSong)
            loadSongs()
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val playlist = Playlist(name = name)
            database.playlistDao().insertPlaylist(playlist)
            loadPlaylists()
        }
    }

    fun addSongToPlaylist(playlistId: String, songId: String) {
        viewModelScope.launch {
            val playlist = _playlists.value.find { it.id == playlistId } ?: return@launch
            val updatedPlaylist = playlist.copy(
                songs = playlist.songs + songId
            )
            database.playlistDao().updatePlaylist(updatedPlaylist)
            loadPlaylists()
        }
    }

    override fun onCleared() {
        super.onCleared()
        progressJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
    }

    fun addSongFromUri(uri: Uri) {
        viewModelScope.launch {
            context.contentResolver.query(
                uri,
                null,
                null,
                null,
                null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                val title = cursor.getString(nameIndex)

                val song = Song(
                    title = title.removeSuffix(".mp3"),
                    artist = "Unknown Artist", // You can add metadata extraction here
                    path = uri.toString()
                )

                database.songDao().insertSong(song)
                loadSongs()
            }
        }
    }

    fun deleteSong(song: Song) {
        viewModelScope.launch {
            database.songDao().deleteSong(song)
            loadSongs()
        }
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MusicPlayerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Room database
        val database = Room.databaseBuilder(
            applicationContext,
            MusicDatabase::class.java,
            "music_database"
        ).build()

        // Initialize ViewModel
        viewModel = MusicPlayerViewModel(application, database)

        setContent {
            MaterialTheme {
                MusicPlayerApp(viewModel)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SpotifyRepository.REQUEST_CODE) {
            val response = AuthorizationClient.getResponse(resultCode, data)
            when (response.type) {
                AuthorizationResponse.Type.TOKEN -> {
                    val accessToken = response.accessToken
                    viewModel.setSpotifyToken(accessToken)
                }
                AuthorizationResponse.Type.ERROR -> {
                    // Handle error
                }
                else -> {
                    // Handle other cases
                }
            }
        }
    }
}

@Composable
fun SpotifySearchScreen(viewModel: MusicPlayerViewModel) {
    var searchQuery by remember { mutableStateOf("") }
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = {
                searchQuery = it
                viewModel.searchSpotify(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search Spotify") },
            leadingIcon = { Icon(Icons.Default.Search, "Search") },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Loading indicator
        if (isSearching) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }

        // Search results
        LazyColumn {
            items(searchResults) { track ->
                SpotifyTrackItem(
                    track = track,
                    onPlayClick = {
                        // Convert Spotify track to local Song and play
                        val song = Song(
                            title = track.name,
                            artist = track.artists.joinToString { it.name },
                            path = track.preview_url ?: return@SpotifyTrackItem,
                            isFavorite = false
                        )
                        viewModel.playSong(song)
                    }
                )
            }
        }
    }
}

@Composable
fun SpotifyTrackItem(
    track: SpotifyTrack,
    onPlayClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onPlayClick() }
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = track.artists.joinToString { it.name },
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            IconButton(onClick = onPlayClick) {
                Icon(Icons.Default.PlayArrow, "Play")
            }
        }
    }
}

@Composable
fun MusicPlayerApp(viewModel: MusicPlayerViewModel) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        bottomBar = {
            Column {
                // Now Playing Bar above Navigation Bar
                CurrentlyPlayingBar(viewModel)

                // Navigation Bar
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Star, "Songs") },
                        label = { Text("Songs") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Favorite, "Favorites") },
                        label = { Text("Favorites") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.PlayArrow, "Playlists") },
                        label = { Text("Playlists") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Default.Search, "Search Spotify") },
                        label = { Text("Search") }
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> SongsList(viewModel)
                1 -> FavoritesList(viewModel)
                2 -> PlaylistsScreen(viewModel)
                3 -> SpotifySearchScreen(viewModel)
            }
        }
    }
}

@Composable
fun SongsList(viewModel: MusicPlayerViewModel) {
    val songs by viewModel.songs.collectAsState()
    var showImportDialog by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.addSongFromUri(it) }
    }

    Column {
        // Import button
        Button(
            onClick = { launcher.launch("audio/*") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Import",
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("Import Song")
            }
        }

        // Songs list
        LazyColumn {
            items(songs) { song ->
                SongItem(
                    song = song,
                    onPlayClick = { viewModel.playSong(song) },
                    onFavoriteClick = { viewModel.toggleFavorite(song) },
                    onDeleteClick = { viewModel.deleteSong(song) }
                )
            }
        }
    }
}

@Composable
fun FavoritesList(viewModel: MusicPlayerViewModel) {
    val songs by viewModel.songs.collectAsState()
    val favoriteSongs = songs.filter { it.isFavorite }

    LazyColumn {
        items(favoriteSongs) { song ->
            SongItem(
                song = song,
                onPlayClick = { viewModel.playSong(song) },
                onFavoriteClick = { viewModel.toggleFavorite(song) },
                onDeleteClick = { viewModel.deleteSong(song) }
            )
        }
    }
}

@Composable
fun PlaylistsScreen(viewModel: MusicPlayerViewModel) {
    val playlists by viewModel.playlists.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    Column {
        Button(
            onClick = { showCreateDialog = true },
            modifier = Modifier.padding(16.dp)
        ) {
            Text("Create New Playlist")
        }

        LazyColumn {
            items(playlists) { playlist ->
                Text(
                    text = playlist.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create Playlist") },
            text = {
                TextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Playlist Name") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newPlaylistName.isNotBlank()) {
                        viewModel.createPlaylist(newPlaylistName)
                        newPlaylistName = ""
                        showCreateDialog = false
                    }
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                Button(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SongItem(
    song: Song,
    onPlayClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = song.title, style = MaterialTheme.typography.bodyLarge)
            Text(text = song.artist, style = MaterialTheme.typography.bodyMedium)
        }

        IconButton(onClick = onPlayClick) {
            Icon(Icons.Default.PlayArrow, "Play")
        }

        IconButton(onClick = onFavoriteClick) {
            Icon(
                if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                "Favorite"
            )
        }

        IconButton(onClick = { showDeleteDialog = true }) {
            Icon(Icons.Default.Delete, "Delete")
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Song") },
            text = { Text("Are you sure you want to delete '${song.title}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteClick()
                        showDeleteDialog = false
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                Button(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun CurrentlyPlayingBar(viewModel: MusicPlayerViewModel) {
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()

    currentSong?.let { song ->
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
            ) {
                // Song info and time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = song.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row {
                        Text(formatDuration(currentPosition))
                        Text(" / ")
                        Text(formatDuration(duration))
                    }
                }

                // Seek bar
                Slider(
                    value = currentPosition.toFloat(),
                    onValueChange = { viewModel.seekTo(it.toLong()) },
                    valueRange = 0f..duration.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )

                // Playback controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.playPreviousSong() }) {
                        Icon(painter = painterResource(R.drawable.skip_previous), contentDescription = "Skip Previous")
                    }

                    IconButton(
                        onClick = { viewModel.togglePlayPause() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Share else Icons.Default.PlayArrow,
                            "Play/Pause",
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(onClick = { viewModel.playNextSong() }) {
                        Icon(painter = painterResource(R.drawable.skip_next), contentDescription = "Skip Next")
                    }
                }
            }
        }
    }
}

private fun formatDuration(durationMillis: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMillis) -
            TimeUnit.MINUTES.toSeconds(minutes)
    return String.format("%02d:%02d", minutes, seconds)
}