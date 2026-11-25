package com.greenlink.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

private val Forest = Color(0xFF123D2B)
private val Leaf = Color(0xFF35C982)
private val Mint = Color(0xFFE8F6EE)
private val Ink = Color(0xFF14251C)
private val Muted = Color(0xFF718278)
private val Bg = Color(0xFFF5F8F5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("greencall", Context.MODE_PRIVATE)
        intent?.getStringExtra("conversation_id")?.let { prefs.edit().putString("notification_conversation_id", it).apply() }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9101)
        }
        setContent { GreenCallApp(prefs) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("conversation_id")?.let {
            getSharedPreferences("greencall", Context.MODE_PRIVATE).edit()
                .putString("notification_conversation_id", it).apply()
        }
    }
}

private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

private fun parseArray(raw: String): List<String> = runCatching {
    val a = JSONArray(raw); (0 until a.length()).map { a.getString(it) }
}.getOrDefault(emptyList())

@Composable
private fun GreenCallApp(prefs: android.content.SharedPreferences) {
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var account by remember { mutableStateOf(if (token.isNotBlank()) prefs.getString("session", "") ?: "" else "") }
    val scope = rememberCoroutineScope()
    var authPage by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var friends by remember { mutableStateOf(emptyList<String>()) }
    var groups by remember { mutableStateOf(emptyList<String>()) }
    var selected by remember { mutableStateOf("") }
    var muted by remember { mutableStateOf(prefs.getBoolean("muted", false)) }

    fun loadUserData(user: String) {
        friends = parseArray(prefs.getString("friends_$user", "[]") ?: "[]")
        groups = parseArray(prefs.getString("groups_$user", "[]") ?: "[]")
    }
    fun saveFriends(values: List<String>) {
        friends = values.distinct()
        prefs.edit().putString("friends_$account", JSONArray(friends).toString()).apply()
    }
    fun saveGroups(values: List<String>) {
        groups = values.distinct()
        prefs.edit().putString("groups_$account", JSONArray(groups).toString()).apply()
    }

    val appContext = LocalContext.current
    DisposableEffect(appContext, account, token) {
        if (account.isNotBlank() && token.isNotBlank()) {
            val serviceIntent = Intent(appContext, VoiceSessionService::class.java)
                .putExtra("token", token)
                .putExtra("user_id", prefs.getString("user_id", "") ?: "")
            ContextCompat.startForegroundService(appContext, serviceIntent)
            onDispose { appContext.stopService(serviceIntent) }
        } else onDispose { }
    }
    LaunchedEffect(account, token) {
        val pendingId = prefs.getString("notification_conversation_id", "") ?: ""
        if (account.isNotBlank() && token.isNotBlank() && pendingId.isNotBlank()) {
            runCatching {
                val conversations = withContext(Dispatchers.IO) { GreenCallApi.getConversations(token) }
                for (index in 0 until conversations.length()) {
                    val item = conversations.optJSONObject(index) ?: continue
                    if (item.optString("id") != pendingId) continue
                    if (item.optString("kind") == "group") selected = "Group: " + item.optString("title", "Group")
                    else {
                        val members = item.optJSONArray("members") ?: JSONArray()
                        for (memberIndex in 0 until members.length()) {
                            val member = members.optJSONObject(memberIndex) ?: continue
                            if (member.optString("username") != account) {
                                selected = member.optString("username")
                                break
                            }
                        }
                    }
                }
                prefs.edit().remove("notification_conversation_id").apply()
            }
        }
    }
    LaunchedEffect(account) { if (account.isNotBlank()) loadUserData(account) }
    LaunchedEffect(account, token) {
        if (account.isNotBlank() && token.isNotBlank()) {
            runCatching {
                val remote = withContext(Dispatchers.IO) { GreenCallApi.getFriends(token) }
                val names = mutableListOf<String>()
                val edit = prefs.edit()
                for (i in 0 until remote.length()) {
                    val friend = remote.getJSONObject(i)
                    val username = friend.optString("username")
                    if (username.isNotBlank()) {
                        names.add(username)
                        edit.putString("display_${account}_${username}", friend.optString("displayName"))
                        edit.putString("friend_id_${account}_${username}", friend.optString("id"))
                    }
                }
                edit.putString("friends_${account}", JSONArray(names.distinct()).toString()).apply()
                friends = names.distinct()
                val remoteConversations = withContext(Dispatchers.IO) { GreenCallApi.getConversations(token) }
                val groupNames = mutableListOf<String>()
                for (i in 0 until remoteConversations.length()) {
                    val conversation = remoteConversations.optJSONObject(i) ?: continue
                    if (conversation.optString("kind") != "group") continue
                    val groupName = conversation.optString("title").ifBlank { "Group" }
                    val memberArray = conversation.optJSONArray("members") ?: JSONArray()
                    val peerNames = mutableListOf<String>()
                    val peerIds = JSONObject()
                    for (memberIndex in 0 until memberArray.length()) {
                        val member = memberArray.optJSONObject(memberIndex) ?: continue
                        val username = member.optString("username")
                        if (username.isNotBlank() && username != account) {
                            peerNames.add(username)
                            peerIds.put(username, member.optString("id"))
                        }
                    }
                    groupNames.add(groupName)
                    edit.putString("group_id_${account}_${groupName}", conversation.optString("id"))
                    edit.putString("group_nearby_peers_${account}_${groupName}", JSONArray(peerNames).toString())
                    edit.putString("group_nearby_peer_ids_${account}_${groupName}", peerIds.toString())
                }
                edit.putString("groups_${account}", JSONArray(groupNames.distinct()).toString()).apply()
                groups = groupNames.distinct()
            }
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Forest, secondary = Leaf, background = Bg)) {
        if (account.isBlank()) {
            AuthScreen(authPage, authError, { authError = "" }, { name, handle, password, register ->
                val cleanHandle = handle.trim().removePrefix("@").lowercase()
                scope.launch {
                    authError = ""
                    try {
                        val response = withContext(Dispatchers.IO) {
                            if (register) GreenCallApi.register(name.trim(), cleanHandle, password)
                            else GreenCallApi.login(cleanHandle, password)
                        }
                        val user = response.getJSONObject("user")
                        val newToken = response.getString("token")
                        val username = user.getString("username")
                        prefs.edit()
                            .putString("session", username)
                            .putString("token", newToken)
                            .putString("display_name", user.optString("displayName"))
                            .putString("user_id", user.optString("id"))
                            .apply()
                        token = newToken
                        account = username
                    } catch (error: Exception) {
                        authError = error.message ?: "Unable to connect to GreenCall."
                    }
                }
            }, { authPage = it })
        } else if (selected.isNotBlank()) {
            ConversationScreen(
                title = if (selected.startsWith("Group: ")) selected.removePrefix("Group: ") else (prefs.getString("display_${account}_${selected}", selected) ?: selected),
                storageKey = "messages_${account}_${selected}",
                prefs = prefs,
                token = token,
                currentUser = account,
                friends = friends,
                expectedPeerNames = if (selected.startsWith("Group: ")) {
                    val groupName = selected.removePrefix("Group: ")
                    val savedPeers = runCatching { JSONArray(prefs.getString("group_nearby_peers_${account}_${groupName}", "[]") ?: "[]") }.getOrElse { JSONArray() }
                    (0 until savedPeers.length()).map { savedPeers.optString(it) }.filter { it.isNotBlank() }.toSet()
                } else setOf(selected),
                friendId = if (selected.startsWith("Group: ")) "" else (prefs.getString("friend_id_${account}_${selected}", "") ?: ""),
                conversationIdOverride = if (selected.startsWith("Group: ")) (prefs.getString("group_id_${account}_${selected.removePrefix("Group: ")}", "") ?: "") else "",
                muted = muted,
                onBack = { selected = "" }
            )
        } else {
            Scaffold(
                containerColor = Bg,
                bottomBar = {
                    NavigationBar(containerColor = Color.White) {
                        NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Default.ChatBubble, null) }, label = { Text("Chats") })
                        NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Default.Groups, null) }, label = { Text("Groups") })
                        NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Default.People, null) }, label = { Text("Friends") })
                        NavigationBarItem(tab == 3, { tab = 3 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                    }
                }
            ) { pad ->
                when (tab) {
                    0 -> ChatsScreen(account, friends, prefs, { selected = it }, pad)
                    1 -> GroupsScreen(groups, friends, account, token, prefs, { selected = "Group: $it" }, ::saveGroups, pad)
                    2 -> FriendsScreen(friends, account, token, prefs, ::saveFriends, pad)
                    else -> SettingsScreen(account, muted, { muted = it; prefs.edit().putBoolean("muted", it).apply() }, prefs, {
                        prefs.edit().remove("session").remove("token").apply()
                        token = ""
                        account = ""; selected = ""; tab = 0
                    }, pad)
                }
            }
        }
    }
}

