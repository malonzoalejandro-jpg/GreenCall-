package com.greenlink.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

private val Forest = Color(0xFF123D2B)
private val Leaf = Color(0xFF35C982)
private val Mint = Color(0xFFE8F6EE)
private val Ink = Color(0xFF14251C)
private val Muted = Color(0xFF718278)
private val Bg = Color(0xFFF5F8F5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GreenCallApp(getSharedPreferences("greencall", Context.MODE_PRIVATE)) }
    }
}

private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

@Composable
private fun GreenCallApp(prefs: android.content.SharedPreferences) {
    var username by remember { mutableStateOf(prefs.getString("session", "") ?: "") }
    var tab by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf("login") }
    var notice by remember { mutableStateOf("") }
    var friends by remember { mutableStateOf(prefs.getString("friends", "[]") ?: "[]") }
    var groups by remember { mutableStateOf(prefs.getString("groups", "[]") ?: "[]") }
    var chats by remember { mutableStateOf(prefs.getString("chats", "[]") ?: "[]") }
    var selectedChat by remember { mutableStateOf("") }
    var muted by remember { mutableStateOf(false) }

    fun save(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    fun array(raw: String): List<String> = runCatching {
        val a = JSONArray(raw); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())
    fun persistFriends(v: List<String>) { friends = JSONArray(v).toString(); save("friends", friends) }
    fun persistGroups(v: List<String>) { groups = JSONArray(v).toString(); save("groups", groups) }
    fun persistChats(v: List<String>) { chats = JSONArray(v).toString(); save("chats", chats) }

    MaterialTheme(colorScheme = lightColorScheme(primary = Forest, secondary = Leaf, background = Bg)) {
        if (username.isBlank()) {
            AuthScreen(page, { page = it }, notice, { notice = it },
                onAuth = { name, email, password, register ->
                    val clean = email.trim().lowercase()
                    if (!android.util.Patterns.EMAIL_ADDRESS.matcher(clean).matches()) {
                        notice = "Enter a valid email address."
                    } else if (password.length < 8) {
                        notice = "Password must be at least 8 characters."
                    } else {
                        val accounts = prefs.getString("accounts", "{}") ?: "{}"
                        val db = runCatching { JSONObject(accounts) }.getOrElse { JSONObject() }
                        if (register && db.has(clean)) notice = "An account with this email already exists."
                        else if (!register && (!db.has(clean) || db.optString(clean) != hash(password))) {
                            notice = "Email or password is incorrect."
                        } else {
                            if (register) db.put(clean, hash(password))
                            prefs.edit().putString("accounts", db.toString()).putString("session", name.ifBlank { clean.substringBefore("@") }).apply()
                            username = name.ifBlank { clean.substringBefore("@") }
                            notice = ""
                        }
                    }
                },
                onGoogle = { notice = "Google sign-in needs the GreenCall OAuth Web Client ID and token-verification backend. Email login is available on this device meanwhile." }
            )
        } else if (selectedChat.isNotBlank()) {
            ConversationScreen(selectedChat, onBack = { selectedChat = "" })
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
                    0 -> ChatsScreen(username, array(chats), array(friends), { selectedChat = it }, { persistChats(array(chats) + it) }, pad)
                    1 -> GroupsScreen(array(groups), { persistGroups(array(groups) + it) }, pad)
                    2 -> FriendsScreen(array(friends), { persistFriends(array(friends) + it) }, pad)
                    else -> SettingsScreen(username, muted, { muted = it }, {
                        prefs.edit().remove("session").apply(); username = ""; page = "login"
                    }, pad)
                }
            }
        }
    }
}

@Composable
private fun AuthScreen(page: String, onPage: (String) -> Unit, notice: String, setNotice: (String) -> Unit,
                       onAuth: (String, String, String, Boolean) -> Unit, onGoogle: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val register = page == "register"
    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 26.dp), verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(68.dp).background(Forest, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Waves, null, tint = Leaf, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(26.dp))
        Text(if (register) "Create your account" else "Welcome to GreenCall", fontSize = 29.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(if (register) "Set up your profile to get started." else "Your conversations, wherever you are.", color = Muted)
        Spacer(Modifier.height(26.dp))
        if (register) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Display name") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
        }
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        if (notice.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(notice, color = Color(0xFFB33A2B), fontSize = 12.sp) }
        Spacer(Modifier.height(18.dp))
        Button(onClick = { onAuth(name, email, password, register) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
            Text(if (register) "Create account" else "Sign in", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onGoogle, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.AccountCircle, null); Spacer(Modifier.width(8.dp)); Text("Continue with Google")
        }
        TextButton(onClick = { setNotice(""); onPage(if (register) "login" else "register") }, Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (register) "Already have an account? Sign in" else "New to GreenCall? Create account", color = Forest)
        }
        Text("Local account storage on this device. Cloud sync is not connected yet.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun TopHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 22.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(Forest, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Waves, null, tint = Leaf)
            }
            Spacer(Modifier.width(10.dp))
            Text("GreenCall", color = Forest, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(8.dp).background(Leaf, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text("DEVICE MODE", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(20.dp))
        Text(title, color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Leaf, modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = Ink, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun ChatsScreen(user: String, chats: List<String>, friends: List<String>, open: (String) -> Unit, create: (String) -> Unit, pad: PaddingValues) {
    var show by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(pad)) {
        TopHeader("Your conversations", "Signed in as $user")
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AssistChip(onClick = {}, label = { Text("Offline-ready") }, leadingIcon = { Icon(Icons.Default.WifiOff, null) })
            AssistChip(onClick = {}, label = { Text("Private by design") }, leadingIcon = { Icon(Icons.Default.Lock, null) })
        }
        if (chats.isEmpty()) EmptyState(Icons.Default.ChatBubbleOutline, "No conversations yet", "Add a friend to start your first chat.")
        else LazyColumn { items(chats) { person ->
            Row(Modifier.fillMaxWidth().clickable { open(person) }.padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(person); Spacer(Modifier.width(12.dp)); Text(person, Modifier.weight(1f), color = Ink, fontWeight = FontWeight.SemiBold); Icon(Icons.Default.ChevronRight, null, tint = Muted)
            }; HorizontalDivider(color = Color(0xFFE8EEE9))
        } }
        Button(onClick = { show = true }, Modifier.fillMaxWidth().padding(18.dp)) { Icon(Icons.Default.Add, null); Text("New conversation") }
    }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text("Start a chat") },
        text = { Column { Text("Choose a saved friend or enter a username."); Spacer(Modifier.height(8.dp))
            if (friends.isNotEmpty()) friends.forEach { TextButton(onClick = { create(it); show = false }) { Text(it) } }
            OutlinedTextField(target, { target = it }, label = { Text("Friend username") }, singleLine = true)
        } }, confirmButton = { TextButton(onClick = { if (target.isNotBlank()) { create(target.trim()); show = false; target = "" } }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } })
}

