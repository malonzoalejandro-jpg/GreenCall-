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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

private fun parseArray(raw: String): List<String> = runCatching {
    val a = JSONArray(raw); (0 until a.length()).map { a.getString(it) }
}.getOrDefault(emptyList())

@Composable
private fun GreenCallApp(prefs: android.content.SharedPreferences) {
    var account by remember { mutableStateOf(prefs.getString("session", "") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    val scope = rememberCoroutineScope()
    var authPage by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var friends by remember { mutableStateOf(emptyList<String>()) }
    var groups by remember { mutableStateOf(emptyList<String>()) }
    var selected by remember { mutableStateOf("") }
    var muted by remember { mutableStateOf(false) }

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
                title = selected,
                storageKey = "messages_${account}_${selected}",
                prefs = prefs,
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
                    1 -> GroupsScreen(groups, friends, account, prefs, { selected = "Group: $it" }, ::saveGroups, pad)
                    2 -> FriendsScreen(friends, account, token, prefs, ::saveFriends, pad)
                    else -> SettingsScreen(account, muted, { muted = it }, {
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
    var search by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<JSONObject>()) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(pad)) {
        Header("Friends", "Find people by their display name.")
        if (friends.isEmpty()) EmptyState(Icons.Default.PersonSearch, "No friends added", "Tap Add friend and search their name.")
        else LazyColumn(Modifier.weight(1f)) {
            items(friends) { handle ->
                val display = prefs.getString("display_${currentUser}_${handle}", handle) ?: handle
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(display); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(display, color = Ink, fontWeight = FontWeight.SemiBold); Text("@$handle", color = Muted, fontSize = 11.sp) }
                    Icon(Icons.Default.CheckCircle, null, tint = Leaf)
                }
                HorizontalDivider(color = Color(0xFFE8EEE9))
            }
        }
        Button(onClick = { show = true }, Modifier.fillMaxWidth().padding(18.dp)) { Icon(Icons.Default.PersonAdd, null); Text("Add friend") }
    }
    if (show) AlertDialog(onDismissRequest = { show = false; search = ""; result = ""; results = emptyList() },
        title = { Text("Find a friend") },
        text = {
            Column {
                Text("Search using the name they registered with, not their @username.", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(search, { search = it; result = ""; results = emptyList() }, Modifier.fillMaxWidth(), label = { Text("Person's name") }, singleLine = true)
                Button(onClick = {
                    if (search.trim().length >= 2 && token.isNotBlank()) scope.launch {
                        searching = true
                        try {
                            val found = withContext(Dispatchers.IO) { GreenCallApi.searchUsers(token, search.trim()) }
                            results = (0 until found.length()).map { found.getJSONObject(it) }
                        } catch (error: Exception) {
                            result = error.message ?: "Search failed."
                        } finally { searching = false }
                    }
                }, enabled = !searching && search.trim().length >= 2) {
                    if (searching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Search")
                }
                results.filter { it.optString("username") !in friends }.forEach { person ->
                    val id = person.optString("id")
                    val handle = person.optString("username")
                    val display = person.optString("displayName")
                    Row(Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { GreenCallApi.addFriend(token, id) }
                                prefs.edit()
                                    .putString("display_${currentUser}_${handle}", display)
                                    .putString("friend_id_${currentUser}_${handle}", id)
                                    .apply()
                                save(friends + handle)
                                result = "$display added to your friends."
                                results = emptyList()
                            } catch (error: Exception) {
                                result = error.message ?: "Could not add this friend."
                            }
                        }
                    }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(display); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) { Text(display, color = Ink, fontWeight = FontWeight.SemiBold); Text("@$handle", color = Muted, fontSize = 11.sp) }
                        Text("Add", color = Forest, fontWeight = FontWeight.Bold)
                    }
                }
                if (result.isNotBlank()) Text(result, color = Forest, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { show = false; search = ""; result = ""; results = emptyList() }) { Text("Done") } })
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
private fun ConversationScreen(title: String, storageKey: String, prefs: android.content.SharedPreferences, onBack: () -> Unit) {
    var raw by remember(storageKey) { mutableStateOf(prefs.getString(storageKey, "[]") ?: "[]") }
    var message by remember { mutableStateOf("") }
    val messages = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
            Avatar(title); Spacer(Modifier.width(10.dp))
            Column { Text(title, color = Ink, fontWeight = FontWeight.Bold); Text("Conversation", color = Muted, fontSize = 11.sp) }
        }
        if (messages.length() == 0) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Default.ChatBubbleOutline, "Start the conversation", "Messages you write will be saved on this phone.")
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items((0 until messages.length()).map { messages.getJSONObject(it) }) { item ->
                    val mine = item.optBoolean("mine")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        Column(Modifier.widthIn(max = 290.dp).background(if (mine) Mint else Color.White, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(item.optString("text"), color = Ink, fontSize = 14.sp)
                            Text(if (mine) "You · saved locally" else title, color = Muted, fontSize = 9.sp)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(message, { message = it }, Modifier.weight(1f), placeholder = { Text("Write a message…") }, maxLines = 4)
            IconButton(onClick = {
                if (message.isNotBlank()) {
                    messages.put(JSONObject().put("text", message.trim()).put("mine", true).put("time", System.currentTimeMillis()))
                    raw = messages.toString()
                    prefs.edit().putString(storageKey, raw).apply()
                    message = ""
                }
            }) { Icon(Icons.Default.Send, null, tint = Forest) }
        }
        Text("Saved on this device · delivery to other phones needs the online service", Modifier.padding(horizontal = 12.dp, vertical = 5.dp), color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun GroupsScreen(groups: List<String>, friends: List<String>, currentUser: String, prefs: android.content.SharedPreferences, open: (String) -> Unit, save: (List<String>) -> Unit, pad: PaddingValues) {
    var show by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var selectedMembers by remember { mutableStateOf(setOf<String>()) }
    Column(Modifier.fillMaxSize().padding(pad)) {
        Header("Groups", "Create a space for your friends.")
        if (groups.isEmpty()) EmptyState(Icons.Default.Groups, "No groups yet", "Create a group and choose its members.")
        else LazyColumn(Modifier.weight(1f)) { items(groups) { group ->
            Row(Modifier.fillMaxWidth().clickable { open(group) }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Avatar(group); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(group, color = Ink, fontWeight = FontWeight.SemiBold); Text("Group conversation", color = Muted, fontSize = 10.sp) }; Icon(Icons.Default.ChevronRight, null, tint = Muted) }
        } }
        Button(onClick = { show = true }, Modifier.fillMaxWidth().padding(18.dp)) { Icon(Icons.Default.Add, null); Text("Create group") }
    }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text("Create a group") },
        text = { Column {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Group name") }, singleLine = true)
            Spacer(Modifier.height(8.dp)); Text("Add friends", fontWeight = FontWeight.SemiBold)
            if (friends.isEmpty()) Text("Add friends first to invite them.", color = Muted, fontSize = 12.sp)
            friends.forEach { friend -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(friend in selectedMembers, { checked -> selectedMembers = if (checked) selectedMembers + friend else selectedMembers - friend })
                Text("@$friend", color = Ink)
            } }
        } },
        confirmButton = { TextButton(onClick = {
            if (title.isNotBlank()) { val groupName = title.trim(); save(groups + groupName); prefs.edit().putString("group_members_" + currentUser + "_" + groupName, JSONArray(selectedMembers.toList()).toString()).apply() }
            title = ""; selectedMembers = emptySet(); show = false
        }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } })
}

@Composable
private fun SettingsScreen(user: String, muted: Boolean, setMuted: (Boolean) -> Unit, signOut: () -> Unit, pad: PaddingValues) {
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
            Text("Accounts, contacts, groups and messages currently stay on this device. Cross-device sync and live voice require the GreenCall server and nearby transport.", color = Muted, fontSize = 12.sp)
            OutlinedButton(onClick = signOut, Modifier.fillMaxWidth()) { Icon(Icons.Default.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out") }
        }
    }
}