@Composable
private fun AuthScreen(register: Boolean, error: String, clearError: () -> Unit,
                       authenticate: (String, String, String, Boolean) -> Unit, changePage: (Boolean) -> Unit) {
    var name by remember(register) { mutableStateOf("") }
    var username by remember(register) { mutableStateOf("") }
    var password by remember(register) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 26.dp), verticalArrangement = Arrangement.Center) {
        BrandMark()
        Spacer(Modifier.height(25.dp))
        Text(if (register) "Create your account" else "Welcome back", fontSize = 29.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(if (register) "Choose a name and a unique username." else "Sign in to continue to GreenCall.", color = Muted)
        Spacer(Modifier.height(24.dp))
        if (register) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Your name") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
        }
        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, prefix = { Text("@") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        if (error.isNotBlank()) { Spacer(Modifier.height(9.dp)); Text(error, color = Color(0xFFB33A2B), fontSize = 12.sp) }
        Spacer(Modifier.height(18.dp))
        Button(onClick = { authenticate(name, username, password, register) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
            Text(if (register) "Create account" else "Sign in", fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { clearError(); changePage(!register) }, Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (register) "Already registered? Sign in" else "New here? Create an account", color = Forest)
        }
        Text("No email or Google account required.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun BrandMark() {
    Box(Modifier.size(66.dp).background(Forest, RoundedCornerShape(21.dp)), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Waves, null, tint = Leaf, modifier = Modifier.size(38.dp))
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 22.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMarkSmall()
            Spacer(Modifier.width(10.dp))
            Text("GreenCall", color = Forest, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            Text("DEVICE MODE", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text(title, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun BrandMarkSmall() {
    Box(Modifier.size(35.dp).background(Forest, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Waves, null, tint = Leaf)
    }
}

@Composable
private fun Avatar(label: String) {
    Box(Modifier.size(44.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) {
        Text(label.take(1).uppercase(), color = Forest, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 44.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Leaf, modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = Ink, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

private fun accountDirectory(prefs: android.content.SharedPreferences) =
    runCatching { JSONObject(prefs.getString("accounts", "{}") ?: "{}") }.getOrElse { JSONObject() }

@Composable
private fun FriendsScreen(friends: List<String>, currentUser: String, token: String, prefs: android.content.SharedPreferences,
                          save: (List<String>) -> Unit, pad: PaddingValues) {
    var show by remember { mutableStateOf(false) }
    var showRequests by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<JSONObject>()) }
    var requests by remember { mutableStateOf(emptyList<JSONObject>()) }
    var searching by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun refreshRequests() {
        scope.launch {
            runCatching {
                val remote = withContext(Dispatchers.IO) { GreenCallApi.getFriendRequests(token) }
                requests = (0 until remote.length()).map { remote.getJSONObject(it) }
            }
        }
    }
    LaunchedEffect(token) { refreshRequests() }
    Column(Modifier.fillMaxSize().padding(pad)) {
        Header("Friends", "Find people by their display name.")
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { refreshRequests(); showRequests = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PersonAddAlt1, null); Spacer(Modifier.width(7.dp))
                Text("Friend requests" + if (requests.isNotEmpty()) " · ${requests.size}" else "")
            }
            Spacer(Modifier.width(10.dp))
            Button(onClick = { show = true }) { Icon(Icons.Default.Add, null); Text("Add") }
        }
        if (friends.isEmpty()) EmptyState(Icons.Default.PersonSearch, "No friends added", "Search for someone and send them a friend request.")
        else LazyColumn(Modifier.weight(1f)) {
            items(friends) { handle ->
                val display = prefs.getString("display_${currentUser}_${handle}", handle) ?: handle
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(display); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(display, color = Ink, fontWeight = FontWeight.SemiBold); Text("@$handle", color = Muted, fontSize = 11.sp) }
                    IconButton(onClick = {
                        scope.launch {
                            val id = prefs.getString("friend_id_${currentUser}_${handle}", "") ?: ""
                            if (id.isNotBlank()) runCatching { withContext(Dispatchers.IO) { GreenCallApi.unfriend(token, id) } }
                            save(friends - handle)
                        }
                    }) { Icon(Icons.Default.PersonRemove, "Unfriend", tint = Muted) }
                }
                HorizontalDivider(color = Color(0xFFE8EEE9))
            }
        }
    }
    if (show) AlertDialog(onDismissRequest = { show = false; search = ""; result = ""; results = emptyList() },
        title = { Text("Find a friend") },
        text = {
            Column {
                Text("Search by the name they registered with.", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(search, { search = it; result = ""; results = emptyList() }, Modifier.fillMaxWidth(), label = { Text("Person's name") }, singleLine = true)
                Button(onClick = {
                    if (search.trim().length >= 2 && token.isNotBlank()) scope.launch {
                        searching = true
                        try {
                            val found = withContext(Dispatchers.IO) { GreenCallApi.searchUsers(token, search.trim()) }
                            results = (0 until found.length()).map { found.getJSONObject(it) }
                        } catch (error: Exception) { result = error.message ?: "Search failed." }
                        finally { searching = false }
                    }
                }, enabled = !searching && search.trim().length >= 2) {
                    if (searching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Search")
                }
                results.filter { it.optString("username") !in friends }.forEach { person ->
                    val id = person.optString("id")
                    val handle = person.optString("username")
                    val display = person.optString("displayName")
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        scope.launch {
                            busy = true
                            try {
                                withContext(Dispatchers.IO) { GreenCallApi.addFriend(token, id) }
                                result = "Friend request sent to $display."
                                results = emptyList()
                                refreshRequests()
                            } catch (error: Exception) { result = error.message ?: "Could not send request." }
                            finally { busy = false }
                        }
                    }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(display); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) { Text(display, color = Ink, fontWeight = FontWeight.SemiBold); Text("@$handle", color = Muted, fontSize = 11.sp) }
                        Text("Send request", color = Forest, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
                if (result.isNotBlank()) Text(result, color = Forest, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { show = false; search = ""; result = ""; results = emptyList() }) { Text("Done") } })
    if (showRequests) AlertDialog(
        onDismissRequest = { showRequests = false },
        title = { Text("Friend requests") },
        text = {
            if (requests.isEmpty()) Text("No pending friend requests.", color = Muted)
            else Column {
                requests.forEach { request ->
                    val user = request.optJSONObject("user") ?: JSONObject()
                    val requestId = request.optString("id")
                    val display = user.optString("displayName", "GreenCall user")
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(display); Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) { Text(display, color = Ink, fontWeight = FontWeight.SemiBold); Text("@${user.optString("username")}", color = Muted, fontSize = 10.sp) }
                        TextButton(enabled = !busy, onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    val accepted = withContext(Dispatchers.IO) { GreenCallApi.acceptFriendRequest(token, requestId) }
                                    val friend = accepted.optJSONObject("friend") ?: user
                                    val handle = friend.optString("username")
                                    prefs.edit().putString("display_${currentUser}_${handle}", friend.optString("displayName"))
                                        .putString("friend_id_${currentUser}_${handle}", friend.optString("id")).apply()
                                    save(friends + handle)
                                    requests = requests.filterNot { it.optString("id") == requestId }
                                } catch (_: Exception) { }
                                finally { busy = false }
                            }
                        }) { Text("Accept", color = Forest) }
                        TextButton(enabled = !busy, onClick = {
                            scope.launch {
                                busy = true
                                runCatching { withContext(Dispatchers.IO) { GreenCallApi.declineFriendRequest(token, requestId) } }
                                requests = requests.filterNot { it.optString("id") == requestId }
                                busy = false
                            }
                        }) { Text("Decline", color = Muted) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showRequests = false }) { Text("Done") } }
    )
}

@Composable
private fun ChatsScreen(user: String, friends: List<String>, prefs: android.content.SharedPreferences,
                        open: (String) -> Unit, pad: PaddingValues) {
    val directory = accountDirectory(prefs)
    Column(Modifier.fillMaxSize().padding(pad)) {
        Header("Messages", "Signed in as @$user")
        if (friends.isEmpty()) EmptyState(Icons.Default.ChatBubbleOutline, "No conversations yet", "Add a friend to start a conversation.")
        else LazyColumn {
            items(friends) { handle ->
                val display = prefs.getString("display_${user}_${handle}", handle) ?: handle
                val messages = runCatching { JSONArray(prefs.getString("messages_${user}_${handle}", "[]") ?: "[]") }.getOrElse { JSONArray() }
                val last = if (messages.length() > 0) messages.optJSONObject(messages.length()-1)?.optString("text") ?: "No messages yet" else "No messages yet"
                Row(Modifier.fillMaxWidth().clickable { open(handle) }.padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(display); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(display, color = Ink, fontWeight = FontWeight.SemiBold); Text(last, color = Muted, fontSize = 12.sp, maxLines = 1) }
                    Icon(Icons.Default.ChevronRight, null, tint = Muted)
                }
                HorizontalDivider(color = Color(0xFFE8EEE9))
            }
        }
    }
}

@Composable
private fun ConversationScreen(
    title: String,
    storageKey: String,
    prefs: android.content.SharedPreferences,
    token: String,
    currentUser: String,
    friends: List<String>,
    expectedPeerNames: Set<String>,
    friendId: String,
    conversationIdOverride: String,
    muted: Boolean,
    onBack: () -> Unit
) {
    var raw by remember(storageKey) { mutableStateOf(prefs.getString(storageKey, "[]") ?: "[]") }
    var message by remember { mutableStateOf("") }
    var conversationId by remember { mutableStateOf("") }
    var connectionError by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var voiceStatus by remember { mutableStateOf("Connecting voice…") }
    var receivingVoice by remember { mutableStateOf(false) }
    var voicemailCount by remember { mutableIntStateOf(0) }
    var voicemailList by remember { mutableStateOf(emptyList<JSONObject>()) }
    var showVoicemails by remember { mutableStateOf(false) }
    var nearbyStatus by remember { mutableStateOf("Nearby mode is off") }
    var nearbyPeers by remember(storageKey) { mutableStateOf(expectedPeerNames) }
    var showAddMember by remember { mutableStateOf(false) }
    var addingMember by remember { mutableStateOf(false) }
    var memberError by remember { mutableStateOf("") }
    var nearbyIncoming by remember { mutableStateOf<JSONObject?>(null) }
    var nearbyEnabled by remember { mutableStateOf(false) }
    var nearbyTalking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val mutedState by rememberUpdatedState(muted)
    val nearbyPermissions = remember {
        buildList {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray()
    }
    val groupPeerIds = remember(storageKey, nearbyPeers) {
        runCatching { JSONObject(prefs.getString("group_nearby_peer_ids_${currentUser}_${title}", "{}") ?: "{}") }
            .getOrElse { JSONObject() }
    }
    val nearbyPeerIds = nearbyPeers.associateWith { handle ->
        groupPeerIds.optString(handle).ifBlank {
            (prefs.getString("friend_id_${currentUser}_${handle}", "") ?: "")
                .ifBlank { if (nearbyPeers.size == 1) friendId else "" }
        }
    }.filterValues { it.isNotBlank() }
    val nearbyTransport = remember(storageKey, currentUser, nearbyPeers) {
        NearbyTransport(context, currentUser, storageKey, nearbyPeers,
            expectedPeerIds = nearbyPeerIds,
            isMuted = { mutedState },
            onStatus = { nearbyStatus = it },
            onText = { nearbyIncoming = it },
            onIncomingVoice = { receivingVoice = it },
            onVoiceMail = { audio, duration ->
                val item = JSONObject()
                    .put("id", "nearby-voice-${System.currentTimeMillis()}")
                    .put("sender_name", title)
                    .put("duration_ms", duration)
                    .put("audioBase64", Base64.encodeToString(audio, Base64.NO_WRAP))
                    .put("local", true)
                    .put("created_at", "Received nearby")
                val saved = runCatching { JSONArray(prefs.getString("local_voicemail_${currentUser}", "[]") ?: "[]") }.getOrElse { JSONArray() }
                val updated = JSONArray().put(item)
                for (index in 0 until saved.length()) saved.optJSONObject(index)?.let { updated.put(it) }
                prefs.edit().putString("local_voicemail_${currentUser}", updated.toString()).apply()
                voicemailList = listOf(item) + voicemailList.filterNot { it.optString("id") == item.optString("id") }
                voicemailCount = voicemailList.size
                voiceStatus = "Nearby voice saved as voicemail"
            })
    }
    LaunchedEffect(currentUser) {
        val saved = runCatching { JSONArray(prefs.getString("local_voicemail_${currentUser}", "[]") ?: "[]") }.getOrElse { JSONArray() }
        if (saved.length() > 0) {
            val local = (0 until saved.length()).mapNotNull { saved.optJSONObject(it) }
            voicemailList = local + voicemailList
            voicemailCount = voicemailList.size
        }
    }
    val nearbyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allowed = nearbyPermissions.all { permission ->
            grants[permission] == true || ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (allowed) {
            nearbyEnabled = true
            nearbyTransport.start()
        } else nearbyStatus = "Nearby permissions are needed for offline messaging"
    }
    DisposableEffect(nearbyTransport) {
        onDispose { nearbyTransport.stop() }
    }
    LaunchedEffect(nearbyTransport, nearbyEnabled) {
        if (nearbyEnabled) nearbyTransport.start()
    }
    var messages by remember(storageKey) { mutableStateOf(runCatching { JSONArray(raw) }.getOrElse { JSONArray() }) }
    LaunchedEffect(nearbyStatus) {
        if (nearbyStatus.startsWith("Nearby connected")) {
            val queued = (0 until messages.length()).map { messages.getJSONObject(it) }.filter { it.optBoolean("pending") }
            if (queued.isNotEmpty()) {
                val updated = JSONArray()
                for (i in 0 until messages.length()) {
                    val item = messages.getJSONObject(i)
                    if (item.optBoolean("pending") && nearbyTransport.sendText(item.optString("text"))) {
                        updated.put(JSONObject(item.toString()).put("pending", false).put("nearby", true))
                    } else updated.put(item)
                }
                messages = updated
                raw = updated.toString()
                prefs.edit().putString(storageKey, raw).apply()
            }
        }
    }
    LaunchedEffect(nearbyIncoming) {
        val packet = nearbyIncoming ?: return@LaunchedEffect
        val text = packet.optString("text")
        if (text.isNotBlank()) {
            val packetId = packet.optString("id").ifBlank { "nearby-${packet.optLong("sentAt", System.currentTimeMillis())}" }
            val alreadySaved = (0 until messages.length()).any { messages.optJSONObject(it)?.optString("id") == packetId }
            if (!alreadySaved) {
                val updated = JSONArray(messages.toString()).put(JSONObject()
                    .put("id", packetId)
                    .put("text", text).put("mine", false).put("nearby", true)
                    .put("time", packet.optLong("sentAt", System.currentTimeMillis()).toString()))
                messages = updated
                raw = updated.toString()
                prefs.edit().putString(storageKey, raw).apply()
            }
        }
        nearbyIncoming = null
    }
    fun retryPendingMessages() {
        scope.launch {
            if (conversationId.startsWith("nearby-") && token.isNotBlank() && friendId.isNotBlank()) {
                runCatching {
                    val serverConversation = withContext(Dispatchers.IO) { GreenCallApi.createDirect(token, friendId) }
                    conversationId = serverConversation
                    prefs.edit().putString("conversation_${storageKey}", serverConversation).apply()
                }
            }
            val queued = (0 until messages.length()).map { messages.getJSONObject(it) }.filter { it.optBoolean("pending") }
            for (pending in queued) {
                try {
                    if (conversationId.startsWith("nearby-")) {
                        if (nearbyTransport.sendText(pending.optString("text"))) {
                            val updated = JSONArray()
                            for (i in 0 until messages.length()) {
                                val item = messages.getJSONObject(i)
                                if (item.optString("id") == pending.optString("id")) {
                                    updated.put(JSONObject(item.toString()).put("pending", false).put("nearby", true))
                                } else updated.put(item)
                            }
                            messages = updated
                            raw = updated.toString()
                            prefs.edit().putString(storageKey, raw).apply()
                            continue
                        }
                        break
                    }
                    val sent = withContext(Dispatchers.IO) { GreenCallApi.sendMessage(token, conversationId, pending.optString("text")) }
                    val updated = JSONArray()
                    for (i in 0 until messages.length()) {
                        val item = messages.getJSONObject(i)
                        if (item.optString("id") != pending.optString("id")) updated.put(item)
                    }
                    updated.put(JSONObject().put("id", sent.optString("id")).put("text", sent.optString("body"))
                        .put("mine", true).put("time", sent.optString("created_at")))
                    messages = updated
                    raw = updated.toString()
                    prefs.edit().putString(storageKey, raw).apply()
                } catch (_: Exception) {
                    break
                }
            }
            if (!conversationId.startsWith("nearby-")) {
                nearbyTransport.syncPendingVoiceToServer(token, conversationId)
                val nearbyOutgoing = (0 until messages.length()).map { messages.getJSONObject(it) }
                    .filter { it.optBoolean("nearby") && it.optBoolean("mine") && !it.optBoolean("synced") && !it.optBoolean("pending") }
                for (nearby in nearbyOutgoing) {
                    try {
                        val sent = withContext(Dispatchers.IO) { GreenCallApi.sendMessage(token, conversationId, nearby.optString("text")) }
                        val updated = JSONArray()
                        for (i in 0 until messages.length()) {
                            val item = messages.getJSONObject(i)
                            if (item.optString("id") == nearby.optString("id")) {
                                updated.put(JSONObject(item.toString())
                                    .put("id", sent.optString("id"))
                                    .put("text", sent.optString("body", item.optString("text")))
                                    .put("time", sent.optString("created_at"))
                                    .put("nearby", false)
                                    .put("synced", true))
                            } else updated.put(item)
                        }
                        messages = updated
                        raw = updated.toString()
                        prefs.edit().putString(storageKey, raw).apply()
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        }
    }
    DisposableEffect(token, conversationId) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                retryPendingMessages()
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
        val active = manager.activeNetwork
        val capabilities = active?.let { manager.getNetworkCapabilities(it) }
        if (capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            retryPendingMessages()
        }
        onDispose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }
    val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        voiceStatus = if (granted) "Microphone ready — press and hold to talk" else "Microphone permission is required for walkie-talkie"
    }

    LaunchedEffect(token, friendId, conversationIdOverride) {
        if (token.isNotBlank() && (friendId.isNotBlank() || conversationIdOverride.isNotBlank())) {
            try {
                conversationId = if (conversationIdOverride.isNotBlank()) conversationIdOverride
                    else (prefs.getString("conversation_${storageKey}", "")?.takeIf { it.isNotBlank() }
                        ?: withContext(Dispatchers.IO) { GreenCallApi.createDirect(token, friendId) }.also {
                            prefs.edit().putString("conversation_${storageKey}", it).apply()
                        })
                if (conversationIdOverride.isNotBlank() && friendId.isBlank()) {
                    runCatching {
                        val all = withContext(Dispatchers.IO) { GreenCallApi.getConversations(token) }
                        for (index in 0 until all.length()) {
                            val group = all.optJSONObject(index) ?: continue
                            if (group.optString("id") != conversationId) continue
                            val members = group.optJSONArray("members") ?: JSONArray()
                            val peers = mutableSetOf<String>()
                            val peerIds = JSONObject()
                            for (memberIndex in 0 until members.length()) {
                                val member = members.optJSONObject(memberIndex) ?: continue
                                val username = member.optString("username")
                                if (username.isNotBlank() && username != currentUser) {
                                    peers.add(username)
                                    peerIds.put(username, member.optString("id"))
                                }
                            }
                            nearbyPeers = peers
                            val saved = JSONArray()
                            peers.forEach { saved.put(it) }
                            prefs.edit()
                                .putString("group_nearby_peers_${currentUser}_${title}", saved.toString())
                                .putString("group_nearby_peer_ids_${currentUser}_${title}", peerIds.toString())
                                .apply()
                            break
                        }
                    }
                }
                val remote = withContext(Dispatchers.IO) { GreenCallApi.getMessages(token, conversationId) }
                val normalized = JSONArray()
                val myId = prefs.getString("user_id", "") ?: ""
                for (i in 0 until remote.length()) {
                    val item = remote.getJSONObject(i)
                    normalized.put(JSONObject()
                        .put("id", item.optString("id"))
                        .put("text", item.optString("body"))
                        .put("mine", item.optString("sender_id") == myId)
                        .put("time", item.optString("created_at")))
                }
                val cached = runCatching { JSONArray(prefs.getString(storageKey, "[]") ?: "[]") }.getOrElse { JSONArray() }
                for (i in 0 until cached.length()) {
                    val pending = cached.optJSONObject(i)
                    if (pending != null && (pending.optBoolean("pending") ||
                            (pending.optBoolean("nearby") && pending.optBoolean("mine") && !pending.optBoolean("synced")))) {
                        val duplicate = (0 until normalized.length()).any { normalized.optJSONObject(it)?.optString("id") == pending.optString("id") }
                        if (!duplicate) normalized.put(pending)
                    }
                }
                messages = normalized
                raw = normalized.toString()
                prefs.edit().putString(storageKey, raw).apply()
                connectionError = ""
            } catch (error: Exception) {
                if (friendId.isNotBlank() && conversationId.isBlank()) {
                    conversationId = "nearby-${currentUser}-${storageKey.substringAfterLast("_")}"
                    connectionError = "Offline mode: connect to your friend nearby to exchange messages."
                } else {
                    connectionError = error.message ?: "Unable to load this conversation."
                }
            }
        } else {
            val cached = prefs.getString("conversation_${storageKey}", "") ?: ""
            conversationId = cached.ifBlank { "nearby-${currentUser}-${storageKey.substringAfterLast("_")}" }
            connectionError = if (friendId.isBlank() && conversationIdOverride.isBlank())
                "This friend is not linked to a server account yet." else "Offline mode: connect to your friend nearby to exchange messages."
        }
        loading = false
    }

    val voiceController = remember(conversationId, token) {
        if (conversationId.isBlank() || conversationId.startsWith("nearby-")) null else WalkieTalkieController(
            context = context,
            token = token,
            conversationId = conversationId,
            muted = muted,
            onStatus = { status -> voiceStatus = status; if (status == "Voice connected") retryPendingMessages() },
            onIncoming = { receivingVoice = it },
            onVoicemail = {
                scope.launch {
                    runCatching {
                        val remote = withContext(Dispatchers.IO) { GreenCallApi.getVoicemails(token) }
                        val local = runCatching { JSONArray(prefs.getString("local_voicemail_${currentUser}", "[]") ?: "[]") }.getOrElse { JSONArray() }
                        voicemailList = (0 until local.length()).mapNotNull { local.optJSONObject(it) } +
                            (0 until remote.length()).map { remote.getJSONObject(it) }
                        voicemailCount = voicemailList.size
                    }
                }
            },
            onMessage = { item ->
                val myId = prefs.getString("user_id", "") ?: ""
                if (item.optString("sender_id") != myId) {
                    val normalized = JSONObject()
                        .put("id", item.optString("id"))
                        .put("text", item.optString("body"))
                        .put("mine", false)
                        .put("time", item.optString("created_at"))
                    val nearbyDuplicate = (0 until messages.length()).firstOrNull { index ->
                        val existing = messages.optJSONObject(index)
                        existing?.optBoolean("nearby") == true && !existing.optBoolean("mine") &&
                            existing.optString("text") == item.optString("body")
                    }
                    val updated = JSONArray()
                    for (index in 0 until messages.length()) {
                        if (index == nearbyDuplicate) updated.put(normalized)
                        else updated.put(messages.getJSONObject(index))
                    }
                    if (nearbyDuplicate == null) updated.put(normalized)
                    messages = updated
                    raw = updated.toString()
                    prefs.edit().putString(storageKey, raw).apply()
                }
            }
        )
    }
    LaunchedEffect(voiceController, muted) { voiceController?.setMuted(muted) }
    DisposableEffect(voiceController) {
        onDispose { voiceController?.close() }
    }
    LaunchedEffect(token, conversationId) {
        if (token.isNotBlank() && conversationId.isNotBlank()) {
            runCatching {
                val list = withContext(Dispatchers.IO) { GreenCallApi.getVoicemails(token) }
                val local = runCatching { JSONArray(prefs.getString("local_voicemail_${currentUser}", "[]") ?: "[]") }.getOrElse { JSONArray() }
                voicemailList = (0 until local.length()).mapNotNull { local.optJSONObject(it) } +
                    (0 until list.length()).map { list.getJSONObject(it) }
                voicemailCount = voicemailList.size
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
            Avatar(title); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontWeight = FontWeight.Bold)
                Text(if (nearbyEnabled) "Nearby device mode active" else if (conversationId.isNotBlank()) "GreenCall voice channel" else "Offline conversation", color = Muted, fontSize = 11.sp)
            }
            if (conversationIdOverride.isNotBlank() && friendId.isBlank()) {
                TextButton(onClick = { memberError = ""; showAddMember = true }) {
                    Icon(Icons.Default.PersonAdd, null, tint = Forest)
                    Text("Add", color = Forest)
                }
            }
            TextButton(onClick = {
                scope.launch {
                    try {
                        val list = withContext(Dispatchers.IO) { GreenCallApi.getVoicemails(token) }
                        val local = runCatching { JSONArray(prefs.getString("local_voicemail_${currentUser}", "[]") ?: "[]") }.getOrElse { JSONArray() }
                        voicemailList = (0 until local.length()).mapNotNull { local.optJSONObject(it) } +
                            (0 until list.length()).map { list.getJSONObject(it) }
                        voicemailCount = voicemailList.size
                        showVoicemails = true
                    } catch (error: Exception) { connectionError = error.message ?: "Could not load voicemails." }
                }
            }) {
                Icon(Icons.Default.Voicemail, null, tint = Forest)
                if (voicemailCount > 0) Text("$voicemailCount", color = Forest, fontWeight = FontWeight.Bold)
            }
        }
        if (receivingVoice) {
            Row(Modifier.fillMaxWidth().background(Mint).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VolumeUp, null, tint = Forest)
                Spacer(Modifier.width(8.dp))
                Text("Receiving live voice…", color = Forest, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
        if (loading && messages.length() == 0) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Leaf) }
        } else if (messages.length() == 0) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Default.ChatBubbleOutline, "Start the conversation", "Send a message or hold the walkie-talkie button.")
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items((0 until messages.length()).map { messages.getJSONObject(it) }) { item ->
                    val mine = item.optBoolean("mine")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        Column(Modifier.widthIn(max = 290.dp).background(if (mine) Mint else Color.White, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(item.optString("text"), color = Ink, fontSize = 14.sp)
                            Text(if (item.optBoolean("pending")) "Pending · will retry when online" else if (mine) "You" else title, color = Muted, fontSize = 9.sp)
                        }
                    }
                }
            }
        }
        if (connectionError.isNotBlank()) Text(connectionError, Modifier.padding(horizontal = 12.dp), color = Color(0xFFB33A2B), fontSize = 10.sp)
        Row(Modifier.fillMaxWidth().background(Mint).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Nearby offline mode", color = Forest, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text(nearbyStatus, color = Muted, fontSize = 10.sp)
            }
            if (!nearbyEnabled) {
                TextButton(onClick = {
                    val missing = nearbyPermissions.filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) { nearbyEnabled = true; nearbyTransport.start() }
                    else nearbyPermissionLauncher.launch(missing.toTypedArray())
                }) { Text("Connect", color = Forest) }
            } else {
                TextButton(onClick = { nearbyTransport.stop(); nearbyEnabled = false; nearbyStatus = "Nearby mode is off" }) { Text("Stop", color = Forest) }
            }
        }
        Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(voiceStatus, color = if (receivingVoice) Forest else Muted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            if (muted) {
                Text("Incoming live voice is muted and will be saved as voicemail. You can still talk.", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(5.dp))
            }
            if (!micGranted) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }, enabled = conversationId.isNotBlank()) {
                    Icon(Icons.Default.Mic, null); Spacer(Modifier.width(6.dp)); Text("Enable microphone")
                }
            } else {
                Box(
                    Modifier.fillMaxWidth().height(58.dp)
                        .background(if (receivingVoice) Color(0xFFDBEDE3) else Forest, RoundedCornerShape(16.dp))
                        .pointerInput(voiceController, nearbyTransport, nearbyEnabled, muted, micGranted, conversationId) {
                            detectTapGestures(onPress = {
                                var useNearby = false
                                var started = false
                                val onlineVoiceReady = voiceController?.isConnected() == true
                                if (nearbyEnabled && (nearbyTransport.hasPeers() || !onlineVoiceReady)) {
                                    useNearby = true
                                    started = nearbyTransport.startTalking()
                                } else {
                                    started = voiceController?.startTalking() == true
                                    if (!started && nearbyEnabled) {
                                        useNearby = true
                                        started = nearbyTransport.startTalking()
                                    }
                                }
                                if (started) {
                                    nearbyTalking = useNearby
                                    tryAwaitRelease()
                                    if (useNearby) nearbyTransport.stopTalking()
                                    else voiceController?.stopTalking()
                                    nearbyTalking = false
                                } else {
                                    voiceStatus = "Microphone could not start. Check microphone permission and try again."
                                }
                            })
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Mic, null, tint = Color.White)
                        Spacer(Modifier.width(9.dp))
                        Text("PRESS AND HOLD TO TALK", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 12.sp)
                    }
                }
            }
        }
        val pendingItems = (0 until messages.length()).count { messages.optJSONObject(it)?.optBoolean("pending") == true }
        if (pendingItems > 0) {
            TextButton(onClick = { retryPendingMessages() }, Modifier.align(Alignment.CenterHorizontally)) {
                Text("Retry $pendingItems queued message(s)")
            }
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(message, { message = it }, Modifier.weight(1f), placeholder = { Text("Write a message…") }, maxLines = 4)
            IconButton(onClick = {
                val body = message.trim()
                if (body.isNotBlank() && conversationId.isNotBlank()) {
                    message = ""
                    scope.launch {
                        try {
                            val sent = withContext(Dispatchers.IO) { GreenCallApi.sendMessage(token, conversationId, body) }
                            val updated = JSONArray(messages.toString()).put(JSONObject()
                                .put("id", sent.optString("id"))
                                .put("text", sent.optString("body", body))
                                .put("mine", true)
                                .put("time", sent.optString("created_at")))
                            messages = updated
                            raw = updated.toString()
                            prefs.edit().putString(storageKey, raw).apply()
                            connectionError = ""
                        } catch (error: Exception) {
                            val nearbySent = nearbyTransport.sendText(body)
                            val queued = JSONArray(messages.toString()).put(JSONObject()
                                .put("id", "pending-${System.currentTimeMillis()}")
                                .put("text", body)
                                .put("mine", true)
                                .put("pending", !nearbySent)
                                .put("nearby", nearbySent)
                                .put("time", System.currentTimeMillis().toString()))
                            messages = queued
                            raw = queued.toString()
                            prefs.edit().putString(storageKey, raw).apply()
                            connectionError = if (nearbySent) "Sent directly over Nearby — no internet used."
                                else "Saved on this device. It will send when a nearby friend connects or internet returns."
                        }
                    }
                } else if (body.isNotBlank()) {
                    connectionError = "Connect to GreenCall before sending."
                }
            }, enabled = conversationId.isNotBlank()) { Icon(Icons.Default.Send, null, tint = Forest) }
        }
        Text("Hold to talk: nearby radio when connected, otherwise internet voice. Muted nearby voice is saved on this device.", Modifier.padding(horizontal = 12.dp, vertical = 5.dp), color = Muted, fontSize = 10.sp)
    }

    if (showAddMember) {
        val candidates = friends.filter { it != currentUser && it !in nearbyPeers }
        AlertDialog(
            onDismissRequest = { if (!addingMember) showAddMember = false },
            title = { Text("Add someone to $title") },
            text = {
                Column {
                    Text("Choose one of your friends to add to this group.", color = Muted, fontSize = 12.sp)
                    if (candidates.isEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("All your friends are already in this group.", color = Muted, fontSize = 12.sp)
                    } else {
                        LazyColumn(Modifier.heightIn(max = 280.dp)) {
                            items(candidates) { handle ->
                                val display = prefs.getString("display_${currentUser}_${handle}", handle) ?: handle
                                TextButton(
                                    onClick = {
                                        val memberId = prefs.getString("friend_id_${currentUser}_${handle}", "") ?: ""
                                        if (memberId.isBlank()) {
                                            memberError = "This friend is not linked to a server account."
                                        } else {
                                            scope.launch {
                                                addingMember = true
                                                memberError = ""
                                                try {
                                                    withContext(Dispatchers.IO) {
                                                        GreenCallApi.addGroupMember(token, conversationId, memberId)
                                                    }
                                                    val updatedPeers = nearbyPeers + handle
                                                    nearbyPeers = updatedPeers
                                                    val peerArray = JSONArray()
                                                    updatedPeers.forEach { peerArray.put(it) }
                                                    val peerIds = JSONObject(groupPeerIds.toString()).put(handle, memberId)
                                                    prefs.edit()
                                                        .putString("group_nearby_peers_${currentUser}_${title}", peerArray.toString())
                                                        .putString("group_nearby_peer_ids_${currentUser}_${title}", peerIds.toString())
                                                        .apply()
                                                    showAddMember = false
                                                } catch (error: Exception) {
                                                    memberError = error.message ?: "Could not add this friend."
                                                } finally {
                                                    addingMember = false
                                                }
                                            }
                                        }
                                    },
                                    enabled = !addingMember,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(display, color = Ink, fontWeight = FontWeight.SemiBold)
                                        Text("@$handle", color = Muted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                    if (memberError.isNotBlank()) Text(memberError, color = Color(0xFFB33A2B), fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddMember = false }, enabled = !addingMember) { Text("Done") }
            }
        )
    }

    if (showVoicemails) {
        AlertDialog(
            onDismissRequest = { showVoicemails = false },
            title = { Text("Voice messages") },
            text = {
                if (voicemailList.isEmpty()) Text("No new voicemails.")
                else Column {
                    voicemailList.forEach { item ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.optString("sender_name", "GreenCall friend"), color = Ink, fontWeight = FontWeight.SemiBold)
                                Text("${(item.optInt("duration_ms") / 1000)} sec · ${item.optString("created_at")}", color = Muted, fontSize = 10.sp)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    try {
                                        if (item.optBoolean("local")) {
                                            LocalVoicePlayer.play(item.optString("audioBase64"))
                                        } else {
                                            val audio = withContext(Dispatchers.IO) { GreenCallApi.getVoicemailAudio(token, item.optString("id")) }
                                            voiceController?.playVoicemail(audio)
                                            val refreshed = withContext(Dispatchers.IO) { GreenCallApi.getVoicemails(token) }
                                            val local = voicemailList.filter { it.optBoolean("local") }
                                            voicemailList = local + (0 until refreshed.length()).map { refreshed.getJSONObject(it) }
                                            voicemailCount = voicemailList.size
                                        }
                                    } catch (error: Exception) { connectionError = error.message ?: "Could not play voicemail." }
                                }
                            }) { Icon(Icons.Default.PlayArrow, null, tint = Forest) }
                        }
                        HorizontalDivider(color = Color(0xFFE8EEE9))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showVoicemails = false }) { Text("Done") } }
        )
    }
}