@Composable
private fun FriendsScreen(friends: List<String>, add: (String) -> Unit, pad: PaddingValues) {
    var show by remember { mutableStateOf(false) }; var handle by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(pad)) {
        TopHeader("Friends", "Your saved contacts on this device.")
        if (friends.isEmpty()) EmptyState(Icons.Default.PersonAddAlt, "Your friend list is empty", "Add a username to save a contact.")
        else LazyColumn { items(friends) { f -> Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Avatar(f); Spacer(Modifier.width(12.dp)); Text(f, color = Ink, fontWeight = FontWeight.SemiBold) } } }
        Button(onClick = { show = true }, Modifier.fillMaxWidth().padding(18.dp)) { Icon(Icons.Default.PersonAdd, null); Text("Add friend") }
    }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text("Add a friend") },
        text = { Column { OutlinedTextField(handle, { handle = it }, label = { Text("Username") }, singleLine = true); Text("This saves a local contact. Sending a real friend request requires the online service.", color = Muted, fontSize = 11.sp) } },
        confirmButton = { TextButton(onClick = { val v = handle.trim().removePrefix("@"); if (v.isNotBlank() && !friends.contains(v)) add(v); handle = ""; show = false }) { Text("Add") } },
        dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } })
}

@Composable
private fun GroupsScreen(groups: List<String>, add: (String) -> Unit, pad: PaddingValues) {
    var show by remember { mutableStateOf(false) }; var title by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(pad)) {
        TopHeader("Groups", "Create spaces for your people.")
        if (groups.isEmpty()) EmptyState(Icons.Default.Groups, "No groups yet", "Your groups will appear here after you create one.")
        else LazyColumn { items(groups) { g -> Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Avatar(g); Spacer(Modifier.width(12.dp)); Text(g, color = Ink, fontWeight = FontWeight.SemiBold) } } }
        Button(onClick = { show = true }, Modifier.fillMaxWidth().padding(18.dp)) { Icon(Icons.Default.Add, null); Text("Create group") }
    }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text("New group") }, text = { OutlinedTextField(title, { title = it }, label = { Text("Group name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (title.isNotBlank() && !groups.contains(title.trim())) add(title.trim()); title = ""; show = false }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } })
}

@Composable
private fun Avatar(label: String) {
    Box(Modifier.size(44.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) {
        Text(label.take(1).uppercase(), color = Forest, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

@Composable
private fun ConversationScreen(person: String, onBack: () -> Unit) {
    var message by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(listOf<String>()) }
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }; Avatar(person); Spacer(Modifier.width(10.dp)); Text(person, color = Ink, fontWeight = FontWeight.Bold)
        }
        if (sent.isEmpty()) EmptyState(Icons.Default.ChatBubbleOutline, "Say hello", "Your messages will appear here.")
        else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp)) { items(sent) { Text(it, Modifier.padding(5.dp).wrapContentWidth(Alignment.End).background(Mint, RoundedCornerShape(14.dp)).padding(12.dp), color = Ink) } }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(message, { message = it }, Modifier.weight(1f), placeholder = { Text("Message") })
            IconButton(onClick = { if (message.isNotBlank()) { sent = sent + message.trim(); message = "" } }) { Icon(Icons.Default.Send, null, tint = Forest) }
        }
        Text("Local preview only · online message delivery is not connected", Modifier.padding(8.dp), color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun SettingsScreen(user: String, muted: Boolean, setMuted: (Boolean) -> Unit, signOut: () -> Unit, pad: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(pad)) {
        TopHeader("Settings", "Your GreenCall preferences")
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(16.dp)) {
                Text("Signed in", color = Muted, fontSize = 12.sp); Text(user, color = Ink, fontWeight = FontWeight.Bold)
            } }
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Mute walkie-talkie", color = Ink, fontWeight = FontWeight.SemiBold); Text("Save incoming voice clips instead of playing live.", color = Muted, fontSize = 11.sp) }
                Switch(muted, setMuted)
            } }
            Text("Offline contacts and groups are stored on this device. Internet sync, Google authentication, and live voice transport still need the GreenCall service configuration.", color = Muted, fontSize = 12.sp)
            OutlinedButton(onClick = signOut, Modifier.fillMaxWidth()) { Icon(Icons.Default.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out") }
        }
    }
}