@Composable
private fun GroupsScreen(groups: List<String>, friends: List<String>, currentUser: String, token: String, prefs: android.content.SharedPreferences, open: (String) -> Unit, save: (List<String>) -> Unit, pad: PaddingValues) {
    var show by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var selectedMembers by remember { mutableStateOf(setOf<String>()) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(pad)) {
        Header("Groups", "Create a space for your friends.")
        if (groups.isEmpty()) EmptyState(Icons.Default.Groups, "No groups yet", "Create a group and choose its members.")
        else LazyColumn(Modifier.weight(1f)) { items(groups) { group ->
            Row(Modifier.fillMaxWidth().clickable { open(group) }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(group); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(group, color = Ink, fontWeight = FontWeight.SemiBold); Text("Group conversation", color = Muted, fontSize = 10.sp) }
                Icon(Icons.Default.ChevronRight, null, tint = Muted)
            }
        } }
        Button(onClick = { show = true }, Modifier.fillMaxWidth().padding(18.dp)) { Icon(Icons.Default.Add, null); Text("Create group") }
    }
    if (show) AlertDialog(onDismissRequest = { if (!creating) show = false }, title = { Text("Create a group") },
        text = { Column {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Group name") }, singleLine = true)
            Spacer(Modifier.height(8.dp)); Text("Add friends", fontWeight = FontWeight.SemiBold)
            if (friends.isEmpty()) Text("Add friends first to invite them.", color = Muted, fontSize = 12.sp)
            friends.forEach { friend -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(friend in selectedMembers, { checked -> selectedMembers = if (checked) selectedMembers + friend else selectedMembers - friend })
                Text("@$friend", color = Ink)
            } }
            if (error.isNotBlank()) Text(error, color = Color(0xFFB33A2B), fontSize = 11.sp)
        } },
        confirmButton = { TextButton(enabled = !creating, onClick = {
            val groupName = title.trim()
            val ids = selectedMembers.mapNotNull { prefs.getString("friend_id_${currentUser}_${it}", null) }
            if (groupName.length < 2) {
                error = "Enter a group name."
            } else if (ids.isEmpty() || ids.size != selectedMembers.size) {
                error = "Choose at least one friend who is synced with GreenCall."
            } else {
                creating = true
                scope.launch {
                    try {
                        val group = withContext(Dispatchers.IO) { GreenCallApi.createGroup(token, groupName, ids) }
                        prefs.edit().putString("group_id_${currentUser}_${groupName}", group.optString("id")).apply()
                        save(groups + groupName)
                        title = ""; selectedMembers = emptySet(); error = ""; show = false
                    } catch (failure: Exception) {
                        error = failure.message ?: "Could not create the group."
                    } finally { creating = false }
                }
            }
        }) { Text(if (creating) "Creating…" else "Create") } },
        dismissButton = { TextButton(enabled = !creating, onClick = { show = false }) { Text("Cancel") } })
}

@Composable
private fun SettingsScreen(user: String, muted: Boolean, setMuted: (Boolean) -> Unit, prefs: android.content.SharedPreferences, signOut: () -> Unit, pad: PaddingValues) {
    var gain by remember { mutableFloatStateOf(prefs.getFloat("playback_gain", 1.0f).coerceIn(0.5f, 3.0f)) }
    Column(Modifier.fillMaxSize().padding(pad)) {
        Header("Settings", "Your GreenCall preferences")
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp)) { Text("Username", color = Muted, fontSize = 12.sp); Text("@$user", color = Ink, fontWeight = FontWeight.Bold) }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Mute walkie-talkie", color = Ink, fontWeight = FontWeight.SemiBold); Text("Don't play incoming live voice when muted.", color = Muted, fontSize = 11.sp) }
                    Switch(muted, setMuted)
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Voice playback volume", color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(String.format(java.util.Locale.US, "%.1fx", gain), color = Forest, fontWeight = FontWeight.Bold)
                    }
                    Slider(value = gain, onValueChange = {
                        gain = it
                        prefs.edit().putFloat("playback_gain", it).apply()
                    }, valueRange = 0.5f..3.0f)
                    Text("Boosts incoming voice inside GreenCall, independently of your phone's media-volume buttons. High boost may distort loud audio.", color = Muted, fontSize = 11.sp)
                }
            }
            Text("Nearby mode sends text and push-to-talk audio directly between connected friends or group members without internet. Keep the conversation open and stay within radio range. Muted nearby voice is saved on this device. Server sync and remote delivery still need internet.", color = Muted, fontSize = 12.sp)
            OutlinedButton(onClick = signOut, Modifier.fillMaxWidth()) { Icon(Icons.Default.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out") }
        }
    }
}
